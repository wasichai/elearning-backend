package elearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// la corrección manual (ADR-0016 §2, RF-EVA-05/06): la hace el docente de la edición, con historial y con motivo al cambiar
class TeacherGradingApiTest : ClassroomApiTestSupport() {
    private inner class Scene {
        val course = course(withEvaluation = true)
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val attempt: String =
            call(
                student,
                "/api/lms/classroom/enrollments/$enrollment/lessons/${course.assessment}/attempts",
                emptyMap(),
            ).second!!["id"].asString()
        val open = course.questions.getValue("OPEN")

        fun answerOpenAndSubmit(): JsonNode {
            ok(
                put(
                    student,
                    "/api/lms/classroom/attempts/$attempt/answers",
                    mapOf("answers" to listOf(mapOf("questionId" to open, "optionIds" to emptyList<String>(), "text" to "Por su fecha"))),
                ),
            )
            return ok(call(student, "/api/lms/classroom/attempts/$attempt/submit", emptyMap()))
        }

        fun grade(
            token: String,
            points: Number,
            reason: String? = null,
            question: String = open,
        ) = call(
            token,
            "/api/lms/teaching/attempts/$attempt/grades",
            mapOf(
                "questionId" to question,
                "points" to points,
                "comment" to "Bien",
                "reason" to reason,
            ),
        )
    }

    @Test
    fun `the queue shows what is pending and the teacher sees answers and solutions`() {
        val s = Scene()
        assertEquals(0, ok(call(s.teacher, "/api/lms/teaching/offerings/${s.course.offering}/attempts")).size(), "abierto no entra en la cola")
        s.answerOpenAndSubmit()

        val row = ok(call(s.teacher, "/api/lms/teaching/offerings/${s.course.offering}/attempts")).single()
        assertEquals(s.attempt, row["attemptId"].asString())
        assertEquals(s.enrollment, row["enrollmentId"].asString())
        assertEquals(get("/api/auth/me", s.student)["email"].asString(), row["email"].asString())
        assertEquals(s.course.assessment, row["lessonId"].asString())
        assertEquals(1, row["pendingQuestions"].asInt())
        assertEquals(1, ok(call(s.teacher, "/api/lms/teaching/offerings"))[0]["pendingAttempts"].asInt())

        val seen = ok(call(s.teacher, "/api/lms/teaching/attempts/${s.attempt}"))
        val single = seen["result"]["questions"].values().single { it["questionId"].asString() == s.course.questions.getValue("SINGLE") }
        assertEquals(1, single["solution"]["optionIds"].size())
        assertEquals("Por su fecha", seen["answers"].values().single { it["questionId"].asString() == s.open }["text"].asString())
    }

    @Test
    fun `grading the last pending question closes the attempt and the student sees it`() {
        val s = Scene()
        assertTrue(s.answerOpenAndSubmit()["result"]["pending"].asBoolean())

        val graded = ok(s.grade(s.teacher, 3))

        assertEquals("GRADED", graded["status"].asString())
        assertEquals(30, graded["result"]["percent"].asInt())
        assertFalse(graded["result"]["passed"].asBoolean())
        val mine = ok(call(s.student, "/api/lms/classroom/attempts/${s.attempt}"))
        assertEquals("GRADED", mine["status"].asString())
        assertEquals("Bien", mine["result"]["questions"].values().single { it["questionId"].asString() == s.open }["comment"].asString())
        assertEquals(0, ok(call(s.teacher, "/api/lms/teaching/offerings/${s.course.offering}/attempts")).size())
        assertEquals(1, ok(call(s.teacher, "/api/lms/teaching/offerings/${s.course.offering}/attempts?status=GRADED")).size())

        val roster = ok(call(s.teacher, "/api/lms/teaching/offerings/${s.course.offering}/students")).single()["assessments"].single()
        assertEquals(30, roster["bestPercent"].asInt())
        assertFalse(roster["passed"].asBoolean())
    }

    @Test
    fun `changing a grade needs a reason and keeps the history`() {
        val s = Scene()
        s.answerOpenAndSubmit()
        ok(s.grade(s.teacher, 1))

        assertEquals(HttpStatus.CONFLICT, s.grade(s.teacher, 2).first)
        assertEquals(HttpStatus.CONFLICT, s.grade(s.teacher, 2, reason = "   ").first)
        val changed = ok(s.grade(s.teacher, 2, reason = "Revisé la rúbrica"))

        assertEquals(20, changed["result"]["percent"].asInt())
        val history = ok(call(s.teacher, "/api/lms/teaching/attempts/${s.attempt}"))["history"]
        assertEquals(listOf(1.0, 2.0), history.values().map { it["points"].asDouble() })
        assertEquals("Revisé la rúbrica", history[1]["reason"].asString())
        assertEquals(userId(s.teacher), history[1]["authorId"].asString())
    }

    // revisión de la Fase 3 (L8): una abierta sin responder vale 0 sola, y corregir ese cero no pedía motivo en el
    // servidor aunque la interfaz sí. el cero automático también es una nota ya puesta
    @Test
    fun `changing the automatic zero of an unanswered question needs a reason too`() {
        val s = Scene()
        ok(call(s.student, "/api/lms/classroom/attempts/${s.attempt}/submit", emptyMap()))

        assertEquals(HttpStatus.CONFLICT, s.grade(s.teacher, 1).first)
        val changed = ok(s.grade(s.teacher, 1, reason = "La respuesta llegó por correo"))
        assertEquals("La respuesta llegó por correo", ok(call(s.teacher, "/api/lms/teaching/attempts/${s.attempt}"))["history"][0]["reason"].asString())
        assertEquals(10, changed["result"]["percent"].asInt())
    }

    @Test
    fun `a grade must fit its question and the attempt must be submitted`() {
        val s = Scene()
        assertEquals(HttpStatus.CONFLICT, s.grade(s.teacher, 1).first, "intento abierto")
        s.answerOpenAndSubmit()

        assertEquals(HttpStatus.BAD_REQUEST, s.grade(s.teacher, 4).first)
        assertEquals(HttpStatus.BAD_REQUEST, s.grade(s.teacher, -1).first)
        assertEquals(
            HttpStatus.BAD_REQUEST,
            s.grade(s.teacher, 1, question = s.course.questions.getValue("SINGLE")).first,
            "lo automático no se corrige a mano",
        )
        assertEquals(HttpStatus.BAD_REQUEST, s.grade(s.teacher, 1, question = "00000000-0000-0000-0000-000000000000").first)
    }

    @Test
    fun `points have at most two decimals`() {
        val s = Scene()
        s.answerOpenAndSubmit()

        assertEquals(HttpStatus.BAD_REQUEST, s.grade(s.teacher, 1.995).first)
        assertEquals(HttpStatus.OK, s.grade(s.teacher, 1.5).first)
    }

    @Test
    fun `only the teacher of the offering reaches its attempts`() {
        val s = Scene()
        s.answerOpenAndSubmit()
        val outsider = newTeacher()
        assign(outsider, course())

        for (token in listOf(outsider, s.student, newStudent())) {
            assertEquals(HttpStatus.NOT_FOUND, call(token, "/api/lms/teaching/offerings/${s.course.offering}/attempts").first)
            assertEquals(HttpStatus.NOT_FOUND, call(token, "/api/lms/teaching/attempts/${s.attempt}").first)
            assertEquals(HttpStatus.NOT_FOUND, s.grade(token, 3).first)
        }
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, "/api/lms/teaching/attempts/${s.attempt}/grades", mapOf("questionId" to s.open, "points" to 3)).first)
        assertTrue(ok(call(s.student, "/api/lms/classroom/attempts/${s.attempt}"))["result"]["pending"].asBoolean(), "nadie ajeno cambió la nota")
    }

    @Test
    fun `nobody grades their own attempt`() {
        val course = course(withEvaluation = true)
        val email = "${uniqueName("mix")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(
                mapOf("email" to email, "displayName" to "Docente y estudiante", "password" to DEMO_PASSWORD, "roles" to listOf("DOCENTE", "ESTUDIANTE")),
            ).exchange()
            .expectStatus()
            .isCreated
        val both = bearer(email, DEMO_PASSWORD)
        assign(both, course)
        val enrollment = enroll(both, course)["id"].asString()
        val attempt = call(both, "/api/lms/classroom/enrollments/$enrollment/lessons/${course.assessment}/attempts", emptyMap()).second!!["id"].asString()
        val open = course.questions.getValue("OPEN")
        ok(put(both, "/api/lms/classroom/attempts/$attempt/answers", mapOf("answers" to listOf(mapOf("questionId" to open, "text" to "Yo")))))
        ok(call(both, "/api/lms/classroom/attempts/$attempt/submit", emptyMap()))

        assertEquals(HttpStatus.NOT_FOUND, call(both, "/api/lms/teaching/attempts/$attempt/grades", mapOf("questionId" to open, "points" to 3)).first)
        // tampoco ve por aquí las soluciones de su propio intento, ni se lo encuentra en la cola
        assertEquals(HttpStatus.NOT_FOUND, call(both, "/api/lms/teaching/attempts/$attempt").first)
        assertEquals(0, ok(call(both, "/api/lms/teaching/offerings/${course.offering}/attempts")).size())
        // ni se marca asistencia a sí mismo
        val tomorrow =
            java.time.LocalDate
                .now(java.time.ZoneId.of("America/Lima"))
                .plusDays(1)
                .toString()
        val session =
            call(
                both,
                "/api/lms/teaching/offerings/${course.offering}/sessions",
                mapOf(
                    "title" to "S",
                    "type" to "TEORIA",
                    "modality" to "VIRTUAL",
                    "durationMinutes" to 60,
                    "recurrence" to mapOf("pattern" to "ONCE", "startDate" to tomorrow, "endDate" to tomorrow, "localTime" to "08:00"),
                ),
            ).second!![0]["id"].asString()
        assertEquals(
            HttpStatus.FORBIDDEN,
            put(
                both,
                "/api/lms/teaching/sessions/$session/attendance",
                mapOf("records" to listOf(mapOf("enrollmentId" to enrollment, "status" to "PRESENTE"))),
            ).first,
        )
    }
}
