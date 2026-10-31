package elearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.LocalDate
import java.time.ZoneId

// las sesiones (ADR-0016 §5, RF-SES-01/02/04): las crea el docente de la edición, las leen él y sus matriculados
class SessionApiTest : ClassroomApiTestSupport() {
    private val today get() = LocalDate.now(ZoneId.of("America/Lima"))

    private fun body(
        pattern: String = "ONCE",
        link: String? = "https://reunion.example.org/sala",
        endOffset: Long = 0,
        extra: Map<String, Any?> = emptyMap(),
    ) = mapOf(
        "title" to "Teoría",
        "type" to "TEORIA",
        "modality" to "VIRTUAL",
        "durationMinutes" to 90,
        "link" to link,
        "location" to null,
        "lessonId" to null,
        "recurrence" to
            mapOf(
                "pattern" to pattern,
                "days" to emptyList<String>(),
                "startDate" to today.plusDays(1).toString(),
                "endDate" to today.plusDays(1 + endOffset).toString(),
                "localTime" to "08:00",
            ),
    ) + extra

    private fun sessionsPath(code: String) = "/api/lms/teaching/offerings/$code/sessions"

    @Test
    fun `a teacher creates a series and an enrolled student reads it in the offering's zone`() {
        val course = course()
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        val (status, created) = call(teacher, sessionsPath(course.offering), body("DAILY", endOffset = 2))
        assertEquals(HttpStatus.CREATED, status, created.toString())
        assertEquals(3, created!!.size())

        val read = ok(call(student, "/api/lms/classroom/enrollments/$enrollment/sessions"))
        assertEquals(3, read.size())
        val first = read[0]
        assertEquals("Teoría", first["title"].asString())
        assertEquals("America/Lima", first["timeZone"].asString())
        assertEquals("PROGRAMADA", first["status"].asString())
        assertEquals("https://reunion.example.org/sala", first["link"].asString())
        // Lima es UTC-5 sin horario de verano: las 08:00 son las 13:00 Z, y dura 90 minutos
        assertEquals("${today.plusDays(1)}T13:00:00Z", first["start"].asString())
        assertEquals("${today.plusDays(1)}T14:30:00Z", first["end"].asString())
        assertTrue(first["attendance"].isNull)
        assertEquals(3, ok(call(teacher, sessionsPath(course.offering))).size())
    }

    @Test
    fun `a link must be https and not internal, and the request must make sense`() {
        val course = course()
        val teacher = newTeacher().also { assign(it, course) }
        val invalid =
            listOf(
                body(link = "http://reunion.example.org"),
                body(link = "file:///etc/passwd"),
                body(link = "https://localhost/sala"),
                body(link = "https://127.0.0.1/sala"),
                body(link = "https://192.168.1.10/sala"),
                body(link = "https://169.254.169.254/latest"),
                body(pattern = "MOONLY"),
                body(extra = mapOf("durationMinutes" to 0)),
                body(extra = mapOf("type" to "FIESTA")),
                body(extra = mapOf("title" to " ")),
                body(extra = mapOf("lessonId" to course().reading)),
            )
        for (one in invalid) {
            assertEquals(HttpStatus.BAD_REQUEST, call(teacher, sessionsPath(course.offering), one).first, one.toString())
        }
        assertEquals(0, ok(call(teacher, sessionsPath(course.offering))).size())
    }

    @Test
    fun `nobody outside the offering reaches its sessions`() {
        val course = course()
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val session = call(teacher, sessionsPath(course.offering), body()).second!![0]["id"].asString()
        val other = newStudent()
        enroll(other, course())
        val outsider = newTeacher().also { assign(it, course()) }

        assertEquals(HttpStatus.NOT_FOUND, call(other, "/api/lms/classroom/enrollments/$enrollment/sessions").first)
        assertEquals(HttpStatus.NOT_FOUND, call(outsider, sessionsPath(course.offering)).first)
        assertEquals(HttpStatus.NOT_FOUND, call(outsider, sessionsPath(course.offering), body()).first)
        assertEquals(HttpStatus.NOT_FOUND, call(student, sessionsPath(course.offering), body()).first)
        assertEquals(HttpStatus.NOT_FOUND, call(other, "/api/objects/sesion/records/$session").first)
        assertEquals(HttpStatus.NOT_FOUND, call(outsider, "/api/objects/sesion/records/$session").first)
        assertEquals(HttpStatus.OK, call(student, "/api/objects/sesion/records/$session").first)
        // ni por la API genérica
        assertTrue(call(teacher, "/api/objects/sesion/records", mapOf("attributes" to mapOf("titulo" to "x"))).first.is4xxClientError)
    }
}
