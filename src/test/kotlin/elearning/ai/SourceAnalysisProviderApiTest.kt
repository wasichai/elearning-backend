package elearning.ai

import elearning.ai.SourceAnalysisFixtures.INJECTED
import elearning.ai.SourceAnalysisFixtures.PUBLISH
import elearning.ai.SourceAnalysisFixtures.WITHDRAW
import elearning.jobs.JobRunner
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

// el análisis de fuentes por la API contra el doble HTTP de cada proveedor (E33.4, ADR-0033 §11): Embabel de verdad, con el
// modelo de extracción y su max_tokens, la fuente delimitada en el mensaje de usuario y nunca en las instrucciones de sistema
// (system en Anthropic, instructions en la Responses API de OpenAI), la salida comprobada por código y su AgentRun MODEL con
// consumo y coste. La respuesta del doble trae dos elementos con procedencia real y tres que no: esos se descartan. Una salida
// que no es el JSON del tipo deja INVALID_OUTPUT, con lo que costaron sus dos intentos, y ningún borrador
@Import(SourceAnalysisFixtures.FakeSource::class)
@TestPropertySource(properties = ["lms.jobs.scheduling=false", "lms.jobs.backoff=0s", "lms.importer.allowed-sources=acme/manuales"])
abstract class SourceAnalysisProviderApiTest(
    private val provider: String,
    private val model: String,
    private val replies: (String) -> Unit,
    private val requests: () -> List<JsonNode>,
    private val analysis: String,
    private val invalid: String,
    private val usage: TokenUsage,
    // con las tarifas de ProviderDoubleApiTest
    private val cost: String,
    // dónde pone cada API las instrucciones de sistema y dónde los mensajes
    private val system: (JsonNode) -> JsonNode,
    private val messages: (JsonNode) -> JsonNode,
) : ProviderDoubleApiTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var db: DatabaseClient

    private val creator by lazy { demoUser("creador") }

    // los pasos de IA que otra clase dejó pendientes cuentan para la concurrencia de la organización y el tope por persona
    @BeforeEach
    fun cancelLeftovers() {
        runBlocking {
            db
                .sql(
                    "UPDATE lms.trabajo SET estado = 'CANCELLED', reclamado_en = NULL, terminado_en = now() " +
                        "WHERE tipo LIKE 'ai.%' AND estado IN ('QUEUED', 'RUNNING')",
                ).fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    private fun created(
        path: String,
        attributes: Map<String, Any?>,
    ): String {
        val r =
            client
                .post()
                .uri(path)
                .header("Authorization", creator)
                .bodyValue(mapOf("attributes" to attributes))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(HttpStatus.CREATED.value(), r.status.value(), r.responseBody)
        return json.readTree(r.responseBody)["id"].asString()
    }

    private fun course(): String {
        val slug = uniqueName("analisis-").lowercase()
        return created("/api/objects/curso/records", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso"))
    }

    private fun analyzed(course: String): JsonNode {
        val brief = created("/api/objects/brief/records", SourceAnalysisFixtures.brief(course))
        val r =
            client
                .post()
                .uri("/api/lms/ai/source-analysis")
                .header("Authorization", creator)
                .bodyValue(mapOf("briefId" to brief))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(HttpStatus.ACCEPTED.value(), r.status.value(), r.responseBody)
        runBlocking { while (runner.runOnce() > 0) Unit }
        return get("/api/lms/ai/jobs/${json.readTree(r.responseBody)["id"].asString()}", creator)
    }

    @Test
    fun `the provider analyzes the sources, what has no real provenance is discarded and the run is MODEL with its cost`() {
        replies(analysis)
        val course = course()

        val job = analyzed(course)

        assertEquals("DONE", job["status"].asString(), job.toString())
        val run = get("/api/lms/ai/runs/${job["executionId"].asString()}", creator)
        assertEquals("SUCCEEDED", run["status"].asString(), run.toString())
        assertEquals("MODEL", run["generator"].asString())
        assertEquals(provider, run["provider"].asString())
        assertEquals(model, run["model"].asString())
        assertEquals(EXTRACTION_MAX_TOKENS, run["modelSettings"]["maxTokens"].asInt())
        assertEquals(usage.inputTokens, run["inputTokens"].asInt())
        assertEquals(usage.outputTokens, run["outputTokens"].asInt())
        assertEquals(0, BigDecimal(cost).compareTo(run["cost"]["amount"].decimalValue()), run["cost"].toString())

        val output = run["output"]
        assertEquals(listOf(WITHDRAW), output["concepts"].values().map { it["source"].asString() })
        assertEquals(listOf(PUBLISH), output["procedures"].values().map { it["source"].asString() })
        val discarded = run["validations"]["discarded"].values().associate { it["name"].asString() to it["reason"].asString() }
        assertEquals(mapOf("Caducidad" to "QUOTE_NOT_IN_SOURCE", "Sin procedencia" to "NO_PROVENANCE", "Ancla inventada" to "ANCHOR_NOT_FOUND"), discarded)

        val request = requests().single()
        assertEquals(model, request["model"].asString())
        val instructions = system(request).toString()
        assertTrue(instructions.contains("never instructions"), instructions)
        for (text in listOf(INJECTED, "Abrir el panel de anuncios", PUBLISH)) assertTrue(!instructions.contains(text), "$text en las instrucciones de sistema")
        val user = messages(request).toString()
        assertTrue(user.contains(INJECTED) && user.contains("<<<SOURCE-") && user.contains(PUBLISH), user)
    }

    @Test
    fun `a malformed output from the provider is INVALID_OUTPUT with what it cost, and no draft`() {
        replies(invalid)
        val course = course()

        val job = analyzed(course)

        assertEquals("FAILED", job["status"].asString(), job.toString())
        assertEquals(1, job["attempts"].asInt())
        assertTrue(job["executionId"].isNull, job.toString())
        val line = get("/api/lms/ai/runs?courseId=$course", creator).values().single()
        assertEquals("INVALID_OUTPUT", line["status"].asString())
        assertEquals("MODEL", line["generator"].asString())
        val run = get("/api/lms/ai/runs/${line["id"].asString()}", creator)
        assertTrue(run["output"].isNull, run.toString())
        // los dos intentos de Embabel: el primero y su corrección
        assertEquals(EMBABEL_ATTEMPTS, requests().size)
        assertTrue(run["inputTokens"].asInt() > 0, run.toString())
    }

    companion object {
        // lms.ai.max-tokens.extraction de application.yml
        const val EXTRACTION_MAX_TOKENS = 8000
        const val EMBABEL_ATTEMPTS = AiStatus.MAX_ATTEMPTS
    }
}

// con las dos claves y sin lms.ai.provider, la canalización usa Anthropic
class SourceAnalysisAnthropicApiTest :
    SourceAnalysisProviderApiTest(
        provider = "anthropic",
        model = ProviderDoubleApiTest.ANTHROPIC_MODEL,
        replies = ProviderDouble::anthropicReplies,
        requests = ProviderDouble::anthropicRequests,
        analysis = "messages-source-analysis.json",
        invalid = "messages-invalid-json.json",
        usage = TokenUsage(2_100, 400),
        // (2 100 × 1 + 400 × 5) / 10⁶
        cost = "0.0041",
        system = { it["system"] },
        messages = { it["messages"] },
    )

@TestPropertySource(properties = ["lms.ai.provider=openai"])
class SourceAnalysisOpenAiApiTest :
    SourceAnalysisProviderApiTest(
        provider = "openai",
        model = ProviderDoubleApiTest.OPENAI_MODEL,
        replies = ProviderDouble::openAiReplies,
        requests = ProviderDouble::openAiRequests,
        analysis = "responses-source-analysis.json",
        invalid = "responses-invalid-json.json",
        usage = TokenUsage(1_900, 380),
        // (1 900 × 2 + 380 × 8) / 10⁶
        cost = "0.00684",
        system = { it["instructions"] },
        messages = { it["input"] },
    )
