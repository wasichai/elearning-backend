package elearning.authoring

import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordRequest
import java.util.UUID

// una versión sin lecciones no avanza en el flujo editorial (#346): la regla está en EditorialWorkflowGuard, así que vale por
// el estudio, la bandeja y la API de registros. el borrador vacío sí se crea (#270): sin él no se añade la primera unidad
class EmptyVersionApiTest : ClassroomApiTestSupport() {
    private fun account(vararg roles: String): String {
        val email = "${uniqueName("vacia")}@demo.wasichai-learning.test"
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

    private fun created(result: Pair<HttpStatus, JsonNode?>): String {
        assertEquals(HttpStatus.CREATED, result.first, result.second.toString())
        return result.second!!["id"].asString()
    }

    private fun newCourse(token: String): String {
        val slug = uniqueName("vacia-")
        return created(
            call(
                token,
                "/api/objects/curso/records",
                mapOf("attributes" to mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso sin temario")),
            ),
        )
    }

    // el primer borrador vacío de un curso nuevo, por el estudio
    private fun emptyDraft(author: String): String =
        created(call(author, "/api/lms/studio/courses/${newCourse(author)}/versions", mapOf("fromVersionId" to null)))

    private fun unit(
        author: String,
        version: String,
    ) = created(call(author, "/api/objects/unidad/records", mapOf("attributes" to mapOf("version_curso" to version, "orden" to 1, "titulo" to "Unidad"))))

    private fun lesson(
        author: String,
        unit: String,
    ) = created(
        call(
            author,
            "/api/objects/leccion/records",
            mapOf("attributes" to mapOf("unidad" to unit, "orden" to 1, "titulo" to "Lección", "tipo" to "LECTURA")),
        ),
    )

    private fun submit(
        token: String,
        version: String,
    ) = call(token, "/api/lms/studio/versions/$version/submit", emptyMap())

    private fun patch(
        token: String,
        version: String,
        attributes: Map<String, Any?>,
    ): Pair<HttpStatus, JsonNode?> {
        val result =
            client
                .patch()
                .uri("/api/objects/version_curso/records/$version")
                .header("Authorization", token)
                .bodyValue(mapOf("attributes" to attributes))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(result.status.value()) to result.responseBody?.let { json.readTree(it) }
    }

    private fun state(version: String) = get("/api/objects/version_curso/records/$version", admin)["attributes"]["estado"].asString()

    // la plataforma no pasa por la guarda: así queda una versión vacía como las enviadas antes de esta regla
    private fun moveAsPlatform(
        version: String,
        state: String,
    ) = runBlocking {
        records.asPlatform(ORG) { records.patch("version_curso", UUID.fromString(version), RecordRequest(mapOf("estado" to state)), null, null) }
    }

    private fun assertRefused(result: Pair<HttpStatus, JsonNode?>) {
        assertEquals(HttpStatus.CONFLICT, result.first, result.second.toString())
        assertTrue(result.second!!["detail"].asString().contains("without lessons"), result.second.toString())
    }

    @Test
    fun `a version without lessons is created but not submitted, with or without units`() {
        val author = account("CREADOR")
        val version = emptyDraft(author)
        assertEquals("BORRADOR", state(version))

        assertRefused(submit(author, version))
        val unit = unit(author, version)
        assertRefused(submit(author, version))
        assertEquals("BORRADOR", state(version))

        lesson(author, unit)
        assertEquals("EN_REVISION", ok(submit(author, version))["status"].asString())
    }

    @Test
    fun `deleting the last lesson empties the version again`() {
        val author = account("CREADOR")
        val version = emptyDraft(author)
        val lesson = lesson(author, unit(author, version))
        val deleted =
            client
                .delete()
                .uri("/api/objects/leccion/records/$lesson")
                .header("Authorization", author)
                .exchange()
                .returnResult(String::class.java)
                .status
        assertTrue(deleted.is2xxSuccessful, deleted.toString())

        assertRefused(submit(author, version))
    }

    @Test
    fun `the record API does not skip the rule, by PATCH nor by PUT`() {
        val author = account("CREADOR")
        val version = emptyDraft(author)

        assertRefused(patch(author, version, mapOf("estado" to "EN_REVISION")))
        assertRefused(patch(admin, version, mapOf("estado" to "EN_REVISION")))
        val stored = get("/api/objects/version_curso/records/$version", admin)["attributes"]
        val whole =
            mapOf(
                "curso" to stored["curso"].asString(),
                "numero" to 1,
                "modalidad" to "ASINCRONA",
                "semanas" to 1,
                "estado" to "EN_REVISION",
                "autor_id" to userId(author),
            )
        assertRefused(put(author, "/api/objects/version_curso/records/$version", mapOf("attributes" to whole)))
        assertEquals("BORRADOR", state(version))
    }

    @Test
    fun `a version is not born past draft, where it would have no lessons`() {
        val author = account("CREADOR")
        val course = newCourse(author)
        val attributes = mapOf("curso" to course, "numero" to 1, "modalidad" to "ASINCRONA", "semanas" to 1, "autor_id" to userId(author))

        assertRefused(call(author, "/api/objects/version_curso/records", mapOf("attributes" to attributes + ("estado" to "EN_REVISION"))))
        for (state in listOf("EN_REVISION", "PUBLICADA")) {
            assertRefused(call(admin, "/api/objects/version_curso/records", mapOf("attributes" to attributes + ("estado" to state))))
        }
        // sin la acción sigue siendo 403: la regla no dice nada a quien no puede
        val published = call(author, "/api/objects/version_curso/records", mapOf("attributes" to attributes + ("estado" to "PUBLICADA")))
        assertEquals(HttpStatus.FORBIDDEN, published.first)
    }

    @Test
    fun `whoever lacks the action still gets 403 and not the rule`() {
        val author = account("CREADOR")
        val version = emptyDraft(author)

        assertEquals(HttpStatus.FORBIDDEN, patch(account("REVISOR"), version, mapOf("estado" to "EN_REVISION")).first)
        assertEquals(HttpStatus.FORBIDDEN, patch(newStudent(), version, mapOf("estado" to "EN_REVISION")).first)
        assertEquals(HttpStatus.FORBIDDEN, patch(account("DOCENTE"), version, mapOf("estado" to "EN_REVISION")).first)
        // el estudio responde por la versión propia: la ajena no existe
        assertEquals(HttpStatus.NOT_FOUND, submit(account("CREADOR"), version).first)
        assertEquals("BORRADOR", state(version))
    }

    @Test
    fun `an empty version already in review is not approved, but can leave the review`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val version = emptyDraft(author)
        moveAsPlatform(version, "EN_REVISION")

        assertRefused(call(reviewer, "/api/lms/review/versions/$version/decision", mapOf("decision" to "APROBAR")))
        assertEquals("EN_REVISION", state(version))
        // el autor no se aprueba lo suyo: 403 antes que la regla
        assertEquals(HttpStatus.FORBIDDEN, patch(author, version, mapOf("estado" to "APROBADA")).first)

        val changes = call(reviewer, "/api/lms/review/versions/$version/decision", mapOf("decision" to "PEDIR_CAMBIOS", "reason" to "Sin temario"))
        assertEquals("BORRADOR", ok(changes)["status"].asString())

        moveAsPlatform(version, "EN_REVISION")
        assertEquals(HttpStatus.OK, patch(author, version, mapOf("estado" to "BORRADOR")).first)

        moveAsPlatform(version, "EN_REVISION")
        val rejected = call(reviewer, "/api/lms/review/versions/$version/decision", mapOf("decision" to "RECHAZAR", "reason" to "Sin temario"))
        assertEquals("RETIRADA", ok(rejected)["status"].asString())
    }

    @Test
    fun `an empty version already approved is not published`() {
        val author = account("CREADOR")
        val version = emptyDraft(author)
        moveAsPlatform(version, "APROBADA")
        try {
            assertRefused(call(admin, "/api/lms/review/versions/$version/publish", emptyMap()))
            assertRefused(patch(admin, version, mapOf("estado" to "PUBLICADA")))
            assertEquals("APROBADA", state(version))
            assertEquals(HttpStatus.FORBIDDEN, call(author, "/api/lms/review/versions/$version/publish", emptyMap()).first)
        } finally {
            moveAsPlatform(version, "RETIRADA")
        }
    }

    @Test
    fun `an empty version already published can be retired`() {
        val version = emptyDraft(account("CREADOR"))
        moveAsPlatform(version, "PUBLICADA")

        assertEquals(HttpStatus.OK, patch(admin, version, mapOf("estado" to "RETIRADA")).first)
    }
}
