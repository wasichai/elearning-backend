package elearning.reputation

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import wasichai.core.common.RetryLaterException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

// qué se cuenta por día
enum class QuotaAction { CAMBIO, DENUNCIA }

// los límites diarios de RNF-SEG-10 (ADR-0038 §19) en lms.cuota_valoracion. consume suma uno y, pasado el límite, responde
// 429 con Retry-After hasta el día siguiente (UTC). va en la transacción del cambio: si el cambio falla, o el límite lo
// corta, la suma se deshace y no cuenta
@Repository
class RatingQuota(
    private val db: DatabaseClient,
    private val clock: Clock,
) {
    suspend fun consume(
        organization: UUID,
        user: UUID,
        action: QuotaAction,
        limit: Int,
    ) {
        val now = clock.instant()
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val used =
            db
                .sql(
                    """
                    INSERT INTO lms.cuota_valoracion (organizacion_id, usuario_id, dia, accion, usos) VALUES (:org, :user, :day, :action, 1)
                    ON CONFLICT (organizacion_id, usuario_id, dia, accion) DO UPDATE SET usos = lms.cuota_valoracion.usos + 1
                    RETURNING usos
                    """.trimIndent(),
                ).bind("org", organization)
                .bind("user", user)
                .bind("day", today)
                .bind("action", action.name)
                .map { row, _ -> row.get("usos", Number::class.java)!!.toInt() }
                .one()
                .awaitSingle()
        if (used > limit) {
            val tomorrow = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
            throw RetryLaterException(
                "Daily limit of $limit reached; try again tomorrow (UTC)",
                Duration.between(now, tomorrow).plusSeconds(1),
                status = HttpStatus.TOO_MANY_REQUESTS,
            )
        }
    }
}
