package elearning.course

import elearning.LmsApiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

// RF-CAT-08 (ADR-0028) por la API de registros de Wasichai: una versión solo declara claves del vocabulario
// conocimiento_previo, y una clave ya declarada no cambia ni se borra (la nombran versiones publicadas)
class PrerequisiteKnowledgeGuardApiTest : LmsApiTest() {
    private val creator by lazy { demoUser("creador") }
    private val author by lazy { get("/api/auth/me", creator)["userId"].asString() }

    private fun send(
        method: String,
        path: String,
        attributes: Map<String, Any?>?,
        token: String = creator,
    ): Pair<HttpStatus, String?> {
        val spec =
            when (method) {
                "POST" ->
                    client
                        .post()
                        .uri(path)
                        .header("Authorization", token)
                        .bodyValue(mapOf("attributes" to attributes))
                "PATCH" ->
                    client
                        .patch()
                        .uri(path)
                        .header("Authorization", token)
                        .bodyValue(mapOf("attributes" to attributes))
                else ->
                    client
                        .delete()
                        .uri(path)
                        .header("Authorization", token)
            }
        val result = spec.exchange().expectBody(String::class.java).returnResult()
        return HttpStatus.valueOf(result.status.value()) to result.responseBody
    }

    private fun draft(keys: String?): Pair<HttpStatus, String?> {
        val slug = uniqueName("conocimientos-").lowercase()
        val (created, course) = send("POST", "/api/objects/curso/records", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso"))
        assertEquals(HttpStatus.CREATED, created, course)
        val attributes =
            mapOf(
                "curso" to json.readTree(course)["id"].asString(),
                "numero" to 1,
                "modalidad" to "ASINCRONA",
                "semanas" to 2,
                "autor_id" to author,
                "conocimientos_previos_claves" to keys,
            )
        return send("POST", "/api/objects/version_curso/records", attributes)
    }

    @Test
    fun `a version declares only keys of the vocabulary, one per line`() {
        assertEquals(HttpStatus.CREATED, draft(null).first)
        assertEquals(HttpStatus.CREATED, draft("programacion-basica\n\n json-y-api-rest \n").first)

        val (unknown, body) = draft("programacion-basica\nastrologia")
        assertEquals(HttpStatus.BAD_REQUEST, unknown)
        assertTrue(body!!.contains("astrologia"), body)
        assertEquals(HttpStatus.BAD_REQUEST, draft("Programación básica").first)
        assertEquals(HttpStatus.BAD_REQUEST, draft("none").first)
    }

    @Test
    fun `editing a draft checks the keys it sends`() {
        val (created, body) = draft(null)
        assertEquals(HttpStatus.CREATED, created, body)
        val path = "/api/objects/version_curso/records/${json.readTree(body)["id"].asString()}"

        assertEquals(HttpStatus.BAD_REQUEST, send("PATCH", path, mapOf("conocimientos_previos_claves" to "no-declarada")).first)
        assertEquals(HttpStatus.OK, send("PATCH", path, mapOf("conocimientos_previos_claves" to "navegador-web")).first)
    }

    @Test
    fun `the vocabulary is written by an administrator, and a key once declared neither changes nor goes away`() {
        val admin = bearer()
        val key = uniqueName("clave-").lowercase()
        val vocabulary = "/api/objects/conocimiento_previo/records"
        assertEquals(HttpStatus.FORBIDDEN, send("POST", vocabulary, mapOf("clave" to key, "nombre" to "X")).first)
        assertEquals(HttpStatus.BAD_REQUEST, send("POST", vocabulary, mapOf("clave" to "Con Espacios", "nombre" to "X"), admin).first)
        assertEquals(HttpStatus.BAD_REQUEST, send("POST", vocabulary, mapOf("clave" to "none", "nombre" to "X"), admin).first)

        val (created, body) = send("POST", vocabulary, mapOf("clave" to key, "nombre" to "Uno"), admin)
        assertEquals(HttpStatus.CREATED, created, body)
        val path = "/api/objects/conocimiento_previo/records/${json.readTree(body)["id"].asString()}"

        assertEquals(HttpStatus.OK, send("PATCH", path, mapOf("nombre" to "Otro nombre"), admin).first, "el nombre sí se corrige")
        assertEquals(HttpStatus.OK, send("PATCH", path, mapOf("clave" to key, "nombre" to "Otro nombre"), admin).first, "reenviar la misma clave no la cambia")
        assertEquals(HttpStatus.CONFLICT, send("PATCH", path, mapOf("clave" to "$key-2"), admin).first)
        assertEquals(HttpStatus.CONFLICT, send("DELETE", path, null, admin).first)
    }
}
