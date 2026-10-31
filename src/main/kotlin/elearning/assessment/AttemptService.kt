package elearning.assessment

import elearning.common.ref
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// los intentos del estudiante (ADR-0016 §2). siempre los suyos: lo ajeno es 404, también para quien ve más. el servidor
// impone los intentos y el plazo: un intento vencido se cierra con lo guardado y ya no admite nada
@Service
class AttemptService(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val content: AssessmentContent,
    private val attempts: AttemptStore,
    private val submissions: SubmissionService,
    private val transactions: TransactionalOperator,
    private val db: DatabaseClient,
    private val clock: Clock,
) {
    data class Started(
        val attempt: Attempt,
        val created: Boolean,
    )

    private data class Outcome(
        val attempt: Attempt,
        val expired: Boolean,
    )

    suspend fun start(
        enrollmentId: UUID,
        lessonId: UUID,
    ): Started {
        val user = currentUser.require()
        val enrollment = records.get(ENROLLMENT, enrollmentId)
        if (enrollment.attributes[STUDENT]?.toString() != user.userId.toString()) throw NotFoundException(NOT_FOUND)
        if (enrollment.attributes[STATE]?.toString() != ACTIVE) throw ConflictException("La matrícula no está activa")
        val lesson = records.get(LESSON, lessonId)
        val unit = lesson.attributes.ref(UNIT)?.let { records.get(UNIT, UUID.fromString(it)) }
        if (unit == null || unit.attributes.ref(VERSION) != enrollment.attributes.ref(VERSION)) throw NotFoundException(NOT_FOUND)
        val offering = UUID.fromString(enrollment.attributes.ref(OFFERING)!!)

        // un intento vencido se cierra aparte: el 409 de «sin intentos» no debe deshacer su cierre
        transactions.executeAndAwait {
            attempts.open(enrollmentId, lessonId)?.let { if (expired(it, Instant.now(clock))) close(it, Instant.now(clock)) }
        }
        return transactions.executeAndAwait {
            db
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .bind("key", "lms.intento:$enrollmentId:$lessonId")
                .fetch()
                .first()
                .awaitSingleOrNull()
            val now = Instant.now(clock)
            val open = attempts.open(enrollmentId, lessonId)
            if (open != null && !expired(open, now)) return@executeAndAwait Started(open, created = false)
            if (open != null) close(open, now)
            val snapshot = content.snapshot(user.organizationId, lessonId) ?: throw ConflictException("Esta lección no tiene evaluación")
            val used = attempts.count(enrollmentId, lessonId)
            if (used >= snapshot.maxAttempts) throw ConflictException("Ya usaste todos los intentos de esta evaluación")
            val deadline = snapshot.timeLimitMinutes?.let { now.plus(it.toLong(), ChronoUnit.MINUTES) }
            Started(attempts.insert(user.organizationId, offering, enrollmentId, lessonId, user.userId, used + 1, snapshot, now, deadline), created = true)
        }
    }

    // con AL_ENVIAR, las soluciones se ven al aprobar o al agotar los intentos: antes servirían para aprobar el siguiente
    suspend fun solutionsOpen(attempt: Attempt): Boolean {
        if (attempt.snapshot.showSolutions != SHOW_ON_SUBMIT || attempt.status == OPEN) return false
        val tries = attempts.ofEnrollment(attempt.organizationId, attempt.enrollmentId).filter { it.lessonId == attempt.lessonId }
        return tries.size >= attempt.snapshot.maxAttempts || tries.any { it.result?.passed == true }
    }

    suspend fun read(id: UUID): Attempt = locked(id) { it }.attempt

    suspend fun saveAnswers(
        id: UUID,
        answers: List<AttemptAnswer>,
    ): Attempt =
        whileOpen(id) { attempt ->
            validate(attempt.snapshot, answers)
            validateSubmissions(attempt, answers)
            attempts.saveAnswers(attempt.id, answers)
            attempt.copy(answers = answers)
        }

    suspend fun submit(id: UUID): Attempt = whileOpen(id) { close(it, Instant.now(clock)) }

    // ejecuta sobre el intento abierto; si venció, lo cierra (eso se confirma) y después responde 409
    private suspend fun whileOpen(
        id: UUID,
        action: suspend (Attempt) -> Attempt,
    ): Attempt {
        val outcome =
            locked(id) { attempt ->
                if (attempt.status != OPEN) throw ConflictException("Este intento ya se envió")
                records.requireActiveEnrollment(attempt.enrollmentId)
                action(attempt)
            }
        if (outcome.expired) throw ConflictException("El plazo de este intento venció: se calificó con lo que tenías guardado")
        return outcome.attempt
    }

    private suspend fun locked(
        id: UUID,
        action: suspend (Attempt) -> Attempt,
    ): Outcome {
        val user = currentUser.require()
        return transactions.executeAndAwait {
            val attempt = attempts.lock(user.organizationId, id)
            if (attempt == null || attempt.studentId != user.userId) throw NotFoundException(NOT_FOUND)
            val now = Instant.now(clock)
            if (expired(attempt, now)) Outcome(close(attempt, now), expired = true) else Outcome(action(attempt), expired = false)
        }
    }

    private fun expired(
        attempt: Attempt,
        now: Instant,
    ) = attempt.status == OPEN && attempt.deadline != null && now.isAfter(attempt.deadline)

    private suspend fun close(
        attempt: Attempt,
        now: Instant,
    ): Attempt {
        val result = AttemptGrader.grade(attempt.snapshot, attempt.answers)
        attempts.saveResult(attempt.id, result, now)
        return attempt.copy(status = if (result.pending) SUBMITTED else GRADED, result = result, submittedAt = now)
    }

    private fun validate(
        snapshot: AssessmentSnapshot,
        answers: List<AttemptAnswer>,
    ) {
        val questions = snapshot.questions.associateBy { it.id }
        if (answers.map { it.questionId }.toSet().size != answers.size) invalid("una pregunta respondida dos veces")
        for (answer in answers) {
            val question = questions[answer.questionId] ?: invalid("pregunta desconocida ${answer.questionId}")
            val options = question.options.map { it.id }.toSet()
            if (!options.containsAll(answer.optionIds)) invalid("opción desconocida en ${answer.questionId}")
            if (question.type in SINGLE_CHOICE && answer.optionIds.size > 1) invalid("solo una opción en ${answer.questionId}")
            if (answer.text != null && question.type !in WRITTEN) invalid("esta pregunta no admite texto")
            if ((answer.text?.length ?: 0) > MAX_TEXT) invalid("respuesta demasiado larga en ${answer.questionId}")
            if (answer.submissionId != null && question.type != FILE) invalid("esta pregunta no admite archivo")
        }
    }

    // una respuesta de archivo apunta a una entrega de este intento y esta pregunta, con su archivo ya subido
    private suspend fun validateSubmissions(
        attempt: Attempt,
        answers: List<AttemptAnswer>,
    ) {
        val named = answers.filter { it.submissionId != null }
        if (named.isEmpty()) return
        val known = submissions.ofQuestion(attempt)
        for (answer in named) {
            if (known[answer.questionId]?.takeIf { it.id == answer.submissionId }?.file ==
                null
            ) {
                invalid("la entrega de ${answer.questionId} no existe o no tiene archivo")
            }
        }
    }

    private fun invalid(message: String): Nothing = throw ValidationException("Invalid answers", "answers", message)

    companion object {
        const val ENROLLMENT = "matricula"
        const val LESSON = "leccion"
        const val UNIT = "unidad"
        const val VERSION = "version_curso"
        const val OFFERING = "edicion"
        const val STUDENT = "estudiante_id"
        const val STATE = "estado"
        const val ACTIVE = "ACTIVA"
        const val OPEN = "OPEN"
        const val SHOW_ON_SUBMIT = "AL_ENVIAR"
        const val SUBMITTED = "SUBMITTED"
        const val GRADED = "GRADED"
        const val FILE = "FILE"
        const val NOT_FOUND = "No encontrado"
        const val MAX_TEXT = 20_000
        val SINGLE_CHOICE = setOf("SINGLE", "TRUE_FALSE")
        val WRITTEN = setOf("SHORT", "OPEN")
    }
}

// guardar, enviar y subir a un intento abierto solo con la matrícula activa: una retirada o finalizada ya no escribe
// (revisión de la Fase 3, L1). empezar ya lo comprobaba
internal suspend fun RecordService.requireActiveEnrollment(enrollment: UUID) {
    if (get(AttemptService.ENROLLMENT, enrollment).attributes[AttemptService.STATE]?.toString() != AttemptService.ACTIVE) {
        throw ConflictException("La matrícula no está activa")
    }
}
