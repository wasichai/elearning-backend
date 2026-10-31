package elearning.assessment

import elearning.common.listAll
import elearning.common.ref
import elearning.learning.FileView
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard
import wasichai.core.identity.CurrentUser
import java.time.Clock
import java.time.Instant
import java.util.UUID

// las entregas de archivo (ADR-0016 §3). se leen como la persona: el alcance deja ver al estudiante las suyas y al docente
// las de su edición. el archivo se sube por la ruta de wasichai-files, que pasa por SubmissionGuard
@Service
class SubmissionService(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val attempts: AttemptStore,
    private val clock: Clock,
) {
    suspend fun create(
        attemptId: UUID,
        questionId: UUID,
    ): SubmissionView {
        val user = currentUser.require()
        val attempt = attempts.find(user.organizationId, attemptId)
        if (attempt == null || attempt.studentId != user.userId) throw NotFoundException(NOT_FOUND)
        if (attempt.status != AttemptService.OPEN ||
            attempt.deadline?.let { Instant.now(clock).isAfter(it) } == true
        ) {
            throw ConflictException("Este intento ya no admite entregas")
        }
        if (attempt.snapshot.questions.none { it.id == questionId.toString() && it.type == AttemptService.FILE }) {
            throw ValidationException("Invalid submission", "questionId", "must be a file question of this attempt")
        }
        records.requireActiveEnrollment(attempt.enrollmentId)
        val existing = records.listAll(OBJECT, mapOf(ATTEMPT to attemptId.toString(), QUESTION to questionId.toString())).singleOrNull()
        val record =
            existing ?: records.create(
                OBJECT,
                RecordRequest(
                    mapOf(
                        ENROLLMENT to attempt.enrollmentId.toString(),
                        STUDENT to user.userId.toString(),
                        ATTEMPT to attemptId.toString(),
                        QUESTION to questionId.toString(),
                    ),
                ),
            )
        return SubmissionView(record.id, file(record), fileUrl(record.id))
    }

    // las entregas del intento por su id, para pintar las respuestas
    suspend fun of(attempt: Attempt): Map<String, SubmissionRef> = ofQuestion(attempt).values.associateBy { it.id }

    // las entregas del intento por pregunta
    suspend fun ofQuestion(attempt: Attempt): Map<String, SubmissionRef> {
        if (attempt.snapshot.questions.none { it.type == AttemptService.FILE }) return emptyMap()
        return records
            .listAll(
                OBJECT,
                mapOf(ATTEMPT to attempt.id.toString()),
            ).associate { it.attributes[QUESTION].toString() to SubmissionRef(it.id, file(it)) }
    }

    private fun file(record: RecordResponse): FileView? =
        (record.attributes[FILE] as? Map<*, *>)?.let {
            FileView(it["name"]?.toString().orEmpty(), it["contentType"]?.toString().orEmpty(), (it["size"] as? Number)?.toLong() ?: 0, fileUrl(record.id))
        }

    private fun fileUrl(id: String) = "/api/objects/$OBJECT/records/$id/files/$FILE"

    companion object {
        const val OBJECT = "entrega"
        const val ENROLLMENT = "matricula"
        const val STUDENT = "estudiante_id"
        const val ATTEMPT = "intento_id"
        const val QUESTION = "pregunta_id"
        const val FILE = "archivo"
        const val NOT_FOUND = "No encontrado"
    }
}

// entrega no es apiOnly porque la subida de wasichai-files es un PATCH de la API genérica: esta guarda exige, por cualquier
// ruta, que sea de quien escribe, de su intento abierto y de una pregunta de archivo, y que solo cambie el archivo
@Component
class SubmissionGuard(
    private val attempts: AttemptStore,
    private val clock: Clock,
    // RecordService recibe la lista de guardas: se pide perezosamente para no formar un ciclo
    private val records: ObjectProvider<RecordService>,
) : RecordWriteGuard {
    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.objectName != SubmissionService.OBJECT || change.userId == null) return
        if (change.kind == RecordChangeKind.DELETED) throw ConflictException("Una entrega no se borra")
        val before = change.before.orEmpty()
        val after = change.attributes.orEmpty()
        if (change.kind != RecordChangeKind.CREATED && FIXED.any { after.containsKey(it) && after.ref(it) != before.ref(it) }) {
            throw ConflictException("De una entrega solo cambia su archivo")
        }
        val source = if (change.kind == RecordChangeKind.CREATED) after else before
        val id = source.ref(SubmissionService.ATTEMPT)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val attempt = id?.let { attempts.find(change.organizationId, it) } ?: throw ForbiddenException(OWN_ONLY)
        if (attempt.studentId != change.userId || source.ref(SubmissionService.STUDENT) != change.userId.toString()) throw ForbiddenException(OWN_ONLY)
        if (source.ref(SubmissionService.ENROLLMENT) != attempt.enrollmentId.toString()) throw ForbiddenException(OWN_ONLY)
        if (attempt.snapshot.questions.none { it.id == source.ref(SubmissionService.QUESTION) && it.type == AttemptService.FILE }) {
            throw ConflictException("Esa pregunta no admite archivo")
        }
        if (attempt.status != AttemptService.OPEN || attempt.deadline?.let { Instant.now(clock).isAfter(it) } == true) {
            throw ConflictException("Este intento ya no admite entregas")
        }
        records.getObject().requireActiveEnrollment(attempt.enrollmentId)
    }

    companion object {
        const val OWN_ONLY = "Una entrega es de su estudiante y de su intento"
        val FIXED = listOf(SubmissionService.ENROLLMENT, SubmissionService.STUDENT, SubmissionService.ATTEMPT, SubmissionService.QUESTION)
    }
}
