package elearning.authoring

import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordRequest
import java.util.UUID

// la bandeja del revisor (ADR-0018 §2, docs/api/lms.md): revisor asignado, comentarios, decisiones con motivo y publicar
class ReviewApiTest : ClassroomApiTestSupport() {
    private fun account(vararg roles: String): String {
        val email = "${uniqueName("rev")}@demo.wasichai-learning.test"
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

    // un borrador nuevo del autor, copiado de la versión publicada de un curso propio, y enviado a revisión
    private fun inReview(author: String): String {
        val (status, body) = call(author, "/api/lms/studio/courses/${courseId(course().offering)}/versions", mapOf("fromVersionId" to null))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        val id = body!!["id"].asString()
        assertEquals("EN_REVISION", ok(call(author, "/api/lms/studio/versions/$id/submit", emptyMap()))["status"].asString())
        return id
    }

    private fun patch(
        token: String,
        id: String,
        attributes: Map<String, Any?>,
    ): HttpStatus =
        HttpStatus.valueOf(
            client
                .patch()
                .uri("/api/objects/version_curso/records/$id")
                .header("Authorization", token)
                .bodyValue(mapOf("attributes" to attributes))
                .exchange()
                .returnResult(String::class.java)
                .status
                .value(),
        )

    private fun decide(
        token: String,
        id: String,
        decision: String?,
        reason: String? = null,
    ) = call(token, "/api/lms/review/versions/$id/decision", mapOf("decision" to decision, "reason" to reason))

    private fun comment(
        token: String,
        id: String,
        section: String? = "unidad:1/leccion:1",
        text: String? = "Falta la bibliografía",
    ) = call(token, "/api/lms/review/versions/$id/comments", mapOf("section" to section, "text" to text))

    private fun inbox(token: String): List<String> = ok(call(token, "/api/lms/review/versions")).strings { it["id"] }

    private fun created(result: Pair<HttpStatus, JsonNode?>): JsonNode {
        assertEquals(HttpStatus.CREATED, result.first, result.second.toString())
        return result.second!!
    }

    @Test
    fun `the inbox holds what is in review and mine to review, and the assigned reviewer alone sees an assigned one`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val other = account("REVISOR")
        val open = inReview(author)
        val assigned = inReview(author)

        assertEquals(HttpStatus.OK, patch(admin, assigned, mapOf("revisor_id" to userId(reviewer))))

        assertTrue(open in inbox(reviewer) && assigned in inbox(reviewer))
        assertTrue(open in inbox(other))
        assertFalse(assigned in inbox(other))
        assertEquals(userId(reviewer), ok(call(reviewer, "/api/lms/review/versions/$assigned"))["reviewerId"].asString())
        assertEquals(HttpStatus.NOT_FOUND, call(other, "/api/lms/review/versions/$assigned").first)
        assertEquals(HttpStatus.NOT_FOUND, call(reviewer, "/api/lms/review/versions/${UUID.randomUUID()}").first)
        // quien no aprueba no tiene bandeja
        assertEquals(HttpStatus.FORBIDDEN, call(author, "/api/lms/review/versions").first)
        assertEquals(HttpStatus.FORBIDDEN, call(newStudent(), "/api/lms/review/versions").first)
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, "/api/lms/review/versions").first)
    }

    @Test
    fun `only the publisher assigns the reviewer, never to the author nor to themselves`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val id = inReview(author)

        assertEquals(HttpStatus.FORBIDDEN, patch(author, id, mapOf("revisor_id" to userId(reviewer))))
        assertEquals(HttpStatus.FORBIDDEN, patch(reviewer, id, mapOf("revisor_id" to userId(reviewer))))
        assertEquals(HttpStatus.BAD_REQUEST, patch(admin, id, mapOf("revisor_id" to userId(author))))
        // quien asigna no se asigna a sí mismo
        assertEquals(HttpStatus.FORBIDDEN, patch(admin, id, mapOf("revisor_id" to userId(admin))))
        assertEquals(HttpStatus.OK, patch(admin, id, mapOf("revisor_id" to userId(reviewer))))
    }

    @Test
    fun `the author does not review their own version, and another reviewer does not decide an assigned one`() {
        // la autora también es revisora: tiene APROBAR y aun así no decide lo suyo
        val author = account("CREADOR", "REVISOR")
        val reviewer = account("REVISOR")
        val other = account("REVISOR")
        val id = inReview(author)

        assertFalse(id in inbox(author))
        assertEquals(HttpStatus.FORBIDDEN, call(author, "/api/lms/review/versions/$id").first)
        assertEquals(HttpStatus.FORBIDDEN, comment(author, id).first)
        assertEquals(HttpStatus.FORBIDDEN, decide(author, id, "APROBAR").first)
        assertEquals(HttpStatus.FORBIDDEN, decide(author, id, "RECHAZAR", "No").first)
        assertEquals(HttpStatus.FORBIDDEN, patch(author, id, mapOf("estado" to "RETIRADA")))

        assertEquals(HttpStatus.OK, patch(admin, id, mapOf("revisor_id" to userId(reviewer))))
        assertEquals(HttpStatus.NOT_FOUND, comment(other, id).first)
        assertEquals(HttpStatus.NOT_FOUND, decide(other, id, "APROBAR").first)
        // tampoco por la API de registros: la guarda exige al revisor asignado
        assertEquals(HttpStatus.FORBIDDEN, patch(other, id, mapOf("estado" to "APROBADA")))
        assertEquals(HttpStatus.FORBIDDEN, patch(other, id, mapOf("estado" to "BORRADOR")))
        assertEquals(HttpStatus.FORBIDDEN, patch(admin, id, mapOf("estado" to "APROBADA")))
        assertEquals("EN_REVISION", ok(call(reviewer, "/api/lms/review/versions/$id"))["status"].asString())

        assertEquals("APROBADA", ok(decide(reviewer, id, "APROBAR"))["status"].asString())
    }

    @Test
    fun `a reviewer comments by section, the author reads it, and the reviewer resolves it`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val id = inReview(author)

        assertEquals(HttpStatus.BAD_REQUEST, comment(reviewer, id, section = " ").first)
        assertEquals(HttpStatus.BAD_REQUEST, comment(reviewer, id, text = null).first)
        val note = created(comment(reviewer, id))
        assertEquals("unidad:1/leccion:1", note["section"].asString())
        assertEquals(userId(reviewer), note["authorId"].asString())
        assertFalse(note["resolved"].asBoolean())

        val seen = ok(call(author, "/api/lms/studio/versions/$id"))
        assertEquals(1, seen["openComments"].asInt())
        assertEquals("Falta la bibliografía", seen["comments"].single()["text"].asString())
        // el comentario solo lo escribe la bandeja: la API de registros no lo crea
        val direct =
            call(
                reviewer,
                "/api/objects/comentario_revision/records",
                mapOf("attributes" to mapOf("version_curso" to id, "seccion" to "x", "texto" to "y", "autor_id" to userId(reviewer))),
            )
        assertFalse(direct.first.is2xxSuccessful, direct.second.toString())
        assertEquals(HttpStatus.FORBIDDEN, call(author, "/api/lms/review/comments/${note["id"].asString()}/resolve", emptyMap()).first)

        assertTrue(ok(call(reviewer, "/api/lms/review/comments/${note["id"].asString()}/resolve", emptyMap()))["resolved"].asBoolean())
        assertEquals(0, ok(call(author, "/api/lms/studio/versions/$id"))["openComments"].asInt())
        assertEquals(HttpStatus.NOT_FOUND, call(reviewer, "/api/lms/review/comments/${UUID.randomUUID()}/resolve", emptyMap()).first)
    }

    @Test
    fun `decisions need a reason unless approving, stay with their version, and only a version in review is decided`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val id = inReview(author)
        created(comment(reviewer, id))

        assertEquals(HttpStatus.BAD_REQUEST, decide(reviewer, id, "PEDIR_CAMBIOS").first)
        assertEquals(HttpStatus.BAD_REQUEST, decide(reviewer, id, "RECHAZAR", "  ").first)
        assertEquals(HttpStatus.BAD_REQUEST, decide(reviewer, id, "PUBLICAR", "no").first)
        assertEquals(HttpStatus.BAD_REQUEST, decide(reviewer, id, null).first)

        assertEquals("BORRADOR", ok(decide(reviewer, id, "PEDIR_CAMBIOS", "Añade la bibliografía"))["status"].asString())
        assertEquals(HttpStatus.CONFLICT, decide(reviewer, id, "APROBAR").first)
        assertEquals(HttpStatus.CONFLICT, comment(reviewer, id).first)
        ok(call(author, "/api/lms/studio/versions/$id/submit", emptyMap()))
        assertEquals("APROBADA", ok(decide(reviewer, id, "APROBAR"))["status"].asString())

        val detail = ok(call(author, "/api/lms/studio/versions/$id"))
        val decisions = detail["decisions"]
        assertEquals(listOf("PEDIR_CAMBIOS", "APROBAR"), decisions.strings { it["decision"] })
        assertEquals("Añade la bibliografía", decisions[0]["reason"].asString())
        assertTrue(decisions[1]["reason"].isNull)
        assertEquals(userId(reviewer), decisions[0]["authorId"].asString())
        assertEquals(1, detail["comments"].size())

        // una versión nueva a partir de esta empieza sin comentarios ni decisiones: aquellos siguen con la suya
        val course = detail["course"]["id"].asString()
        val next = created(call(author, "/api/lms/studio/courses/$course/versions", mapOf("fromVersionId" to id)))["id"].asString()
        val fresh = ok(call(author, "/api/lms/studio/versions/$next"))
        assertEquals(0, fresh["comments"].size())
        assertEquals(0, fresh["decisions"].size())
        assertEquals(2, ok(call(author, "/api/lms/studio/versions/$id"))["decisions"].size())
    }

    @Test
    fun `rejecting retires the version with its reason`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val id = inReview(author)

        assertEquals("RETIRADA", ok(decide(reviewer, id, "RECHAZAR", "Fuera del alcance del programa"))["status"].asString())

        val decision = ok(call(author, "/api/lms/studio/versions/$id"))["decisions"].single()
        assertEquals("RECHAZAR", decision["decision"].asString())
        assertEquals("Fuera del alcance del programa", decision["reason"].asString())
        assertEquals(HttpStatus.CONFLICT, decide(reviewer, id, "APROBAR").first)
    }

    @Test
    fun `publishing needs PUBLICAR and an approved version`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val id = inReview(author)

        assertEquals(HttpStatus.CONFLICT, call(admin, "/api/lms/review/versions/$id/publish", emptyMap()).first)
        ok(decide(reviewer, id, "APROBAR"))
        assertEquals(HttpStatus.FORBIDDEN, call(reviewer, "/api/lms/review/versions/$id/publish", emptyMap()).first)
        assertEquals(HttpStatus.FORBIDDEN, call(author, "/api/lms/review/versions/$id/publish", emptyMap()).first)
        try {
            val published = ok(call(admin, "/api/lms/review/versions/$id/publish", emptyMap()))
            assertEquals("PUBLICADA", published["status"].asString())
            assertFalse(get("/api/objects/version_curso/records/$id", admin)["attributes"]["publicada_en"].isNull)
        } finally {
            // fuera del catálogo de las demás pruebas
            runBlocking {
                records.asPlatform(ORG) { records.patch("version_curso", UUID.fromString(id), RecordRequest(mapOf("estado" to "RETIRADA")), null, null) }
            }
        }
    }
}
