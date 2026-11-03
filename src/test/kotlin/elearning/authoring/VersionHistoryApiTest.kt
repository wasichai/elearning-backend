package elearning.authoring

import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordRequest
import java.time.Instant
import java.util.UUID

// el historial de estados de una versión (#259, ADR-0032): cada cambio de estado deja su fila en lms.transicion_version,
// sea cual sea la vía, y el detalle del estudio y de la bandeja la devuelven con quién y cuándo
class VersionHistoryApiTest : ClassroomApiTestSupport() {
    @Autowired
    private lateinit var db: DatabaseClient

    private fun account(
        name: String,
        vararg roles: String,
    ): String {
        val email = "${uniqueName("hist")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to name, "password" to DEMO_PASSWORD, "roles" to roles.toList()))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    private fun courseId(code: String) = get("/api/objects/curso/records?codigo=$code", admin)["content"][0]["id"].asString()

    private fun draft(author: String): String {
        val (status, body) = call(author, "/api/lms/studio/courses/${courseId(course().offering)}/versions", mapOf("fromVersionId" to null))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        return body!!["id"].asString()
    }

    private fun submit(
        author: String,
        id: String,
    ) = assertEquals("EN_REVISION", ok(call(author, "/api/lms/studio/versions/$id/submit", emptyMap()))["status"].asString())

    private fun decide(
        reviewer: String,
        id: String,
        decision: String,
        reason: String? = null,
    ) = ok(call(reviewer, "/api/lms/review/versions/$id/decision", mapOf("decision" to decision, "reason" to reason)))["status"].asString()

    private fun patch(
        token: String,
        id: String,
        attributes: Map<String, Any?>,
    ): HttpStatus =
        HttpStatus.valueOf(
            client
                .patch()
                .uri("/api/objects/version_curso/records/$id")
                .header("Authorization", token)
                .bodyValue(mapOf("attributes" to attributes))
                .exchange()
                .returnResult(String::class.java)
                .status
                .value(),
        )

    // (desde, hacia, quién) de cada fila, en orden
    private fun steps(history: JsonNode): List<Triple<String?, String, String?>> =
        history.values().map {
            Triple(
                it["from"]
                    .takeUnless { f ->
                        f.isNull
                    }?.asString(),
                it["to"].asString(),
                it["by"].takeUnless { b -> b.isNull }?.asString(),
            )
        }

    @Test
    fun `the studio and the inbox show every state a version went through, who moved it and when`() {
        val author = account("Ana Autora", "CREADOR")
        val reviewer = account("Rubén Revisor", "REVISOR")
        val other = account("Otra Creadora", "CREADOR")
        val me = userId(author)
        val rev = userId(reviewer)
        val start = Instant.now()

        val id = draft(author)
        submit(author, id)
        assertEquals("BORRADOR", decide(reviewer, id, "PEDIR_CAMBIOS", "Añade la bibliografía"))
        submit(author, id)
        assertEquals("APROBADA", decide(reviewer, id, "APROBAR"))
        try {
            assertEquals("PUBLICADA", ok(call(admin, "/api/lms/review/versions/$id/publish", emptyMap()))["status"].asString())

            val expected =
                listOf(
                    Triple(null, "BORRADOR", me),
                    Triple("BORRADOR", "EN_REVISION", me),
                    Triple("EN_REVISION", "BORRADOR", rev),
                    Triple("BORRADOR", "EN_REVISION", me),
                    Triple("EN_REVISION", "APROBADA", rev),
                    Triple("APROBADA", "PUBLICADA", userId(admin)),
                )
            val studio = ok(call(author, "/api/lms/studio/versions/$id"))["history"]
            val inbox = ok(call(reviewer, "/api/lms/review/versions/$id"))["history"]
            assertEquals(expected, steps(studio))
            assertEquals(expected, steps(inbox))
            assertEquals(listOf("Ana Autora", "Ana Autora", "Rubén Revisor"), studio.values().take(3).map { it["byName"].asString() })
            // cuándo: en orden, y entre el principio de la prueba y ahora
            val at = studio.values().map { Instant.parse(it["at"].asString()) }
            assertEquals(at.sorted(), at)
            assertTrue(at.all { !it.isBefore(start) && !it.isAfter(Instant.now()) }, at.toString())

            // las mismas reglas de lectura que el detalle: a quien no puede, 404
            assertEquals(HttpStatus.NOT_FOUND, call(other, "/api/lms/studio/versions/$id").first)
            assertEquals(HttpStatus.FORBIDDEN, call(other, "/api/lms/review/versions/$id").first)
            assertEquals(HttpStatus.FORBIDDEN, call(author, "/api/lms/review/versions/$id").first)
        } finally {
            // fuera del catálogo de las demás pruebas
            runBlocking {
                records.asPlatform(ORG) { records.patch("version_curso", UUID.fromString(id), RecordRequest(mapOf("estado" to "RETIRADA")), null, null) }
            }
        }
    }

    @Test
    fun `the record api and the platform leave their row too, and a write that keeps the state leaves none`() {
        val author = account("Ana Autora", "CREADOR")
        val reviewer = account("Rubén Revisor", "REVISOR")
        val id = draft(author)
        submit(author, id)

        // el autor la retira de revisión por la API de registros; asignar el revisor no cambia el estado
        assertEquals(HttpStatus.OK, patch(author, id, mapOf("estado" to "BORRADOR")))
        assertEquals(HttpStatus.OK, patch(admin, id, mapOf("revisor_id" to userId(reviewer))))
        assertEquals(HttpStatus.OK, patch(author, id, mapOf("estado" to "BORRADOR")))
        // la plataforma no tiene usuario: by y byName nulos
        runBlocking {
            records.asPlatform(ORG) { records.patch("version_curso", UUID.fromString(id), RecordRequest(mapOf("estado" to "RETIRADA")), null, null) }
        }

        val history = ok(call(author, "/api/lms/studio/versions/$id"))["history"]
        val me = userId(author)
        assertEquals(
            listOf(
                Triple(null, "BORRADOR", me),
                Triple("BORRADOR", "EN_REVISION", me),
                Triple("EN_REVISION", "BORRADOR", me),
                Triple("BORRADOR", "RETIRADA", null),
            ),
            steps(history),
        )
        assertTrue(history.values().last()["byName"].isNull)
    }

    @Test
    fun `a transition is never changed nor deleted`() {
        val author = account("Ana Autora", "CREADOR")
        val id = UUID.fromString(draft(author))

        // bloquear está bien en una prueba: aquí no hay hilo de WebFlux que proteger
        fun execute(sql: String) =
            db
                .sql(sql)
                .bind("version", id)
                .fetch()
                .rowsUpdated()
                .block()
        assertThrows<Exception> { execute("UPDATE lms.transicion_version SET hacia = 'PUBLICADA' WHERE version_curso_id = :version") }
        assertThrows<Exception> { execute("DELETE FROM lms.transicion_version WHERE version_curso_id = :version") }
        assertEquals(1, ok(call(author, "/api/lms/studio/versions/$id"))["history"].size())
    }
}
