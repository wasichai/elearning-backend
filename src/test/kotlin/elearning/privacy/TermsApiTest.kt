package elearning.privacy

import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import java.util.UUID

// los términos de uso (ADR-0037, RNF-SEG-11): sin aceptación vigente no hay matrícula ni progreso, con un 403 que dice
// por qué; el catálogo público sigue abierto. publicar otra versión no cambia las aceptaciones que ya había
class TermsApiTest : ClassroomApiTestSupport() {
    @Autowired
    private lateinit var db: DatabaseClient

    private fun current(token: String): Int = ok(call(token, "/api/lms/terms"))["current"]["version"].asInt()

    private fun accept(
        token: String,
        version: Int = current(token),
    ) = call(token, "/api/lms/terms/acceptance", mapOf("version" to version))

    private fun withdraw(token: String): Pair<HttpStatus, JsonNode?> {
        val r =
            client
                .delete()
                .uri("/api/lms/terms/acceptance")
                .header("Authorization", token)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(r.status.value()) to r.responseBody?.let { runCatching { json.readTree(it) }.getOrNull() }
    }

    private fun publish(
        token: String,
        days: Int = 365,
    ) = call(token, "/api/lms/terms", mapOf("text" to "Términos de prueba ${UUID.randomUUID()}", "retentionDays" to days))

    @Test
    fun `without a valid acceptance enrolling is forbidden with a clear reason and the public catalog stays open`() {
        val course = course()
        val student = newStudent(acceptingTerms = false)

        val (status, body) = call(student, "/api/lms/enrollments", mapOf("offering" to course.offering))

        assertEquals(HttpStatus.FORBIDDEN, status, body.toString())
        assertTrue("términos de uso" in body!!["detail"].asString(), body.toString())
        assertEquals(0, ok(call(student, "/api/lms/classroom/enrollments")).size())
        assertEquals(HttpStatus.OK, call(null, "/api/lms/public/catalog/courses").first)

        assertEquals(HttpStatus.OK, accept(student).first)
        assertEquals(HttpStatus.CREATED, call(student, "/api/lms/enrollments", mapOf("offering" to course.offering)).first)
    }

    @Test
    fun `a withdrawn acceptance stops the progress, and the student sees why`() {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        assertEquals(HttpStatus.OK, event(student, enrollment, course.reading, "STARTED").first)

        val (status, withdrawal) = withdraw(student)
        assertEquals(HttpStatus.ACCEPTED, status, withdrawal.toString())
        assertNotNull(withdrawal!!["jobId"].asString())
        // repetirlo, mientras la supresión no corre, es el mismo trabajo
        assertEquals(withdrawal["jobId"], withdraw(student).second!!["jobId"])

        val (refused, reason) = event(student, enrollment, course.reading, "MARKED_COMPLETE")
        assertEquals(HttpStatus.FORBIDDEN, refused, reason.toString())
        assertTrue("términos de uso" in reason!!["detail"].asString())
        val mine = ok(call(student, "/api/lms/terms"))["acceptance"]
        assertFalse(mine["valid"].asBoolean())
        assertFalse(mine["withdrawnAt"].isNull)
        // aceptar otra vez espera a que la supresión termine
        assertEquals(HttpStatus.CONFLICT, accept(student).first)
    }

    @Test
    fun `publishing a new version leaves the earlier acceptances as they were`() {
        val student = newStudent()
        val before = ok(call(student, "/api/lms/terms"))["acceptance"]
        assertTrue(before["valid"].asBoolean())

        val (status, published) = publish(admin, days = 30)
        assertEquals(HttpStatus.CREATED, status, published.toString())
        assertEquals(before["version"].asInt() + 1, published!!["version"].asInt())

        val after = ok(call(student, "/api/lms/terms"))
        assertEquals(published["version"].asInt(), after["current"]["version"].asInt())
        assertEquals(before, after["acceptance"])
        assertEquals(HttpStatus.CREATED, call(student, "/api/lms/enrollments", mapOf("offering" to course().offering)).first)

        // la versión anterior no se acepta ya; la nueva, sí, con su propio plazo
        assertEquals(HttpStatus.CONFLICT, accept(student, version = before["version"].asInt()).first)
        val renewed = ok(accept(student))
        assertEquals(published["version"].asInt(), renewed["version"].asInt())
        // y una versión publicada no se cambia, ni siquiera con SQL
        val changed =
            runCatching {
                runBlocking {
                    db
                        .sql("UPDATE lms.terminos_uso SET plazo_dias = 1")
                        .fetch()
                        .rowsUpdated()
                        .awaitSingle()
                }
            }
        assertTrue(changed.isFailure)
    }

    @Test
    fun `only who manages the organization publishes terms, and they are validated`() {
        val student = newStudent()

        assertEquals(HttpStatus.FORBIDDEN, publish(student).first)
        assertEquals(HttpStatus.FORBIDDEN, publish(newTeacher()).first)
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, "/api/lms/terms", mapOf("text" to "x", "retentionDays" to 1)).first)
        assertEquals(HttpStatus.BAD_REQUEST, call(admin, "/api/lms/terms", mapOf("text" to " ", "retentionDays" to 0)).first)
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, "/api/lms/terms").first)
    }
}
