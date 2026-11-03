package elearning.ai

import elearning.jobs.JobQueue
import elearning.jobs.JobState
import elearning.jobs.JobStatus
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.readValue
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.RetryLaterException
import wasichai.core.data.RecordService
import wasichai.core.identity.AuthenticatedUser
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.TreeMap
import java.util.UUID

// lo que pide quien encola un paso de IA (ADR-0033 §5). model null: el doble determinista. inputs es el JSON del paso
data class AiStepRequest(
    val step: AgentStep,
    val courseId: UUID,
    val unitId: UUID? = null,
    val versionId: UUID? = null,
    val objective: String,
    val inputs: Any,
    val sources: List<SourceDigest> = emptyList(),
    val model: ModelChoice?,
    val promptVersion: String,
    val templateVersion: String,
) {
    init {
        require(objective.isNotBlank()) { "objective must not be blank" }
        require(promptVersion.isNotBlank() && templateVersion.isNotBlank()) { "the prompt and template versions must not be blank" }
    }
}

// los datos del trabajo en lms.trabajo: lo pedido, quién lo pidió y su clave
data class AiStepPayload(
    val requester: UUID,
    val step: AgentStep,
    val courseId: UUID,
    val unitId: UUID?,
    val versionId: UUID?,
    val objective: String,
    val inputs: JsonNode,
    val sources: List<SourceDigest>,
    val model: ModelChoice?,
    val promptVersion: String,
    val templateVersion: String,
    val key: String,
)

// un trabajo de IA como lo ve quien lo pidió (docs/api/lms.md). executionId: la ejecución que lo resolvió, cuando terminó bien
data class AiJob(
    val id: UUID,
    val step: AgentStep,
    val status: JobState,
    val attempts: Int,
    val maxAttempts: Int,
    val error: String?,
    val executionId: UUID?,
    val createdAt: Instant,
    val finishedAt: Instant?,
)

// encolar, leer y cancelar los pasos de IA (ADR-0033 §5 y §9, RF-IA-04). Encolar exige GENERAR y que quien llama vea el
// curso; con la misma clave mientras el trabajo no termina devuelve el mismo, y si ya hay una ejecución que la resolvió, el
// trabajo nuevo la reutiliza sin llamar al proveedor (AiStepRunner). Leer y cancelar exigen GENERAR y son solo de quien lo
// pidió: el de otra persona, también frente al administrador, es 404, como la importación
@Service
class AiJobs(
    private val permissions: AiPermissions,
    private val records: RecordService,
    private val queue: JobQueue,
    private val store: AgentExecutionStore,
    private val limits: AiLimits,
) {
    suspend fun enqueue(request: AiStepRequest): UUID {
        val user = permissions.requireToEnqueue(AiAction.GENERAR)
        // como quien llama: un curso que no ve es 404
        records.get(COURSE, request.courseId)
        val organization = user.organizationId
        val inputs = MAPPER.valueToTree<JsonNode>(request.inputs)
        val type = typeOf(request.step)
        val digest = keyOf(request)
        queue.active(organization, type, digest)?.let { return it }
        // lo ya resuelto no llama al proveedor: no cuenta para los límites
        if (store.succeededWithKey(organization, digest) == null) {
            requireConcurrency(organization)
            if (budgetExhausted(organization, request.courseId)) {
                throw RetryLaterException(
                    "The AI budget of this course is spent: ask an administrator to raise lms.ai.limits.course-budget",
                    limits.budgetRetryAfter,
                    status = HttpStatus.TOO_MANY_REQUESTS,
                )
            }
        }
        val payload =
            AiStepPayload(
                requester = user.userId,
                step = request.step,
                courseId = request.courseId,
                unitId = request.unitId,
                versionId = request.versionId,
                objective = request.objective,
                inputs = inputs,
                sources = request.sources,
                model = request.model,
                promptVersion = request.promptVersion,
                templateVersion = request.templateVersion,
                key = digest,
            )
        return queue.enqueue(organization, type, MAPPER.writeValueAsString(payload), key = digest, requester = user.userId)
    }

    suspend fun find(id: UUID): AiJob {
        val user = permissions.requireToEnqueue(AiAction.GENERAR)
        return view(own(user, id))
    }

    // pendiente o en curso, queda CANCELLED: el manejador lo ve entre llamadas al modelo y no deja borrador. cancelar otra vez
    // es inocuo; uno ya terminado de otra forma es 409
    suspend fun cancel(id: UUID): AiJob {
        val user = permissions.requireToEnqueue(AiAction.GENERAR)
        own(user, id)
        if (!queue.cancel(user.organizationId, id)) {
            val state = queue.find(user.organizationId, id)?.state ?: throw NotFoundException("AI job $id not found")
            if (state != JobState.CANCELLED) throw ConflictException("AI job $id is already over ($state)")
        }
        return view(queue.find(user.organizationId, id) ?: throw NotFoundException("AI job $id not found"))
    }

    // el presupuesto del curso, si hay, está gastado
    suspend fun budgetExhausted(
        organization: UUID,
        course: UUID,
    ): Boolean {
        val budget = limits.courseBudget ?: return false
        return store.spent(organization, course, limits.currency) >= budget
    }

    // un tope blando, como el de la cola por persona: dos peticiones a la vez pueden pasarlo por uno
    private suspend fun requireConcurrency(organization: UUID) {
        val active = queue.activeCount(organization, TYPES)
        if (active >= limits.maxActivePerOrganization) {
            throw RetryLaterException(
                "The organization already has $active AI steps waiting or running: try again when one ends",
                limits.concurrencyRetryAfter,
                status = HttpStatus.TOO_MANY_REQUESTS,
            )
        }
    }

    private suspend fun own(
        user: AuthenticatedUser,
        id: UUID,
    ): JobStatus {
        val status = queue.find(user.organizationId, id)
        if (status == null || status.type !in TYPES || payloadOf(status.payload).requester != user.userId) {
            throw NotFoundException("AI job $id not found")
        }
        return status
    }

    private fun view(status: JobStatus) =
        AiJob(
            id = status.id,
            step = stepOf(status.type),
            status = status.state,
            attempts = status.attempts,
            maxAttempts = status.maxAttempts,
            error = status.error,
            executionId = status.result?.let { MAPPER.readTree(it)["executionId"]?.asString()?.let(UUID::fromString) },
            createdAt = status.createdAt,
            finishedAt = status.finishedAt,
        )

    companion object {
        private const val COURSE = "curso"
        private const val TYPE_PREFIX = "ai."

        internal val MAPPER = AgentExecutionStore.MAPPER

        // el tipo de trabajo de cada paso; un JobHandler por tipo
        fun typeOf(step: AgentStep): String = TYPE_PREFIX + step.name.lowercase()

        fun stepOf(type: String): AgentStep = AgentStep.valueOf(type.removePrefix(TYPE_PREFIX).uppercase())

        val TYPES: List<String> = AgentStep.entries.map(::typeOf)

        fun payloadOf(json: String): AiStepPayload = MAPPER.readValue<AiStepPayload>(json)

        // la clave de idempotencia (ADR-0033 §5, docs/ai §5): el hash del paso, su ámbito, el objetivo y las entradas, los
        // hashes de las fuentes, las versiones de instrucciones y plantilla y el modelo con sus parámetros. JSON canónico:
        // el orden de las propiedades y de las fuentes no cambia la clave
        fun keyOf(request: AiStepRequest): String {
            val key =
                mapOf(
                    "step" to request.step.name,
                    "scope" to listOf(request.courseId.toString(), request.unitId?.toString(), request.versionId?.toString()),
                    "objective" to request.objective,
                    "inputs" to MAPPER.convertValue(MAPPER.valueToTree<JsonNode>(request.inputs), Any::class.java),
                    "sources" to request.sources.sortedWith(compareBy<SourceDigest>({ it.ref }, { it.sha256 })).map { listOf(it.ref, it.sha256) },
                    "prompt" to request.promptVersion,
                    "template" to request.templateVersion,
                    "model" to
                        request.model?.let {
                            mapOf(
                                "provider" to it.provider,
                                "model" to it.model,
                                "maxTokens" to it.settings.maxTokens,
                                "temperature" to it.settings.temperature,
                            )
                        },
                )
            return request.step.name + ":" + sha256(canonical(key))
        }

        private fun canonical(value: Any?): String = MAPPER.writeValueAsString(sorted(value))

        private fun sorted(value: Any?): Any? =
            when (value) {
                is Map<*, *> -> TreeMap<String, Any?>().apply { value.forEach { (k, v) -> put(k.toString(), sorted(v)) } }
                is Collection<*> -> value.map(::sorted)
                else -> value
            }

        private fun sha256(text: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
    }
}
