package elearning.authoring

import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.http.HttpStatus
import wasichai.core.data.RecordRequest
import java.util.UUID

// quién lleva una versión por el flujo editorial, por el estudio y por la API de registros. cada caso va en el nombre de su
// prueba dinámica con lo que devolvió: el CI solo muestra el nombre de lo que falla, no el mensaje
class VersionTransitionsApiTest : ClassroomApiTestSupport() {
    private val course = "version_curso"
    private val resource = "version_recurso"

    private class Report {
        private val cases = mutableListOf<DynamicTest>()

        fun expect(
            what: String,
            expected: Any,
            actual: Any,
        ) {
            cases += dynamicTest("$what: esperado $expected, devolvió $actual") { assertEquals(expected.toString(), actual.toString(), what) }
        }

        fun all(): List<DynamicTest> = cases
    }

    private fun account(vararg roles: String): String {
        val email = "${uniqueName("flujo")}@demo.wasichai-learning.test"
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

    private fun created(
        token: String,
        objectName: String,
        attributes: Map<String, Any?>,
    ): String {
        val (status, body) = call(token, "/api/objects/$objectName/records", mapOf("attributes" to attributes))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        return body!!["id"].asString()
    }

    private fun emptyDraft(author: String): String {
        val slug = uniqueName("flujo-")
        val course = created(author, "curso", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso del flujo"))
        val (status, body) = call(author, "/api/lms/studio/courses/$course/versions", mapOf("fromVersionId" to null))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        return body!!["id"].asString()
    }

    private fun draft(author: String): String {
        val version = emptyDraft(author)
        val unit = created(author, "unidad", mapOf("version_curso" to version, "orden" to 1, "titulo" to "Unidad"))
        created(author, "leccion", mapOf("unidad" to unit, "orden" to 1, "titulo" to "Lección", "tipo" to "LECTURA"))
        return version
    }

    private fun resourceDraft(author: String): String {
        val parent = created(author, "recurso", mapOf("titulo" to "Lectura del flujo", "tipo" to "HTML", "idioma" to "es"))
        return created(author, resource, mapOf("recurso" to parent, "numero" to 1, "autor_id" to userId(author)))
    }

    private fun patch(
        token: String,
        objectName: String,
        id: String,
        state: String,
        reason: String? = null,
    ): Int {
        val spec =
            client
                .patch()
                .uri("/api/objects/$objectName/records/$id")
                .header("Authorization", token)
        reason?.let { spec.header("X-Change-Reason", it) }
        return spec
            .bodyValue(mapOf("attributes" to mapOf("estado" to state)))
            .exchange()
            .returnResult(String::class.java)
            .status
            .value()
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
    ) = call(token, "/api/lms/studio/versions/$version/submit", emptyMap()).first.value()

    private fun decide(
        token: String,
        version: String,
        decision: String,
        reason: String? = "Motivo de la decisión",
    ) = call(token, "/api/lms/review/versions/$version/decision", mapOf("decision" to decision, "reason" to reason)).first.value()

    private fun status(
        token: String,
        path: String,
    ) = call(token, path).first.value()

    private fun state(
        objectName: String,
        id: String,
    ) = get("/api/objects/$objectName/records/$id", admin)["attributes"]["estado"].asString()

    private fun moveAsPlatform(
        version: String,
        state: String,
    ) = runBlocking {
        records.asPlatform(ORG) { records.patch(course, UUID.fromString(version), RecordRequest(mapOf("estado" to state)), null, null) }
    }

    @TestFactory
    fun `a course version, for someone with UPDATE who is not its author`(): List<DynamicTest> {
        val report = Report()
        val author = account("CREADOR")
        val other = account("CREADOR")

        val read = draft(author)
        report.expect("otro creador, GET de registros de un borrador ajeno", 200, status(other, "/api/objects/$course/records/$read"))
        report.expect("otro creador, GET del estudio de un borrador ajeno", 404, status(other, "/api/lms/studio/versions/$read"))

        val patched = draft(author)
        report.expect("otro creador, PATCH BORRADOR a EN_REVISION", 403, patch(other, course, patched, "EN_REVISION"))
        report.expect("estado tras ese PATCH", "BORRADOR", state(course, patched))

        val whole = draft(author)
        val stored = get("/api/objects/$course/records/$whole", admin)["attributes"]
        val attributes =
            mapOf(
                "curso" to stored["curso"].asString(),
                "numero" to 1,
                "modalidad" to "ASINCRONA",
                "semanas" to 1,
                "sistema_credito" to stored["sistema_credito"].asString(),
                "contenido_ilustrativo" to stored["contenido_ilustrativo"].asBoolean(),
                "estado" to "EN_REVISION",
                "autor_id" to userId(author),
            )
        report.expect(
            "otro creador, PUT BORRADOR a EN_REVISION",
            403,
            put(other, "/api/objects/$course/records/$whole", mapOf("attributes" to attributes)).first.value(),
        )
        report.expect("estado tras ese PUT", "BORRADOR", state(course, whole))

        val studio = draft(author)
        report.expect("otro creador, POST submit de un borrador ajeno", 404, submit(other, studio))
        report.expect("estado tras ese submit", "BORRADOR", state(course, studio))

        report.expect("otro creador, PATCH a EN_REVISION de un borrador ajeno vacío", 403, patch(other, course, emptyDraft(author), "EN_REVISION"))
        report.expect("revisor, PATCH BORRADOR a EN_REVISION", 403, patch(account("REVISOR"), course, draft(author), "EN_REVISION"))
        report.expect("autoridad académica, PATCH BORRADOR a EN_REVISION", 403, patch(account("AUTORIDAD_ACADEMICA"), course, draft(author), "EN_REVISION"))
        report.expect("creador y revisor, PATCH BORRADOR a EN_REVISION", 403, patch(account("CREADOR", "REVISOR"), course, draft(author), "EN_REVISION"))

        val illegal = draft(author)
        for (target in listOf("APROBADA", "PUBLICADA", "RETIRADA")) {
            report.expect("otro creador, PATCH BORRADOR a $target", 409, patch(other, course, illegal, target))
        }
        report.expect("otro creador, DELETE de un borrador ajeno", 403, delete(other, course, illegal))

        val inReview = draft(author)
        assertEquals(200, submit(author, inReview))
        report.expect("otro creador, PATCH EN_REVISION a BORRADOR", 403, patch(other, course, inReview, "BORRADOR"))
        report.expect("otro creador, PATCH EN_REVISION a APROBADA", 403, patch(other, course, inReview, "APROBADA"))
        report.expect("otro creador, PATCH EN_REVISION a RETIRADA", 403, patch(other, course, inReview, "RETIRADA"))
        report.expect("otro creador, PATCH EN_REVISION a PUBLICADA", 409, patch(other, course, inReview, "PUBLICADA"))
        report.expect("otro creador, POST submit de una versión ajena en revisión", 404, submit(other, inReview))
        report.expect("estado tras esos intentos", "EN_REVISION", state(course, inReview))

        val approved = draft(author)
        moveAsPlatform(approved, "APROBADA")
        try {
            report.expect("otro creador, PATCH APROBADA a PUBLICADA", 403, patch(other, course, approved, "PUBLICADA"))
            report.expect("otro creador, PATCH APROBADA a BORRADOR", 409, patch(other, course, approved, "BORRADOR"))
            report.expect("otro creador, PATCH APROBADA a RETIRADA", 409, patch(other, course, approved, "RETIRADA"))
        } finally {
            moveAsPlatform(approved, "RETIRADA")
        }
        report.expect("otro creador, PATCH RETIRADA a BORRADOR", 409, patch(other, course, approved, "BORRADOR"))

        val published = draft(author)
        moveAsPlatform(published, "PUBLICADA")
        try {
            report.expect("otro creador, PATCH PUBLICADA a RETIRADA", 403, patch(other, course, published, "RETIRADA"))
            report.expect("otro creador, PATCH PUBLICADA a BORRADOR", 409, patch(other, course, published, "BORRADOR"))
        } finally {
            moveAsPlatform(published, "RETIRADA")
        }
        return report.all()
    }

    @TestFactory
    fun `a resource version, for someone with UPDATE who is not its author`(): List<DynamicTest> {
        val report = Report()
        val author = account("CREADOR")
        val other = account("CREADOR")
        val reviewer = account("REVISOR")

        val draft = resourceDraft(author)
        report.expect("otro creador, GET de una versión de recurso ajena", 200, status(other, "/api/objects/$resource/records/$draft"))
        report.expect("otro creador, PATCH de recurso BORRADOR a EN_REVISION", 403, patch(other, resource, draft, "EN_REVISION"))
        report.expect("estado del recurso tras ese PATCH", "BORRADOR", state(resource, draft))
        report.expect("otro creador, PATCH de recurso BORRADOR a PUBLICADA", 409, patch(other, resource, draft, "PUBLICADA"))

        val deleted = resourceDraft(author)
        report.expect("otro creador, DELETE de un borrador de recurso ajeno", 403, delete(other, resource, deleted))
        report.expect("el borrador de recurso sigue tras ese DELETE", 200, status(admin, "/api/objects/$resource/records/$deleted"))

        val inReview = resourceDraft(author)
        assertEquals(200, patch(author, resource, inReview, "EN_REVISION"))
        report.expect("otro creador, PATCH de recurso EN_REVISION a BORRADOR", 403, patch(other, resource, inReview, "BORRADOR", "Motivo"))
        report.expect("otro creador, PATCH de recurso EN_REVISION a APROBADA", 403, patch(other, resource, inReview, "APROBADA"))
        report.expect("otro creador, PATCH de recurso EN_REVISION a RETIRADA", 403, patch(other, resource, inReview, "RETIRADA", "Motivo"))
        report.expect("estado del recurso tras esos intentos", "EN_REVISION", state(resource, inReview))

        // el autor la retira y la vuelve a enviar; el revisor decide, con motivo salvo al aprobar
        report.expect("autor, PATCH de recurso EN_REVISION a BORRADOR", 200, patch(author, resource, inReview, "BORRADOR"))
        report.expect("autor, PATCH de recurso BORRADOR a EN_REVISION", 200, patch(author, resource, inReview, "EN_REVISION"))
        report.expect("revisor, PATCH de recurso a BORRADOR sin motivo", 400, patch(reviewer, resource, inReview, "BORRADOR"))
        report.expect("revisor, PATCH de recurso a BORRADOR con motivo", 200, patch(reviewer, resource, inReview, "BORRADOR", "Motivo"))
        report.expect("autor, otra vez a EN_REVISION", 200, patch(author, resource, inReview, "EN_REVISION"))
        report.expect("revisor, PATCH de recurso a APROBADA", 200, patch(reviewer, resource, inReview, "APROBADA"))

        val direction = resourceDraft(author)
        report.expect("quien tiene PUBLICAR, PATCH de recurso ajeno BORRADOR a EN_REVISION", 200, patch(admin, resource, direction, "EN_REVISION"))
        return report.all()
    }

    @TestFactory
    fun `the author, the reviewer, whoever publishes and the platform keep their steps`(): List<DynamicTest> {
        val report = Report()
        val author = account("CREADOR")
        val reviewer = account("REVISOR")

        val own = draft(author)
        report.expect("autor, PATCH BORRADOR a EN_REVISION", 200, patch(author, course, own, "EN_REVISION"))
        report.expect("autor, PATCH EN_REVISION a BORRADOR", 200, patch(author, course, own, "BORRADOR"))
        report.expect("autor, POST submit", 200, submit(author, own))
        report.expect("revisor, por registros EN_REVISION a BORRADOR", 403, patch(reviewer, course, own, "BORRADOR"))
        report.expect("revisor, pedir cambios en la bandeja", 200, decide(reviewer, own, "PEDIR_CAMBIOS"))
        report.expect("estado tras pedir cambios", "BORRADOR", state(course, own))
        report.expect("autor, reenvía", 200, submit(author, own))
        report.expect("revisor, rechazar en la bandeja", 200, decide(reviewer, own, "RECHAZAR"))
        report.expect("estado tras rechazar", "RETIRADA", state(course, own))

        val approved = draft(author)
        assertEquals(200, submit(author, approved))
        report.expect("revisor, aprobar en la bandeja", 200, decide(reviewer, approved, "APROBAR", null))
        report.expect("estado tras aprobar", "APROBADA", state(course, approved))
        moveAsPlatform(approved, "RETIRADA")

        // quien tiene PUBLICAR también envía el borrador de otro; sacarlo de revisión sigue siendo decidirla en la bandeja
        val direction = draft(author)
        report.expect("quien tiene PUBLICAR, PATCH de un borrador ajeno a EN_REVISION", 200, patch(admin, course, direction, "EN_REVISION"))
        report.expect("quien tiene PUBLICAR, por registros EN_REVISION a BORRADOR", 403, patch(admin, course, direction, "BORRADOR"))
        report.expect("quien tiene PUBLICAR, pedir cambios en la bandeja", 200, decide(admin, direction, "PEDIR_CAMBIOS"))

        val platform = draft(author)
        moveAsPlatform(platform, "EN_REVISION")
        report.expect("la plataforma lleva un borrador a EN_REVISION", "EN_REVISION", state(course, platform))
        return report.all()
    }
}
