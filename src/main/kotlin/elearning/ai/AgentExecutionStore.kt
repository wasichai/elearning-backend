package elearning.ai

import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// lms.ejecucion_agente (ADR-0033 §7), siempre acotada a la organización. Solo inserción, el cierre de la ejecución y las
// intervenciones añadidas: el disparador de V17 rechaza lo demás. El JSON llega ya serializado (y sin claves) de
// AgentExecutionLog
@Repository
class AgentExecutionStore(
    private val db: DatabaseClient,
) {
    suspend fun insert(
        run: NewAgentExecution,
        inputs: String,
        sources: String,
        settings: String?,
        objective: String,
        startedAt: Instant,
    ): UUID {
        var spec =
            db
                .sql(
                    """
                    INSERT INTO lms.ejecucion_agente (organizacion_id, trabajo_id, paso, solicitante_id, curso_id, unidad_id, version_curso_id,
                        objetivo, entradas, fuentes, generador, proveedor, modelo, parametros_modelo, version_instrucciones, version_plantilla,
                        clave, intento, iniciada_en, estado)
                    VALUES (:org, :job, :step, :requester, :course, :unit, :version, :objective, CAST(:inputs AS jsonb), CAST(:sources AS jsonb),
                        :generator, :provider, :model, CAST(:settings AS jsonb), :prompt, :template, :key, :attempt, :startedAt, 'RUNNING')
                    RETURNING id
                    """.trimIndent(),
                ).bind("org", run.organizationId)
                .bind("job", run.jobId)
                .bind("step", run.step.name)
                .bind("requester", run.requestedBy)
                .bind("course", run.courseId)
                .bind("objective", objective)
                .bind("inputs", inputs)
                .bind("sources", sources)
                .bind("generator", run.generator.name)
                .bind("prompt", run.promptVersion)
                .bind("template", run.templateVersion)
                .bind("key", run.idempotencyKey)
                .bind("attempt", run.attempt)
                .bind("startedAt", startedAt.atOffset(ZoneOffset.UTC))
        spec = spec.bindOrNull("unit", run.unitId, UUID::class.java)
        spec = spec.bindOrNull("version", run.versionId, UUID::class.java)
        spec = spec.bindOrNull("provider", run.model?.provider, String::class.java)
        spec = spec.bindOrNull("model", run.model?.model, String::class.java)
        spec = spec.bindOrNull("settings", settings, String::class.java)
        return spec.map { row, _ -> row.get("id", UUID::class.java)!! }.one().awaitSingle()
    }

    // cierra una ejecución aún abierta; false si no existe en la organización o ya estaba cerrada
    suspend fun close(
        organization: UUID,
        id: UUID,
        outcome: AgentExecutionOutcome,
        output: String?,
        validations: String?,
        error: String?,
        cost: AgentExecutionCost?,
        finishedAt: Instant,
    ): Boolean {
        var spec =
            db
                .sql(
                    """
                    UPDATE lms.ejecucion_agente SET estado = :status, salida = CAST(:output AS jsonb), validaciones = CAST(:validations AS jsonb),
                        error = :error, tokens_entrada = :inputTokens, tokens_salida = :outputTokens, coste = :cost, moneda = :currency,
                        tarifa_entrada = :inputRate, tarifa_salida = :outputRate, tarifa_desde = :rateSince, terminada_en = :finishedAt
                    WHERE organizacion_id = :org AND id = :id AND estado IN ('PENDING', 'RUNNING')
                    """.trimIndent(),
                ).bind("org", organization)
                .bind("id", id)
                .bind("status", outcome.status.name)
                .bind("finishedAt", finishedAt.atOffset(ZoneOffset.UTC))
        spec = spec.bindOrNull("output", output, String::class.java)
        spec = spec.bindOrNull("validations", validations, String::class.java)
        spec = spec.bindOrNull("error", error, String::class.java)
        spec = spec.bindOrNull("inputTokens", outcome.usage?.inputTokens, Integer::class.java)
        spec = spec.bindOrNull("outputTokens", outcome.usage?.outputTokens, Integer::class.java)
        spec = spec.bindOrNull("cost", cost?.amount, BigDecimal::class.java)
        spec = spec.bindOrNull("currency", cost?.currency, String::class.java)
        spec = spec.bindOrNull("inputRate", cost?.inputPerMillion, BigDecimal::class.java)
        spec = spec.bindOrNull("outputRate", cost?.outputPerMillion, BigDecimal::class.java)
        spec = spec.bindOrNull("rateSince", cost?.rateSince, LocalDate::class.java)
        return spec.fetch().rowsUpdated().awaitSingle() > 0
    }

    // añade una intervención al final; false si la ejecución no existe en la organización
    suspend fun addIntervention(
        organization: UUID,
        id: UUID,
        intervention: String,
    ): Boolean =
        db
            .sql(
                "UPDATE lms.ejecucion_agente SET intervenciones = intervenciones || jsonb_build_array(CAST(:item AS jsonb)) " +
                    "WHERE organizacion_id = :org AND id = :id",
            ).bind("org", organization)
            .bind("id", id)
            .bind("item", intervention)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    suspend fun find(
        organization: UUID,
        id: UUID,
    ): AgentExecution? =
        db
            .sql("SELECT $COLUMNS FROM lms.ejecucion_agente WHERE organizacion_id = :org AND id = :id")
            .bind("org", organization)
            .bind("id", id)
            .map { row, _ -> execution(row) }
            .one()
            .awaitSingleOrNull()

    // las de un curso, la más reciente primero, a lo sumo limit
    suspend fun ofCourse(
        organization: UUID,
        course: UUID,
        limit: Int,
    ): List<AgentExecutionLine> =
        db
            .sql(
                "SELECT $LINE_COLUMNS FROM lms.ejecucion_agente WHERE organizacion_id = :org AND curso_id = :course " +
                    "ORDER BY iniciada_en DESC, id LIMIT :limit",
            ).bind("org", organization)
            .bind("course", course)
            .bind("limit", limit)
            .map { row, _ -> line(row) }
            .all()
            .collectList()
            .awaitSingle()

    // la ejecución que ya resolvió esta clave (ADR-0033 §5): la más antigua, que es la que se entregó primero
    suspend fun succeededWithKey(
        organization: UUID,
        key: String,
    ): AgentExecution? =
        db
            .sql(
                "SELECT $COLUMNS FROM lms.ejecucion_agente WHERE organizacion_id = :org AND clave = :key AND estado = 'SUCCEEDED' " +
                    "ORDER BY iniciada_en, id LIMIT 1",
            ).bind("org", organization)
            .bind("key", key)
            .map { row, _ -> execution(row) }
            .one()
            .awaitSingleOrNull()

    // las ejecuciones de este trabajo que siguen abiertas: las de un intento cuya réplica se perdió
    suspend fun openOfJob(
        organization: UUID,
        job: UUID,
    ): List<UUID> =
        db
            .sql("SELECT id FROM lms.ejecucion_agente WHERE organizacion_id = :org AND trabajo_id = :job AND estado IN ('PENDING', 'RUNNING')")
            .bind("org", organization)
            .bind("job", job)
            .map { row, _ -> row.get("id", UUID::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

    // lo gastado en el curso en esta moneda: la suma del coste de sus ejecuciones (ADR-0033 §9)
    suspend fun spent(
        organization: UUID,
        course: UUID,
        currency: String,
    ): BigDecimal =
        db
            .sql(
                "SELECT COALESCE(sum(coste), 0) AS gastado FROM lms.ejecucion_agente " +
                    "WHERE organizacion_id = :org AND curso_id = :course AND moneda = :currency",
            ).bind("org", organization)
            .bind("course", course)
            .bind("currency", currency)
            .map { row, _ -> row.get("gastado", BigDecimal::class.java)!! }
            .one()
            .awaitSingle()

    // lo gastado en la organización en esta moneda desde since, entre todos sus cursos (#368): una consulta, con el índice de V31
    suspend fun spentSince(
        organization: UUID,
        since: Instant,
        currency: String,
    ): BigDecimal =
        db
            .sql(
                "SELECT COALESCE(sum(coste), 0) AS gastado FROM lms.ejecucion_agente " +
                    "WHERE organizacion_id = :org AND iniciada_en >= :since AND coste IS NOT NULL AND moneda = :currency",
            ).bind("org", organization)
            .bind("since", since.atOffset(ZoneOffset.UTC))
            .bind("currency", currency)
            .map { row, _ -> row.get("gastado", BigDecimal::class.java)!! }
            .one()
            .awaitSingle()

    private fun <T : Any> DatabaseClient.GenericExecuteSpec.bindOrNull(
        name: String,
        value: Any?,
        type: Class<T>,
    ): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)

    private fun cost(row: Row): AgentExecutionCost? =
        row.get("coste", BigDecimal::class.java)?.let {
            AgentExecutionCost(
                amount = it,
                currency = row.get("moneda", String::class.java)!!,
                inputPerMillion = row.get("tarifa_entrada", BigDecimal::class.java)!!,
                outputPerMillion = row.get("tarifa_salida", BigDecimal::class.java)!!,
                rateSince = row.get("tarifa_desde", LocalDate::class.java)!!,
            )
        }

    private fun line(row: Row): AgentExecutionLine =
        AgentExecutionLine(
            id = row.get("id", UUID::class.java)!!,
            jobId = row.get("trabajo_id", UUID::class.java)!!,
            step = AgentStep.valueOf(row.get("paso", String::class.java)!!),
            status = AgentExecutionStatus.valueOf(row.get("estado", String::class.java)!!),
            generator = Generator.valueOf(row.get("generador", String::class.java)!!),
            provider = row.get("proveedor", String::class.java),
            model = row.get("modelo", String::class.java),
            requestedBy = row.get("solicitante_id", UUID::class.java)!!,
            courseId = row.get("curso_id", UUID::class.java)!!,
            unitId = row.get("unidad_id", UUID::class.java),
            versionId = row.get("version_curso_id", UUID::class.java),
            attempt = row.get("intento", Integer::class.java)!!.toInt(),
            inputTokens = row.get("tokens_entrada", Integer::class.java)?.toInt(),
            outputTokens = row.get("tokens_salida", Integer::class.java)?.toInt(),
            cost = cost(row),
            startedAt = row.get("iniciada_en", OffsetDateTime::class.java)!!.toInstant(),
            finishedAt = row.get("terminada_en", OffsetDateTime::class.java)?.toInstant(),
        )

    private fun execution(row: Row): AgentExecution {
        val line = line(row)
        return AgentExecution(
            id = line.id,
            organizationId = row.get("organizacion_id", UUID::class.java)!!,
            jobId = line.jobId,
            step = line.step,
            requestedBy = line.requestedBy,
            courseId = line.courseId,
            unitId = line.unitId,
            versionId = line.versionId,
            objective = row.get("objetivo", String::class.java)!!,
            inputs = MAPPER.readTree(row.get("entradas_json", String::class.java)!!),
            sources = MAPPER.readValue(row.get("fuentes_json", String::class.java)!!, object : TypeReference<List<SourceDigest>>() {}),
            generator = line.generator,
            provider = line.provider,
            model = line.model,
            modelSettings = row.get("parametros_json", String::class.java)?.let { MAPPER.readValue(it, ModelSettings::class.java) },
            promptVersion = row.get("version_instrucciones", String::class.java)!!,
            templateVersion = row.get("version_plantilla", String::class.java)!!,
            idempotencyKey = row.get("clave", String::class.java)!!,
            attempt = line.attempt,
            status = line.status,
            output = row.get("salida_json", String::class.java)?.let { MAPPER.readTree(it) },
            validations = row.get("validaciones_json", String::class.java)?.let { MAPPER.readTree(it) },
            error = row.get("error", String::class.java),
            inputTokens = line.inputTokens,
            outputTokens = line.outputTokens,
            cost = line.cost,
            startedAt = line.startedAt,
            finishedAt = line.finishedAt,
            interventions =
                MAPPER.readValue(row.get("intervenciones_json", String::class.java)!!, object : TypeReference<List<Intervention>>() {}),
        )
    }

    companion object {
        internal val MAPPER: JsonMapper = JsonMapper.builder().findAndAddModules().build()

        private const val LINE_COLUMNS =
            "id, trabajo_id, paso, estado, generador, proveedor, modelo, solicitante_id, curso_id, unidad_id, version_curso_id, intento, " +
                "tokens_entrada, tokens_salida, coste, moneda, tarifa_entrada, tarifa_salida, tarifa_desde, iniciada_en, terminada_en"
        private const val COLUMNS =
            "$LINE_COLUMNS, organizacion_id, objetivo, CAST(entradas AS text) AS entradas_json, CAST(fuentes AS text) AS fuentes_json, " +
                "CAST(parametros_modelo AS text) AS parametros_json, version_instrucciones, version_plantilla, clave, " +
                "CAST(salida AS text) AS salida_json, CAST(validaciones AS text) AS validaciones_json, error, " +
                "CAST(intervenciones AS text) AS intervenciones_json"
    }
}
