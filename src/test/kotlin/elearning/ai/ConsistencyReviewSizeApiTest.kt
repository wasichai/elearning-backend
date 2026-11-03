package elearning.ai

import elearning.jobs.JobRunner
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordService

// la opinión de consistencia acota su entrada (#369, L1 de la revisión de seguridad de la F5): el análisis de fuentes corta
// en SourceMaterialReader.MAX_CHARS, y la opinión mandaba el borrador entero, una llamada cara que el presupuesto solo vería
// después. Con lms.ai.limits.consistency-max-chars por debajo del borrador más pequeño, la opinión falla sin llamar al
// proveedor y sin ejecución, el informe la da FAILED con su error y la validación termina igual
@TestPropertySource(properties = ["lms.jobs.scheduling=false", "lms.jobs.backoff=0s", "lms.ai.limits.consistency-max-chars=200"])
class ConsistencyReviewSizeApiTest : ProviderDoubleApiTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var log: AgentExecutionLog

    private val admin by lazy { bearer() }
    private val fixtures by lazy { DraftFixtures(records, log) }

    // los pasos de IA que otra clase dejó pendientes cuentan para la concurrencia de la organización y el tope por persona
    @BeforeEach
    fun cancelLeftovers() {
        runBlocking {
            db
                .sql(
                    "UPDATE lms.trabajo SET estado = 'CANCELLED', reclamado_en = NULL, terminado_en = now() " +
                        "WHERE (tipo LIKE 'ai.%' OR tipo = '${DraftValidations.JOB_TYPE}') AND estado IN ('QUEUED', 'RUNNING')",
                ).fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    private fun account(vararg roles: String): String {
        val email = "${uniqueName("tamano")}@demo.wasichai-learning.test"
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

    private fun call(
        token: String,
        path: String,
        body: Map<String, Any?>? = null,
    ): Pair<HttpStatus, JsonNode?> {
        val spec = if (body == null) client.get().uri(path) else client.post().uri(path).bodyValue(body)
        val r =
            spec
                .header("Authorization", token)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(r.status.value()) to r.responseBody?.let { runCatching { json.readTree(it) }.getOrNull() }
    }

    private fun ok(result: Pair<HttpStatus, JsonNode?>): JsonNode {
        assertEquals(HttpStatus.OK, result.first, result.second.toString())
        return result.second!!
    }

    @Test
    fun `a draft longer than the limit fails its opinion without calling the provider and the report says so`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val draft = fixtures.draft(uniqueName("TAM-").uppercase(), get("/api/auth/me", author)["userId"].asString(), defective = false)

        val (status, body) = call(reviewer, "/api/lms/ai/validations", mapOf("versionId" to draft.version))
        assertEquals(HttpStatus.ACCEPTED, status, body.toString())
        runBlocking { while (runner.runOnce() > 0) Unit }
        assertEquals("DONE", ok(call(reviewer, "/api/lms/ai/validations/jobs/${body!!["id"].asString()}"))["status"].asString())
        val view = ok(call(reviewer, "/api/lms/ai/validations?versionId=${draft.version}"))

        // la validación por código no cambia: el borrador limpio no bloquea
        assertFalse(view["report"]["blocking"].asBoolean(), view.toString())
        val opinion = view["opinion"]
        assertEquals("FAILED", opinion["status"].asString(), opinion.toString())
        assertTrue(opinion["error"].asString().contains("consistency-max-chars"), opinion.toString())
        // sin respuesta preparada en el doble: una petición no emparejaría y haría fallar la prueba
        assertEquals(0, ProviderDouble.requestCount(), "sin llamar al proveedor")
        val steps = ok(call(reviewer, "/api/lms/ai/runs?courseId=${draft.course}")).values().map { it["step"].asString() }
        assertFalse("CONSISTENCY_REVIEW" in steps, "sin ejecución: no se llamó a ningún modelo ($steps)")
        // la opinión falla sola: el borrador entra en revisión
        val submitted = ok(call(author, "/api/lms/studio/versions/${draft.version}/submit", emptyMap()))
        assertEquals("EN_REVISION", submitted["status"].asString())
    }
}
