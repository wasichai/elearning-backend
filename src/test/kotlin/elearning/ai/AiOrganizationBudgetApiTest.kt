package elearning.ai

import elearning.jobs.JobQueue
import elearning.jobs.JobRunner
import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

// el presupuesto de IA de la organización por período (#368, revisión de seguridad de la F5, docs/security §4.5 D): sin él,
// quien crea cursos estrena presupuesto con cada uno. Aquí 1 USD al mes, sin presupuesto por curso: lo gastado en un curso
// cualquiera de la organización en el mes en curso cuenta para todos. Agotado, encolar es 429 con Retry-After hasta el mes
// siguiente, también en un curso nuevo; el paso ya en cola falla sin reintento y sin llamar; la opinión de consistencia se
// omite con BUDGET_SPENT, y lo ya resuelto por su clave se reutiliza igual. Lo gastado queda (lms.ejecucion_agente no se
// borra) y solo este contexto tiene presupuesto de organización, así que cada prueba gasta lo suyo antes de mirar y ninguna
// necesita el presupuesto libre: el paso a cero del período siguiente lo prueban AiStepKeyTest y BudgetPeriod
@Import(AiJobsApiTest.Steps::class, AiJobsApiTest.EnqueueController::class)
@TestPropertySource(
    properties = [
        "lms.jobs.scheduling=false",
        "lms.jobs.backoff=0s",
        "lms.jobs.max-attempts=3",
        "lms.jobs.max-active-per-requester=10",
        "lms.ai.limits.max-active-per-organization=10",
        "lms.ai.limits.organization-budget=1",
        "lms.ai.limits.organization-budget-period=MONTH",
        "lms.ai.limits.currency=USD",
        // con presupuesto, cada modelo de paso necesita su tarifa (AiStatus): el de extracción y consistencia y el de los demás
        "lms.ai.rates[0].provider=anthropic",
        "lms.ai.rates[0].model=${ProviderDoubleApiTest.ANTHROPIC_MODEL}",
        "lms.ai.rates[0].input-per-million=1",
        "lms.ai.rates[0].output-per-million=5",
        "lms.ai.rates[0].currency=USD",
        "lms.ai.rates[0].since=2026-01-01",
        "lms.ai.rates[1].provider=openai",
        "lms.ai.rates[1].model=${ProviderDoubleApiTest.OPENAI_MODEL}",
        "lms.ai.rates[1].input-per-million=2",
        "lms.ai.rates[1].output-per-million=8",
        "lms.ai.rates[1].currency=USD",
        "lms.ai.rates[1].since=2026-01-01",
        "lms.ai.rates[2].provider=anthropic",
        "lms.ai.rates[2].model=claude-sonnet-5-5",
        "lms.ai.rates[2].input-per-million=3",
        "lms.ai.rates[2].output-per-million=15",
        "lms.ai.rates[2].currency=USD",
        "lms.ai.rates[2].since=2026-01-01",
    ],
)
class AiOrganizationBudgetApiTest : ProviderDoubleApiTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var queue: JobQueue

    @Autowired
    private lateinit var log: AgentExecutionLog

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var records: RecordService

    private val admin by lazy { bearer() }
    private val creator by lazy { demoUser("creador") }
    private val fixtures by lazy { DraftFixtures(records, log) }

    private data class Answer(
        val status: HttpStatus,
        val body: JsonNode?,
        val retryAfter: String?,
    )

    // lo que otra prueba dejó pendiente no cuenta para los topes de esta
    @BeforeEach
    fun reset() {
        runBlocking {
            db
                .sql(
                    "UPDATE lms.trabajo SET estado = 'CANCELLED', reclamado_en = NULL, terminado_en = now() " +
                        "WHERE (tipo LIKE 'ai.%' OR tipo = '${DraftValidations.JOB_TYPE}') AND estado IN ('QUEUED', 'RUNNING')",
                ).fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        AiJobsApiTest.script.clear()
        AiJobsApiTest.calls.set(0)
        AiJobsApiTest.drafts.clear()
    }

    private fun drain() = runBlocking { while (runner.runOnce() > 0) Unit }

    private fun exchange(
        method: String,
        path: String,
        token: String,
        body: Any? = null,
    ): Answer {
        val spec = if (method == "POST") client.post().uri(path).bodyValue(body ?: emptyMap<String, Any>()) else client.get().uri(path)
        val r =
            spec
                .header("Authorization", token)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return Answer(
            HttpStatus.valueOf(r.status.value()),
            r.responseBody?.let { runCatching { json.readTree(it) }.getOrNull() },
            r.responseHeaders.getFirst("Retry-After"),
        )
    }

    private fun ok(answer: Answer): JsonNode {
        assertEquals(HttpStatus.OK, answer.status, answer.body.toString())
        return answer.body!!
    }

    private fun course(): UUID {
        val slug = uniqueName("presupuesto-").lowercase()
        val attributes = mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso")
        val created = exchange("POST", "/api/objects/curso/records", admin, mapOf("attributes" to attributes))
        assertEquals(HttpStatus.CREATED, created.status, created.body.toString())
        return UUID.fromString(created.body!!["id"].asString())
    }

    private fun enqueue(
        course: UUID,
        tag: String = UUID.randomUUID().toString(),
    ) = exchange("POST", AiJobsApiTest.ENQUEUE, creator, mapOf("courseId" to course.toString(), "tag" to tag))

    private fun userId(token: String): UUID = UUID.fromString(get("/api/auth/me", token)["userId"].asString())

    private fun account(vararg roles: String): String {
        val email = "${uniqueName("presupuesto")}@demo.wasichai-learning.test"
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

    // una ejecución SUCCEEDED, como la deja un paso; usage con coste: con la tarifa del modelo de extracción (1 y 5 por millón)
    private fun execution(
        course: UUID,
        key: String,
        usage: TokenUsage?,
        model: String = ANTHROPIC_MODEL,
    ): UUID =
        runBlocking {
            val id =
                log.start(
                    NewAgentExecution(
                        organizationId = ORGANIZATION,
                        jobId = UUID.randomUUID(),
                        step = AgentStep.ASSISTANT,
                        requestedBy = UUID.randomUUID(),
                        courseId = course,
                        objective = "Gastar",
                        inputs = mapOf("n" to 1),
                        generator = Generator.MODEL,
                        model = ModelChoice("anthropic", model, ModelSettings(maxTokens = 1_024)),
                        promptVersion = "p@1",
                        templateVersion = "t@1",
                        idempotencyKey = key,
                        attempt = 1,
                    ),
                )
            log.finish(ORGANIZATION, id, AgentExecutionOutcome(AgentExecutionStatus.SUCCEEDED, mapOf("answer" to "hecho"), usage = usage)).id
        }

    // gasta el presupuesto del mes en un curso nuevo de la organización: (1 000 000 × 1 + 10 000 × 5) / 10⁶ = 1.05 > 1
    private fun spendTheMonth() {
        execution(course(), "spend:${UUID.randomUUID()}", TokenUsage(1_000_000, 10_000))
    }

    // los segundos hasta el 1 del mes siguiente, a las 00:00 en UTC
    private fun untilNextMonth(): Long = Duration.between(Instant.now(), BudgetPeriod.MONTH.next(Instant.now())).seconds

    @Test
    fun `a spent organization budget is 429 until the next month in any course and fails the waiting step without retry`() {
        val course = course()
        // un paso ya en cola, encolado antes de que se gastara el presupuesto (directo en la cola: AiJobs ya lo rechazaría)
        val request = AiJobsApiTest.stepRequest(course, "waiting")
        val payload =
            AiStepPayload(
                requester = userId(creator),
                step = request.step,
                courseId = course,
                unitId = null,
                versionId = null,
                objective = request.objective,
                inputs = AiJobs.MAPPER.valueToTree(request.inputs),
                sources = request.sources,
                model = request.model,
                promptVersion = request.promptVersion,
                templateVersion = request.templateVersion,
                key = AiJobs.keyOf(request),
            )
        val type = AiJobs.typeOf(request.step)
        val serialized = AiJobs.MAPPER.writeValueAsString(payload)
        val waiting = runBlocking { queue.enqueue(ORGANIZATION, type, serialized, key = payload.key, requester = payload.requester) }
        spendTheMonth()

        drain()

        val failed = ok(exchange("GET", "/api/lms/ai/jobs/$waiting", creator))
        assertEquals("FAILED", failed["status"].asString(), failed.toString())
        assertEquals(1, failed["attempts"].asInt(), "agotado, no se reintenta")
        assertTrue(failed["error"].asString().contains("budget of the organization"), failed.toString())
        assertEquals(0, AiJobsApiTest.calls.get(), "sin llamar al proveedor")

        // ni en el curso del paso ni en uno recién creado: el presupuesto ya no se estrena con cada curso
        for (target in listOf(course, course())) {
            val before = untilNextMonth()
            val refused = enqueue(target)
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, refused.status, refused.body.toString())
            assertTrue(refused.body.toString().contains("budget of the organization"), refused.body.toString())
            val retryAfter = refused.retryAfter!!.toLong()
            assertTrue(retryAfter in (before - 60)..(before + 2), "Retry-After $retryAfter, hasta el mes siguiente $before")
        }
        assertEquals(0, AiJobsApiTest.calls.get())
    }

    @Test
    fun `with the organization budget spent a step already resolved by its key is reused and does not count`() {
        val course = course()
        val resolved = execution(course, AiJobs.keyOf(AiJobsApiTest.stepRequest(course, "resolved")), usage = null)
        spendTheMonth()

        val answer = enqueue(course, "resolved")
        assertEquals(HttpStatus.ACCEPTED, answer.status, answer.body.toString())
        drain()

        val done = ok(exchange("GET", "/api/lms/ai/jobs/${answer.body!!["id"].asString()}", creator))
        assertEquals("DONE", done["status"].asString(), done.toString())
        assertEquals(resolved.toString(), done["executionId"].asString(), "devuelve la ejecución que ya resolvió la clave")
        assertEquals(0, AiJobsApiTest.calls.get(), "sin llamar al proveedor")
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, enqueue(course, "new").status, "lo nuevo sí cuenta")
    }

    @Test
    fun `with the organization budget spent the consistency opinion is skipped as BUDGET_SPENT without calling the provider`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val draft = fixtures.draft(uniqueName("PRS-").uppercase(), userId(author).toString(), defective = false)
        spendTheMonth()

        val started = exchange("POST", "/api/lms/ai/validations", reviewer, mapOf("versionId" to draft.version))
        assertEquals(HttpStatus.ACCEPTED, started.status, started.body.toString())
        drain()
        val job = ok(exchange("GET", "/api/lms/ai/validations/jobs/${started.body!!["id"].asString()}", reviewer))
        assertEquals("DONE", job["status"].asString(), job.toString())
        val view = ok(exchange("GET", "/api/lms/ai/validations?versionId=${draft.version}", reviewer))

        assertFalse(view["report"]["blocking"].asBoolean(), view.toString())
        assertEquals("SKIPPED", view["opinion"]["status"].asString(), view.toString())
        assertEquals("BUDGET_SPENT", view["opinion"]["reason"].asString())
        // sin respuesta preparada: una petición al doble no emparejaría y haría fallar la prueba
        assertEquals(0, ProviderDouble.requestCount(), "sin llamar al proveedor")
        val steps = ok(exchange("GET", "/api/lms/ai/runs?courseId=${draft.course}", reviewer)).values().map { it["step"].asString() }
        assertFalse("CONSISTENCY_REVIEW" in steps, steps.toString())
    }

    companion object {
        val ORGANIZATION: UUID = ClassroomApiTestSupport.ORG
    }
}
