package elearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

// el docente por asignación (ADR-0016, RF-ROL-04): lee las matrículas y el progreso de sus ediciones y de ninguna otra
class TeacherScopeApiTest : ClassroomApiTestSupport() {
    @Test
    fun `a teacher reads the enrollments and progress of their offering and nothing else`() {
        val mine = course()
        val other = course()
        val teacher = newTeacher()
        assign(teacher, mine)
        val student = newStudent()
        val inMine = enroll(student, mine)["id"].asString()
        val inOther = enroll(newStudent(), other)["id"].asString()
        ok(event(student, inMine, mine.reading, "MARKED_COMPLETE"))

        val enrollments = get("/api/objects/matricula/records?size=200", teacher)["content"]
        assertEquals(listOf(inMine), enrollments.strings { it["id"] })
        assertEquals(get("/api/auth/me", student)["email"].asString(), enrollments[0]["attributes"]["estudiante_correo"].asString())
        assertEquals(HttpStatus.NOT_FOUND, call(teacher, "/api/objects/matricula/records/$inOther").first)
        assertEquals(1, get("/api/objects/progreso_leccion/records?matricula=$inMine", teacher)["totalElements"].asInt())

        // otro docente, sin asignación, no ve nada
        val outsider = newTeacher()
        assertEquals(0, get("/api/objects/matricula/records?size=200", outsider)["totalElements"].asInt())
        assertEquals(HttpStatus.NOT_FOUND, call(outsider, "/api/objects/matricula/records/$inMine").first)
        assertEquals(0, get("/api/objects/progreso_leccion/records?matricula=$inMine", outsider)["totalElements"].asInt())
    }

    @Test
    fun `an assignment never lets a teacher write a student's progress or enrollment`() {
        val course = course()
        val teacher = newTeacher()
        assign(teacher, course)
        val enrollment = enroll(newStudent(), course)["id"].asString()

        assertEquals(HttpStatus.NOT_FOUND, event(teacher, enrollment, course.reading, "MARKED_COMPLETE").first)
        assertEquals(HttpStatus.NOT_FOUND, call(teacher, "/api/lms/classroom/enrollments/$enrollment").first)
    }
}
