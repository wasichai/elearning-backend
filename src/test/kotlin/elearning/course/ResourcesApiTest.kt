package elearning.course

import elearning.LmsApiTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.web.reactive.function.BodyInserters
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import java.util.UUID

// recursos versionados con su archivo (ADR-0012/0013): el mismo flujo editorial que una versión de curso, lo publicado
// inmutable con su archivo, una lección solo usa versiones publicadas y el archivo solo lo baja quien está matriculado
class ResourcesApiTest : LmsApiTest() {
    @Autowired
    private lateinit var records: RecordService

    private val admin by lazy { bearer() }
    private val creator by lazy { demoUser("creador") }
    private val reviewer by lazy { demoUser("revisor") }

    private fun call(
        method: String,
        path: String,
        token: String,
        body: Map<String, Any?>? = null,
    ): Pair<HttpStatus, JsonNode?> {
        val spec =
            when (method) {
                "POST" -> client.post().uri(path).bodyValue(body!!)
                "PATCH" -> client.patch().uri(path).bodyValue(body!!)
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
        val (status, body) = call("POST", "/api/objects/$obj/records", token, mapOf("attributes" to attributes))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        return body!!["id"].asString()
    }

    private fun patch(
        token: String,
        obj: String,
        id: String,
        attributes: Map<String, Any?>,
    ) = call("PATCH", "/api/objects/$obj/records/$id", token, mapOf("attributes" to attributes)).first

    private fun upload(
        token: String,
        version: String,
        name: String,
        bytes: ByteArray,
    ): HttpStatus {
        val parts = MultipartBodyBuilder()
        parts
            .part(
                "file",
                object : ByteArrayResource(bytes) {
                    override fun getFilename() = name
                },
            ).contentType(MediaType.TEXT_HTML)
        return HttpStatus.valueOf(
            client
                .post()
                .uri("/api/objects/version_recurso/records/$version/files/archivo")
                .header("Authorization", token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .exchange()
                .returnResult(String::class.java)
                .status
                .value(),
        )
    }

    private fun me(token: String) = get("/api/auth/me", token)["userId"].asString()

    // un recurso del creador, publicado por el flujo: él lo envía, el revisor lo aprueba, el administrador lo publica
    private fun publishedResource(html: String = "<h1>Lectura</h1><p>Contenido propio.</p>"): String {
        val resource = create(creator, "recurso", mapOf("titulo" to "Lectura de prueba", "tipo" to "HTML", "idioma" to "es"))
        val version = create(creator, "version_recurso", mapOf("recurso" to resource, "numero" to 1, "autor_id" to me(creator)))
        assertEquals(HttpStatus.OK, upload(creator, version, "lectura.html", html.toByteArray()))
        assertEquals(HttpStatus.OK, patch(creator, "version_recurso", version, mapOf("estado" to "EN_REVISION")))
        assertEquals(HttpStatus.FORBIDDEN, patch(creator, "version_recurso", version, mapOf("estado" to "APROBADA")))
        assertEquals(HttpStatus.OK, patch(reviewer, "version_recurso", version, mapOf("estado" to "APROBADA")))
        assertEquals(HttpStatus.OK, patch(admin, "version_recurso", version, mapOf("estado" to "PUBLICADA")))
        return version
    }

    @Test
    fun `a resource version goes through the editorial flow and is frozen with its file once published`() {
        val version = publishedResource()

        assertEquals(HttpStatus.CONFLICT, patch(admin, "version_recurso", version, mapOf("origen" to "cambiado")))
        assertTrue(upload(admin, version, "otra.html", "<p>otra</p>".toByteArray()) in setOf(HttpStatus.CONFLICT, HttpStatus.FORBIDDEN))
    }

    @Test
    fun `a file over the limit is refused`() {
        val resource = create(creator, "recurso", mapOf("titulo" to "Grande", "tipo" to "PDF", "idioma" to "es"))
        val version = create(creator, "version_recurso", mapOf("recurso" to resource, "numero" to 1, "autor_id" to me(creator)))

        assertEquals(HttpStatus.BAD_REQUEST, upload(creator, version, "grande.pdf", ByteArray(26 * 1024 * 1024)))
    }

    @Test
    fun `a lesson only uses published resource versions, and only while its course version is a draft`() {
        val draftResource = create(creator, "recurso", mapOf("titulo" to "Borrador", "tipo" to "HTML", "idioma" to "es"))
        val draftVersion = create(creator, "version_recurso", mapOf("recurso" to draftResource, "numero" to 1, "autor_id" to me(creator)))
        val published = publishedResource()
        val draftCourseVersion = get("/api/objects/version_curso/records?estado=BORRADOR&size=1", admin)["content"][0]["id"].asString()
        val draftUnit = get("/api/objects/unidad/records?version_curso=$draftCourseVersion&size=1", admin)["content"][0]["id"].asString()
        val draftLesson = get("/api/objects/leccion/records?unidad=$draftUnit&size=1", admin)["content"][0]["id"].asString()
        val publishedLesson =
            get(
                "/api/objects/leccion/records?size=1",
                admin,
            )["content"].values().first { it["id"].asString() != draftLesson }["id"].asString()

        val (unpublished, _) =
            call(
                "POST",
                "/api/objects/recurso_leccion/records",
                admin,
                mapOf("attributes" to mapOf("leccion" to draftLesson, "version_recurso" to draftVersion, "orden" to 1)),
            )
        assertEquals(HttpStatus.CONFLICT, unpublished)
        val (frozen, _) =
            call(
                "POST",
                "/api/objects/recurso_leccion/records",
                admin,
                mapOf("attributes" to mapOf("leccion" to publishedLesson, "version_recurso" to published, "orden" to 9)),
            )
        assertEquals(HttpStatus.CONFLICT, frozen)
        create(admin, "recurso_leccion", mapOf("leccion" to draftLesson, "version_recurso" to published, "orden" to 1))
    }

    @Test
    fun `only an enrolled student reads a lesson's resources and downloads their files`() {
        val enrolled = newStudent()
        val outsider = newStudent()
        val offering = get("/api/objects/edicion_curso/records?codigo=IA-AGT-201-A", admin)["content"][0]
        val courseVersion = offering["attributes"]["version_curso"].asString()
        runBlocking {
            records.asPlatform(ORG) {
                records.create(
                    "matricula",
                    RecordRequest(
                        mapOf(
                            "edicion" to offering["id"].asString(),
                            "version_curso" to courseVersion,
                            "estudiante_id" to me(enrolled),
                        ),
                    ),
                )
            }
        }
        val unit = get("/api/objects/unidad/records?version_curso=$courseVersion&size=1", admin)["content"][0]["id"].asString()
        val lesson = get("/api/objects/leccion/records?unidad=$unit&size=1", admin)["content"][0]["id"].asString()
        val resource = publishedResource("<h1>Solo matriculados</h1>")
        // el temario de una versión publicada no cambia: el enlace lo pone la plataforma, como lo haría la carga de la demo
        runBlocking {
            records.asPlatform(ORG) {
                records.create(
                    "recurso_leccion",
                    RecordRequest(
                        mapOf(
                            "leccion" to lesson,
                            "version_recurso" to resource,
                            "orden" to 1,
                        ),
                    ),
                )
            }
        }
        val file = "/api/objects/version_recurso/records/$resource/files/archivo"

        assertTrue(get("/api/objects/recurso_leccion/records?leccion=$lesson", enrolled)["content"].size() == 1)
        val body =
            client
                .get()
                .uri(
                    file,
                ).header("Authorization", enrolled)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
        assertTrue("Solo matriculados" in body!!)

        assertEquals(0, get("/api/objects/recurso_leccion/records?leccion=$lesson", outsider)["totalElements"].asInt())
        assertEquals(HttpStatus.NOT_FOUND, call("GET", "/api/objects/version_recurso/records/$resource", outsider).first)
        assertEquals(HttpStatus.NOT_FOUND, call("GET", file, outsider).first)
    }

    private fun newStudent(): String {
        val email = "${uniqueName("est")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Estudiante", "password" to DEMO_PASSWORD, "roles" to listOf("ESTUDIANTE")))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    companion object {
        val ORG: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
