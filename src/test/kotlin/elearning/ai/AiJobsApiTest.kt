package elearning.ai

import elearning.LmsApiTest
import elearning.jobs.Job
import elearning.jobs.JobHandler
import elearning.jobs.JobQueue
import elearning.jobs.JobRunner
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// los pasos de IA en la cola (E33.2, RF-IA-04, ADR-0033 §5, §6 y §9): idempotencia, reintentos, cancelación y límites. El
// paso es uno de prueba (ASSISTANT, que la canalización no encola) con un proveedor falso que cuenta sus llamadas y responde
// lo que la prueba le pide: un 500, un 429, un 400, una salida inválida o una respuesta con su consumo. Cuando el doble
// WireMock de los proveedores (E32.3, #274) esté en main, una prueba más hará lo mismo contra él, contando sus peticiones.
// Se encola por un controlador de prueba que llama a AiJobs.enqueue como la persona del token, igual que lo hará el
// controlador de cada paso; la cola se mueve a mano con runOnce. Cada prueba usa un curso propio: el presupuesto es por curso
@Import(AiJobsApiTest.Steps::class, AiJobsApiTest.EnqueueController::class)
@TestPropertySource(
    properties = [
        "lms.jobs.scheduling=false",
        "lms.jobs.backoff=0s",
        "lms.jobs.max-attempts=3",
        "lms.jobs.max-active-per-requester=10",
        "lms.ai.limits.max-active-per-organization=3",
        "lms.ai.limits.course-budget=1",
        "lms.ai.limits.currency=USD",
        "lms.ai.limits.concurrency-retry-after=60s",
        "lms.ai.limits.budget-retry-after=1h",
        "lms.ai.rates[0].provider=anthropic",
        "lms.ai.rates[0].model=claude-test-model",
        "lms.ai.rates[0].input-per-million=3",
        "lms.ai.rates[0].output-per-million=15",
        "lms.ai.rates[0].currency=USD",
        "lms.ai.rates[0].since=2026-01-01",
    ],
)
class AiJobsApiTest : LmsApiTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var queue: JobQueue

    @Autowired
    private lateinit var log: AgentExecutionLog

    @Autowired
    private lateinit var db: DatabaseClient

    private val admin by lazy { bearer() }
    private val creator by lazy { demoUser("creador") }
    private val student by lazy { demoUser("estudiante") }

    private data class Answer(
        val status: HttpStatus,
        val body: JsonNode?,
        val retryAfter: String?,
    )

    private data class Run(
        val status: String,
        val attempt: Int,
        val cost: BigDecimal?,
        val jobId: UUID,
    )

    @BeforeEach
    fun reset() {
        // lo que otra prueba dejó pendiente no cuenta para la concurrencia de esta
        sql("UPDATE lms.trabajo SET estado = 'CANCELLED', reclamado_en = NULL, terminado_en = now() WHERE tipo LIKE 'ai.%' AND estado IN ('QUEUED', 'RUNNING')")
        drain()
        script.clear()
        calls.set(0)
        drafts.clear()
        failDraftOnce.set(false)
        cancelDuringCall.set(null)
    }

    private fun sql(statement: String) =
        runBlocking {
            db
                .sql(statement)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }

    private fun drain() =
        runBlocking {
            while (runner.runOnce() > 0) {
                // otra vuelta: un reintento sin espera vuelve a estar disponible
            }
        }

    private fun once() = runBlocking { runner.runOnce() }

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

    private fun course(): UUID {
        val slug = uniqueName("ia-").lowercase()
        val attributes = mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso")
        val created = exchange("POST", "/api/objects/curso/records", admin, mapOf("attributes" to attributes))
        assertEquals(HttpStatus.CREATED, created.status, created.body.toString())
        return UUID.fromString(created.body!!["id"].asString())
    }

    private fun enqueue(
        course: UUID,
        tag: String = UUID.randomUUID().toString(),
        token: String = creator,
    ): Answer = exchange("POST", ENQUEUE, token, mapOf("courseId" to course.toString(), "tag" to tag))

    private fun enqueued(
        course: UUID,
        tag: String = UUID.randomUUID().toString(),
    ): UUID {
        val answer = enqueue(course, tag)
        assertEquals(HttpStatus.ACCEPTED, answer.status, answer.body.toString())
        return UUID.fromString(answer.body!!["id"].asString())
    }

    private fun job(
        id: UUID,
        token: String = creator,
    ): JsonNode {
        val answer = exchange("GET", "/api/lms/ai/jobs/$id", token)
        assertEquals(HttpStatus.OK, answer.status, answer.body.toString())
        return answer.body!!
    }

    private fun cancel(
        id: UUID,
        token: String = creator,
    ) = exchange("POST", "/api/lms/ai/jobs/$id/cancel", token)

    private fun runs(course: UUID): List<Run> =
        runBlocking {
            db
                .sql("SELECT estado, intento, coste, trabajo_id FROM lms.ejecucion_agente WHERE curso_id = :course ORDER BY iniciada_en, intento, id")
                .bind("course", course)
                .map { row, _ ->
                    Run(
                        row.get("estado", String::class.java)!!,
                        row.get("intento", Integer::class.java)!!.toInt(),
                        row.get("coste", BigDecimal::class.java),
                        row.get("trabajo_id", UUID::class.java)!!,
                    )
                }.all()
                .collectList()
                .awaitSingle()
        }

    private fun same(
        expected: String,
        actual: BigDecimal?,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual), "esperaba $expected, fue $actual")

    @Test
    fun `enqueueing the same step returns the same job and, once done, its execution without another provider call`() {
        val course = course()
        val first = enqueued(course, "same")
        assertEquals(first, enqueued(course, "same"), "mientras espera, el mismo trabajo")
        assertNotEquals(first, enqueued(course, "other"), "otras entradas, otro trabajo")

        drain()

        assertEquals(2, calls.get())
        val done = job(first)
        assertEquals("DONE", done["status"].asString())
        assertEquals("ASSISTANT", done["step"].asString())
        val execution = done["executionId"].asString()
        val again = enqueued(course, "same")
        assertNotEquals(first, again, "terminado, la cola hace otro trabajo")
        drain()

        assertEquals(2, calls.get(), "sin otra llamada al proveedor")
        assertEquals("DONE", job(again)["status"].asString())
        assertEquals(execution, job(again)["executionId"].asString(), "devuelve la ejecución existente")
        assertEquals(2, runs(course).size, "ni otra ejecución")
        assertEquals(2, drafts.size, "ni otro borrador")
    }

    @Test
    fun `a 500 and a 429 from the provider are retried and then the step succeeds, paying only the call that answered`() {
        val course = course()
        script.add { throw AiProviderFailure(500, "internal error") }
        script.add { throw AiProviderFailure(429, "rate limited") }
        val id = enqueued(course)

        once()
        assertEquals("QUEUED", job(id)["status"].asString())
        assertTrue(job(id)["error"].asString().contains("500"), job(id).toString())
        once()
        assertEquals("QUEUED", job(id)["status"].asString())
        assertTrue(job(id)["error"].asString().contains("429"), job(id).toString())
        once()

        val done = job(id)
        assertEquals("DONE", done["status"].asString())
        assertEquals(3, done["attempts"].asInt())
        assertEquals(3, calls.get())
        val runs = runs(course)
        assertEquals(listOf("FAILED" to 1, "FAILED" to 2, "SUCCEEDED" to 3), runs.map { it.status to it.attempt })
        assertNull(runs[0].cost)
        assertNull(runs[1].cost)
        // (1 000 × 3 + 200 × 15) / 1 000 000
        same("0.006", runs[2].cost)
        assertEquals(1, drafts.size)
    }

    @Test
    fun `a transient failure is retried only up to the limit of the queue`() {
        val course = course()
        repeat(5) { script.add { throw AiProviderFailure(503, "unavailable") } }
        val id = enqueued(course)

        drain()

        val failed = job(id)
        assertEquals("FAILED", failed["status"].asString())
        assertEquals(3, failed["attempts"].asInt())
        assertEquals(3, calls.get())
        assertFalse(failed["finishedAt"].isNull)
        assertTrue(drafts.isEmpty())
    }

    @Test
    fun `a 400 or an invalid output is not retried and leaves no draft`() {
        val course = course()
        script.add { throw AiProviderFailure(400, "bad request") }
        val refused = enqueued(course)
        drain()

        assertEquals("FAILED", job(refused)["status"].asString())
        assertEquals(1, job(refused)["attempts"].asInt())
        assertTrue(job(refused)["error"].asString().contains("400"))
        assertEquals(1, calls.get())

        script.add { throw AiInvalidOutput("units must not be empty", usage = TokenUsage(1_000, 200), validations = mapOf("schema" to "KO")) }
        val invalid = enqueued(course)
        drain()

        assertEquals("FAILED", job(invalid)["status"].asString())
        assertEquals(1, job(invalid)["attempts"].asInt())
        assertTrue(job(invalid)["error"].asString().contains("not valid"))
        assertEquals(2, calls.get())
        val runs = runs(course)
        assertEquals(listOf("FAILED", "INVALID_OUTPUT"), runs.map { it.status })
        same("0.006", runs[1].cost)
        assertTrue(drafts.isEmpty(), "ni el 400 ni la salida inválida dejan borrador")
    }

    @Test
    fun `retrying after the call does not pay it again nor make a second draft`() {
        val course = course()
        failDraftOnce.set(true)
        val id = enqueued(course)

        once()
        assertEquals("QUEUED", job(id)["status"].asString(), "el borrador falló: el trabajo vuelve a la cola")
        assertTrue(drafts.isEmpty())
        once()

        assertEquals("DONE", job(id)["status"].asString())
        assertEquals(1, calls.get(), "el segundo intento reutiliza la ejecución")
        val runs = runs(course)
        assertEquals(1, runs.size)
        same("0.006", runs.single().cost)
        assertEquals(1, drafts.size)
        assertEquals(job(id)["executionId"].asString(), drafts.single().toString())
    }

    @Test
    fun `cancelling a waiting job leaves it cancelled without calling the provider`() {
        val course = course()
        val id = enqueued(course)

        val cancelled = cancel(id)
        assertEquals(HttpStatus.OK, cancelled.status, cancelled.body.toString())
        assertEquals("CANCELLED", cancelled.body!!["status"].asString())
        assertFalse(cancelled.body["finishedAt"].isNull)
        drain()

        assertEquals(0, calls.get())
        assertEquals("CANCELLED", job(id)["status"].asString())
        assertEquals(HttpStatus.OK, cancel(id).status, "cancelar otra vez es inocuo")
        assertTrue(runs(course).isEmpty())
        assertNotEquals(id, enqueued(course), "cancelado, la clave queda libre")
    }

    @Test
    fun `a job cancelled between two calls to the model does not call it again`() {
        val course = course()
        script.add { throw AiProviderFailure(500, "internal error") }
        val id = enqueued(course)
        once()
        assertEquals("QUEUED", job(id)["status"].asString())

        assertEquals(HttpStatus.OK, cancel(id).status)
        drain()

        assertEquals(1, calls.get())
        assertEquals("CANCELLED", job(id)["status"].asString())
        assertEquals(listOf("FAILED"), runs(course).map { it.status })
        assertTrue(drafts.isEmpty())
    }

    @Test
    fun `a job cancelled while the model answers is stopped before its draft and keeps what the call cost`() {
        val course = course()
        val id = enqueued(course)
        val cancelIt: suspend () -> Unit = { queue.cancel(DEMO_ORGANIZATION, id) }
        cancelDuringCall.set(cancelIt)

        drain()

        assertEquals(1, calls.get())
        val cancelled = job(id)
        assertEquals("CANCELLED", cancelled["status"].asString())
        assertTrue(cancelled["executionId"].isNull)
        val run = runs(course).single()
        assertEquals("CANCELLED", run.status)
        same("0.006", run.cost)
        assertTrue(drafts.isEmpty(), "cancelado, sin borrador")
    }

    @Test
    fun `only whoever asked reads and cancels a job, and a finished one is a conflict`() {
        val course = course()
        val id = enqueued(course)

        assertEquals(HttpStatus.FORBIDDEN, cancel(id, student).status, "sin GENERAR")
        assertEquals(HttpStatus.FORBIDDEN, exchange("GET", "/api/lms/ai/jobs/$id", student).status)
        assertEquals(HttpStatus.NOT_FOUND, cancel(id, admin).status, "de otra persona, también frente al administrador")
        assertEquals(HttpStatus.NOT_FOUND, exchange("GET", "/api/lms/ai/jobs/$id", admin).status)
        assertEquals(HttpStatus.NOT_FOUND, cancel(UUID.randomUUID()).status)
        assertEquals(HttpStatus.FORBIDDEN, enqueue(course, token = student).status, "encolar también exige GENERAR")
        assertEquals("QUEUED", job(id)["status"].asString(), "nadie más lo canceló")

        drain()
        val over = cancel(id)
        assertEquals(HttpStatus.CONFLICT, over.status, over.body.toString())
        assertEquals("DONE", job(id)["status"].asString())
    }

    @Test
    fun `more AI steps than the organization may run at once is 429 with Retry-After`() {
        val course = course()
        val waiting = (1..3).map { enqueued(course, "c$it") }

        val refused = enqueue(course, "c4")
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, refused.status, refused.body.toString())
        assertEquals("60", refused.retryAfter)
        assertEquals(waiting[0], enqueued(course, "c1"), "lo ya pedido no cuenta otra vez")

        assertEquals(HttpStatus.OK, cancel(waiting[0]).status)
        assertEquals(HttpStatus.ACCEPTED, enqueue(course, "c4").status, "terminado uno, cabe otro")
    }

    @Test
    fun `a spent course budget is 429 with Retry-After when enqueueing and a permanent failure when it runs`() {
        val course = course()
        val waiting = enqueued(course)
        // lo gastado en el curso: (300 000 × 3 + 10 000 × 15) / 1 000 000 = 1.05, sobre el presupuesto de 1
        spend(course)

        drain()

        val failed = job(waiting)
        assertEquals("FAILED", failed["status"].asString())
        assertEquals(1, failed["attempts"].asInt(), "agotado, no se reintenta")
        assertTrue(failed["error"].asString().contains("budget"), failed.toString())
        assertEquals(0, calls.get())
        val refused = enqueue(course)
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, refused.status, refused.body.toString())
        assertEquals("3600", refused.retryAfter)
        assertEquals(HttpStatus.ACCEPTED, enqueue(course()).status, "el presupuesto es por curso")
    }

    private fun spend(course: UUID) =
        runBlocking {
            val id =
                log.start(
                    NewAgentExecution(
                        organizationId = DEMO_ORGANIZATION,
                        jobId = UUID.randomUUID(),
                        step = AgentStep.SOURCE_ANALYSIS,
                        requestedBy = UUID.randomUUID(),
                        courseId = course,
                        objective = "Gastar",
                        inputs = mapOf("n" to 1),
                        generator = Generator.MODEL,
                        model = ModelChoice("anthropic", MODEL, ModelSettings(maxTokens = 1_024)),
                        promptVersion = "p@1",
                        templateVersion = "t@1",
                        idempotencyKey = "spend:${UUID.randomUUID()}",
                        attempt = 1,
                    ),
                )
            log.finish(DEMO_ORGANIZATION, id, AgentExecutionOutcome(AgentExecutionStatus.SUCCEEDED, usage = TokenUsage(300_000, 10_000)))
        }

    data class StepBody(
        val courseId: UUID,
        val tag: String,
    )

    // lo que hará el controlador de cada paso: AiJobs.enqueue como quien llama
    @RestController
    class EnqueueController(
        private val jobs: AiJobs,
    ) {
        @PostMapping(ENQUEUE)
        suspend fun enqueue(
            @RequestBody body: StepBody,
        ): ResponseEntity<Map<String, String>> {
            val id =
                jobs.enqueue(
                    AiStepRequest(
                        step = AgentStep.ASSISTANT,
                        courseId = body.courseId,
                        objective = "Responder con lo pedido",
                        inputs = mapOf("tag" to body.tag),
                        sources = listOf(SourceDigest("acme/manuales@1:manual.adoc#intro", "ab".repeat(32))),
                        model = ModelChoice("anthropic", MODEL, ModelSettings(maxTokens = 1_024)),
                        promptVersion = "assistant@1",
                        templateVersion = "none@1",
                    ),
                )
            return ResponseEntity.accepted().body(mapOf("id" to id.toString()))
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Steps {
        @Bean
        fun assistantStep(runner: AiStepRunner): JobHandler =
            object : JobHandler {
                override val type = AiJobs.typeOf(AgentStep.ASSISTANT)

                override suspend fun handle(job: Job): String? =
                    runner.run(job, provider) { run ->
                        // un fallo pasajero al escribir el borrador, después de la llamada
                        if (failDraftOnce.getAndSet(false)) error("the draft store is down")
                        drafts.add(run.id)
                    }
            }
    }

    companion object {
        const val MODEL = "claude-test-model"
        const val ENQUEUE = "/api/test/ai/steps"

        // lo que responde cada llamada, en orden; sin nada más, una respuesta con su consumo
        val script = ConcurrentLinkedQueue<suspend (AiPrompt) -> AiReply>()
        val calls = AtomicInteger()
        val drafts = ConcurrentLinkedQueue<UUID>()
        val failDraftOnce = AtomicBoolean()
        val cancelDuringCall = AtomicReference<(suspend () -> Unit)?>()

        // el proveedor falso: cuenta cada llamada, como contará las peticiones el doble WireMock
        val provider =
            object : AiModelCall {
                override suspend fun call(prompt: AiPrompt): AiReply {
                    calls.incrementAndGet()
                    cancelDuringCall.getAndSet(null)?.invoke()
                    val next = script.poll() ?: return AiReply(output = mapOf("answer" to prompt.inputs["tag"].asString()), usage = TokenUsage(1_000, 200))
                    return next(prompt)
                }
            }
    }
}
