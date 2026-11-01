package elearning.learning

import elearning.common.instantOf
import elearning.common.listAll
import elearning.common.listAllIn
import elearning.common.ref
import org.springframework.stereotype.Component
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import wasichai.core.platform.SqlIdentifier
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// lo que ve el docente de sus ediciones asignadas (docente_edicion.usuario_id, ADR-0016). todo se lee como la persona:
// el alcance de matrícula y progreso decide; una edición que no le está asignada no existe para él
@Component
class TeachingReader(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val classroom: ClassroomReader,
    private val facts: AssessmentFacts,
    private val clock: Clock,
) {
    suspend fun offerings(): List<TeachingOffering> =
        assigned()
            .map { offering(records.get(OFFERING, UUID.fromString(it))) }
            .sortedWith(compareBy({ it.startDate }, { it.code }))

    // por lotes, no por estudiante (M4 de #183): las sentencias crecen con el temario y las sesiones, no con el roster. con
    // page, una página en el orden de siempre (el de alta), y su asistencia y su progreso se leen por sus matrículas
    suspend fun students(
        code: String,
        page: PageRequest? = null,
    ): List<RosterRow> {
        val offering = assignedOffering(code)
        val sessions = records.listAll(SESSION, mapOf(ENROLLMENT_OFFERING to offering.id)).filter { it.attributes[STATE]?.toString() != CANCELLED }
        val enrollments = if (page == null) enrollmentsOf(offering.id) else enrollmentPage(offering.id, page)
        val counted = sessions.map { it.id }.toSet()
        val attendance =
            if (page == null) {
                sessions.flatMap { records.listAll(ATTENDANCE, mapOf(SESSION to it.id)) }
            } else {
                records.listAllIn(ATTENDANCE, ENROLLMENT, enrollments.map { it.id }).filter { it.attributes.ref(SESSION) in counted }
            }
        val taken = attendance.groupBy { it.attributes.ref(ENROLLMENT).orEmpty() }
        val progress = classroom.progress(offering.id, enrollments, sessions, attendance, byEnrollment = page != null)
        val assessments = facts.offeringAssessments(currentUser.require().organizationId, UUID.fromString(offering.id))
        return enrollments.map { enrollment ->
            RosterRow(
                enrollmentId = enrollment.id,
                email = enrollment.attributes[STUDENT_EMAIL]?.toString(),
                status = enrollment.attributes[STATE].toString(),
                progress = progress.getValue(enrollment.id),
                attendance = attendanceOf(taken[enrollment.id].orEmpty(), sessions),
                assessments =
                    assessments[enrollment.id].orEmpty().map {
                        RosterAssessment(it.lessonId, it.title, it.bestPercent, it.passed, it.pending)
                    },
            )
        }
    }

    // sobre las sesiones no canceladas ya empezadas o con su asistencia registrada: una futura sin registrar aún no cuenta
    private fun attendanceOf(
        marks: List<RecordResponse>,
        sessions: List<RecordResponse>,
    ): AttendanceSummary {
        val taken = marks.associate { it.attributes.ref(SESSION) to it.attributes[STATE]?.toString() }
        val now = Instant.now(clock)
        val counted = sessions.filter { it.id in taken || instantOf(it.attributes["inicio"])?.isBefore(now) == true }
        return AttendanceSummary(counted.count { taken[it.id] in ClassroomReader.PRESENT }, counted.size)
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

    // una página de las matrículas no retiradas de la edición, en el orden de listAll (el de alta y, a igualdad, el id)
    private suspend fun enrollmentPage(
        offering: String,
        page: PageRequest,
    ): List<RecordResponse> {
        val notWithdrawn =
            RecordCriterion { definition, bind ->
                definition.fields
                    .firstOrNull { it.name == STATE }
                    ?.let { "${SqlIdentifier.quote(it.columnName)} IS DISTINCT FROM ${bind(WITHDRAWN)}" } ?: "true"
            }
        val query = RecordQuery(page = page, filters = mapOf(ENROLLMENT_OFFERING to offering), criteria = listOf(notWithdrawn), count = false)
        return records.list(ENROLLMENT, query).content
    }

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
            pendingAttempts = facts.pendingAttempts(currentUser.require().organizationId, UUID.fromString(record.id)),
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
        const val SESSION = "sesion"
        const val ATTENDANCE = "asistencia"
        const val CANCELLED = "CANCELADA"
        const val NOT_FOUND = "Edición no encontrada"
    }
}
