package elearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// las entregas de archivo (ADR-0016 §3, RF-LAB-02): de su estudiante y de su intento abierto, fijas al enviarlo, y solo las
// bajan él y el docente de la edición
class SubmissionApiTest : ClassroomApiTestSupport() {
    private inner class Scene {
        val course = course(withEvaluation = true, withFileQuestion = true)
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val attempt: String =
            call(
                student,
                "/api/lms/classroom/enrollments/$enrollment/lessons/${course.assessment}/attempts",
                emptyMap(),
            ).second!!["id"].asString()
        val file = course.questions.getValue("FILE")

        fun create(
            token: String = student,
            question: String = file,
        ) = call(token, "/api/lms/classroom/attempts/$attempt/questions/$question/submission", emptyMap())

        fun answer(id: String?) =
            put(student, "/api/lms/classroom/attempts/$attempt/answers", mapOf("answers" to listOf(mapOf("questionId" to file, "submissionId" to id))))
    }

    private fun JsonNode.fileAnswer(question: String) = this["answers"].values().single { it["questionId"].asString() == question }

    @Test
    fun `a student uploads, answers with it and the teacher downloads and grades it`() {
        val s = Scene()
        val (status, created) = s.create()
        assertTrue(status.is2xxSuccessful, created.toString())
        val id = created!!["id"].asString()
        assertTrue(created["file"].isNull)
        assertEquals(id, s.create().second!!["id"].asString(), "pedirla otra vez da la misma")
        assertEquals(HttpStatus.OK, upload(s.student, created["uploadUrl"].asString(), "informe.txt", "Mi informe".toByteArray()))

        val saved = ok(s.answer(id))
        assertEquals(id, saved.fileAnswer(s.file)["submission"]["id"].asString())
        assertEquals("informe.txt", saved.fileAnswer(s.file)["submission"]["file"]["name"].asString())

        val done = ok(call(s.student, "/api/lms/classroom/attempts/${s.attempt}/submit", emptyMap()))
        assertTrue(done["result"]["pending"].asBoolean())

        val seen = ok(call(s.teacher, "/api/lms/teaching/attempts/${s.attempt}"))
        val url = seen.fileAnswer(s.file)["submission"]["file"]["url"].asString()
        val body =
            client
                .get()
                .uri(
                    url,
                ).header("Authorization", s.teacher)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
        assertEquals("Mi informe", body)
        ok(call(s.teacher, "/api/lms/teaching/attempts/${s.attempt}/grades", mapOf("questionId" to s.file, "points" to 5)))
    }

    @Test
    fun `a submission is fixed once the attempt is submitted`() {
        val s = Scene()
        val created = s.create().second!!
        upload(s.student, created["uploadUrl"].asString(), "a.txt", "a".toByteArray())
        ok(s.answer(created["id"].asString()))
        ok(call(s.student, "/api/lms/classroom/attempts/${s.attempt}/submit", emptyMap()))

        assertFalse(upload(s.student, created["uploadUrl"].asString(), "b.txt", "b".toByteArray()).is2xxSuccessful)
        assertEquals(HttpStatus.CONFLICT, s.create().first)
    }

    @Test
    fun `an answer needs an uploaded file of this attempt and question`() {
        val s = Scene()
        val empty = s.create().second!!["id"].asString()
        assertEquals(HttpStatus.BAD_REQUEST, s.answer(empty).first, "sin archivo todavía")

        val other = Scene()
        val foreign = other.create().second!!
        upload(other.student, foreign["uploadUrl"].asString(), "x.txt", "x".toByteArray())
        assertEquals(HttpStatus.BAD_REQUEST, s.answer(foreign["id"].asString()).first, "de otro intento")
        assertEquals(HttpStatus.BAD_REQUEST, s.create(question = s.course.questions.getValue("OPEN")).first, "la pregunta no es de archivo")
    }

    @Test
    fun `nobody else reads, downloads or writes a submission, nor by the generic record api`() {
        val s = Scene()
        val created = s.create().second!!
        val id = created["id"].asString()
        upload(s.student, created["uploadUrl"].asString(), "a.txt", "secreto".toByteArray())
        val other = newStudent()
        val outsider = newTeacher()
        assign(outsider, course())
        val file = "/api/objects/entrega/records/$id/files/archivo"

        for (token in listOf(other, outsider)) {
            assertEquals(HttpStatus.NOT_FOUND, call(token, "/api/objects/entrega/records/$id").first)
            assertEquals(HttpStatus.NOT_FOUND, call(token, file).first)
            assertEquals(HttpStatus.NOT_FOUND, s.create(token).first)
            assertFalse(upload(token, file, "b.txt", "b".toByteArray()).is2xxSuccessful)
        }
        // por la API genérica: en nombre de otro, sobre un intento ajeno o moviendo la propia a otra pregunta
        val otherEnrollment = enroll(other, s.course)["id"].asString()
        val mine = mapOf("matricula" to otherEnrollment, "estudiante_id" to userId(other), "intento_id" to s.attempt, "pregunta_id" to s.file)
        assertTrue(call(other, "/api/objects/entrega/records", mapOf("attributes" to mine)).first.is4xxClientError)
        val patched =
            client
                .patch()
                .uri("/api/objects/entrega/records/$id")
                .header("Authorization", s.student)
                .bodyValue(mapOf("attributes" to mapOf("pregunta_id" to s.course.questions.getValue("OPEN"))))
                .exchange()
                .returnResult(String::class.java)
                .status
                .value()
        assertEquals(409, patched)
        val deleted =
            client
                .delete()
                .uri(
                    "/api/objects/entrega/records/$id",
                ).header("Authorization", s.student)
                .exchange()
                .returnResult(String::class.java)
                .status
                .value()
        assertTrue(deleted in 400..499, deleted.toString())
    }
}
