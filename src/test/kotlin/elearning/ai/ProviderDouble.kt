package elearning.ai

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import elearning.LmsApiTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

// el doble HTTP de los dos proveedores (ADR-0033 §11, docs/testing «Doble HTTP de los proveedores de IA»): un WireMock en
// 127.0.0.1 y puerto dinámico que hace de Anthropic (POST /v1/messages) y de OpenAI (Responses API, POST /v1/responses).
// Las respuestas son archivos de src/test/resources/ai/<proveedor>/. Uno por JVM: arranca la primera vez que se usa y lo
// comparten todas las clases (cada prueba lo vacía con reset). Prueba Embabel, la deserialización, los errores del proveedor
// y el consumo de tokens sin salir de la máquina; no sustituye al doble determinista de docs/ai §7.
//
// Solo empareja una petición con la clave ficticia de su proveedor; cualquier otra recibe el 404 de WireMock y queda como
// no emparejada, lo que hace fallar la prueba (assertNoUnmatchedRequests, que ProviderDoubleApiTest llama tras cada una)
object ProviderDouble {
    // de mentira, y lo bastante largas para que AgentExecutionLog las tape si apareciesen en un texto
    const val ANTHROPIC_KEY = "sk-ant-double-not-a-real-key"
    const val OPENAI_KEY = "sk-double-not-a-real-key"

    const val ANTHROPIC_MESSAGES = "/v1/messages"
    const val OPENAI_RESPONSES = "/v1/responses"

    private val mapper: JsonMapper = JsonMapper.builder().build()

    private val server: WireMockServer by lazy {
        WireMockServer(options().bindAddress(LOOPBACK).dynamicPort()).also { server ->
            server.start()
            Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
        }
    }

    // http://127.0.0.1:<puerto>: la URL base de Anthropic. la de OpenAI lleva /v1, como la de su API. no server.baseUrl(),
    // que dice localhost
    val baseUrl: String get() = "http://$LOOPBACK:${server.port()}"

    // las URL base apuntan al doble, por variable (la que lee Embabel, con precedencia) y por propiedad. Las claves no
    // pueden ir aquí: la compuerta AiProviderGate las mira antes de que Spring aplique @DynamicPropertySource, y van en el
    // @TestPropertySource de ProviderDoubleApiTest
    fun register(registry: DynamicPropertyRegistry) {
        registry.add("ANTHROPIC_BASE_URL") { baseUrl }
        registry.add("${AiProvider.ANTHROPIC.embabelPrefix}.base-url") { baseUrl }
        registry.add("OPENAI_BASE_URL") { "$baseUrl/v1" }
        registry.add("${AiProvider.OPENAI.embabelPrefix}.base-url") { "$baseUrl/v1" }
    }

    // la siguiente llamada de cada proveedor responde 200 con este archivo (p. ej. "messages-outline.json")
    fun anthropicReplies(resource: String) = stub(anthropic(), 200, read("anthropic", resource))

    fun openAiReplies(resource: String) = stub(openAi(), 200, read("openai", resource))

    // el proveedor responde con este estado y el cuerpo de error de su API (error-<estado>.json)
    fun anthropicFails(status: Int) = stub(anthropic(), status, read("anthropic", "error-$status.json"))

    fun openAiFails(status: Int) = stub(openAi(), status, read("openai", "error-$status.json"))

    // las peticiones que llegaron a cada proveedor, en orden, con su cuerpo JSON
    fun anthropicRequests(): List<JsonNode> = bodies(ANTHROPIC_MESSAGES)

    fun openAiRequests(): List<JsonNode> = bodies(OPENAI_RESPONSES)

    // todas las que llegaron, emparejadas o no
    fun requestCount(): Int = server.allServeEvents.size

    fun unmatchedRequests(): List<LoggedRequest> = server.findAllUnmatchedRequests()

    fun assertNoUnmatchedRequests() {
        val unmatched = unmatchedRequests()
        if (unmatched.isNotEmpty()) {
            throw AssertionError(
                "the provider double got ${unmatched.size} unmatched request(s):\n" +
                    unmatched.joinToString("\n") { "- ${it.method} ${it.url} ${it.bodyAsString.take(BODY_PREVIEW)}" },
            )
        }
    }

    // sin respuestas ni peticiones guardadas
    fun reset() = server.resetAll()

    private fun anthropic(): MappingBuilder =
        post(urlPathEqualTo(ANTHROPIC_MESSAGES)).withHeader("x-api-key", equalTo(ANTHROPIC_KEY)).withHeader("anthropic-version", equalTo(ANTHROPIC_VERSION))

    private fun openAi(): MappingBuilder = post(urlPathEqualTo(OPENAI_RESPONSES)).withHeader("Authorization", equalTo("Bearer $OPENAI_KEY"))

    // Retry-After-Ms: los SDK de los dos proveedores repiten 408, 409, 429 y 5xx dos veces con una espera de hasta 8 s, salvo
    // que el servidor diga cuánto esperar. El doble dice 1 ms para que la prueba no espere; cuenta las mismas peticiones
    private fun stub(
        request: MappingBuilder,
        status: Int,
        body: String,
    ) {
        val response = aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)
        if (status >= 400) response.withHeader("Retry-After-Ms", "1")
        server.stubFor(request.willReturn(response))
    }

    private fun bodies(path: String): List<JsonNode> = server.findAll(postRequestedFor(urlPathEqualTo(path))).map { mapper.readTree(it.bodyAsString) }

    private fun read(
        provider: String,
        resource: String,
    ): String {
        val path = "/ai/$provider/$resource"
        return checkNotNull(ProviderDouble::class.java.getResource(path)) { "no test resource $path" }.readText()
    }

    private const val LOOPBACK = "127.0.0.1"

    // la que envía anthropic-java
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val BODY_PREVIEW = 300
}

// base de las pruebas de API contra el doble HTTP: la aplicación entera con los dos proveedores, cada uno con su clave
// ficticia y su URL base en el doble. Las clases que la extienden comparten contexto de Spring. Antes de cada prueba el doble
// se vacía; después, una petición que no emparejó con ninguna respuesta hace fallar la prueba. lms.ai.rates trae una tarifa
// de cada modelo de extracción para comprobar el coste
@TestPropertySource(
    properties = [
        "ANTHROPIC_API_KEY=${ProviderDouble.ANTHROPIC_KEY}",
        "OPENAI_API_KEY=${ProviderDouble.OPENAI_KEY}",
        "embabel.agent.platform.models.anthropic.api-key=",
        "embabel.agent.platform.models.openai.api-key=",
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
    ],
)
abstract class ProviderDoubleApiTest : LmsApiTest() {
    @BeforeEach
    fun resetProviderDouble() = ProviderDouble.reset()

    @AfterEach
    fun noUnmatchedProviderRequests() = ProviderDouble.assertNoUnmatchedRequests()

    companion object {
        // los modelos de extracción de application.yml, que el doble usa en sus respuestas
        const val ANTHROPIC_MODEL = "claude-haiku-5-5"
        const val OPENAI_MODEL = "gpt-6-luna"

        @JvmStatic
        @DynamicPropertySource
        fun providerDouble(registry: DynamicPropertyRegistry) = ProviderDouble.register(registry)
    }
}
