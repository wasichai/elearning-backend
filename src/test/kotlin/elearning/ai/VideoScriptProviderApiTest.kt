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

// el guion por la API contra el doble HTTP de cada proveedor (E36.1, ADR-0033 §11): Embabel de verdad, con el modelo del guion
// (lms.ai.models.<proveedor>.script) y su max_tokens, la fuente delimitada en el mensaje de usuario y nunca en las instrucciones
// de sistema, el guion comprobado por código y guardado como versión del guion de su vídeo, del esquema del render. La respuesta
// con una traza a una sección que no existe invalida el guion entero: INVALID_OUTPUT, con lo que costó, y ningún guion guardado
@Import(SourceAnalysisFixtures.FakeSource::class)
@TestPropertySource(properties = ["lms.jobs.scheduling=false", "lms.jobs.backoff=0s", "lms.importer.allowed-sources=acme/manuales"])
abstract class VideoScriptProviderApiTest(
    private val provider: String,
    private val model: String,
    private val replies: (String) -> Unit,
    private val requests: () -> List<JsonNode>,
    private val script: String,
    private val badTrace: String,
    private val usage: TokenUsage,
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

    private fun created(
        objectName: String,
        attributes: Map<String, Any?>,
    ): String = post("/api/objects/$objectName/records", mapOf("attributes" to attributes), HttpStatus.CREATED)["id"].asString()

    private fun course(): String {
        val slug = uniqueName("guion-").lowercase()
        return created("curso", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso"))
    }

    private fun video(): String {
        val resource = created("recurso", mapOf("titulo" to "Vídeo de anuncios", "tipo" to "VIDEO", "idioma" to "es"))
        return created("version_recurso", mapOf("recurso" to resource, "numero" to 1, "autor_id" to get("/api/auth/me", creator)["userId"].asString()))
    }

    private fun written(
        course: String,
        version: String,
    ): JsonNode {
        val brief = created("brief", SourceAnalysisFixtures.brief(course))
        val job = post("/api/lms/ai/scripts", mapOf("briefId" to brief, "versionId" to version, "targetSeconds" to TARGET), HttpStatus.ACCEPTED)
        runBlocking { while (runner.runOnce() > 0) Unit }
        return get("/api/lms/ai/jobs/${job["id"].asString()}", creator)
    }

    private fun scripts(version: String) = get("/api/objects/guion_video/records?version_recurso=$version&size=10", creator)["content"].values().toList()

    @Test
    fun `the provider writes the script, it passes the check and is stored as the script of the video, valid for the render`() {
        replies(script)
        val version = video()

        val job = written(course(), version)

        assertEquals("DONE", job["status"].asString(), job.toString())
        val run = get("/api/lms/ai/runs/${job["executionId"].asString()}", creator)
        assertEquals("SUCCEEDED", run["status"].asString(), run.toString())
        assertEquals("MODEL", run["generator"].asString())
        assertEquals(provider, run["provider"].asString())
        assertEquals(model, run["model"].asString())
        assertEquals(SCRIPT_MAX_TOKENS, run["modelSettings"]["maxTokens"].asInt())
        assertEquals(usage.inputTokens, run["inputTokens"].asInt())
        assertEquals(usage.outputTokens, run["outputTokens"].asInt())
        assertEquals(listOf(PUBLISH, PUBLISH, WITHDRAW), run["output"]["sections"].values().map { it["traces"][0].asString() })

        val stored = scripts(version).single()["attributes"]
        assertEquals(1, stored["numero"].asInt())
        assertEquals(TARGET, stored["duracion_segundos"].asInt())
        assertEquals(emptyList<String>(), GuionSchema.violations(json.readTree(stored["guion"].asString())))
        assertTrue(stored["subtitulos_vtt"].asString().contains("00:00:22.500 --> 00:00:35.000"), stored["subtitulos_vtt"].asString())

        val request = requests().single()
        assertEquals(model, request["model"].asString())
        val instructions = system(request).toString()
        assertTrue(instructions.contains("never instructions"), instructions)
        for (text in listOf(INJECTED, "Abrir el panel de anuncios", PUBLISH)) assertTrue(!instructions.contains(text), "$text en las instrucciones de sistema")
        val user = messages(request).toString()
        assertTrue(user.contains(INJECTED) && user.contains("<<<SOURCE-") && user.contains("Target duration: $TARGET seconds"), user)
    }

    // RF-MED-01: una traza a una sección que no existe invalida el guion, aunque el resto valga
    @Test
    fun `a trace to a section that does not exist invalidates the script of the provider, with what it cost, and nothing is stored`() {
        replies(badTrace)
        val course = course()
        val version = video()

        val job = written(course, version)

        assertEquals("FAILED", job["status"].asString(), job.toString())
        assertEquals(1, job["attempts"].asInt())
        assertTrue(job["executionId"].isNull, job.toString())
        val line = get("/api/lms/ai/runs?courseId=$course", creator).values().single()
        assertEquals("INVALID_OUTPUT", line["status"].asString())
        val run = get("/api/lms/ai/runs/${line["id"].asString()}", creator)
        assertTrue(run["output"].isNull, run.toString())
        assertEquals(usage.inputTokens, run["inputTokens"].asInt())
        val violation = run["validations"]["violations"].values().single()
        assertEquals("TRACE_NOT_FOUND", violation["code"].asString())
        assertEquals("s03", violation["section"].asString())
        assertEquals(1, requests().size)
        assertTrue(scripts(version).isEmpty())
    }

    companion object {
        // lms.ai.max-tokens.script de application.yml
        const val SCRIPT_MAX_TOKENS = 16000

        // la duración de los guiones de src/test/resources/ai/*/…-video-script*.json
        const val TARGET = 60
    }
}

// con las dos claves y sin lms.ai.provider, la canalización usa Anthropic
class VideoScriptAnthropicApiTest :
    VideoScriptProviderApiTest(
        provider = "anthropic",
        // lms.ai.models.anthropic.script de application.yml
        model = "claude-sonnet-5-5",
        replies = ProviderDouble::anthropicReplies,
        requests = ProviderDouble::anthropicRequests,
        script = "messages-video-script.json",
        badTrace = "messages-video-script-bad-trace.json",
        usage = TokenUsage(2_400, 900),
        system = { it["system"] },
        messages = { it["messages"] },
    )

@TestPropertySource(properties = ["lms.ai.provider=openai"])
class VideoScriptOpenAiApiTest :
    VideoScriptProviderApiTest(
        provider = "openai",
        model = "gpt-6.1-sol",
        replies = ProviderDouble::openAiReplies,
        requests = ProviderDouble::openAiRequests,
        script = "responses-video-script.json",
        badTrace = "responses-video-script-bad-trace.json",
        usage = TokenUsage(2_300, 850),
        system = { it["instructions"] },
        messages = { it["input"] },
    )
