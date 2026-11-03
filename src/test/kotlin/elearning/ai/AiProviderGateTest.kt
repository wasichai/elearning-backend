package elearning.ai

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment

// la compuerta sobre un entorno a mano: qué autoconfiguración de Embabel queda fuera con cada combinación de claves.
// Que el contexto de verdad arranca así lo prueba AiProvidersApiTest
class AiProviderGateTest {
    private val anthropic = "com.embabel.agent.autoconfigure.models.anthropic.AgentAnthropicAutoConfiguration"
    private val openai = "com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration"

    private fun gated(vararg properties: Pair<String, String>): MockEnvironment {
        val environment = MockEnvironment()
        environment.setProperty("lms.ai.models.anthropic.default", "claude-sonnet-5-5")
        environment.setProperty("lms.ai.models.openai.default", "gpt-6.1-sol")
        properties.forEach { (key, value) -> environment.setProperty(key, value) }
        AiProviderGate().postProcessEnvironment(environment, SpringApplication())
        return environment
    }

    private fun MockEnvironment.excluded(): List<String> = getProperty("spring.autoconfigure.exclude").orEmpty().split(",").filter { it.isNotBlank() }

    @Test
    fun `without keys both providers and the whole platform stay out, so the application still starts`() {
        val environment = gated()

        assertThat(environment.excluded()).containsExactlyInAnyOrder(*(listOf(anthropic, openai) + AiProviderGate.PLATFORM).toTypedArray())
        assertThat(environment.getProperty(AiProviderGate.DEFAULT_LLM)).isNull()
        assertThat(AiProvider.active(environment)).isNull()
    }

    @Test
    fun `a blank key is no key`() {
        assertThat(gated("ANTHROPIC_API_KEY" to " ", "OPENAI_API_KEY" to "").excluded()).containsAll(AiProviderGate.PLATFORM)
    }

    @Test
    fun `only the anthropic key keeps openai out and makes the anthropic default the embabel default`() {
        val environment = gated("ANTHROPIC_API_KEY" to "k")

        assertThat(environment.excluded()).containsExactly(openai)
        assertThat(environment.getProperty(AiProviderGate.DEFAULT_LLM)).isEqualTo("claude-sonnet-5-5")
        assertThat(AiProvider.active(environment)).isEqualTo(AiProvider.ANTHROPIC)
    }

    @Test
    fun `only the openai key keeps anthropic out and makes the openai default the embabel default`() {
        val environment = gated("embabel.agent.platform.models.openai.api-key" to "k")

        assertThat(environment.excluded()).containsExactly(anthropic)
        assertThat(environment.getProperty(AiProviderGate.DEFAULT_LLM)).isEqualTo("gpt-6.1-sol")
        assertThat(AiProvider.active(environment)).isEqualTo(AiProvider.OPENAI)
    }

    @Test
    fun `with both keys nothing is excluded, anthropic leads unless lms ai provider says otherwise`() {
        val both = arrayOf("ANTHROPIC_API_KEY" to "a", "OPENAI_API_KEY" to "o")

        assertThat(gated(*both).excluded()).isEmpty()
        assertThat(AiProvider.active(gated(*both))).isEqualTo(AiProvider.ANTHROPIC)
        val openaiFirst = gated(*both, "lms.ai.provider" to "OpenAI")
        assertThat(AiProvider.active(openaiFirst)).isEqualTo(AiProvider.OPENAI)
        assertThat(openaiFirst.getProperty(AiProviderGate.DEFAULT_LLM)).isEqualTo("gpt-6.1-sol")
    }

    @Test
    fun `an explicit embabel default model wins over the provider default`() {
        assertThat(gated("ANTHROPIC_API_KEY" to "k", AiProviderGate.DEFAULT_LLM to "claude-opus-5-5").getProperty(AiProviderGate.DEFAULT_LLM))
            .isEqualTo("claude-opus-5-5")
    }

    @Test
    fun `a provider that is unknown, or has no key while another has one, does not start`() {
        assertThatThrownBy { gated("ANTHROPIC_API_KEY" to "k", "lms.ai.provider" to "gemini") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("lms.ai.provider is 'gemini'")
        assertThatThrownBy { gated("ANTHROPIC_API_KEY" to "k", "lms.ai.provider" to "openai") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("there is no OPENAI_API_KEY")
        // sin ninguna clave, lms.ai.provider no cuenta: se arranca sin proveedor
        assertThat(AiProvider.active(gated("lms.ai.provider" to "openai"))).isNull()
    }

    @Test
    fun `the application's own exclusions survive, as a string or as an indexed list`() {
        assertThat(gated("spring.autoconfigure.exclude" to "com.acme.FooAutoConfiguration").excluded())
            .contains("com.acme.FooAutoConfiguration")
            .containsAll(AiProviderGate.PLATFORM)
        assertThat(gated("spring.autoconfigure.exclude[0]" to "com.acme.Foo", "spring.autoconfigure.exclude[1]" to "com.acme.Bar").excluded())
            .contains("com.acme.Foo", "com.acme.Bar")
            .containsAll(AiProviderGate.PLATFORM)
    }
}
