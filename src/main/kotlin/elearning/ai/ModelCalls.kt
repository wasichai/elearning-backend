package elearning.ai

import com.anthropic.errors.AnthropicServiceException
import com.embabel.agent.api.common.ExecutingOperationContext
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import com.embabel.agent.core.support.InvalidLlmReturnTypeException
import com.embabel.common.ai.model.LlmOptions
import com.openai.errors.OpenAIServiceException
import elearning.jobs.PermanentJobFailure
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

// lo que devuelve una llamada: el objeto ya deserializado y los tokens que informó el proveedor (null si no informó)
data class ModelReply<T : Any>(
    val value: T,
    val usage: TokenUsage?,
)

// la única llamada a un modelo de la canalización (ADR-0033 §4): una llamada tipada por paso con Embabel
// (Ai.withLlm(...).createObject, la API de 1.5.4), sin planificador, sin herramientas y con el modelo y max_tokens del paso.
// Cada llamada abre su propio contexto de Embabel (prototipo), así que el consumo que se lee al final es solo el suyo.
// Bloquea: solo se llama desde un trabajo de la cola, en su dispatcher acotado, nunca en el event loop de WebFlux.
//
// Los fallos, como los clasifica la cola (ADR-0033 §6): lo que reintentar no arregla (400, 401, 403, 404, 422 y una salida
// que no se deserializa) sale como PermanentJobFailure; 408, 409, 429, 5xx y los de red salen tal cual, y la cola los
// reintenta. Antes de eso, Embabel hace hasta embabel.agent.platform.llm-operations.data-binding.max-attempts intentos
// (2) y el SDK del proveedor repite 408, 409, 429 y 5xx dos veces más dentro de cada uno (docs/ai §2)
@Service
class ModelCalls(
    private val contexts: ObjectProvider<ExecutingOperationContext>,
    private val status: AiStatus,
) {
    fun <T : Any> createObject(
        model: String,
        settings: ModelSettings,
        prompt: String,
        type: Class<T>,
    ): ModelReply<T> {
        // sin proveedor, cada paso usa su doble determinista (docs/ai §7): llegar aquí es un error del programa
        check(status.available) { "no AI provider: the step must use its deterministic double" }
        val context = contexts.getObject()
        val options = LlmOptions.withModel(model).withMaxTokens(settings.maxTokens).withTemperature(settings.temperature)
        val value =
            try {
                context.ai().withLlm(options).createObject(prompt, type)
            } catch (e: Exception) {
                throw classified(e)
            }
        val usage = context.agentProcess.usage()
        val tokens =
            if (usage.promptTokens == null && usage.completionTokens == null) {
                null
            } else {
                TokenUsage(usage.promptTokens ?: 0, usage.completionTokens ?: 0)
            }
        return ModelReply(value, tokens)
    }

    private companion object {
        // los que el SDK del proveedor ya considera pasajeros
        val TRANSIENT = setOf(408, 409, 429)

        fun classified(e: Exception): Exception {
            val chain = generateSequence<Throwable>(e) { it.cause }.take(MAX_CAUSES).toList()
            if (chain.any { it is InvalidLlmReturnFormatException || it is InvalidLlmReturnTypeException }) {
                return PermanentJobFailure("the model output does not match the expected type", e)
            }
            val status = chain.firstNotNullOfOrNull(::statusOf) ?: return e
            return if (status in TRANSIENT || status >= 500) e else PermanentJobFailure("the AI provider rejected the request ($status)", e)
        }

        fun statusOf(t: Throwable): Int? =
            when (t) {
                is AnthropicServiceException -> t.statusCode()
                is OpenAIServiceException -> t.statusCode()
                else -> null
            }

        const val MAX_CAUSES = 10
    }
}
