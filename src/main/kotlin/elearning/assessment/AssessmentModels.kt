package elearning.assessment

import java.math.BigDecimal
import java.time.Instant

// la instantánea de una evaluación, guardada en lms.intento: lleva las soluciones y nunca sale tal cual por la API

data class SnapshotOption(
    val id: String,
    val text: String,
    val correct: Boolean,
)

data class SnapshotCriterion(
    val description: String,
    val points: BigDecimal,
)

data class SnapshotQuestion(
    val id: String,
    val order: Int,
    val type: String,
    val prompt: String,
    val points: BigDecimal,
    val options: List<SnapshotOption> = emptyList(),
    val rubric: List<SnapshotCriterion> = emptyList(),
    val acceptedAnswers: List<String> = emptyList(),
)

data class AssessmentSnapshot(
    val evaluationId: String,
    val title: String,
    val instructions: String?,
    val maxAttempts: Int,
    val timeLimitMinutes: Int?,
    val passingPercent: Int,
    val showSolutions: String,
    val questions: List<SnapshotQuestion>,
)

data class AttemptAnswer(
    val questionId: String,
    val optionIds: List<String> = emptyList(),
    val text: String? = null,
    val submissionId: String? = null,
)

data class QuestionGrade(
    val questionId: String,
    val points: BigDecimal?,
    val maxPoints: BigDecimal,
    val correct: Boolean?,
    val comment: String? = null,
)

data class GradeResult(
    val score: BigDecimal,
    val maxScore: BigDecimal,
    val percent: Int?,
    val passed: Boolean?,
    val pending: Boolean,
    val questions: List<QuestionGrade>,
)

// el contrato del intento (docs/api/lms.md): sin soluciones ni puntos mientras está abierto

data class OptionView(
    val id: String,
    val text: String,
)

data class CriterionView(
    val description: String,
    val points: BigDecimal,
)

data class QuestionView(
    val id: String,
    val order: Int,
    val type: String,
    val prompt: String,
    val points: BigDecimal,
    val options: List<OptionView>,
    val rubric: List<CriterionView>,
)

data class SolutionView(
    val optionIds: List<String>,
    val acceptedAnswers: List<String>,
)

data class QuestionResultView(
    val questionId: String,
    val points: BigDecimal?,
    val maxPoints: BigDecimal,
    val correct: Boolean?,
    val comment: String?,
    val solution: SolutionView?,
)

data class ResultView(
    val score: BigDecimal,
    val maxScore: BigDecimal,
    val percent: Int?,
    val passed: Boolean?,
    val pending: Boolean,
    val questions: List<QuestionResultView>,
)

data class AnswerView(
    val questionId: String,
    val optionIds: List<String>,
    val text: String?,
    val submissionId: String?,
)

data class AttemptView(
    val id: String,
    val number: Int,
    val status: String,
    val startedAt: Instant,
    val deadline: Instant?,
    val submittedAt: Instant?,
    val questions: List<QuestionView>,
    val answers: List<AnswerView>,
    val result: ResultView?,
)

data class SaveAnswersRequest(
    val answers: List<AttemptAnswer>? = null,
)
