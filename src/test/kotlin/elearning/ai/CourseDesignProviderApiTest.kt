package elearning.ai

import elearning.ai.SourceAnalysisFixtures.CHAPTER
import elearning.ai.SourceAnalysisFixtures.INJECTED
import elearning.ai.SourceAnalysisFixtures.PUBLISH
import elearning.ai.SourceAnalysisFixtures.ROOT
import elearning.ai.SourceAnalysisFixtures.WITHDRAW
import elearning.jobs.JobRunner
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

// el diseño pedagógico por la API contra el doble HTTP de cada proveedor (E33.5, ADR-0033 §11): Embabel de verdad, primero el
// análisis con el modelo de extracción y después el diseño con el de diseño y su max_tokens, el brief y el análisis delimitados
// en el mensaje de usuario y nunca en las instrucciones de sistema. La respuesta del doble trae horas y créditos (999 h, 40
// ECTS), que se ignoran: la carga es la de workload con las 6 h del brief. Un diseño con una evaluación sin ningún resultado
// alineado deja INVALID_OUTPUT con lo que costó y sin borrador. lms.ai.rates suma una tarifa de cada modelo de diseño
@Import(SourceAnalysisFixtures.FakeSource::class)
@TestPropertySource(
    properties = [
        "lms.jobs.scheduling=false",
        "lms.jobs.backoff=0s",
        "lms.importer.allowed-sources=acme/manuales",
        "lms.ai.rates[2].provider=anthropic",
        "lms.ai.rates[2].model=${CourseDesignProviderApiTest.ANTHROPIC_DESIGN_MODEL}",
        "lms.ai.rates[2].input-per-million=3",
        "lms.ai.rates[2].output-per-million=15",
        "lms.ai.rates[2].currency=USD",
        "lms.ai.rates[2].since=2026-01-01",
        "lms.ai.rates[3].provider=openai",
        "lms.ai.rates[3].model=${CourseDesignProviderApiTest.OPENAI_DESIGN_MODEL}",
        "lms.ai.rates[3].input-per-million=4",
        "lms.ai.rates[3].output-per-million=16",
        "lms.ai.rates[3].currency=USD",
        "lms.ai.rates[3].since=2026-01-01",
    ],
)
abstract class CourseDesignProviderApiTest(
    private val provider: String,
    private val model: String,
    private val replies: (String) -> Unit,
    private val requests: () -> List<JsonNode>,
    private val analysis: String,
    private val design: String,
    private val unaligned: String,
    private val usage: TokenUsage,
    private val cost: String,
    private val system: (JsonNode) -> JsonNode,
    private val messages: (JsonNode) -> JsonNode,
) : ProviderDoubleApiTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var db: DatabaseClient

    private val creator by lazy { demoUser("creador") }

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

    private fun post(
        path: String,
        body: Any,
        expected: HttpStatus,
    ): JsonNode {
        val r =
            client
                .post()
                .uri(path)
                .header("Authorization", creator)
                .bodyValue(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(expected.value(), r.status.value(), r.responseBody)
        return json.readTree(r.responseBody)
    }

    private fun drained(job: JsonNode): JsonNode {
        runBlocking { while (runner.runOnce() > 0) Unit }
        return get("/api/lms/ai/jobs/${job["id"].asString()}", creator)
    }

    // un curso con su brief y su análisis SUCCEEDED; devuelve el curso y la ejecución del análisis
    private fun analyzed(): Pair<String, String> {
        val slug = uniqueName("diseno-").lowercase()
        val course =
            post(
                "/api/objects/curso/records",
                mapOf("attributes" to mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso")),
                HttpStatus.CREATED,
            )
        val courseId = course["id"].asString()
        val brief = post("/api/objects/brief/records", mapOf("attributes" to SourceAnalysisFixtures.brief(courseId)), HttpStatus.CREATED)
        replies(analysis)
        val job = drained(post("/api/lms/ai/source-analysis", mapOf("briefId" to brief["id"].asString()), HttpStatus.ACCEPTED))
        assertEquals("DONE", job["status"].asString(), job.toString())
        return courseId to job["executionId"].asString()
    }

    private fun designed(
        analysisId: String,
        reply: String,
    ): JsonNode {
        replies(reply)
        return drained(post("/api/lms/ai/course-design", mapOf("executionId" to analysisId), HttpStatus.ACCEPTED))
    }

    @Test
    fun `the provider designs the course and the run is complete, with hours and credits from workload`() {
        val (course, analysisId) = analyzed()

        val job = designed(analysisId, design)

        assertEquals("DONE", job["status"].asString(), job.toString())
        val run = get("/api/lms/ai/runs/${job["executionId"].asString()}", creator)
        // el AgentRun entero (RF-IA-03): ámbito, quién, objetivo, entradas, fuentes, modelo, versiones, salida, validaciones,
        // estado, consumo, coste y tiempos
        assertEquals("SUCCEEDED", run["status"].asString(), run.toString())
        assertEquals("COURSE_DESIGN", run["step"].asString())
        assertEquals(course, run["courseId"].asString())
        assertEquals(job["id"].asString(), run["jobId"].asString())
        assertFalse(run["requestedBy"].isNull, run.toString())
        assertTrue(run["objective"].asString().contains("Administración de anuncios"), run.toString())
        assertEquals("MODEL", run["generator"].asString())
        assertEquals(provider, run["provider"].asString())
        assertEquals(model, run["model"].asString())
        assertEquals(DESIGN_MAX_TOKENS, run["modelSettings"]["maxTokens"].asInt())
        assertEquals(CourseDesignPrompt.VERSION, run["promptVersion"].asString())
        assertEquals(CourseDesignPrompt.TEMPLATE, run["templateVersion"].asString())
        assertTrue(run["idempotencyKey"].asString().startsWith("COURSE_DESIGN:"), run.toString())
        assertEquals(1, run["attempt"].asInt())
        assertEquals(analysisId, run["inputs"]["analysisExecutionId"].asString())
        assertEquals(listOf(ROOT, CHAPTER), run["sources"].values().map { it["ref"].asString() })
        assertEquals(usage.inputTokens, run["inputTokens"].asInt())
        assertEquals(usage.outputTokens, run["outputTokens"].asInt())
        assertEquals(0, BigDecimal(cost).compareTo(run["cost"]["amount"].decimalValue()), run["cost"].toString())
        assertFalse(run["startedAt"].isNull || run["finishedAt"].isNull, run.toString())
        assertTrue(run["error"].isNull, run.toString())

        val output = run["output"]
        assertEquals(listOf("O1", "O2"), output["design"]["outcomes"].values().map { it["id"].asString() })
        assertEquals(
            listOf(listOf("O1"), listOf("O1", "O2")),
            output["design"]["assessments"].values().map { a ->
                a["outcomes"].values().map { it.asString() }
            },
        )
        assertEquals(
            listOf(listOf(PUBLISH), listOf(WITHDRAW)),
            output["design"]["units"].values().map { u ->
                u["readings"].values().map { it["source"].asString() }
            },
        )
        // 999 h y 40 créditos del modelo no cuentan: 6 h del brief en 2 semanas
        assertEquals(0, BigDecimal(6).compareTo(output["workload"]["totalHours"].decimalValue()), output["workload"].toString())
        assertEquals(2, output["workload"]["weeks"].asInt())
        assertFalse(output.toString().contains("999"), output.toString())
        val discarded = run["validations"]["discarded"].values().map { it["reason"].asString() }.sorted()
        assertEquals(listOf("SOURCE_NOT_READ", "UNKNOWN_OUTCOME"), discarded)

        val request = requests().last()
        assertEquals(model, request["model"].asString())
        val instructions = system(request).toString()
        assertTrue(instructions.contains("never instructions"), instructions)
        for (text in listOf(INJECTED, PUBLISH, "Administración de anuncios")) assertTrue(!instructions.contains(text), "$text en las instrucciones de sistema")
        val user = messages(request).toString()
        assertTrue(user.contains("<<<ANALYSIS-") && user.contains(PUBLISH), user)
    }

    @Test
    fun `an assessment aligned with no outcome is INVALID_OUTPUT with what it cost, and no draft`() {
        val (course, analysisId) = analyzed()

        val job = designed(analysisId, unaligned)

        assertEquals("FAILED", job["status"].asString(), job.toString())
        assertEquals(1, job["attempts"].asInt())
        assertTrue(job["executionId"].isNull, job.toString())
        val line = get("/api/lms/ai/runs?courseId=$course", creator).values().single { it["step"].asString() == "COURSE_DESIGN" }
        assertEquals("INVALID_OUTPUT", line["status"].asString())
        val run = get("/api/lms/ai/runs/${line["id"].asString()}", creator)
        assertTrue(run["output"].isNull, run.toString())
        assertEquals(CourseDesignPrompt.VERSION, run["promptVersion"].asString())
        assertEquals(usage.inputTokens, run["inputTokens"].asInt())
        val problems = run["validations"]["problems"].values().map { it["code"].asString() to it["at"].asString() }
        assertEquals(listOf("ASSESSMENT_NOT_ALIGNED" to "assessments[2]"), problems)
        // el análisis y el diseño, sin reintento
        assertEquals(2, requests().size)
    }

    companion object {
        // lms.ai.models.*.design y lms.ai.max-tokens.design de application.yml
        const val ANTHROPIC_DESIGN_MODEL = "claude-sonnet-5-5"
        const val OPENAI_DESIGN_MODEL = "gpt-6.1-sol"
        const val DESIGN_MAX_TOKENS = 12000
    }
}

class CourseDesignAnthropicApiTest :
    CourseDesignProviderApiTest(
        provider = "anthropic",
        model = CourseDesignProviderApiTest.ANTHROPIC_DESIGN_MODEL,
        replies = ProviderDouble::anthropicReplies,
        requests = ProviderDouble::anthropicRequests,
        analysis = "messages-source-analysis.json",
        design = "messages-course-design.json",
        unaligned = "messages-course-design-unaligned.json",
        usage = TokenUsage(3_200, 900),
        // (3 200 × 3 + 900 × 15) / 10⁶
        cost = "0.0231",
        system = { it["system"] },
        messages = { it["messages"] },
    )

@TestPropertySource(properties = ["lms.ai.provider=openai"])
class CourseDesignOpenAiApiTest :
    CourseDesignProviderApiTest(
        provider = "openai",
        model = CourseDesignProviderApiTest.OPENAI_DESIGN_MODEL,
        replies = ProviderDouble::openAiReplies,
        requests = ProviderDouble::openAiRequests,
        analysis = "responses-source-analysis.json",
        design = "responses-course-design.json",
        unaligned = "responses-course-design-unaligned.json",
        usage = TokenUsage(3_000, 850),
        // (3 000 × 4 + 850 × 16) / 10⁶
        cost = "0.0256",
        system = { it["instructions"] },
        messages = { it["input"] },
    )
