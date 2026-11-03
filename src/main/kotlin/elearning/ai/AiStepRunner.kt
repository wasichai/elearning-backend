package elearning.ai

import elearning.jobs.Job
import elearning.jobs.JobCancelled
import elearning.jobs.JobQueue
import elearning.jobs.PermanentJobFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.util.UUID

// la política de un paso de IA en la cola (ADR-0033 §5, §6 y §9, RF-IA-04). El JobHandler de cada paso la llama con su
// generador (AiModelCall) y su borrador; aquí se decide todo lo demás, igual para todos los pasos:
//
// - idempotencia: si una ejecución ya resolvió la clave, no se llama al proveedor. Si fue de este mismo trabajo (un intento
//   anterior terminó la llamada pero no entregó), se entrega otra vez; si fue de otro, el trabajo la devuelve sin borrador
//   nuevo: el borrador se hizo con aquel
// - reintentos: 408, 429 y 5xx del proveedor (y cualquier otra excepción) vuelven a la cola, con su espera creciente y su
//   tope; otro 4xx, una salida inválida, el presupuesto agotado o la pérdida de GENERAR son PermanentJobFailure. Cada intento
//   que llamó al modelo deja su ejecución con su consumo: un reintento no repite el coste de otro
// - cancelación: se mira antes de llamar y al volver; cancelado, la ejecución se cierra CANCELLED (con lo que costó) y no
//   hay borrador. El borrador y el cierre del trabajo van en una transacción: si la cancelación llega entre medias, complete
//   no escribe y el borrador se deshace
// - límites: el presupuesto del curso se vuelve a mirar antes de cada llamada
// - lectura: lo que el paso lee antes de llamar (sus fuentes, la salida de un paso anterior) se lee después de todo lo
//   anterior, así que lo ya resuelto o cancelado no gasta lecturas. Un fallo al leer sale tal cual, sin ejecución: no se
//   llamó a ningún modelo. Las fuentes leídas, con su hash, son las de la ejecución
@Service
class AiStepRunner(
    private val log: AgentExecutionLog,
    private val store: AgentExecutionStore,
    private val queue: JobQueue,
    private val permissions: AiPermissions,
    private val jobs: AiJobs,
    private val transactions: TransactionalOperator,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // draft escribe el borrador con la ejecución que terminó bien, dentro de la transacción que cierra el trabajo. devuelve el
    // resultado del trabajo: {"executionId", "reused"}. Todo corre con la marca AiWork: el borrador no aprueba ni publica
    suspend fun run(
        job: Job,
        generator: AiModelCall,
        draft: suspend (AgentExecution) -> Unit = {},
    ): String = run(job, { request -> AiStepInput(Unit, request.sources) }, { prompt, _ -> generator.call(prompt) }, draft)

    // un paso que lee antes de llamar: read le da al generador su material y las fuentes que leyó. action: la acción que quien
    // lo pidió debe conservar al ejecutar; GENERAR salvo la opinión de consistencia, que pide quien valida (VALIDAR)
    suspend fun <M> run(
        job: Job,
        read: suspend (AiStepPayload) -> AiStepInput<M>,
        generator: AiStepGenerator<M>,
        draft: suspend (AgentExecution) -> Unit = {},
        action: AiAction = AiAction.GENERAR,
    ): String = withContext(AiWork()) { execute(job, read, generator, draft, action) }

    private suspend fun <M> execute(
        job: Job,
        read: suspend (AiStepPayload) -> AiStepInput<M>,
        generator: AiStepGenerator<M>,
        draft: suspend (AgentExecution) -> Unit,
        action: AiAction,
    ): String {
        val request = AiJobs.payloadOf(job.payload)
        val organization = job.organizationId
        // antes de gastar: quien lo pidió puede todavía generar, o validar (ADR-0022 §4)
        permissions.requireStillAllowed(organization, request.requester, action)
        store.succeededWithKey(organization, request.key)?.let { done ->
            return deliver(job, done, if (done.jobId == job.id) draft else null)
        }
        // un intento anterior cuya réplica se perdió dejó su ejecución abierta
        for (lost in store.openOfJob(organization, job.id)) {
            log.finish(organization, lost, AgentExecutionOutcome(AgentExecutionStatus.FAILED, error = LOST))
        }
        if (queue.cancelled(job)) throw JobCancelled(job)
        if (jobs.budgetExhausted(organization, request.courseId)) throw PermanentJobFailure("The AI budget of this course is spent")
        val input = read(request)
        if (queue.cancelled(job)) throw JobCancelled(job)
        val id = log.start(execution(job, request, input.sources))
        val reply =
            try {
                generator.generate(
                    AiPrompt(request.step, request.objective, request.inputs, input.sources, request.model, request.promptVersion, request.templateVersion),
                    input.material,
                )
            } catch (e: AiProviderFailure) {
                val error = "The AI provider answered ${e.status}: ${e.message}"
                close(organization, id, AgentExecutionOutcome(AgentExecutionStatus.FAILED, error = error))
                // con el estado en el mensaje: es el error que lee quien lo pidió mientras espera el reintento
                if (e.transient) throw IllegalStateException(error, e)
                throw PermanentJobFailure(error, e)
            } catch (e: AiInvalidOutput) {
                val error = "The AI output is not valid: ${e.message}"
                close(organization, id, AgentExecutionOutcome(AgentExecutionStatus.INVALID_OUTPUT, validations = e.validations, error = error, usage = e.usage))
                throw PermanentJobFailure(error, e)
            } catch (e: CancellationException) {
                // tiempo límite o apagado: la ejecución no se queda abierta
                withContext(NonCancellable) { close(organization, id, AgentExecutionOutcome(AgentExecutionStatus.FAILED, error = INTERRUPTED)) }
                throw e
            } catch (e: Exception) {
                close(organization, id, AgentExecutionOutcome(AgentExecutionStatus.FAILED, error = e.message ?: e.javaClass.simpleName))
                throw e
            }
        if (queue.cancelled(job)) {
            close(organization, id, AgentExecutionOutcome(AgentExecutionStatus.CANCELLED, error = CANCELLED, usage = reply.usage))
            throw JobCancelled(job)
        }
        val run = log.finish(organization, id, AgentExecutionOutcome(AgentExecutionStatus.SUCCEEDED, reply.output, reply.validations, usage = reply.usage))
        return deliver(job, run, draft)
    }

    private suspend fun deliver(
        job: Job,
        run: AgentExecution,
        draft: (suspend (AgentExecution) -> Unit)?,
    ): String {
        val result = AiJobs.MAPPER.writeValueAsString(mapOf("executionId" to run.id.toString(), "reused" to (run.jobId != job.id)))
        transactions.executeAndAwait {
            draft?.invoke(run)
            if (!queue.complete(job, result)) {
                if (queue.cancelled(job)) throw JobCancelled(job)
                error("the AI job ${job.id} lost its claim: its draft is rolled back")
            }
            result
        }
        return result
    }

    // cerrar no debe tapar el error del paso: si la base no responde, el reclamo caduca y el siguiente intento la cierra
    private suspend fun close(
        organization: UUID,
        id: UUID,
        outcome: AgentExecutionOutcome,
    ) {
        try {
            log.finish(organization, id, outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("agent execution {} could not be closed as {}", id, outcome.status, e)
        }
    }

    private fun execution(
        job: Job,
        request: AiStepPayload,
        sources: List<SourceDigest>,
    ) = NewAgentExecution(
        organizationId = job.organizationId,
        jobId = job.id,
        step = request.step,
        requestedBy = request.requester,
        courseId = request.courseId,
        unitId = request.unitId,
        versionId = request.versionId,
        objective = request.objective,
        inputs = request.inputs,
        sources = sources,
        generator = if (request.model == null) Generator.DETERMINISTIC else Generator.MODEL,
        model = request.model,
        promptVersion = request.promptVersion,
        templateVersion = request.templateVersion,
        idempotencyKey = request.key,
        attempt = job.attempt,
    )

    private companion object {
        const val LOST = "the attempt that ran this execution was lost"
        const val INTERRUPTED = "the attempt was interrupted (time limit or shutdown)"
        const val CANCELLED = "the job was cancelled"
    }
}
