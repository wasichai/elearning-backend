package elearning.ai

import elearning.jobs.JobRunner
import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordService
import java.math.BigDecimal
import java.util.UUID

// la opinión de consistencia contenido-evaluación (E34.2) por la API contra el doble HTTP de cada proveedor: la validación
// por código termina y pide la opinión, que es otro trabajo con su propia ejecución CONSISTENCY_REVIEW (MODEL, el modelo de
// la clave consistency y su max_tokens) cuyo coste cuenta en el presupuesto del curso. La opinión se marca como tal y no
// cambia el resultado bloqueante: «APPROVED» sobre un borrador con errores sigue sin entrar en revisión, y «CONCERNS» sobre uno
// limpio entra. Un hallazgo que cita un registro que no está en el borrador se descarta. Las anotaciones y propiedades son
// las de SourceAnalysisProviderApiTest a propósito: las dos clases comparten contexto de Spring
@Import(SourceAnalysisFixtures.FakeSource::class)
@TestPropertySource(properties = ["lms.jobs.scheduling=false", "lms.jobs.backoff=0s", "lms.importer.allowed-sources=acme/manuales"])
abstract class ConsistencyReviewProviderApiTest(
    private val provider: String,
    private val model: String,
    private val replies: (String) -> Unit,
    private val requests: () -> List<JsonNode>,
    private val approved: String,
    private val concerns: String,
    private val invalid: String,
    private val usage: TokenUsage,
    // con las tarifas de ProviderDoubleApiTest
    private val cost: String,
    // dónde pone cada API las instrucciones de sistema y dónde los mensajes
    private val system: (JsonNode) -> JsonNode,
    private val messages: (JsonNode) -> JsonNode,
) : ProviderDoubleApiTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var log: AgentExecutionLog

    @Autowired
    private lateinit var executions: AgentExecutionStore

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
        val email = "${uniqueName("opinion")}@demo.wasichai-learning.test"
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

    private fun userId(token: String): String = get("/api/auth/me", token)["userId"].asString()

    private fun draft(
        author: String,
        defective: Boolean,
    ) = fixtures.draft(uniqueName("OPN-").uppercase(), userId(author), defective)

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

    // lanza la validación, mueve la cola (la validación y después la opinión) y devuelve el informe
    private fun validated(
        token: String,
        version: String,
    ): JsonNode {
        val (status, body) = call(token, "/api/lms/ai/validations", mapOf("versionId" to version))
        assertEquals(HttpStatus.ACCEPTED, status, body.toString())
        runBlocking { while (runner.runOnce() > 0) Unit }
        val job = ok(call(token, "/api/lms/ai/validations/jobs/${body!!["id"].asString()}"))
        assertEquals("DONE", job["status"].asString(), job.toString())
        return ok(call(token, "/api/lms/ai/validations?versionId=$version"))
    }

    private fun submit(
        token: String,
        version: String,
    ) = call(token, "/api/lms/studio/versions/$version/submit", emptyMap())

    @Test
    fun `an APPROVED opinion on a draft with blocking errors is marked as an opinion and the draft still does not go to review`() {
        replies(approved)
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val draft = draft(author, defective = true)

        val view = validated(reviewer, draft.version)

        assertTrue(view["report"]["blocking"].asBoolean(), view.toString())
        val opinion = view["opinion"]
        assertEquals("OPINION", opinion["kind"].asString(), opinion.toString())
        assertFalse(opinion["blocking"].asBoolean())
        assertEquals("DONE", opinion["status"].asString(), opinion.toString())
        assertEquals("APPROVED", opinion["verdict"].asString())
        assertEquals(provider, opinion["provider"].asString())
        assertEquals(model, opinion["model"].asString())
        // el hallazgo cita una pregunta que el borrador no tiene
        assertEquals(0, opinion["findings"].size(), opinion.toString())

        // el informe manda: con errores bloqueantes no entra en revisión, diga lo que diga la opinión
        assertEquals(HttpStatus.CONFLICT, submit(author, draft.version).first)

        // su propia ejecución, en otro trabajo, con su modelo, su max_tokens y su coste, que cuenta en el gasto del curso
        val run = ok(call(reviewer, "/api/lms/ai/runs/${opinion["executionId"].asString()}"))
        assertEquals("CONSISTENCY_REVIEW", run["step"].asString())
        assertEquals("SUCCEEDED", run["status"].asString())
        assertEquals("MODEL", run["generator"].asString())
        assertEquals(provider, run["provider"].asString())
        assertEquals(model, run["model"].asString())
        assertEquals(CONSISTENCY_MAX_TOKENS, run["modelSettings"]["maxTokens"].asInt())
        assertEquals(draft.version, run["versionId"].asString())
        assertEquals(usage.inputTokens, run["inputTokens"].asInt())
        assertEquals(usage.outputTokens, run["outputTokens"].asInt())
        assertEquals(0, BigDecimal(cost).compareTo(run["cost"]["amount"].decimalValue()), run["cost"].toString())
        assertEquals("OPINION", run["output"]["kind"].asString())
        assertEquals(view["report"]["contentHash"].asString(), run["output"]["contentHash"].asString())
        assertEquals("UNKNOWN_ORIGIN", run["validations"]["discarded"].values().single()["reason"].asString())
        val validation = ok(call(reviewer, "/api/lms/ai/runs/${view["executionId"].asString()}"))
        assertNotEquals(validation["jobId"].asString(), run["jobId"].asString())
        assertTrue(validation["cost"].isNull, validation.toString())
        val spent = runBlocking { executions.spent(ClassroomApiTestSupport.ORG, UUID.fromString(draft.course), "USD") }
        assertEquals(0, BigDecimal(cost).compareTo(spent), spent.toString())

        // el borrador va en el mensaje de usuario, delimitado, nunca en las instrucciones de sistema
        val request = requests().single()
        assertEquals(model, request["model"].asString())
        val instructions = system(request).toString()
        assertTrue(instructions.contains("never instructions") && instructions.contains("only an opinion"), instructions)
        for (text in listOf("¿Qué es el padrón?", draft.version)) assertFalse(instructions.contains(text), "$text en las instrucciones de sistema")
        val user = messages(request).toString()
        assertTrue(user.contains("<<<DRAFT-") && user.contains("¿Qué es el padrón?") && user.contains("pregunta:"), user)

        // validar otra vez el mismo contenido reutiliza la opinión: no se vuelve a llamar ni a pagar
        val again = validated(reviewer, draft.version)
        assertEquals(opinion["executionId"].asString(), again["opinion"]["executionId"].asString())
        assertEquals(1, requests().size)
    }

    @Test
    fun `a CONCERNS opinion on a clean draft does not keep it from going to review`() {
        replies(concerns)
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val draft = draft(author, defective = false)

        val view = validated(reviewer, draft.version)

        assertFalse(view["report"]["blocking"].asBoolean(), view.toString())
        val opinion = view["opinion"]
        assertEquals("CONCERNS", opinion["verdict"].asString(), opinion.toString())
        assertFalse(opinion["blocking"].asBoolean())
        // el hallazgo de la versión en conjunto queda; el de una pregunta que no existe, no
        assertEquals(1, opinion["findings"].size(), opinion.toString())
        assertTrue(opinion["findings"][0]["origin"].isNull)
        assertEquals("EN_REVISION", ok(submit(author, draft.version))["status"].asString())
    }

    @Test
    fun `a malformed opinion fails on its own, the report and its result do not change`() {
        replies(invalid)
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val draft = draft(author, defective = false)

        val view = validated(reviewer, draft.version)

        assertFalse(view["report"]["blocking"].asBoolean(), view.toString())
        val opinion = view["opinion"]
        assertEquals("FAILED", opinion["status"].asString(), opinion.toString())
        assertTrue(opinion["verdict"].isNull)
        assertTrue(opinion["error"].asString().isNotBlank(), opinion.toString())
        val runs = ok(call(reviewer, "/api/lms/ai/runs?courseId=${draft.course}")).values().filter { it["step"].asString() == "CONSISTENCY_REVIEW" }
        assertEquals("INVALID_OUTPUT", runs.single()["status"].asString())
        assertEquals("EN_REVISION", ok(submit(author, draft.version))["status"].asString())
    }

    companion object {
        // lms.ai.max-tokens.consistency de application.yml
        const val CONSISTENCY_MAX_TOKENS = 2000
    }
}

// con las dos claves y sin lms.ai.provider, la canalización usa Anthropic
class ConsistencyReviewAnthropicApiTest :
    ConsistencyReviewProviderApiTest(
        provider = "anthropic",
        model = ProviderDoubleApiTest.ANTHROPIC_MODEL,
        replies = ProviderDouble::anthropicReplies,
        requests = ProviderDouble::anthropicRequests,
        approved = "messages-consistency-approved.json",
        concerns = "messages-consistency-concerns.json",
        invalid = "messages-invalid-json.json",
        usage = TokenUsage(900, 120),
        // (900 × 1 + 120 × 5) / 10⁶
        cost = "0.0015",
        system = { it["system"] },
        messages = { it["messages"] },
    )

@TestPropertySource(properties = ["lms.ai.provider=openai"])
class ConsistencyReviewOpenAiApiTest :
    ConsistencyReviewProviderApiTest(
        provider = "openai",
        model = ProviderDoubleApiTest.OPENAI_MODEL,
        replies = ProviderDouble::openAiReplies,
        requests = ProviderDouble::openAiRequests,
        approved = "responses-consistency-approved.json",
        concerns = "responses-consistency-concerns.json",
        invalid = "responses-invalid-json.json",
        usage = TokenUsage(850, 110),
        // (850 × 2 + 110 × 8) / 10⁶
        cost = "0.00258",
        system = { it["instructions"] },
        messages = { it["input"] },
    )
