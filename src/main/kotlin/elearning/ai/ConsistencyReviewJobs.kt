package elearning.ai

import elearning.jobs.Job
import elearning.jobs.JobHandler
import elearning.jobs.JobQueue
import elearning.jobs.JobState
import elearning.jobs.PermanentJobFailure
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import wasichai.core.common.NotFoundException
import wasichai.core.common.RetryLaterException
import wasichai.core.data.RecordService
import java.util.UUID

// SKIPPED: no se pidió (reason); PENDING: en la cola; DONE: la opinión está; FAILED: se pidió y no salió
enum class OpinionStatus { SKIPPED, PENDING, DONE, FAILED }

// por qué se omitió: sin proveedor, con el presupuesto del curso gastado, con la cola de quien validó o los pasos de IA de la
// organización llenos, o una validación anterior a E34.2
enum class OpinionSkipReason { NO_PROVIDER, BUDGET_SPENT, QUEUE_FULL, NOT_REQUESTED }

// lo que la validación deja en su salida (output.consistencyReview): si pidió la opinión, con qué trabajo y con qué clave,
// o por qué no la pidió
data class OpinionRequest(
    val status: OpinionStatus,
    val reason: OpinionSkipReason? = null,
    val jobId: UUID? = null,
    val key: String? = null,
)

// la opinión como la lee el informe (DraftValidationView.opinion): siempre marcada como opinión y nunca bloqueante
data class OpinionView(
    val status: OpinionStatus,
    val reason: OpinionSkipReason? = null,
    val executionId: UUID? = null,
    val provider: String? = null,
    val model: String? = null,
    val verdict: ConsistencyVerdict? = null,
    val summary: String? = null,
    val findings: List<ConsistencyFinding> = emptyList(),
    val error: String? = null,
) {
    val kind: String = ConsistencyOpinion.KIND
    val blocking: Boolean = false
}

// pedir y leer la opinión de consistencia de un borrador validado (E34.2). La pide la validación (DraftValidationJobHandler)
// al terminar su informe, como trabajo ai.consistency_review aparte, con su propia ejecución AgentRun: su fallo no toca el
// informe ni su resultado. Con el modelo de la clave consistency (uno barato, configurable) y su max_tokens; sin proveedor,
// con el presupuesto del curso gastado o con la organización en su tope de pasos de IA a la vez (lms.ai.limits, como al
// encolar cualquier otro paso: la validación no lo elude, revisión de seguridad de la F5, #302) se omite y el informe lo
// dice. La clave de idempotencia lleva el hash del contenido: validar otra vez lo mismo reutiliza la opinión sin volver a
// pagarla, y eso no cuenta para el tope
@Service
class ConsistencyReviews(
    private val queue: JobQueue,
    private val jobs: AiJobs,
    private val models: AiStepModels,
    private val executions: AgentExecutionStore,
) {
    suspend fun request(
        organization: UUID,
        requester: UUID,
        course: UUID,
        version: UUID,
        contentHash: String,
    ): OpinionRequest {
        val model = models.choice(MODEL_KEY) ?: return OpinionRequest(OpinionStatus.SKIPPED, OpinionSkipReason.NO_PROVIDER)
        if (jobs.budgetExhausted(organization, course)) return OpinionRequest(OpinionStatus.SKIPPED, OpinionSkipReason.BUDGET_SPENT)
        val inputs = ConsistencyReviewInputs(version.toString(), contentHash)
        val request =
            AiStepRequest(
                step = AgentStep.CONSISTENCY_REVIEW,
                courseId = course,
                versionId = version,
                objective = ConsistencyReviewPrompt.objective(version.toString()),
                inputs = inputs,
                model = model,
                promptVersion = ConsistencyReviewPrompt.VERSION,
                templateVersion = ConsistencyReviewPrompt.TEMPLATE,
            )
        val key = AiJobs.keyOf(request)
        val type = AiJobs.typeOf(request.step)
        // lo ya resuelto o ya en la cola no llama otra vez al proveedor: solo un trabajo nuevo cuenta para el tope
        if (executions.succeededWithKey(organization, key) == null && queue.active(organization, type, key) == null && jobs.concurrencyFull(organization)) {
            return OpinionRequest(OpinionStatus.SKIPPED, OpinionSkipReason.QUEUE_FULL)
        }
        val payload =
            AiStepPayload(
                requester = requester,
                step = request.step,
                courseId = course,
                unitId = null,
                versionId = version,
                objective = request.objective,
                inputs = AiJobs.MAPPER.valueToTree(inputs),
                sources = emptyList(),
                model = model,
                promptVersion = request.promptVersion,
                templateVersion = request.templateVersion,
                key = key,
            )
        val job =
            try {
                queue.enqueue(organization, type, AiJobs.MAPPER.writeValueAsString(payload), key = key, requester = requester)
            } catch (_: RetryLaterException) {
                // el tope por persona de la cola: la validación ya terminó, la opinión no la hace fallar
                return OpinionRequest(OpinionStatus.SKIPPED, OpinionSkipReason.QUEUE_FULL)
            }
        return OpinionRequest(OpinionStatus.PENDING, jobId = job, key = key)
    }

    // requested: output.consistencyReview de la validación (null en una anterior a E34.2)
    suspend fun view(
        organization: UUID,
        requested: JsonNode?,
    ): OpinionView {
        if (requested == null || requested.isNull || requested.isMissingNode) return OpinionView(OpinionStatus.SKIPPED, OpinionSkipReason.NOT_REQUESTED)
        val request = AiJobs.MAPPER.treeToValue(requested, OpinionRequest::class.java)
        if (request.status == OpinionStatus.SKIPPED || request.key == null) return OpinionView(OpinionStatus.SKIPPED, request.reason)
        // por la clave: la ejecución de este trabajo o la de uno anterior con el mismo contenido, que este reutilizó
        executions.succeededWithKey(organization, request.key)?.let { return done(it) }
        val job = request.jobId?.let { queue.find(organization, it) }
        return when (job?.state) {
            JobState.QUEUED, JobState.RUNNING -> OpinionView(OpinionStatus.PENDING)
            else -> OpinionView(OpinionStatus.FAILED, error = job?.error ?: "the opinion did not finish")
        }
    }

    private fun done(run: AgentExecution): OpinionView {
        val output = run.output
        return OpinionView(
            status = OpinionStatus.DONE,
            executionId = run.id,
            provider = run.provider,
            model = run.model,
            verdict = output?.path("verdict")?.asString()?.let(ConsistencyVerdict::valueOf),
            summary = output?.path("summary")?.asString(),
            findings =
                output?.path("findings")?.values()?.map { finding ->
                    ConsistencyFinding(finding.get("origin")?.takeUnless { it.isNull }?.asString(), finding.path("detail").asString())
                } ?: emptyList(),
        )
    }

    private companion object {
        // la clave del modelo y de max_tokens del paso en lms.ai.models y lms.ai.max-tokens
        const val MODEL_KEY = "consistency"
    }
}

// el trabajo ai.consistency_review: lee el borrador como la plataforma, comprueba que es el que se validó (el mismo hash),
// pide la opinión al modelo y la comprueba por código. Lo demás (permisos al ejecutar, idempotencia, reintentos, cancelación,
// presupuesto y AgentRun con su coste) es de AiStepRunner, con VALIDAR en lugar de GENERAR: la pide quien valida. No escribe
// ningún registro: la opinión es la salida de su ejecución, que el informe lee por la clave
@Component
class ConsistencyReviewJobHandler(
    private val runner: AiStepRunner,
    private val records: RecordService,
    private val snapshots: DraftSnapshots,
    private val reviewer: ConsistencyReviewer,
) : JobHandler {
    override val type = AiJobs.typeOf(AgentStep.CONSISTENCY_REVIEW)

    override suspend fun handle(job: Job): String =
        runner.run(
            job,
            read = { request ->
                val inputs = ConsistencyReviewInputs.of(request.inputs)
                val snapshot =
                    records.asPlatform(job.organizationId, "consistency-review:${job.id}") {
                        val version =
                            try {
                                records.get(DraftValidations.VERSION, UUID.fromString(inputs.versionId))
                            } catch (_: NotFoundException) {
                                throw PermanentJobFailure("The version ${inputs.versionId} no longer exists")
                            }
                        snapshots.of(version)
                    }
                if (DraftValidator.hash(snapshot) != inputs.contentHash) {
                    throw PermanentJobFailure("The draft changed after its validation: a new validation asks for a new opinion")
                }
                AiStepInput(snapshot, emptyList())
            },
            generator = { prompt, snapshot ->
                val reply = reviewer.review(prompt, snapshot)
                ConsistencyReviewCheck.checked(reply.value, snapshot, DraftValidator.hash(snapshot), reply.usage)
            },
            action = AiAction.VALIDAR,
        )
}
