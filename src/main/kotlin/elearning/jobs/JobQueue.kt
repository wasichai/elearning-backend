package elearning.jobs

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import wasichai.core.common.RetryLaterException
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// lms.trabajo (V9 y V12, ADR-0018 §4 y ADR-0022). encolar es idempotente por clave mientras el trabajo no termina y tiene
// tope por persona; reclamar usa FOR UPDATE SKIP LOCKED, así que varias réplicas nunca toman el mismo trabajo. terminar
// exige seguir siendo el dueño del reclamo (estado e intento): una réplica que perdió su reclamo no pisa el resultado de la
// que lo retomó
@Repository
class JobQueue(
    private val db: DatabaseClient,
    private val properties: JobProperties,
    private val clock: Clock,
) {
    // el id del trabajo. con la misma clave en la misma organización y tipo, mientras el que ya estaba siga pendiente o en
    // curso, el de ese (ADR-0022): pedir dos veces lo mismo no hace dos trabajos. terminado, la clave queda libre.
    // requester: quien lo pidió; sin un trabajo que devolver, si ya tiene lms.jobs.max-active-per-requester activos, 429.
    // payload es JSON; maxAttempts null: lms.jobs.max-attempts. el valor por defecto no puede leer properties: la llamada
    // con defecto corre sobre el proxy de @Repository, que no tiene los campos
    suspend fun enqueue(
        organization: UUID,
        type: String,
        payload: String = "{}",
        key: String? = null,
        maxAttempts: Int? = null,
        requester: UUID? = null,
    ): UUID {
        require(type.isNotBlank()) { "job type must not be blank" }
        val max = maxAttempts ?: properties.maxAttempts
        require(max > 0) { "maxAttempts must be positive" }
        if (key != null) active(organization, type, key)?.let { return it }
        if (requester != null) requireRoomFor(organization, requester)
        // otra petición con la misma clave pudo encolar entre la lectura y la inserción, y su trabajo pudo terminar ya
        for (round in 1..2) {
            insert(organization, type, payload, key, max, requester)?.let { return it }
            active(organization, type, key!!)?.let { return it }
        }
        error("job $type with key $key could not be enqueued")
    }

    private suspend fun insert(
        organization: UUID,
        type: String,
        payload: String,
        key: String?,
        max: Int,
        requester: UUID?,
    ): UUID? {
        val now = now()
        val spec =
            db
                .sql(
                    """
                    INSERT INTO lms.trabajo (organizacion_id, tipo, clave, solicitante, datos, max_intentos, disponible_en, creado_en)
                    VALUES (:org, :type, :key, :requester, CAST(:payload AS jsonb), :max, :now, :now)
                    ON CONFLICT (organizacion_id, tipo, clave) WHERE estado IN ('QUEUED', 'RUNNING') DO NOTHING
                    RETURNING id
                    """.trimIndent(),
                ).bind("org", organization)
                .bind("type", type)
                .bind("payload", payload)
                .bind("max", max)
                .bind("now", now)
        val keyed = if (key == null) spec.bindNull("key", String::class.java) else spec.bind("key", key)
        val bound = if (requester == null) keyed.bindNull("requester", UUID::class.java) else keyed.bind("requester", requester)
        return bound
            .map { row, _ -> row.get("id", UUID::class.java)!! }
            .one()
            .awaitSingleOrNull()
    }

    // el trabajo pendiente o en curso con esta clave
    private suspend fun active(
        organization: UUID,
        type: String,
        key: String,
    ): UUID? =
        db
            .sql(
                """
                SELECT id FROM lms.trabajo
                WHERE organizacion_id = :org AND tipo = :type AND clave = :key AND estado IN ('QUEUED', 'RUNNING')
                """.trimIndent(),
            ).bind("org", organization)
            .bind("type", type)
            .bind("key", key)
            .map { row, _ -> row.get("id", UUID::class.java)!! }
            .one()
            .awaitSingleOrNull()

    // un tope blando: dos peticiones a la vez pueden pasarlo por uno, lo que no cambia la cuota de nadie
    private suspend fun requireRoomFor(
        organization: UUID,
        requester: UUID,
    ) {
        val limit = properties.maxActivePerRequester
        val active =
            db
                .sql(
                    """
                    SELECT count(*) AS activos FROM lms.trabajo
                    WHERE organizacion_id = :org AND solicitante = :requester AND estado IN ('QUEUED', 'RUNNING')
                    """.trimIndent(),
                ).bind("org", organization)
                .bind("requester", requester)
                .map { row, _ -> row.get("activos", Number::class.java)!!.toInt() }
                .one()
                .awaitSingle()
        if (active >= limit) {
            throw RetryLaterException(
                "There are already $active jobs of yours waiting or running: try again when one ends",
                RETRY_AFTER,
                status = HttpStatus.TOO_MANY_REQUESTS,
            )
        }
    }

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

    // false: el reclamo ya no era de quien termina. cerrar otra vez el mismo intento es inocuo: un manejador puede cerrar su
    // trabajo dentro de su propia transacción (ADR-0022) y el runner lo vuelve a cerrar con el mismo resultado
    suspend fun complete(
        job: Job,
        result: String?,
    ): Boolean {
        val spec =
            db
                .sql(
                    """
                    UPDATE lms.trabajo SET estado = 'DONE', resultado = CAST(:result AS jsonb), error = NULL, reclamado_en = NULL, terminado_en = :now
                    WHERE id = :id AND estado IN ('RUNNING', 'DONE') AND intentos = :attempt
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

        // lo que se pide esperar al pasar el tope: un trabajo corto tarda eso en terminar
        private val RETRY_AFTER: Duration = Duration.ofMinutes(1)
    }
}
