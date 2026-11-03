package elearning.ai

import elearning.LmsApiTest
import elearning.ai.SourceAnalysisFixtures.CHAPTER
import elearning.ai.SourceAnalysisFixtures.MISSING
import elearning.ai.SourceAnalysisFixtures.NO_SECTIONS
import elearning.ai.SourceAnalysisFixtures.PUBLISH
import elearning.ai.SourceAnalysisFixtures.ROOT
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

// el análisis de fuentes por la API sin claves (E33.4, RF-IA-02, RF-IA-08): el doble determinista hace el paso entero, desde
// el brief LISTO hasta la ejecución DETERMINISTIC con su salida comprobada y sus fuentes con hash, sin red. Una salida que no
// pasa la comprobación queda INVALID_OUTPUT y sin borrador; una fuente que no existe falla sin ejecución. La cola se mueve a mano
@Import(SourceAnalysisFixtures.FakeSource::class)
@TestPropertySource(
    properties = [
        "ANTHROPIC_API_KEY=",
        "OPENAI_API_KEY=",
        "lms.jobs.scheduling=false",
        "lms.jobs.backoff=0s",
        "lms.importer.allowed-sources=acme/manuales",
    ],
)
class SourceAnalysisApiTest : LmsApiTest() {
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

    private val student by lazy { demoUser("estudiante") }

    private fun exchange(
        method: String,
        path: String,
        token: String,
        body: Any? = null,
    ): Pair<HttpStatus, JsonNode?> {
        val spec = if (method == "POST") client.post().uri(path).bodyValue(body ?: emptyMap<String, Any>()) else client.get().uri(path)
        val r =
            spec
                .header("Authorization", token)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(r.status.value()) to r.responseBody?.let { runCatching { json.readTree(it) }.getOrNull() }
    }

    private fun created(
        path: String,
        attributes: Map<String, Any?>,
    ): String {
        val (status, body) = exchange("POST", path, creator, mapOf("attributes" to attributes))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        return body!!["id"].asString()
    }

    private fun course(): String {
        val slug = uniqueName("analisis-").lowercase()
        return created("/api/objects/curso/records", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso"))
    }

    private fun brief(
        course: String,
        sources: String = ROOT,
    ) = created("/api/objects/brief/records", SourceAnalysisFixtures.brief(course, sources))

    private fun analyze(
        brief: String,
        token: String = creator,
    ) = exchange("POST", "/api/lms/ai/source-analysis", token, mapOf("briefId" to brief))

    // encola, mueve la cola hasta vaciarla y devuelve el trabajo terminado
    private fun analyzed(brief: String): JsonNode {
        val (status, job) = analyze(brief)
        assertEquals(HttpStatus.ACCEPTED, status, job.toString())
        assertEquals("SOURCE_ANALYSIS", job!!["step"].asString())
        assertEquals("QUEUED", job["status"].asString())
        runBlocking { while (runner.runOnce() > 0) Unit }
        return get("/api/lms/ai/jobs/${job["id"].asString()}", creator)
    }

    private fun runs(course: String) = get("/api/lms/ai/runs?courseId=$course", creator).values().toList()

    @Test
    fun `without keys the deterministic double analyzes a ready brief and the run says DETERMINISTIC, with provenance and hashes`() {
        val course = course()
        val brief = brief(course)

        val job = analyzed(brief)

        assertEquals("DONE", job["status"].asString(), job.toString())
        val run = get("/api/lms/ai/runs/${job["executionId"].asString()}", creator)
        assertEquals("SUCCEEDED", run["status"].asString(), run.toString())
        assertEquals("DETERMINISTIC", run["generator"].asString())
        assertTrue(run["provider"].isNull && run["model"].isNull && run["modelSettings"].isNull, run.toString())
        assertTrue(run["cost"].isNull && run["inputTokens"].isNull, run.toString())
        assertEquals(SourceAnalysisPrompt.VERSION, run["promptVersion"].asString())
        assertEquals(SourceAnalysisPrompt.TEMPLATE, run["templateVersion"].asString())
        assertEquals(brief, run["inputs"]["briefId"].asString())
        assertEquals(listOf(ROOT), run["inputs"]["sources"].values().map { it.asString() })
        // cada archivo leído, con el SHA-256 de su texto
        assertEquals(listOf(ROOT, CHAPTER), run["sources"].values().map { it["ref"].asString() })
        assertTrue(run["sources"].values().all { Regex("[0-9a-f]{64}").matches(it["sha256"].asString()) }, run["sources"].toString())

        val output = run["output"]
        assertEquals(listOf("Publicar un anuncio"), output["procedures"].values().map { it["name"].asString() })
        assertEquals(PUBLISH, output["procedures"][0]["source"].asString())
        assertEquals(
            listOf("Abrir el panel de anuncios.", "Escribir el texto.", "Pulsar Publicar."),
            output["procedures"][0]["steps"].values().map { it.asString() },
        )
        assertEquals(listOf(WITHDRAW), output["concepts"].values().map { it["source"].asString() })
        assertEquals(0, run["validations"]["discarded"].size(), run["validations"].toString())
        assertEquals(1, run["validations"]["procedures"].asInt())
        assertEquals("DETERMINISTIC", runs(course).single()["generator"].asString())
    }

    // RF-IA-02: la salida del doble que no pasa la comprobación (ninguna sección de la que citar) no es un borrador
    @Test
    fun `an output that does not pass the check is INVALID_OUTPUT, without output and without a draft`() {
        val course = course()
        val brief = brief(course, NO_SECTIONS)

        val job = analyzed(brief)

        assertEquals("FAILED", job["status"].asString(), job.toString())
        assertEquals(1, job["attempts"].asInt(), "una salida inválida no se reintenta")
        assertTrue(job["executionId"].isNull, job.toString())
        assertTrue(job["error"].asString().contains("not valid"), job.toString())
        val run = runs(course).single()
        assertEquals("INVALID_OUTPUT", run["status"].asString())
        val detail = get("/api/lms/ai/runs/${run["id"].asString()}", creator)
        assertTrue(detail["output"].isNull, detail.toString())
        assertEquals(0, detail["validations"]["concepts"].asInt() + detail["validations"]["procedures"].asInt())
    }

    @Test
    fun `a source that does not exist fails for good before any execution`() {
        val course = course()
        val brief = brief(course, MISSING)

        val job = analyzed(brief)

        assertEquals("FAILED", job["status"].asString(), job.toString())
        assertEquals(1, job["attempts"].asInt())
        assertTrue(job["error"].asString().contains("could not be read"), job.toString())
        assertTrue(runs(course).isEmpty(), "sin llamada, sin ejecución")
    }

    @Test
    fun `asking again for the same analysis reuses its execution`() {
        val course = course()
        val brief = brief(course)
        val first = analyzed(brief)

        val again = analyzed(brief)

        assertEquals("DONE", again["status"].asString())
        assertEquals(first["executionId"].asString(), again["executionId"].asString())
        assertEquals(1, runs(course).size)
    }

    @Test
    fun `only a ready brief the caller sees is analyzed, and only by who may generate`() {
        val course = course()
        val draft = created("/api/objects/brief/records", SourceAnalysisFixtures.brief(course) + ("estado" to "BORRADOR"))
        assertEquals(HttpStatus.CONFLICT, analyze(draft).first)
        assertEquals(HttpStatus.NOT_FOUND, analyze("00000000-0000-0000-0000-00000000dead").first)
        assertEquals(HttpStatus.BAD_REQUEST, exchange("POST", "/api/lms/ai/source-analysis", creator, emptyMap<String, Any>()).first)
        assertEquals(HttpStatus.FORBIDDEN, analyze(brief(course), student).first)
        assertTrue(runs(course).isEmpty())
    }
}
