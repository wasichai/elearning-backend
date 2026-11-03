package elearning.ai

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.time.LocalDate

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
            AiStatus.DATA_BINDING_ATTEMPTS to "2",
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

    // el reintento que envuelve cada llamada tipada en 1.5.4 (ProviderDoubleApiTest cuenta sus peticiones): sin configurar
    // son 10, y repite también los 400
    @Test
    fun `more than two embabel data binding attempts, or its default of ten, does not start`() {
        val anthropic = arrayOf("ANTHROPIC_API_KEY" to "k", AiProviderGate.DEFAULT_LLM to "claude-sonnet-5-5")

        assertThatThrownBy { AiStatus.verified(environment(*anthropic, AiStatus.DATA_BINDING_ATTEMPTS to "3"), registered) }
            .hasMessageContaining("${AiStatus.DATA_BINDING_ATTEMPTS} is 3: it must be between 1 and 2")
        val unset =
            MockEnvironment()
                .withProperty("ANTHROPIC_API_KEY", "k")
                .withProperty(AiProviderGate.DEFAULT_LLM, "claude-sonnet-5-5")
                .withProperty("embabel.agent.platform.models.anthropic.max-attempts", "2")
        assertThatThrownBy { AiStatus.verified(unset, registered) }.hasMessageContaining("${AiStatus.DATA_BINDING_ATTEMPTS} is 10")
        assertThat(AiStatus.verified(environment(*anthropic, AiStatus.DATA_BINDING_ATTEMPTS to "1"), registered).available).isTrue()
    }

    // revisión de seguridad de la F5 (#302): un modelo sin tarifa en la moneda del presupuesto cuesta null y no suma, así que el
    // presupuesto del curso no se agotaría nunca. Con presupuesto, el modelo de cada paso (el de su clave o el por defecto)
    // necesita su tarifa vigente; sin presupuesto no se mira
    @Test
    fun `with a course budget every step model needs a rate in its currency, or it does not start`() {
        val day = LocalDate.parse("2026-11-03")
        val base =
            arrayOf(
                "ANTHROPIC_API_KEY" to "k",
                AiProviderGate.DEFAULT_LLM to "claude-sonnet-5-5",
                "lms.ai.max-tokens.extraction" to "8000",
                "lms.ai.max-tokens.design" to "12000",
            )

        fun rate(
            index: Int,
            model: String,
            currency: String = "USD",
            since: String = "2026-01-01",
        ) = arrayOf(
            "lms.ai.rates[$index].provider" to "anthropic",
            "lms.ai.rates[$index].model" to model,
            "lms.ai.rates[$index].input-per-million" to "1",
            "lms.ai.rates[$index].output-per-million" to "5",
            "lms.ai.rates[$index].currency" to currency,
            "lms.ai.rates[$index].since" to since,
        )

        // sin presupuesto, sin tarifas: arranca
        assertThat(AiStatus.verified(environment(*base), registered, day).available).isTrue()
        assertThat(AiStatus.verified(environment(*base, "lms.ai.limits.course-budget" to ""), registered, day).available).isTrue()

        // design no tiene modelo propio: usa el por defecto, claude-sonnet-5-5, que no tiene tarifa
        assertThatThrownBy { AiStatus.verified(environment(*base, "lms.ai.limits.course-budget" to "10", *rate(0, "claude-haiku-5-5")), registered, day) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("anthropic 'claude-sonnet-5-5' has no rate in USD on 2026-11-03")
            .hasMessageNotContaining("'claude-haiku-5-5'")
        // en otra moneda, o desde mañana, tampoco cuenta
        assertThatThrownBy {
            AiStatus.verified(
                environment(*base, "lms.ai.limits.course-budget" to "10", *rate(0, "claude-haiku-5-5"), *rate(1, "claude-sonnet-5-5", currency = "EUR")),
                registered,
                day,
            )
        }.hasMessageContaining("'claude-sonnet-5-5' has no rate in USD")
        assertThatThrownBy {
            AiStatus.verified(
                environment(*base, "lms.ai.limits.course-budget" to "10", *rate(0, "claude-haiku-5-5"), *rate(1, "claude-sonnet-5-5", since = "2026-11-04")),
                registered,
                day,
            )
        }.hasMessageContaining("'claude-sonnet-5-5' has no rate in USD")

        val rated = environment(*base, "lms.ai.limits.course-budget" to "10", *rate(0, "claude-haiku-5-5"), *rate(1, "claude-sonnet-5-5"))
        assertThat(AiStatus.verified(rated, registered, day).available).isTrue()
        // la moneda del presupuesto manda
        rated.setProperty("lms.ai.limits.currency", "EUR")
        assertThatThrownBy { AiStatus.verified(rated, registered, day) }.hasMessageContaining("has no rate in EUR")
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
