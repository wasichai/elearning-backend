package elearning.learning

import elearning.jobs.JobQueue
import elearning.jobs.JobRunner
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import wasichai.core.data.RecordRequest
import java.util.UUID

// el aviso de publicación de contenido (RF-AUL-09): publicar una versión nueva de un curso avisa a quien cursa una
// versión anterior de ese curso con su matrícula activa, y a nadie más. el aviso sale de la cola persistente, que en las
// pruebas se mueve a mano con runOnce
class PublicationNoticesApiTest : ClassroomApiTestSupport() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var queue: JobQueue

    private fun drain() = runBlocking { while (runner.runOnce() > 0) Unit }

    private fun account(vararg roles: String): String {
        val email = "${uniqueName("pub")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Persona", "password" to DEMO_PASSWORD, "roles" to roles.toList()))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    private fun inbox(token: String) =
        client
            .get()
            .uri("/api/auth/me/notifications")
            .header("Authorization", token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            .orEmpty()

    // cuántos avisos de este curso tiene la persona: el código del curso va solo en el título del aviso
    private fun notices(
        token: String,
        code: String,
    ) = inbox(token).split(code).size - 1

    private inner class Scene {
        val course = course()
        val code = course.offering
        val student = newStudent().also { enroll(it, course) }
        val withdrawn = newStudent().also { withdraw(enroll(it, course)["id"].asString()) }
        val elsewhere = newStudent().also { enroll(it, course()) }
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val courseId: String = get("/api/objects/curso/records?codigo=$code", admin)["content"][0]["id"].asString()

        // una versión nueva del curso, por el estudio y la bandeja: copiada, enviada, aprobada y lista para publicar
        fun approvedVersion(): String {
            val (status, body) = call(author, "/api/lms/studio/courses/$courseId/versions", mapOf("fromVersionId" to null))
            assertEquals(HttpStatus.CREATED, status, body.toString())
            val id = body!!["id"].asString()
            ok(call(author, "/api/lms/studio/versions/$id/submit", emptyMap()))
            ok(call(reviewer, "/api/lms/review/versions/$id/decision", mapOf("decision" to "APROBAR", "reason" to null)))
            return id
        }

        fun publish(id: String) = call(admin, "/api/lms/review/versions/$id/publish", emptyMap())
    }

    private fun withdraw(enrollment: String) =
        runBlocking {
            records.asPlatform(ORG) {
                records.patch("matricula", UUID.fromString(enrollment), RecordRequest(mapOf("estado" to "RETIRADA")), "Baja de prueba", null)
            }
        }

    // fuera del catálogo de las demás pruebas
    private fun retire(version: String) =
        runBlocking {
            records.asPlatform(ORG) { records.patch("version_curso", UUID.fromString(version), RecordRequest(mapOf("estado" to "RETIRADA")), null, null) }
        }

    @Test
    fun `publishing a new version warns the active students of that course and nobody else`() {
        val s = Scene()
        drain()
        assertEquals(0, notices(s.student, s.code), "publicar la primera versión no avisa a nadie: nadie la cursaba")

        val version = s.approvedVersion()
        try {
            assertEquals("PUBLICADA", ok(s.publish(version))["status"].asString())
            drain()

            assertEquals(1, notices(s.student, s.code), "quien cursa la versión anterior recibe el aviso")
            assertEquals(0, notices(s.withdrawn, s.code), "una matrícula retirada, no")
            assertEquals(0, notices(s.elsewhere, s.code), "quien cursa otro curso, no")
            assertEquals(0, notices(s.author, s.code), "ni quien la escribió")
            assertEquals(0, notices(s.reviewer, s.code), "ni quien la aprobó")
            assertEquals(0, notices(admin, s.code), "ni quien la publicó")
        } finally {
            retire(version)
        }
    }

    @Test
    fun `publishing twice warns once`() {
        val s = Scene()
        val version = s.approvedVersion()
        try {
            ok(s.publish(version))
            drain()
            assertEquals(HttpStatus.CONFLICT, s.publish(version).first)
            // el mismo trabajo otra vez (una réplica que murió tras avisar, un reintento): el aviso tiene clave y no se repite
            runBlocking { queue.enqueue(ORG, PublicationNotices.TYPE, PublicationNotices.payload(UUID.fromString(version))) }
            drain()

            assertEquals(1, notices(s.student, s.code))
        } finally {
            retire(version)
        }
    }

    @Test
    fun `a rejected publication warns nobody`() {
        val s = Scene()
        val version = s.approvedVersion()
        try {
            assertEquals(HttpStatus.FORBIDDEN, call(s.reviewer, "/api/lms/review/versions/$version/publish", emptyMap()).first)
            drain()

            assertEquals(0, notices(s.student, s.code))
        } finally {
            retire(version)
        }
    }
}
