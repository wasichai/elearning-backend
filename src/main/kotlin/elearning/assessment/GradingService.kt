package elearning.assessment

import elearning.common.ref
import elearning.learning.AssessmentFacts
import elearning.learning.LessonAssessmentFact
import elearning.learning.TeachingReader
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

// la corrección manual (ADR-0016 §2): solo el docente de la edición del intento, y nunca sobre su propio intento.
// lo ajeno es 404. cada calificación queda en lms.calificacion; cambiar una nota ya puesta exige motivo
@Service
class GradingService(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val teaching: TeachingReader,
    private val attempts: AttemptStore,
    private val transactions: TransactionalOperator,
    private val clock: Clock,
) {
    suspend fun queue(
        code: String,
        status: String?,
    ): List<PendingAttempt> {
        val wanted = status ?: AttemptService.SUBMITTED
        if (wanted !in LISTABLE) throw ValidationException("Invalid status", "status", "must be one of $LISTABLE")
        val offering = teaching.assignedOffering(code)
        return attempts.ofOffering(currentUser.require().organizationId, UUID.fromString(offering.id), wanted).map { a ->
            PendingAttempt(
                attemptId = a.id.toString(),
                enrollmentId = a.enrollmentId.toString(),
                email = email(a),
                lessonId = a.lessonId.toString(),
                lessonTitle = records.get(LESSON, a.lessonId).attributes[TITLE].toString(),
                submittedAt = a.submittedAt,
                pendingQuestions = a.result?.questions?.count { it.points == null } ?: 0,
            )
        }
    }

    suspend fun read(id: UUID): TeacherAttemptView = view(reachable(attempts.find(currentUser.require().organizationId, id)))

    suspend fun grade(
        id: UUID,
        request: GradeRequest,
    ): TeacherAttemptView {
        val user = currentUser.require()
        val updated =
            transactions.executeAndAwait {
                val attempt = reachable(attempts.lock(user.organizationId, id))
                if (attempt.studentId == user.userId) throw ForbiddenException("Nobody grades their own attempt")
                val result = attempt.result ?: throw ConflictException("Este intento aún no se envió")
                val question =
                    attempt.snapshot.questions.firstOrNull { it.id == request.questionId && it.type in MANUAL }
                        ?: invalid("questionId", "must be an open or file question of this attempt")
                val points = request.points ?: invalid("points", "required")
                if (points.signum() < 0 || points > question.points) invalid("points", "must be between 0 and ${question.points}")
                val reason = request.reason?.trim()?.takeIf { it.isNotEmpty() }
                val current = result.questions.first { it.questionId == question.id }
                val graded = attempts.grades(attempt.id).any { it.questionId == question.id }
                if (graded && reason == null) throw ConflictException("Cambiar una nota ya puesta exige un motivo")
                val now = Instant.now(clock)
                val comment = request.comment?.trim()?.takeIf { it.isNotEmpty() }
                attempts.addGrade(user.organizationId, attempt.id, UUID.fromString(question.id), points, comment, reason, user.userId, now)
                val summary =
                    AttemptGrader.summarize(
                        attempt.snapshot,
                        result.questions.map { if (it === current) it.copy(points = points, comment = comment) else it },
                    )
                attempts.saveResult(attempt.id, summary, now)
                attempt.copy(status = if (summary.pending) AttemptService.SUBMITTED else AttemptService.GRADED, result = summary)
            }
        return view(updated)
    }

    // un intento solo existe para el docente de su edición, y uno abierto solo se corrige cuando se envía
    private suspend fun reachable(attempt: Attempt?): Attempt {
        if (attempt == null || attempt.offeringId.toString() !in teaching.assigned()) throw NotFoundException(NOT_FOUND)
        return attempt
    }

    private suspend fun view(attempt: Attempt) =
        TeacherAttemptView(AttemptViews.teacher(attempt), attempt.enrollmentId.toString(), email(attempt), attempts.grades(attempt.id))

    private suspend fun email(attempt: Attempt): String? = records.get(ENROLLMENT, attempt.enrollmentId).attributes[EMAIL]?.toString()

    private fun invalid(
        field: String,
        message: String,
    ): Nothing = throw ValidationException("Invalid grade", field, message)

    companion object {
        const val ENROLLMENT = "matricula"
        const val LESSON = "leccion"
        const val TITLE = "titulo"
        const val EMAIL = "estudiante_correo"
        const val NOT_FOUND = "No encontrado"
        val MANUAL = setOf("OPEN", "FILE")
        val LISTABLE = setOf(AttemptService.SUBMITTED, AttemptService.GRADED)
    }
}

// lo que learning pregunta de las evaluaciones: sin alcance propio, quien llama ya comprobó a qué tiene derecho
@org.springframework.stereotype.Component
class AttemptFacts(
    private val attempts: AttemptStore,
) : AssessmentFacts {
    override suspend fun pendingAttempts(
        organization: UUID,
        offering: UUID,
    ): Int = attempts.countPending(organization, offering)

    override suspend fun assessments(
        organization: UUID,
        enrollment: UUID,
    ): List<LessonAssessmentFact> =
        attempts
            .ofEnrollment(organization, enrollment)
            .filter { it.status != AttemptService.OPEN }
            .groupBy { it.lessonId }
            .map { (lesson, tries) ->
                val graded = tries.mapNotNull { it.result }.filter { !it.pending }
                val best = graded.maxByOrNull { it.percent ?: 0 }
                LessonAssessmentFact(
                    lessonId = lesson.toString(),
                    title = tries.first().snapshot.title,
                    bestPercent = best?.percent,
                    passed = best?.passed,
                    pending = tries.any { it.status == AttemptService.SUBMITTED },
                )
            }
}
