package elearning.authoring

import elearning.learning.ClassroomApiTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// #258: el estudio y la bandeja dicen quién escribió, quién revisa y quién decidió con el nombre para mostrar, junto al id
// y nunca con el correo. una persona deshabilitada no tiene nombre (null); su id sigue
class AuthoringNamesApiTest : ClassroomApiTestSupport() {
    private data class Person(
        val token: String,
        val id: String,
        val name: String,
        val email: String,
    )

    private fun person(role: String): Person {
        val email = "${uniqueName("nom")}@demo.wasichai-learning.test"
        val name = "Persona ${uniqueName("n")}"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to name, "password" to DEMO_PASSWORD, "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        val token = bearer(email, DEMO_PASSWORD)
        return Person(token, userId(token), name, email)
    }

    private fun courseId(code: String) = get("/api/objects/curso/records?codigo=$code", admin)["content"][0]["id"].asString()

    // un borrador del autor, en revisión y asignado al revisor
    private fun inReview(
        author: Person,
        reviewer: Person,
    ): String {
        val (status, body) = call(author.token, "/api/lms/studio/courses/${courseId(course().offering)}/versions", mapOf("fromVersionId" to null))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        val id = body!!["id"].asString()
        ok(call(author.token, "/api/lms/studio/versions/$id/submit", emptyMap()))
        val assigned =
            client
                .patch()
                .uri("/api/objects/version_curso/records/$id")
                .header("Authorization", admin)
                .bodyValue(mapOf("attributes" to mapOf("revisor_id" to reviewer.id)))
                .exchange()
                .returnResult(String::class.java)
                .status
                .value()
        assertEquals(200, assigned)
        return id
    }

    private fun comment(
        reviewer: Person,
        id: String,
    ): JsonNode {
        val (status, body) =
            call(reviewer.token, "/api/lms/review/versions/$id/comments", mapOf("section" to "unidad:1", "text" to "Falta la bibliografía"))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        return body!!
    }

    private fun named(
        node: JsonNode,
        person: Person,
        idField: String,
        nameField: String,
    ) {
        assertEquals(person.id, node[idField].asString(), node.toString())
        assertEquals(person.name, node[nameField].asString(), node.toString())
    }

    // ni el correo ni otro dato de la persona: solo su id y su nombre
    private fun noEmail(
        node: JsonNode,
        vararg people: Person,
    ) {
        for (p in people) assertFalse(node.toString().contains(p.email, ignoreCase = true), node.toString())
    }

    @Test
    fun `the studio and the inbox name the author, the reviewer and who comments and decides, never by email`() {
        val author = person("CREADOR")
        val reviewer = person("REVISOR")
        val id = inReview(author, reviewer)

        val note = comment(reviewer, id)
        named(note, reviewer, "authorId", "authorName")
        val resolved = ok(call(reviewer.token, "/api/lms/review/comments/${note["id"].asString()}/resolve", emptyMap()))
        named(resolved, reviewer, "authorId", "authorName")

        val row = ok(call(reviewer.token, "/api/lms/review/versions")).values().single { it["id"].asString() == id }
        named(row, author, "authorId", "authorName")
        named(row, reviewer, "reviewerId", "reviewerName")
        noEmail(row, author, reviewer)

        val decided = ok(call(reviewer.token, "/api/lms/review/versions/$id/decision", mapOf("decision" to "PEDIR_CAMBIOS", "reason" to "Cita las fuentes")))
        named(decided, author, "authorId", "authorName")
        named(decided, reviewer, "reviewerId", "reviewerName")

        val detail = ok(call(author.token, "/api/lms/studio/versions/$id"))
        named(detail, author, "authorId", "authorName")
        named(detail, reviewer, "reviewerId", "reviewerName")
        named(detail["comments"].single(), reviewer, "authorId", "authorName")
        named(detail["decisions"].single(), reviewer, "authorId", "authorName")
        noEmail(detail, author, reviewer)

        val mine = ok(call(author.token, "/api/lms/studio/versions")).values().single { it["id"].asString() == id }
        named(mine, author, "authorId", "authorName")
        named(mine, reviewer, "reviewerId", "reviewerName")
        noEmail(mine, author, reviewer)

        // quien publica lee la bandeja con los mismos nombres
        val published = ok(call(admin, "/api/lms/review/versions/$id"))
        named(published["decisions"].single(), reviewer, "authorId", "authorName")
        noEmail(published, author, reviewer)
    }

    @Test
    fun `a disabled person keeps the id and loses the name`() {
        val author = person("CREADOR")
        val reviewer = person("REVISOR")
        val id = inReview(author, reviewer)
        comment(reviewer, id)
        ok(call(reviewer.token, "/api/lms/review/versions/$id/decision", mapOf("decision" to "RECHAZAR", "reason" to "Fuera del programa")))

        assertEquals(HttpStatus.OK, put(admin, "/api/users/${reviewer.id}", mapOf("enabled" to false)).first)

        val detail = ok(call(author.token, "/api/lms/studio/versions/$id"))
        named(detail, author, "authorId", "authorName")
        assertEquals(reviewer.id, detail["reviewerId"].asString())
        assertTrue(detail["reviewerName"].isNull, detail.toString())
        assertEquals(reviewer.id, detail["comments"].single()["authorId"].asString())
        assertTrue(detail["comments"].single()["authorName"].isNull, detail.toString())
        assertEquals(reviewer.id, detail["decisions"].single()["authorId"].asString())
        assertTrue(detail["decisions"].single()["authorName"].isNull, detail.toString())
    }

    @Test
    fun `whoever cannot read the version does not get the names either`() {
        val author = person("CREADOR")
        val reviewer = person("REVISOR")
        val other = person("REVISOR")
        val outsider = person("CREADOR")
        val id = inReview(author, reviewer)
        comment(reviewer, id)

        // un creador ajeno, el revisor que no es el asignado y el autor en la bandeja: nada que nombrar
        val hidden =
            listOf(
                call(outsider.token, "/api/lms/studio/versions/$id"),
                call(reviewer.token, "/api/lms/studio/versions/$id"),
                call(other.token, "/api/lms/review/versions/$id"),
            )
        hidden.forEach { assertEquals(HttpStatus.NOT_FOUND, it.first, it.second.toString()) }
        assertEquals(HttpStatus.FORBIDDEN, call(author.token, "/api/lms/review/versions/$id").first)
        assertEquals(HttpStatus.FORBIDDEN, call(newStudent(), "/api/lms/review/versions").first)
        assertFalse(ok(call(other.token, "/api/lms/review/versions")).values().any { it["id"].asString() == id })
        assertFalse(ok(call(outsider.token, "/api/lms/studio/versions")).values().any { it["id"].asString() == id })
    }
}
