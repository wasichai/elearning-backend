package elearning.assessment

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ValidationException
import java.util.UUID

// los intentos en el aula (docs/api/lms.md). la vista se arma aquí: lo único que sale de la instantánea
@RestController
@RequestMapping("/api/lms/classroom")
class AttemptController(
    private val attempts: AttemptService,
    private val submissions: SubmissionService,
) {
    @PostMapping("/enrollments/{enrollmentId}/lessons/{lessonId}/attempts")
    suspend fun start(
        @PathVariable enrollmentId: UUID,
        @PathVariable lessonId: UUID,
    ): ResponseEntity<AttemptView> {
        val started = attempts.start(enrollmentId, lessonId)
        return ResponseEntity.status(if (started.created) HttpStatus.CREATED else HttpStatus.OK).body(view(started.attempt))
    }

    @GetMapping("/attempts/{id}")
    suspend fun read(
        @PathVariable id: UUID,
    ): AttemptView = view(attempts.read(id))

    @PutMapping("/attempts/{id}/answers")
    suspend fun answers(
        @PathVariable id: UUID,
        @RequestBody body: SaveAnswersRequest,
    ): AttemptView {
        val answers = body.answers ?: throw ValidationException("Invalid answers", "answers", "required")
        return view(attempts.saveAnswers(id, answers))
    }

    @PostMapping("/attempts/{id}/submit")
    suspend fun submit(
        @PathVariable id: UUID,
    ): AttemptView = view(attempts.submit(id))

    @PostMapping("/attempts/{id}/questions/{questionId}/submission")
    suspend fun submission(
        @PathVariable id: UUID,
        @PathVariable questionId: UUID,
    ): SubmissionView = submissions.create(id, questionId)

    private suspend fun view(attempt: Attempt) = AttemptViews.student(attempt, submissions.of(attempt))
}

object AttemptViews {
    // sin soluciones con el intento abierto; enviadas, según la política de la evaluación
    fun student(
        attempt: Attempt,
        submissions: Map<String, SubmissionRef>,
    ): AttemptView = view(attempt, attempt.snapshot.showSolutions == "AL_ENVIAR", submissions)

    // el docente ve las soluciones siempre: corrige con ellas
    fun teacher(
        attempt: Attempt,
        submissions: Map<String, SubmissionRef>,
    ): AttemptView = view(attempt, solutions = true, submissions = submissions)

    private fun view(
        attempt: Attempt,
        solutions: Boolean,
        submissions: Map<String, SubmissionRef>,
    ): AttemptView {
        val questions = attempt.snapshot.questions.associateBy { it.id }
        return AttemptView(
            id = attempt.id.toString(),
            number = attempt.number,
            status = attempt.status,
            startedAt = attempt.startedAt,
            deadline = attempt.deadline,
            submittedAt = attempt.submittedAt,
            questions =
                attempt.snapshot.questions.map { q ->
                    QuestionView(
                        q.id,
                        q.order,
                        q.type,
                        q.prompt,
                        q.points,
                        q.options.map { OptionView(it.id, it.text) },
                        q.rubric.map { CriterionView(it.description, it.points) },
                    )
                },
            answers = attempt.answers.map { AnswerView(it.questionId, it.optionIds, it.text, it.submissionId, submissions[it.submissionId]) },
            result =
                attempt.result?.let { r ->
                    ResultView(
                        score = r.score,
                        maxScore = r.maxScore,
                        percent = r.percent,
                        passed = r.passed,
                        pending = r.pending,
                        questions =
                            r.questions.map { g ->
                                QuestionResultView(
                                    g.questionId,
                                    g.points,
                                    g.maxPoints,
                                    g.correct,
                                    g.comment,
                                    if (solutions) solution(questions.getValue(g.questionId)) else null,
                                )
                            },
                    )
                },
        )
    }

    private fun solution(question: SnapshotQuestion): SolutionView? {
        if (question.type in setOf("OPEN", "FILE")) return null
        return SolutionView(question.options.filter { it.correct }.map { it.id }, question.acceptedAnswers)
    }
}
