package elearning.privacy

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// una versión de los términos tal como se guardó: id es la fila a la que apunta una aceptación
data class StoredTerms(
    val id: UUID,
    val terms: TermsVersion,
)

// una aceptación que hay que suprimir: retirada, o vencida su plazo
data class DueErasure(
    val organization: UUID,
    val user: UUID,
    val reason: ErasureReason,
)

// lms.terminos_uso y lms.aceptacion_terminos (V15, ADR-0037). toda lectura dentro de su organización, salvo el barrido
// de las vencidas, que recorre todas
@Repository
class TermsStore(
    private val db: DatabaseClient,
) {
    // la vigente: la de versión más alta
    suspend fun current(organization: UUID): StoredTerms? =
        db
            .sql(
                """
                SELECT id, version, texto, plazo_dias, publicada_en FROM lms.terminos_uso
                WHERE organizacion_id = :org ORDER BY version DESC LIMIT 1
                """.trimIndent(),
            ).bind("org", organization)
            .map { row, _ -> StoredTerms(row.get("id", UUID::class.java)!!, terms(row)) }
            .one()
            .awaitSingleOrNull()

    // la versión siguiente a la vigente. dentro de una transacción: el cerrojo la serializa con otra publicación a la vez
    suspend fun publish(
        organization: UUID,
        text: String,
        retentionDays: Int,
        at: Instant,
    ): TermsVersion {
        db
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .bind("key", "terminos_uso:$organization")
            .fetch()
            .first()
            .awaitSingleOrNull()
        return db
            .sql(
                """
                INSERT INTO lms.terminos_uso (organizacion_id, version, texto, plazo_dias, publicada_en)
                SELECT :org, coalesce(max(version), 0) + 1, :text, :days, :at FROM lms.terminos_uso WHERE organizacion_id = :org
                RETURNING version, texto, plazo_dias, publicada_en
                """.trimIndent(),
            ).bind("org", organization)
            .bind("text", text)
            .bind("days", retentionDays)
            .bind("at", utc(at))
            .map { row, _ -> terms(row) }
            .one()
            .awaitSingle()
    }

    suspend fun acceptance(
        organization: UUID,
        user: UUID,
        now: Instant,
    ): TermsAcceptance? =
        db
            .sql(
                """
                SELECT t.version, a.aceptada_en, a.vence_en, a.retirada_en FROM lms.aceptacion_terminos a
                JOIN lms.terminos_uso t ON t.id = a.terminos_id
                WHERE a.organizacion_id = :org AND a.usuario_id = :user
                """.trimIndent(),
            ).bind("org", organization)
            .bind("user", user)
            .map { row, _ ->
                val expires = instant(row, "vence_en")!!
                val withdrawn = instant(row, "retirada_en")
                TermsAcceptance(
                    version = row.get("version", Number::class.java)!!.toInt(),
                    acceptedAt = instant(row, "aceptada_en")!!,
                    expiresAt = expires,
                    withdrawnAt = withdrawn,
                    valid = withdrawn == null && expires.isAfter(now),
                )
            }.one()
            .awaitSingleOrNull()

    // vigente: aceptada, sin retirar y dentro de su plazo
    suspend fun isValid(
        organization: UUID,
        user: UUID,
        now: Instant,
    ): Boolean =
        db
            .sql(
                """
                SELECT count(*) AS vigentes FROM lms.aceptacion_terminos
                WHERE organizacion_id = :org AND usuario_id = :user AND retirada_en IS NULL AND vence_en > :now
                """.trimIndent(),
            ).bind("org", organization)
            .bind("user", user)
            .bind("now", utc(now))
            .map { row, _ -> row.get("vigentes", Number::class.java)!!.toLong() > 0 }
            .one()
            .awaitSingle()

    // acepta o renueva: la aceptación pasa a esta versión con su plazo desde ahora. false si la que había está retirada o
    // vencida: sus datos se están suprimiendo y no se toca
    suspend fun accept(
        organization: UUID,
        user: UUID,
        terms: UUID,
        at: Instant,
        expires: Instant,
    ): Boolean =
        db
            .sql(
                """
                INSERT INTO lms.aceptacion_terminos (organizacion_id, usuario_id, terminos_id, aceptada_en, vence_en)
                VALUES (:org, :user, :terms, :at, :expires)
                ON CONFLICT (organizacion_id, usuario_id) DO UPDATE
                    SET terminos_id = excluded.terminos_id, aceptada_en = excluded.aceptada_en, vence_en = excluded.vence_en
                    WHERE lms.aceptacion_terminos.retirada_en IS NULL AND lms.aceptacion_terminos.vence_en > excluded.aceptada_en
                """.trimIndent(),
            ).bind("org", organization)
            .bind("user", user)
            .bind("terms", terms)
            .bind("at", utc(at))
            .bind("expires", utc(expires))
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    // marca la retirada (la primera fecha se queda). false: no había aceptación
    suspend fun withdraw(
        organization: UUID,
        user: UUID,
        at: Instant,
    ): Boolean =
        db
            .sql(
                """
                UPDATE lms.aceptacion_terminos SET retirada_en = coalesce(retirada_en, :at)
                WHERE organizacion_id = :org AND usuario_id = :user
                """.trimIndent(),
            ).bind("org", organization)
            .bind("user", user)
            .bind("at", utc(at))
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    // lo último de una supresión: la aceptación misma también es un dato personal
    suspend fun delete(
        organization: UUID,
        user: UUID,
    ): Long =
        db
            .sql("DELETE FROM lms.aceptacion_terminos WHERE organizacion_id = :org AND usuario_id = :user")
            .bind("org", organization)
            .bind("user", user)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    // las retiradas y las vencidas de todas las organizaciones. una retirada sigue aquí hasta que su supresión termina:
    // si ese trabajo falló, el barrido lo vuelve a encolar
    suspend fun due(now: Instant): List<DueErasure> =
        db
            .sql(
                """
                SELECT organizacion_id, usuario_id, retirada_en IS NOT NULL AS retirada FROM lms.aceptacion_terminos
                WHERE retirada_en IS NOT NULL OR vence_en <= :now
                """.trimIndent(),
            ).bind("now", utc(now))
            .map { row, _ ->
                DueErasure(
                    organization = row.get("organizacion_id", UUID::class.java)!!,
                    user = row.get("usuario_id", UUID::class.java)!!,
                    reason = if (row.get("retirada", Boolean::class.javaObjectType) == true) ErasureReason.WITHDRAWN else ErasureReason.EXPIRED,
                )
            }.all()
            .collectList()
            .awaitSingle()

    private fun terms(row: Readable) =
        TermsVersion(
            version = row.get("version", Number::class.java)!!.toInt(),
            text = row.get("texto", String::class.java)!!,
            retentionDays = row.get("plazo_dias", Number::class.java)!!.toInt(),
            publishedAt = instant(row, "publicada_en")!!,
        )

    private fun instant(
        row: Readable,
        column: String,
    ): Instant? = row.get(column, OffsetDateTime::class.java)?.toInstant()

    private fun utc(at: Instant): OffsetDateTime = at.atOffset(ZoneOffset.UTC)
}
