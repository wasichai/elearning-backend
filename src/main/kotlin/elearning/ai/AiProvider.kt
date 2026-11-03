package elearning.ai

import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.api.models.OpenAiModels
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment

// los dos proveedores de la canalización (ADR-0033 §2), ambos opcionales: cada uno existe solo si tiene clave.
// embabelName es el nombre con el que Embabel registra sus modelos; autoConfiguration, la que la compuerta deja fuera sin clave
enum class AiProvider(
    val id: String,
    val keyVariable: String,
    val embabelName: String,
    val autoConfiguration: String,
) {
    ANTHROPIC(
        "anthropic",
        "ANTHROPIC_API_KEY",
        AnthropicModels.PROVIDER,
        "com.embabel.agent.autoconfigure.models.anthropic.AgentAnthropicAutoConfiguration",
    ),
    OPENAI(
        "openai",
        "OPENAI_API_KEY",
        OpenAiModels.PROVIDER,
        "com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration",
    ),
    ;

    // las propiedades de Embabel para este proveedor: api-key, base-url, max-attempts…
    val embabelPrefix = "embabel.agent.platform.models.$id"

    // la clave llega como la variable que documenta Embabel o como su propiedad; vacía no cuenta
    fun hasKey(environment: Environment): Boolean = sequenceOf(keyVariable, "$embabelPrefix.api-key").any { !environment.getProperty(it).isNullOrBlank() }

    // lms.ai.models.<id>: el modelo por defecto (default) y el de cada paso. nunca en el código (ADR-0033 §3)
    fun models(environment: Environment): Map<String, String> =
        Binder.get(environment).bind("$MODELS_PREFIX.$id", Bindable.mapOf(String::class.java, String::class.java)).orElse(null) ?: emptyMap()

    companion object {
        const val PROVIDER_PROPERTY = "lms.ai.provider"
        const val MODELS_PREFIX = "lms.ai.models"
        const val DEFAULT_MODEL = "default"

        // en este orden: con las dos claves y sin lms.ai.provider, manda Anthropic
        fun keyed(environment: Environment): List<AiProvider> = entries.filter { it.hasKey(environment) }

        // el proveedor de la canalización. sin ninguna clave, ninguno (doble determinista), diga lo que diga
        // lms.ai.provider; con claves, lms.ai.provider debe nombrar uno que la tenga
        fun active(environment: Environment): AiProvider? {
            val keyed = keyed(environment)
            if (keyed.isEmpty()) return null
            val requested = environment.getProperty(PROVIDER_PROPERTY).orEmpty().trim()
            if (requested.isEmpty()) return keyed.first()
            val provider =
                entries.firstOrNull { it.id.equals(requested, ignoreCase = true) }
                    ?: error("$PROVIDER_PROPERTY is '$requested': it must be one of ${entries.map { it.id }}")
            check(provider in keyed) { "$PROVIDER_PROPERTY is '${provider.id}' but there is no ${provider.keyVariable}" }
            return provider
        }
    }
}
