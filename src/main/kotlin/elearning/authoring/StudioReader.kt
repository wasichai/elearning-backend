package elearning.authoring

import elearning.common.DisplayNames
import elearning.common.listAll
import elearning.common.listAllIn
import elearning.common.listByIds
import elearning.common.ref
import org.springframework.stereotype.Service
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import java.time.Instant
import java.util.UUID

// el contrato del estudio (docs/api/lms.md, ADR-0018). cada id de una persona va con su nombre para mostrar, null si está
// deshabilitada o se borró (#258, ADR-0034); nunca su correo

data class StudioCourse(
    val id: String,
    val code: String,
    val title: String,
)

data class StudioVersion(
    val id: String,
    val number: Int,
    val status: String,
    val course: StudioCourse,
    val authorId: String?,
    val authorName: String?,
    val reviewerId: String?,
    val reviewerName: String?,
    val updatedAt: Instant?,
    val openComments: Int,
)

data class StudioLesson(
    val id: String,
    val order: Int,
    val title: String,
    val type: String,
    val sourceRef: String?,
    val hasAssessment: Boolean,
    val resources: Int,
)

data class StudioUnit(
    val id: String,
    val order: Int,
    val title: String,
    val sourceRef: String?,
    val lessons: List<StudioLesson>,
)

data class StudioVersionDetail(
    val id: String,
    val number: Int,
    val status: String,
    val course: StudioCourse,
    val authorId: String?,
    val authorName: String?,
    val reviewerId: String?,
    val reviewerName: String?,
    val updatedAt: Instant?,
    val openComments: Int,
    val units: List<StudioUnit>,
    val comments: List<ReviewComment>,
    val decisions: List<ReviewDecision>,
)

data class ReviewComment(
    val id: String,
    val section: String,
    val text: String,
    val authorId: String?,
    val authorName: String?,
    val createdAt: Instant?,
    val resolved: Boolean,
)

data class ReviewDecision(
    val decision: String,
    val reason: String?,
    val authorId: String,
    val authorName: String?,
    val at: Instant,
)

// las versiones de quien las escribió. el resto no existe aquí, aunque pueda leerlas por otra vía: la bandeja del revisor
// tiene su propia ruta (ReviewService) y comparte con esta el resumen y el detalle
@Service
class StudioReader(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val decisions: DecisionStore,
    private val names: DisplayNames,
) {
    suspend fun mine(): List<StudioVersion> {
        val me = currentUser.require().userId.toString()
        val versions =
            try {
                records.listAll(VERSION, mapOf(AUTHOR to me))
            } catch (_: ForbiddenException) {
                emptyList()
            }
        return summaries(versions.sortedByDescending { it.updatedAt })
    }

    suspend fun detail(id: UUID): StudioVersionDetail = detailOf(own(id))

    // el detalle de una versión que quien llama ya puede ver aquí: su temario, sus comentarios y sus decisiones. el temario
    // se lee por niveles, no lección a lección (L4 de #216)
    suspend fun detailOf(version: RecordResponse): StudioVersionDetail {
        val organization = currentUser.require().organizationId
        val course = records.get(COURSE, UUID.fromString(version.attributes.ref(COURSE)!!))
        val notes = comments(listOf(version.id))[version.id].orEmpty()
        val unitRecords = records.listAll(UNIT, mapOf(VERSION to version.id))
        val lessonRecords = records.listAllIn(LESSON, UNIT, unitRecords.map { it.id }).groupBy { it.attributes.ref(UNIT) }
        val lessonIds = lessonRecords.values.flatten().map { it.id }
        val assessed = records.listAllIn(EVALUATION, LESSON, lessonIds).mapNotNull { it.attributes.ref(LESSON) }.toSet()
        val linked = records.listAllIn(LINK, LESSON, lessonIds).groupingBy { it.attributes.ref(LESSON) }.eachCount()
        val units =
            unitRecords.sortedBy { int(it, ORDER) }.map { unit ->
                StudioUnit(
                    id = unit.id,
                    order = int(unit, ORDER),
                    title = unit.attributes[TITLE].toString(),
                    sourceRef = unit.attributes[SOURCE]?.toString(),
                    lessons =
                        lessonRecords[unit.id].orEmpty().sortedBy { int(it, ORDER) }.map { lesson ->
                            StudioLesson(
                                id = lesson.id,
                                order = int(lesson, ORDER),
                                title = lesson.attributes[TITLE].toString(),
                                type = lesson.attributes["tipo"].toString(),
                                sourceRef = lesson.attributes[SOURCE]?.toString(),
                                hasAssessment = lesson.id in assessed,
                                resources = linked[lesson.id] ?: 0,
                            )
                        },
                )
            }
        val decided = decisions.ofVersion(organization, UUID.fromString(version.id))
        // los nombres de la versión, de sus comentarios y de sus decisiones, en una sola lectura (#258)
        val named = names.of(organization, people(version) + notes.map { it.attributes[AUTHOR]?.toString() } + decided.map { it.authorId })
        val s = summaryOf(version, course, notes, named)
        return StudioVersionDetail(
            s.id,
            s.number,
            s.status,
            s.course,
            s.authorId,
            s.authorName,
            s.reviewerId,
            s.reviewerName,
            s.updatedAt,
            s.openComments,
            units,
            notes.map { comment(it, named) },
            decided.map { it.copy(authorName = named[it.authorId]) },
        )
    }

    suspend fun submit(id: UUID): StudioVersion {
        val version = own(id)
        if (version.attributes[STATE]?.toString() != "BORRADOR") throw ConflictException("Solo se envía a revisión un borrador")
        return summary(records.patch(VERSION, UUID.fromString(version.id), RecordRequest(mapOf(STATE to "EN_REVISION")), null, null))
    }

    // una versión de quien llama; la de otra persona, o la que no puede leer, no existe (404)
    suspend fun own(id: UUID): RecordResponse {
        val me = currentUser.require().userId.toString()
        val version =
            try {
                records.get(VERSION, id)
            } catch (_: ForbiddenException) {
                throw NotFoundException(NOT_FOUND)
            }
        if (version.attributes[AUTHOR]?.toString() != me) throw NotFoundException(NOT_FOUND)
        return version
    }

    suspend fun summary(version: RecordResponse): StudioVersion = summaries(listOf(version)).single()

    // el resumen de varias versiones con sus cursos, sus comentarios y los nombres de sus autores y revisores leídos de una
    // vez, no por versión ni por persona (L4 de #216, #258)
    suspend fun summaries(versions: List<RecordResponse>): List<StudioVersion> {
        if (versions.isEmpty()) return emptyList()
        val courses = records.listByIds(COURSE, versions.mapNotNull { it.attributes.ref(COURSE) }).associateBy { it.id }
        val notes = comments(versions.map { it.id })
        val named = names.of(currentUser.require().organizationId, versions.flatMap { people(it) })
        return versions.map { version ->
            val course = courses[version.attributes.ref(COURSE)] ?: records.get(COURSE, UUID.fromString(version.attributes.ref(COURSE)!!))
            summaryOf(version, course, notes[version.id].orEmpty(), named)
        }
    }

    private fun summaryOf(
        version: RecordResponse,
        course: RecordResponse,
        notes: List<RecordResponse>,
        named: Map<String, String>,
    ): StudioVersion {
        val author = version.attributes[AUTHOR]?.toString()
        val reviewer = version.attributes[REVIEWER]?.toString()
        return StudioVersion(
            id = version.id,
            number = int(version, "numero"),
            status = version.attributes[STATE].toString(),
            course = StudioCourse(course.id, course.attributes["codigo"].toString(), course.attributes[TITLE].toString()),
            authorId = author,
            authorName = author?.let { named[it] },
            reviewerId = reviewer,
            reviewerName = reviewer?.let { named[it] },
            updatedAt = version.updatedAt,
            openComments = notes.count { !resolved(it) },
        )
    }

    private fun people(version: RecordResponse) = listOf(version.attributes[AUTHOR]?.toString(), version.attributes[REVIEWER]?.toString())

    // los comentarios de cada versión, en el orden en que se hicieron. quien no los puede leer no ve ninguno
    private suspend fun comments(versions: List<String>): Map<String, List<RecordResponse>> =
        try {
            records
                .listAllIn(COMMENT, VERSION, versions)
                .groupBy { it.attributes.ref(VERSION).orEmpty() }
                .mapValues { (_, notes) -> notes.sortedBy { it.createdAt } }
        } catch (_: ForbiddenException) {
            emptyMap()
        }

    // un comentario recién escrito o resuelto, con el nombre de quien lo escribió
    suspend fun comment(record: RecordResponse): ReviewComment =
        comment(record, names.of(currentUser.require().organizationId, listOf(record.attributes[AUTHOR]?.toString())))

    private fun comment(
        record: RecordResponse,
        named: Map<String, String>,
    ): ReviewComment {
        val author = record.attributes[AUTHOR]?.toString()
        return ReviewComment(
            id = record.id,
            section = record.attributes["seccion"].toString(),
            text = record.attributes["texto"].toString(),
            authorId = author,
            authorName = author?.let { named[it] },
            createdAt = record.createdAt,
            resolved = resolved(record),
        )
    }

    private fun resolved(record: RecordResponse) = record.attributes["resuelto"]?.toString() == "true"

    private fun int(
        record: RecordResponse,
        field: String,
    ) = (record.attributes[field] as? Number)?.toInt() ?: 0

    companion object {
        const val VERSION = "version_curso"
        const val COURSE = "curso"
        const val UNIT = "unidad"
        const val LESSON = "leccion"
        const val LINK = "recurso_leccion"
        const val EVALUATION = "evaluacion"
        const val COMMENT = "comentario_revision"
        const val AUTHOR = "autor_id"
        const val REVIEWER = "revisor_id"
        const val STATE = "estado"
        const val ORDER = "orden"
        const val TITLE = "titulo"
        const val SOURCE = "referencia_fuente"
        const val NOT_FOUND = "Versión no encontrada"
    }
}
