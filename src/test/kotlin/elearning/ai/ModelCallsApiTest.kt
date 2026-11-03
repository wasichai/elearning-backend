package elearning.ai

import com.embabel.agent.config.models.anthropic.AnthropicProperties
import com.embabel.agent.config.models.openai.OpenAiProperties
import elearning.jobs.PermanentJobFailure
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.util.UUID

// la salida tipada de ejemplo de un paso
data class CourseOutline(
    val title: String,
    val lessons: List<OutlineLesson>,
)

data class OutlineLesson(
    val title: String,
    val minutes: Int,
)

// una llamada tipada por Embabel 1.5.4 contra el doble HTTP de cada proveedor (ADR-0033 §4, §6 y §11): lo que se envía, lo
// que se deserializa, el consumo que llega al registro AgentRun y cómo sale cada error para la cola. Los recuentos de
// peticiones fijan los reintentos reales: Embabel hace 2 intentos por llamada (llm-operations.data-binding) y el SDK del
// proveedor repite 429 y 5xx dos veces dentro de cada uno, así que un 429 son 2 × 3 peticiones (docs/ai §2)
class ModelCallsApiTest : ProviderDoubleApiTest() {
    @Autowired
    private lateinit var calls: ModelCalls

    @Autowired
    private lateinit var log: AgentExecutionLog

    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var anthropicProperties: AnthropicProperties

    @Autowired
    private lateinit var openAiProperties: OpenAiProperties

    private val admin by lazy { bearer() }
    private val creator by lazy { demoUser("creador") }

    // un proveedor del doble: cómo se le configura una respuesta y qué peticiones recibió
    private enum class Fake(
        val id: String,
        val model: String,
        val replies: (String) -> Unit,
        val fails: (Int) -> Unit,
        val requests: () -> List<JsonNode>,
        val outline: String,
        val invalid: String,
        val usage: TokenUsage,
    ) {
        ANTHROPIC(
            "anthropic",
            ProviderDoubleApiTest.ANTHROPIC_MODEL,
            ProviderDouble::anthropicReplies,
            ProviderDouble::anthropicFails,
            ProviderDouble::anthropicRequests,
            "messages-outline.json",
            "messages-invalid-json.json",
            TokenUsage(1_200, 300),
        ),
        OPENAI(
            "openai",
            ProviderDoubleApiTest.OPENAI_MODEL,
            ProviderDouble::openAiReplies,
            ProviderDouble::openAiFails,
            ProviderDouble::openAiRequests,
            "responses-outline.json",
            "responses-invalid-json.json",
            TokenUsage(1_100, 280),
        ),
    }

    private fun outline(double: Fake): ModelReply<CourseOutline> = calls.createObject(double.model, SETTINGS, PROMPT, CourseOutline::class.java)

    @Test
    fun `both providers point at the double, never at their public api`() {
        assertThat(environment.getProperty("ANTHROPIC_BASE_URL")).isEqualTo(ProviderDouble.baseUrl).startsWith("http://127.0.0.1:")
        assertThat(environment.getProperty("OPENAI_BASE_URL")).isEqualTo("${ProviderDouble.baseUrl}/v1")
        assertThat(anthropicProperties.baseUrl).isEqualTo(ProviderDouble.baseUrl)
        assertThat(openAiProperties.baseUrl).isEqualTo("${ProviderDouble.baseUrl}/v1")
        assertThat(listOf(environment.getProperty("ANTHROPIC_BASE_URL"), environment.getProperty("OPENAI_BASE_URL")))
            .noneMatch { it!!.contains("api.anthropic.com") || it.contains("api.openai.com") }
    }

    @Test
    fun `anthropic answers a typed outline through embabel, with the step model, max_tokens and the prompt`() {
        ProviderDouble.anthropicReplies("messages-outline.json")

        val reply = outline(Fake.ANTHROPIC)

        assertThat(reply.value).isEqualTo(EXPECTED)
        assertThat(reply.usage).isEqualTo(TokenUsage(1_200, 300))
        val request = ProviderDouble.anthropicRequests().single()
        assertThat(ProviderDouble.requestCount()).isEqualTo(1)
        assertThat(request["model"].asString()).isEqualTo(ANTHROPIC_MODEL)
        assertThat(request["max_tokens"].asInt()).isEqualTo(SETTINGS.maxTokens)
        assertThat(request["messages"].toString()).contains(PROMPT)
        // Embabel pide el esquema JSON del tipo en las instrucciones de sistema
        assertThat(request["system"].toString()).contains("minutes", "lessons")
    }

    @Test
    fun `openai answers a typed outline through embabel over the responses api, with the step model and max_output_tokens`() {
        ProviderDouble.openAiReplies("responses-outline.json")

        val reply = outline(Fake.OPENAI)

        assertThat(reply.value).isEqualTo(EXPECTED)
        assertThat(reply.usage).isEqualTo(TokenUsage(1_100, 280))
        val request = ProviderDouble.openAiRequests().single()
        assertThat(ProviderDouble.requestCount()).isEqualTo(1)
        assertThat(request["model"].asString()).isEqualTo(OPENAI_MODEL)
        assertThat(request["max_output_tokens"].asInt()).isEqualTo(SETTINGS.maxTokens)
        assertThat(request["input"].toString()).contains(PROMPT)
        assertThat(request["text"]["format"]["type"].asString()).isEqualTo("json_schema")
    }

    // el criterio de E32.4 (#275) que dependía del doble: una ejecución contra él guarda el consumo del proveedor y su coste
    @Test
    fun `an execution against each double records the provider token usage and its cost`() {
        val course = UUID.fromString(get("/api/objects/curso/records", admin)["content"][0]["id"].asString())
        val requester = UUID.fromString(get("/api/auth/me", creator)["userId"].asString())
        // lms.ai.rates de ProviderDoubleApiTest: (1 200 × 1 + 300 × 5) / 10⁶ y (1 100 × 2 + 280 × 8) / 10⁶
        val expectedCost = mapOf(Fake.ANTHROPIC to BigDecimal("0.0027"), Fake.OPENAI to BigDecimal("0.00444"))
        for (double in Fake.entries) {
            double.replies(double.outline)
            val id =
                runBlocking {
                    log.start(
                        NewAgentExecution(
                            organizationId = DEMO_ORGANIZATION,
                            jobId = UUID.randomUUID(),
                            step = AgentStep.COURSE_DESIGN,
                            requestedBy = requester,
                            courseId = course,
                            objective = "Proponer el temario del curso",
                            inputs = mapOf("prompt" to PROMPT),
                            generator = Generator.MODEL,
                            model = ModelChoice(double.id, double.model, SETTINGS),
                            promptVersion = "outline@1",
                            templateVersion = "outline@1",
                            idempotencyKey = "COURSE_DESIGN:${UUID.randomUUID()}",
                            attempt = 1,
                        ),
                    )
                }
            val reply = outline(double)
            runBlocking {
                log.finish(DEMO_ORGANIZATION, id, AgentExecutionOutcome(AgentExecutionStatus.SUCCEEDED, output = reply.value, usage = reply.usage))
            }

            val run = get("/api/lms/ai/runs/$id", creator)
            assertThat(run["status"].asString()).isEqualTo("SUCCEEDED")
            assertThat(run["provider"].asString()).isEqualTo(double.id)
            assertThat(run["model"].asString()).isEqualTo(double.model)
            assertThat(run["inputTokens"].asInt()).isEqualTo(double.usage.inputTokens)
            assertThat(run["outputTokens"].asInt()).isEqualTo(double.usage.outputTokens)
            assertThat(run["cost"]["amount"].decimalValue()).isEqualByComparingTo(expectedCost.getValue(double))
            assertThat(run["cost"]["currency"].asString()).isEqualTo("USD")
            assertThat(run["output"]["lessons"][1]["title"].asString()).isEqualTo("Funciones")
            ProviderDouble.reset()
        }
    }

    // 400: reintentar no lo arregla. Embabel lo repite una vez (su reintento no distingue) y el SDK no
    @Test
    fun `a 400 from either provider is a permanent failure for the job queue`() {
        for (double in Fake.entries) {
            double.fails(400)

            assertThatThrownBy { outline(double) }
                .isInstanceOf(PermanentJobFailure::class.java)
                .hasMessageContaining("rejected the request (400)")
            assertThat(double.requests()).describedAs(double.id).hasSize(EMBABEL_ATTEMPTS)
            ProviderDouble.reset()
        }
    }

    // 401: la clave no vale. el mensaje, que acaba en lms.trabajo.error y en AgentRun.error, es solo el estado: ni el cuerpo
    // del proveedor (el de OpenAI cita parte de la clave), ni cabeceras, ni la clave (RNF-SEG-09)
    @Test
    fun `a 401 from either provider is permanent and its message carries neither the response, nor headers, nor the key`() {
        for (double in Fake.entries) {
            double.fails(401)

            val failure = runCatching { outline(double) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(PermanentJobFailure::class.java).hasMessage("the AI provider rejected the request (401)")
            assertThat(failure.toString())
                .doesNotContain(ProviderDouble.ANTHROPIC_KEY, ProviderDouble.OPENAI_KEY, "sk-doubl")
                .doesNotContainIgnoringCase("x-api-key")
                .doesNotContainIgnoringCase("authorization")
                .doesNotContainIgnoringCase("bearer")
            assertThat(double.requests()).describedAs(double.id).hasSize(EMBABEL_ATTEMPTS)
            ProviderDouble.reset()
        }
    }

    @Test
    fun `a 429 or a 500 from either provider is transient, the queue retries it`() {
        for (double in Fake.entries) {
            for (status in listOf(429, 500)) {
                double.fails(status)

                assertThatThrownBy { outline(double) }
                    .isInstanceOf(ProviderUnavailable::class.java)
                    .hasMessage("the AI provider failed ($status)")
                assertThat(double.requests()).describedAs("${double.id} $status").hasSize(EMBABEL_ATTEMPTS * SDK_ATTEMPTS)
                ProviderDouble.reset()
            }
        }
    }

    // la salida del modelo no es el JSON del tipo: Embabel lo intenta una segunda vez (la corrección acotada de docs/ai §5)
    // y después es un fallo permanente, que el paso registrará como INVALID_OUTPUT
    @Test
    fun `invalid json from either provider is a permanent failure after one correction attempt`() {
        for (double in Fake.entries) {
            double.replies(double.invalid)

            assertThatThrownBy { outline(double) }
                .isInstanceOf(PermanentJobFailure::class.java)
                .hasMessageContaining("does not match the expected type")
            assertThat(double.requests()).describedAs(double.id).hasSize(EMBABEL_ATTEMPTS)
            ProviderDouble.reset()
        }
    }

    // sin respuesta preparada, la petición no empareja: WireMock responde 404 y la prueba fallaría en su @AfterEach. Aquí se
    // comprueba a mano y se vacía el doble para que esta prueba pase
    @Test
    fun `a request the double does not match fails the test`() {
        ProviderDouble.anthropicReplies("messages-outline.json")

        assertThatThrownBy { outline(Fake.OPENAI) }.isInstanceOf(PermanentJobFailure::class.java).hasMessageContaining("(404)")

        assertThat(ProviderDouble.unmatchedRequests()).isNotEmpty().allMatch { it.url == ProviderDouble.OPENAI_RESPONSES }
        assertThatThrownBy { ProviderDouble.assertNoUnmatchedRequests() }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("unmatched request")
            .hasMessageContaining(ProviderDouble.OPENAI_RESPONSES)
        ProviderDouble.reset()
    }

    private companion object {
        val DEMO_ORGANIZATION: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val SETTINGS = ModelSettings(maxTokens = 512, temperature = 0.2)
        const val PROMPT = "Propón el temario de un curso corto de Kotlin para principiantes"
        val EXPECTED = CourseOutline("Introducción a Kotlin", listOf(OutlineLesson("Variables y tipos", 30), OutlineLesson("Funciones", 45)))

        // embabel.agent.platform.llm-operations.data-binding.max-attempts de application.yml
        const val EMBABEL_ATTEMPTS = AiStatus.MAX_ATTEMPTS

        // el primero y los dos reintentos de anthropic-java y openai-java ante 408, 409, 429 y 5xx
        const val SDK_ATTEMPTS = 3
    }
}
