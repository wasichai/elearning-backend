package elearning.ai

import elearning.ai.SourceAnalysisFixtures.INJECTED
import elearning.jobs.JobRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordService

// la unidad de demostración de extremo a extremo con Embabel de verdad contra el doble HTTP de cada proveedor (E38.1, #300,
// ADR-0033 §11): el mismo recorrido que DemoUnitApiTest (DemoUnitFlow), con las respuestas de src/test/resources/ai/<proveedor>/
// de los pasos (análisis, diseño, borrador) y de la opinión de consistencia de cada validación. Ninguna petición sale de la
// máquina ni lleva herramientas; el texto de las fuentes nunca llega a las instrucciones de sistema. Las anotaciones son las de
// LessonDraftProviderApiTest a propósito: las clases de cada proveedor comparten contexto de Spring
@Import(SourceAnalysisFixtures.FakeSource::class, LessonDraftFixtures.FakeRenderer::class)
@TestPropertySource(
    properties = [
        "lms.jobs.scheduling=false",
        "lms.jobs.backoff=0s",
        "lms.importer.allowed-sources=acme/manuales",
        "lms.ai.rates[2].provider=anthropic",
        "lms.ai.rates[2].model=${LessonDraftProviderApiTest.ANTHROPIC_DRAFT_MODEL}",
        "lms.ai.rates[2].input-per-million=3",
        "lms.ai.rates[2].output-per-million=15",
        "lms.ai.rates[2].currency=USD",
        "lms.ai.rates[2].since=2026-01-01",
        "lms.ai.rates[3].provider=openai",
        "lms.ai.rates[3].model=${LessonDraftProviderApiTest.OPENAI_DRAFT_MODEL}",
        "lms.ai.rates[3].input-per-million=4",
        "lms.ai.rates[3].output-per-million=16",
        "lms.ai.rates[3].currency=USD",
        "lms.ai.rates[3].since=2026-01-01",
    ],
)
abstract class DemoUnitProviderApiTest(
    private val provider: String,
    // el modelo de cada petición, en orden: análisis (extracción), diseño, borrador y la opinión de cada una de las dos validaciones
    private val models: List<String>,
    private val replies: (String) -> Unit,
    private val requests: () -> List<JsonNode>,
    private val prefix: String,
    // dónde pone cada API las instrucciones de sistema
    private val system: (JsonNode) -> JsonNode,
) : ProviderDoubleApiTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var db: DatabaseClient

    private val admin by lazy { bearer() }

    private val flow by lazy {
        DemoUnitFlow(
            client = client,
            json = json,
            runner = runner,
            records = records,
            db = db,
            admin = admin,
            account = { roles -> account(roles) },
            student = { acceptTerms(account(listOf("ESTUDIANTE"))) },
            uniqueName = { uniqueName(it) },
            reply = { file -> replies("$prefix-$file") },
        )
    }

    private fun account(roles: List<String>): String {
        val email = "${uniqueName("demo")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Persona", "password" to DEMO_PASSWORD, "roles" to roles))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    @BeforeEach
    fun cancelLeftovers() {
        flow.cancelLeftovers()
    }

    @Test
    fun `the provider takes a brief to a published unit that an enrolled student reads in the classroom`() {
        val result = flow.run(generator = "MODEL", opinion = "DONE")

        for (run in result.runs.values) assertEquals(provider, run["provider"].asString(), run.toString())
        assertEquals(models.take(3), listOf("SOURCE_ANALYSIS", "COURSE_DESIGN", "LESSON_DRAFT").map { result.runs.getValue(it)["model"].asString() })
        // la opinión de cada validación es del proveedor, con su propia ejecución
        for (report in result.reports) {
            assertEquals(provider, report["opinion"]["provider"].asString(), report["opinion"].toString())
            assertEquals("APPROVED", report["opinion"]["verdict"].asString())
        }

        // una petición por paso y por opinión, ni una más; ninguna con herramientas, y la fuente (con su orden inyectada)
        // nunca en las instrucciones de sistema
        val sent = requests()
        assertEquals(models, sent.map { it["model"].asString() })
        for (request in sent) {
            assertFalse(request.has("tools") || request.has("tool_choice"), request.toString())
            assertFalse(system(request).toString().contains(INJECTED), "la fuente en las instrucciones de sistema")
        }
        assertTrue(sent.any { it.toString().contains(INJECTED) }, "la fuente va en el mensaje de usuario")
    }
}

// con las dos claves y sin lms.ai.provider, la canalización usa Anthropic
class DemoUnitAnthropicApiTest :
    DemoUnitProviderApiTest(
        provider = "anthropic",
        models =
            listOf(
                ProviderDoubleApiTest.ANTHROPIC_MODEL,
                LessonDraftProviderApiTest.ANTHROPIC_DRAFT_MODEL,
                LessonDraftProviderApiTest.ANTHROPIC_DRAFT_MODEL,
                ProviderDoubleApiTest.ANTHROPIC_MODEL,
                ProviderDoubleApiTest.ANTHROPIC_MODEL,
            ),
        replies = ProviderDouble::anthropicReplies,
        requests = ProviderDouble::anthropicRequests,
        prefix = "messages",
        system = { it["system"] },
    )

@TestPropertySource(properties = ["lms.ai.provider=openai"])
class DemoUnitOpenAiApiTest :
    DemoUnitProviderApiTest(
        provider = "openai",
        models =
            listOf(
                ProviderDoubleApiTest.OPENAI_MODEL,
                LessonDraftProviderApiTest.OPENAI_DRAFT_MODEL,
                LessonDraftProviderApiTest.OPENAI_DRAFT_MODEL,
                ProviderDoubleApiTest.OPENAI_MODEL,
                ProviderDoubleApiTest.OPENAI_MODEL,
            ),
        replies = ProviderDouble::openAiReplies,
        requests = ProviderDouble::openAiRequests,
        prefix = "responses",
        system = { it["instructions"] },
    )
