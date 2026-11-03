package elearning.ai

import elearning.jobs.Job
import elearning.jobs.JobHandler
import elearning.jobs.JobQueue
import elearning.jobs.JobRunner
import elearning.jobs.JobState
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.logging.LoggingSystem
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.util.UUID

// las claves de los proveedores fuera de registros y persistencia (RNF-SEG-09, ADR-0033 §12, E32.5): la aplicación entera con
// las claves centinela del doble HTTP y los registros de Embabel, Spring AI, OkHttp y los SDK en DEBUG. Una ejecución completa
// y otra fallida (401) por proveedor pasan por la cola, como pasará un paso de la canalización, y después la clave no está en
// lo capturado de la salida (el arranque incluido), ni en ninguna tabla, ni en lms.trabajo.error ni en AgentRun.error; y
// actuator no la expone.
//
// Contexto propio (por los niveles de registro) y capturado desde antes de crearse: OutputCaptureExtension empieza en su
// beforeAll y Spring crea el contexto al preparar la primera instancia. Que el arranque quede dentro se comprueba, no se
// supone. Al terminar, el contexto se cierra y los niveles vuelven a lo que eran
@ExtendWith(OutputCaptureExtension::class)
@TestPropertySource(
    properties = [
        "logging.level.com.embabel=DEBUG",
        "logging.level.org.springframework.ai=DEBUG",
        "logging.level.okhttp3=DEBUG",
        "logging.level.com.anthropic=DEBUG",
        "logging.level.com.openai=DEBUG",
        "logging.level.elearning=DEBUG",
    ],
)
@Import(ProviderKeysApiTest.Step::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProviderKeysApiTest : ProviderDoubleApiTest() {
    @Autowired
    private lateinit var queue: JobQueue

    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var log: AgentExecutionLog

    @Autowired
    private lateinit var db: DatabaseClient

    private fun enqueue(provider: String): UUID = runBlocking { queue.enqueue(ORGANIZATION, STEP, "\"$provider\"", maxAttempts = 1) }

    private fun runQueue() = runBlocking { while (runner.runOnce() > 0) Unit }

    private fun runOf(job: UUID): AgentExecution {
        val id =
            runBlocking {
                db
                    .sql("SELECT id FROM lms.ejecucion_agente WHERE organizacion_id = :org AND trabajo_id = :job")
                    .bind("org", ORGANIZATION)
                    .bind("job", job)
                    .map { row, _ -> row.get("id", UUID::class.java)!! }
                    .one()
                    .awaitSingle()
            }
        return runBlocking { log.find(ORGANIZATION, id) }!!
    }

    // las tablas de la base que no son del sistema, lms y el audit_log de Wasichai entre ellas
    private fun tables(): List<String> =
        runBlocking {
            db
                .sql(
                    """
                    SELECT format('%I.%I', table_schema, table_name) AS name FROM information_schema.tables
                    WHERE table_type = 'BASE TABLE' AND table_schema NOT IN ('pg_catalog', 'information_schema') AND table_schema NOT LIKE 'pg\_%'
                    ORDER BY 1
                    """.trimIndent(),
                ).map { row, _ -> row.get("name", String::class.java)!! }
                .all()
                .collectList()
                .awaitSingle()
        }

    // las filas de la tabla que contienen el texto en cualquier columna
    private fun rowsWith(
        table: String,
        text: String,
    ): Long =
        runBlocking {
            db
                .sql("SELECT count(*) AS n FROM $table t WHERE position(:text IN CAST(to_jsonb(t) AS text)) > 0")
                .bind("text", text)
                .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        }

    @Test
    fun `a complete run and a failed one leave the keys out of the log, of every table and of the errors`(output: CapturedOutput) {
        ProviderDouble.anthropicReplies("messages-outline.json")
        ProviderDouble.openAiFails(401)
        val anthropicDone = enqueue("anthropic")
        val openAiFailed = enqueue("openai")
        runQueue()
        ProviderDouble.reset()
        ProviderDouble.openAiReplies("responses-outline.json")
        ProviderDouble.anthropicFails(401)
        val openAiDone = enqueue("openai")
        val anthropicFailed = enqueue("anthropic")
        runQueue()

        for (job in listOf(anthropicDone, openAiDone)) {
            assertThat(runBlocking { queue.find(ORGANIZATION, job) }!!.state).isEqualTo(JobState.DONE)
            val run = runOf(job)
            assertThat(run.status).isEqualTo(AgentExecutionStatus.SUCCEEDED)
            assertThat(run.output!!["lessons"]).hasSize(2)
        }
        for (job in listOf(openAiFailed, anthropicFailed)) {
            val status = runBlocking { queue.find(ORGANIZATION, job) }!!
            assertThat(status.state).isEqualTo(JobState.FAILED)
            assertThat(status.error).isEqualTo(REJECTED)
            val run = runOf(job)
            assertThat(run.status).isEqualTo(AgentExecutionStatus.FAILED)
            // el texto exacto: ni el cuerpo del proveedor, ni cabeceras, ni la clave, tampoco tapada
            assertThat(run.error).isEqualTo(REJECTED)
        }

        val captured = output.all
        // el arranque está en lo capturado, y los registros en DEBUG también
        assertThat(captured).contains("AI: provider anthropic").contains("DEBUG")
        for (key in KEYS) assertThat(captured).describedAs("the captured log").doesNotContain(key)

        val tables = tables()
        assertThat(tables).anyMatch { it.startsWith("lms.") }.anyMatch { it.contains("audit_log") }
        for (key in KEYS) {
            val leaks = tables.filter { rowsWith(it, key) > 0 }
            assertThat(leaks).describedAs("tables holding a provider key").isEmpty()
        }
    }

    @Test
    fun `actuator and the configuration endpoints do not expose the keys`() {
        for (token in listOf(null, bearer())) {
            val (status, index) = fetch("/actuator", token)
            val links =
                if (status == 200) {
                    json
                        .readTree(index!!)["_links"]
                        .values()
                        .filterNot { it["templated"].asBoolean() }
                        .map { URI(it["href"].asString()).path }
                } else {
                    emptyList()
                }
            for (path in links + listOf("/api/health", "/actuator/health", "/api/lms/public/openapi")) {
                val (_, body) = fetch(path, token)
                for (key in KEYS) assertThat(body.orEmpty()).describedAs("$path with ${token != null}").doesNotContain(key)
            }
            for (path in EXCLUDED) {
                assertThat(fetch(path, token).first / 100).describedAs("$path with ${token != null}").isNotEqualTo(2)
            }
        }
    }

    private fun fetch(
        path: String,
        token: String?,
    ): Pair<Int, String?> {
        val result =
            client
                .get()
                .uri(path)
                .headers { headers -> token?.let { headers.set("Authorization", it) } }
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return result.status.value() to result.responseBody
    }

    // un paso de prueba de la canalización: abre su AgentRun, llama al modelo del proveedor que dice el trabajo y cierra la
    // ejecución con la salida o con el mensaje del fallo, que deja seguir hacia la cola
    @TestConfiguration(proxyBeanMethods = false)
    class Step {
        @Bean
        fun providerKeysStep(
            calls: ModelCalls,
            log: AgentExecutionLog,
        ): JobHandler =
            object : JobHandler {
                override val type = STEP

                override suspend fun handle(job: Job): String? {
                    val provider = job.payload.trim('"')
                    val model = if (provider == "openai") ProviderDoubleApiTest.OPENAI_MODEL else ProviderDoubleApiTest.ANTHROPIC_MODEL
                    val id =
                        log.start(
                            NewAgentExecution(
                                organizationId = job.organizationId,
                                jobId = job.id,
                                step = AgentStep.COURSE_DESIGN,
                                requestedBy = REQUESTER,
                                courseId = COURSE,
                                objective = "Proponer el temario del curso",
                                inputs = mapOf("prompt" to PROMPT),
                                generator = Generator.MODEL,
                                model = ModelChoice(provider, model, SETTINGS),
                                promptVersion = "outline@1",
                                templateVersion = "outline@1",
                                idempotencyKey = "COURSE_DESIGN:${job.id}",
                                attempt = job.attempt,
                            ),
                        )
                    try {
                        val reply = calls.createObject(model, SETTINGS, PROMPT, CourseOutline::class.java)
                        log.finish(job.organizationId, id, AgentExecutionOutcome(AgentExecutionStatus.SUCCEEDED, output = reply.value, usage = reply.usage))
                    } catch (e: Exception) {
                        log.finish(job.organizationId, id, AgentExecutionOutcome(AgentExecutionStatus.FAILED, error = e.message))
                        throw e
                    }
                    return null
                }
            }
    }

    companion object {
        const val STEP = "test-provider-keys"
        val ORGANIZATION: UUID = UUID.randomUUID()
        val REQUESTER: UUID = UUID.randomUUID()
        val COURSE: UUID = UUID.randomUUID()
        val SETTINGS = ModelSettings(maxTokens = 512)
        const val PROMPT = "Propón el temario de un curso corto de Kotlin para principiantes"
        const val REJECTED = "the AI provider rejected the request (401)"
        val KEYS = listOf(ProviderDouble.ANTHROPIC_KEY, ProviderDouble.OPENAI_KEY)

        // lo que llevaría las claves: el entorno, las propiedades enlazadas y un volcado de memoria (application.yml)
        val EXCLUDED =
            listOf(
                "/actuator/env",
                "/actuator/env/ANTHROPIC_API_KEY",
                "/actuator/configprops",
                "/actuator/configprops/embabel.agent.platform.models.anthropic",
                "/actuator/heapdump",
            )

        private val DEBUG_LOGGERS = listOf("com.embabel", "org.springframework.ai", "okhttp3", "com.anthropic", "com.openai", "elearning")

        @JvmStatic
        @AfterAll
        fun restoreLogLevels() {
            val logging = LoggingSystem.get(ProviderKeysApiTest::class.java.classLoader)
            DEBUG_LOGGERS.forEach { logging.setLogLevel(it, null) }
        }
    }
}
