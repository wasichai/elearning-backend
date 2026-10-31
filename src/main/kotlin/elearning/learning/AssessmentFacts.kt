package elearning.learning

import java.time.Instant
import java.util.UUID

data class LessonAssessmentFact(
    val lessonId: String,
    val title: String,
    val bestPercent: Int?,
    val passed: Boolean?,
    val pending: Boolean,
)

// la evaluación de una lección para una matrícula, con lo que dicen sus intentos
data class LessonAssessment(
    val id: String,
    val title: String,
    val maxAttempts: Int,
    val timeLimitMinutes: Int?,
    val passingPercent: Int,
    val attemptsUsed: Int,
    val openAttemptId: String?,
    val bestPercent: Int?,
    val passed: Boolean?,
    val pending: Boolean,
    val firstStartedAt: Instant?,
    val passedAt: Instant?,
)

// lo que learning necesita saber de las evaluaciones, que dependen de learning y no al revés: lo implementa assessment
interface AssessmentFacts {
    suspend fun pendingAttempts(
        organization: UUID,
        offering: UUID,
    ): Int

    // por matrícula de la edición, sus evaluaciones con algún intento enviado: una lectura para todo el roster (M4 de #183)
    suspend fun offeringAssessments(
        organization: UUID,
        offering: UUID,
    ): Map<String, List<LessonAssessmentFact>>

    // por id de lección, solo las que tienen evaluación
    suspend fun lessonAssessments(
        organization: UUID,
        enrollment: UUID,
        lessons: List<UUID>,
    ): Map<String, LessonAssessment>

    // lo mismo para todas las matrículas de la edición, por id de matrícula, con una lectura de intentos (M4 de #183)
    suspend fun offeringLessonAssessments(
        organization: UUID,
        offering: UUID,
        lessons: List<UUID>,
    ): Map<String, Map<String, LessonAssessment>>

    suspend fun hasEvaluation(
        organization: UUID,
        lesson: UUID,
    ): Boolean
}
