package elearning.reputation

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// una cifra de un indicador: media simple y n de una dimensión, para un sujeto (la asignación docente o la versión de
// recurso). sin nadie dentro
data class Indicator(
    val subject: UUID,
    val dimension: String,
    val n: Int,
    val mean: BigDecimal?,
)

// los indicadores de las valoraciones (ADR-0038 §9–§11). se calculan con subconsultas sobre las tablas de Wasichai (como
// RatingFacts) y se guardan en lms.indicador_valoracion: los de una edición se congelan al liberarse (lms.liberacion_valoracion)
// y no se vuelven a calcular; los de recurso los rehace el trabajo de agregados
@Repository
class RatingIndicators(
    private val db: DatabaseClient,
    private val facts: RatingFacts,
) {
    // los de los docentes de una edición, al vuelo
    suspend fun teachersNow(
        organization: UUID,
        offering: UUID,
    ): List<Indicator> = live(organization, RatingWriteGuard.TEACHER, RatingFacts.ASSIGNMENT, offering)

    // los de todas las versiones de recurso valoradas de la organización, al vuelo
    suspend fun resourcesNow(organization: UUID): List<Indicator> = live(organization, RatingWriteGuard.RESOURCE, RatingFacts.RESOURCE_VERSION, null)

    private suspend fun live(
        organization: UUID,
        target: String,
        subjectField: String,
        offering: UUID?,
    ): List<Indicator> {
        val p = facts.table(organization, RatingFacts.SCORE)
        val v = facts.table(organization, RatingFacts.RATING)
        val d = facts.table(organization, RatingFacts.DIMENSION)
        val subject = v.column(subjectField)
        val ofOffering = offering?.let { " AND v.${v.column(RatingFacts.OFFERING_FIELD)} = :offering" }.orEmpty()
        val spec =
            db
                .sql(
                    "SELECT v.$subject AS subject, d.${d.column(RatingFacts.KEY)} AS dimension, count(*) AS n, avg(p.${p.column(VALUE)}) AS mean " +
                        "FROM ${p.name} p JOIN ${v.name} v ON v.\"id\" = p.${p.column(RatingFacts.RATING)} AND v.organization_id = :org " +
                        "JOIN ${d.name} d ON d.\"id\" = p.${p.column(DIMENSION)} AND d.organization_id = :org " +
                        "WHERE p.organization_id = :org AND v.${v.column(RatingFacts.TARGET)} = :target AND v.$subject IS NOT NULL$ofOffering " +
                        "GROUP BY v.$subject, d.${d.column(RatingFacts.KEY)}",
                ).bind("org", organization)
                .bind("target", target)
        return (if (offering == null) spec else spec.bind("offering", offering))
            .map { row, _ ->
                Indicator(
                    row.get("subject", UUID::class.java)!!,
                    row.get("dimension", String::class.java)!!,
                    row.get("n", Number::class.java)!!.toInt(),
                    row.get("mean", BigDecimal::class.java)?.setScale(2, RoundingMode.HALF_UP),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    // los comentarios publicados de las valoraciones de una asignación, en orden aleatorio, sin autor ni fecha
    suspend fun publishedComments(
        organization: UUID,
        offering: UUID,
        assignment: UUID,
    ): List<String> {
        val v = facts.table(organization, RatingFacts.RATING)
        return db
            .sql(
                "SELECT v.${v.column(RatingWriteGuard.COMMENT)} AS comment FROM ${v.name} v WHERE v.organization_id = :org " +
                    "AND v.${v.column(RatingFacts.OFFERING_FIELD)} = :offering AND v.${v.column(RatingFacts.ASSIGNMENT)} = :assignment " +
                    "AND v.${v.column(RatingWriteGuard.MODERATION)} = :published AND v.${v.column(RatingWriteGuard.COMMENT)} IS NOT NULL ORDER BY random()",
            ).bind("org", organization)
            .bind("offering", offering)
            .bind("assignment", assignment)
            .bind("published", RatingWriteGuard.PUBLISHED)
            .map { row, _ -> row.get("comment", String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()
    }

    suspend fun released(
        organization: UUID,
        offering: UUID,
    ): Boolean =
        db
            .sql("SELECT 1 AS found FROM lms.liberacion_valoracion WHERE organizacion_id = :org AND edicion_id = :offering")
            .bind("org", organization)
            .bind("offering", offering)
            .map { _, _ -> true }
            .one()
            .awaitSingleOrNull() == true

    // congela los indicadores de la edición, una vez: quien llega después encuentra la liberación y no escribe nada. va en la
    // transacción de quien llama
    suspend fun freeze(
        organization: UUID,
        offering: UUID,
        now: Instant,
    ): Boolean {
        val inserted =
            db
                .sql(
                    "INSERT INTO lms.liberacion_valoracion (organizacion_id, edicion_id, liberada_en) VALUES (:org, :offering, :at) " +
                        "ON CONFLICT DO NOTHING",
                ).bind("org", organization)
                .bind("offering", offering)
                .bind("at", now.atOffset(ZoneOffset.UTC))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        if (inserted == 0L) return false
        teachersNow(organization, offering).forEach { insert(organization, RatingWriteGuard.TEACHER, it, offering, frozen = true, now = now) }
        return true
    }

    // los de recurso, rehechos: lo que ya no tiene valoraciones desaparece
    suspend fun replaceResources(
        organization: UUID,
        now: Instant,
    ): Int {
        val fresh = resourcesNow(organization)
        db
            .sql("DELETE FROM lms.indicador_valoracion WHERE organizacion_id = :org AND destino = :target")
            .bind("org", organization)
            .bind("target", RatingWriteGuard.RESOURCE)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        fresh.forEach { insert(organization, RatingWriteGuard.RESOURCE, it, null, frozen = false, now = now) }
        return fresh.size
    }

    // los guardados de unos sujetos; de un docente, los congelados de esa edición
    suspend fun stored(
        organization: UUID,
        target: String,
        subjects: Collection<UUID>,
    ): List<Indicator> {
        if (subjects.isEmpty()) return emptyList()
        return db
            .sql(
                "SELECT sujeto_id, dimension, n, media FROM lms.indicador_valoracion " +
                    "WHERE organizacion_id = :org AND destino = :target AND sujeto_id = ANY(:subjects)",
            ).bind("org", organization)
            .bind("target", target)
            .bind("subjects", subjects.distinct().toTypedArray())
            .map { row, _ ->
                Indicator(
                    row.get("sujeto_id", UUID::class.java)!!,
                    row.get("dimension", String::class.java)!!,
                    row.get("n", Number::class.java)!!.toInt(),
                    row.get("media", BigDecimal::class.java),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    // cuándo se calcularon por última vez los de recurso de la organización
    suspend fun resourcesCalculatedAt(organization: UUID): Instant? =
        db
            .sql("SELECT max(calculado_en) AS at FROM lms.indicador_valoracion WHERE organizacion_id = :org AND destino = :target")
            .bind("org", organization)
            .bind("target", RatingWriteGuard.RESOURCE)
            .map { row, _ -> java.util.Optional.ofNullable(row.get("at", java.time.OffsetDateTime::class.java)) }
            .one()
            .awaitSingleOrNull()
            ?.orElse(null)
            ?.toInstant()

    private suspend fun insert(
        organization: UUID,
        target: String,
        indicator: Indicator,
        offering: UUID?,
        frozen: Boolean,
        now: Instant,
    ) {
        val spec =
            db
                .sql(
                    """
                    INSERT INTO lms.indicador_valoracion (organizacion_id, destino, sujeto_id, edicion_id, dimension, n, media, criterio, congelado, calculado_en)
                    VALUES (:org, :target, :subject, CAST(:offering AS uuid), :dimension, :n, :mean, :criterion, :frozen, :at)
                    """.trimIndent(),
                ).bind("org", organization)
                .bind("target", target)
                .bind("subject", indicator.subject)
                .bind("dimension", indicator.dimension)
                .bind("n", indicator.n)
                .bind("criterion", RatingCriterion.VERSION)
                .bind("frozen", frozen)
                .bind("at", now.atOffset(ZoneOffset.UTC))
        val withOffering = if (offering == null) spec.bindNull("offering", String::class.java) else spec.bind("offering", offering.toString())
        val withMean = if (indicator.mean == null) withOffering.bindNull("mean", BigDecimal::class.java) else withOffering.bind("mean", indicator.mean)
        withMean.fetch().rowsUpdated().awaitSingle()
    }

    private companion object {
        const val VALUE = "valor"
        const val DIMENSION = "dimension"
    }
}
