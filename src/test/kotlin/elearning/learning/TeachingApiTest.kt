package elearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

// el espacio docente (ADR-0016, docs/api/lms.md): sus ediciones asignadas y sus matriculados con su progreso
class TeachingApiTest : ClassroomApiTestSupport() {
    @Test
    fun `a teacher lists their offerings and the students of each with their progress`() {
        val course = course()
        val teacher = newTeacher()
        assign(teacher, course)
        val first = newStudent()
        val firstEnrollment = enroll(first, course)["id"].asString()
        enroll(newStudent(), course)
        ok(event(first, firstEnrollment, course.reading, "MARKED_COMPLETE"))

        val offerings = ok(call(teacher, "/api/lms/teaching/offerings"))
        val offering = offerings.single()
        assertEquals(course.offering, offering["code"].asString())
        assertEquals(2, offering["enrolled"].asInt())
        assertEquals("America/Lima", offering["timeZone"].asString())
        assertEquals(0, offering["pendingAttempts"].asInt())

        val students = ok(call(teacher, "/api/lms/teaching/offerings/${course.offering}/students"))
        assertEquals(2, students.size())
        val row = students.values().single { it["enrollmentId"].asString() == firstEnrollment }
        assertEquals(get("/api/auth/me", first)["email"].asString(), row["email"].asString())
        assertEquals(1, row["progress"]["completedLessons"].asInt())
        assertEquals("ACTIVA", row["status"].asString())
    }

    @Test
    fun `nobody reaches an offering that is not assigned to them`() {
        val course = course()
        val teacher = newTeacher()
        assign(teacher, course)
        val outsider = newTeacher()
        val student = newStudent()
        enroll(student, course)

        assertEquals(0, ok(call(outsider, "/api/lms/teaching/offerings")).size())
        assertEquals(HttpStatus.NOT_FOUND, call(outsider, "/api/lms/teaching/offerings/${course.offering}/students").first)
        assertEquals(HttpStatus.NOT_FOUND, call(student, "/api/lms/teaching/offerings/${course.offering}/students").first)
        assertEquals(HttpStatus.NOT_FOUND, call(teacher, "/api/lms/teaching/offerings/NO-EXISTE/students").first)
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, "/api/lms/teaching/offerings").first)
    }
}
