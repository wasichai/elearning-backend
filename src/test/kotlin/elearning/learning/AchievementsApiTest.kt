package elearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.time.Instant

// los logros del aula (RF-AUL-08): se derivan al leer de lo que el aula ya calcula, sin modelo ni escritura propios. el
// curso de prueba tiene una lectura, un vídeo y una lección de evaluación sin evaluación, que no cuenta: la unidad se
// completa con las dos primeras. la evaluación aprobada la prueba la semilla (DemoClassroomApiTest)
class AchievementsApiTest : ClassroomApiTestSupport() {
    private inner class Scene {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        fun classroom(token: String = student) = call(token, "/api/lms/classroom/enrollments/$enrollment")

        fun achievements(): List<JsonNode> = ok(classroom())["achievements"].values().toList()
    }

    @Test
    fun `a unit is an achievement once all its countable lessons are completed, dated by the last one`() {
        val s = Scene()
        assertTrue(s.achievements().isEmpty(), "sin nada hecho, ningún logro")

        ok(event(s.student, s.enrollment, s.course.reading, "MARKED_COMPLETE"))
        assertTrue(s.achievements().isEmpty(), "media unidad no es un logro")

        ok(event(s.student, s.enrollment, s.course.video, "VIDEO_ENDED"))
        val classroom = ok(s.classroom())
        val unit = classroom["achievements"].values().single()
        assertEquals("UNIT_COMPLETED", unit["kind"].asString())
        assertEquals(s.course.unit, unit["ref"].asString())
        val video = classroom["units"][0]["lessons"].values().single { it["id"].asString() == s.course.video }
        assertEquals(Instant.parse(video["progress"]["completedAt"].asString()), Instant.parse(unit["at"].asString()))
    }

    @Test
    fun `the certificate is an achievement, after the unit, and reading the classroom does not change them`() {
        val s = Scene()
        ok(event(s.student, s.enrollment, s.course.reading, "MARKED_COMPLETE"))
        ok(event(s.student, s.enrollment, s.course.video, "VIDEO_ENDED"))
        val (status, certificate) = call(s.student, "/api/lms/classroom/enrollments/${s.enrollment}/certificate", mapOf("holderName" to "Ana Pérez"))
        assertEquals(HttpStatus.CREATED, status, certificate.toString())

        val achievements = s.achievements()
        assertEquals(listOf("UNIT_COMPLETED", "CERTIFICATE_ISSUED"), achievements.map { it["kind"].asString() })
        assertEquals(certificate!!["code"].asString(), achievements[1]["ref"].asString())
        assertEquals(Instant.parse(certificate["issuedAt"].asString()), Instant.parse(achievements[1]["at"].asString()))
        assertEquals(achievements, s.achievements(), "se calculan al leer y no cambian al releer")
    }

    @Test
    fun `only the student of the enrollment sees them, not another student nor the assigned teacher`() {
        val s = Scene()
        ok(event(s.student, s.enrollment, s.course.reading, "MARKED_COMPLETE"))
        ok(event(s.student, s.enrollment, s.course.video, "VIDEO_ENDED"))

        assertEquals(HttpStatus.NOT_FOUND, s.classroom(newStudent()).first)
        assertEquals(HttpStatus.NOT_FOUND, s.classroom(newTeacher().also { assign(it, s.course) }).first)
        assertEquals(HttpStatus.UNAUTHORIZED, s.classroom(null).first)
    }
}
