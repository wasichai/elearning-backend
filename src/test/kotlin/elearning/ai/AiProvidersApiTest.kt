package elearning.ai

import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.config.models.anthropic.AnthropicProperties
import com.embabel.agent.config.models.openai.OpenAiProperties
import com.embabel.common.ai.autoconfig.ProviderInitialization
import elearning.ElearningApplication
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContext
import org.springframework.test.context.TestPropertySource
import wasichai.test.WasichaiIntegrationTest
import wasichai.test.WasichaiTestDatabase

// la aplicación entera con cada combinación de claves (ADR-0033 §2). Las claves de estas pruebas son de mentira y las
// URL base apuntan a un puerto cerrado: arrancar no llama a ningún proveedor, y si algo llamara, fallaría sin salir de la
// máquina. Las claves vacías tapan las que hubiera en el entorno de quien corre la suite
abstract class AiProvidersApiTestSupport : WasichaiIntegrationTest() {
    @Autowired
    protected lateinit var context: ApplicationContext

    @Autowired
    protected lateinit var status: AiStatus

    // lo que registró cada proveedor de Embabel, por su nombre
    protected fun registered(): Map<String, List<String>> =
        context
            .getBeansOfType(ProviderInitialization::class.java)
            .values
            .associate { initialization -> initialization.provider to initialization.registeredLlms.map { it.modelId } }

    // Embabel trae el cliente MCP de Spring AI: apagado, no deja ni clientes, ni su autoconfiguración, ni el registro de
    // sus anotaciones
    protected fun assertNoMcpClient() {
        assertThat(context.environment.getProperty("spring.ai.mcp.client.enabled")).isEqualTo("false")
        assertThat(context.beanDefinitionNames.filter { it.contains("mcp", ignoreCase = true) }).isEmpty()
    }

    companion object {
        const val CLOSED_PORT = "http://127.0.0.1:9"
    }
}

@TestPropertySource(
    properties = [
        "ANTHROPIC_API_KEY=",
        "OPENAI_API_KEY=",
        "embabel.agent.platform.models.anthropic.api-key=",
        "embabel.agent.platform.models.openai.api-key=",
    ],
)
class NoAiProviderApiTest : AiProvidersApiTestSupport() {
    @Test
    fun `without keys the application starts and the pipeline reports no provider`() {
        assertThat(status.available).isFalse()
        assertThat(status.provider).isNull()
        assertThat(registered()).isEmpty()
        assertThat(context.getBeanNamesForType(AnthropicProperties::class.java)).isEmpty()
        assertThat(context.getBeanNamesForType(OpenAiProperties::class.java)).isEmpty()
        assertNoMcpClient()
        client
            .get()
            .uri("/api/health")
            .exchange()
            .expectStatus()
            .isOk
    }
}

@TestPropertySource(
    properties = [
        "ANTHROPIC_API_KEY=test-anthropic-key-not-real",
        "ANTHROPIC_BASE_URL=${AiProvidersApiTestSupport.CLOSED_PORT}",
        "OPENAI_API_KEY=",
        "embabel.agent.platform.models.openai.api-key=",
    ],
)
class AnthropicOnlyApiTest : AiProvidersApiTestSupport() {
    @Autowired
    private lateinit var anthropic: AnthropicProperties

    @Test
    fun `only the anthropic key starts only anthropic, with its models and two attempts at most`() {
        assertThat(status.provider).isEqualTo(AiProvider.ANTHROPIC)
        assertThat(status.models).containsEntry("default", "claude-sonnet-5-5").containsEntry("extraction", "claude-haiku-5-5")
        assertThat(registered().keys).containsExactly(AnthropicModels.PROVIDER)
        assertThat(registered().getValue(AnthropicModels.PROVIDER)).contains("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5")
        assertThat(context.getBeanNamesForType(OpenAiProperties::class.java)).isEmpty()
        assertThat(anthropic.maxAttempts).isBetween(1, AiStatus.MAX_ATTEMPTS)
        assertThat(context.environment.getProperty(AiProviderGate.DEFAULT_LLM)).isEqualTo("claude-sonnet-5-5")
        assertNoMcpClient()
    }
}

@TestPropertySource(
    properties = [
        "OPENAI_API_KEY=test-openai-key-not-real",
        "OPENAI_BASE_URL=${AiProvidersApiTestSupport.CLOSED_PORT}",
        "ANTHROPIC_API_KEY=",
        "embabel.agent.platform.models.anthropic.api-key=",
    ],
)
class OpenAiOnlyApiTest : AiProvidersApiTestSupport() {
    @Autowired
    private lateinit var openai: OpenAiProperties

    @Test
    fun `only the openai key starts only openai, with its models and two attempts at most`() {
        assertThat(status.provider).isEqualTo(AiProvider.OPENAI)
        assertThat(status.models).containsEntry("default", "gpt-6.1-sol").containsEntry("extraction", "gpt-6-luna")
        assertThat(registered().keys).containsExactly(OpenAiModels.PROVIDER)
        assertThat(registered().getValue(OpenAiModels.PROVIDER)).contains("gpt-6-luna", "gpt-6.1-sol", "gpt-6-astra")
        assertThat(context.getBeanNamesForType(AnthropicProperties::class.java)).isEmpty()
        assertThat(openai.maxAttempts).isBetween(1, AiStatus.MAX_ATTEMPTS)
        assertThat(context.environment.getProperty(AiProviderGate.DEFAULT_LLM)).isEqualTo("gpt-6.1-sol")
        assertNoMcpClient()
    }
}

// un modelo desconocido falla al arrancar, no en la primera ejecución: la aplicación de verdad, lanzada a mano sobre la base
// de las pruebas, solo con la clave de Anthropic y con argumentos de línea de órdenes, que ganan a application.yml y al entorno
@Tag("integration")
class AiStartupFailureTest {
    private fun start(vararg extra: String) {
        val arguments =
            WasichaiTestDatabase.properties().map { (key, value) -> "--$key=$value" } +
                listOf(
                    "--server.port=0",
                    "--server.address=127.0.0.1",
                    "--wasichai.security.jwt.secret=${WasichaiIntegrationTest.TEST_JWT_SECRET}",
                    "--lms.jobs.scheduling=false",
                    "--ANTHROPIC_API_KEY=test-anthropic-key-not-real",
                    "--ANTHROPIC_BASE_URL=${AiProvidersApiTestSupport.CLOSED_PORT}",
                    "--OPENAI_API_KEY=",
                    "--embabel.agent.platform.models.openai.api-key=",
                ) + extra
        SpringApplicationBuilder(ElearningApplication::class.java).run(*arguments.toTypedArray()).close()
    }

    @Test
    fun `an unknown step model stops the application at startup, naming the property and the known models`() {
        assertThatThrownBy { start("--lms.ai.models.anthropic.draft=claude-2") }
            .rootCause()
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("lms.ai.models.anthropic.draft is 'claude-2', which Anthropic does not have")
            .hasMessageContaining("claude-sonnet-5-5")
    }

    // lo detecta antes la propia Embabel, con un mensaje igual de claro; AiStatusTest prueba el de AiStatus. un modelo de
    // OpenAI con solo la clave de Anthropic tampoco existe
    @Test
    fun `an unknown default model stops the application at startup, naming it`() {
        assertThatThrownBy { start("--embabel.models.default-llm=gpt-6.1-sol") }
            .rootCause()
            .hasMessageContaining("'gpt-6.1-sol'")
            .hasMessageContaining("embabel.models.default-llm")
            .hasMessageContaining("claude-sonnet-5-5")
    }
}
