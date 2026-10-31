package elearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// el certificado (ADR-0016 §7, RF-EVA-08): solo al cumplir las reglas, congelado al emitirse y verificable sin sesión
class CertificateApiTest : ClassroomApiTestSupport() {
    private inner class Scene {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        fun finish() {
            ok(event(student, enrollment, course.reading, "MARKED_COMPLETE"))
            ok(event(student, enrollment, course.video, "VIDEO_ENDED"))
        }

        fun request(
            token: String = student,
            name: String? = "Ana Pérez",
        ) = call(token, "/api/lms/classroom/enrollments/$enrollment/certificate", mapOf("holderName" to name))
    }

    private fun verify(code: String): Pair<HttpStatus, JsonNode?> = call(null, "/api/lms/public/certificates/$code")

    @Test
    fun `without meeting the rules there is no certificate`() {
        val s = Scene()
        ok(event(s.student, s.enrollment, s.course.reading, "MARKED_COMPLETE"))

        assertEquals(HttpStatus.CONFLICT, s.request().first)

        val still = ok(call(s.student, "/api/lms/classroom/enrollments/${s.enrollment}"))
        assertEquals("ACTIVA", still["status"].asString())
        assertTrue(still["certificateCode"].isNull)
    }

    @Test
    fun `meeting them issues it once, finishes the enrollment and anyone can verify it`() {
        val s = Scene()
        s.finish()

        val (status, issued) = s.request()
        assertEquals(HttpStatus.CREATED, status, issued.toString())
        val code = issued!!["code"].asString()
        assertTrue(Regex("^[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$").matches(code), code)
        assertEquals("Ana Pérez", issued["holderName"].asString())
        assertEquals("APROBADO", issued["result"].asString())
        assertEquals("LESSONS", issued["rules"].single()["rule"].asString())

        val (again, same) = s.request(name = "Otro Nombre")
        assertEquals(HttpStatus.OK, again)
        assertEquals(code, same!!["code"].asString())
        assertEquals("Ana Pérez", same["holderName"].asString())

        val (found, public) = verify(code)
        assertEquals(HttpStatus.OK, found)
        assertEquals(s.course.offering, public!!["offering"]["code"].asString())
        assertFalse("estudiante" in public.toString().lowercase() || "@" in public.toString(), "sin datos de cuenta")

        val after = ok(call(s.student, "/api/lms/classroom/enrollments/${s.enrollment}"))
        assertEquals("FINALIZADA", after["status"].asString())
        assertEquals(code, after["certificateCode"].asString())
    }

    @Test
    fun `a finished enrollment stays readable with its resources and takes nothing new`() {
        val s = Scene()
        s.finish()
        s.request()

        assertTrue(ok(call(s.student, "/api/lms/classroom/enrollments")).strings { it["id"] }.contains(s.enrollment))
        val lesson =
            ok(call(s.student, "/api/lms/classroom/enrollments/${s.enrollment}"))["units"].values().flatMap { it["lessons"].values() }.single {
                it["id"].asString() ==
                    s.course.reading
            }
        assertEquals(1, lesson["resources"].size(), "conserva sus materiales")
        assertEquals(HttpStatus.CONFLICT, event(s.student, s.enrollment, s.course.video, "STARTED").first)
    }

    @Test
    fun `a certificate does not change when the data behind it does`() {
        val s = Scene()
        s.finish()
        val issued = s.request().second!!
        val slug = ok(call(s.student, "/api/lms/classroom/enrollments/${s.enrollment}"))["course"]["code"].asString()
        val courseId = get("/api/objects/curso/records?codigo=$slug", admin)["content"][0]["id"].asString()

        val patched =
            client
                .patch()
                .uri("/api/objects/curso/records/$courseId")
                .header("Authorization", admin)
                .bodyValue(mapOf("attributes" to mapOf("titulo" to "Título cambiado")))
                .exchange()
                .returnResult(String::class.java)
                .status
                .value()
        assertEquals(200, patched)

        assertEquals(issued["course"]["title"].asString(), verify(issued["code"].asString()).second!!["course"]["title"].asString())
        assertEquals("Título cambiado", ok(call(s.student, "/api/lms/classroom/enrollments/${s.enrollment}"))["course"]["title"].asString())
    }

    @Test
    fun `the request is checked and only the student asks`() {
        val s = Scene()
        s.finish()

        assertEquals(HttpStatus.BAD_REQUEST, s.request(name = " ").first)
        assertEquals(HttpStatus.BAD_REQUEST, s.request(name = "x".repeat(121)).first)
        assertEquals(HttpStatus.NOT_FOUND, s.request(token = newStudent()).first)
        assertEquals(HttpStatus.NOT_FOUND, s.request(token = newTeacher().also { assign(it, s.course) }).first)
        assertEquals(HttpStatus.NOT_FOUND, verify("AAAA-BBBB-CCCC").first)
        assertEquals(HttpStatus.NOT_FOUND, verify("no-es-un-codigo").first)
    }

    @Test
    fun `simultaneous requests issue a single certificate`() {
        val s = Scene()
        s.finish()
        val pool =
            java.util.concurrent.Executors
                .newFixedThreadPool(4)
        val codes =
            try {
                pool.invokeAll(List(4) { java.util.concurrent.Callable { s.request().second!!["code"].asString() } }).map { it.get() }
            } finally {
                pool.shutdown()
            }

        assertEquals(1, codes.toSet().size, codes.toString())
    }
}
