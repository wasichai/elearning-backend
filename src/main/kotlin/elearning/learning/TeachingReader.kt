package elearning.learning

import elearning.common.DisplayNames
import elearning.common.instantOf
import elearning.common.listAll
import elearning.common.listAllIn
import elearning.common.listByIds
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
    private val names: DisplayNames,
    private val clock: Clock,
) {
    suspend fun offerings(): List<TeachingOffering> = offerings(assignedOfferings())

    // las ediciones asignadas, la de inicio más próximo primero y a igualdad por código: el orden de offerings() y del panel
    suspend fun assignedOfferings(): List<RecordResponse> =
        records.listByIds(OFFERING, assigned()).sortedWith(compareBy({ it.attributes["fecha_inicio"].toString().take(10) }, { it.attributes[CODE].toString() }))

    // esas ediciones, en su orden, por lotes (#261): sus versiones, sus cursos, sus matrículas y sus intentos por corregir en
    // una lectura cada uno, no cuatro por edición
    suspend fun offerings(found: List<RecordResponse>): List<TeachingOffering> {
        if (found.isEmpty()) return emptyList()
        val versions = records.listByIds(VERSION, found.map { it.attributes.ref(VERSION)!! }).associateBy { it.id }
        val courses = records.listByIds(COURSE, versions.values.map { it.attributes.ref(COURSE)!! }).associateBy { it.id }
        val enrolled =
            records
                .listAllIn(ENROLLMENT, ENROLLMENT_OFFERING, found.map { it.id })
                .filter { it.attributes[STATE]?.toString() != WITHDRAWN }
                .groupingBy { it.attributes.ref(ENROLLMENT_OFFERING).orEmpty() }
                .eachCount()
        val user = currentUser.require()
        val pending = facts.pendingAttempts(user.organizationId, found.map { UUID.fromString(it.id) }, user.userId)
        return found.map { record ->
            val a = record.attributes
            val course = courses.getValue(versions.getValue(a.ref(VERSION)!!).attributes.ref(COURSE)!!)
            TeachingOffering(
                code = a[CODE].toString(),
                status = a[STATE].toString(),
                startDate = LocalDate.parse(a["fecha_inicio"].toString().take(10)),
                endDate = LocalDate.parse(a["fecha_fin"].toString().take(10)),
                timeZone = a["zona_horaria"].toString(),
                course = CourseRef(course.attributes["slug"].toString(), course.attributes["codigo"].toString(), course.attributes["titulo"].toString()),
                enrolled = enrolled[record.id] ?: 0,
                pendingAttempts = pending[record.id] ?: 0,
            )
        }
    }

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
        val organization = currentUser.require().organizationId
        val assessments = facts.offeringAssessments(organization, UUID.fromString(offering.id))
        // los nombres de los matriculados de la respuesta (o de la página), en una lectura (ADR-0043)
        val named = names.of(organization, enrollments.map { it.attributes[STUDENT]?.toString() })
        return enrollments.map { enrollment ->
            RosterRow(
                enrollmentId = enrollment.id,
                email = enrollment.attributes[STUDENT_EMAIL]?.toString(),
                name = named[enrollment.attributes[STUDENT]?.toString()],
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

    companion object {
        const val OFFERING = "edicion_curso"
        const val ASSIGNMENT = "docente_edicion"
        const val ENROLLMENT = "matricula"
        const val VERSION = "version_curso"
        const val COURSE = "curso"
        const val TEACHER = "usuario_id"
        const val OFFERING_FIELD = "edicion_curso"
        const val ENROLLMENT_OFFERING = "edicion"
        const val STUDENT = "estudiante_id"
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
