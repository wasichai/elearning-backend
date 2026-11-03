package elearning.ai

import com.anthropic.errors.AnthropicServiceException
import com.embabel.agent.api.common.ExecutingOperationContext
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import com.embabel.agent.core.support.InvalidLlmReturnTypeException
import com.embabel.agent.openai.withOpenAiReasoningEffort
import com.embabel.agent.spi.loop.ToolNotFoundException
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import com.openai.errors.OpenAIServiceException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
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
// Las instrucciones van como instrucciones de sistema (withSystemPrompt) y el texto del paso, con sus fuentes delimitadas,
// en el mensaje de usuario: el texto de una fuente nunca entra en las de sistema (docs/ai §6).
//
// Los fallos salen como los espera AiStepRunner (ADR-0033 §6): un estado HTTP del proveedor es AiProviderFailure (408, 429
// y 5xx pasajeros: la cola los reintenta; 400, 401, 403, 404, 409, 422… no) y una salida que no se deserializa es
// AiInvalidOutput, con lo que costó (INVALID_OUTPUT); los de red salen tal cual, y la cola los reintenta. Una respuesta que
// pide una herramienta (la de un modelo que obedece a una fuente inyectada, RF-IA-06) también es AiInvalidOutput: el paso no
// tiene ninguna, el bucle de Embabel no ejecuta nada y falla en la primera
// (embabel.agent.platform.toolloop.tool-not-found.max-retries=0), sin devolverle al modelo la lista de herramientas, y la
// cola no la repite. Antes de eso,
// Embabel hace hasta embabel.agent.platform.llm-operations.data-binding.max-attempts intentos (2) y el SDK del proveedor
// repite 408, 409, 429 y 5xx dos veces más dentro de cada uno (docs/ai §2).
//
// El mensaje de un fallo del proveedor es siempre el de aquí, con su estado y nada más (RNF-SEG-09): es el que guardan
// lms.trabajo.error y AgentRun.error y el que lee quien pidió el trabajo. El del SDK repite el cuerpo de la respuesta (el
// 401 de OpenAI cita parte de la clave) y se queda en la causa, que solo va al registro de la aplicación
@Service
class ModelCalls internal constructor(
    // un contexto nuevo por llamada; en las pruebas unitarias, el FakeOperationContext de Embabel
    private val contexts: () -> OperationContext,
    private val status: AiStatus,
) {
    @Autowired
    constructor(contexts: ObjectProvider<ExecutingOperationContext>, status: AiStatus) : this({ contexts.getObject() }, status)

    // system: las instrucciones del paso; prompt: el mensaje de usuario
    fun <T : Any> createObject(
        model: String,
        settings: ModelSettings,
        prompt: String,
        type: Class<T>,
        system: String? = null,
    ): ModelReply<T> {
        // sin proveedor, cada paso usa su doble determinista (docs/ai §7): llegar aquí es un error del programa
        check(status.available) { "no AI provider: the step must use its deterministic double" }
        val context = contexts()
        val options = options(model, settings)
        val value =
            try {
                val runner = context.ai().withLlm(options)
                (if (system == null) runner else runner.withSystemPrompt(system)).createObject(prompt, type)
            } catch (e: Exception) {
                throw classified(e, usage(context))
            }
        return ModelReply(value, usage(context))
    }

    // el esfuerzo va a los dos sitios: el convertidor de Anthropic solo lee el pensamiento y el de OpenAI solo su extensión, así
    // que cada proveedor recibe el suyo y no el del otro (lo comprueba ModelEvaluationSmokeTest en el cuerpo de las peticiones)
    private fun options(
        model: String,
        settings: ModelSettings,
    ): LlmOptions {
        val options = LlmOptions.withModel(model).withMaxTokens(settings.maxTokens).withTemperature(settings.temperature)
        val effort = settings.effort ?: return options
        return options.withThinking(Thinking.withTokenBudget(effort.anthropicBudget)).withOpenAiReasoningEffort(effort.wire)
    }

    private fun usage(context: OperationContext): TokenUsage? {
        val usage = context.agentProcess.usage()
        return if (usage.promptTokens == null && usage.completionTokens == null) {
            null
        } else {
            TokenUsage(usage.promptTokens ?: 0, usage.completionTokens ?: 0)
        }
    }

    private companion object {
        fun classified(
            e: Exception,
            usage: TokenUsage?,
        ): Exception {
            val chain = generateSequence<Throwable>(e) { it.cause }.take(MAX_CAUSES).toList()
            if (chain.any { it is InvalidLlmReturnFormatException || it is InvalidLlmReturnTypeException }) {
                return AiInvalidOutput("the model output does not match the expected type", usage)
            }
            if (chain.any { it is ToolNotFoundException }) return AiInvalidOutput("the model asked for a tool, and the step has none", usage)
            val status = chain.firstNotNullOfOrNull(::statusOf) ?: return e
            val verdict = if (AiProviderFailure.isTransient(status)) "failed" else "rejected the request"
            return AiProviderFailure(status, "the AI provider $verdict ($status)", e)
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
