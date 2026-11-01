package elearning.media

import elearning.LmsApiTest
import elearning.jobs.JobRunner
import elearning.media.asciidoctor.AsciidoctorProperties
import elearning.media.asciidoctor.AsciidoctorRenderer
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import org.springframework.web.reactive.function.BodyInserters
import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// generación de artefactos desde una fuente (ADR-0018 §7, RF-CON-03/07). En CI no hay Asciidoctor: el generador es un doble
// que devuelve un HTML y un PDF mínimos, salvo cuando la prueba lo cambia por el adaptador real sin binario. La cola se
// mueve a mano con runOnce
@Import(RenderApiTest.FakeRenderer::class)
@TestPropertySource(properties = ["lms.jobs.scheduling=false", "lms.jobs.max-attempts=1"])
class RenderApiTest : LmsApiTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var db: DatabaseClient

    private val admin by lazy { bearer() }
    private val creator by lazy { demoUser("creador") }
    private val reviewer by lazy { demoUser("revisor") }
    private val student by lazy { demoUser("estudiante") }

    @AfterEach
    fun binaryBack() = missingBinary.set(false)

    private fun exchange(
        method: String,
        path: String,
        token: String,
        body: Any? = null,
    ): Pair<HttpStatus, JsonNode?> {
        val spec =
            when (method) {
                "POST" -> if (body == null) client.post().uri(path) else client.post().uri(path).bodyValue(body)
                "PATCH" -> client.patch().uri(path).bodyValue(body!!)
                "PUT" -> client.put().uri(path).bodyValue(body!!)
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

    private fun create(
        token: String,
        obj: String,
        attributes: Map<String, Any?>,
    ): String {
        val (status, body) = exchange("POST", "/api/objects/$obj/records", token, mapOf("attributes" to attributes))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        return body!!["id"].asString()
    }

    private fun me(token: String) = get("/api/auth/me", token)["userId"].asString()

    // la fuente es de solo inserción: el archivo se sube primero y su id va en el alta
    private fun source(
        type: String,
        text: String = SOURCE_TEXT,
        owner: String = creator,
    ): Pair<String, String> {
        val resource = create(owner, "recurso", mapOf("titulo" to "Generado $type", "tipo" to type, "idioma" to "es"))
        val parts = MultipartBodyBuilder()
        parts
            .part(
                "file",
                object : ByteArrayResource(text.toByteArray()) {
                    override fun getFilename() = "leccion.adoc"
                },
            ).contentType(MediaType.TEXT_PLAIN)
        val staged =
            client
                .post()
                .uri("/api/objects/fuente_recurso/files/archivo")
                .header("Authorization", owner)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val file = json.readTree(staged)["id"].asString()
        return resource to create(owner, "fuente_recurso", mapOf("recurso" to resource, "numero" to 1, "archivo" to file))
    }

    private fun render(
        source: String,
        token: String = creator,
    ): JsonNode {
        val (status, body) = exchange("POST", "/api/lms/studio/sources/$source/render", token)
        assertEquals(HttpStatus.ACCEPTED, status, body.toString())
        assertEquals("PENDIENTE", body!!["status"].asString())
        return body
    }

    private fun drain() =
        runBlocking {
            while (runner.runOnce() > 0) Unit
        }

    private fun download(
        path: String,
        token: String,
    ): ByteArray =
        client
            .get()
            .uri(path)
            .header("Authorization", token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(ByteArray::class.java)
            .returnResult()
            .responseBody!!

    @Test
    fun `a source becomes a new draft version with its html and pdf artifacts, each naming its exact source`() {
        val (resource, source) = source("HTML")
        val author = me(creator)
        create(creator, "version_recurso", mapOf("recurso" to resource, "numero" to 1, "autor_id" to author))

        val job = render(source)["id"].asString()
        drain()

        val (status, done) = exchange("GET", "/api/lms/studio/renders/$job", creator)
        assertEquals(HttpStatus.OK, status)
        assertEquals("HECHO", done!!["status"].asString(), done.toString())
        assertEquals(2, done["versionNumber"].asInt(), "una versión nueva, después de la que había")
        val version = get("/api/objects/version_recurso/records/${done["versionId"].asString()}", creator)["attributes"]
        assertEquals("BORRADOR", version["estado"].asString())
        assertEquals(author, version["autor_id"].asString(), "en nombre de quien la pidió")

        val artifacts = get("/api/objects/artefacto_generado/records?version_recurso=${done["versionId"].asString()}&size=10", creator)["content"].values()
        assertEquals(setOf("HTML", "PDF"), artifacts.map { it["attributes"]["formato"].asString() }.toSet())
        assertEquals(done["artifacts"].values().map { it["id"].asString() }.toSet(), artifacts.map { it["id"].asString() }.toSet())
        for (artifact in artifacts) {
            val a = artifact["attributes"]
            assertEquals(source, a["fuente_recurso"].let { if (it.isObject) it["id"] else it }.asString())
            assertEquals(sha256(SOURCE_TEXT.toByteArray()), a["hash_fuente"].asString())
            assertEquals(FAKE, a["generador"].asString())
            assertEquals("$FAKE 1.0", a["version_generador"].asString())
            assertEquals(job, a["trabajo_id"].asString())
            val bytes = download("/api/objects/artefacto_generado/records/${artifact["id"].asString()}/files/archivo", creator)
            assertEquals(sha256(bytes), a["sha256"].asString(), "el hash es el de lo que se descarga")
            assertEquals(a["sha256"].asString(), a["archivo"]["sha256"].asString())
        }
        val html = artifacts.first { it["attributes"]["formato"].asString() == "HTML" }
        assertEquals(html["attributes"]["sha256"].asString(), version["archivo"]["sha256"].asString(), "el archivo de la versión es el HTML")
        assertTrue(String(download("/api/objects/version_recurso/records/${done["versionId"].asString()}/files/archivo", creator)).contains("Texto propio"))
    }

    @Test
    fun `a pdf resource gets the pdf as its version file`() {
        val (_, source) = source("PDF")

        val job = render(source)["id"].asString()
        drain()

        val done = get("/api/lms/studio/renders/$job", creator)
        assertEquals("HECHO", done["status"].asString(), done.toString())
        assertEquals(1, done["versionNumber"].asInt())
        val file = get("/api/objects/version_recurso/records/${done["versionId"].asString()}", creator)["attributes"]["archivo"]
        assertEquals("application/pdf", file["contentType"].asString())
    }

    @Test
    fun `rendering again makes another version and never overwrites`() {
        val (_, source) = source("HTML")

        val first = render(source)["id"].asString()
        drain()
        val second = render(source)["id"].asString()
        drain()

        val a = get("/api/lms/studio/renders/$first", creator)
        val b = get("/api/lms/studio/renders/$second", creator)
        assertEquals(listOf(1, 2), listOf(a["versionNumber"].asInt(), b["versionNumber"].asInt()))
        assertTrue(a["versionId"].asString() != b["versionId"].asString())
    }

    // M2 de #216: pedir otra vez mientras espera no hace otro trabajo ni otra versión
    @Test
    fun `asking again while the render waits returns the same job`() {
        val (resource, source) = source("HTML")

        val first = render(source)["id"].asString()
        val again = render(source)["id"].asString()
        drain()

        assertEquals(first, again)
        assertEquals(1, get("/api/objects/version_recurso/records?recurso=$resource", creator)["totalElements"].asInt())
    }

    // L7 de #216: quien pidió la generación tiene que poder todavía crear la versión cuando el trabajo corre
    @Test
    fun `a render whose requester lost the permission before it ran fails without a version`() {
        val person = account("CREADOR")
        val personId = me(person)
        val (resource, source) = source("HTML", owner = person)
        val job = render(source, person)["id"].asString()
        val (status, body) = exchange("PUT", "/api/users/$personId/roles", admin, mapOf("roles" to listOf("ESTUDIANTE")))
        assertEquals(HttpStatus.OK, status, body.toString())

        drain()

        val (state, error) = stateOf(job)
        assertEquals("FAILED", state, error)
        assertTrue(error.contains("no longer"), error)
        assertEquals(0, get("/api/objects/version_recurso/records?recurso=$resource", admin)["totalElements"].asInt())
    }

    // el estado y el error del trabajo, leídos de la cola: quien lo pidió ya no puede leerlo
    private fun stateOf(id: String): Pair<String, String> =
        runBlocking {
            db
                .sql("SELECT estado, error FROM lms.trabajo WHERE id = :id")
                .bind("id", UUID.fromString(id))
                .map { row, _ -> row.get("estado", String::class.java)!! to (row.get("error", String::class.java) ?: "") }
                .one()
                .awaitSingle()
        }

    @Test
    fun `without the asciidoctor binary the job fails with a clear error and writes nothing`() {
        val (resource, source) = source("HTML")
        missingBinary.set(true)

        val job = render(source)["id"].asString()
        drain()

        val failed = get("/api/lms/studio/renders/$job", creator)
        assertEquals("FALLIDO", failed["status"].asString(), failed.toString())
        val error = failed["error"].asString()
        assertTrue(error.contains("not available") && error.contains("deployment image must include Asciidoctor"), error)
        assertTrue(failed["versionId"].isNull || failed["versionId"].isMissingNode)
        assertEquals(0, get("/api/objects/version_recurso/records?recurso=$resource", creator)["totalElements"].asInt())
    }

    @Test
    fun `only someone who may create resource versions asks for a render or reads it`() {
        val (_, source) = source("HTML")
        val job = render(source)["id"].asString()

        for (token in listOf(reviewer, student)) {
            assertEquals(HttpStatus.FORBIDDEN, exchange("POST", "/api/lms/studio/sources/$source/render", token).first)
            assertEquals(HttpStatus.FORBIDDEN, exchange("GET", "/api/lms/studio/renders/$job", token).first)
        }
        assertEquals(HttpStatus.NOT_FOUND, exchange("POST", "/api/lms/studio/sources/${UUID.randomUUID()}/render", creator).first)
        assertEquals(HttpStatus.NOT_FOUND, exchange("GET", "/api/lms/studio/renders/${UUID.randomUUID()}", creator).first)
        assertEquals(HttpStatus.ACCEPTED, exchange("POST", "/api/lms/studio/sources/$source/render", admin).first)
        drain()
    }

    // un trabajo ajeno no existe, como en la importación: su error y su versión son de quien lo pidió (revisión de la Fase 4)
    @Test
    fun `nobody else reads a render job, not another creator nor the administrator`() {
        val (_, source) = source("HTML")
        val job = render(source)["id"].asString()
        val otherCreator = account("CREADOR")

        for (token in listOf(otherCreator, admin)) {
            val (status, body) = exchange("GET", "/api/lms/studio/renders/$job", token)
            assertEquals(HttpStatus.NOT_FOUND, status, body.toString())
        }
        assertEquals(HttpStatus.OK, exchange("GET", "/api/lms/studio/renders/$job", creator).first)

        val (adminStatus, adminJob) = exchange("POST", "/api/lms/studio/sources/$source/render", admin)
        assertEquals(HttpStatus.ACCEPTED, adminStatus)
        assertEquals(HttpStatus.NOT_FOUND, exchange("GET", "/api/lms/studio/renders/${adminJob!!["id"].asString()}", creator).first)
        assertEquals(HttpStatus.OK, exchange("GET", "/api/lms/studio/renders/${adminJob["id"].asString()}", admin).first)
        drain()
    }

    private fun account(vararg roles: String): String {
        val email = "${uniqueName("render")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Persona", "password" to DEMO_PASSWORD, "roles" to roles.toList()))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    @Test
    fun `only html and pdf resources are generated`() {
        val (_, source) = source("VIDEO")

        assertEquals(HttpStatus.CONFLICT, exchange("POST", "/api/lms/studio/sources/$source/render", creator).first)
    }

    // la fuente y los artefactos no se reescriben: la fuente es de solo inserción y el artefacto solo lo escribe el trabajo
    @Test
    fun `sources and artifacts are never overwritten through the record api`() {
        val (_, source) = source("HTML")
        val job = render(source)["id"].asString()
        drain()
        val done = get("/api/lms/studio/renders/$job", creator)
        val artifact = done["artifacts"][0]["id"].asString()

        val (sourceUpdate, _) = exchange("PATCH", "/api/objects/fuente_recurso/records/$source", admin, mapOf("attributes" to mapOf("numero" to 7)))
        assertEquals(HttpStatus.CONFLICT, sourceUpdate)
        val (artifactUpdate, _) =
            exchange("PATCH", "/api/objects/artefacto_generado/records/$artifact", admin, mapOf("attributes" to mapOf("generador" to "otro")))
        assertTrue(artifactUpdate in setOf(HttpStatus.FORBIDDEN, HttpStatus.CONFLICT), artifactUpdate.toString())
        val (artifactCreate, _) =
            exchange(
                "POST",
                "/api/objects/artefacto_generado/records",
                admin,
                mapOf("attributes" to mapOf("version_recurso" to done["versionId"].asString(), "fuente_recurso" to source, "formato" to "HTML")),
            )
        assertEquals(HttpStatus.FORBIDDEN, artifactCreate)
    }

    // un texto que el estudiante nunca ve: ninguna fuente ni artefacto se lee sin ser del personal
    @Test
    fun `students do not read sources nor artifacts`() {
        assertEquals(HttpStatus.FORBIDDEN, exchange("GET", "/api/objects/fuente_recurso/records", student).first)
        assertEquals(HttpStatus.FORBIDDEN, exchange("GET", "/api/objects/artefacto_generado/records", student).first)
    }

    // el doble del generador: HTML y PDF mínimos con la fuente dentro, o el adaptador real sin binario
    @TestConfiguration
    class FakeRenderer {
        @Bean
        @Primary
        fun fakeDocumentRenderer(): DocumentRenderer {
            val missing = AsciidoctorRenderer(AsciidoctorProperties(html = "/nonexistent/asciidoctor", pdf = "/nonexistent/asciidoctor-pdf"))
            return object : DocumentRenderer {
                override fun render(
                    source: ByteArray,
                    format: ArtifactFormat,
                ): RenderedDocument {
                    if (missingBinary.get()) return missing.render(source, format)
                    val text = String(source)
                    val bytes =
                        when (format) {
                            ArtifactFormat.HTML -> "<html><body><pre>$text</pre></body></html>"
                            ArtifactFormat.PDF -> "%PDF-1.7\n% $text\n%%EOF\n"
                        }.toByteArray()
                    return RenderedDocument(format, bytes, FAKE, "$FAKE 1.0")
                }
            }
        }
    }

    companion object {
        private const val FAKE = "fake-renderer"
        private const val SOURCE_TEXT = "= Lección generada\n\nTexto propio de la prueba."
        private val missingBinary = AtomicBoolean(false)

        private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
