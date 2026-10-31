package elearning.authoring

import elearning.common.listAll
import elearning.common.ref
import elearning.course.ReviewDecisionWrite
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.time.Instant
import java.util.UUID

data class CommentRequest(
    val section: String? = null,
    val text: String? = null,
)

data class DecisionRequest(
    val decision: String? = null,
    val reason: String? = null,
)

// la bandeja del revisor (ADR-0018 §2, docs/api/lms.md). revisa quien tiene APROBAR sobre version_curso, no es el autor y,
// si la versión tiene revisor asignado, es esa persona: lo demás no existe aquí (404). el autor recibe 403, como en la
// guarda. todo se escribe como la persona, así que EditorialWorkflowGuard vuelve a comprobar cada transición
@Service
class ReviewService(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val metadata: MetadataService,
    private val reader: StudioReader,
    private val decisions: DecisionStore,
    private val differ: VersionDiffer,
    private val transactions: TransactionalOperator,
) {
    // en revisión y a mi cargo, la que más espera primero
    suspend fun inbox(): List<StudioVersion> {
        val user = reviewer()
        return records
            .listAll(VERSION, mapOf(STATE to IN_REVIEW))
            .filter { mine(it, user) }
            .sortedBy { it.updatedAt }
            .map { reader.summary(it) }
    }

    suspend fun detail(id: UUID): StudioVersionDetail = reader.detailOf(reviewable(id))

    suspend fun diff(id: UUID): VersionDiff = differ.againstPrevious(reviewable(id))

    suspend fun comment(
        id: UUID,
        request: CommentRequest,
    ): ReviewComment {
        val version = reviewable(id)
        val section = text(request.section, "section", MAX_SECTION)
        val body = text(request.text, "text", MAX_TEXT)
        if (version.attributes[STATE]?.toString() != IN_REVIEW) throw ConflictException("Solo se comenta una versión en revisión")
        val me = currentUser.require().userId.toString()
        val created =
            records.create(
                COMMENT,
                RecordRequest(mapOf(VERSION to version.id, "seccion" to section, "texto" to body, AUTHOR to me, RESOLVED to false)),
            )
        return reader.comment(created)
    }

    // marcar resuelto lo que ya lo está no cambia nada
    suspend fun resolve(id: UUID): ReviewComment {
        reviewer()
        val comment =
            try {
                records.get(COMMENT, id)
            } catch (_: ForbiddenException) {
                throw NotFoundException(COMMENT_NOT_FOUND)
            }
        val version = comment.attributes.ref(VERSION) ?: throw NotFoundException(COMMENT_NOT_FOUND)
        reviewable(UUID.fromString(version))
        if (reader.comment(comment).resolved) return reader.comment(comment)
        return reader.comment(records.patch(COMMENT, id, RecordRequest(mapOf(RESOLVED to true)), null, null))
    }

    // la transición y su decisión en la misma transacción: si una falla, no hay ninguna. la guarda solo admite decidir una
    // versión de curso con esta marca (ADR-0021): la API de registros no deja así una transición sin su decisión
    suspend fun decide(
        id: UUID,
        request: DecisionRequest,
    ): StudioVersion {
        val decision = request.decision?.takeIf { it in TARGETS } ?: invalid("decision", "must be one of ${TARGETS.keys}")
        val reason = request.reason?.trim()?.takeIf { it.isNotEmpty() }
        if (reason == null && decision != APPROVE) invalid("reason", "required unless approving")
        if (reason != null && reason.length > MAX_TEXT) invalid("reason", "at most $MAX_TEXT characters")
        val version = reviewable(id)
        val user = currentUser.require()
        val versionId = UUID.fromString(version.id)
        val updated =
            transactions.executeAndAwait {
                decisions.lock(versionId)
                val state = records.get(VERSION, versionId).attributes[STATE]?.toString()
                if (state != IN_REVIEW) throw ConflictException("Solo se decide una versión en revisión")
                val written =
                    withContext(ReviewDecisionWrite()) {
                        records.patch(VERSION, versionId, RecordRequest(mapOf(STATE to TARGETS.getValue(decision))), reason, null)
                    }
                decisions.insert(user.organizationId, versionId, decision, reason, user.userId, Instant.now())
                written
            }
        return reader.summary(updated)
    }

    // publicar lo aprobado: exige PUBLICAR; la fecha la pone el servidor
    suspend fun publish(id: UUID): StudioVersion {
        val user = currentUser.require()
        currentUser.requirePermission(user, PUBLISH, versionObject())
        val version = visible(id)
        if (version.attributes[STATE]?.toString() != APPROVED) throw ConflictException("Solo se publica una versión aprobada")
        val written =
            records.patch(
                VERSION,
                id,
                RecordRequest(mapOf(STATE to PUBLISHED, "publicada_en" to Instant.now().toString())),
                null,
                null,
            )
        return reader.summary(written)
    }

    private suspend fun reviewer(): AuthenticatedUser {
        val user = currentUser.require()
        currentUser.requirePermission(user, APPROVE, versionObject())
        return user
    }

    // una versión que esta persona revisa: el autor no (403); con revisor asignado, solo esa persona (lo demás, 404)
    private suspend fun reviewable(id: UUID): RecordResponse {
        val user = reviewer()
        val version = visible(id)
        val me = user.userId.toString()
        if (version.attributes[AUTHOR]?.toString() == me) throw ForbiddenException("Nadie revisa su propia versión")
        if (!mine(version, user)) throw NotFoundException(NOT_FOUND)
        return version
    }

    private suspend fun visible(id: UUID): RecordResponse =
        try {
            records.get(VERSION, id)
        } catch (_: ForbiddenException) {
            throw NotFoundException(NOT_FOUND)
        }

    private fun mine(
        version: RecordResponse,
        user: AuthenticatedUser,
    ): Boolean {
        val me = user.userId.toString()
        val author = version.attributes[AUTHOR]?.toString()
        val assigned = version.attributes[REVIEWER]?.toString()
        return author != null && author != me && (assigned == null || assigned == me)
    }

    // APROBAR y PUBLICAR se conceden sobre version_curso
    private suspend fun versionObject(): UUID = metadata.definitionOf(VERSION).obj.id

    private fun text(
        value: String?,
        field: String,
        max: Int,
    ): String {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: invalid(field, "required")
        if (trimmed.length > max) invalid(field, "at most $max characters")
        return trimmed
    }

    private fun invalid(
        field: String,
        message: String,
    ): Nothing = throw ValidationException("Invalid $field", field, message)

    companion object {
        const val VERSION = "version_curso"
        const val COMMENT = "comentario_revision"
        const val STATE = "estado"
        const val AUTHOR = "autor_id"
        const val REVIEWER = "revisor_id"
        const val RESOLVED = "resuelto"
        const val IN_REVIEW = "EN_REVISION"
        const val APPROVED = "APROBADA"
        const val PUBLISHED = "PUBLICADA"
        const val APPROVE = "APROBAR"
        const val PUBLISH = "PUBLICAR"
        const val MAX_SECTION = 200
        const val MAX_TEXT = 4000
        const val NOT_FOUND = "Versión no encontrada"
        const val COMMENT_NOT_FOUND = "Comentario no encontrado"

        // la decisión y el estado al que lleva la versión
        val TARGETS: Map<String, String> = mapOf(APPROVE to APPROVED, "PEDIR_CAMBIOS" to "BORRADOR", "RECHAZAR" to "RETIRADA")
    }
}
