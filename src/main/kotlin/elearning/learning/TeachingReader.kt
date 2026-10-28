package elearning.learning

import elearning.common.listAll
import elearning.common.ref
import org.springframework.stereotype.Component
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import java.time.LocalDate
import java.util.UUID

// lo que ve el docente de sus ediciones asignadas (docente_edicion.usuario_id, ADR-0016). todo se lee como la persona:
// el alcance de matrícula y progreso decide; una edición que no le está asignada no existe para él
@Component
class TeachingReader(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val classroom: ClassroomReader,
) {
    suspend fun offerings(): List<TeachingOffering> =
        assigned()
            .map { offering(records.get(OFFERING, UUID.fromString(it))) }
            .sortedWith(compareBy({ it.startDate }, { it.code }))

    suspend fun students(code: String): List<RosterRow> {
        val offering = assignedOffering(code)
        return enrollmentsOf(offering.id).map { enrollment ->
            RosterRow(
                enrollmentId = enrollment.id,
                email = enrollment.attributes[STUDENT_EMAIL]?.toString(),
                status = enrollment.attributes[STATE].toString(),
                progress = classroom.view(enrollment).progress,
                attendance = AttendanceSummary(0, 0),
                assessments = emptyList(),
            )
        }
    }

    // la edición de ese código, solo si está asignada a la persona; si no, 404 como si no existiera
    suspend fun assignedOffering(code: String): RecordResponse {
        val found = records.listAll(OFFERING, mapOf(CODE to code)).singleOrNull() ?: throw NotFoundException(NOT_FOUND)
        if (found.id !in assigned()) throw NotFoundException(NOT_FOUND)
        return found
    }

    // las ediciones asignadas a la persona; quien no puede leer asignaciones no tiene ninguna
    suspend fun assigned(): Set<String> {
        val me = currentUser.require().userId.toString()
        return try {
            records.listAll(ASSIGNMENT, mapOf(TEACHER to me)).mapNotNull { it.attributes.ref(OFFERING_FIELD) }.toSet()
        } catch (_: ForbiddenException) {
            emptySet()
        }
    }

    private suspend fun enrollmentsOf(offering: String): List<RecordResponse> =
        records.listAll(ENROLLMENT, mapOf(ENROLLMENT_OFFERING to offering)).filter { it.attributes[STATE]?.toString() != WITHDRAWN }

    private suspend fun offering(record: RecordResponse): TeachingOffering {
        val a = record.attributes
        val version = records.get(VERSION, UUID.fromString(a.ref(VERSION)!!))
        val course = records.get(COURSE, UUID.fromString(version.attributes.ref(COURSE)!!))
        return TeachingOffering(
            code = a[CODE].toString(),
            status = a[STATE].toString(),
            startDate = LocalDate.parse(a["fecha_inicio"].toString().take(10)),
            endDate = LocalDate.parse(a["fecha_fin"].toString().take(10)),
            timeZone = a["zona_horaria"].toString(),
            course = CourseRef(course.attributes["slug"].toString(), course.attributes["codigo"].toString(), course.attributes["titulo"].toString()),
            enrolled = enrollmentsOf(record.id).size,
            pendingAttempts = 0,
        )
    }

    companion object {
        const val OFFERING = "edicion_curso"
        const val ASSIGNMENT = "docente_edicion"
        const val ENROLLMENT = "matricula"
        const val VERSION = "version_curso"
        const val COURSE = "curso"
        const val TEACHER = "usuario_id"
        const val OFFERING_FIELD = "edicion_curso"
        const val ENROLLMENT_OFFERING = "edicion"
        const val STUDENT_EMAIL = "estudiante_correo"
        const val STATE = "estado"
        const val CODE = "codigo"
        const val WITHDRAWN = "RETIRADA"
        const val NOT_FOUND = "Edición no encontrada"
    }
}
