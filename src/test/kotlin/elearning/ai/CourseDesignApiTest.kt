package elearning.ai

import elearning.LmsApiTest
import elearning.ai.SourceAnalysisFixtures.CHAPTER
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
import java.math.BigDecimal

// el diseño pedagógico por la API sin claves (E33.5, RF-IA-02, RF-IA-08): del brief LISTO al análisis y de su ejecución
// SUCCEEDED al diseño, con el doble determinista, sin red. La carga y los créditos salen de workload con las horas del brief;
// la ejecución guarda las fuentes del análisis y las versiones de instrucciones y plantilla. Las mismas propiedades que
// SourceAnalysisApiTest, para compartir contexto. La cola se mueve a mano
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
class CourseDesignApiTest : LmsApiTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var db: DatabaseClient

    private val creator by lazy { demoUser("creador") }
    private val student by lazy { demoUser("estudiante") }

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

    private fun exchange(
        path: String,
        token: String,
        body: Any,
    ): Pair<HttpStatus, JsonNode?> {
        val r =
            client
                .post()
                .uri(path)
                .bodyValue(body)
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
        val (status, body) = exchange(path, creator, mapOf("attributes" to attributes))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        return body!!["id"].asString()
    }

    private fun course(): String {
        val slug = uniqueName("diseno-").lowercase()
        return created("/api/objects/curso/records", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso"))
    }

    private fun drain() = runBlocking { while (runner.runOnce() > 0) Unit }

    // el análisis del brief, ya terminado
    private fun analysis(
        course: String,
        sources: String = ROOT,
    ): JsonNode {
        val brief = created("/api/objects/brief/records", SourceAnalysisFixtures.brief(course, sources))
        val (status, job) = exchange("/api/lms/ai/source-analysis", creator, mapOf("briefId" to brief))
        assertEquals(HttpStatus.ACCEPTED, status, job.toString())
        drain()
        return get("/api/lms/ai/jobs/${job!!["id"].asString()}", creator)
    }

    private fun design(
        execution: String,
        token: String = creator,
    ) = exchange("/api/lms/ai/course-design", token, mapOf("executionId" to execution))

    private fun designed(execution: String): JsonNode {
        val (status, job) = design(execution)
        assertEquals(HttpStatus.ACCEPTED, status, job.toString())
        assertEquals("COURSE_DESIGN", job!!["step"].asString())
        assertEquals("QUEUED", job["status"].asString())
        drain()
        return get("/api/lms/ai/jobs/${job["id"].asString()}", creator)
    }

    private fun runs(course: String) = get("/api/lms/ai/runs?courseId=$course", creator).values().toList()

    @Test
    fun `without keys the deterministic double designs from a succeeded analysis, with hours and credits from workload`() {
        val course = course()
        val analysis = analysis(course)
        val analysisId = analysis["executionId"].asString()

        val job = designed(analysisId)

        assertEquals("DONE", job["status"].asString(), job.toString())
        val run = get("/api/lms/ai/runs/${job["executionId"].asString()}", creator)
        assertEquals("SUCCEEDED", run["status"].asString(), run.toString())
        assertEquals("COURSE_DESIGN", run["step"].asString())
        assertEquals("DETERMINISTIC", run["generator"].asString())
        assertTrue(run["provider"].isNull && run["model"].isNull && run["modelSettings"].isNull, run.toString())
        assertEquals(CourseDesignPrompt.VERSION, run["promptVersion"].asString())
        assertEquals(CourseDesignPrompt.TEMPLATE, run["templateVersion"].asString())
        assertEquals(analysisId, run["inputs"]["analysisExecutionId"].asString())
        assertEquals(0, BigDecimal(6).compareTo(run["inputs"]["hours"].decimalValue()))
        // las fuentes son las del análisis, con su hash
        val analysisRun = get("/api/lms/ai/runs/$analysisId", creator)
        assertEquals(analysisRun["sources"], run["sources"])
        assertEquals(listOf(ROOT, CHAPTER), run["sources"].values().map { it["ref"].asString() })

        val design = run["output"]["design"]
        assertEquals(listOf("O1", "O2"), design["outcomes"].values().map { it["id"].asString() })
        assertEquals(listOf("Retirar un anuncio", "Publicar un anuncio"), design["units"].values().map { it["title"].asString() })
        assertEquals(listOf(WITHDRAW, PUBLISH), design["units"].values().map { it["readings"][0]["source"].asString() })
        assertTrue(design["assessments"].values().all { it["outcomes"].size() > 0 }, design.toString())
        val workload = run["output"]["workload"]
        assertEquals(2, workload["weeks"].asInt())
        assertEquals(0, BigDecimal(6).compareTo(workload["totalHours"].decimalValue()), workload.toString())
        assertEquals(listOf("ECTS", "SCH", "PE_CREDITO"), workload["credits"].values().map { it["system"].asString() })
        assertTrue(workload["credits"].values().all { it["status"].asString() == "PROPUESTO" }, workload.toString())
        assertEquals(0, run["validations"]["problems"].size(), run["validations"].toString())
        assertEquals(2, runs(course).size)
    }

    @Test
    fun `asking again for the same design reuses its execution`() {
        val course = course()
        val analysisId = analysis(course)["executionId"].asString()
        val first = designed(analysisId)

        val again = designed(analysisId)

        assertEquals("DONE", again["status"].asString())
        assertEquals(first["executionId"].asString(), again["executionId"].asString())
        assertEquals(2, runs(course).size)
    }

    @Test
    fun `only a succeeded analysis the caller sees is designed, and only by who may generate`() {
        val course = course()
        // el análisis de una fuente sin secciones queda INVALID_OUTPUT
        analysis(course, NO_SECTIONS)
        val invalid = runs(course).single { it["step"].asString() == "SOURCE_ANALYSIS" }["id"].asString()
        assertEquals(HttpStatus.CONFLICT, design(invalid).first)
        assertEquals(HttpStatus.NOT_FOUND, design("00000000-0000-0000-0000-00000000dead").first)
        assertEquals(HttpStatus.BAD_REQUEST, exchange("/api/lms/ai/course-design", creator, emptyMap<String, Any>()).first)
        val good = analysis(course)["executionId"].asString()
        assertEquals(HttpStatus.FORBIDDEN, design(good, student).first)
        assertTrue(runs(course).none { it["step"].asString() == "COURSE_DESIGN" })
    }

    @Test
    fun `a design run is not an analysis to design from`() {
        val course = course()
        val designRun = designed(analysis(course)["executionId"].asString())["executionId"].asString()

        assertEquals(HttpStatus.NOT_FOUND, design(designRun).first)
    }
}
