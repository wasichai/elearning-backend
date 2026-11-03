package elearning.importer

import elearning.jobs.JobQueue
import elearning.jobs.JobState
import elearning.jobs.JobStatus
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import wasichai.core.common.Actions
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordService
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.time.Instant
import java.util.UUID

// el contrato del importador (docs/api/lms.md, ADR-0018 §5 y §6)

data class ImportRequest(
    // owner/repo@sha:ruta del maestro
    val source: String? = null,
    val courseId: String? = null,
)

data class ImportSource(
    val repository: String,
    // null: cualquier commit del repositorio
    val sha: String?,
)

data class ImportJob(
    val id: String,
    // PENDIENTE · EN_CURSO · HECHO · FALLIDO
    val status: String,
    val error: String?,
    val versionId: String?,
    val report: ImportReport?,
    // lo pedido (E30.2): owner/repo@sha:ruta del maestro y el curso
    val source: String,
    val courseId: String,
    val createdAt: Instant,
    val finishedAt: Instant?,
)

data class ImportReport(
    val source: String,
    val files: Int,
    val includes: Int,
    val sections: Int,
    val images: Int,
    val units: Int,
    val lessons: Int,
    // los problemas son problemCount; la lista se recorta a MAX_PROBLEMS
    val problemCount: Int,
    val problems: List<ImportProblem>,
    // todos los problemas por gravedad, también los que la lista recorta. null en los informes guardados antes (E30.1)
    val problemsBySeverity: Map<ProblemSeverity, Int>? = null,
) {
    // el informe con la gravedad de cada problema; uno guardado antes la deriva al leerlo, y sus totales, de su lista
    fun withSeverity(): ImportReport {
        val graded = problems.map { it.copy(severity = ProblemSeverity.of(it.kind)) }
        return copy(problems = graded, problemsBySeverity = problemsBySeverity ?: ProblemSeverity.count(graded))
    }
}

// kind: un DefectKind del analizador o FILE_TOO_LARGE. origin es repo@sha:ruta y line, la línea (0: el archivo entero).
// detail sale del manual: dato, no instrucción. severity la pone el servidor por kind al leer el informe
data class ImportProblem(
    val kind: String,
    val origin: String,
    val line: Int,
    val detail: String,
    val severity: ProblemSeverity? = null,
)

// GAP: esa parte del manual no se leyó y al temario le pueden faltar unidades o lecciones. CHECK: se leyó; queda algo que
// revisar
enum class ProblemSeverity {
    GAP,
    CHECK,
    ;

    companion object {
        private val CHECKS =
            setOf(
                DefectKind.UNSUPPORTED_INCLUDE_OPTION,
                DefectKind.MISSING_IMAGE,
                DefectKind.IMAGE_OUTSIDE_ROOT,
                DefectKind.BROKEN_REFERENCE,
                DefectKind.DUPLICATE_ANCHOR,
            ).map { it.name }
                .toSet()

        // lo que no se sabe clasificar pide atención
        fun of(kind: String): ProblemSeverity = if (kind in CHECKS) CHECK else GAP

        fun count(problems: List<ImportProblem>): Map<ProblemSeverity, Int> = entries.associateWith { s -> problems.count { of(it.kind) == s } }
    }
}

// lo que la cola guarda como resultado del trabajo; failure: terminó sin borrador por un motivo que reintentar no arregla
data class ImportOutcome(
    val versionId: String? = null,
    val failure: String? = null,
    val report: ImportReport? = null,
)

// el payload del trabajo: quién lo pidió (el autor del borrador y el único que lo lee), sobre qué curso y de qué fuente
data class ImportPayload(
    val requester: String,
    val courseId: String,
    val source: String,
)

// encola importaciones y cuenta su estado. importar exige crear versiones, unidades y lecciones: lo que el borrador
// importado es. el trabajo y su informe solo existen para quien lo pidió; para cualquier otro, también el administrador,
// responden 404 (como lo ajeno del estudio)
@Service
class ImportService(
    private val currentUser: CurrentUser,
    private val metadata: MetadataService,
    private val records: RecordService,
    private val queue: JobQueue,
    properties: ImporterProperties,
) {
    private val allowed = AllowedSources(properties.allowedSources)

    suspend fun sources(): List<ImportSource> {
        requireImporter()
        return allowed.origins.map { ImportSource(it.repository, it.sha) }
    }

    suspend fun start(request: ImportRequest): ImportJob {
        val user = requireImporter()
        val source =
            try {
                SourceRef.parse(request.source.orEmpty())
            } catch (e: IllegalArgumentException) {
                throw ValidationException("Invalid source", "source", e.message ?: "expected owner/repo@sha:path")
            }
        val courseId =
            request.courseId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw ValidationException("Invalid course", "courseId", "must be a course id")
        if (!allowed.permits(source)) throw ForbiddenException("${source.repository} is not an allowed source")
        // el curso tiene que existir y ser legible para quien importa; el trabajo lo vuelve a leer
        try {
            records.get(COURSE, courseId)
        } catch (_: ForbiddenException) {
            throw NotFoundException("Course not found")
        }
        val payload = ImportPayload(user.userId.toString(), courseId.toString(), source.toString())
        // la misma persona, el mismo curso y la misma fuente mientras el trabajo no termina: el mismo trabajo (ADR-0022)
        val key = "${user.userId}:$courseId:$source"
        val id = queue.enqueue(user.organizationId, JOB_TYPE, MAPPER.writeValueAsString(payload), key = key, requester = user.userId)
        return view(queue.find(user.organizationId, id)!!)
    }

    // mis importaciones, la más reciente primero (E30.2): al recargar, la interfaz no pierde de vista la que corre. pide lo
    // mismo que importar; nunca lista las de otra persona, tampoco al administrador
    suspend fun mine(): List<ImportJob> {
        val user = requireImporter()
        return queue.requestedBy(user.organizationId, JOB_TYPE, user.userId, MAX_LISTED).map { view(it) }
    }

    suspend fun job(id: UUID): ImportJob {
        val user = currentUser.require()
        val status = queue.find(user.organizationId, id)?.takeIf { it.type == JOB_TYPE } ?: throw NotFoundException(NOT_FOUND)
        if (MAPPER.readValue<ImportPayload>(status.payload).requester != user.userId.toString()) throw NotFoundException(NOT_FOUND)
        return view(status)
    }

    private suspend fun requireImporter(): AuthenticatedUser {
        val user = currentUser.require()
        for (name in listOf(VERSION, UNIT, LESSON)) currentUser.requirePermission(user, Actions.CREATE, metadata.definitionOf(name).obj.id)
        return user
    }

    private fun view(status: JobStatus): ImportJob {
        val outcome = status.result?.let { MAPPER.readValue<ImportOutcome>(it) }
        val failed = status.state == JobState.FAILED || outcome?.failure != null
        val state =
            when {
                failed -> "FALLIDO"
                status.state == JobState.QUEUED -> "PENDIENTE"
                status.state == JobState.RUNNING -> "EN_CURSO"
                else -> "HECHO"
            }
        val payload = MAPPER.readValue<ImportPayload>(status.payload)
        return ImportJob(
            status.id.toString(),
            state,
            outcome?.failure ?: status.error,
            outcome?.versionId,
            outcome?.report?.withSeverity(),
            payload.source,
            payload.courseId,
            status.createdAt,
            status.finishedAt,
        )
    }

    companion object {
        const val JOB_TYPE = "importer.asciidoc"
        const val COURSE = "curso"
        const val VERSION = "version_curso"
        const val UNIT = "unidad"
        const val LESSON = "leccion"
        private const val NOT_FOUND = "Import not found"

        // con el tope de activos por persona y la retención, una persona tiene pocas: las más recientes bastan
        private const val MAX_LISTED = 50
        internal val MAPPER: JsonMapper = JsonMapper.builder().findAndAddModules().build()
    }
}
