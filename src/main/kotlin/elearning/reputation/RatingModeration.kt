package elearning.reputation

import elearning.common.listAll
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
import java.time.Clock
import java.time.Instant
import java.util.UUID

// lo que ve quien modera: ni quién escribió ni quién denunció
data class ModerationItem(
    val id: String,
    val type: String?,
    val comment: String?,
    val moderation: String?,
    val reason: String?,
    val openReports: Int,
    val appeal: String?,
    val appealPending: Boolean,
)

// PUBLISH o HIDE; reason, uno de los motivos tasados, obligatorio al ocultar
data class ModerationDecision(
    val decision: String? = null,
    val reason: String? = null,
)

// UPHOLD (sigue oculto) o REVERSE (se publica)
data class AppealDecision(
    val decision: String? = null,
)

data class AppealRequest(
    val text: String? = null,
)

data class ReportRequest(
    val reason: String? = null,
)

data class ReportView(
    val status: String,
)

// moderación, denuncias y apelación de los comentarios (RF-REP-04, ADR-0038 §13–§19). todo como la persona: quien modera
// tiene MODERAR y RatingWriteGuard le impide moderar lo propio o lo que lo tiene como destino, también siendo ADMIN; la
// apelación la decide otro moderador, una vez. el valorado denuncia por la ficha opaca de un comentario que ve, sin saber
// de quién es; la denuncia no oculta nada y cuenta en su límite diario. el autor apela una vez lo que se le ocultó
@Service
class RatingModeration(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val metadata: MetadataService,
    private val facts: RatingFacts,
    private val quota: RatingQuota,
    private val transactions: TransactionalOperator,
    private val properties: ReputationProperties,
    private val clock: Clock,
) {
    // lo pendiente: comentarios por moderar, denunciados o con apelación sin decidir; nunca lo que lo tiene a uno como
    // destino ni lo que escribió
    suspend fun queue(): List<ModerationItem> {
        val user = holder(MODERATE)
        val org = user.organizationId
        val reports =
            records
                .listAll(REPORT, mapOf(STATE to OPEN))
                .mapNotNull { RatingWriteGuard.uuidOf(it.attributes[RatingWriteGuard.REPORTED]) }
                .groupingBy { it }
                .eachCount()
        return records
            .listAll(RatingFacts.RATING)
            .filter { r ->
                val a = r.attributes
                val pending = RatingWriteGuard.text(a, RatingWriteGuard.MODERATION) == RatingWriteGuard.PENDING
                val appeal = RatingWriteGuard.text(a, RatingWriteGuard.APPEAL) != null && RatingWriteGuard.text(a, RatingWriteGuard.DECIDED_BY) == null
                pending || appeal || reports.containsKey(UUID.fromString(r.id))
            }.filter { mine(org, user, UUID.fromString(it.id)) == null }
            .map { r ->
                val a = r.attributes
                ModerationItem(
                    id = r.id,
                    type = a[RatingFacts.TARGET]?.toString(),
                    comment = RatingWriteGuard.text(a, RatingWriteGuard.COMMENT),
                    moderation = RatingWriteGuard.text(a, RatingWriteGuard.MODERATION),
                    reason = RatingWriteGuard.text(a, RatingWriteGuard.REASON),
                    openReports = reports[UUID.fromString(r.id)] ?: 0,
                    appeal = RatingWriteGuard.text(a, RatingWriteGuard.APPEAL),
                    appealPending = RatingWriteGuard.text(a, RatingWriteGuard.APPEAL) != null && RatingWriteGuard.text(a, RatingWriteGuard.DECIDED_BY) == null,
                )
            }
    }

    suspend fun decide(
        id: UUID,
        request: ModerationDecision,
    ): ModerationItem {
        val user = holder(MODERATE)
        val rating = moderable(user, id)
        if (RatingWriteGuard.text(rating.attributes, RatingWriteGuard.COMMENT) == null) throw ConflictException("Only a comment is moderated")
        val changes =
            when (request.decision) {
                PUBLISH -> mapOf(RatingWriteGuard.MODERATION to RatingWriteGuard.PUBLISHED, RatingWriteGuard.REASON to null)
                HIDE -> {
                    val reason = request.reason?.trim()
                    if (reason !in RatingWriteGuard.REASONS) invalid("reason", "hiding needs one of ${RatingWriteGuard.REASONS.joinToString()}")
                    mapOf(RatingWriteGuard.MODERATION to RatingWriteGuard.HIDDEN, RatingWriteGuard.REASON to reason)
                }
                else -> invalid("decision", "must be $PUBLISH or $HIDE")
            } + mapOf(RatingWriteGuard.MODERATED_BY to user.userId.toString(), RatingWriteGuard.MODERATED_AT to Instant.now(clock).toString())
        transactions.executeAndAwait {
            records.patch(RatingFacts.RATING, id, RecordRequest(changes))
            resolveReports(user, id)
        }
        return item(records.get(RatingFacts.RATING, id))
    }

    suspend fun decideAppeal(
        id: UUID,
        request: AppealDecision,
    ): ModerationItem {
        val user = holder(MODERATE)
        val rating = moderable(user, id)
        val result =
            when (request.decision) {
                UPHOLD -> mapOf(RatingWriteGuard.APPEAL_RESULT to UPHELD)
                REVERSE ->
                    mapOf(
                        RatingWriteGuard.APPEAL_RESULT to REVERSED,
                        RatingWriteGuard.MODERATION to RatingWriteGuard.PUBLISHED,
                        RatingWriteGuard.REASON to null,
                    )
                else -> invalid("decision", "must be $UPHOLD or $REVERSE")
            }
        if (RatingWriteGuard.text(rating.attributes, RatingWriteGuard.APPEAL) == null) throw ConflictException("There is no appeal to decide")
        records.patch(RatingFacts.RATING, id, RecordRequest(result + mapOf(RatingWriteGuard.DECIDED_BY to user.userId.toString())))
        return item(records.get(RatingFacts.RATING, id))
    }

    // el autor apela una vez lo oculto (regla 17); lo propio, por su alcance: lo ajeno no existe
    suspend fun appeal(
        id: UUID,
        request: AppealRequest,
    ): RatingAppealView {
        val user = holder(RatingService.VALUE_ACTION)
        val rating = records.get(RatingFacts.RATING, id)
        if (RatingWriteGuard.uuidOf(rating.attributes[RatingFacts.STUDENT]) != user.userId) throw NotFoundException("Rating not found")
        val text = request.text?.trim()?.takeIf { it.isNotEmpty() } ?: invalid("text", "an appeal says why")
        if (text.length > RatingService.MAX_COMMENT) invalid("text", "at most ${RatingService.MAX_COMMENT} characters")
        if (RatingWriteGuard.text(rating.attributes, RatingWriteGuard.APPEAL) != null) throw ConflictException("A hidden comment is appealed only once")
        if (RatingWriteGuard.text(rating.attributes, RatingWriteGuard.MODERATION) != RatingWriteGuard.HIDDEN) {
            throw ConflictException("Only a hidden comment is appealed")
        }
        records.patch(
            RatingFacts.RATING,
            id,
            RecordRequest(mapOf(RatingWriteGuard.APPEAL to text, RatingWriteGuard.APPEALED_AT to Instant.now(clock).toString())),
        )
        return RatingAppealView(id.toString(), text, pending = true)
    }

    // el valorado denuncia un comentario publicado que lo tiene como destino, por su ficha; otra ficha, 404
    suspend fun report(
        token: UUID,
        request: ReportRequest,
    ): ReportView {
        val user = holder(REPORT_ACTION)
        val org = user.organizationId
        val rating = facts.rating(org, token, byToken = true)
        if (rating == null || rating.state != RatingWriteGuard.PUBLISHED || facts.ratedUser(org, rating) != user.userId) {
            throw NotFoundException("Comment not found")
        }
        val reason = request.reason?.trim()?.takeIf { it.isNotEmpty() }
        if (reason != null && reason.length > RatingService.MAX_COMMENT) invalid("reason", "at most ${RatingService.MAX_COMMENT} characters")
        transactions.executeAndAwait {
            quota.consume(org, user.userId, QuotaAction.DENUNCIA, properties.maxReportsPerDay)
            records.create(
                REPORT,
                RecordRequest(
                    mapOf(
                        RatingWriteGuard.REPORTED to rating.id.toString(),
                        RatingWriteGuard.REPORTER to user.userId.toString(),
                        "motivo" to reason,
                        STATE to OPEN,
                    ),
                ),
            )
        }
        return ReportView(OPEN)
    }

    // las denuncias abiertas de la valoración quedan resueltas con la decisión
    private suspend fun resolveReports(
        user: AuthenticatedUser,
        rating: UUID,
    ) {
        records
            .listAll(REPORT, mapOf(RatingWriteGuard.REPORTED to rating.toString(), STATE to OPEN))
            .forEach { records.patch(REPORT, UUID.fromString(it.id), RecordRequest(mapOf(STATE to RESOLVED, "resuelta_por" to user.userId.toString()))) }
    }

    // una valoración que quien modera puede moderar: la lee (su alcance) y no es suya ni lo tiene como destino
    private suspend fun moderable(
        user: AuthenticatedUser,
        id: UUID,
    ): RecordResponse {
        // antes de leerla: a un moderador delegado su alcance le esconde lo que lo tiene como destino, y la respuesta es 403
        mine(user.organizationId, user, id)?.let { throw if (it == NOT_FOUND) NotFoundException(it) else ForbiddenException(it) }
        return records.get(RatingFacts.RATING, id)
    }

    // por qué no la puede moderar, o null
    private suspend fun mine(
        org: UUID,
        user: AuthenticatedUser,
        id: UUID,
    ): String? {
        val rating = facts.rating(org, id) ?: return NOT_FOUND
        if (rating.student == user.userId) return "Nobody moderates their own rating"
        if (facts.ratedUser(org, rating) == user.userId) return "Nobody moderates a rating about themselves"
        return null
    }

    private fun item(r: RecordResponse): ModerationItem {
        val a = r.attributes
        return ModerationItem(
            id = r.id,
            type = a[RatingFacts.TARGET]?.toString(),
            comment = RatingWriteGuard.text(a, RatingWriteGuard.COMMENT),
            moderation = RatingWriteGuard.text(a, RatingWriteGuard.MODERATION),
            reason = RatingWriteGuard.text(a, RatingWriteGuard.REASON),
            openReports = 0,
            appeal = RatingWriteGuard.text(a, RatingWriteGuard.APPEAL),
            appealPending = RatingWriteGuard.text(a, RatingWriteGuard.APPEAL) != null && RatingWriteGuard.text(a, RatingWriteGuard.DECIDED_BY) == null,
        )
    }

    private suspend fun holder(action: String): AuthenticatedUser {
        val user = currentUser.require()
        currentUser.requirePermission(user, action, metadata.loadDefinition(user.organizationId, RatingFacts.RATING).obj.id)
        return user
    }

    private fun invalid(
        field: String,
        reason: String,
    ): Nothing = throw ValidationException("Invalid moderation", field, reason)

    companion object {
        const val MODERATE = "MODERAR"
        const val NOT_FOUND = "Rating not found"
        const val REPORT_ACTION = "DENUNCIAR"
        const val REPORT = "denuncia_valoracion"
        const val STATE = "estado"
        const val OPEN = "ABIERTA"
        const val RESOLVED = "RESUELTA"
        const val PUBLISH = "PUBLISH"
        const val HIDE = "HIDE"
        const val UPHOLD = "UPHOLD"
        const val REVERSE = "REVERSE"
        const val UPHELD = "MANTENIDA"
        const val REVERSED = "REVOCADA"
    }
}

data class RatingAppealView(
    val ratingId: String,
    val text: String,
    val pending: Boolean,
)
