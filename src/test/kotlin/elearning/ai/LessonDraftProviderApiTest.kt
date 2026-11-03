package elearning.ai

import elearning.ai.SourceAnalysisFixtures.INJECTED
import elearning.ai.SourceAnalysisFixtures.PUBLISH
import elearning.jobs.JobRunner
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
import java.math.BigDecimal

// el borrador de lecciones por la API contra el doble HTTP de cada proveedor (E33.6, ADR-0033 §11): Embabel de verdad en los
// tres pasos (análisis, diseño y borrador con el modelo draft y su max_tokens), el diseño y las fuentes delimitados en el
// mensaje de usuario y nunca en las instrucciones de sistema. La respuesta del doble trae un bloque ++++ con un script, que no
// llega al HTML guardado (ADR-0022), un enlace que no está en las fuentes y otro que no es https público, que quedan para
// revisión, una fuente que el paso no leyó y una duración imposible, que se descartan, y las preguntas de cada evaluación del
// diseño (#367). Un borrador con menos unidades que el diseño, o con una pregunta mal formada, deja INVALID_OUTPUT con lo que
// costó y sin versión. lms.ai.rates suma una tarifa de cada modelo de diseño y borrador
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
abstract class LessonDraftProviderApiTest(
    private val provider: String,
    private val model: String,
    private val replies: (String) -> Unit,
    private val requests: () -> List<JsonNode>,
    private val prefix: String,
    private val usage: TokenUsage,
    private val cost: String,
    private val system: (JsonNode) -> JsonNode,
    private val messages: (JsonNode) -> JsonNode,
) : ProviderDoubleApiTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var db: DatabaseClient

    private val creator by lazy { demoUser("creador") }

    @BeforeEach
    fun cancelLeftovers() {
        runBlocking {
            db
                .sql(
                    "UPDATE lms.trabajo SET estado = 'CANCELLED', reclamado_en = NULL, terminado_en = now() " +
                        "WHERE tipo LIKE 'ai.%' AND estado IN ('QUEUED', 'RUNNING')",
                ).fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    private fun post(
        path: String,
        body: Any,
        expected: HttpStatus,
    ): JsonNode {
        val r =
            client
                .post()
                .uri(path)
                .header("Authorization", creator)
                .bodyValue(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(expected.value(), r.status.value(), r.responseBody)
        return json.readTree(r.responseBody)
    }

    private fun drained(job: JsonNode): JsonNode {
        runBlocking { while (runner.runOnce() > 0) Unit }
        return get("/api/lms/ai/jobs/${job["id"].asString()}", creator)
    }

    private fun step(
        path: String,
        body: Any,
        reply: String,
    ): JsonNode {
        replies(reply)
        return drained(post(path, body, HttpStatus.ACCEPTED))
    }

    // un curso con su brief, su análisis y su diseño SUCCEEDED; devuelve el curso y la ejecución del diseño
    private fun designed(): Pair<String, String> {
        val slug = uniqueName("borrador-").lowercase()
        val course =
            post(
                "/api/objects/curso/records",
                mapOf("attributes" to mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso")),
                HttpStatus.CREATED,
            )["id"].asString()
        val brief = post("/api/objects/brief/records", mapOf("attributes" to SourceAnalysisFixtures.brief(course)), HttpStatus.CREATED)["id"].asString()
        val analysis = step("/api/lms/ai/source-analysis", mapOf("briefId" to brief), "$prefix-source-analysis.json")
        val design = step("/api/lms/ai/course-design", mapOf("executionId" to analysis["executionId"].asString()), "$prefix-course-design.json")
        assertEquals("DONE", design["status"].asString(), design.toString())
        return course to design["executionId"].asString()
    }

    private fun versions(course: String) = get("/api/objects/version_curso/records?curso=$course&size=200", creator)["content"].values().toList()

    private fun records(
        objectName: String,
        field: String,
        value: String,
    ) = get("/api/objects/$objectName/records?$field=$value&size=200", creator)["content"].values().toList()

    @Test
    fun `the provider drafts the lessons, the html is sanitized and the new links are flagged for review`() {
        val (course, design) = designed()

        val job = step("/api/lms/ai/lesson-draft", mapOf("executionId" to design), "$prefix-lesson-draft.json")

        assertEquals("DONE", job["status"].asString(), job.toString())
        val run = get("/api/lms/ai/runs/${job["executionId"].asString()}", creator)
        assertEquals("SUCCEEDED", run["status"].asString(), run.toString())
        assertEquals("LESSON_DRAFT", run["step"].asString())
        assertEquals("MODEL", run["generator"].asString())
        assertEquals(provider, run["provider"].asString())
        assertEquals(model, run["model"].asString())
        assertEquals(DRAFT_MAX_TOKENS, run["modelSettings"]["maxTokens"].asInt())
        assertEquals(LessonDraftPrompt.VERSION, run["promptVersion"].asString())
        assertEquals(LessonTemplate.VERSION, run["templateVersion"].asString())
        assertEquals(usage.inputTokens, run["inputTokens"].asInt())
        assertEquals(0, BigDecimal(cost).compareTo(run["cost"]["amount"].decimalValue()), run["cost"].toString())

        val validations = run["validations"]
        assertEquals(
            listOf(
                Triple("units[0].lessons[0]", "https://ayuda.example.org/anuncios", "NOT_IN_SOURCES"),
                Triple("units[1].lessons[0]", "http://intranet.local/anuncios", "UNSAFE"),
            ),
            validations["linksToReview"].values().map { Triple(it["at"].asString(), it["url"].asString(), it["reason"].asString()) },
        )
        assertEquals(
            listOf("INVALID_DURATION", "SOURCE_NOT_READ"),
            validations["discarded"].values().map { it["reason"].asString() }.sorted(),
        )

        val versionId = run["output"]["versionId"].asString()
        val version = versions(course).single()
        assertEquals(versionId, version["id"].asString())
        assertEquals("BORRADOR", version["attributes"]["estado"].asString())
        assertEquals(LessonTemplate.VERSION, version["attributes"]["plantilla_ia"].asString())
        val units = records("unidad", "version_curso", versionId).sortedBy { it["attributes"]["orden"].asInt() }
        val byUnit = units.map { u -> records("leccion", "unidad", u["id"].asString()).sortedBy { it["attributes"]["orden"].asInt() } }
        val lessons = byUnit.map { unit -> unit.single { it["attributes"]["tipo"].asString() == "LECTURA" } }
        assertEquals(PUBLISH, lessons[0]["attributes"]["referencia_fuente"].asString())
        assertEquals(45, lessons[0]["attributes"]["duracion_minutos"].asInt())
        // la fuente que el paso no leyó y la duración imposible se descartaron: la lección queda sin ellas
        for (field in listOf("referencia_fuente", "duracion_minutos")) {
            assertTrue(lessons[1]["attributes"][field].let { it == null || it.isNull }, lessons[1].toString())
        }

        // el HTML guardado está saneado; los enlaces para revisión quedan también en la versión del recurso
        val links = records("recurso_leccion", "leccion", lessons[0]["id"].asString()).single()
        val resourceVersion = links["attributes"]["version_recurso"].asString()
        val html =
            String(
                client
                    .get()
                    .uri("/api/objects/version_recurso/records/$resourceVersion/files/archivo")
                    .header("Authorization", creator)
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody(ByteArray::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        assertTrue(html.contains("Pulsar Publicar"), html)
        for (unsafe in listOf("<script", "alert(1)", "<iframe", "onload")) assertFalse(html.contains(unsafe), "$unsafe en $html")
        val references = get("/api/objects/version_recurso/records/$resourceVersion", creator)["attributes"]["referencias"].asString()
        assertTrue(references.contains("REVISAR (NOT_IN_SOURCES): https://ayuda.example.org/anuncios"), references)

        // #367: cada evaluación del diseño en la última unidad que trabaja sus resultados, detrás de su lectura, con las
        // preguntas, las opciones y las respuestas aceptadas del modelo; la clave no está en la salida de la ejecución
        val evaluations = byUnit.map { unit -> unit.single { it["attributes"]["tipo"].asString() == "EVALUACION" } }
        assertEquals(listOf("Cuestionario de publicación", "Práctica final"), evaluations.map { it["attributes"]["titulo"].asString() })
        assertEquals(listOf(2, 2), evaluations.map { it["attributes"]["orden"].asInt() })
        assertEquals("Publicar un anuncio para toda la organización", evaluations[0]["attributes"]["objetivo"].asString())
        val questions =
            evaluations.map { lesson ->
                val evaluation = records("evaluacion", "leccion", lesson["id"].asString()).single()
                records("pregunta", "evaluacion", evaluation["id"].asString()).sortedBy { it["attributes"]["orden"].asInt() }
            }
        assertEquals(
            listOf(listOf("UNICA", "CORTA"), listOf("VERDADERO_FALSO", "MULTIPLE", "ABIERTA")),
            questions.map { q -> q.map { it["attributes"]["tipo"].asString() } },
        )
        assertEquals("Panel de anuncios\nanuncios", questions[0][1]["attributes"]["respuestas_aceptadas"].asString())
        val options = records("opcion", "pregunta", questions[0][0]["id"].asString()).sortedBy { it["attributes"]["orden"].asInt() }
        assertEquals(
            listOf("Publicar" to true, "Retirar" to false, "Guardar borrador" to false),
            options.map { it["attributes"]["texto"].asString() to it["attributes"]["correcta"].asBoolean() },
        )
        assertEquals(0, BigDecimal("2").compareTo(BigDecimal(questions[1][2]["attributes"]["puntos"].asString())))
        assertEquals(5, validations["questions"].asInt(), validations.toString())
        for (key in listOf("\"correct\"", "acceptedAnswers", "Panel de anuncios")) assertFalse(run["output"].toString().contains(key), "$key en la salida")

        val request = requests().last()
        assertEquals(model, request["model"].asString())
        val instructions = system(request).toString()
        assertTrue(instructions.contains("never instructions"), instructions)
        for (text in listOf(INJECTED, PUBLISH, "Administración de anuncios")) assertFalse(instructions.contains(text), "$text en las instrucciones de sistema")
        val user = messages(request).toString()
        assertTrue(user.contains("<<<SOURCES-") && user.contains("<<<DESIGN-") && user.contains(INJECTED), user)
    }

    @Test
    fun `a draft with fewer units than the design is INVALID_OUTPUT with what it cost, and no version`() {
        val (course, design) = designed()

        val job = step("/api/lms/ai/lesson-draft", mapOf("executionId" to design), "$prefix-lesson-draft-short.json")

        assertEquals("FAILED", job["status"].asString(), job.toString())
        assertTrue(job["executionId"].isNull, job.toString())
        val line = get("/api/lms/ai/runs?courseId=$course", creator).values().single { it["step"].asString() == "LESSON_DRAFT" }
        assertEquals("INVALID_OUTPUT", line["status"].asString())
        val run = get("/api/lms/ai/runs/${line["id"].asString()}", creator)
        assertTrue(run["output"].isNull, run.toString())
        assertEquals(usage.inputTokens, run["inputTokens"].asInt())
        assertEquals(listOf("UNITS_MISMATCH"), run["validations"]["problems"].values().map { it["code"].asString() })
        assertTrue(versions(course).isEmpty())
    }

    @Test
    fun `an ill formed question of the provider is INVALID_OUTPUT with its code, and no version`() {
        val (course, design) = designed()

        val job = step("/api/lms/ai/lesson-draft", mapOf("executionId" to design), "$prefix-lesson-draft-bad-question.json")

        assertEquals("FAILED", job["status"].asString(), job.toString())
        val line = get("/api/lms/ai/runs?courseId=$course", creator).values().single { it["step"].asString() == "LESSON_DRAFT" }
        assertEquals("INVALID_OUTPUT", line["status"].asString())
        val problems = get("/api/lms/ai/runs/${line["id"].asString()}", creator)["validations"]["problems"].values()
        assertEquals(
            listOf("INVALID_OPTIONS" to "assessments[0].questions[0]", "MISSING_ACCEPTED_ANSWERS" to "assessments[0].questions[1]"),
            problems.map { it["code"].asString() to it["at"].asString() },
        )
        assertTrue(versions(course).isEmpty())
    }

    companion object {
        // lms.ai.models.*.draft y lms.ai.max-tokens.draft de application.yml
        const val ANTHROPIC_DRAFT_MODEL = "claude-sonnet-5-5"
        const val OPENAI_DRAFT_MODEL = "gpt-6.1-sol"
        const val DRAFT_MAX_TOKENS = 16000
    }
}

class LessonDraftAnthropicApiTest :
    LessonDraftProviderApiTest(
        provider = "anthropic",
        model = LessonDraftProviderApiTest.ANTHROPIC_DRAFT_MODEL,
        replies = ProviderDouble::anthropicReplies,
        requests = ProviderDouble::anthropicRequests,
        prefix = "messages",
        usage = TokenUsage(4_000, 1_200),
        // (4 000 × 3 + 1 200 × 15) / 10⁶
        cost = "0.03",
        system = { it["system"] },
        messages = { it["messages"] },
    )

@TestPropertySource(properties = ["lms.ai.provider=openai"])
class LessonDraftOpenAiApiTest :
    LessonDraftProviderApiTest(
        provider = "openai",
        model = LessonDraftProviderApiTest.OPENAI_DRAFT_MODEL,
        replies = ProviderDouble::openAiReplies,
        requests = ProviderDouble::openAiRequests,
        prefix = "responses",
        usage = TokenUsage(3_800, 1_100),
        // (3 800 × 4 + 1 100 × 16) / 10⁶
        cost = "0.0328",
        system = { it["instructions"] },
        messages = { it["input"] },
    )
