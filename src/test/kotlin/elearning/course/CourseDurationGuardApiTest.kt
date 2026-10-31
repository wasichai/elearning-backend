package elearning.course

import elearning.LmsApiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

// RF-ACA-05 por la API de registros de Wasichai: la duración y la modalidad de una versión de curso, al crearla y al
// editarla. La regla la hace cumplir CourseDurationGuard; la metadata solo conoce el tipo de cada campo
class CourseDurationGuardApiTest : LmsApiTest() {
    private val creator by lazy { demoUser("creador") }
    private val author by lazy { get("/api/auth/me", creator)["userId"].asString() }

    private fun send(
        method: String,
        path: String,
        attributes: Map<String, Any?>,
    ): Pair<HttpStatus, String?> {
        val spec = if (method == "POST") client.post().uri(path) else client.patch().uri(path)
        val result =
            spec
                .header("Authorization", creator)
                .bodyValue(mapOf("attributes" to attributes))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(result.status.value()) to result.responseBody
    }

    // una versión en borrador de un curso nuevo con esa duración y modalidad: el estado y el cuerpo de la respuesta
    private fun version(
        weeks: Int,
        duration: String? = null,
        modality: String = "ASINCRONA",
    ): Pair<HttpStatus, String?> {
        val slug = uniqueName("duracion-").lowercase()
        val (created, course) = send("POST", "/api/objects/curso/records", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso de duración"))
        assertEquals(HttpStatus.CREATED, created, course)
        val attributes =
            mapOf("curso" to json.readTree(course)["id"].asString(), "numero" to 1, "modalidad" to modality, "semanas" to weeks, "autor_id" to author) +
                (duration?.let { mapOf("duracion" to it) } ?: emptyMap())
        return send("POST", "/api/objects/version_curso/records", attributes)
    }

    @Test
    fun `from 1 to 8 weeks, or a semester, and nothing else`() {
        assertEquals(HttpStatus.BAD_REQUEST, version(0).first)
        assertEquals(HttpStatus.CREATED, version(1).first)
        assertEquals(HttpStatus.CREATED, version(8, "SEMANAS").first)
        assertEquals(HttpStatus.BAD_REQUEST, version(9).first)
        assertEquals(HttpStatus.CREATED, version(16, "SEMESTRAL").first)
        assertEquals(HttpStatus.BAD_REQUEST, version(8, "SEMESTRAL").first)
    }

    @Test
    fun `the rule holds when editing, on what is sent together with what is stored`() {
        val (created, body) = version(2)
        assertEquals(HttpStatus.CREATED, created, body)
        val path = "/api/objects/version_curso/records/${json.readTree(body)["id"].asString()}"

        assertEquals(HttpStatus.BAD_REQUEST, send("PATCH", path, mapOf("semanas" to 12)).first)
        assertEquals(HttpStatus.BAD_REQUEST, send("PATCH", path, mapOf("duracion" to "SEMESTRAL")).first)
        assertEquals(HttpStatus.OK, send("PATCH", path, mapOf("duracion" to "SEMESTRAL", "semanas" to 16)).first)
        assertEquals(HttpStatus.BAD_REQUEST, send("PATCH", path, mapOf("duracion" to "SEMANAS")).first)
    }

    @Test
    fun `an in-person course is a modality like the others, also in the catalog filter`() {
        assertEquals(HttpStatus.CREATED, version(4, modality = "PRESENCIAL").first)
        client
            .get()
            .uri("/api/lms/public/catalog/courses?modality=PRESENCIAL")
            .exchange()
            .expectStatus()
            .isOk
    }
}
