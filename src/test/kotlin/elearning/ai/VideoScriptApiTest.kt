package elearning.ai

import elearning.LmsApiTest
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

// el guion por secciones por la API sin claves (E36.1, RF-MED-01): el doble determinista escribe el guion entero desde el brief
// LISTO, la comprobación lo valida y se guarda como la versión siguiente del guion de su version_recurso VIDEO en borrador
// (guion_video), con el guion del render de ADR-0040, sus subtítulos WebVTT y su transcripción. Un guion no cambia nunca y
// una versión que salió de borrador, publicada incluida, conserva el suyo. La cola se mueve a mano
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
class VideoScriptApiTest : LmsApiTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var db: DatabaseClient

    private val admin by lazy { bearer() }
    private val creator by lazy { demoUser("creador") }
    private val reviewer by lazy { demoUser("revisor") }
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
        method: String,
        path: String,
        token: String,
        body: Any? = null,
    ): Pair<HttpStatus, JsonNode?> {
        val spec =
            when (method) {
                "POST" -> client.post().uri(path).bodyValue(body ?: emptyMap<String, Any>())
                "PATCH" -> client.patch().uri(path).bodyValue(body ?: emptyMap<String, Any>())
                "DELETE" -> client.delete().uri(path)
                else -> client.get().uri(path)
            }
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
        token: String = creator,
    ): String {
        val (status, body) = exchange("POST", path, token, mapOf("attributes" to attributes))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        return body!!["id"].asString()
    }

    private fun me(token: String) = get("/api/auth/me", token)["userId"].asString()

    private fun course(): String {
        val slug = uniqueName("guion-").lowercase()
        return created("/api/objects/curso/records", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso"))
    }

    private fun brief(
        course: String = course(),
        sources: String = ROOT,
    ) = created("/api/objects/brief/records", SourceAnalysisFixtures.brief(course, sources))

    // una version_recurso en BORRADOR de un recurso de ese tipo, de quien la crea
    private fun resourceVersion(
        type: String = "VIDEO",
        token: String = creator,
    ): String {
        val resource = created("/api/objects/recurso/records", mapOf("titulo" to "Vídeo de anuncios", "tipo" to type, "idioma" to "es"), token)
        return created("/api/objects/version_recurso/records", mapOf("recurso" to resource, "numero" to 1, "autor_id" to me(token)), token)
    }

    private fun write(
        brief: String,
        version: String,
        target: Int? = 120,
        token: String = creator,
    ) = exchange("POST", "/api/lms/ai/scripts", token, mapOf("briefId" to brief, "versionId" to version, "targetSeconds" to target))

    private fun drain() = runBlocking { while (runner.runOnce() > 0) Unit }

    // encola, mueve la cola hasta vaciarla y devuelve el trabajo terminado
    private fun written(
        brief: String,
        version: String,
        target: Int = 120,
    ): JsonNode {
        val (status, job) = write(brief, version, target)
        assertEquals(HttpStatus.ACCEPTED, status, job.toString())
        assertEquals("SCRIPT", job!!["step"].asString())
        drain()
        return get("/api/lms/ai/jobs/${job["id"].asString()}", creator)
    }

    private fun scripts(version: String) = get("/api/objects/guion_video/records?version_recurso=$version&size=50", creator)["content"].values().toList()

    private fun runs(course: String) = get("/api/lms/ai/runs?courseId=$course", creator).values().toList()

    private fun patch(
        token: String,
        version: String,
        state: String,
    ) = exchange("PATCH", "/api/objects/version_recurso/records/$version", token, mapOf("attributes" to mapOf("estado" to state))).first

    @Test
    fun `without keys the double writes a valid script that is stored as the first version of the script of the video`() {
        val course = course()
        val brief = brief(course)
        val version = resourceVersion()

        val job = written(brief, version, target = 120)

        assertEquals("DONE", job["status"].asString(), job.toString())
        val run = get("/api/lms/ai/runs/${job["executionId"].asString()}", creator)
        assertEquals("SUCCEEDED", run["status"].asString(), run.toString())
        assertEquals("SCRIPT", run["step"].asString())
        assertEquals("DETERMINISTIC", run["generator"].asString())
        assertEquals(ScriptPrompt.VERSION, run["promptVersion"].asString())
        assertEquals(version, run["inputs"]["versionId"].asString())
        assertEquals(120, run["inputs"]["targetSeconds"].asInt())
        assertEquals(0, run["validations"]["violations"].size(), run["validations"].toString())
        assertEquals(
            listOf(PUBLISH, WITHDRAW),
            run["output"]["sections"]
                .values()
                .drop(1)
                .dropLast(1)
                .map { it["traces"][0].asString() },
        )

        val script = scripts(version).single()["attributes"]
        assertEquals(1, script["numero"].asInt())
        assertEquals(120, script["duracion_segundos"].asInt())
        assertEquals(job["executionId"].asString(), script["ejecucion_agente_id"].asString())
        assertEquals(job["id"].asString(), script["trabajo_id"].asString())
        val document = json.readTree(script["guion"].asString())
        assertEquals(emptyList<String>(), GuionSchema.violations(document))
        assertEquals(VideoScriptDocuments.sha256(script["guion"].asString()), script["sha256"].asString())
        assertEquals(listOf("portada", "vinetas", "vinetas", "cierre"), document["secciones"].values().map { it["diapositiva"]["tipo"].asString() })
        assertTrue(script["subtitulos_vtt"].asString().startsWith("WEBVTT\n"), script["subtitulos_vtt"].asString())
        assertTrue(script["transcripcion"].asString().contains("Publicar un anuncio"), script["transcripcion"].asString())
    }

    // el guion se guarda como versión: escribir otro es la versión siguiente, y la anterior queda como estaba
    @Test
    fun `writing the script again adds the next version and leaves the previous one as it was`() {
        val brief = brief()
        val version = resourceVersion()
        written(brief, version, target = 120)
        val first = scripts(version).single()

        val again = written(brief, version, target = 90)

        assertEquals("DONE", again["status"].asString(), again.toString())
        val all = scripts(version).sortedBy { it["attributes"]["numero"].asInt() }
        assertEquals(listOf(1, 2), all.map { it["attributes"]["numero"].asInt() })
        assertEquals(first["attributes"]["sha256"].asString(), all.first()["attributes"]["sha256"].asString())
        assertEquals(90, all.last()["attributes"]["duracion_segundos"].asInt())
    }

    // regla 5: un guion no cambia nunca, por nadie, y una versión publicada conserva el suyo
    @Test
    fun `a script never changes and a published video keeps its script`() {
        val brief = brief()
        val version = resourceVersion()
        written(brief, version)
        val script = scripts(version).single()
        val id = script["id"].asString()

        val update = exchange("PATCH", "/api/objects/guion_video/records/$id", admin, mapOf("attributes" to mapOf("transcripcion" to "otra"))).first
        assertTrue(update in setOf(HttpStatus.FORBIDDEN, HttpStatus.CONFLICT), update.toString())
        val delete = exchange("DELETE", "/api/objects/guion_video/records/$id", admin).first
        assertTrue(delete in setOf(HttpStatus.FORBIDDEN, HttpStatus.CONFLICT), delete.toString())
        val forged = mapOf("version_recurso" to version, "numero" to 9, "guion" to "{}", "sha256" to "x", "subtitulos_vtt" to "WEBVTT\n")
        assertEquals(HttpStatus.FORBIDDEN, exchange("POST", "/api/objects/guion_video/records", admin, mapOf("attributes" to forged)).first)
        assertEquals(HttpStatus.FORBIDDEN, exchange("POST", "/api/objects/guion_video/records", creator, mapOf("attributes" to forged)).first)

        assertEquals(HttpStatus.OK, patch(creator, version, "EN_REVISION"))
        assertEquals(HttpStatus.OK, patch(reviewer, version, "APROBADA"))
        assertEquals(HttpStatus.OK, patch(admin, version, "PUBLICADA"))

        assertEquals(HttpStatus.CONFLICT, write(brief, version).first)
        drain()
        val after = scripts(version).single()
        assertEquals(id, after["id"].asString())
        assertEquals(script["attributes"]["sha256"].asString(), after["attributes"]["sha256"].asString())
        assertEquals(script["attributes"]["guion"].asString(), after["attributes"]["guion"].asString())
    }

    // si la versión sale de borrador mientras el trabajo espera, el trabajo falla sin guion: ni la plataforma lo añade
    @Test
    fun `a version sent to review while the job waits gets no script`() {
        val brief = brief()
        val version = resourceVersion()
        val (status, job) = write(brief, version)
        assertEquals(HttpStatus.ACCEPTED, status, job.toString())
        assertEquals(HttpStatus.OK, patch(creator, version, "EN_REVISION"))

        drain()

        val done = get("/api/lms/ai/jobs/${job!!["id"].asString()}", creator)
        assertEquals("FAILED", done["status"].asString(), done.toString())
        assertTrue(done["error"].asString().contains("no longer a draft"), done.toString())
        assertTrue(scripts(version).isEmpty())
    }

    // RF-MED-01: sin secciones de las que trazar, el guion del doble no vale: INVALID_OUTPUT y ningún guion guardado
    @Test
    fun `a script that does not pass the check is INVALID_OUTPUT and is not stored`() {
        val course = course()
        val brief = brief(course, NO_SECTIONS)
        val version = resourceVersion()

        val job = written(brief, version)

        assertEquals("FAILED", job["status"].asString(), job.toString())
        assertEquals(1, job["attempts"].asInt(), "un guion inválido no se reintenta")
        assertTrue(job["executionId"].isNull, job.toString())
        val run = get("/api/lms/ai/runs/${runs(course).single()["id"].asString()}", creator)
        assertEquals("INVALID_OUTPUT", run["status"].asString())
        assertTrue(run["output"].isNull, run.toString())
        assertEquals("NO_SECTIONS", run["validations"]["violations"][0]["code"].asString(), run["validations"].toString())
        assertTrue(scripts(version).isEmpty())
    }

    @Test
    fun `only the author of a video draft writes its script, from a ready brief, within the limits, and only who may generate`() {
        val course = course()
        val brief = brief(course)
        val version = resourceVersion()

        assertEquals(HttpStatus.BAD_REQUEST, write(brief, version, target = 10).first)
        assertEquals(HttpStatus.BAD_REQUEST, write(brief, version, target = 901).first)
        assertEquals(HttpStatus.BAD_REQUEST, exchange("POST", "/api/lms/ai/scripts", creator, mapOf("briefId" to brief)).first)
        assertEquals(
            HttpStatus.BAD_REQUEST,
            exchange("POST", "/api/lms/ai/scripts", creator, mapOf("briefId" to brief, "versionId" to version, "resolution" to "800x600")).first,
        )
        assertEquals(
            HttpStatus.BAD_REQUEST,
            exchange("POST", "/api/lms/ai/scripts", creator, mapOf("briefId" to brief, "versionId" to version, "fps" to 60)).first,
        )
        val draftBrief = created("/api/objects/brief/records", SourceAnalysisFixtures.brief(course) + ("estado" to "BORRADOR"))
        assertEquals(HttpStatus.CONFLICT, write(draftBrief, version).first)
        assertEquals(HttpStatus.CONFLICT, write(brief, resourceVersion(type = "HTML")).first)
        assertEquals(HttpStatus.NOT_FOUND, write(brief, "00000000-0000-0000-0000-00000000dead").first)
        val others = write(brief, resourceVersion(token = admin)).first
        assertTrue(others in setOf(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND), others.toString())
        assertEquals(HttpStatus.FORBIDDEN, write(brief, version, token = student).first)
        assertTrue(runs(course).isEmpty())
        assertTrue(scripts(version).isEmpty())
    }
}
