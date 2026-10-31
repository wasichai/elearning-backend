package elearning.authoring

import elearning.learning.ClassroomApiTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.util.UUID

// una versión nueva a partir de otra (ADR-0018 §1, RF-CON-04): un borrador del autor con su propia copia del temario. lo
// publicado, y quien lo cursa, no se tocan
class NewVersionApiTest : ClassroomApiTestSupport() {
    private val creator by lazy { demoUser("creador") }

    private fun courseId(code: String) = get("/api/objects/curso/records?codigo=$code", admin)["content"][0]["id"].asString()

    private fun copy(
        token: String,
        courseId: String,
        from: String? = null,
    ) = call(token, "/api/lms/studio/courses/$courseId/versions", mapOf("fromVersionId" to from))

    private fun records(
        obj: String,
        field: String,
        value: String,
    ): List<JsonNode> = get("/api/objects/$obj/records?$field=$value&size=200", admin)["content"].values().toList()

    private fun versionOf(course: String) = records("version_curso", "curso", course).single()

    @Test
    fun `it copies the curriculum to a draft of the author, with new identities`() {
        val fixture = course(withEvaluation = true)
        val id = courseId(fixture.offering)
        val original = versionOf(id)

        val (status, created) = copy(creator, id)

        assertEquals(HttpStatus.CREATED, status, created.toString())
        val copied = created!!["id"].asString()
        assertNotEquals(original["id"].asString(), copied)
        assertEquals(2, created["number"].asInt())
        assertEquals("BORRADOR", created["status"].asString())
        val attributes = get("/api/objects/version_curso/records/$copied", admin)["attributes"]
        assertEquals(userId(creator), attributes["autor_id"].asString())
        assertTrue(attributes["publicada_en"].isNull)

        val unit = records("unidad", "version_curso", copied).single()
        assertNotEquals(fixture.unit, unit["id"].asString())
        val lessons = records("leccion", "unidad", unit["id"].asString()).sortedBy { it["attributes"]["orden"].asInt() }
        assertEquals(listOf("LECTURA", "VIDEO", "EVALUACION"), lessons.map { it["attributes"]["tipo"].asString() })
        assertEquals(fixture.resource, records("recurso_leccion", "leccion", lessons[0]["id"].asString()).single()["attributes"]["version_recurso"].asString())

        val evaluation = records("evaluacion", "leccion", lessons[2]["id"].asString()).single()
        assertNotEquals(fixture.evaluation, evaluation["id"].asString())
        assertEquals(60, evaluation["attributes"]["nota_minima"].asInt())
        val questions = records("pregunta", "evaluacion", evaluation["id"].asString())
        assertEquals(5, questions.size)
        val single = questions.single { it["attributes"]["tipo"].asString() == "UNICA" }
        assertEquals(2, records("opcion", "pregunta", single["id"].asString()).size)
        val open = questions.single { it["attributes"]["tipo"].asString() == "ABIERTA" }
        assertEquals(2, records("criterio_rubrica", "pregunta", open["id"].asString()).size)
    }

    @Test
    fun `publishing and editing the copy leave the original and its students alone`() {
        val fixture = course()
        val id = courseId(fixture.offering)
        val student = newStudent()
        val enrollment = enroll(student, fixture)["id"].asString()
        ok(event(student, enrollment, fixture.reading, "MARKED_COMPLETE"))
        val copied = copy(creator, id).second!!["id"].asString()
        val copiedLesson =
            records("leccion", "unidad", records("unidad", "version_curso", copied).single()["id"].asString())
                .first {
                    it["attributes"]["tipo"].asString() ==
                        "LECTURA"
                }["id"]
                .asString()

        val edited =
            client
                .patch()
                .uri("/api/objects/leccion/records/$copiedLesson")
                .header("Authorization", creator)
                .bodyValue(mapOf("attributes" to mapOf("titulo" to "Título de la v2")))
                .exchange()
                .returnResult(String::class.java)
                .status
                .value()
        assertEquals(200, edited)

        val view = ok(call(student, "/api/lms/classroom/enrollments/$enrollment"))
        assertEquals(1, view["version"].asInt())
        assertEquals(1, view["progress"]["completedLessons"].asInt())
        assertEquals("Lección 1", view["units"][0]["lessons"][0]["title"].asString())
        assertEquals("Lección 1", get("/api/objects/leccion/records/${fixture.reading}", admin)["attributes"]["titulo"].asString())
    }

    @Test
    fun `the source can be named, must be of that course, and numbers keep growing`() {
        val fixture = course()
        val id = courseId(fixture.offering)
        val first = versionOf(id)["id"].asString()

        assertEquals(2, copy(creator, id, first).second!!["number"].asInt())
        assertEquals(3, copy(creator, id, first).second!!["number"].asInt())

        val other = versionOf(courseId(course().offering))["id"].asString()
        assertEquals(HttpStatus.BAD_REQUEST, copy(creator, id, other).first)
        assertEquals(HttpStatus.BAD_REQUEST, copy(creator, id, UUID.randomUUID().toString()).first)
    }

    @Test
    fun `only who can create versions copies`() {
        val fixture = course()
        val id = courseId(fixture.offering)

        assertEquals(HttpStatus.FORBIDDEN, copy(newStudent(), id).first)
        assertEquals(HttpStatus.FORBIDDEN, copy(demoUser("revisor"), id).first)
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, "/api/lms/studio/courses/$id/versions", mapOf("fromVersionId" to null)).first)
        assertEquals(HttpStatus.NOT_FOUND, copy(creator, UUID.randomUUID().toString()).first)
    }
}
