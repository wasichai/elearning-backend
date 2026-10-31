package elearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

// reglas del progreso (ADR-0012): solo avanza, cada tipo de lección se completa con su evidencia y el tiempo con la
// página abierta no cuenta
class LessonProgressApiTest : ClassroomApiTestSupport() {
    @Test
    fun `a reading is started and then completed by the student, and never goes back`() {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        val (started, s) = event(student, enrollment, course.reading, "STARTED")
        assertEquals(HttpStatus.OK, started, s.toString())
        assertEquals("INICIADA", s!!["status"].asString())
        assertTrue(s["completedAt"].isNull)

        val (completed, c) = event(student, enrollment, course.reading, "MARKED_COMPLETE")
        assertEquals(HttpStatus.OK, completed, c.toString())
        assertEquals("COMPLETADA", c!!["status"].asString())
        assertEquals("FINALIZACION_EXPLICITA", c["evidence"].asString())
        assertEquals(s["startedAt"].asString(), c["startedAt"].asString())

        val (_, again) = event(student, enrollment, course.reading, "STARTED")
        assertEquals("COMPLETADA", again!!["status"].asString())
        assertEquals(c["completedAt"].asString(), again["completedAt"].asString())

        // la matrícula cuenta la lectura y apunta a la siguiente; la evaluación (F3) no cuenta
        val view = enroll(student, course)
        assertEquals(1, view["progress"]["completedLessons"].asInt())
        assertEquals(2, view["progress"]["totalLessons"].asInt())
        assertEquals(50, view["progress"]["percent"].asInt())
        assertEquals(course.video, view["nextLesson"]["id"].asString())
    }

    @Test
    fun `completing straight away also records when it started`() {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        val (_, c) = event(student, enrollment, course.reading, "MARKED_COMPLETE")

        assertEquals("COMPLETADA", c!!["status"].asString())
        assertFalse(c["startedAt"].isNull)
    }

    @Test
    fun `each lesson type is completed only with its own evidence`() {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        assertEquals(HttpStatus.CONFLICT, event(student, enrollment, course.video, "MARKED_COMPLETE").first)
        assertEquals(HttpStatus.CONFLICT, event(student, enrollment, course.reading, "VIDEO_ENDED").first)
        assertEquals(HttpStatus.CONFLICT, event(student, enrollment, course.assessment, "MARKED_COMPLETE").first)
        assertEquals(HttpStatus.CONFLICT, event(student, enrollment, course.assessment, "VIDEO_ENDED").first)

        val (_, started) = event(student, enrollment, course.assessment, "STARTED")
        assertEquals("INICIADA", started!!["status"].asString())
        val (ended, v) = event(student, enrollment, course.video, "VIDEO_ENDED")
        assertEquals(HttpStatus.OK, ended, v.toString())
        assertEquals("COMPLETADA", v!!["status"].asString())
        assertEquals("FIN_DE_VIDEO", v["evidence"].asString())
    }

    @Test
    fun `the same event sent twice counts once`() {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val id = UUID.randomUUID().toString()

        val (first, a) = event(student, enrollment, course.reading, "STARTED", id)
        val (second, b) = event(student, enrollment, course.reading, "STARTED", id)

        assertEquals(HttpStatus.OK, first)
        assertEquals(HttpStatus.OK, second)
        assertEquals(a, b)
        assertEquals(1, get("/api/objects/progreso_leccion/records?matricula=$enrollment", student)["totalElements"].asInt())
    }

    @Test
    fun `a client event id reused for another lesson or type is a conflict and records nothing`() {
        // revisión de la Fase 2 (L9): antes respondía 200 con el progreso de la otra lección, sin registrar nada
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val id = UUID.randomUUID().toString()
        val (_, started) = event(student, enrollment, course.reading, "STARTED", id)

        assertEquals(HttpStatus.CONFLICT, event(student, enrollment, course.reading, "MARKED_COMPLETE", id).first)
        assertEquals(HttpStatus.CONFLICT, event(student, enrollment, course.video, "STARTED", id).first)
        val (again, same) = event(student, enrollment, course.reading, "STARTED", id)
        assertEquals(HttpStatus.OK, again)
        assertEquals(started, same)
        assertEquals(1, get("/api/objects/progreso_leccion/records?matricula=$enrollment", student)["totalElements"].asInt())
    }

    @Test
    fun `simultaneous events on a lesson leave one progress record and no errors`() {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val pool = Executors.newFixedThreadPool(6)
        val statuses =
            try {
                pool
                    .invokeAll(
                        List(6) { i -> Callable { event(student, enrollment, course.reading, if (i % 2 == 0) "STARTED" else "MARKED_COMPLETE").first } },
                    ).map { it.get() }
            } finally {
                pool.shutdown()
            }

        assertTrue(statuses.all { it == HttpStatus.OK }, statuses.toString())
        val progress = get("/api/objects/progreso_leccion/records?matricula=$enrollment", student)
        assertEquals(1, progress["totalElements"].asInt())
        assertEquals("COMPLETADA", progress["content"][0]["attributes"]["estado"].asString())
    }

    @Test
    fun `progress is only written through the classroom, and only on your own enrollment`() {
        val course = course()
        val student = newStudent()
        val other = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val otherEnrollment = enroll(other, course())["id"].asString()
        event(student, enrollment, course.reading, "STARTED")

        // ajena, inexistente, o una lección de otra versión: lo mismo, no existe
        assertEquals(HttpStatus.NOT_FOUND, event(other, enrollment, course.reading, "STARTED").first)
        assertEquals(HttpStatus.NOT_FOUND, event(student, UUID.randomUUID().toString(), course.reading, "STARTED").first)
        assertEquals(HttpStatus.NOT_FOUND, event(other, otherEnrollment, course.reading, "STARTED").first)
        assertEquals(HttpStatus.BAD_REQUEST, event(student, enrollment, course.reading, "OPENED_FOR_AN_HOUR").first)
        assertEquals(
            HttpStatus.BAD_REQUEST,
            call(
                student,
                "/api/lms/classroom/enrollments/$enrollment/lessons/${course.reading}/events",
                mapOf("type" to "STARTED"),
            ).first,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, event(null, enrollment, course.reading, "STARTED").first)

        // el progreso no se escribe por la API genérica, y nadie lee el de otro
        val forged = mapOf("attributes" to mapOf("matricula" to enrollment, "leccion" to course.video, "estudiante_id" to "x", "estado" to "COMPLETADA"))
        assertTrue(call(student, "/api/objects/progreso_leccion/records", forged).first.is4xxClientError)
        assertEquals(0, get("/api/objects/progreso_leccion/records?matricula=$enrollment", other)["totalElements"].asInt())
    }
}
