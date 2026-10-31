package elearning.authoring

import elearning.common.listAll
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

// el contrato del estudio (docs/api/lms.md, ADR-0018)

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
    val reviewerId: String?,
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
    val lessons: List<StudioLesson>,
)

data class StudioVersionDetail(
    val id: String,
    val number: Int,
    val status: String,
    val course: StudioCourse,
    val authorId: String?,
    val reviewerId: String?,
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
    val createdAt: Instant?,
    val resolved: Boolean,
)

data class ReviewDecision(
    val decision: String,
    val reason: String?,
    val authorId: String,
    val at: Instant,
)

// las versiones de quien las escribió. el resto no existe aquí, aunque pueda leerlas por otra vía: la bandeja del revisor
// tiene su propia ruta (ReviewService) y comparte con esta el resumen y el detalle
@Service
class StudioReader(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val decisions: DecisionStore,
) {
    suspend fun mine(): List<StudioVersion> {
        val me = currentUser.require().userId.toString()
        val versions =
            try {
                records.listAll(VERSION, mapOf(AUTHOR to me))
            } catch (_: ForbiddenException) {
                emptyList()
            }
        return versions.sortedByDescending { it.updatedAt }.map { summary(it) }
    }

    suspend fun detail(id: UUID): StudioVersionDetail = detailOf(own(id))

    // el detalle de una versión que quien llama ya puede ver aquí: su temario, sus comentarios y sus decisiones
    suspend fun detailOf(version: RecordResponse): StudioVersionDetail {
        val notes = comments(version.id)
        val s = summary(version, notes)
        val units =
            records.listAll(UNIT, mapOf(VERSION to version.id)).sortedBy { int(it, ORDER) }.map { unit ->
                StudioUnit(
                    id = unit.id,
                    order = int(unit, ORDER),
                    title = unit.attributes[TITLE].toString(),
                    lessons =
                        records.listAll(LESSON, mapOf(UNIT to unit.id)).sortedBy { int(it, ORDER) }.map { lesson ->
                            StudioLesson(
                                id = lesson.id,
                                order = int(lesson, ORDER),
                                title = lesson.attributes[TITLE].toString(),
                                type = lesson.attributes["tipo"].toString(),
                                sourceRef = lesson.attributes["referencia_fuente"]?.toString(),
                                hasAssessment = records.listAll(EVALUATION, mapOf(LESSON to lesson.id)).isNotEmpty(),
                                resources = records.listAll(LINK, mapOf(LESSON to lesson.id)).size,
                            )
                        },
                )
            }
        val decided = decisions.ofVersion(currentUser.require().organizationId, UUID.fromString(version.id))
        return StudioVersionDetail(s.id, s.number, s.status, s.course, s.authorId, s.reviewerId, s.updatedAt, s.openComments, units, notes, decided)
    }

    suspend fun submit(id: UUID): StudioVersion {
        val version = own(id)
        if (version.attributes[STATE]?.toString() != "BORRADOR") throw ConflictException("Solo se envía a revisión un borrador")
        return summary(records.patch(VERSION, UUID.fromString(version.id), RecordRequest(mapOf(STATE to "EN_REVISION")), null, null))
    }

    private suspend fun own(id: UUID): RecordResponse {
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

    suspend fun summary(
        version: RecordResponse,
        known: List<ReviewComment>? = null,
    ): StudioVersion {
        val open = (known ?: comments(version.id)).count { !it.resolved }
        val course = records.get(COURSE, UUID.fromString(version.attributes.ref(COURSE)!!))
        return StudioVersion(
            id = version.id,
            number = int(version, "numero"),
            status = version.attributes[STATE].toString(),
            course = StudioCourse(course.id, course.attributes["codigo"].toString(), course.attributes[TITLE].toString()),
            authorId = version.attributes[AUTHOR]?.toString(),
            reviewerId = version.attributes["revisor_id"]?.toString(),
            updatedAt = version.updatedAt,
            openComments = open,
        )
    }

    // los comentarios de una versión, en el orden en que se hicieron. quien no los puede leer no ve ninguno
    suspend fun comments(version: String): List<ReviewComment> =
        try {
            records.listAll(COMMENT, mapOf(VERSION to version)).map { comment(it) }.sortedBy { it.createdAt }
        } catch (_: ForbiddenException) {
            emptyList()
        }

    fun comment(record: RecordResponse) =
        ReviewComment(
            id = record.id,
            section = record.attributes["seccion"].toString(),
            text = record.attributes["texto"].toString(),
            authorId = record.attributes[AUTHOR]?.toString(),
            createdAt = record.createdAt,
            resolved = record.attributes["resuelto"]?.toString() == "true",
        )

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
        const val STATE = "estado"
        const val ORDER = "orden"
        const val TITLE = "titulo"
        const val NOT_FOUND = "Versión no encontrada"
    }
}
