package elearning.learning

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import wasichai.core.data.RecordRequest
import java.util.UUID

// la API de lectura del aula (ADR-0012, docs/api/lms.md): mis cursos y el aula de una matrícula, siempre la propia
class ClassroomApiTest : ClassroomApiTestSupport() {
    @Test
    fun `my courses lists only my active enrollments, the soonest first, with their progress`() {
        val later = course(startsIn = 20)
        val sooner = course(startsIn = 2)
        val withdrawn = course()
        val student = newStudent()
        val laterEnrollment = enroll(student, later)["id"].asString()
        val soonerEnrollment = enroll(student, sooner)["id"].asString()
        val withdrawnEnrollment = enroll(student, withdrawn)["id"].asString()
        runBlocking {
            records.asPlatform(ORG) {
                records.patch("matricula", UUID.fromString(withdrawnEnrollment), RecordRequest(mapOf("estado" to "RETIRADA")), null, null)
            }
        }
        enroll(newStudent(), sooner)
        ok(event(student, laterEnrollment, later.reading, "MARKED_COMPLETE"))

        val mine = ok(call(student, "/api/lms/classroom/enrollments"))

        assertEquals(listOf(soonerEnrollment, laterEnrollment), mine.strings { it["id"] })
        assertEquals(1, mine[1]["progress"]["completedLessons"].asInt())
        assertEquals(later.video, mine[1]["nextLesson"]["id"].asString())
        assertEquals(sooner.reading, mine[0]["nextLesson"]["id"].asString())
    }

    @Test
    fun `the classroom shows each unit with its lessons, how they complete, their progress and their resources`() {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        ok(event(student, enrollment, course.reading, "STARTED"))
        ok(event(student, enrollment, course.video, "VIDEO_ENDED"))

        val classroom = ok(call(student, "/api/lms/classroom/enrollments/$enrollment"))

        assertEquals(enrollment, classroom["id"].asString())
        assertEquals(course.offering, classroom["offering"]["code"].asString())
        assertEquals(1, classroom["progress"]["completedLessons"].asInt())
        assertEquals(course.reading, classroom["nextLesson"]["id"].asString())
        val unit = classroom["units"].single()
        assertEquals(course.unit, unit["id"].asString())
        assertEquals(1, unit["order"].asInt())
        // lo que no tiene valor viene como null, no ausente: el contrato lo exige así
        assertTrue(unit.has("description") && unit["description"].isNull, unit.toString())
        assertEquals(
            listOf(
                1,
                2,
                1,
                50,
            ),
            listOf(
                unit["progress"]["completedLessons"],
                unit["progress"]["totalLessons"],
                unit["progress"]["completedLessons"],
                unit["progress"]["percent"],
            ).map {
                it.asInt()
            },
        )

        val (reading, video, assessment) = unit["lessons"].values().toList()
        assertEquals(listOf(course.reading, course.video, course.assessment), listOf(reading, video, assessment).map { it["id"].asString() })
        assertEquals(listOf("EXPLICIT", "VIDEO_END", "NOT_YET"), listOf(reading, video, assessment).map { it["completion"].asString() })
        assertEquals(listOf("INICIADA", "COMPLETADA", "NO_INICIADA"), listOf(reading, video, assessment).map { it["progress"]["status"].asString() })
        assertEquals("FIN_DE_VIDEO", video["progress"]["evidence"].asString())
        assertTrue(reading.has("durationMinutes") && reading["durationMinutes"].isNull, reading.toString())
        assertTrue(reading.has("summary") && reading["summary"].isNull, reading.toString())
        assertTrue(assessment["progress"].has("startedAt") && assessment["progress"]["startedAt"].isNull, assessment.toString())

        val resource = reading["resources"].single()
        assertEquals(course.resource, resource["id"].asString())
        assertEquals("Lectura de la unidad", resource["title"].asString())
        assertEquals("HTML", resource["type"].asString())
        assertEquals(1, resource["version"].asInt())
        assertEquals("lectura.html", resource["file"]["name"].asString())
        assertTrue(resource["file"]["size"].asInt() > 0)
        val url = resource["file"]["url"].asString()
        assertEquals("/api/objects/version_recurso/records/${course.resource}/files/archivo", url)
        val html =
            client
                .get()
                .uri(
                    url,
                ).header("Authorization", student)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
        assertTrue("Lectura" in html!!)
        assertEquals(0, video["resources"].size())
    }

    @Test
    fun `nobody opens someone else's classroom, not even a teacher who sees every enrollment`() {
        val course = course()
        val enrollment = enroll(newStudent(), course)["id"].asString()

        assertEquals(HttpStatus.NOT_FOUND, call(newStudent(), "/api/lms/classroom/enrollments/$enrollment").first)
        assertEquals(HttpStatus.NOT_FOUND, call(demoUser("docente"), "/api/lms/classroom/enrollments/$enrollment").first)
        assertEquals(HttpStatus.NOT_FOUND, call(newStudent(), "/api/lms/classroom/enrollments/${UUID.randomUUID()}").first)
        assertEquals(0, ok(call(demoUser("docente"), "/api/lms/classroom/enrollments")).size())
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, "/api/lms/classroom/enrollments/$enrollment").first)
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, "/api/lms/classroom/enrollments").first)
    }
}
