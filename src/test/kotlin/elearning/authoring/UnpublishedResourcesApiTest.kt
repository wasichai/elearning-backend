package elearning.authoring

import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import wasichai.core.data.RecordRequest
import java.util.UUID

// #366 (ADR-0041): un curso no llega a revisión, ni se aprueba ni se publica con versiones de recurso sin publicar en sus
// lecciones, por el estudio, la bandeja ni la API de registros; una versión de recurso enlazada desde una versión de curso
// fuera de borrador no cambia; y el estudiante nunca lee una versión de recurso que no está PUBLICADA. el temario lo pone
// la plataforma como lo deja el borrador de lección de #283 (LessonDraftWriter): recurso y version_recurso en BORRADOR a
// nombre de la autora, enlazados a la lección
class UnpublishedResourcesApiTest : ClassroomApiTestSupport() {
    private fun account(vararg roles: String): String {
        val email = "${uniqueName("rec")}@demo.wasichai-learning.test"
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

    private class Scene(
        val version: String,
        val resourceVersion: String,
    )

    // un borrador de curso de la autora, abierto en el estudio, con una lección que enlaza una versión de recurso en
    // BORRADOR a su nombre, puestas por la plataforma como las deja el borrador de #283
    private fun generatedLike(author: String): Scene {
        val slug = uniqueName("rec-")
        val attributes = mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso con recursos")
        val (created, course) = call(author, "/api/objects/curso/records", mapOf("attributes" to attributes))
        assertEquals(HttpStatus.CREATED, created, course.toString())
        val (opened, draft) = call(author, "/api/lms/studio/courses/${course!!["id"].asString()}/versions", mapOf("fromVersionId" to null))
        assertEquals(HttpStatus.CREATED, opened, draft.toString())
        val version = draft!!["id"].asString()
        val authorId = userId(author)
        return runBlocking {
            records.asPlatform(ORG) {
                val unit = records.create("unidad", RecordRequest(mapOf("version_curso" to version, "orden" to 1, "titulo" to "Unidad")))
                val lesson = records.create("leccion", RecordRequest(mapOf("unidad" to unit.id, "orden" to 1, "titulo" to LESSON, "tipo" to "LECTURA")))
                val resource = records.create("recurso", RecordRequest(mapOf("codigo" to "ia-$slug", "titulo" to LESSON, "tipo" to "HTML", "idioma" to "es")))
                val resourceVersion =
                    records.create(
                        "version_recurso",
                        RecordRequest(mapOf("recurso" to resource.id, "numero" to 1, "autor_id" to authorId, "estado" to "BORRADOR")),
                    )
                records.create("recurso_leccion", RecordRequest(mapOf("leccion" to lesson.id, "version_recurso" to resourceVersion.id, "orden" to 1)))
                Scene(version, resourceVersion.id)
            }
        }
    }

    private fun moveAsPlatform(
        objectName: String,
        id: String,
        state: String,
    ) = runBlocking {
        records.asPlatform(ORG) { records.patch(objectName, UUID.fromString(id), RecordRequest(mapOf("estado" to state)), null, null) }
    }

    private fun state(
        objectName: String,
        id: String,
    ) = get("/api/objects/$objectName/records/$id", admin)["attributes"]["estado"].asString()

    private fun patch(
        token: String,
        objectName: String,
        id: String,
        attributes: Map<String, Any?>,
        reason: String? = null,
    ): Pair<Int, String> {
        val spec =
            client
                .patch()
                .uri("/api/objects/$objectName/records/$id")
                .header("Authorization", token)
        reason?.let { spec.header("X-Change-Reason", it) }
        val r =
            spec
                .bodyValue(mapOf("attributes" to attributes))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return r.status.value() to r.responseBody.orEmpty()
    }

    private fun delete(
        token: String,
        objectName: String,
        id: String,
    ): Int =
        client
            .delete()
            .uri("/api/objects/$objectName/records/$id")
            .header("Authorization", token)
            .exchange()
            .returnResult(String::class.java)
            .status
            .value()

    private fun submit(
        token: String,
        version: String,
    ) = call(token, "/api/lms/studio/versions/$version/submit", emptyMap())

    private fun decide(
        token: String,
        version: String,
        decision: String,
    ) = call(token, "/api/lms/review/versions/$version/decision", mapOf("decision" to decision, "reason" to "Motivo"))

    @Test
    fun `a draft with an unpublished resource is not submitted by the studio nor the records API, and names the lesson`() {
        val author = account("CREADOR")
        val scene = generatedLike(author)
        try {
            val (studio, body) = submit(author, scene.version)
            assertEquals(HttpStatus.CONFLICT, studio, body.toString())
            assertTrue(body.toString().contains(LESSON), body.toString())
            assertTrue(body.toString().contains(scene.resourceVersion), body.toString())

            val (records, message) = patch(author, "version_curso", scene.version, mapOf("estado" to "EN_REVISION"))
            assertEquals(HttpStatus.CONFLICT.value(), records, message)
            assertTrue(message.contains(LESSON), message)
            assertEquals("BORRADOR", state("version_curso", scene.version))
        } finally {
            moveAsPlatform("version_curso", scene.version, "RETIRADA")
        }
    }

    @Test
    fun `a course version in review with an unpublished resource is not approved in the inbox nor published`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val scene = generatedLike(author)
        try {
            // datos de antes de #366: la plataforma la llevó a revisión con el recurso en borrador
            moveAsPlatform("version_curso", scene.version, "EN_REVISION")
            val (approved, body) = decide(reviewer, scene.version, "APROBAR")
            assertEquals(HttpStatus.CONFLICT, approved, body.toString())
            assertEquals("EN_REVISION", state("version_curso", scene.version))

            moveAsPlatform("version_curso", scene.version, "APROBADA")
            val (published, message) = patch(admin, "version_curso", scene.version, mapOf("estado" to "PUBLICADA"))
            assertEquals(HttpStatus.CONFLICT.value(), published, message)
            val (byInbox, published2) = call(admin, "/api/lms/review/versions/${scene.version}/publish", emptyMap())
            assertEquals(HttpStatus.CONFLICT, byInbox, published2.toString())
            assertEquals("APROBADA", state("version_curso", scene.version))
        } finally {
            moveAsPlatform("version_curso", scene.version, "RETIRADA")
        }
    }

    @Test
    fun `a resource version linked from a course version beyond draft does not change, even as a draft`() {
        val author = account("CREADOR")
        val scene = generatedLike(author)
        try {
            // en borrador, con el curso en borrador, la autora la edita
            assertEquals(HttpStatus.OK.value(), patch(author, "version_recurso", scene.resourceVersion, mapOf("origen" to "editado")).first)

            moveAsPlatform("version_curso", scene.version, "PUBLICADA")
            val (edited, message) = patch(author, "version_recurso", scene.resourceVersion, mapOf("origen" to "otra vez"))
            assertEquals(HttpStatus.CONFLICT.value(), edited, message)
            assertEquals(HttpStatus.CONFLICT.value(), delete(author, "version_recurso", scene.resourceVersion))
            assertEquals("editado", get("/api/objects/version_recurso/records/${scene.resourceVersion}", admin)["attributes"]["origen"].asString())
            // su flujo sí sigue: se envía a revisión para publicarla
            assertEquals(HttpStatus.OK.value(), patch(author, "version_recurso", scene.resourceVersion, mapOf("estado" to "EN_REVISION")).first)
        } finally {
            moveAsPlatform("version_curso", scene.version, "RETIRADA")
        }
    }

    @Test
    fun `the author submits, a reviewer approves and a publisher publishes each resource first, then the course goes through`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val scene = generatedLike(author)
        try {
            assertEquals(HttpStatus.OK.value(), patch(author, "version_recurso", scene.resourceVersion, mapOf("estado" to "EN_REVISION")).first)
            assertEquals(HttpStatus.CONFLICT, submit(author, scene.version).first)
            assertEquals(HttpStatus.OK.value(), patch(reviewer, "version_recurso", scene.resourceVersion, mapOf("estado" to "APROBADA")).first)
            assertEquals(HttpStatus.CONFLICT, submit(author, scene.version).first)
            assertEquals(HttpStatus.OK.value(), patch(admin, "version_recurso", scene.resourceVersion, mapOf("estado" to "PUBLICADA")).first)

            assertEquals("EN_REVISION", ok(submit(author, scene.version))["status"].asString())
            assertEquals("APROBADA", ok(decide(reviewer, scene.version, "APROBAR"))["status"].asString())
            assertEquals("PUBLICADA", ok(call(admin, "/api/lms/review/versions/${scene.version}/publish", emptyMap()))["status"].asString())
            assertEquals("PUBLICADA", state("version_curso", scene.version))
        } finally {
            moveAsPlatform("version_curso", scene.version, "RETIRADA")
        }
    }

    // autorización negativa por la API de registros: quien no puede dar el paso recibe su 403, no noticias del temario
    @Test
    fun `without the action or as someone else, the records API answers 403 before looking at the resources`() {
        val author = account("CREADOR")
        val scene = generatedLike(author)
        try {
            for (token in listOf(account("CREADOR"), account("REVISOR"))) {
                val (status, message) = patch(token, "version_curso", scene.version, mapOf("estado" to "EN_REVISION"))
                assertEquals(HttpStatus.FORBIDDEN.value(), status, message)
            }
            val student = newStudent()
            val (submitted, answer) = patch(student, "version_curso", scene.version, mapOf("estado" to "EN_REVISION"))
            assertTrue(submitted == HttpStatus.FORBIDDEN.value() || submitted == HttpStatus.NOT_FOUND.value(), "$submitted $answer")
            val (status, message) = patch(student, "version_recurso", scene.resourceVersion, mapOf("origen" to "del estudiante"))
            assertTrue(status == HttpStatus.FORBIDDEN.value() || status == HttpStatus.NOT_FOUND.value(), "$status $message")
            assertEquals("BORRADOR", state("version_curso", scene.version))
        } finally {
            moveAsPlatform("version_curso", scene.version, "RETIRADA")
        }
    }

    // defensa en profundidad: aunque una lección de su matrícula la enlace (datos de antes de #366), el estudiante no la lee
    @Test
    fun `an enrolled student never reads a resource version that is not published`() {
        val course = course()
        val draft =
            runBlocking {
                records.asPlatform(ORG) {
                    val resource = records.create("recurso", RecordRequest(mapOf("titulo" to "Sin publicar", "tipo" to "HTML", "idioma" to "es")))
                    val version =
                        records.create("version_recurso", RecordRequest(mapOf("recurso" to resource.id, "numero" to 1, "estado" to "BORRADOR"))).id
                    val lesson =
                        records.create("leccion", RecordRequest(mapOf("unidad" to course.unit, "orden" to 9, "titulo" to "Con borrador", "tipo" to "LECTURA")))
                    records.create("recurso_leccion", RecordRequest(mapOf("leccion" to lesson.id, "version_recurso" to version, "orden" to 1)))
                    resource.id to version
                }
            }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        assertEquals(HttpStatus.NOT_FOUND, call(student, "/api/objects/version_recurso/records/${draft.second}").first)
        assertEquals(HttpStatus.NOT_FOUND, call(student, "/api/objects/recurso/records/${draft.first}").first)
        assertEquals(HttpStatus.OK, call(student, "/api/objects/version_recurso/records/${course.resource}").first)
        val shown =
            ok(call(student, "/api/lms/classroom/enrollments/$enrollment"))["units"]
                .flatMap { it["lessons"] }
                .flatMap { it["resources"] }
                .map { it["id"].asString() }
        assertFalse(draft.second in shown, shown.toString())
        assertTrue(course.resource in shown, shown.toString())
    }

    companion object {
        const val LESSON = "Lección generada con su recurso"
    }
}
