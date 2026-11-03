package elearning.credits

import elearning.catalog.CatalogProjection
import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordRequest
import java.util.UUID

// aprobación humana y auditada de créditos (RF-CRE-08, ADR-0035): APROBAR_CREDITOS y no APROBAR, separación de funciones
// por datos también para ADMIN, creditos_aprobados solo por el comando, filas de solo inserción, auditoría y catálogo
class CreditApprovalApiTest : ClassroomApiTestSupport() {
    @Autowired
    private lateinit var projection: CatalogProjection

    private class Person(
        val email: String,
        val token: String,
    )

    private fun person(vararg roles: String): Person {
        val email = "${uniqueName("cre")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Persona", "password" to DEMO_PASSWORD, "roles" to roles.toList()))
            .exchange()
            .expectStatus()
            .isCreated
        return Person(email, bearer(email, DEMO_PASSWORD))
    }

    private fun account(vararg roles: String): String = person(*roles).token

    private fun courseId(code: String) = get("/api/objects/curso/records?codigo=$code", admin)["content"][0]["id"].asString()

    private fun send(
        method: String,
        token: String,
        path: String,
        body: Map<String, Any?>? = null,
    ): HttpStatus {
        val spec =
            when (method) {
                "POST" -> client.post().uri(path).bodyValue(body!!)
                "PATCH" -> client.patch().uri(path).bodyValue(body!!)
                else -> client.delete().uri(path)
            }
        return HttpStatus.valueOf(
            spec
                .header("Authorization", token)
                .exchange()
                .returnResult(String::class.java)
                .status
                .value(),
        )
    }

    private fun patch(
        token: String,
        id: String,
        attributes: Map<String, Any?>,
    ) = send("PATCH", token, "/api/objects/version_curso/records/$id", mapOf("attributes" to attributes))

    // un borrador nuevo del autor, copiado de la versión publicada de un curso propio, con la carga del ejemplo de la
    // especificación (6 × (12 + 12 + 12) = 216 h, ECTS a 27 h: 8 propuestos)
    private fun draft(
        author: String,
        course: Course = course(),
        attributes: Map<String, Any?> = WORKLOAD,
    ): String {
        val (status, body) = call(author, "/api/lms/studio/courses/${courseId(course.offering)}/versions", mapOf("fromVersionId" to null))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        val id = body!!["id"].asString()
        assertEquals(HttpStatus.OK, patch(author, id, attributes))
        return id
    }

    private fun inReview(
        author: String,
        course: Course = course(),
        attributes: Map<String, Any?> = WORKLOAD,
    ): String {
        val id = draft(author, course, attributes)
        assertEquals("EN_REVISION", ok(call(author, "/api/lms/studio/versions/$id/submit", emptyMap()))["status"].asString())
        return id
    }

    // aprobada en la bandeja por quien decide; con assigned, quien decide es además su revisor asignado
    private fun approved(
        author: String,
        decider: String,
        assigned: Boolean = false,
        course: Course = course(),
        attributes: Map<String, Any?> = WORKLOAD,
    ): String {
        val id = inReview(author, course, attributes)
        if (assigned) assertEquals(HttpStatus.OK, patch(admin, id, mapOf("revisor_id" to userId(decider))))
        val decided = ok(call(decider, "/api/lms/review/versions/$id/decision", mapOf("decision" to "APROBAR")))
        assertEquals("APROBADA", decided["status"].asString())
        return id
    }

    private fun approve(
        token: String?,
        version: String,
        credits: Any? = null,
        reason: String? = null,
        body: String? = BODY,
        document: String? = DOCUMENT,
    ) = call(
        token,
        "/api/lms/credits/versions/$version/approvals",
        mapOf("approvedCredits" to credits, "reason" to reason, "approvingBody" to body, "documentReference" to document, "documentDate" to "2026-10-30"),
    )

    private fun revoke(
        token: String,
        approval: String,
        reason: String? = "La resolución se anuló",
    ) = call(
        token,
        "/api/lms/credits/approvals/$approval/revoke",
        mapOf("reason" to reason, "approvingBody" to BODY, "documentReference" to "Resolución 051-2026-CF"),
    )

    private fun created(result: Pair<HttpStatus, JsonNode?>): JsonNode {
        assertEquals(HttpStatus.CREATED, result.first, result.second.toString())
        return result.second!!
    }

    @Test
    fun `approving credits needs APROBAR_CREDITOS, which a reviewer with APROBAR does not have`() {
        val id = approved(account("CREADOR"), account("REVISOR"))

        // otro revisor, con APROBAR sobre version_curso: aprobar contenido no es aprobar créditos
        assertEquals(HttpStatus.FORBIDDEN, approve(account("REVISOR"), id).first)
        assertEquals(HttpStatus.FORBIDDEN, approve(account("CREADOR"), id).first)
        assertEquals(HttpStatus.FORBIDDEN, approve(newTeacher(), id).first)
        assertEquals(HttpStatus.FORBIDDEN, approve(newStudent(), id).first)
        assertEquals(HttpStatus.UNAUTHORIZED, approve(null, id).first)
        // el estudiante no lee las aprobaciones
        assertFalse(call(newStudent(), "/api/lms/credits/versions/$id/approvals").first.is2xxSuccessful)

        val approval = created(approve(account("AUTORIDAD_ACADEMICA"), id))
        assertEquals("APROBACION", approval["type"].asString())
        assertEquals(8, approval["approvedCredits"].asInt())
    }

    @Test
    fun `the author, the assigned reviewer, who approved it in the inbox and a teacher of the course do not approve its credits`() {
        val author = account("CREADOR", "AUTORIDAD_ACADEMICA")
        val reviewer = account("REVISOR", "AUTORIDAD_ACADEMICA")
        val assigned = approved(author, reviewer, assigned = true)
        assertEquals(HttpStatus.FORBIDDEN, approve(author, assigned).first)
        assertEquals(HttpStatus.FORBIDDEN, approve(reviewer, assigned).first)

        // sin revisor asignado: quien la aprobó en la bandeja
        val decider = account("REVISOR", "AUTORIDAD_ACADEMICA")
        val decided = approved(account("CREADOR"), decider)
        assertEquals(HttpStatus.FORBIDDEN, approve(decider, decided).first)

        // docente de una edición del curso, aunque sea de otra versión
        val course = course()
        val teacher = account("DOCENTE", "AUTORIDAD_ACADEMICA")
        assign(teacher, course)
        val taught = approved(account("CREADOR"), account("REVISOR"), course = course)
        assertEquals(HttpStatus.FORBIDDEN, approve(teacher, taught).first)

        // una autoridad sin ninguno de esos papeles sí
        val authority = account("AUTORIDAD_ACADEMICA")
        created(approve(authority, assigned))
        created(approve(authority, decided))
        created(approve(authority, taught))
    }

    @Test
    fun `the administrator does not approve credits either as author, inbox approver or teacher`() {
        val reviewer = account("REVISOR")
        val authored = approved(admin, reviewer)
        assertEquals(HttpStatus.FORBIDDEN, approve(admin, authored).first)

        val decided = approved(account("CREADOR"), admin)
        assertEquals(HttpStatus.FORBIDDEN, approve(admin, decided).first)

        val course = course()
        assign(admin, course)
        try {
            val taught = approved(account("CREADOR"), reviewer, course = course)
            assertEquals(HttpStatus.FORBIDDEN, approve(admin, taught).first)
        } finally {
            // la asignación del administrador no se queda para otras pruebas del espacio docente
            val offering = get("/api/objects/edicion_curso/records?codigo=${course.offering}", admin)["content"][0]["id"].asString()
            get("/api/objects/docente_edicion/records?edicion_curso=$offering", admin)["content"].values().forEach {
                assertTrue(send("DELETE", admin, "/api/objects/docente_edicion/records/${it["id"].asString()}").is2xxSuccessful)
            }
        }
    }

    @Test
    fun `the record api never writes approved credits and approvals never change`() {
        val authority = account("AUTORIDAD_ACADEMICA")
        val reviewer = account("REVISOR")
        val id = approved(account("CREADOR"), reviewer)
        // ni antes de aprobar…
        assertEquals(HttpStatus.FORBIDDEN, patch(admin, id, mapOf("creditos_aprobados" to 8)))
        assertEquals(HttpStatus.FORBIDDEN, patch(authority, id, mapOf("creditos_aprobados" to 8)))
        assertEquals(HttpStatus.FORBIDDEN, patch(reviewer, id, mapOf("creditos_aprobados" to 8)))
        val direct =
            mapOf(
                "attributes" to
                    mapOf(
                        "version_curso" to id,
                        "tipo" to "APROBACION",
                        "creditos_aprobados" to 8,
                        "organo" to BODY,
                        "documento_referencia" to DOCUMENT,
                        "aprobada_por" to userId(authority),
                        "aprobada_en" to "2026-11-03T10:00:00Z",
                    ),
            )
        assertEquals(HttpStatus.FORBIDDEN, send("POST", admin, "/api/objects/aprobacion_creditos/records", direct))
        assertEquals(HttpStatus.FORBIDDEN, send("POST", authority, "/api/objects/aprobacion_creditos/records", direct))

        val approval = created(approve(authority, id))["id"].asString()
        val version = get("/api/objects/version_curso/records/$id", admin)["attributes"]
        assertEquals(8, version["creditos_aprobados"].asInt())
        assertEquals("ects-carga-total@1", version["politica_creditos"].asString())
        // …ni después: tampoco para quitarlos, y la fila no cambia ni se borra
        assertEquals(HttpStatus.FORBIDDEN, patch(admin, id, mapOf("creditos_aprobados" to null)))
        assertEquals(HttpStatus.FORBIDDEN, patch(authority, id, mapOf("creditos_aprobados" to 9)))
        val path = "/api/objects/aprobacion_creditos/records/$approval"
        assertFalse(send("PATCH", admin, path, mapOf("attributes" to mapOf("creditos_aprobados" to 9))).is2xxSuccessful)
        assertFalse(send("DELETE", admin, path).is2xxSuccessful)
        assertEquals(8, get(path, admin)["attributes"]["creditos_aprobados"].asInt())
        // una versión nueva no hereda los créditos aprobados: se aprueban otra vez
        val courseOfVersion = version["curso"].asString()
        val (status, copy) = call(account("CREADOR"), "/api/lms/studio/courses/$courseOfVersion/versions", mapOf("fromVersionId" to id))
        assertEquals(HttpStatus.CREATED, status, copy.toString())
        val copied = get("/api/objects/version_curso/records/${copy!!["id"].asString()}", admin)["attributes"]
        assertTrue(copied["creditos_aprobados"] == null || copied["creditos_aprobados"].isNull, copied.toString())
    }

    @Test
    fun `only an approved or published version with a credit system, and a different value needs a reason`() {
        val authority = account("AUTORIDAD_ACADEMICA")
        val author = account("CREADOR")
        val reviewer = account("REVISOR")

        assertEquals(HttpStatus.CONFLICT, approve(authority, draft(author)).first)
        assertEquals(HttpStatus.CONFLICT, approve(authority, inReview(author)).first)
        val noSystem = approved(author, reviewer, attributes = WORKLOAD + ("sistema_credito" to "NINGUNO"))
        assertEquals(HttpStatus.CONFLICT, approve(authority, noSystem).first)
        assertEquals(HttpStatus.NOT_FOUND, approve(authority, UUID.randomUUID().toString()).first)

        val id = approved(author, reviewer)
        val proposal = ok(call(authority, "/api/lms/credits/versions/$id/proposal"))
        assertEquals("ECTS", proposal["system"].asString())
        assertEquals(8, proposal["proposal"]["proposed"].asInt())
        assertEquals("PROPUESTO", proposal["proposal"]["status"].asString())
        assertTrue(proposal["current"].isNull)

        assertEquals(HttpStatus.BAD_REQUEST, approve(authority, id, credits = 7).first)
        assertEquals(HttpStatus.BAD_REQUEST, approve(authority, id, body = " ").first)
        assertEquals(HttpStatus.BAD_REQUEST, approve(authority, id, document = null).first)
        assertEquals(HttpStatus.BAD_REQUEST, approve(authority, id, credits = -1, reason = "x").first)
        val approval = created(approve(authority, id, credits = 7, reason = "El Consejo descuenta la semana de exámenes"))
        assertEquals(7, approval["approvedCredits"].asInt())
        assertEquals(8, approval["proposed"].asInt())
        assertEquals("El Consejo descuenta la semana de exámenes", approval["reason"].asString())
        // una aprobación vigente se revoca antes de aprobar otra
        assertEquals(HttpStatus.CONFLICT, approve(authority, id).first)
        assertEquals(approval["id"].asString(), ok(call(authority, "/api/lms/credits/versions/$id/proposal"))["current"]["id"].asString())
    }

    @Test
    fun `an approval is audited and reaches the catalog, and a revocation is a new row that takes it out`() {
        val authority = person("AUTORIDAD_ACADEMICA")
        val author = account("CREADOR")
        val course = course()
        val id = approved(author, account("REVISOR"), course = course)
        assertEquals("PUBLICADA", ok(call(admin, "/api/lms/review/versions/$id/publish", emptyMap()))["status"].asString())
        try {
            val approval = created(approve(authority.token, id))
            val approvalId = approval["id"].asString()
            assertEquals("ects-carga-total", approval["policyId"].asString())
            assertEquals("1", approval["policyVersion"].asString())
            assertEquals("27", approval["parameters"]["horasPorCredito"].asString())
            assertEquals(216, approval["totalHours"].asInt())
            assertEquals(BODY, approval["approvingBody"].asString())
            assertEquals(DOCUMENT, approval["documentReference"].asString())
            assertEquals("2026-10-30", approval["documentDate"].asString())
            assertEquals(userId(authority.token), approval["approvedBy"].asString())

            // en la auditoría de Wasichai, con la política, sus parámetros, las horas, el órgano, el documento, la persona y la hora
            val audit = get("/api/audit?objectName=aprobacion_creditos&recordId=$approvalId", admin).values().toList()
            val entry = audit.single { it["operation"].asString() == "CREATE" }
            assertEquals(authority.email, entry["userEmail"].asString())
            assertFalse(entry["occurredAt"].isNull)
            val reason = entry["reason"].asString()
            for (expected in listOf("APROBACION", "ects-carga-total@1", "horasPorCredito=27", "216 h", "8 créditos", BODY, DOCUMENT, "2026-10-30")) {
                assertTrue(expected in reason, "$expected no está en $reason")
            }

            val slug = course.offering.lowercase()
            runBlocking { projection.refreshNow() }
            val credits = get("/api/lms/public/catalog/courses/$slug", admin)["credits"]
            assertEquals(8, credits["approved"].asInt())
            assertEquals(BODY, credits["approval"]["approvingBody"].asString())
            assertEquals(DOCUMENT, credits["approval"]["documentReference"].asString())
            assertEquals("ects-carga-total", credits["approval"]["policyId"].asString())

            assertEquals(HttpStatus.BAD_REQUEST, revoke(authority.token, approvalId, reason = null).first)
            assertEquals(HttpStatus.FORBIDDEN, revoke(author, approvalId).first)
            val revocation = created(revoke(authority.token, approvalId))
            assertEquals("REVOCACION", revocation["type"].asString())
            assertEquals(approvalId, revocation["revokes"].asString())
            assertEquals(HttpStatus.CONFLICT, revoke(authority.token, approvalId).first)

            val history = ok(call(authority.token, "/api/lms/credits/versions/$id/approvals"))
            assertEquals(listOf("APROBACION", "REVOCACION"), history.strings { it["type"] })
            assertTrue(ok(call(authority.token, "/api/lms/credits/versions/$id/proposal"))["current"].isNull)
            assertTrue(get("/api/objects/version_curso/records/$id", admin)["attributes"]["creditos_aprobados"]?.isNull ?: true)

            runBlocking { projection.refreshNow() }
            val after = get("/api/lms/public/catalog/courses/$slug", admin)["credits"]
            assertTrue(after["approved"].isNull, after.toString())
            assertTrue(after["approval"].isNull, after.toString())
            assertEquals("PROPUESTO", after["proposal"]["status"].asString())
        } finally {
            runBlocking {
                records.asPlatform(ORG) { records.patch("version_curso", UUID.fromString(id), RecordRequest(mapOf("estado" to "RETIRADA")), null, null) }
            }
        }
    }

    companion object {
        const val BODY = "Consejo de Facultad"
        const val DOCUMENT = "Resolución 045-2026-CF"
        val WORKLOAD =
            mapOf(
                "semanas" to 6,
                "horas_teoria_semana" to 12,
                "horas_practica_semana" to 12,
                "horas_supervisadas_semana" to 0,
                "horas_autonomas_semana" to 12,
                "sistema_credito" to "ECTS",
            )
    }
}
