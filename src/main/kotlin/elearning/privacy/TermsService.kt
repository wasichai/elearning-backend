package elearning.privacy

import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.RetryLaterException
import wasichai.core.common.ValidationException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class TermsVersion(
    val version: Int,
    val text: String,
    val retentionDays: Int,
    val publishedAt: Instant,
)

// valid: sin retirar y dentro de su plazo. sin aceptación vigente no hay matrícula ni progreso
data class TermsAcceptance(
    val version: Int,
    val acceptedAt: Instant,
    val expiresAt: Instant,
    val withdrawnAt: Instant?,
    val valid: Boolean,
)

// los términos vigentes (null si la organización aún no publicó) y la aceptación de quien pregunta (null si no tiene)
data class TermsView(
    val current: TermsVersion?,
    val acceptance: TermsAcceptance?,
)

data class PublishTermsRequest(
    val text: String? = null,
    val retentionDays: Int? = null,
)

data class AcceptTermsRequest(
    val version: Int? = null,
)

// la supresión encolada al retirar la aceptación: se sigue como cualquier trabajo de la cola
data class TermsWithdrawal(
    val jobId: UUID,
)

// los términos de uso y su aceptación (ADR-0030, ADR-0037, RNF-SEG-11). los publica quien administra la organización
// (MANAGE_ORGANIZATION, de Wasichai); cada persona acepta la versión vigente, con el plazo de conservación que esa versión
// ofrece, y puede retirarla: retirarla, o que venza, encola la supresión de sus datos personales (PersonalDataErasure).
// publicar otra versión no cambia ninguna aceptación: cada una sigue con la versión y el plazo que la persona aprobó
@Service
class TermsService(
    private val currentUser: CurrentUser,
    private val store: TermsStore,
    private val erasure: PersonalDataErasure,
    private val transactions: TransactionalOperator,
    private val clock: Clock,
) {
    suspend fun view(): TermsView {
        val user = currentUser.require()
        return TermsView(store.current(user.organizationId)?.terms, store.acceptance(user.organizationId, user.userId, Instant.now(clock)))
    }

    suspend fun publish(request: PublishTermsRequest): TermsVersion {
        val user = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        val text = request.text?.trim().orEmpty()
        val days = request.retentionDays
        val problems =
            listOfNotNull(
                FieldViolation("text", "must have between 1 and $MAX_TEXT characters").takeIf { text.isEmpty() || text.length > MAX_TEXT },
                FieldViolation("retentionDays", "must be a number of days between 1 and $MAX_RETENTION_DAYS").takeIf {
                    days == null || days !in 1..MAX_RETENTION_DAYS
                },
            )
        if (problems.isNotEmpty()) throw ValidationException("Invalid terms of use", problems)
        return transactions.executeAndAwait { store.publish(user.organizationId, text, days!!, Instant.now(clock)) }
    }

    // acepta la versión vigente, que quien acepta nombra: si se publicó otra mientras la leía, 409. aceptar con una vigente
    // la renueva con la versión y el plazo de ahora. con una retirada o vencida, sus datos se están suprimiendo: se encola
    // (o se reencuentra) la supresión y se pide volver después
    suspend fun accept(request: AcceptTermsRequest): TermsAcceptance {
        val user = currentUser.require()
        val version = request.version ?: throw ValidationException("Invalid acceptance", "version", "must be the version of the current terms")
        val current = store.current(user.organizationId) ?: throw ConflictException("La organización aún no publicó sus términos de uso")
        if (version != current.terms.version) {
            throw ConflictException("Los términos de uso vigentes son la versión ${current.terms.version}: léela antes de aceptarla")
        }
        val now = Instant.now(clock)
        val expires = now.plus(Duration.ofDays(current.terms.retentionDays.toLong()))
        if (!store.accept(user.organizationId, user.userId, current.id, now, expires)) {
            erasure.enqueue(user.organizationId, user.userId, ErasureReason.EXPIRED)
            throw RetryLaterException("Tus datos de la aceptación anterior se están suprimiendo: vuelve a aceptar en unos minutos", RETRY_AFTER)
        }
        return store.acceptance(user.organizationId, user.userId, now)!!
    }

    // retira la aceptación y encola la supresión, en una transacción: o las dos cosas o ninguna. repetirlo devuelve el
    // mismo trabajo mientras no termine; terminado, ya no hay aceptación (404)
    suspend fun withdraw(): TermsWithdrawal {
        val user = currentUser.require()
        return transactions.executeAndAwait {
            val withdrawn = store.withdraw(user.organizationId, user.userId, Instant.now(clock))
            if (!withdrawn) throw NotFoundException("No tienes una aceptación de los términos de uso")
            TermsWithdrawal(erasure.enqueue(user.organizationId, user.userId, ErasureReason.WITHDRAWN))
        }
    }

    // la puerta de lo que trata los datos de la persona (matrícula, progreso): sin aceptación vigente, 403 con el motivo
    suspend fun requireAccepted(
        user: AuthenticatedUser,
        doing: String,
    ) {
        if (!store.isValid(user.organizationId, user.userId, Instant.now(clock))) {
            throw ForbiddenException("Para $doing debes aceptar los términos de uso vigentes (o renovarlos si tu aceptación venció o la retiraste)")
        }
    }

    companion object {
        const val MAX_TEXT = 100_000

        // el mismo tope que lms.terminos_uso (V15) y model/apply.py: cien años
        const val MAX_RETENTION_DAYS = 36_500

        // lo que tarda una supresión corta en la cola
        private val RETRY_AFTER: Duration = Duration.ofMinutes(1)
    }
}
