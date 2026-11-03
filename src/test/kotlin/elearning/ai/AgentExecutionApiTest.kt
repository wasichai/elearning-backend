package elearning.ai

import elearning.LmsApiTest
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.env.MockEnvironment
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import wasichai.core.common.NotFoundException
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

// el registro AgentRun (RF-IA-03, ADR-0033 §7): lo que se escribe, lo que se calcula, quién lo lee y qué no cambia.
// La ejecución se construye en la prueba; la de un paso real contra el doble WireMock llega con él (E32.3, #274). El
// registro se arma aquí con su tarifa, su reloj y una clave centinela, sin tocar el contexto compartido: ninguna clave
// llega a la configuración de la aplicación
class AgentExecutionApiTest : LmsApiTest() {
    @Autowired
    private lateinit var store: AgentExecutionStore

    @Autowired
    private lateinit var db: DatabaseClient

    private val admin by lazy { bearer() }
    private val creator by lazy { demoUser("creador") }
    private val reviewer by lazy { demoUser("revisor") }
    private val teacher by lazy { demoUser("docente") }
    private val student by lazy { demoUser("estudiante") }

    private val log by lazy {
        AgentExecutionLog(
            store,
            AiRates(
                listOf(
                    AiRates.Rate("anthropic", MODEL, BigDecimal("3"), BigDecimal("15"), "USD", LocalDate.parse("2026-01-01")),
                    // todavía no rige el día de la prueba
                    AiRates.Rate("anthropic", MODEL, BigDecimal("99"), BigDecimal("99"), "USD", LocalDate.parse("2099-01-01")),
                ),
            ),
            Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC),
            MockEnvironment().withProperty("ANTHROPIC_API_KEY", SENTINEL),
        )
    }

    private fun courseId(): UUID = UUID.fromString(get("/api/objects/curso/records", admin)["content"][0]["id"].asString())

    private fun me(token: String) = UUID.fromString(get("/api/auth/me", token)["userId"].asString())

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

    private fun newRun(
        course: UUID = courseId(),
        organization: UUID = DEMO_ORGANIZATION,
        generator: Generator = Generator.MODEL,
        model: String = MODEL,
        objective: String = "Analizar las fuentes del curso",
        inputs: Any = mapOf("brief" to "Un curso de Kotlin", "sourceIds" to listOf("s-1")),
    ) = NewAgentExecution(
        organizationId = organization,
        jobId = UUID.randomUUID(),
        step = AgentStep.SOURCE_ANALYSIS,
        requestedBy = me(creator),
        courseId = course,
        unitId = UNIT,
        versionId = VERSION,
        objective = objective,
        inputs = inputs,
        sources = listOf(SourceDigest("acme/manuales@$SHA:manual.adoc#intro", "ab".repeat(32))),
        generator = generator,
        model = if (generator == Generator.MODEL) ModelChoice("anthropic", model, ModelSettings(maxTokens = 4_096, temperature = 0.2)) else null,
        promptVersion = "source-analysis@3",
        templateVersion = "course-outline@1",
        idempotencyKey = "SOURCE_ANALYSIS:${UUID.randomUUID()}",
        attempt = 2,
    )

    private fun sql(statement: String) =
        runBlocking {
            db
                .sql(statement)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }

    @Test
    fun `a closed execution keeps every field, the provider token usage and its cost with the rate and its date`() {
        val run = newRun()
        val id = runBlocking { log.start(run) }
        val open = ok(read("/api/lms/ai/runs/$id", creator))
        assertEquals("RUNNING", open["status"].asString())
        assertTrue(open["finishedAt"].isNull)

        runBlocking {
            log.finish(
                DEMO_ORGANIZATION,
                id,
                AgentExecutionOutcome(
                    status = AgentExecutionStatus.SUCCEEDED,
                    output = mapOf("units" to listOf(mapOf("title" to "Introducción"))),
                    validations = mapOf("schema" to "OK", "references" to listOf<String>()),
                    usage = TokenUsage(inputTokens = 1_200, outputTokens = 300),
                ),
            )
        }

        val body = ok(read("/api/lms/ai/runs/$id", creator))
        assertEquals(id.toString(), body["id"].asString())
        assertEquals(run.jobId.toString(), body["jobId"].asString())
        assertEquals("SOURCE_ANALYSIS", body["step"].asString())
        assertEquals(me(creator).toString(), body["requestedBy"].asString())
        assertEquals(run.courseId.toString(), body["courseId"].asString())
        assertEquals(UNIT.toString(), body["unitId"].asString())
        assertEquals(VERSION.toString(), body["versionId"].asString())
        assertEquals("Analizar las fuentes del curso", body["objective"].asString())
        assertEquals("Un curso de Kotlin", body["inputs"]["brief"].asString())
        assertEquals("s-1", body["inputs"]["sourceIds"][0].asString())
        assertEquals("acme/manuales@$SHA:manual.adoc#intro", body["sources"][0]["ref"].asString())
        assertEquals("ab".repeat(32), body["sources"][0]["sha256"].asString())
        assertEquals("MODEL", body["generator"].asString())
        assertEquals("anthropic", body["provider"].asString())
        assertEquals(MODEL, body["model"].asString())
        assertEquals(4_096, body["modelSettings"]["maxTokens"].asInt())
        assertEquals(0.2, body["modelSettings"]["temperature"].asDouble())
        assertEquals("source-analysis@3", body["promptVersion"].asString())
        assertEquals("course-outline@1", body["templateVersion"].asString())
        assertEquals(run.idempotencyKey, body["idempotencyKey"].asString())
        assertEquals(2, body["attempt"].asInt())
        assertEquals("SUCCEEDED", body["status"].asString())
        assertEquals("Introducción", body["output"]["units"][0]["title"].asString())
        assertEquals("OK", body["validations"]["schema"].asString())
        assertTrue(body["error"].isNull)
        assertEquals(1_200, body["inputTokens"].asInt())
        assertEquals(300, body["outputTokens"].asInt())
        // (1 200 × 3 + 300 × 15) / 1 000 000, con la tarifa que regía el día en que empezó
        assertEquals(0, BigDecimal("0.0081").compareTo(body["cost"]["amount"].decimalValue()), body["cost"].toString())
        assertEquals("USD", body["cost"]["currency"].asString())
        assertEquals(0, BigDecimal("3").compareTo(body["cost"]["inputPerMillion"].decimalValue()))
        assertEquals(0, BigDecimal("15").compareTo(body["cost"]["outputPerMillion"].decimalValue()))
        assertEquals("2026-01-01", body["cost"]["rateSince"].asString())
        assertEquals(Instant.parse("2026-11-03T10:00:00Z"), Instant.parse(body["startedAt"].asString()))
        assertEquals(Instant.parse("2026-11-03T10:00:00Z"), Instant.parse(body["finishedAt"].asString()))
        assertEquals(0, body["interventions"].size())

        val line = ok(read("/api/lms/ai/runs?courseId=${run.courseId}", creator)).values().first { it["id"].asString() == id.toString() }
        assertEquals("SUCCEEDED", line["status"].asString())
        assertEquals(1_200, line["inputTokens"].asInt())
        assertEquals(0, BigDecimal("0.0081").compareTo(line["cost"]["amount"].decimalValue()))
        assertNull(line["inputs"], "la lista no lleva entradas ni salida")
        assertNull(line["output"])
    }

    @Test
    fun `without a rate or without a model the cost stays unknown, never invented`() {
        val unrated = runBlocking { log.start(newRun(model = "claude-opus-5-5")) }
        val deterministic = runBlocking { log.start(newRun(generator = Generator.DETERMINISTIC)) }
        runBlocking {
            log.finish(DEMO_ORGANIZATION, unrated, AgentExecutionOutcome(AgentExecutionStatus.SUCCEEDED, usage = TokenUsage(10, 5)))
            log.finish(DEMO_ORGANIZATION, deterministic, AgentExecutionOutcome(AgentExecutionStatus.SUCCEEDED, output = mapOf("ok" to true)))
        }

        val withoutRate = ok(read("/api/lms/ai/runs/$unrated", creator))
        assertEquals(10, withoutRate["inputTokens"].asInt())
        assertTrue(withoutRate["cost"].isNull)
        val double = ok(read("/api/lms/ai/runs/$deterministic", creator))
        assertEquals("DETERMINISTIC", double["generator"].asString())
        assertTrue(double["provider"].isNull)
        assertTrue(double["model"].isNull)
        assertTrue(double["modelSettings"].isNull)
        assertTrue(double["cost"].isNull)
    }

    @Test
    fun `no provider key reaches any field`() {
        val id =
            runBlocking {
                log.start(newRun(objective = "Analizar con $SENTINEL", inputs = mapOf("pasted" to "Authorization: Bearer $SENTINEL")))
            }
        runBlocking {
            log.finish(DEMO_ORGANIZATION, id, AgentExecutionOutcome(AgentExecutionStatus.FAILED, error = "401 for key $SENTINEL"))
            log.intervene(DEMO_ORGANIZATION, id, Intervention(me(reviewer), InterventionKind.REJECTED, Instant.now(), "la clave $SENTINEL"))
        }

        val stored =
            runBlocking {
                db
                    .sql("SELECT CAST(to_jsonb(e) AS text) AS fila FROM lms.ejecucion_agente e WHERE id = :id")
                    .bind("id", id)
                    .map { row, _ -> row.get("fila", String::class.java)!! }
                    .one()
                    .awaitSingle()
            }
        assertFalse(SENTINEL in stored, stored)
        assertTrue("[REDACTED]" in stored, stored)
        assertEquals("401 for key [REDACTED]", ok(read("/api/lms/ai/runs/$id", creator))["error"].asString())
    }

    @Test
    fun `who works the content reads the executions of a course it sees, anyone else gets 403 or 404`() {
        val course = courseId()
        val id = runBlocking { log.start(newRun(course = course)) }
        val elsewhere = runBlocking { log.start(newRun(course = course, organization = UUID.randomUUID())) }

        assertTrue(ok(read("/api/lms/ai/runs?courseId=$course", reviewer)).values().any { it["id"].asString() == id.toString() })
        assertEquals(HttpStatus.OK, read("/api/lms/ai/runs/$id", reviewer).first)
        assertEquals(HttpStatus.OK, read("/api/lms/ai/runs/$id", admin).first)
        // sin UPDATE sobre version_curso
        for (token in listOf(student, teacher)) {
            assertEquals(HttpStatus.FORBIDDEN, read("/api/lms/ai/runs?courseId=$course", token).first)
            assertEquals(HttpStatus.FORBIDDEN, read("/api/lms/ai/runs/$id", token).first)
        }
        // un curso que no existe para quien llama, una ejecución que no existe y la de otra organización
        assertEquals(HttpStatus.NOT_FOUND, read("/api/lms/ai/runs?courseId=${UUID.randomUUID()}", creator).first)
        assertEquals(HttpStatus.NOT_FOUND, read("/api/lms/ai/runs/${UUID.randomUUID()}", creator).first)
        assertEquals(HttpStatus.NOT_FOUND, read("/api/lms/ai/runs/$elsewhere", admin).first)
        assertFalse(ok(read("/api/lms/ai/runs?courseId=$course", admin)).values().any { it["id"].asString() == elsewhere.toString() })
        // sin sesión
        val anonymous =
            client
                .get()
                .uri("/api/lms/ai/runs/$id")
                .exchange()
                .returnResult(String::class.java)
                .status
        assertEquals(HttpStatus.UNAUTHORIZED.value(), anonymous.value())
    }

    // no hay ruta que escriba: ni POST, ni PATCH, ni DELETE
    @Test
    fun `the api only reads`() {
        val id = runBlocking { log.start(newRun()) }
        for (method in listOf("POST", "PATCH", "PUT", "DELETE")) {
            val status =
                client
                    .method(HttpMethod.valueOf(method))
                    .uri("/api/lms/ai/runs/$id")
                    .header("Authorization", admin)
                    .exchange()
                    .returnResult(String::class.java)
                    .status
            assertFalse(status.is2xxSuccessful, "$method → $status")
        }
    }

    @Test
    fun `an execution is neither edited nor deleted, closes once and only gains interventions`() {
        val id = runBlocking { log.start(newRun()) }
        val where = "WHERE id = '$id'"

        // lo que se pidió no cambia, ni antes ni después del cierre, y no se borra
        assertThrows<Exception> { sql("UPDATE lms.ejecucion_agente SET objetivo = 'otro' $where") }
        assertThrows<Exception> { sql("UPDATE lms.ejecucion_agente SET modelo = 'otro' $where") }
        assertThrows<Exception> { sql("DELETE FROM lms.ejecucion_agente $where") }

        runBlocking { log.finish(DEMO_ORGANIZATION, id, AgentExecutionOutcome(AgentExecutionStatus.INVALID_OUTPUT, validations = mapOf("schema" to "KO"))) }
        // cerrada no se vuelve a cerrar, por el registro ni por SQL
        assertThrows<IllegalStateException> {
            runBlocking { log.finish(DEMO_ORGANIZATION, id, AgentExecutionOutcome(AgentExecutionStatus.SUCCEEDED)) }
        }
        assertThrows<Exception> { sql("UPDATE lms.ejecucion_agente SET estado = 'SUCCEEDED' $where") }
        assertThrows<Exception> { sql("UPDATE lms.ejecucion_agente SET coste = 0 $where") }

        val first = Intervention(me(reviewer), InterventionKind.EDITED, Instant.parse("2026-11-03T11:00:00Z"), "Título corregido")
        val second = Intervention(me(creator), InterventionKind.ACCEPTED, Instant.parse("2026-11-03T12:00:00Z"))
        runBlocking {
            log.intervene(DEMO_ORGANIZATION, id, first)
            log.intervene(DEMO_ORGANIZATION, id, second)
        }
        val interventions = ok(read("/api/lms/ai/runs/$id", creator))["interventions"]
        assertEquals(listOf("EDITED", "ACCEPTED"), interventions.strings { it["kind"] })
        assertEquals(me(reviewer).toString(), interventions[0]["userId"].asString())
        assertEquals("Título corregido", interventions[0]["note"].asString())
        assertEquals(Instant.parse("2026-11-03T11:00:00Z"), Instant.parse(interventions[0]["at"].asString()))

        // las anteriores no se cambian, no se quitan ni se reordenan
        assertThrows<Exception> { sql("UPDATE lms.ejecucion_agente SET intervenciones = '[]' $where") }
        assertThrows<Exception> { sql("UPDATE lms.ejecucion_agente SET intervenciones = intervenciones - 0 $where") }
        assertThrows<Exception> {
            sql("UPDATE lms.ejecucion_agente SET intervenciones = jsonb_build_array(intervenciones -> 1, intervenciones -> 0) $where")
        }
        assertThrows<Exception> {
            sql("UPDATE lms.ejecucion_agente SET intervenciones = jsonb_set(intervenciones, '{0,kind}', '\"REJECTED\"') $where")
        }
        assertEquals(2, ok(read("/api/lms/ai/runs/$id", creator))["interventions"].size())
        // una intervención sobre una ejecución que no existe en la organización
        assertThrows<NotFoundException> { runBlocking { log.intervene(UUID.randomUUID(), id, second) } }
    }

    @Test
    fun `what is asked is validated before it is written`() {
        assertThrows<IllegalArgumentException> { newRun(objective = " ") }
        assertThrows<IllegalArgumentException> { newRun().copy(generator = Generator.DETERMINISTIC) }
        assertThrows<IllegalArgumentException> { newRun().copy(attempt = 0) }
        assertThrows<IllegalArgumentException> { ModelChoice("acme", MODEL, ModelSettings(maxTokens = 10)) }
        assertThrows<IllegalArgumentException> { ModelSettings(maxTokens = 0) }
        assertThrows<IllegalArgumentException> { AgentExecutionOutcome(AgentExecutionStatus.RUNNING) }
        assertNotNull(newRun(generator = Generator.DETERMINISTIC))
    }

    private fun ok(result: Pair<HttpStatus, JsonNode?>): JsonNode {
        assertEquals(HttpStatus.OK, result.first, result.second.toString())
        return result.second!!
    }

    private companion object {
        val DEMO_ORGANIZATION: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val UNIT: UUID = UUID.randomUUID()
        val VERSION: UUID = UUID.randomUUID()
        const val MODEL = "claude-sonnet-5-5"
        const val SHA = "0123456789abcdef0123456789abcdef01234567"

        // una clave centinela: la forma de una de Anthropic, nunca una real
        const val SENTINEL = "sk-ant-sentinel-0000-never-a-real-key"
    }
}
