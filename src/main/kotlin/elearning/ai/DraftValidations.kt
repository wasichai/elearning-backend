package elearning.ai

import elearning.common.ref
import elearning.jobs.Job
import elearning.jobs.JobHandler
import elearning.jobs.JobQueue
import elearning.jobs.JobState
import elearning.jobs.JobStatus
import elearning.jobs.PermanentJobFailure
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.readValue
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.time.Instant
import java.util.UUID

data class ValidationRequest(
    val versionId: String? = null,
)

// el trabajo de validación como lo ve quien lo pidió. executionId: la ejecución AgentRun con el informe, al terminar
data class ValidationJob(
    val id: UUID,
    val versionId: UUID,
    val status: JobState,
    val error: String?,
    val executionId: UUID?,
    val createdAt: Instant,
    val finishedAt: Instant?,
)

// el último informe de una versión: current dice si sigue correspondiendo al contenido (el mismo hash). opinion: la de
// consistencia contenido-evaluación de un modelo (E34.2), marcada como tal; nunca cambia report.blocking
data class DraftValidationView(
    val executionId: UUID,
    val versionId: UUID,
    val requestedBy: UUID,
    val validatedAt: Instant?,
    val current: Boolean,
    val report: JsonNode,
    val opinion: OpinionView,
)

// lo que la cola guarda: quién la pidió y de qué versión
data class ValidationPayload(
    val requester: UUID,
    val versionId: UUID,
    val courseId: UUID,
)

data class ValidationOutcome(
    val executionId: UUID,
)

// lanzar y leer la validación por código de un borrador (RF-IA-05, docs/api/lms.md). Lanzar exige VALIDAR (AiPermissions,
// 403), ver la versión (404) y que sea un borrador (409); nadie valida su propio borrador (403, separación de deberes como
// en la revisión). Corre en la cola: la misma versión mientras el trabajo no termina es el mismo trabajo. El trabajo es
// solo de quien lo pidió (404 para cualquier otro). El informe lo leen, si ven la versión, su autor, su revisor asignado y
// quien tiene VALIDAR; el resto, 403. Nunca por rol
@Service
class DraftValidations(
    private val permissions: AiPermissions,
    private val currentUser: CurrentUser,
    private val metadata: MetadataService,
    private val records: RecordService,
    private val queue: JobQueue,
    private val store: DraftValidationStore,
    private val executions: AgentExecutionStore,
    private val snapshots: DraftSnapshots,
    private val opinions: ConsistencyReviews,
) {
    suspend fun start(request: ValidationRequest): ValidationJob {
        val user = permissions.requireToEnqueue(AiAction.VALIDAR)
        val id =
            request.versionId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw ValidationException("Invalid version", "versionId", "must be a course version id")
        val version = visible(id)
        if (version.attributes[AUTHOR]?.toString() == user.userId.toString()) throw ForbiddenException("Nobody validates their own draft")
        if (version.attributes[STATE]?.toString() != DRAFT) throw ConflictException("Only a draft is validated")
        val course = UUID.fromString(version.attributes.ref(COURSE)!!)
        val payload = ValidationPayload(user.userId, id, course)
        val job = queue.enqueue(user.organizationId, JOB_TYPE, MAPPER.writeValueAsString(payload), key = id.toString(), requester = user.userId)
        return view(queue.find(user.organizationId, job)!!)
    }

    suspend fun job(id: UUID): ValidationJob {
        val user = currentUser.require()
        val status = queue.find(user.organizationId, id)?.takeIf { it.type == JOB_TYPE } ?: throw NotFoundException(JOB_NOT_FOUND)
        if (MAPPER.readValue<ValidationPayload>(status.payload).requester != user.userId) throw NotFoundException(JOB_NOT_FOUND)
        return view(status)
    }

    suspend fun latest(versionId: UUID): DraftValidationView {
        val user = currentUser.require()
        val version = visible(versionId)
        val me = user.userId.toString()
        val involved = version.attributes[AUTHOR]?.toString() == me || version.attributes[REVIEWER]?.toString() == me
        if (!involved && !currentUser.hasPermission(user, AiAction.VALIDAR.name, metadata.definitionOf(VERSION).obj.id)) {
            throw ForbiddenException("The validation report is read by the author, the assigned reviewer or whoever validates")
        }
        val run =
            store.latestValidation(user.organizationId, versionId)?.let { executions.find(user.organizationId, it) }
                ?: throw NotFoundException("This version has no validation report")
        val report = run.validations ?: throw NotFoundException("This version has no validation report")
        val current = report.path("contentHash").asString() == DraftValidator.hash(snapshots.of(version))
        val opinion = opinions.view(user.organizationId, run.output?.get(OPINION))
        return DraftValidationView(run.id, versionId, run.requestedBy, run.finishedAt, current, report, opinion)
    }

    // una versión que quien llama no ve no existe (404)
    private suspend fun visible(id: UUID): RecordResponse =
        try {
            records.get(VERSION, id)
        } catch (_: ForbiddenException) {
            throw NotFoundException(VERSION_NOT_FOUND)
        }

    private fun view(status: JobStatus): ValidationJob {
        val payload = MAPPER.readValue<ValidationPayload>(status.payload)
        return ValidationJob(
            id = status.id,
            versionId = payload.versionId,
            status = status.state,
            error = status.error,
            executionId = status.result?.let { MAPPER.readValue<ValidationOutcome>(it).executionId },
            createdAt = status.createdAt,
            finishedAt = status.finishedAt,
        )
    }

    companion object {
        const val JOB_TYPE = "ai-validation"
        const val VERSION = "version_curso"
        const val COURSE = "curso"
        const val AUTHOR = "autor_id"
        const val REVIEWER = "revisor_id"
        const val STATE = "estado"
        const val DRAFT = "BORRADOR"

        // dónde deja la validación, en su salida, lo que pidió de la opinión (OpinionRequest)
        const val OPINION = "consistencyReview"
        private const val JOB_NOT_FOUND = "Validation not found"
        private const val VERSION_NOT_FOUND = "Version not found"
        internal val MAPPER = AgentExecutionStore.MAPPER
    }
}

// la validación como trabajo de la cola: vuelve a mirar VALIDAR con los roles guardados de quien la pidió (ADR-0022), lee
// el borrador como la plataforma, lo valida y deja el informe en una ejecución AgentRun (paso VALIDATION, generator
// DETERMINISTIC, sin modelo ni coste) con la versión en version_curso_id. Una validación con problemas bloqueantes también
// termina SUCCEEDED: lo que falla es el borrador, no la validación. Después pide la opinión de consistencia (E34.2), que es
// otro trabajo con su propia ejecución, o deja dicho por qué se omite; lo que pidió queda en output.consistencyReview
@Component
class DraftValidationJobHandler(
    private val permissions: AiPermissions,
    private val records: RecordService,
    private val snapshots: DraftSnapshots,
    private val log: AgentExecutionLog,
    private val opinions: ConsistencyReviews,
) : JobHandler {
    override val type = DraftValidations.JOB_TYPE

    // con la marca AiWork, como todo trabajo de IA: no aprueba ni publica
    override suspend fun handle(job: Job): String? = withContext(AiWork()) { validate(job) }

    private suspend fun validate(job: Job): String {
        val payload = DraftValidations.MAPPER.readValue<ValidationPayload>(job.payload)
        permissions.requireStillAllowed(job.organizationId, payload.requester, AiAction.VALIDAR)
        val report =
            records.asPlatform(job.organizationId, "validation:${job.id}") {
                val version =
                    try {
                        records.get(DraftValidations.VERSION, payload.versionId)
                    } catch (_: NotFoundException) {
                        throw PermanentJobFailure("The version ${payload.versionId} no longer exists")
                    }
                DraftValidator.validate(snapshots.of(version))
            }
        val run =
            log.start(
                NewAgentExecution(
                    organizationId = job.organizationId,
                    jobId = job.id,
                    step = AgentStep.VALIDATION,
                    requestedBy = payload.requester,
                    courseId = payload.courseId,
                    versionId = payload.versionId,
                    objective = "Validate by code the draft course version ${payload.versionId} (RF-IA-05)",
                    inputs = mapOf("versionId" to payload.versionId, "contentHash" to report.contentHash),
                    generator = Generator.DETERMINISTIC,
                    model = null,
                    promptVersion = DraftValidator.VERSION,
                    templateVersion = DraftValidator.VERSION,
                    idempotencyKey = "validation:${payload.versionId}:${report.contentHash}",
                    attempt = job.attempt,
                ),
            )
        val opinion = opinions.request(job.organizationId, payload.requester, payload.courseId, payload.versionId, report.contentHash)
        val summary =
            mapOf(
                "blocking" to report.blocking,
                "problemCount" to report.problemCount,
                "problemsBySeverity" to report.problemsBySeverity,
                DraftValidations.OPINION to opinion,
            )
        log.finish(job.organizationId, run, AgentExecutionOutcome(AgentExecutionStatus.SUCCEEDED, output = summary, validations = report))
        return DraftValidations.MAPPER.writeValueAsString(ValidationOutcome(run))
    }
}
