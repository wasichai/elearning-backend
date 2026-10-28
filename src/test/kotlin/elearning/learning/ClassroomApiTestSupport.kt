package elearning.learning

import elearning.LmsApiTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.web.reactive.function.BodyInserters
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

// lo común de las pruebas del aula: un curso propio con una lectura (con su recurso HTML publicado), un vídeo y una
// evaluación —la demo no tiene vídeos—, puesto por la plataforma como lo haría la carga de la demo y retirado al terminar
// para no aparecer en el catálogo de otras pruebas; estudiantes nuevos, matrícula y eventos por la API
abstract class ClassroomApiTestSupport : LmsApiTest() {
    @Autowired
    protected lateinit var records: RecordService

    protected val admin by lazy { bearer() }
    private val published = mutableListOf<String>()

    protected class Course(
        val offering: String,
        val unit: String,
        val reading: String,
        val video: String,
        val assessment: String,
        val resource: String,
    )

    @AfterEach
    fun retireCourses() =
        runBlocking {
            records.asPlatform(ORG) {
                published.forEach { records.patch("version_curso", UUID.fromString(it), RecordRequest(mapOf("estado" to "RETIRADA")), null, null) }
            }
            published.clear()
        }

    // startsIn: días desde hoy hasta el inicio de la edición, para ordenar "mis cursos"
    protected fun course(startsIn: Long = 0): Course {
        val resource = publishedHtml()
        return runBlocking {
            records.asPlatform(ORG) {
                val code = uniqueName("PRG-").uppercase()
                val course =
                    records.create(
                        "curso",
                        RecordRequest(
                            mapOf(
                                "codigo" to code,
                                "slug" to code.lowercase(),
                                "titulo" to "Curso de progreso",
                                "idioma" to "es",
                                "nivel" to "INICIAL",
                            ),
                        ),
                    )
                val version =
                    records.create(
                        "version_curso",
                        RecordRequest(
                            mapOf(
                                "curso" to course.id,
                                "numero" to 1,
                                "estado" to "BORRADOR",
                                "modalidad" to "ASINCRONA",
                                "semanas" to 1,
                                "sistema_credito" to "NINGUNO",
                                "contenido_ilustrativo" to true,
                            ),
                        ),
                    )
                val unit = records.create("unidad", RecordRequest(mapOf("version_curso" to version.id, "orden" to 1, "titulo" to "Unidad 1")))

                suspend fun lesson(
                    order: Int,
                    type: String,
                ) = records.create("leccion", RecordRequest(mapOf("unidad" to unit.id, "orden" to order, "titulo" to "Lección $order", "tipo" to type)))
                val reading = lesson(1, "LECTURA")
                val video = lesson(2, "VIDEO")
                val assessment = lesson(3, "EVALUACION")
                records.create("recurso_leccion", RecordRequest(mapOf("leccion" to reading.id, "version_recurso" to resource, "orden" to 1)))
                records.patch("version_curso", UUID.fromString(version.id), RecordRequest(mapOf("estado" to "PUBLICADA")), null, null)
                published += version.id
                val today = LocalDate.now(ZoneId.of("America/Lima"))
                records.create(
                    "edicion_curso",
                    RecordRequest(
                        mapOf(
                            "codigo" to code,
                            "version_curso" to version.id,
                            "estado" to "INSCRIPCION_ABIERTA",
                            "fecha_inicio" to today.plusDays(startsIn).toString(),
                            "fecha_fin" to today.plusDays(startsIn + 30).toString(),
                            "zona_horaria" to "America/Lima",
                            "inscripcion_desde" to today.minusDays(1).toString(),
                            "inscripcion_hasta" to today.plusDays(5).toString(),
                        ),
                    ),
                )
                Course(code, unit.id, reading.id, video.id, assessment.id, resource)
            }
        }
    }

    // una versión de recurso HTML con su archivo, publicada: la sube su autor (el administrador) y la publica la plataforma
    private fun publishedHtml(): String {
        val author = get("/api/auth/me", admin)["userId"].asString()
        val version =
            runBlocking {
                records.asPlatform(ORG) {
                    val resource = records.create("recurso", RecordRequest(mapOf("titulo" to "Lectura de la unidad", "tipo" to "HTML", "idioma" to "es")))
                    records.create("version_recurso", RecordRequest(mapOf("recurso" to resource.id, "numero" to 1, "autor_id" to author))).id
                }
            }
        val parts = MultipartBodyBuilder()
        parts
            .part(
                "file",
                object : ByteArrayResource("<h1>Lectura</h1>".toByteArray()) {
                    override fun getFilename() = "lectura.html"
                },
            ).contentType(MediaType.TEXT_HTML)
        client
            .post()
            .uri("/api/objects/version_recurso/records/$version/files/archivo")
            .header("Authorization", admin)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus()
            .isOk
        runBlocking {
            records.asPlatform(ORG) { records.patch("version_recurso", UUID.fromString(version), RecordRequest(mapOf("estado" to "PUBLICADA")), null, null) }
        }
        return version
    }

    protected fun newStudent(): String {
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

    protected fun userId(token: String): String = get("/api/auth/me", token)["userId"].asString()

    // una persona DOCENTE nueva, sin ediciones hasta que se le asigne una
    protected fun newTeacher(): String {
        val email = "${uniqueName("doc")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Docente", "password" to DEMO_PASSWORD, "roles" to listOf("DOCENTE")))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    // la asignación que da al docente el alcance sobre la edición (ADR-0016): su perfil y su cuenta
    protected fun assign(
        teacher: String,
        course: Course,
    ) {
        val account = userId(teacher)
        val offering = get("/api/objects/edicion_curso/records?codigo=${course.offering}", admin)["content"][0]["id"].asString()
        val profile =
            call(
                admin,
                "/api/objects/perfil_docente/records",
                mapOf("attributes" to mapOf("nombre_publico" to "Docente", "usuario_id" to account, "perfil_publico" to false)),
            ).second!!["id"]
                .asString()
        val (status, body) =
            call(
                admin,
                "/api/objects/docente_edicion/records",
                mapOf("attributes" to mapOf("edicion_curso" to offering, "perfil_docente" to profile, "usuario_id" to account)),
            )
        assertEquals(HttpStatus.CREATED, status, body.toString())
    }

    protected fun call(
        token: String?,
        path: String,
        body: Map<String, Any?>? = null,
    ): Pair<HttpStatus, JsonNode?> {
        val spec = if (body == null) client.get().uri(path) else client.post().uri(path).bodyValue(body)
        val r = (if (token == null) spec else spec.header("Authorization", token)).exchange().expectBody(String::class.java).returnResult()
        return HttpStatus.valueOf(r.status.value()) to r.responseBody?.let { runCatching { json.readTree(it) }.getOrNull() }
    }

    protected fun enroll(
        token: String,
        course: Course,
    ): JsonNode {
        val (status, body) = call(token, "/api/lms/enrollments", mapOf("offering" to course.offering))
        assertTrue(status.is2xxSuccessful, body.toString())
        return body!!
    }

    protected fun event(
        token: String?,
        enrollment: String,
        lesson: String,
        type: String,
        clientEventId: String = UUID.randomUUID().toString(),
    ) = call(token, "/api/lms/classroom/enrollments/$enrollment/lessons/$lesson/events", mapOf("type" to type, "clientEventId" to clientEventId))

    protected fun ok(result: Pair<HttpStatus, JsonNode?>): JsonNode {
        assertEquals(HttpStatus.OK, result.first, result.second.toString())
        return result.second!!
    }

    companion object {
        val ORG: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
