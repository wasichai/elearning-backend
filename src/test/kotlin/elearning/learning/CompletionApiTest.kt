package elearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.LocalDate
import java.time.ZoneId

// las reglas de finalización de una matrícula (ADR-0016 §7, RF-ACA-09, RF-EVA-07)
class CompletionApiTest : ClassroomApiTestSupport() {
    private fun completion(
        token: String,
        enrollment: String,
    ) = ok(call(token, "/api/lms/classroom/enrollments/$enrollment/completion"))

    @Test
    fun `without declared rules it asks for the lessons and follows the progress`() {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        val before = completion(student, enrollment)
        assertFalse(before["met"].asBoolean())
        assertEquals("LESSONS", before["rules"].single()["rule"].asString())
        assertEquals("0 de 2 lecciones", before["rules"].single()["detail"].asString())

        ok(event(student, enrollment, course.reading, "MARKED_COMPLETE"))
        ok(event(student, enrollment, course.video, "VIDEO_ENDED"))

        assertTrue(completion(student, enrollment)["met"].asBoolean())
    }

    @Test
    fun `the declared rules read the attempts and the attendance`() {
        val course =
            course(withEvaluation = true, completionRules = mapOf("regla_lecciones" to false, "regla_evaluaciones" to true, "asistencia_minima" to 100))
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val tomorrow = LocalDate.now(ZoneId.of("America/Lima")).plusDays(1).toString()
        val session =
            call(
                teacher,
                "/api/lms/teaching/offerings/${course.offering}/sessions",
                mapOf(
                    "title" to "Única",
                    "type" to "TEORIA",
                    "modality" to "VIRTUAL",
                    "durationMinutes" to 60,
                    "recurrence" to mapOf("pattern" to "ONCE", "startDate" to tomorrow, "endDate" to tomorrow, "localTime" to "08:00"),
                ),
            ).second!![0]["id"].asString()

        val before = completion(student, enrollment)
        assertEquals(listOf("ASSESSMENTS", "ATTENDANCE"), before["rules"].values().map { it["rule"].asString() })
        assertFalse(before["met"].asBoolean())

        ok(
            put(
                teacher,
                "/api/lms/teaching/sessions/$session/attendance",
                mapOf("records" to listOf(mapOf("enrollmentId" to enrollment, "status" to "PRESENTE"))),
            ),
        )
        val attended = completion(student, enrollment)
        assertTrue(attended["rules"].values().single { it["rule"].asString() == "ATTENDANCE" }["met"].asBoolean())
        assertFalse(attended["met"].asBoolean(), "falta la evaluación")
    }

    @Test
    fun `nobody reads the completion of someone else's enrollment`() {
        val course = course()
        val enrollment = enroll(newStudent(), course)["id"].asString()
        val teacher = newTeacher().also { assign(it, course) }

        assertEquals(HttpStatus.NOT_FOUND, call(newStudent(), "/api/lms/classroom/enrollments/$enrollment/completion").first)
        assertEquals(HttpStatus.NOT_FOUND, call(teacher, "/api/lms/classroom/enrollments/$enrollment/completion").first)
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, "/api/lms/classroom/enrollments/$enrollment/completion").first)
    }
}
