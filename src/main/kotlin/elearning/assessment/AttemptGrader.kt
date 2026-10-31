package elearning.assessment

import java.math.BigDecimal
import java.math.RoundingMode

// la corrección automática, dominio puro. cerradas y cortas se corrigen solas, todo o nada; una abierta o de archivo
// respondida queda pendiente del docente, y sin responder vale cero
object AttemptGrader {
    private val MANUAL = setOf("OPEN", "FILE")

    fun grade(
        snapshot: AssessmentSnapshot,
        answers: List<AttemptAnswer>,
    ): GradeResult {
        val byQuestion = answers.associateBy { it.questionId }
        return summarize(snapshot, snapshot.questions.map { grade(it, byQuestion[it.id]) })
    }

    // puntuación y porcentaje a partir de las notas de cada pregunta; también tras una corrección manual
    fun summarize(
        snapshot: AssessmentSnapshot,
        grades: List<QuestionGrade>,
    ): GradeResult {
        val score = grades.mapNotNull { it.points }.fold(BigDecimal.ZERO, BigDecimal::add)
        val max = grades.map { it.maxPoints }.fold(BigDecimal.ZERO, BigDecimal::add)
        val pending = grades.any { it.points == null }
        // hacia abajo: nadie aprueba por redondeo
        val percent =
            when {
                pending -> null
                max.signum() == 0 -> 0
                else -> score.multiply(BigDecimal(100)).divide(max, 0, RoundingMode.DOWN).toInt()
            }
        return GradeResult(score, max, percent, percent?.let { it >= snapshot.passingPercent }, pending, grades)
    }

    private fun grade(
        question: SnapshotQuestion,
        answer: AttemptAnswer?,
    ): QuestionGrade {
        if (question.type in MANUAL) {
            val answered = !answer?.text.isNullOrBlank() || answer?.submissionId != null
            return QuestionGrade(question.id, if (answered) null else BigDecimal.ZERO, question.points, null)
        }
        val correct =
            if (question.type == "SHORT") {
                val given = answer?.text?.let(::normalize)
                given != null && question.acceptedAnswers.any { normalize(it) == given }
            } else {
                val right =
                    question.options
                        .filter { it.correct }
                        .map { it.id }
                        .toSet()
                right.isNotEmpty() && answer?.optionIds?.toSet() == right
            }
        return QuestionGrade(question.id, if (correct) question.points else BigDecimal.ZERO, question.points, correct)
    }

    private fun normalize(text: String) = text.trim().lowercase()
}
