package elearning.ai

import elearning.LmsApiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource

// RF-IA-01 (E33.1) por la API de registros de Wasichai: un brief se guarda a medias en borrador y pasa a LISTO solo
// completo; sus fuentes son de la lista permitida del importador o de un paquete ya importado al curso; lo escribe solo
// quien crea versiones del curso
@TestPropertySource(properties = ["lms.importer.allowed-sources=acme/manuales"])
class BriefGuardApiTest : LmsApiTest() {
    private val admin by lazy { bearer() }
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

    private fun id(body: String?) = json.readTree(body)["id"].asString()

    private fun course(): String {
        val slug = uniqueName("brief-").lowercase()
        val (created, body) = send("POST", "/api/objects/curso/records", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso"))
        assertEquals(HttpStatus.CREATED, created, body)
        return id(body)
    }

    private fun complete(course: String): Map<String, Any?> =
        mapOf(
            "curso" to course,
            "tematica" to "Administración de anuncios",
            "publico" to "Personal administrativo",
            "objetivos" to "Publicar un anuncio\nRetirarlo",
            "duracion_horas" to 6,
            "idioma" to "es",
            "nivel" to "INICIAL",
            "expertos" to "Ana Pérez, jefa de comunicación",
            "fuentes" to "acme/manuales@$SHA:docs/anuncios.adoc",
        )

    @Test
    fun `an incomplete brief is kept as a draft and is refused as ready with the fields it lacks`() {
        val (created, body) = send("POST", BRIEFS, mapOf("curso" to course(), "tematica" to "Anuncios"))
        assertEquals(HttpStatus.CREATED, created, body)
        assertEquals("BORRADOR", json.readTree(body)["attributes"]["estado"].asString())
        val path = "$BRIEFS/${id(body)}"

        val (refused, problem) = send("PATCH", path, mapOf("estado" to "LISTO"))
        assertEquals(HttpStatus.BAD_REQUEST, refused, problem)
        for (field in listOf("publico", "objetivos", "duracion_horas", "idioma", "nivel", "expertos", "fuentes")) {
            assertTrue(problem!!.contains(field), "$field: $problem")
        }
        assertTrue(!problem!!.contains("tematica"), problem)

        val (direct, missing) = send("POST", BRIEFS, mapOf("curso" to course(), "estado" to "LISTO", "idioma" to " "))
        assertEquals(HttpStatus.BAD_REQUEST, direct, missing)
        assertTrue(missing!!.contains("tematica") && missing.contains("idioma"), missing)

        val (zero, hours) = send("POST", BRIEFS, complete(course()) + mapOf("duracion_horas" to 0))
        assertEquals(HttpStatus.BAD_REQUEST, zero, hours)
        assertTrue(hours!!.contains("duracion_horas"), hours)
    }

    @Test
    fun `a complete brief is ready, and stays complete while ready`() {
        val (created, body) = send("POST", BRIEFS, complete(course()) + mapOf("estado" to "LISTO"))
        assertEquals(HttpStatus.CREATED, created, body)
        val path = "$BRIEFS/${id(body)}"

        assertEquals(HttpStatus.BAD_REQUEST, send("PATCH", path, mapOf("expertos" to "")).first)
        assertEquals(HttpStatus.OK, send("PATCH", path, mapOf("estado" to "BORRADOR", "expertos" to "")).first)
    }

    @Test
    fun `a source outside the allowed list is refused, also in a draft`() {
        val course = course()
        val (outside, body) = send("POST", BRIEFS, complete(course) + mapOf("fuentes" to "acme/manuales@$SHA:a.adoc\nintruso/repo@$SHA:b.adoc"))
        assertEquals(HttpStatus.BAD_REQUEST, outside, body)
        assertTrue(body!!.contains("intruso/repo"), body)
        assertEquals(HttpStatus.BAD_REQUEST, send("POST", BRIEFS, mapOf("curso" to course, "fuentes" to "https://example.com/manual.adoc")).first)
        assertEquals(HttpStatus.BAD_REQUEST, send("POST", BRIEFS, mapOf("curso" to course, "fuentes" to "acme/manuales@main:a.adoc")).first)

        val (draft, saved) = send("POST", BRIEFS, mapOf("curso" to course))
        assertEquals(HttpStatus.CREATED, draft, saved)
        assertEquals(HttpStatus.BAD_REQUEST, send("PATCH", "$BRIEFS/${id(saved)}", mapOf("fuentes" to "intruso/repo@$SHA:b.adoc")).first)
        assertEquals(HttpStatus.OK, send("PATCH", "$BRIEFS/${id(saved)}", mapOf("fuentes" to " ACME/Manuales@$SHA:b.adoc \n")).first)
    }

    @Test
    fun `a package already imported into the course is an authorized source of its brief, not of another course`() {
        val course = course()
        val imported = "otro/paquete@$SHA"
        val version =
            mapOf("curso" to course, "numero" to 1, "modalidad" to "ASINCRONA", "semanas" to 2, "autor_id" to author, "fuentes" to "$imported:manual.adoc")
        val (created, body) = send("POST", "/api/objects/version_curso/records", version)
        assertEquals(HttpStatus.CREATED, created, body)

        assertEquals(HttpStatus.CREATED, send("POST", BRIEFS, complete(course) + mapOf("fuentes" to "$imported:capitulo.adoc")).first)
        assertEquals(HttpStatus.BAD_REQUEST, send("POST", BRIEFS, complete(course()) + mapOf("fuentes" to "$imported:capitulo.adoc")).first)
        assertEquals(HttpStatus.BAD_REQUEST, send("POST", BRIEFS, complete(course) + mapOf("fuentes" to "otro/paquete@${"b".repeat(40)}:x.adoc")).first)
    }

    @Test
    fun `only who creates course versions writes a brief, never by role name`() {
        val (created, body) = send("POST", BRIEFS, complete(course()))
        assertEquals(HttpStatus.CREATED, created, body)
        val path = "$BRIEFS/${id(body)}"

        val reviewer = demoUser("revisor")
        assertEquals(HttpStatus.FORBIDDEN, send("POST", BRIEFS, complete(course()), reviewer).first)
        assertEquals(HttpStatus.FORBIDDEN, send("PATCH", path, mapOf("tematica" to "Otra"), reviewer).first)

        // un rol al que el administrador le dio el brief pero no crear versiones: la guarda lo frena igual
        val briefOnly = accountWith(mapOf("curso" to listOf("READ"), "brief" to listOf("READ", "CREATE", "UPDATE", "DELETE")))
        assertEquals(HttpStatus.FORBIDDEN, send("POST", BRIEFS, complete(course()), briefOnly).first)
        assertEquals(HttpStatus.FORBIDDEN, send("PATCH", path, mapOf("tematica" to "Otra"), briefOnly).first)
        assertEquals(HttpStatus.FORBIDDEN, send("DELETE", path, null, briefOnly).first)

        assertEquals(HttpStatus.OK, send("PATCH", path, mapOf("tematica" to "Otra")).first)
        assertEquals(HttpStatus.NO_CONTENT, send("DELETE", path, null).first)
    }

    // una cuenta con un rol nuevo que tiene exactamente esos permisos, creada por el administrador
    private fun accountWith(permissions: Map<String, List<String>>): String {
        val role = uniqueName("SOLO_BRIEF_").uppercase().replace('-', '_')
        client
            .post()
            .uri("/api/roles")
            .header("Authorization", admin)
            .bodyValue(mapOf("name" to role, "label" to "Solo brief"))
            .exchange()
            .expectStatus()
            .isCreated
        val entries = permissions.flatMap { (obj, actions) -> actions.map { mapOf("objectName" to obj, "action" to it) } }
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header("Authorization", admin)
            .bodyValue(mapOf("permissions" to entries))
            .exchange()
            .expectStatus()
            .isOk
        val email = "${uniqueName("brief-").lowercase()}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Cuenta de solo brief", "password" to DEMO_PASSWORD, "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    private companion object {
        const val BRIEFS = "/api/objects/brief/records"
        val SHA = "a".repeat(40)
    }
}
