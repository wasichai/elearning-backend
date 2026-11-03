package elearning.ai

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

// la comprobación de arranque sobre un entorno a mano y los modelos que cada proveedor diría haber registrado
class AiStatusTest {
    private val registered =
        mapOf(
            "Anthropic" to setOf("claude-haiku-5-5", "claude-sonnet-5-5"),
            "OpenAI" to setOf("gpt-6-luna", "gpt-6.1-sol"),
        )

    private fun environment(vararg properties: Pair<String, String>): MockEnvironment {
        val environment = MockEnvironment()
        mapOf(
            "embabel.agent.platform.models.anthropic.max-attempts" to "2",
            "embabel.agent.platform.models.openai.max-attempts" to "2",
            "lms.ai.models.anthropic.default" to "claude-sonnet-5-5",
            "lms.ai.models.anthropic.extraction" to "claude-haiku-5-5",
            "lms.ai.models.openai.default" to "gpt-6.1-sol",
            "lms.ai.models.openai.extraction" to "gpt-6-luna",
        ).forEach(environment::setProperty)
        properties.forEach { (key, value) -> environment.setProperty(key, value) }
        return environment
    }

    @Test
    fun `without keys there is no provider and nothing to check`() {
        val status = AiStatus.verified(environment("lms.ai.models.anthropic.draft" to "nonexistent"), emptyMap())

        assertThat(status.available).isFalse()
        assertThat(status).isEqualTo(AiStatus(null, emptyMap()))
    }

    @Test
    fun `with a key and known models the status names the provider and its models`() {
        val status = AiStatus.verified(environment("ANTHROPIC_API_KEY" to "k", AiProviderGate.DEFAULT_LLM to "claude-sonnet-5-5"), registered)

        assertThat(status.provider).isEqualTo(AiProvider.ANTHROPIC)
        assertThat(status.models).containsExactlyInAnyOrderEntriesOf(mapOf("default" to "claude-sonnet-5-5", "extraction" to "claude-haiku-5-5"))
    }

    // con solo la clave de OpenAI, Anthropic no registra nada: un paso que pide Claude no arranca
    @Test
    fun `an unknown step model, or one of the provider without a key, does not start and says which`() {
        assertThatThrownBy {
            AiStatus.verified(
                environment("OPENAI_API_KEY" to "k", AiProviderGate.DEFAULT_LLM to "gpt-6.1-sol", "lms.ai.models.openai.draft" to "claude-sonnet-5-5"),
                mapOf("OpenAI" to registered.getValue("OpenAI")),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("lms.ai.models.openai.draft is 'claude-sonnet-5-5', which OpenAI does not have")
            .hasMessageContaining("[gpt-6-luna, gpt-6.1-sol]")
    }

    @Test
    fun `an unknown default model does not start`() {
        assertThatThrownBy { AiStatus.verified(environment("ANTHROPIC_API_KEY" to "k", AiProviderGate.DEFAULT_LLM to "gpt-4.1-mini"), registered) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("embabel.models.default-llm is 'gpt-4.1-mini', which no provider has")
    }

    @Test
    fun `more than two attempts per call, or the embabel default of ten, does not start`() {
        val anthropic = arrayOf("ANTHROPIC_API_KEY" to "k", AiProviderGate.DEFAULT_LLM to "claude-sonnet-5-5")

        assertThatThrownBy { AiStatus.verified(environment(*anthropic, "embabel.agent.platform.models.anthropic.max-attempts" to "3"), registered) }
            .hasMessageContaining("embabel.agent.platform.models.anthropic.max-attempts is 3: it must be between 1 and 2")
        val unset = MockEnvironment().withProperty("ANTHROPIC_API_KEY", "k").withProperty(AiProviderGate.DEFAULT_LLM, "claude-sonnet-5-5")
        assertThatThrownBy { AiStatus.verified(unset, registered) }.hasMessageContaining("anthropic.max-attempts is 10")
        assertThat(AiStatus.verified(environment(*anthropic, "embabel.agent.platform.models.anthropic.max-attempts" to "1"), registered).available).isTrue()
    }

    @Test
    fun `every problem is reported at once`() {
        assertThatThrownBy {
            AiStatus.verified(
                environment(
                    "ANTHROPIC_API_KEY" to "k",
                    AiProviderGate.DEFAULT_LLM to "nope",
                    "lms.ai.models.anthropic.script" to "claude-2",
                    "embabel.agent.platform.models.anthropic.max-attempts" to "5",
                ),
                registered,
            )
        }.hasMessageContaining("lms.ai.models.anthropic.script")
            .hasMessageContaining("max-attempts is 5")
            .hasMessageContaining("default-llm is 'nope'")
    }
}
