package elearning.ai

import elearning.LmsApiTest
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

// RF-IA-10 (E35.2): los indicadores de proceso de docs/ai §10, calculados con datos sembrados en un curso propio: ejecuciones
// AgentRun (con sus intervenciones), el historial de estados de las versiones y las decisiones de revisión. Solo cuentan las
// ejecuciones MODEL; los lee quien tiene VER_INDICADORES_IA, nunca por rol; y ninguno se presenta como calidad pedagógica
class PipelineIndicatorsApiTest : LmsApiTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    private val admin by lazy { bearer() }
    private val requester by lazy { UUID.fromString(get("/api/auth/me", admin)["userId"].asString()) }

    private fun send(
        path: String,
        attributes: Map<String, Any?>,
        token: String = admin,
    ): JsonNode {
        val result =
            client
                .post()
                .uri(path)
                .header("Authorization", token)
                .bodyValue(mapOf("attributes" to attributes))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(HttpStatus.CREATED.value(), result.status.value(), result.responseBody)
        return json.readTree(result.responseBody)
    }

    private fun read(
        path: String,
        token: String,
    ): Pair<HttpStatus, JsonNode?> {
        val r =
            client
                .get()
                .uri(path)
                .header("Authorization", token)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(r.status.value()) to r.responseBody?.let { runCatching { json.readTree(it) }.getOrNull() }
    }

    private fun course(): UUID {
        val slug = uniqueName("ind-").lowercase()
        return UUID.fromString(send("/api/objects/curso/records", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso"))["id"].asString())
    }

    private fun insert(
        table: String,
        values: Map<String, Any?>,
    ) {
        val present = values.filterValues { it != null }
        val placeholders = present.map { (k, v) -> if (v is Jsonb) "CAST(:$k AS jsonb)" else ":$k" }
        var spec = db.sql("INSERT INTO $table (${present.keys.joinToString()}) VALUES (${placeholders.joinToString()})")
        present.forEach { (k, v) ->
            spec =
                spec.bind(
                    k,
                    when (v) {
                        is Jsonb -> v.text
                        is Instant -> v.atOffset(ZoneOffset.UTC)
                        else -> v!!
                    },
                )
        }
        runBlocking { spec.fetch().rowsUpdated().awaitSingle() }
    }

    private data class Jsonb(
        val text: String,
    )

    private fun jsonb(value: Any) = Jsonb(json.writeValueAsString(value))

    private fun run(
        course: UUID,
        step: AgentStep,
        at: Instant,
        generator: Generator = Generator.MODEL,
        status: AgentExecutionStatus = AgentExecutionStatus.SUCCEEDED,
        version: UUID? = null,
        unit: UUID? = null,
        inputs: Map<String, Any?> = emptyMap(),
        output: Any? = null,
        validations: Any? = null,
        interventions: List<Map<String, Any?>> = emptyList(),
        cost: Cost? = null,
        attempt: Int = 1,
    ) {
        val model = generator == Generator.MODEL
        insert(
            "lms.ejecucion_agente",
            mapOf(
                "organizacion_id" to DEMO_ORGANIZATION,
                "trabajo_id" to UUID.randomUUID(),
                "paso" to step.name,
                "solicitante_id" to requester,
                "curso_id" to course,
                "unidad_id" to unit,
                "version_curso_id" to version,
                "objetivo" to "Sembrado para los indicadores",
                "entradas" to jsonb(inputs),
                "generador" to generator.name,
                "proveedor" to (if (model) (cost?.provider ?: "anthropic") else null),
                "modelo" to (if (model) (cost?.model ?: "claude-sonnet-5-5") else null),
                "parametros_modelo" to (if (model) jsonb(mapOf("maxTokens" to 1000)) else null),
                "version_instrucciones" to "seed@1",
                "version_plantilla" to "seed@1",
                "clave" to "seed:${UUID.randomUUID()}",
                "intento" to attempt,
                "iniciada_en" to at,
                "estado" to status.name,
                "salida" to output?.let(::jsonb),
                "validaciones" to validations?.let(::jsonb),
                "terminada_en" to at,
                "coste" to cost?.amount,
                "moneda" to cost?.let { "USD" },
                "tarifa_entrada" to cost?.input,
                "tarifa_salida" to cost?.output,
                "tarifa_desde" to cost?.since,
                "intervenciones" to jsonb(interventions),
            ),
        )
    }

    private data class Cost(
        val amount: BigDecimal,
        val provider: String,
        val model: String,
        val input: BigDecimal,
        val output: BigDecimal,
        val since: LocalDate,
    )

    private fun report(
        at: Instant,
        version: UUID,
        course: UUID,
        units: Int = 2,
        blocking: Int = 0,
        warnings: Int = 0,
    ) = run(
        course,
        AgentStep.VALIDATION,
        at,
        generator = Generator.DETERMINISTIC,
        version = version,
        validations =
            mapOf(
                "versionId" to version.toString(),
                "units" to units,
                "lessons" to 3,
                "evaluations" to 1,
                "blocking" to (blocking > 0),
                "problemCount" to blocking + warnings,
                "problemsBySeverity" to mapOf("BLOCKING" to blocking, "WARNING" to warnings),
            ),
    )

    private fun transition(
        version: UUID,
        to: String,
        at: Instant,
    ) = insert(
        "lms.transicion_version",
        mapOf("organizacion_id" to DEMO_ORGANIZATION, "version_curso_id" to version, "desde" to "BORRADOR", "hacia" to to, "ocurrida_en" to at),
    )

    private fun decision(
        version: UUID,
        decision: String,
        at: Instant,
    ) = insert(
        "lms.decision_revision",
        mapOf(
            "organizacion_id" to DEMO_ORGANIZATION,
            "version_curso_id" to version,
            "decision" to decision,
            "motivo" to (if (decision == "APROBAR") null else "Motivo"),
            "autor_id" to requester,
            "decidida_en" to at,
        ),
    )

    private fun edited(note: String) = mapOf("userId" to requester.toString(), "kind" to "EDITED", "at" to "2026-11-03T10:00:00Z", "note" to note)

    @Test
    fun `each process indicator is computed from the seeded runs, versions and decisions, counting only MODEL executions`() {
        val course = course()
        val brief = send("/api/objects/brief/records", mapOf("curso" to course.toString(), "tematica" to "Indicadores"))
        val b = Instant.parse(get("/api/objects/brief/records/${brief["id"].asString()}", admin)["createdAt"].asString())
        val briefId = mapOf("briefId" to brief["id"].asString())
        val h = { hours: Long -> b.plus(Duration.ofHours(hours)) }
        val (v1, v2, v3, v4, v5) = List(5) { UUID.randomUUID() }
        val (v6, v7) = List(2) { UUID.randomUUID() }
        val unit = UUID.randomUUID()
        val claude = Cost(BigDecimal("0.5"), "anthropic", "claude-sonnet-5-5", BigDecimal("3"), BigDecimal("15"), LocalDate.parse("2026-01-01"))

        // v1: generada desde el brief, validada sin bloqueantes a las 4 h, en revisión a las 5 h y aprobada a las 7 h; solo
        // ACCEPTED. Su ejecución tiene un reintento fallido que también costó
        run(course, AgentStep.LESSON_DRAFT, h(0), version = v1, unit = unit, inputs = briefId, cost = claude, interventions = listOf(accepted()))
        run(
            course,
            AgentStep.LESSON_DRAFT,
            h(0),
            status = AgentExecutionStatus.FAILED,
            version = v1,
            unit = unit,
            inputs = briefId,
            cost = claude.copy(amount = BigDecimal("0.25")),
            attempt = 2,
        )
        report(h(2), v1, course, blocking = 1, warnings = 2)
        report(h(4), v1, course, warnings = 1)
        transition(v1, "EN_REVISION", h(5))
        decision(v1, "APROBAR", h(7))

        // v2: la creó la ejecución (salida.versionId); validada a las 6 h con 2 unidades y a las 8 h con 3: un alta, cambio
        // estructural. En revisión a las 9 h, aprobada a las 13 h
        run(course, AgentStep.LESSON_DRAFT, h(0), output = mapOf("versionId" to v2.toString()), inputs = briefId)
        report(h(6), v2, course)
        report(h(8), v2, course, units = 3, warnings = 2)
        transition(v2, "EN_REVISION", h(9))
        decision(v2, "APROBAR", h(13))

        // v3: sin brief en sus entradas; una persona reordenó una lección (estructural). Pide cambios y luego la rechaza
        run(course, AgentStep.LESSON_DRAFT, h(0), version = v3, interventions = listOf(edited("leccion ${UUID.randomUUID()}: orden, titulo")))
        report(h(1), v3, course)
        transition(v3, "EN_REVISION", h(2))
        decision(v3, "PEDIR_CAMBIOS", h(3))
        transition(v3, "EN_REVISION", h(4))
        decision(v3, "RECHAZAR", h(10))

        // v4: el doble determinista; con todo lo demás, no cuenta en ningún indicador
        run(course, AgentStep.LESSON_DRAFT, h(0), generator = Generator.DETERMINISTIC, version = v4, inputs = briefId)
        report(h(1), v4, course)
        transition(v4, "EN_REVISION", h(1))
        decision(v4, "APROBAR", h(30))

        // v5: generada y validada a las 3 h, sin revisar todavía
        run(course, AgentStep.LESSON_DRAFT, h(0), version = v5, inputs = briefId)
        report(h(3), v5, course)

        // v6: una edición que no es estructural (orden_aleatorio no es orden) y aprobada; entró en revisión sin informe
        run(course, AgentStep.LESSON_DRAFT, h(0), version = v6, interventions = listOf(edited("evaluacion ${UUID.randomUUID()}: orden_aleatorio")))
        transition(v6, "EN_REVISION", h(1))
        decision(v6, "APROBAR", h(2))

        // v7: aprobada sin entrada en revisión registrada (historial anterior a V16)
        run(course, AgentStep.LESSON_DRAFT, h(0), version = v7)
        decision(v7, "APROBAR", h(1))

        // análisis de fuentes: 4 citas comprobadas y 3 descartadas por su procedencia (BLANK no es una referencia)
        val gpt = Cost(BigDecimal("0.1"), "openai", "gpt-6", BigDecimal("1"), BigDecimal("5"), LocalDate.parse("2026-06-01"))
        run(
            course,
            AgentStep.SOURCE_ANALYSIS,
            h(0),
            cost = gpt,
            validations = mapOf("concepts" to 3, "procedures" to 1, "discarded" to listOf(discarded("QUOTE_NOT_IN_SOURCE"), discarded("BLANK"))),
        )
        run(
            course,
            AgentStep.SOURCE_ANALYSIS,
            h(0),
            status = AgentExecutionStatus.INVALID_OUTPUT,
            validations = mapOf("concepts" to 0, "procedures" to 0, "discarded" to listOf(discarded("NO_PROVENANCE"), discarded("ANCHOR_NOT_FOUND"))),
        )
        run(
            course,
            AgentStep.SOURCE_ANALYSIS,
            h(0),
            generator = Generator.DETERMINISTIC,
            validations =
                mapOf(
                    "concepts" to 10,
                    "procedures" to 0,
                    "discarded" to listOf<Any>(),
                ),
        )

        val (status, body) = read("/api/lms/ai/indicators?courseId=$course", admin)
        assertEquals(HttpStatus.OK, status, body.toString())
        val r = body!!
        assertEquals("PROCESS", r["kind"].asString())
        assertEquals("MODEL", r["generator"].asString())
        assertTrue(r["notice"].asString().contains("do not measure pedagogical quality"))
        val all =
            listOf(
                "acceptedWithoutStructuralChange",
                "briefToValidatedDraft",
                "verifiedReferences",
                "issuesBeforeReview",
                "humanReviewTime",
                "cost",
                "publishedWithoutGenerationErrors",
                "issuesAfterPublishing",
            )
        assertEquals((1..8).toList(), all.map { r[it]["number"].asInt() })
        all.forEach { assertEquals("PROCESS", r[it]["kind"].asString(), it) }

        // 1: revisadas v1, v2, v3, v6, v7; aceptadas sin cambio estructural v1, v6 y v7
        val accepted = r["acceptedWithoutStructuralChange"]
        assertEquals(3, accepted["numerator"].asLong())
        assertEquals(5, accepted["denominator"].asLong())
        assertEquals(0.6, accepted["value"].asDouble(), 1e-9)

        // 2: v1 4 h, v2 6 h, v5 3 h -> mediana 4 h; v3 no tiene brief
        val brief2 = r["briefToValidatedDraft"]
        assertEquals(3, brief2["sample"].asLong())
        assertEquals(1, brief2["excluded"].asLong())
        assertEquals(4 * 3600.0, brief2["medianSeconds"].asDouble(), 1e-3)

        // 3: 4 ÷ 7
        val references = r["verifiedReferences"]
        assertEquals(4, references["numerator"].asLong())
        assertEquals(7, references["denominator"].asLong())

        // 4: el último informe antes de la primera entrada: v1 (1 aviso), v2 (2 avisos), v3 (0); v6 entró sin informe
        val issues = r["issuesBeforeReview"]
        assertEquals(3, issues["drafts"].asLong())
        assertEquals(1, issues["withoutReport"].asLong())
        assertEquals(3, issues["problems"].asLong())
        assertEquals(0, issues["blocking"].asLong())
        assertEquals(3, issues["warnings"].asLong())
        assertEquals(1.0, issues["perDraft"].asDouble(), 1e-9)

        // 5: v1 2 h, v2 4 h, v3 1 h y 6 h (desde su última entrada), v6 1 h -> mediana 2 h; v7 sin entrada
        val review = r["humanReviewTime"]
        assertEquals(5, review["sample"].asLong())
        assertEquals(1, review["excluded"].asLong())
        assertEquals(2 * 3600.0, review["medianSeconds"].asDouble(), 1e-3)

        // 6: el curso gasta 0.85 USD en 3 ejecuciones con dos tarifas; 6 ejecuciones MODEL sin tarifa quedan sin importe
        val perCourse = r["cost"]["perCourse"].values().toList()
        val usd = perCourse.single { it["currency"].asString() == "USD" }
        assertEquals(0, BigDecimal("0.85").compareTo(usd["amount"].decimalValue()), usd.toString())
        assertEquals(3, usd["runs"].asLong())
        assertEquals(listOf("2026-01-01", "2026-06-01"), usd["rates"].values().map { it["since"].asString() })
        val unpriced = perCourse.single { it["currency"].isNull }
        assertTrue(unpriced["amount"].isNull)
        assertEquals(6, unpriced["runs"].asLong())
        val perUnit = r["cost"]["perUnit"].values().toList()
        assertEquals(unit.toString(), perUnit.single()["unitId"].asString())
        assertEquals(0, BigDecimal("0.75").compareTo(perUnit.single()["amount"].decimalValue()))
        assertEquals(2, perUnit.single()["runs"].asLong())

        // 7 y 8: definidos, sin dato todavía (feedback contextual, F6)
        assertEquals("UNAVAILABLE", r["publishedWithoutGenerationErrors"]["status"].asString())
        assertEquals("UNAVAILABLE", r["issuesAfterPublishing"]["status"].asString())
        assertTrue(r["quality"] == null && r["perceivedQuality"] == null, "la calidad percibida no se sirve aquí")
    }

    @Test
    fun `reading the indicators needs VER_INDICADORES_IA, never a role name`() {
        val course = course()
        for (role in listOf("creador", "revisor", "docente", "estudiante", "autoridad")) {
            assertEquals(HttpStatus.FORBIDDEN, read("/api/lms/ai/indicators", demoUser(role)).first, role)
            assertEquals(HttpStatus.FORBIDDEN, read("/api/lms/ai/indicators?courseId=$course", demoUser(role)).first, role)
        }
        // una cuenta con un rol nuevo que solo tiene esa acción (y leer cursos) los lee; sin leer briefs, el 2 va sin muestra
        val reader = accountWith(mapOf("curso" to listOf("READ"), "version_curso" to listOf("VER_INDICADORES_IA")))
        val (status, body) = read("/api/lms/ai/indicators?courseId=$course", reader)
        assertEquals(HttpStatus.OK, status, body.toString())
        assertEquals("PROCESS", body!!["kind"].asString())
        assertEquals(HttpStatus.OK, read("/api/lms/ai/indicators", admin).first)
        // un curso que no existe es 404
        assertEquals(HttpStatus.NOT_FOUND, read("/api/lms/ai/indicators?courseId=${UUID.randomUUID()}", admin).first)
    }

    private fun accepted() =
        mapOf("userId" to requester.toString(), "kind" to "ACCEPTED", "at" to "2026-11-03T10:00:00Z", "note" to "review decision: APROBADA")

    private fun discarded(reason: String) = mapOf("kind" to "CONCEPT", "name" to "x", "source" to null, "reason" to reason)

    // una cuenta con un rol nuevo que tiene exactamente esos permisos, creada por el administrador
    private fun accountWith(permissions: Map<String, List<String>>): String {
        val role = uniqueName("INDICADORES_").uppercase().replace('-', '_')
        client
            .post()
            .uri("/api/roles")
            .header("Authorization", admin)
            .bodyValue(mapOf("name" to role, "label" to "Solo indicadores"))
            .exchange()
            .expectStatus()
            .isCreated
        val entries = permissions.flatMap { (obj, actions) -> actions.map { mapOf("objectName" to obj, "action" to it) } }
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header("Authorization", admin)
            .bodyValue(mapOf("permissions" to entries))
            .exchange()
            .expectStatus()
            .isOk
        val email = "${uniqueName("ind-").lowercase()}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Cuenta de indicadores", "password" to DEMO_PASSWORD, "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    private companion object {
        val DEMO_ORGANIZATION: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
