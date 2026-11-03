package elearning.course

import elearning.learning.ClassroomApiTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// la lección de repaso de una pregunta (#260): de su misma versión, congelada con lo publicado y re-apuntada a la lección
// copiada al crear la versión siguiente
class ReviewLessonApiTest : ClassroomApiTestSupport() {
    private val creator by lazy { demoUser("creador") }

    private fun records(
        obj: String,
        field: String,
        value: String,
    ): List<JsonNode> = get("/api/objects/$obj/records?$field=$value&size=200", admin)["content"].values().toList()

    private fun patch(
        token: String,
        question: String,
        lesson: String?,
    ): HttpStatus =
        HttpStatus.valueOf(
            client
                .patch()
                .uri("/api/objects/pregunta/records/$question")
                .header("Authorization", token)
                .bodyValue(mapOf("attributes" to mapOf("leccion_repaso" to lesson)))
                .exchange()
                .returnResult(String::class.java)
                .status
                .value(),
        )

    private fun reviewOf(question: String) = get("/api/objects/pregunta/records/$question", admin)["attributes"]["leccion_repaso"]

    // la copia del curso como borrador del creador: sus lecciones por tipo y su pregunta de opción única
    private fun draftOf(fixture: Course): Pair<Map<String, String>, String> {
        val course = get("/api/objects/curso/records?codigo=${fixture.offering}", admin)["content"][0]["id"].asString()
        val (status, created) = call(creator, "/api/lms/studio/courses/$course/versions", mapOf("fromVersionId" to null))
        assertEquals(HttpStatus.CREATED, status, created.toString())
        val unit = records("unidad", "version_curso", created!!["id"].asString()).single()["id"].asString()
        val lessons = records("leccion", "unidad", unit).associate { it["attributes"]["tipo"].asString() to it["id"].asString() }
        val evaluation = records("evaluacion", "leccion", lessons.getValue("EVALUACION")).single()["id"].asString()
        val single = records("pregunta", "evaluacion", evaluation).single { it["attributes"]["tipo"].asString() == "UNICA" }["id"].asString()
        return lessons to single
    }

    @Test
    fun `copying a version points the review link to the copied lesson`() {
        val fixture = course(withEvaluation = true)

        val (lessons, single) = draftOf(fixture)

        assertEquals(lessons.getValue("LECTURA"), reviewOf(single).asString())
        assertNotEquals(fixture.reading, reviewOf(single).asString())
        assertEquals(fixture.reading, reviewOf(fixture.questions.getValue("SINGLE")).asString())
    }

    @Test
    fun `a review lesson of another version is refused`() {
        val fixture = course(withEvaluation = true)
        val (lessons, single) = draftOf(fixture)

        assertEquals(HttpStatus.BAD_REQUEST, patch(creator, single, fixture.reading))
        assertEquals(HttpStatus.BAD_REQUEST, patch(creator, single, course().reading))
        assertEquals(lessons.getValue("LECTURA"), reviewOf(single).asString())

        assertEquals(HttpStatus.OK, patch(creator, single, lessons.getValue("VIDEO")))
        assertEquals(lessons.getValue("VIDEO"), reviewOf(single).asString())
        assertEquals(HttpStatus.OK, patch(creator, single, null))
        assertTrue(reviewOf(single)?.isNull ?: true)
    }

    @Test
    fun `the review link of a published question does not change`() {
        val fixture = course(withEvaluation = true)
        val single = fixture.questions.getValue("SINGLE")

        assertEquals(HttpStatus.CONFLICT, patch(admin, single, fixture.video))
        assertEquals(HttpStatus.CONFLICT, patch(admin, single, null))
        assertEquals(fixture.reading, reviewOf(single).asString())
    }
}
