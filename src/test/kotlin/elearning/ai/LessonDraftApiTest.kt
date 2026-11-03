package elearning.ai

import elearning.LmsApiTest
import elearning.ai.SourceAnalysisFixtures.CHAPTER
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

// el borrador de lecciones por la API sin claves (E33.6): brief LISTO → análisis → diseño → borrador, con los dobles
// deterministas y sin red. El borrador entra como version_curso BORRADOR a nombre de quien lo pidió, con la plantilla y su
// versión en la ejecución y en la versión; cada lección guarda su fuente AsciiDoc y su HTML saneado (ADR-0022) en un recurso
// BORRADOR. Nada cambia de estado solo, y la versión es «generada» (docs/ai §13): no va a revisión sin informe. La cola se
// mueve a mano
@Import(SourceAnalysisFixtures.FakeSource::class, LessonDraftFixtures.FakeRenderer::class)
@TestPropertySource(
    properties = [
        "ANTHROPIC_API_KEY=",
        "OPENAI_API_KEY=",
        "lms.jobs.scheduling=false",
        "lms.jobs.backoff=0s",
        "lms.importer.allowed-sources=acme/manuales",
    ],
)
class LessonDraftApiTest : LmsApiTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var db: DatabaseClient

    private val creator by lazy { demoUser("creador") }
    private val student by lazy { demoUser("estudiante") }

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
        val slug = uniqueName("borrador-").lowercase()
        return created("/api/objects/curso/records", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso"))
    }

    private fun drain() = runBlocking { while (runner.runOnce() > 0) Unit }

    private fun done(
        path: String,
        body: Any,
    ): JsonNode {
        val (status, job) = exchange(path, creator, body)
        assertEquals(HttpStatus.ACCEPTED, status, job.toString())
        drain()
        return get("/api/lms/ai/jobs/${job!!["id"].asString()}", creator)
    }

    // el diseño del brief del curso, ya terminado: su ejecución
    private fun designed(course: String): String {
        val brief = created("/api/objects/brief/records", SourceAnalysisFixtures.brief(course))
        val analysis = done("/api/lms/ai/source-analysis", mapOf("briefId" to brief))
        val design = done("/api/lms/ai/course-design", mapOf("executionId" to analysis["executionId"].asString()))
        assertEquals("DONE", design["status"].asString(), design.toString())
        return design["executionId"].asString()
    }

    private fun draft(
        execution: String,
        token: String = creator,
    ) = exchange("/api/lms/ai/lesson-draft", token, mapOf("executionId" to execution))

    private fun drafted(execution: String): JsonNode {
        val (status, job) = draft(execution)
        assertEquals(HttpStatus.ACCEPTED, status, job.toString())
        assertEquals("LESSON_DRAFT", job!!["step"].asString())
        drain()
        return get("/api/lms/ai/jobs/${job["id"].asString()}", creator)
    }

    private fun list(
        objectName: String,
        field: String,
        value: String,
    ): List<JsonNode> = get("/api/objects/$objectName/records?$field=$value&size=200", creator)["content"].values().toList()

    private fun download(path: String): String =
        String(
            client
                .get()
                .uri(path)
                .header("Authorization", creator)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(ByteArray::class.java)
                .returnResult()
                .responseBody!!,
        )

    private fun versions(course: String) = list("version_curso", "curso", course)

    @Test
    fun `without keys the double drafts a BORRADOR version for whoever asked, with the template in the run and in the version`() {
        val course = course()
        val design = designed(course)

        val job = drafted(design)

        assertEquals("DONE", job["status"].asString(), job.toString())
        val run = get("/api/lms/ai/runs/${job["executionId"].asString()}", creator)
        assertEquals("SUCCEEDED", run["status"].asString(), run.toString())
        assertEquals("LESSON_DRAFT", run["step"].asString())
        assertEquals("DETERMINISTIC", run["generator"].asString())
        assertEquals(LessonDraftPrompt.VERSION, run["promptVersion"].asString())
        assertEquals(LessonTemplate.VERSION, run["templateVersion"].asString())
        assertEquals(design, run["inputs"]["designExecutionId"].asString())
        // las fuentes del brief, leídas otra vez en el mismo commit
        assertEquals(listOf(ROOT, CHAPTER), run["sources"].values().map { it["ref"].asString() })
        val output = run["output"]
        assertEquals(LessonTemplate.VERSION, output["template"].asString())
        val versionId = output["versionId"].asString()

        // la versión: BORRADOR, de quien lo pidió, con la plantilla y su versión, y nadie la ha movido
        val version = get("/api/objects/version_curso/records/$versionId", creator)["attributes"]
        assertEquals(course, version["curso"].asString())
        assertEquals("BORRADOR", version["estado"].asString())
        assertEquals(get("/api/auth/me", creator)["userId"].asString(), version["autor_id"].asString())
        assertEquals(LessonTemplate.VERSION, version["plantilla_ia"].asString())
        assertEquals(2, version["semanas"].asInt())
        assertTrue(version["fuentes"].asString().contains(CHAPTER), version.toString())
        assertEquals(1, versions(course).size)

        // una unidad por unidad del diseño y una lección por lectura, con su procedencia
        val units = list("unidad", "version_curso", versionId).sortedBy { it["attributes"]["orden"].asInt() }
        assertEquals(listOf("Retirar un anuncio", "Publicar un anuncio"), units.map { it["attributes"]["titulo"].asString() })
        val lessons = units.flatMap { u -> list("leccion", "unidad", u["id"].asString()) }
        assertEquals(listOf(WITHDRAW, PUBLISH), lessons.map { it["attributes"]["referencia_fuente"].asString() })
        assertTrue(lessons.all { it["attributes"]["objetivo"].asString().isNotBlank() })

        // cada lección: su fuente AsciiDoc con la plantilla y su HTML saneado, en un recurso BORRADOR
        val link = list("recurso_leccion", "leccion", lessons[1]["id"].asString()).single()["attributes"]
        val resourceVersionId = link["version_recurso"].asString()
        val resourceVersion = get("/api/objects/version_recurso/records/$resourceVersionId", creator)["attributes"]
        assertEquals("BORRADOR", resourceVersion["estado"].asString())
        assertEquals(PUBLISH, resourceVersion["origen"].asString())
        val html = download("/api/objects/version_recurso/records/$resourceVersionId/files/archivo")
        assertTrue(html.contains(LessonDraftFixtures.MARK) && html.contains("== Objetivo") && html.contains("== Verificación"), html)
        for (unsafe in listOf("<script", "steal()", "onload", "<iframe", "javascript:")) assertFalse(html.contains(unsafe), "$unsafe en $html")
        val resource = resourceVersion["recurso"].asString()
        val source = list("fuente_recurso", "recurso", resource).single()
        val asciidoc = download("/api/objects/fuente_recurso/records/${source["id"].asString()}/files/archivo")
        assertTrue(asciidoc.startsWith("= Publicar un anuncio\n:lang: es\n"), asciidoc)
        assertTrue(asciidoc.contains("Fuente: `$PUBLISH`"), asciidoc)

        // generada (docs/ai §13): sin informe de validación no va a revisión, y el trabajo no la movió
        val (submitted, _) = exchange("/api/lms/studio/versions/$versionId/submit", creator, emptyMap<String, Any>())
        assertEquals(HttpStatus.CONFLICT, submitted)
        assertEquals("BORRADOR", get("/api/objects/version_curso/records/$versionId", creator)["attributes"]["estado"].asString())
        assertEquals(0, run["validations"]["problems"].size(), run["validations"].toString())
        assertEquals(0, run["validations"]["linksToReview"].size(), run["validations"].toString())
    }

    @Test
    fun `asking again for the same draft reuses its execution and makes no second version`() {
        val course = course()
        val design = designed(course)
        val first = drafted(design)

        val again = drafted(design)

        assertEquals("DONE", again["status"].asString())
        assertEquals(first["executionId"].asString(), again["executionId"].asString())
        assertEquals(1, versions(course).size)
    }

    @Test
    fun `only a succeeded design the caller sees is drafted, and only by who may generate`() {
        val course = course()
        val design = designed(course)
        assertEquals(HttpStatus.FORBIDDEN, draft(design, student).first)
        assertEquals(HttpStatus.NOT_FOUND, draft("00000000-0000-0000-0000-00000000dead").first)
        assertEquals(HttpStatus.BAD_REQUEST, exchange("/api/lms/ai/lesson-draft", creator, emptyMap<String, Any>()).first)
        // una ejecución del análisis no es un diseño
        val analysis = get("/api/lms/ai/runs?courseId=$course", creator).values().single { it["step"].asString() == "SOURCE_ANALYSIS" }
        assertEquals(HttpStatus.NOT_FOUND, draft(analysis["id"].asString()).first)
        assertTrue(versions(course).isEmpty())
    }
}
