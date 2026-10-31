package elearning.learning

import elearning.common.listAll
import elearning.common.ref
import org.springframework.stereotype.Service
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import java.util.UUID

// aplica las reglas de la versión de una matrícula propia a lo que muestra su aula. toda sesión no cancelada cuenta, también
// las futuras: quien finaliza antes de que acabe la edición no ha asistido a todas
@Service
class CompletionService(
    private val records: RecordService,
    private val classroom: ClassroomReader,
) {
    suspend fun of(enrollmentId: UUID): Completion {
        val view = classroom.classroom(enrollmentId)
        val enrollment = records.get(ENROLLMENT, enrollmentId)
        val version = records.get(VERSION, UUID.fromString(enrollment.attributes.ref(VERSION)!!))
        val lessons = view.units.flatMap { it.lessons }
        val countable = lessons.filter { it.completion != "NOT_YET" }
        val assessments = lessons.mapNotNull { it.assessment }
        val sessions =
            records.listAll(SESSION, mapOf(OFFERING to enrollment.attributes.ref(OFFERING)!!)).filter {
                it.attributes[STATE]?.toString() !=
                    "CANCELADA"
            }
        val present =
            records
                .listAll(ATTENDANCE, mapOf(ENROLLMENT to enrollmentId.toString()))
                .filter {
                    it.attributes[STATE]?.toString() in
                        ClassroomReader.PRESENT
                }.mapNotNull { it.attributes.ref(SESSION) }
                .toSet()
        return CompletionRules.evaluate(
            declared(version),
            CompletionFacts(
                lessonsDone = countable.count { it.progress.status in LessonProgressService.DONE },
                lessonsTotal = countable.size,
                assessmentsPassed = assessments.count { it.best?.passed == true },
                assessmentsTotal = assessments.size,
                sessionsAttended = sessions.count { it.id in present },
                sessionsTotal = sessions.size,
            ),
        )
    }

    private fun declared(version: RecordResponse) =
        DeclaredRules(
            lessons = version.attributes["regla_lecciones"] == true,
            assessments = version.attributes["regla_evaluaciones"] == true,
            minAttendancePercent = (version.attributes["asistencia_minima"] as? Number)?.toInt(),
        )

    companion object {
        const val ENROLLMENT = "matricula"
        const val VERSION = "version_curso"
        const val OFFERING = "edicion"
        const val SESSION = "sesion"
        const val ATTENDANCE = "asistencia"
        const val STATE = "estado"
    }
}
