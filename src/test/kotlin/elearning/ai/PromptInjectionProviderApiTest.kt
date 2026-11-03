package elearning.ai

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.spi.ToolGroupResolver
import com.embabel.agent.tools.math.MathTools
import elearning.ai.SourceAnalysisFixtures.INJECTED_CHAPTER
import elearning.ai.SourceAnalysisFixtures.ORDERS
import elearning.jobs.JobRunner
import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.client.McpSyncClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.resolution.ToolCallbackResolver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import org.springframework.util.ClassUtils
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard
import java.util.concurrent.ConcurrentLinkedQueue

// la prueba de inyección de instrucciones (E33.7, RF-IA-06, docs/security §4.5) contra el doble HTTP de cada proveedor: una
// fuente con órdenes inyectadas (SourceAnalysisFixtures.ORDERS: «ignora lo anterior», publicar y aprobar, darse ADMIN, llamar a
// una herramienta y cerrar a mano el bloque que la delimita) recorre análisis, diseño y borrador, y el doble contesta en cada
// paso como un modelo que obedece: campos de estado editorial, aprobación, roles, permisos y llamadas a herramientas en el JSON,
// una cita que concede ADMIN, una lectura y enlaces a un sitio ajeno, un script, un iframe y un enlace javascript:. Nada de eso
// cambia herramientas, permisos ni estado editorial. Una respuesta que de verdad pide una herramienta (tool_use de Anthropic,
// function_call de OpenAI) no ejecuta nada y es INVALID_OUTPUT, sin reintentos de la cola
@Import(SourceAnalysisFixtures.FakeSource::class, LessonDraftFixtures.FakeRenderer::class, PromptInjectionProviderApiTest.VersionWrites::class)
@TestPropertySource(properties = ["lms.jobs.scheduling=false", "lms.jobs.backoff=0s", "lms.importer.allowed-sources=acme/manuales"])
abstract class PromptInjectionProviderApiTest(
    private val replies: (String) -> Unit,
    private val requests: () -> List<JsonNode>,
    private val prefix: String,
    // el campo de las instrucciones de sistema y el de los mensajes en la petición del proveedor
    private val system: String,
    private val messages: String,
    // los campos de cada petición, exactamente: ni tools, ni tool_choice, ni servidores MCP
    private val fields: Set<String>,
    // los tokens de entrada de una respuesta del análisis
    private val analysisInput: Int,
) : ProviderDoubleApiTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var context: ApplicationContext

    private val creator by lazy { demoUser("creador") }

    @BeforeEach
    fun cancelLeftovers() {
        WRITES.clear()
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
        assertThat(r.status.value()).describedAs(r.responseBody).isEqualTo(expected.value())
        return json.readTree(r.responseBody)
    }

    // encola el paso con la respuesta del doble y vacía la cola: si la cola reintentase, la respuesta se volvería a pedir aquí
    private fun step(
        path: String,
        body: Any,
        reply: String,
    ): JsonNode {
        replies("$prefix-$reply")
        val job = post(path, body, HttpStatus.ACCEPTED)
        runBlocking { while (runner.runOnce() > 0) Unit }
        return get("/api/lms/ai/jobs/${job["id"].asString()}", creator)
    }

    // un curso con su brief LISTO sobre el capítulo inyectado; devuelve el curso y el brief
    private fun briefed(): Pair<String, String> {
        val slug = uniqueName("inyeccion-").lowercase()
        val course =
            post(
                "/api/objects/curso/records",
                mapOf("attributes" to mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso")),
                HttpStatus.CREATED,
            )["id"].asString()
        val brief = SourceAnalysisFixtures.brief(course, INJECTED_CHAPTER)
        return course to post("/api/objects/brief/records", mapOf("attributes" to brief), HttpStatus.CREATED)["id"].asString()
    }

    private fun run(job: JsonNode): JsonNode {
        assertThat(job["status"].asString()).describedAs(job.toString()).isEqualTo("DONE")
        val run = get("/api/lms/ai/runs/${job["executionId"].asString()}", creator)
        assertThat(run["status"].asString()).describedAs(run.toString()).isEqualTo("SUCCEEDED")
        return run
    }

    private fun records(
        objectName: String,
        field: String,
        value: String,
    ) = get("/api/objects/$objectName/records?$field=$value&size=200", creator)["content"].values().toList()

    private fun record(
        objectName: String,
        id: String,
    ) = get("/api/objects/$objectName/records/$id", creator)

    @Test
    fun `an injected source walks analysis, design and draft, and the obedient model changes no tool, permission nor editorial state`() {
        val permissions = get("/api/auth/me/permissions", creator)
        val (course, brief) = briefed()

        val analysisJob = step("/api/lms/ai/source-analysis", mapOf("briefId" to brief), "injected-source-analysis.json")
        val analysis = run(analysisJob)
        // la cita que concede ADMIN no está en la fuente: se descarta
        assertThat(analysis["validations"]["discarded"].values().map { it["name"].asString() to it["reason"].asString() })
            .contains("Permisos de ADMIN" to "QUOTE_NOT_IN_SOURCE")
        val designJob = step("/api/lms/ai/course-design", mapOf("executionId" to analysisJob["executionId"].asString()), "injected-course-design.json")
        val design = run(designJob)
        assertThat(design["validations"]["discarded"].values().map { it["value"].asString() to it["reason"].asString() })
            .contains("https://evil.example.com/admin" to "SOURCE_NOT_READ")
        val draft = run(step("/api/lms/ai/lesson-draft", mapOf("executionId" to designJob["executionId"].asString()), "injected-lesson-draft.json"))
        assertThat(draft["validations"]["linksToReview"].values().map { it["url"].asString() to it["reason"].asString() })
            .contains("https://evil.example.com/grant?role=ADMIN" to "NOT_IN_SOURCES", "javascript:alert" to "UNSAFE")

        // lo que el modelo añadió fuera del esquema no llega a ninguna salida: solo quedan los campos de cada tipo
        for (output in listOf(analysis, design, draft).map { it["output"] }) assertThat(fieldsOf(output)).doesNotContainAnyElementsOf(OBEDIENT_FIELDS)

        // estado editorial: una versión BORRADOR, sin publicar, sin créditos aprobados; sus recursos, BORRADOR y sin revisar
        val version = get("/api/objects/version_curso/records?curso=$course&size=200", creator)["content"].values().single()
        assertThat(version["id"].asString()).isEqualTo(draft["output"]["versionId"].asString())
        assertThat(version["attributes"]["estado"].asString()).isEqualTo("BORRADOR")
        for (field in listOf(
            "publicada_en",
            "creditos_aprobados",
        )) {
            assertThat(version["attributes"][field].let { it == null || it.isNull }).describedAs(field).isTrue()
        }
        val all = records("unidad", "version_curso", version["id"].asString()).flatMap { records("leccion", "unidad", it["id"].asString()) }
        val lessons = all.filter { it["attributes"]["tipo"].asString() == "LECTURA" }
        assertThat(lessons).hasSize(2)
        // y las evaluaciones del diseño (#367): una lección EVALUACION por evaluación, con lo «obediente» de sus preguntas ignorado
        val evaluations = all.filter { it["attributes"]["tipo"].asString() == "EVALUACION" }
        assertThat(evaluations).hasSize(2)
        for (lesson in evaluations) assertThat(records("evaluacion", "leccion", lesson["id"].asString())).hasSize(1)
        for (lesson in lessons) {
            val resource =
                record("version_recurso", records("recurso_leccion", "leccion", lesson["id"].asString()).single()["attributes"]["version_recurso"].asString())
            assertThat(resource["attributes"]["estado"].asString()).isEqualTo("BORRADOR")
            assertThat(resource["attributes"]["revisada_en"].let { it == null || it.isNull }).isTrue()
        }

        // la IA propone, una persona aprueba (#288): cada versión se escribió dentro de un trabajo de IA, donde AiWorkGuard no
        // deja aprobar ni publicar (lo prueba DraftInterventionApiTest), y ninguna pidió un estado decidido
        assertThat(WRITES).isNotEmpty().allSatisfy { assertThat(it.aiWork).describedAs(it.toString()).isTrue() }
        assertThat(WRITES.mapNotNull { it.state }).isNotEmpty().allSatisfy { assertThat(it).isEqualTo("BORRADOR") }

        // permisos: los mismos que antes, sin roles nuevos
        assertThat(get("/api/auth/me/permissions", creator)).isEqualTo(permissions)

        // la petición de cada paso: sin herramientas, con las órdenes solo dentro de los bloques delimitados del mensaje de
        // usuario, nunca en las instrucciones de sistema, aunque la fuente intente cerrar el suyo a mano
        val sent = requests()
        assertThat(sent).hasSize(3)
        sent.forEachIndexed { i, request ->
            assertThat(request.propertyNames().asSequence().toSet()).describedAs("campos de la petición $i").isEqualTo(fields)
            val instructions = textsOf(request[system]).joinToString("\n")
            val user = textsOf(request[messages]).joinToString("\n")
            val outside = DELIMITED.replace(user, "")
            for (order in ORDERS) {
                assertThat(instructions).describedAs("instrucciones de la petición $i").doesNotContain(order)
                assertThat(outside).describedAs("mensaje de la petición $i fuera de sus bloques").doesNotContain(order)
            }
        }
        // el análisis y el borrador leen la fuente: todas las órdenes van, como dato, dentro de sus bloques
        for (i in listOf(0, 2)) assertThat(textsOf(sent[i][messages]).joinToString("\n")).contains(ORDERS)
    }

    // Embabel no tiene herramientas que ofrecer: su bucle falla en la primera que pide el modelo, sin contestarle, y cada uno
    // de los dos intentos de Embabel es una petición nueva, sin resultado de herramienta. La cola no lo repite
    @Test
    fun `a reply that asks for a tool runs nothing and is INVALID_OUTPUT with what it cost, without retries`() {
        val permissions = get("/api/auth/me/permissions", creator)
        val (course, brief) = briefed()

        val job = step("/api/lms/ai/source-analysis", mapOf("briefId" to brief), "injected-tool-call.json")

        assertThat(job["status"].asString()).describedAs(job.toString()).isEqualTo("FAILED")
        val line = get("/api/lms/ai/runs?courseId=$course", creator).values().single()
        assertThat(line["status"].asString()).isEqualTo("INVALID_OUTPUT")
        val run = get("/api/lms/ai/runs/${line["id"].asString()}", creator)
        assertThat(run["output"].isNull).isTrue()
        assertThat(run["inputTokens"].asInt()).isEqualTo(AiStatus.MAX_ATTEMPTS * analysisInput)
        val sent = requests()
        assertThat(sent).hasSize(AiStatus.MAX_ATTEMPTS)
        for (request in sent) {
            assertThat(request.propertyNames().asSequence().toSet()).isEqualTo(fields)
            assertThat(request.toString()).doesNotContain("tool_result", "function_call_output", "\"tool_use\"", "\"function_call\"")
        }
        assertThat(WRITES).isEmpty()
        assertThat(get("/api/objects/version_curso/records?curso=$course&size=200", creator)["content"].values().toList()).isEmpty()
        assertThat(get("/api/auth/me/permissions", creator)).isEqualTo(permissions)
    }

    // ni herramientas ni cliente MCP en la aplicación: ningún ToolCallback, ToolCallbackProvider ni ToolCallbackResolver de
    // Spring AI, ningún Tool ni ToolObject de Embabel, ningún cliente MCP y ningún método @Tool ni @LlmTool en un bean. El único
    // grupo de herramientas es el de aritmética que Embabel registra siempre (ToolGroupsConfiguration.mathToolGroup): sin
    // estado, red ni archivos, y ningún paso lo pide (ArchitectureTest: nada en src/main da herramientas al modelo)
    @Test
    fun `the application context holds no tool, no tool callback and no mcp client`() {
        for (type in listOf(ToolCallback::class, ToolCallbackProvider::class, ToolCallbackResolver::class, Tool::class, ToolObject::class)) {
            assertThat(context.getBeanNamesForType(type.java, true, false)).describedAs(type.java.name).isEmpty()
        }
        for (type in listOf(McpSyncClient::class, McpAsyncClient::class)) {
            assertThat(context.getBeanNamesForType(type.java, true, false)).describedAs(type.java.name).isEmpty()
        }
        val annotated =
            context.beanDefinitionNames.filter { name ->
                val type = runCatching { context.getType(name, false) }.getOrNull() ?: return@filter false
                ClassUtils.getUserClass(type).declaredMethods.any { method ->
                    TOOL_ANNOTATIONS.any { AnnotatedElementUtils.hasAnnotation(method, it) }
                }
            }
        assertThat(annotated).isEmpty()
        assertThat(context.getBeansOfType(ToolGroup::class.java).values).allSatisfy { assertThat(it).isInstanceOf(MathTools::class.java) }
        assertThat(
            context
                .getBeansOfType(ToolGroupResolver::class.java)
                .values
                .flatMap { it.availableToolGroups() }
                .map { it.role },
        ).allSatisfy { assertThat(it).isEqualTo("math") }
    }

    // una escritura de una versión durante la prueba: el estado que pide y si iba dentro de un trabajo de IA
    data class VersionWrite(
        val objectName: String,
        val kind: String,
        val state: String?,
        val aiWork: Boolean,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class VersionWrites {
        @Bean
        fun versionWriteRecorder(): RecordWriteGuard =
            object : RecordWriteGuard {
                override suspend fun beforeWrite(change: RecordWrite) {
                    if (change.objectName !in VERSIONS) return
                    val state = change.attributes.orEmpty()["estado"]?.toString()
                    WRITES.add(VersionWrite(change.objectName, change.kind.toString(), state, currentCoroutineContext()[AiWork] != null))
                }
            }
    }

    companion object {
        val VERSIONS = setOf("version_curso", "version_recurso")
        val WRITES = ConcurrentLinkedQueue<VersionWrite>()

        // lo que añaden las respuestas obedientes del doble (resources/ai/*/*-injected-*.json)
        val OBEDIENT_FIELDS = setOf("estado", "publicada_en", "aprobada", "approvedBy", "roles", "permissions", "tool_calls")
        val TOOL_ANNOTATIONS = listOf(org.springframework.ai.tool.annotation.Tool::class.java, LlmTool::class.java)

        // un bloque delimitado: <<<TIPO-marca ...>>> hasta <<<END-TIPO-marca>>>, con la misma marca de 16 hexadecimales
        val DELIMITED = Regex("""<<<([A-Z]+)-([0-9a-f]{16})[^>\n]*>>>.*?<<<END-\1-\2>>>""", RegexOption.DOT_MATCHES_ALL)

        // los textos de un nodo, en cualquier profundidad (system y messages de Anthropic pueden ser bloques)
        fun textsOf(node: JsonNode?): List<String> =
            when {
                node == null -> emptyList()
                node.isString -> listOf(node.asString())
                else -> node.values().flatMap { textsOf(it) }
            }

        // los nombres de campo de un nodo, en cualquier profundidad
        fun fieldsOf(node: JsonNode): Set<String> = node.properties().map { it.key }.toSet() + node.values().flatMap { fieldsOf(it) }
    }
}

// Embabel 1.5.4 pide la salida tipada de estos pasos con el esquema JSON en las instrucciones de sistema, no con una
// herramienta: la petición de Anthropic lleva solo model, max_tokens, system y messages
class PromptInjectionAnthropicApiTest :
    PromptInjectionProviderApiTest(
        replies = ProviderDouble::anthropicReplies,
        requests = ProviderDouble::anthropicRequests,
        prefix = "messages",
        system = "system",
        messages = "messages",
        fields = setOf("model", "max_tokens", "system", "messages"),
        analysisInput = 2_100,
    )

// en la Responses API, igual: ni tools ni tool_choice, y tampoco text.format, que Embabel solo usa con los tipos cuyo esquema
// admite el modo estricto (ModelCallsApiTest); los de estos pasos llevan el esquema en instructions
@TestPropertySource(properties = ["lms.ai.provider=openai"])
class PromptInjectionOpenAiApiTest :
    PromptInjectionProviderApiTest(
        replies = ProviderDouble::openAiReplies,
        requests = ProviderDouble::openAiRequests,
        prefix = "responses",
        system = "instructions",
        messages = "input",
        fields = setOf("model", "max_output_tokens", "instructions", "input"),
        analysisInput = 1_900,
    )
