package elearning.authoring

import elearning.learning.ClassroomApiTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.util.UUID

// las diferencias con la versión anterior (ADR-0018 §3, docs/api/lms.md): por orden de unidad y lección, sin guardarse.
// las ven el autor en su estudio y quien la revisa en su bandeja; nadie más
class VersionDiffApiTest : ClassroomApiTestSupport() {
    private fun account(vararg roles: String): String {
        val email = "${uniqueName("dif")}@demo.wasichai-learning.test"
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

    private fun courseId(code: String) = get("/api/objects/curso/records?codigo=$code", admin)["content"][0]["id"].asString()

    private fun records(
        obj: String,
        field: String,
        value: String,
    ): List<JsonNode> = get("/api/objects/$obj/records?$field=$value&size=200", admin)["content"].values().toList()

    private fun draft(
        author: String,
        fixture: Course,
    ): String {
        val (status, body) = call(author, "/api/lms/studio/courses/${courseId(fixture.offering)}/versions", mapOf("fromVersionId" to null))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        return body!!["id"].asString()
    }

    private fun patch(
        token: String,
        obj: String,
        id: String,
        attributes: Map<String, Any?>,
    ) {
        val status =
            client
                .patch()
                .uri("/api/objects/$obj/records/$id")
                .header("Authorization", token)
                .bodyValue(mapOf("attributes" to attributes))
                .exchange()
                .returnResult(String::class.java)
                .status
        assertEquals(200, status.value())
    }

    private fun create(
        token: String,
        obj: String,
        attributes: Map<String, Any?>,
    ): String {
        val (status, body) = call(token, "/api/objects/$obj/records", mapOf("attributes" to attributes))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        return body!!["id"].asString()
    }

    private fun delete(
        token: String,
        obj: String,
        id: String,
    ) {
        val status =
            client
                .delete()
                .uri("/api/objects/$obj/records/$id")
                .header("Authorization", token)
                .exchange()
                .returnResult(String::class.java)
                .status
        assertTrue(status.is2xxSuccessful, status.toString())
    }

    private fun JsonNode.changes() = values().map { it["order"].asInt() to it["change"].asString() }

    @Test
    fun `a fresh copy is the same as the version it comes from`() {
        val author = account("CREADOR")
        val id = draft(author, course())

        val diff = ok(call(author, "/api/lms/studio/versions/$id/diff"))

        assertEquals(1, diff["baseVersion"].asInt())
        val unit = diff["units"].single()
        assertEquals("SAME", unit["change"].asString())
        assertEquals("Unidad 1", unit["title"].asString())
        assertEquals("Unidad 1", unit["previousTitle"].asString())
        assertEquals(listOf(1 to "SAME", 2 to "SAME", 3 to "SAME"), unit["lessons"].changes())
    }

    @Test
    fun `the author and the reviewer see what changed against the previous version`() {
        val fixture = course()
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val id = draft(author, fixture)
        val unit = records("unidad", "version_curso", id).single()["id"].asString()
        val lessons = records("leccion", "unidad", unit).associateBy { it["attributes"]["orden"].asInt() }
        patch(author, "leccion", lessons.getValue(1)["id"].asString(), mapOf("titulo" to "Lectura guiada", "resumen" to "Qué se lee"))
        delete(author, "leccion", lessons.getValue(2)["id"].asString())
        val added = create(author, "unidad", mapOf("version_curso" to id, "orden" to 2, "titulo" to "Práctica"))
        create(author, "leccion", mapOf("unidad" to added, "orden" to 1, "titulo" to "Ejercicio", "tipo" to "LECTURA"))

        val diff = ok(call(author, "/api/lms/studio/versions/$id/diff"))

        assertEquals(1, diff["baseVersion"].asInt())
        assertEquals(listOf(1 to "CHANGED", 2 to "ADDED"), diff["units"].changes())
        val first = diff["units"][0]["lessons"]
        assertEquals(listOf(1 to "CHANGED", 2 to "REMOVED", 3 to "SAME"), first.changes())
        assertEquals("Lectura guiada", first[0]["title"].asString())
        assertEquals("Lección 1", first[0]["previousTitle"].asString())
        assertEquals(listOf("resumen", "titulo"), first[0]["fields"].strings())
        assertTrue(first[1]["title"].isNull)
        assertEquals("Lección 2", first[1]["previousTitle"].asString())
        val second = diff["units"][1]
        assertEquals("Práctica", second["title"].asString())
        assertTrue(second["previousTitle"].isNull)
        assertEquals(listOf(1 to "ADDED"), second["lessons"].changes())

        ok(call(author, "/api/lms/studio/versions/$id/submit", emptyMap()))
        assertEquals(diff, ok(call(reviewer, "/api/lms/review/versions/$id/diff")))
    }

    @Test
    fun `changing the linked resources changes the lesson`() {
        val fixture = course()
        val author = account("CREADOR")
        val id = draft(author, fixture)
        val unit = records("unidad", "version_curso", id).single()["id"].asString()
        val reading = records("leccion", "unidad", unit).single { it["attributes"]["orden"].asInt() == 1 }["id"].asString()
        delete(author, "recurso_leccion", records("recurso_leccion", "leccion", reading).single()["id"].asString())

        val lesson = ok(call(author, "/api/lms/studio/versions/$id/diff"))["units"][0]["lessons"][0]

        assertEquals("CHANGED", lesson["change"].asString())
        assertEquals(listOf("recursos"), lesson["fields"].strings())
    }

    @Test
    fun `nobody else sees the differences of someone's version`() {
        val fixture = course()
        val author = account("CREADOR", "REVISOR")
        val reviewer = account("REVISOR")
        val other = account("REVISOR")
        val id = draft(author, fixture)
        ok(call(author, "/api/lms/studio/versions/$id/submit", emptyMap()))
        patch(admin, "version_curso", id, mapOf("revisor_id" to userId(reviewer)))

        assertEquals(HttpStatus.OK, call(reviewer, "/api/lms/review/versions/$id/diff").first)
        // otra persona del estudio, o quien no es el revisor asignado, no la encuentra
        assertEquals(HttpStatus.NOT_FOUND, call(account("CREADOR"), "/api/lms/studio/versions/$id/diff").first)
        assertEquals(HttpStatus.NOT_FOUND, call(other, "/api/lms/review/versions/$id/diff").first)
        assertEquals(HttpStatus.NOT_FOUND, call(reviewer, "/api/lms/studio/versions/$id/diff").first)
        // la autora no se revisa a sí misma; quien no aprueba no tiene bandeja
        assertEquals(HttpStatus.FORBIDDEN, call(author, "/api/lms/review/versions/$id/diff").first)
        assertEquals(HttpStatus.FORBIDDEN, call(newStudent(), "/api/lms/review/versions/$id/diff").first)
        assertEquals(HttpStatus.NOT_FOUND, call(newStudent(), "/api/lms/studio/versions/$id/diff").first)
        // otra versión, que no existe
        assertEquals(HttpStatus.NOT_FOUND, call(author, "/api/lms/studio/versions/${UUID.randomUUID()}/diff").first)
        assertEquals(HttpStatus.NOT_FOUND, call(reviewer, "/api/lms/review/versions/${UUID.randomUUID()}/diff").first)
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, "/api/lms/studio/versions/$id/diff").first)
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, "/api/lms/review/versions/$id/diff").first)
    }
}
