package elearning.learning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.LocalDate
import java.time.ZoneId

// reprogramar y cancelar (ADR-0016 §5, RF-SES-03, RF-AUL-09): con motivo en el historial y avisando a los matriculados de
// esa edición y a nadie más
class SessionChangesApiTest : ClassroomApiTestSupport() {
    private val today get() = LocalDate.now(ZoneId.of("America/Lima"))

    private inner class Scene {
        val course = course()
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val elsewhere = newStudent().also { enroll(it, course()) }
        val session: String =
            call(
                teacher,
                "/api/lms/teaching/offerings/${course.offering}/sessions",
                mapOf(
                    "title" to "Teoría ${uniqueName("s")}",
                    "type" to "TEORIA",
                    "modality" to "VIRTUAL",
                    "durationMinutes" to 90,
                    "recurrence" to
                        mapOf(
                            "pattern" to "ONCE",
                            "startDate" to today.plusDays(1).toString(),
                            "endDate" to today.plusDays(1).toString(),
                            "localTime" to "08:00",
                        ),
                ),
            ).second!![0]["id"].asString()
        val title: String = ok(call(student, "/api/lms/classroom/enrollments/$enrollment/sessions"))[0]["title"].asString()

        fun reschedule(
            token: String,
            reason: String? = "Feriado",
        ) = call(
            token,
            "/api/lms/teaching/sessions/$session/reschedule",
            mapOf(
                "date" to today.plusDays(3).toString(),
                "localTime" to "10:30",
                "reason" to reason,
            ),
        )

        fun cancel(
            token: String,
            reason: String? = "Sin aula",
        ) = call(token, "/api/lms/teaching/sessions/$session/cancel", mapOf("reason" to reason))

        fun inbox(token: String) =
            client
                .get()
                .uri(
                    "/api/auth/me/notifications",
                ).header("Authorization", token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
                .orEmpty()
    }

    @Test
    fun `rescheduling keeps the duration, records the reason and warns only that offering`() {
        val s = Scene()

        val moved = ok(s.reschedule(s.teacher))

        assertEquals("REPROGRAMADA", moved["status"].asString())
        assertEquals("${today.plusDays(3)}T15:30:00Z", moved["start"].asString())
        assertEquals("${today.plusDays(3)}T17:00:00Z", moved["end"].asString())
        val history =
            client
                .get()
                .uri(
                    "/api/objects/sesion/records/${s.session}/history",
                ).header("Authorization", admin)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
                .orEmpty()
        assertTrue("Feriado" in history, history)
        assertTrue(s.title in s.inbox(s.student), "el matriculado recibe el aviso")
        assertFalse(s.title in s.inbox(s.elsewhere), "el de otra edición, no")
        assertFalse(s.title in s.inbox(s.teacher), "quien la cambió, no")
    }

    @Test
    fun `cancelling warns and closes the session for further changes`() {
        val s = Scene()

        assertEquals("CANCELADA", ok(s.cancel(s.teacher))["status"].asString())

        assertTrue(s.title in s.inbox(s.student))
        assertFalse(s.title in s.inbox(s.elsewhere))
        assertEquals("CANCELADA", ok(call(s.student, "/api/lms/classroom/enrollments/${s.enrollment}/sessions"))[0]["status"].asString())
        assertEquals(HttpStatus.CONFLICT, s.reschedule(s.teacher).first)
        assertEquals(HttpStatus.CONFLICT, s.cancel(s.teacher).first)
    }

    // revisión de la Fase 3 (L7): reprogramar y cancelar a la vez pasaban las dos comprobaciones y una cancelada podía
    // volver a REPROGRAMADA. en cualquier orden, la sesión acaba cancelada y lo que llega después de cancelar es 409
    @Test
    fun `rescheduling and cancelling at once always leave the session cancelled`() {
        repeat(3) {
            val s = Scene()
            val statuses =
                runBlocking(Dispatchers.IO) {
                    val moved = async { s.reschedule(s.teacher).first }
                    val cancelled = async { s.cancel(s.teacher).first }
                    listOf(moved.await(), cancelled.await())
                }

            assertEquals(HttpStatus.OK, statuses[1], statuses.toString())
            assertTrue(statuses[0] in setOf(HttpStatus.OK, HttpStatus.CONFLICT), statuses.toString())
            assertEquals("CANCELADA", ok(call(s.student, "/api/lms/classroom/enrollments/${s.enrollment}/sessions"))[0]["status"].asString())
        }
    }

    @Test
    fun `a change needs a reason and an assigned teacher`() {
        val s = Scene()
        val outsider = newTeacher().also { assign(it, course()) }

        assertEquals(HttpStatus.BAD_REQUEST, s.reschedule(s.teacher, reason = null).first)
        assertEquals(HttpStatus.BAD_REQUEST, s.cancel(s.teacher, reason = "  ").first)
        for (token in listOf(outsider, s.student)) {
            assertEquals(HttpStatus.NOT_FOUND, s.reschedule(token).first)
            assertEquals(HttpStatus.NOT_FOUND, s.cancel(token).first)
        }
        assertEquals("PROGRAMADA", ok(call(s.student, "/api/lms/classroom/enrollments/${s.enrollment}/sessions"))[0]["status"].asString())
        assertFalse(s.title in s.inbox(s.student), "lo rechazado no avisa")
    }
}
