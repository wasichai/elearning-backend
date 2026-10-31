package elearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.ZoneId

// la asistencia (ADR-0016 §6, RF-SES-05): la registra el docente de la edición, cambiarla exige motivo, no toca el progreso
// de materiales, y una lección con sesión se completa al asistir
class AttendanceApiTest : ClassroomApiTestSupport() {
    private val today get() = LocalDate.now(ZoneId.of("America/Lima"))

    private inner class Scene(
        lesson: (Course) -> String? = { null },
    ) {
        val course = course()
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val session: String =
            call(
                teacher,
                "/api/lms/teaching/offerings/${course.offering}/sessions",
                mapOf(
                    "title" to "Teoría",
                    "type" to "TEORIA",
                    "modality" to "VIRTUAL",
                    "durationMinutes" to 60,
                    "lessonId" to lesson(course),
                    "recurrence" to
                        mapOf(
                            "pattern" to "ONCE",
                            "startDate" to today.plusDays(1).toString(),
                            "endDate" to today.plusDays(1).toString(),
                            "localTime" to "08:00",
                        ),
                ),
            ).second!![0]["id"].asString()

        fun mark(
            token: String,
            status: String,
            reason: String? = null,
            target: String = enrollment,
        ) = put(
            token,
            "/api/lms/teaching/sessions/$session/attendance",
            mapOf(
                "records" to listOf(mapOf("enrollmentId" to target, "status" to status)),
                "reason" to reason,
            ),
        )

        fun lessonOf(id: String): JsonNode =
            ok(call(student, "/api/lms/classroom/enrollments/$enrollment"))["units"].values().flatMap { it["lessons"].values() }.single {
                it["id"].asString() ==
                    id
            }
    }

    @Test
    fun `the teacher takes attendance, the student sees it and the roster counts it`() {
        val s = Scene()
        val other = enroll(newStudent(), s.course)["id"].asString()

        val before = ok(call(s.teacher, "/api/lms/teaching/sessions/${s.session}/attendance"))
        assertEquals(setOf(s.enrollment, other), before.strings { it["enrollmentId"] }.toSet())
        assertTrue(before[0]["status"].isNull)

        val marked = ok(s.mark(s.teacher, "PRESENTE"))
        assertEquals("PRESENTE", marked.values().single { it["enrollmentId"].asString() == s.enrollment }["status"].asString())

        assertEquals("PRESENTE", ok(call(s.student, "/api/lms/classroom/enrollments/${s.enrollment}/sessions"))[0]["attendance"].asString())
        val roster =
            ok(call(s.teacher, "/api/lms/teaching/offerings/${s.course.offering}/students")).values().single {
                it["enrollmentId"].asString() ==
                    s.enrollment
            }
        assertEquals(1, roster["attendance"]["attended"].asInt())
        assertEquals(1, roster["attendance"]["total"].asInt())
    }

    @Test
    fun `changing a recorded attendance needs a reason, and repeating it does not`() {
        val s = Scene()
        ok(s.mark(s.teacher, "AUSENTE"))

        ok(s.mark(s.teacher, "AUSENTE"))
        assertEquals(HttpStatus.CONFLICT, s.mark(s.teacher, "PRESENTE").first)
        assertEquals(HttpStatus.CONFLICT, s.mark(s.teacher, "PRESENTE", reason = " ").first)
        assertEquals("PRESENTE", ok(s.mark(s.teacher, "PRESENTE", reason = "Llegó tarde al registro")).values().single()["status"].asString())
    }

    @Test
    fun `attendance and the progress of materials do not touch each other`() {
        val s = Scene()
        ok(event(s.student, s.enrollment, s.course.reading, "MARKED_COMPLETE"))
        val progress = ok(call(s.student, "/api/lms/classroom/enrollments/${s.enrollment}"))["progress"]

        ok(s.mark(s.teacher, "PRESENTE"))

        assertEquals(progress, ok(call(s.student, "/api/lms/classroom/enrollments/${s.enrollment}"))["progress"])
        assertTrue(ok(call(s.student, "/api/lms/classroom/enrollments/${s.enrollment}/sessions"))[0]["attendance"].asString() == "PRESENTE")
    }

    @Test
    fun `a session lesson is completed by attending`() {
        val s = Scene(lesson = { it.assessment })
        assertEquals("ATTENDANCE", s.lessonOf(s.course.assessment)["completion"].asString())
        assertEquals(HttpStatus.CONFLICT, event(s.student, s.enrollment, s.course.assessment, "MARKED_COMPLETE").first)

        ok(s.mark(s.teacher, "AUSENTE"))
        assertEquals("NO_INICIADA", s.lessonOf(s.course.assessment)["progress"]["status"].asString())

        ok(s.mark(s.teacher, "TARDE", reason = "Corrección"))
        val done = s.lessonOf(s.course.assessment)
        assertEquals("COMPLETADA", done["progress"]["status"].asString())
        assertEquals("ASISTENCIA", done["progress"]["evidence"].asString())
    }

    @Test
    fun `a cancelled session takes no attendance and a duplicated student is refused`() {
        val s = Scene()
        val twice = listOf(mapOf("enrollmentId" to s.enrollment, "status" to "PRESENTE"), mapOf("enrollmentId" to s.enrollment, "status" to "AUSENTE"))
        assertEquals(HttpStatus.BAD_REQUEST, put(s.teacher, "/api/lms/teaching/sessions/${s.session}/attendance", mapOf("records" to twice)).first)

        ok(call(s.teacher, "/api/lms/teaching/sessions/${s.session}/cancel", mapOf("reason" to "Sin aula")))

        assertEquals(HttpStatus.CONFLICT, s.mark(s.teacher, "PRESENTE").first)
    }

    @Test
    fun `a session of another offering of the same version does not change how a lesson completes`() {
        val s = Scene(lesson = { it.reading })
        assertEquals(HttpStatus.CONFLICT, event(s.student, s.enrollment, s.course.reading, "MARKED_COMPLETE").first)
        val version = get("/api/objects/edicion_curso/records?codigo=${s.course.offering}", admin)["content"][0]["attributes"]["version_curso"].asString()
        val other = uniqueName("OTRA-").uppercase()
        call(
            admin,
            "/api/objects/edicion_curso/records",
            mapOf(
                "attributes" to
                    mapOf(
                        "codigo" to other,
                        "version_curso" to version,
                        "estado" to "INSCRIPCION_ABIERTA",
                        "fecha_inicio" to today.toString(),
                        "fecha_fin" to today.plusDays(30).toString(),
                        "zona_horaria" to "America/Lima",
                        "inscripcion_desde" to today.minusDays(1).toString(),
                        "inscripcion_hasta" to today.plusDays(5).toString(),
                    ),
            ),
        )
        // la misma persona en las dos ediciones: la sesión de la otra se le ve, y no debe cambiar esta
        val enrollment = ok2(call(s.student, "/api/lms/enrollments", mapOf("offering" to other)))["id"].asString()

        assertEquals(HttpStatus.OK, event(s.student, enrollment, s.course.reading, "MARKED_COMPLETE").first)
    }

    private fun ok2(result: Pair<HttpStatus, JsonNode?>): JsonNode {
        assertTrue(result.first.is2xxSuccessful, result.second.toString())
        return result.second!!
    }

    @Test
    fun `only the teacher of the offering takes attendance, and only of its students`() {
        val s = Scene()
        val outsider = newTeacher().also { assign(it, course()) }
        val foreign = enroll(newStudent(), course())["id"].asString()

        for (token in listOf(outsider, s.student)) {
            assertEquals(HttpStatus.NOT_FOUND, s.mark(token, "PRESENTE").first)
            assertEquals(HttpStatus.NOT_FOUND, call(token, "/api/lms/teaching/sessions/${s.session}/attendance").first)
        }
        assertEquals(HttpStatus.BAD_REQUEST, s.mark(s.teacher, "PRESENTE", target = foreign).first)
        assertEquals(HttpStatus.BAD_REQUEST, s.mark(s.teacher, "VOLANDO").first)
        assertTrue(ok(call(s.student, "/api/lms/classroom/enrollments/${s.enrollment}/sessions"))[0]["attendance"].isNull)
        assertTrue(call(s.student, "/api/objects/asistencia/records", mapOf("attributes" to mapOf("estado" to "PRESENTE"))).first.is4xxClientError)
        assertEquals(0, get("/api/objects/asistencia/records?size=10", outsider)["totalElements"].asInt())
    }
}
