package elearning.ai

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service

// lms.ai.max-tokens: el max_tokens de cada paso (ADR-0033 §9), por la misma clave que su modelo en lms.ai.models
// (extraction, design, draft…). Siempre se envía al proveedor
@ConfigurationProperties("lms.ai")
data class AiStepSettings(
    val maxTokens: Map<String, Int> = emptyMap(),
) {
    init {
        require(maxTokens.values.all { it > 0 }) { "lms.ai.max-tokens must be positive" }
    }
}

// el modelo con que se encola un paso: el del proveedor activo para su clave (o su modelo por defecto) con su max_tokens.
// null sin proveedor: el paso usa su doble determinista (docs/ai §7) y su ejecución queda DETERMINISTIC
@Service
class AiStepModels(
    private val status: AiStatus,
    private val settings: AiStepSettings,
) {
    fun choice(key: String): ModelChoice? {
        val provider = status.provider ?: return null
        val model = status.models[key] ?: status.models.getValue(AiProvider.DEFAULT_MODEL)
        val maxTokens = checkNotNull(settings.maxTokens[key]) { "lms.ai.max-tokens.$key is not configured" }
        return ModelChoice(provider.id, model, ModelSettings(maxTokens))
    }
}
