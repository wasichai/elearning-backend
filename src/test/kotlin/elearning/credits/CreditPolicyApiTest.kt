package elearning.credits

import elearning.learning.ClassroomApiTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.util.UUID

// políticas de crédito versionadas por jurisdicción, institución y programa (RF-CRE-07, ADR-0036): borrador, publicada
// inmutable, versión siguiente, validación por método, ámbito, y la versión de curso que sigue ligada a la suya
class CreditPolicyApiTest : ClassroomApiTestSupport() {
    private fun account(vararg roles: String): String {
        val email = "${uniqueName("pol")}@demo.wasichai-learning.test"
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

    private fun send(
        method: String,
        token: String,
        path: String,
        body: Map<String, Any?>? = null,
    ): Pair<HttpStatus, JsonNode?> {
        val spec =
            when (method) {
                "POST" -> client.post().uri(path).bodyValue(body!!)
                "PATCH" -> client.patch().uri(path).bodyValue(body!!)
                else -> client.delete().uri(path)
            }
        val r =
            spec
                .header("Authorization", token)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(r.status.value()) to r.responseBody?.let { runCatching { json.readTree(it) }.getOrNull() }
    }

    // un borrador de política por la API de registros; extra cambia o añade atributos
    private fun draftPolicy(
        token: String,
        key: String,
        vararg extra: Pair<String, Any?>,
    ): Pair<HttpStatus, JsonNode?> =
        send(
            "POST",
            token,
            "/api/objects/politica_credito/records",
            mapOf(
                "attributes" to
                    mapOf(
                        "clave" to key,
                        "numero" to 1,
                        "nombre" to "Créditos de la Facultad",
                        "sistema" to "ECTS",
                        "metodo" to "ECTS_CARGA_TOTAL",
                        "parametros" to """{"horasPorCredito": "25"}""",
                        "jurisdiccion" to "EHEA",
                        "fuente" to "Reglamento de la Facultad, art. 12",
                    ) + extra,
            ),
        )

    private fun policy(
        token: String,
        key: String,
        vararg extra: Pair<String, Any?>,
    ): String {
        val (status, body) = draftPolicy(token, key, *extra)
        assertEquals(HttpStatus.CREATED, status, body.toString())
        return body!!["id"].asString()
    }

    private fun publish(
        token: String,
        id: String,
    ) = call(token, "/api/lms/credits/policies/$id/publish", emptyMap())

    private fun courseId(code: String) = get("/api/objects/curso/records?codigo=$code", admin)["content"][0]["id"].asString()

    // una versión de curso con la carga del ejemplo (216 h), ligada a la política, aprobada en la bandeja
    private fun approvedVersion(
        policy: String?,
        system: String = "ECTS",
    ): String {
        val author = account("CREADOR")
        val (status, body) = call(author, "/api/lms/studio/courses/${courseId(course().offering)}/versions", mapOf("fromVersionId" to null))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        val id = body!!["id"].asString()
        val workload = CreditApprovalApiTest.WORKLOAD + ("sistema_credito" to system) + ("politica_credito" to policy)
        val (patched, problem) = send("PATCH", author, "/api/objects/version_curso/records/$id", mapOf("attributes" to workload))
        assertEquals(HttpStatus.OK, patched, problem.toString())
        assertEquals("EN_REVISION", ok(call(author, "/api/lms/studio/versions/$id/submit", emptyMap()))["status"].asString())
        assertEquals("APROBADA", ok(call(account("REVISOR"), "/api/lms/review/versions/$id/decision", mapOf("decision" to "APROBAR")))["status"].asString())
        return id
    }

    private fun approve(
        token: String,
        version: String,
    ) = call(
        token,
        "/api/lms/credits/versions/$version/approvals",
        mapOf("approvingBody" to CreditApprovalApiTest.BODY, "documentReference" to CreditApprovalApiTest.DOCUMENT),
    )

    @Test
    fun `a policy is drafted, published and then never changes, and a new version leaves the first one alone`() {
        val authority = account("AUTORIDAD_ACADEMICA")
        val key = uniqueName("ects-")
        val v1 = policy(authority, key)
        assertEquals(
            HttpStatus.OK,
            send("PATCH", authority, "/api/objects/politica_credito/records/$v1", mapOf("attributes" to mapOf("nombre" to "Otro"))).first,
        )
        // el estado no se escribe por la API de registros
        assertEquals(
            HttpStatus.CONFLICT,
            send(
                "PATCH",
                authority,
                "/api/objects/politica_credito/records/$v1",
                mapOf("attributes" to mapOf("estado" to "PUBLICADA")),
            ).first,
        )
        // quien no tiene PUBLICAR no publica; quien no la redacta no la crea
        assertEquals(HttpStatus.FORBIDDEN, publish(account("REVISOR"), v1).first)
        assertEquals(HttpStatus.FORBIDDEN, draftPolicy(account("CREADOR"), uniqueName("x-")).first)

        val published = ok(publish(authority, v1))
        assertEquals("PUBLICADA", published["status"].asString())
        assertEquals(1, published["number"].asInt())
        assertEquals("25", published["parameters"]["horasPorCredito"].asString())
        assertTrue(!published["publishedAt"].isNull && !published["publishedBy"].isNull, published.toString())
        assertEquals(HttpStatus.CONFLICT, publish(authority, v1).first)

        // publicada no cambia ni se borra, tampoco para ADMIN
        val path = "/api/objects/politica_credito/records/$v1"
        assertEquals(HttpStatus.CONFLICT, send("PATCH", admin, path, mapOf("attributes" to mapOf("parametros" to """{"horasPorCredito": "30"}"""))).first)
        assertEquals(HttpStatus.CONFLICT, send("PATCH", authority, path, mapOf("attributes" to mapOf("estado" to "BORRADOR"))).first)
        assertEquals(HttpStatus.CONFLICT, send("DELETE", admin, path).first)
        // nace en borrador
        assertEquals(HttpStatus.CONFLICT, draftPolicy(authority, uniqueName("ects-"), "estado" to "PUBLICADA").first)

        val (status, v2) = call(authority, "/api/lms/credits/policies/$v1/new-version", emptyMap())
        assertEquals(HttpStatus.CREATED, status, v2.toString())
        assertEquals(2, v2!!["number"].asInt())
        assertEquals("BORRADOR", v2["status"].asString())
        assertEquals(key, v2["key"].asString())
        val v2Id = v2["id"].asString()
        assertEquals(HttpStatus.CONFLICT, call(authority, "/api/lms/credits/policies/$v2Id/new-version", emptyMap()).first)
        val edited =
            send(
                "PATCH",
                authority,
                "/api/objects/politica_credito/records/$v2Id",
                mapOf("attributes" to mapOf("parametros" to """{"horasPorCredito": "30"}""")),
            )
        assertEquals(HttpStatus.OK, edited.first, edited.second.toString())
        ok(publish(authority, v2Id))

        val first = ok(call(authority, "/api/lms/credits/policies/$v1"))
        assertEquals("25", first["parameters"]["horasPorCredito"].asString())
        assertEquals("PUBLICADA", first["status"].asString())
        assertEquals("RETIRADA", ok(call(authority, "/api/lms/credits/policies/$v1/retire", emptyMap()))["status"].asString())
        assertEquals(HttpStatus.CONFLICT, call(authority, "/api/lms/credits/policies/$v1/retire", emptyMap()).first)
    }

    @Test
    fun `parameters outside the rule, a method of another system or a bad jurisdiction are a 400`() {
        val authority = account("AUTORIDAD_ACADEMICA")
        assertEquals(HttpStatus.BAD_REQUEST, draftPolicy(authority, uniqueName("p-"), "parametros" to """{"horasPorCredito": "20"}""").first)
        val peru = arrayOf("sistema" to "PE_CREDITO", "metodo" to "PE_LEY30220_HORAS_LECTIVAS", "jurisdiccion" to "PE")
        assertEquals(HttpStatus.BAD_REQUEST, draftPolicy(authority, uniqueName("p-"), *peru, "parametros" to """{"horasTeoriaPorCredito": "12"}""").first)
        assertEquals(HttpStatus.BAD_REQUEST, draftPolicy(authority, uniqueName("p-"), "parametros" to """{"factor": "1"}""").first)
        assertEquals(HttpStatus.BAD_REQUEST, draftPolicy(authority, uniqueName("p-"), "parametros" to "no es json").first)
        assertEquals(HttpStatus.BAD_REQUEST, draftPolicy(authority, uniqueName("p-"), "sistema" to "SCH").first)
        assertEquals(HttpStatus.BAD_REQUEST, draftPolicy(authority, uniqueName("p-"), "jurisdiccion" to "Perú").first)
        // un borrador tampoco se edita fuera de la norma
        val id = policy(authority, uniqueName("p-"), *peru, "parametros" to """{"horasTeoriaPorCredito": "16"}""")
        val bad = mapOf("attributes" to mapOf("parametros" to """{"horasPracticaPorCredito": "20"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, send("PATCH", authority, "/api/objects/politica_credito/records/$id", bad).first)
    }

    @Test
    fun `policies are listed by jurisdiction, system, status and program, and another organization's do not exist`() {
        val authority = account("AUTORIDAD_ACADEMICA")
        val program = uniqueName("PRG-")
        val institution = policy(authority, uniqueName("pe-"), "sistema" to "PE_CREDITO", "metodo" to "PE_LEY30220_HORAS_LECTIVAS", "jurisdiccion" to "PE")
        val ofProgram =
            policy(
                authority,
                uniqueName("pe-"),
                "sistema" to "PE_CREDITO",
                "metodo" to "PE_LEY30220_HORAS_LECTIVAS",
                "jurisdiccion" to "PE",
                "programa_codigo" to program,
            )
        ok(publish(authority, ofProgram))

        fun listed(query: String) = ok(call(authority, "/api/lms/credits/policies?$query")).strings { it["id"] }
        assertEquals(listOf(ofProgram), listed("program=$program"))
        assertEquals(listOf(ofProgram), listed("program=$program&jurisdiction=PE&system=PE_CREDITO&status=PUBLICADA"))
        assertEquals(emptyList<String>(), listed("program=$program&jurisdiction=EHEA"))
        assertTrue(institution in listed("jurisdiction=PE&status=BORRADOR"))
        assertTrue(institution !in listed("jurisdiction=EHEA"))
        // el estudiante no las lee
        assertEquals(HttpStatus.FORBIDDEN, call(newStudent(), "/api/lms/credits/policies").first)
        assertEquals(HttpStatus.NOT_FOUND, call(authority, "/api/lms/credits/policies/${UUID.randomUUID()}").first)

        // otra organización, con su propio administrador: la política de esta no existe para ella
        val email = "${uniqueName("adm")}@otra.wasichai-learning.test"
        val (created, problem) =
            call(
                admin,
                "/api/organizations",
                mapOf("name" to "Otra universidad", "slug" to uniqueName("otra-"), "adminEmail" to email, "adminPassword" to DEMO_PASSWORD),
            )
        assertEquals(HttpStatus.CREATED, created, problem.toString())
        val other = bearer(email, DEMO_PASSWORD)
        assertEquals(HttpStatus.NOT_FOUND, call(other, "/api/lms/credits/policies/$ofProgram").first)
        assertEquals(HttpStatus.NOT_FOUND, call(other, "/api/objects/politica_credito/records/$ofProgram").first)
    }

    @Test
    fun `a course version bound to v1 still proposes with v1 after v2 is published, and its approval keeps the snapshot`() {
        val authority = account("AUTORIDAD_ACADEMICA")
        val key = uniqueName("ects-")
        val v1 = policy(authority, key)
        ok(publish(authority, v1))
        val version = approvedVersion(v1)
        val v2 = created(call(authority, "/api/lms/credits/policies/$v1/new-version", emptyMap()))["id"].asString()
        send("PATCH", authority, "/api/objects/politica_credito/records/$v2", mapOf("attributes" to mapOf("parametros" to """{"horasPorCredito": "30"}""")))
        ok(publish(authority, v2))

        val proposal = ok(call(authority, "/api/lms/credits/versions/$version/proposal"))
        assertEquals(v1, proposal["policyId"].asString())
        assertEquals(key, proposal["proposal"]["policyId"].asString())
        assertEquals("1", proposal["proposal"]["policyVersion"].asString())
        // 216 h a 25 h por crédito: 8,64 → 8,5
        assertEquals("8.5", proposal["proposal"]["proposed"].asString())

        val approval = created(approve(authority, version))
        assertEquals(key, approval["policyId"].asString())
        assertEquals("1", approval["policyVersion"].asString())
        assertEquals("25", approval["parameters"]["horasPorCredito"].asString())
        val row = get("/api/objects/aprobacion_creditos/records/${approval["id"].asString()}", admin)["attributes"]
        assertEquals(v1, row["politica_credito"].let { if (it.isObject) it["id"] else it }.asString())
        assertEquals("$key@1", get("/api/objects/version_curso/records/$version", admin)["attributes"]["politica_creditos"].asString())
    }

    @Test
    fun `an approval needs the bound policy published and of the version's credit system`() {
        val authority = account("AUTORIDAD_ACADEMICA")
        val draft = policy(authority, uniqueName("ects-"))
        assertEquals(HttpStatus.CONFLICT, approve(authority, approvedVersion(draft)).first)

        val peru = policy(authority, uniqueName("pe-"), "sistema" to "PE_CREDITO", "metodo" to "PE_LEY30220_HORAS_LECTIVAS", "jurisdiccion" to "PE")
        ok(publish(authority, peru))
        // una versión ECTS ligada a una política peruana no propone: no hay conversión entre sistemas
        val mismatched = approvedVersion(peru)
        assertTrue(ok(call(authority, "/api/lms/credits/versions/$mismatched/proposal"))["proposal"].isNull)
        assertEquals(HttpStatus.CONFLICT, approve(authority, mismatched).first)

        // la misma, en una versión PE_CREDITO: 6 × 12 h de teoría = 72 / 16 = 4,5; 6 × 12 h de práctica = 72 / 32 = 2,25
        val peruvian = approvedVersion(peru, system = "PE_CREDITO")
        val approval = created(approve(authority, peruvian))
        assertEquals("PE_CREDITO", approval["system"].asString())
        assertEquals("6.5", approval["proposed"].asString())
    }

    private fun created(result: Pair<HttpStatus, JsonNode?>): JsonNode {
        assertEquals(HttpStatus.CREATED, result.first, result.second.toString())
        return result.second!!
    }
}
