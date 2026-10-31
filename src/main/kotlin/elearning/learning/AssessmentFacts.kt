package elearning.learning

import java.util.UUID

data class LessonAssessmentFact(
    val lessonId: String,
    val title: String,
    val bestPercent: Int?,
    val passed: Boolean?,
    val pending: Boolean,
)

// lo que learning necesita saber de las evaluaciones, que dependen de learning y no al revés: lo implementa assessment
interface AssessmentFacts {
    suspend fun pendingAttempts(
        organization: UUID,
        offering: UUID,
    ): Int

    suspend fun assessments(
        organization: UUID,
        enrollment: UUID,
    ): List<LessonAssessmentFact>
}
