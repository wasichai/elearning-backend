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
    val comments: List<Any>,
    val decisions: List<Any>,
)

// las versiones de quien las escribió. el resto no existe aquí, aunque pueda leerlas por otra vía: la bandeja del revisor
// tiene su propia ruta
@Service
class StudioReader(
    private val currentUser: CurrentUser,
    private val records: RecordService,
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

    suspend fun detail(id: UUID): StudioVersionDetail {
        val version = own(id)
        val s = summary(version)
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
        return StudioVersionDetail(s.id, s.number, s.status, s.course, s.authorId, s.reviewerId, s.updatedAt, s.openComments, units, emptyList(), emptyList())
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

    private suspend fun summary(version: RecordResponse): StudioVersion {
        val course = records.get(COURSE, UUID.fromString(version.attributes.ref(COURSE)!!))
        return StudioVersion(
            id = version.id,
            number = int(version, "numero"),
            status = version.attributes[STATE].toString(),
            course = StudioCourse(course.id, course.attributes["codigo"].toString(), course.attributes[TITLE].toString()),
            authorId = version.attributes[AUTHOR]?.toString(),
            reviewerId = version.attributes["revisor_id"]?.toString(),
            updatedAt = version.updatedAt,
            openComments = 0,
        )
    }

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
        const val AUTHOR = "autor_id"
        const val STATE = "estado"
        const val ORDER = "orden"
        const val TITLE = "titulo"
        const val NOT_FOUND = "Versión no encontrada"
    }
}
