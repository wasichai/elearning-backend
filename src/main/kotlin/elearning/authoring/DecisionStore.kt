package elearning.authoring

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// lms.decision_revision (ADR-0018 §2): solo inserción, siempre acotada a la organización y ligada a su versión
@Repository
class DecisionStore(
    private val db: DatabaseClient,
) {
    suspend fun insert(
        organization: UUID,
        version: UUID,
        decision: String,
        reason: String?,
        author: UUID,
        at: Instant,
    ) {
        val spec =
            db
                .sql(
                    """
                    INSERT INTO lms.decision_revision (organizacion_id, version_curso_id, decision, motivo, autor_id, decidida_en)
                    VALUES (:org, :version, :decision, :reason, :author, :at)
                    """.trimIndent(),
                ).bind("org", organization)
                .bind("version", version)
                .bind("decision", decision)
                .bind("author", author)
                .bind("at", at.atOffset(ZoneOffset.UTC))
        val withReason = reason?.let { spec.bind("reason", it) } ?: spec.bindNull("reason", String::class.java)
        withReason.fetch().rowsUpdated().awaitSingle()
    }

    suspend fun ofVersion(
        organization: UUID,
        version: UUID,
    ): List<ReviewDecision> =
        db
            .sql(
                """
                SELECT decision, motivo, autor_id, decidida_en FROM lms.decision_revision
                WHERE organizacion_id = :org AND version_curso_id = :version ORDER BY decidida_en, id
                """.trimIndent(),
            ).bind("org", organization)
            .bind("version", version)
            .map { row, _ ->
                ReviewDecision(
                    decision = row.get("decision", String::class.java)!!,
                    reason = row.get("motivo", String::class.java),
                    authorId = row.get("autor_id", UUID::class.java).toString(),
                    at = row.get("decidida_en", OffsetDateTime::class.java)!!.toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    // una decisión a la vez por versión, hasta el commit: dos revisores a la vez no deciden ambos sobre lo mismo
    suspend fun lock(version: UUID) {
        db
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .bind("key", "lms.decision_revision:$version")
            .fetch()
            .first()
            .awaitSingleOrNull()
    }
}
