package elearning.authoring

import elearning.learning.ClassroomApiTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.UUID

// el estudio del creador (ADR-0018, docs/api/lms.md): sus versiones, su detalle y enviarlas a revisión
class StudioReadApiTest : ClassroomApiTestSupport() {
    private val creator by lazy { demoUser("creador") }

    private fun newCreator(): String {
        val email = "${uniqueName("cre")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Creadora", "password" to DEMO_PASSWORD, "roles" to listOf("CREADOR")))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    private fun courseId(code: String) = get("/api/objects/curso/records?codigo=$code", admin)["content"][0]["id"].asString()

    private fun draft(
        token: String,
        code: String,
    ) = call(token, "/api/lms/studio/courses/${courseId(code)}/versions", mapOf("fromVersionId" to null)).second!!["id"].asString()

    @Test
    fun `my versions list only mine, and the detail shows the curriculum`() {
        val fixture = course(withEvaluation = true)
        val author = newCreator()
        val id = draft(author, fixture.offering)
        draft(newCreator(), course().offering)

        val mine = ok(call(author, "/api/lms/studio/versions"))
        val row = mine.single()
        assertEquals(id, row["id"].asString())
        assertEquals(2, row["number"].asInt())
        assertEquals("BORRADOR", row["status"].asString())
        assertEquals(fixture.offering, row["course"]["code"].asString())
        assertEquals(userId(author), row["authorId"].asString())
        assertTrue(row["reviewerId"].isNull)
        assertEquals(0, row["openComments"].asInt())
        assertFalse(row["updatedAt"].isNull)

        val detail = ok(call(author, "/api/lms/studio/versions/$id"))
        val lessons = detail["units"].single()["lessons"]
        assertEquals(listOf("LECTURA", "VIDEO", "EVALUACION"), lessons.values().map { it["type"].asString() })
        assertEquals(1, lessons[0]["resources"].asInt())
        assertFalse(lessons[0]["hasAssessment"].asBoolean())
        assertTrue(lessons[2]["hasAssessment"].asBoolean())
        assertEquals(0, detail["comments"].size())
        assertEquals(0, detail["decisions"].size())
    }

    @Test
    fun `the author submits a draft, and only a draft`() {
        val fixture = course()
        val author = newCreator()
        val id = draft(author, fixture.offering)

        assertEquals("EN_REVISION", ok(call(author, "/api/lms/studio/versions/$id/submit", emptyMap()))["status"].asString())

        assertEquals(HttpStatus.CONFLICT, call(author, "/api/lms/studio/versions/$id/submit", emptyMap()).first)
    }

    @Test
    fun `nobody else reads or submits someone's version here`() {
        val fixture = course()
        val id = draft(newCreator(), fixture.offering)
        val student = newStudent()

        for (token in listOf(newCreator(), student, creator)) {
            assertEquals(HttpStatus.NOT_FOUND, call(token, "/api/lms/studio/versions/$id").first)
            assertEquals(HttpStatus.NOT_FOUND, call(token, "/api/lms/studio/versions/$id/submit", emptyMap()).first)
        }
        assertEquals(HttpStatus.NOT_FOUND, call(creator, "/api/lms/studio/versions/${UUID.randomUUID()}").first)
        assertEquals(0, ok(call(student, "/api/lms/studio/versions")).size())
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, "/api/lms/studio/versions").first)
    }
}
