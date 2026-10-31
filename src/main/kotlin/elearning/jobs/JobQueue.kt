package elearning.jobs

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// lms.trabajo (V9, ADR-0018 §4). encolar es idempotente por clave; reclamar usa FOR UPDATE SKIP LOCKED, así que varias
// réplicas nunca toman el mismo trabajo. terminar exige seguir siendo el dueño del reclamo (estado e intento): una réplica
// que perdió su reclamo no pisa el resultado de la que lo retomó
@Repository
class JobQueue(
    private val db: DatabaseClient,
    private val properties: JobProperties,
    private val clock: Clock,
) {
    // el id del trabajo; con la misma clave en la misma organización y tipo, el del que ya estaba, sea cual sea su estado.
    // payload es JSON; maxAttempts null: lms.jobs.max-attempts. el valor por defecto no puede leer properties: la llamada
    // con defecto corre sobre el proxy de @Repository, que no tiene los campos
    suspend fun enqueue(
        organization: UUID,
        type: String,
        payload: String = "{}",
        key: String? = null,
        maxAttempts: Int? = null,
    ): UUID {
        require(type.isNotBlank()) { "job type must not be blank" }
        val max = maxAttempts ?: properties.maxAttempts
        require(max > 0) { "maxAttempts must be positive" }
        val now = now()
        val spec =
            db
                .sql(
                    """
                    INSERT INTO lms.trabajo (organizacion_id, tipo, clave, datos, max_intentos, disponible_en, creado_en)
                    VALUES (:org, :type, :key, CAST(:payload AS jsonb), :max, :now, :now)
                    ON CONFLICT (organizacion_id, tipo, clave) DO NOTHING
                    RETURNING id
                    """.trimIndent(),
                ).bind("org", organization)
                .bind("type", type)
                .bind("payload", payload)
                .bind("max", max)
                .bind("now", now)
        val inserted =
            (if (key == null) spec.bindNull("key", String::class.java) else spec.bind("key", key))
                .map { row, _ -> row.get("id", UUID::class.java)!! }
                .one()
                .awaitSingleOrNull()
        return inserted ?: existing(organization, type, key!!)
    }

    private suspend fun existing(
        organization: UUID,
        type: String,
        key: String,
    ): UUID =
        db
            .sql("SELECT id FROM lms.trabajo WHERE organizacion_id = :org AND tipo = :type AND clave = :key")
            .bind("org", organization)
            .bind("type", type)
            .bind("key", key)
            .map { row, _ -> row.get("id", UUID::class.java)!! }
            .one()
            .awaitSingle()

    // como toda lectura de lms, dentro de su organización
    suspend fun find(
        organization: UUID,
        id: UUID,
    ): JobStatus? =
        db
            .sql(
                """
                SELECT id, tipo, datos::text AS datos, estado, intentos, max_intentos, error, resultado::text AS resultado, creado_en, terminado_en
                FROM lms.trabajo WHERE organizacion_id = :org AND id = :id
                """.trimIndent(),
            ).bind("org", organization)
            .bind("id", id)
            .map { row, _ -> status(row) }
            .one()
            .awaitSingleOrNull()

    // reclama hasta limit trabajos de estos tipos: los encolados que ya tocan y los en curso cuyo reclamo caducó. cada
    // reclamo cuenta como un intento
    suspend fun claim(
        types: Collection<String>,
        limit: Int,
    ): List<Job> {
        if (types.isEmpty()) return emptyList()
        val now = now()
        return db
            .sql(
                """
                UPDATE lms.trabajo SET estado = 'RUNNING', intentos = intentos + 1, reclamado_en = :now
                WHERE id IN (
                    SELECT id FROM lms.trabajo
                    WHERE tipo = ANY(:types)
                      AND ((estado = 'QUEUED' AND disponible_en <= :now) OR (estado = 'RUNNING' AND reclamado_en < :stale))
                    ORDER BY disponible_en
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, organizacion_id, tipo, datos::text AS datos, intentos, max_intentos
                """.trimIndent(),
            ).bind("now", now)
            .bind("stale", now.minus(properties.lease))
            .bind("types", types.toTypedArray())
            .bind("limit", limit)
            .map { row, _ ->
                Job(
                    id = row.get("id", UUID::class.java)!!,
                    organizationId = row.get("organizacion_id", UUID::class.java)!!,
                    type = row.get("tipo", String::class.java)!!,
                    payload = row.get("datos", String::class.java)!!,
                    attempt = row.get("intentos", Number::class.java)!!.toInt(),
                    maxAttempts = row.get("max_intentos", Number::class.java)!!.toInt(),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    // false: el reclamo ya no era de quien termina
    suspend fun complete(
        job: Job,
        result: String?,
    ): Boolean {
        val spec =
            db
                .sql(
                    """
                    UPDATE lms.trabajo SET estado = 'DONE', resultado = CAST(:result AS jsonb), error = NULL, reclamado_en = NULL, terminado_en = :now
                    WHERE id = :id AND estado = 'RUNNING' AND intentos = :attempt
                    """.trimIndent(),
                ).bind("now", now())
                .bind("id", job.id)
                .bind("attempt", job.attempt)
        return (if (result == null) spec.bindNull("result", String::class.java) else spec.bind("result", result))
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0
    }

    // vuelve a la cola con espera creciente o, agotados los intentos, queda FAILED. false: el reclamo ya no era suyo
    suspend fun fail(
        job: Job,
        error: String,
    ): Boolean {
        val now = now()
        return db
            .sql(
                """
                UPDATE lms.trabajo SET
                    estado = CASE WHEN intentos >= max_intentos THEN 'FAILED' ELSE 'QUEUED' END,
                    error = :error,
                    disponible_en = :retryAt,
                    reclamado_en = NULL,
                    terminado_en = CASE WHEN intentos >= max_intentos THEN CAST(:now AS timestamptz) END
                WHERE id = :id AND estado = 'RUNNING' AND intentos = :attempt
                """.trimIndent(),
            ).bind("error", error.take(MAX_ERROR))
            .bind("retryAt", now.plus(properties.backoff.multipliedBy(job.attempt.toLong())))
            .bind("now", now)
            .bind("id", job.id)
            .bind("attempt", job.attempt)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0
    }

    private fun now(): OffsetDateTime = clock.instant().atOffset(ZoneOffset.UTC)

    private fun status(row: Readable) =
        JobStatus(
            id = row.get("id", UUID::class.java)!!,
            type = row.get("tipo", String::class.java)!!,
            payload = row.get("datos", String::class.java)!!,
            state = JobState.valueOf(row.get("estado", String::class.java)!!),
            attempts = row.get("intentos", Number::class.java)!!.toInt(),
            maxAttempts = row.get("max_intentos", Number::class.java)!!.toInt(),
            error = row.get("error", String::class.java),
            result = row.get("resultado", String::class.java),
            createdAt = row.get("creado_en", OffsetDateTime::class.java)!!.toInstant(),
            finishedAt = row.get("terminado_en", OffsetDateTime::class.java)?.toInstant(),
        )

    companion object {
        // un error cabe en una línea de informe; el detalle completo va al log
        const val MAX_ERROR = 2000
    }
}
