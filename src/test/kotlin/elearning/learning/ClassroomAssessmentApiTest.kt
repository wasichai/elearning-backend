package elearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// la evaluación como lección del aula (ADR-0016 §4): se completa al aprobar, calculado a partir de sus intentos
class ClassroomAssessmentApiTest : ClassroomApiTestSupport() {
    private fun lesson(
        token: String,
        enrollment: String,
        id: String,
    ): JsonNode =
        ok(call(token, "/api/lms/classroom/enrollments/$enrollment"))["units"]
            .values()
            .flatMap { it["lessons"].values() }
            .single { it["id"].asString() == id }

    private fun start(
        token: String,
        enrollment: String,
        course: Course,
    ) = call(token, "/api/lms/classroom/enrollments/$enrollment/lessons/${course.assessment}/attempts", emptyMap()).second!!

    private fun option(
        attempt: JsonNode,
        question: String,
        text: String,
    ) = attempt["questions"]
        .values()
        .single { it["id"].asString() == question }["options"]
        .values()
        .single { it["text"].asString() == text }["id"]
        .asString()

    // 7 de 10 con la abierta en blanco: aprueba (mínimo 60)
    private fun pass(
        token: String,
        attempt: JsonNode,
        course: Course,
        open: String? = null,
    ) {
        val q = course.questions
        val id = attempt["id"].asString()

        fun closed(
            key: String,
            vararg texts: String,
        ) = mapOf("questionId" to q.getValue(key), "optionIds" to texts.map { option(attempt, q.getValue(key), it) })
        ok(
            put(
                token,
                "/api/lms/classroom/attempts/$id/answers",
                mapOf(
                    "answers" to
                        listOf(
                            closed("SINGLE", "Correcta"),
                            closed("MULTIPLE", "Sí A", "Sí B"),
                            closed("TRUE_FALSE", "Verdadero"),
                            mapOf("questionId" to q.getValue("SHORT"), "text" to "padron"),
                            mapOf("questionId" to q.getValue("OPEN"), "text" to open),
                        ),
                ),
            ),
        )
        ok(call(token, "/api/lms/classroom/attempts/$id/submit", emptyMap()))
    }

    @Test
    fun `an evaluation lesson shows its assessment and counts for the progress`() {
        val course = course(withEvaluation = true)
        val student = newStudent()
        val view = enroll(student, course)
        val enrollment = view["id"].asString()
        assertEquals(3, view["progress"]["totalLessons"].asInt())

        val before = lesson(student, enrollment, course.assessment)
        assertEquals("ASSESSMENT", before["completion"].asString())
        assertEquals(course.evaluation, before["assessment"]["id"].asString())
        assertEquals(2, before["assessment"]["maxAttempts"].asInt())
        assertEquals(60, before["assessment"]["passingPercent"].asInt())
        assertEquals(0, before["assessment"]["attemptsUsed"].asInt())
        assertTrue(before["assessment"]["openAttemptId"].isNull)
        assertTrue(before["assessment"]["best"].isNull)
        assertEquals("NO_INICIADA", before["progress"]["status"].asString())
        assertTrue(lesson(student, enrollment, course.reading)["assessment"].isNull)

        val attempt = start(student, enrollment, course)
        val open = lesson(student, enrollment, course.assessment)
        assertEquals(attempt["id"].asString(), open["assessment"]["openAttemptId"].asString())
        assertEquals("INICIADA", open["progress"]["status"].asString())
    }

    @Test
    fun `passing an attempt approves the lesson and failing one does not`() {
        val course = course(withEvaluation = true)
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        val failed = start(student, enrollment, course)
        ok(call(student, "/api/lms/classroom/attempts/${failed["id"].asString()}/submit", emptyMap()))
        val afterFail = lesson(student, enrollment, course.assessment)
        assertEquals("INICIADA", afterFail["progress"]["status"].asString())
        assertEquals(0, afterFail["assessment"]["best"]["percent"].asInt())
        assertFalse(afterFail["assessment"]["best"]["passed"].asBoolean())

        pass(student, start(student, enrollment, course), course)

        val approved = lesson(student, enrollment, course.assessment)
        assertEquals("APROBADA", approved["progress"]["status"].asString())
        assertEquals("EVALUACION_APROBADA", approved["progress"]["evidence"].asString())
        assertFalse(approved["progress"]["completedAt"].isNull)
        assertEquals(2, approved["assessment"]["attemptsUsed"].asInt())
        assertEquals(70, approved["assessment"]["best"]["percent"].asInt())
        val summary = ok(call(student, "/api/lms/classroom/enrollments/$enrollment"))
        assertEquals(1, summary["progress"]["completedLessons"].asInt())
        assertEquals(33, summary["progress"]["percent"].asInt())
    }

    @Test
    fun `a pending attempt does not approve until its teacher grades it`() {
        val course = course(withEvaluation = true)
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val attempt = start(student, enrollment, course)
        pass(student, attempt, course, open = "Por su fecha")

        val pending = lesson(student, enrollment, course.assessment)
        assertEquals("INICIADA", pending["progress"]["status"].asString())
        assertTrue(pending["assessment"]["best"]["pending"].asBoolean())
        assertTrue(pending["assessment"]["best"]["percent"].isNull)

        ok(
            call(
                teacher,
                "/api/lms/teaching/attempts/${attempt["id"].asString()}/grades",
                mapOf("questionId" to course.questions.getValue("OPEN"), "points" to 3),
            ),
        )

        assertEquals("APROBADA", lesson(student, enrollment, course.assessment)["progress"]["status"].asString())
    }

    @Test
    fun `a lab with an evaluation is not completed by marking it`() {
        val course = course(withEvaluation = true, assessmentLessonType = "LABORATORIO")
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        assertEquals("ASSESSMENT", lesson(student, enrollment, course.assessment)["completion"].asString())
        assertEquals(HttpStatus.CONFLICT, event(student, enrollment, course.assessment, "MARKED_COMPLETE").first)
        assertEquals(HttpStatus.OK, event(student, enrollment, course.assessment, "STARTED").first)
    }

    @Test
    fun `an evaluation lesson without evaluation stays out of the progress`() {
        val course = course()
        val student = newStudent()
        val view = enroll(student, course)

        assertEquals(2, view["progress"]["totalLessons"].asInt())
        assertEquals("NOT_YET", lesson(student, view["id"].asString(), course.assessment)["completion"].asString())
    }
}
