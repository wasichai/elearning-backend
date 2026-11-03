package elearning.ai

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// lo que los indicadores de proceso (RF-IA-10, docs/ai §10) leen de las tablas fijas del LMS: lms.ejecucion_agente (con sus
// intervenciones), lms.transicion_version (el historial de estados de ADR-0032) y lms.decision_revision. Una consulta por
// indicador, siempre en la organización y, si se pide, en un curso. Solo cuentan las ejecuciones generator = MODEL: una versión
// GENERADA (docs/ai §13) lo es aquí solo si la nombra una ejecución MODEL de un paso que crea borradores. Los informes de
// validación son ejecuciones DETERMINISTIC por diseño (DraftValidator): son la medida de esas versiones, no una generación
@Repository
class PipelineIndicatorStore(
    private val db: DatabaseClient,
) {
    // indicador 1: versiones generadas con alguna decisión de revisión, y cuántas de ellas tienen APROBAR como última decisión
    // sin cambio estructural
    suspend fun acceptance(
        organization: UUID,
        course: UUID?,
    ): Ratio =
        query(
            course,
            """
            ${generated(course)},
            revisada AS (
                SELECT g.version,
                    (SELECT d.decision FROM lms.decision_revision d WHERE d.organizacion_id = :org AND d.version_curso_id = g.version
                     ORDER BY d.decidida_en DESC, d.id DESC LIMIT 1) AS ultima
                FROM generada g
                WHERE EXISTS (SELECT 1 FROM lms.decision_revision d WHERE d.organizacion_id = :org AND d.version_curso_id = g.version)
            ),
            -- bajas y reordenaciones: lo que una persona dejó en la ejecución que generó la versión (#288, docs/ai §14)
            estructural AS (
                SELECT gen.version FROM generacion gen
                CROSS JOIN LATERAL jsonb_array_elements(gen.intervenciones) AS i
                WHERE i ->> 'kind' = 'EDITED' AND i ->> 'note' ~ :structural
                UNION
                -- altas (y bajas): otro número de unidades, lecciones o evaluaciones entre el primer informe de la versión y el último
                SELECT g.version FROM generada g
                JOIN LATERAL (SELECT x.validaciones FROM lms.ejecucion_agente x
                              WHERE ${validationOf("g.version")} ORDER BY x.terminada_en, x.iniciada_en, x.id LIMIT 1) AS primero ON true
                JOIN LATERAL (SELECT x.validaciones FROM lms.ejecucion_agente x
                              WHERE ${validationOf("g.version")} ORDER BY x.terminada_en DESC, x.iniciada_en DESC, x.id DESC LIMIT 1) AS ultimo ON true
                WHERE (primero.validaciones -> 'units', primero.validaciones -> 'lessons', primero.validaciones -> 'evaluations')
                    IS DISTINCT FROM (ultimo.validaciones -> 'units', ultimo.validaciones -> 'lessons', ultimo.validaciones -> 'evaluations')
            )
            SELECT count(*) AS denominador,
                count(*) FILTER (WHERE r.ultima = 'APROBAR' AND NOT EXISTS (SELECT 1 FROM estructural s WHERE s.version = r.version)) AS numerador
            FROM revisada r
            """,
            mapOf("org" to organization, "structural" to STRUCTURAL_CHANGE.pattern),
        ) { row -> Ratio(row.long("numerador"), row.long("denominador")) }

    // indicador 2: por versión generada, el brief de su ejecución generadora más antigua (entradas.briefId) y el fin del primer
    // informe de validación sin problemas bloqueantes. La creación del brief es de Wasichai: la lee PipelineIndicators
    suspend fun validatedDrafts(
        organization: UUID,
        course: UUID?,
    ): List<ValidatedDraft> =
        rows(
            course,
            """
            ${generated(course)},
            primera AS (
                SELECT DISTINCT ON (version) version, brief FROM generacion ORDER BY version, iniciada_en, id
            )
            SELECT p.version, p.brief, v.validada FROM primera p
            JOIN LATERAL (SELECT min(x.terminada_en) AS validada FROM lms.ejecucion_agente x
                          WHERE ${validationOf("p.version")} AND x.validaciones ->> 'blocking' = 'false') AS v ON v.validada IS NOT NULL
            """,
            mapOf("org" to organization),
        ) { row ->
            ValidatedDraft(row.uuid("version"), row.get("brief", String::class.java), row.instant("validada"))
        }

    // indicador 3: en los análisis de fuentes con informe, lo que quedó (citas comprobadas en la sección y el commit indicados) y
    // lo descartado por su procedencia. BLANK no cuenta: un elemento vacío no es una referencia
    suspend fun references(
        organization: UUID,
        course: UUID?,
    ): Ratio =
        query(
            course,
            """
            SELECT COALESCE(sum(COALESCE((validaciones ->> 'concepts')::bigint, 0) + COALESCE((validaciones ->> 'procedures')::bigint, 0)), 0)::bigint
                       AS verificadas,
                   COALESCE(sum((SELECT count(*) FROM jsonb_array_elements(COALESCE(validaciones -> 'discarded', '[]'::jsonb)) AS d
                                 WHERE d ->> 'reason' IS DISTINCT FROM 'BLANK')), 0)::bigint AS descartadas
            FROM lms.ejecucion_agente
            WHERE organizacion_id = :org AND generador = 'MODEL' AND paso = '${AgentStep.SOURCE_ANALYSIS.name}'
                AND estado IN ('SUCCEEDED', 'INVALID_OUTPUT') AND jsonb_typeof(validaciones) = 'object' ${byCourse(course, "")}
            """,
            mapOf("org" to organization),
        ) { row ->
            val verified = row.long("verificadas")
            Ratio(verified, verified + row.long("descartadas"))
        }

    // indicador 4: por versión generada que entró en revisión, el último informe terminado antes de su primera entrada
    suspend fun issuesBeforeReview(
        organization: UUID,
        course: UUID?,
    ): IssuesBeforeReview =
        query(
            course,
            """
            ${generated(course)},
            entrada AS (
                SELECT g.version, inf.validaciones FROM generada g
                JOIN LATERAL (SELECT t.ocurrida_en FROM lms.transicion_version t
                              WHERE t.organizacion_id = :org AND t.version_curso_id = g.version AND t.hacia = 'EN_REVISION'
                              ORDER BY t.id LIMIT 1) AS e ON true
                LEFT JOIN LATERAL (SELECT x.validaciones FROM lms.ejecucion_agente x
                                   WHERE ${validationOf("g.version")} AND x.terminada_en <= e.ocurrida_en
                                   ORDER BY x.terminada_en DESC, x.iniciada_en DESC, x.id DESC LIMIT 1) AS inf ON true
            )
            SELECT count(validaciones) AS borradores, (count(*) - count(validaciones)) AS sin_informe,
                COALESCE(sum((validaciones ->> 'problemCount')::bigint), 0)::bigint AS problemas,
                COALESCE(sum((validaciones -> 'problemsBySeverity' ->> 'BLOCKING')::bigint), 0)::bigint AS bloqueantes,
                COALESCE(sum((validaciones -> 'problemsBySeverity' ->> 'WARNING')::bigint), 0)::bigint AS avisos
            FROM entrada
            """,
            mapOf("org" to organization),
        ) { row ->
            IssuesBeforeReview(row.long("borradores"), row.long("sin_informe"), row.long("problemas"), row.long("bloqueantes"), row.long("avisos"))
        }

    // indicador 5: la mediana, en segundos, entre la última entrada en revisión anterior a cada decisión y la decisión, en las
    // versiones generadas. Una decisión sin entrada registrada (historial anterior a V16) queda fuera y se cuenta
    suspend fun reviewTime(
        organization: UUID,
        course: UUID?,
    ): Median =
        query(
            course,
            """
            ${generated(course)},
            decision AS (
                SELECT d.decidida_en,
                    (SELECT max(t.ocurrida_en) FROM lms.transicion_version t
                     WHERE t.organizacion_id = :org AND t.version_curso_id = d.version_curso_id AND t.hacia = 'EN_REVISION'
                         AND t.ocurrida_en <= d.decidida_en) AS entrada
                FROM lms.decision_revision d
                WHERE d.organizacion_id = :org AND d.version_curso_id IN (SELECT version FROM generada)
            )
            SELECT count(entrada) AS muestra, (count(*) - count(entrada)) AS fuera,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM decidida_en - entrada)::double precision) AS mediana
            FROM decision
            """,
            mapOf("org" to organization),
        ) { row -> Median(row.long("muestra"), row.long("fuera"), row.get("mediana", Double::class.javaObjectType)) }

    // indicador 6: el coste de las ejecuciones MODEL, todos sus intentos y estados, por curso y por unidad y moneda, con las
    // tarifas con que se calculó. Una ejecución sin tarifa (sin coste) va en su línea con moneda nula: no se inventa un importe
    suspend fun costs(
        organization: UUID,
        course: UUID?,
    ): List<CostRow> =
        rows(
            course,
            """
            SELECT curso_id, unidad_id, GROUPING(unidad_id) AS por_curso, moneda, sum(coste) AS importe, count(*) AS ejecuciones,
                (jsonb_agg(DISTINCT jsonb_build_object('provider', proveedor, 'model', modelo, 'inputPerMillion', tarifa_entrada,
                    'outputPerMillion', tarifa_salida, 'since', tarifa_desde)) FILTER (WHERE coste IS NOT NULL))::text AS tarifas
            FROM lms.ejecucion_agente
            WHERE organizacion_id = :org AND generador = 'MODEL' ${byCourse(course, "")}
            GROUP BY GROUPING SETS ((curso_id, moneda), (curso_id, unidad_id, moneda))
            HAVING GROUPING(unidad_id) = 1 OR unidad_id IS NOT NULL
            ORDER BY curso_id, unidad_id NULLS FIRST, moneda NULLS LAST
            """,
            mapOf("org" to organization),
        ) { row ->
            CostRow(
                courseId = row.uuid("curso_id"),
                unitId = if (row.get("por_curso", Int::class.javaObjectType)!! == 1) null else row.get("unidad_id", UUID::class.java),
                currency = row.get("moneda", String::class.java),
                amount = row.get("importe", BigDecimal::class.java),
                runs = row.long("ejecuciones"),
                rates =
                    row
                        .get("tarifas", String::class.java)
                        ?.let { AgentExecutionStore.MAPPER.readValue(it, RATES) }
                        .orEmpty()
                        .sortedBy { it.since },
            )
        }

    private suspend fun <T : Any> query(
        course: UUID?,
        sql: String,
        binds: Map<String, Any>,
        map: (Readable) -> T,
    ): T = spec(course, sql, binds).map { row, _ -> map(row) }.one().awaitSingle()

    private suspend fun <T : Any> rows(
        course: UUID?,
        sql: String,
        binds: Map<String, Any>,
        map: (Readable) -> T,
    ): List<T> =
        spec(course, sql, binds)
            .map { row, _ -> map(row) }
            .all()
            .collectList()
            .awaitSingle()

    private fun spec(
        course: UUID?,
        sql: String,
        binds: Map<String, Any>,
    ): DatabaseClient.GenericExecuteSpec {
        var spec = db.sql(sql.trimIndent())
        binds.forEach { (name, value) -> spec = spec.bind(name, value) }
        if (course != null) spec = spec.bind("course", course)
        return spec
    }

    private fun Readable.long(name: String): Long = (get(name) as Number).toLong()

    private fun Readable.uuid(name: String): UUID = get(name, UUID::class.java)!!

    private fun Readable.instant(name: String): Instant = get(name, OffsetDateTime::class.java)!!.toInstant()

    companion object {
        // un cambio estructural (docs/ai §10) en la nota de una intervención EDITED de #288 («<objeto> <id> deleted» o
        // «<objeto> <id>: campo, campo»): la baja de una unidad, lección o evaluación, o el cambio de su orden o de su padre.
        // Las altas no dejan intervención: las cuentan los informes de validación. Vale igual en Kotlin y en PostgreSQL
        val STRUCTURAL_CHANGE = Regex("""^(unidad|leccion|evaluacion) [0-9a-fA-F-]+( deleted$|: (.*, )?(orden|version_curso|unidad|leccion)(,|$))""")

        private val UUID_TEXT = "'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'"

        // nombres del enum, no datos de nadie: van en el SQL tal cual
        private val STEPS = DraftValidationStore.GENERATING_STEPS.joinToString { "'${it.name}'" }

        private val RATES = object : TypeReference<List<RateUsed>>() {}

        private fun byCourse(
            course: UUID?,
            alias: String,
        ) = if (course == null) "" else "AND ${alias}curso_id = :course"

        // las ejecuciones MODEL que crean borradores, con la versión que nombran (version_curso_id o salida.versionId, docs/ai §13),
        // y las versiones generadas que salen de ellas
        private fun generated(course: UUID?) =
            """
            WITH generacion AS (
                SELECT e.id, v.version, e.iniciada_en, e.intervenciones, e.entradas ->> 'briefId' AS brief
                FROM lms.ejecucion_agente e
                CROSS JOIN LATERAL (VALUES (e.version_curso_id),
                    (CASE WHEN e.salida ->> 'versionId' ~ $UUID_TEXT THEN (e.salida ->> 'versionId')::uuid END)) AS v(version)
                WHERE e.organizacion_id = :org AND e.generador = 'MODEL' AND e.paso IN ($STEPS) AND v.version IS NOT NULL ${byCourse(course, "e.")}
            ),
            generada AS (SELECT DISTINCT version FROM generacion)
            """.trimIndent()

        // los informes de validación terminados de una versión (paso VALIDATION, DraftValidator)
        private fun validationOf(version: String) =
            "x.organizacion_id = :org AND x.version_curso_id = $version AND x.paso = '${AgentStep.VALIDATION.name}' AND x.estado = 'SUCCEEDED'"
    }
}

data class Ratio(
    val numerator: Long,
    val denominator: Long,
)

data class ValidatedDraft(
    val versionId: UUID,
    val briefId: String?,
    val validatedAt: Instant,
)

data class IssuesBeforeReview(
    val drafts: Long,
    val withoutReport: Long,
    val problems: Long,
    val blocking: Long,
    val warnings: Long,
)

data class Median(
    val sample: Long,
    val excluded: Long,
    val seconds: Double?,
)

data class RateUsed(
    val provider: String,
    val model: String,
    val inputPerMillion: BigDecimal,
    val outputPerMillion: BigDecimal,
    val since: LocalDate,
)

data class CostRow(
    val courseId: UUID,
    val unitId: UUID?,
    val currency: String?,
    val amount: BigDecimal?,
    val runs: Long,
    val rates: List<RateUsed>,
)
