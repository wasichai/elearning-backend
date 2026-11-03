package elearning.ai

import com.embabel.common.ai.autoconfig.ProviderInitialization
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

// lo que la canalización sabe del proveedor al arrancar. provider null: sin proveedor, cada paso usará su doble
// determinista (docs/ai §7). models: el modelo por defecto y el de cada paso del proveedor activo
data class AiStatus(
    val provider: AiProvider?,
    val models: Map<String, String>,
) {
    val available: Boolean get() = provider != null

    companion object {
        // intentos de Embabel en cada llamada, el primero incluido: los reintentos son de la cola (ADR-0033 §6)
        const val MAX_ATTEMPTS = 2

        // el reintento interno de Embabel cuando no se configura
        private const val EMBABEL_DEFAULT_ATTEMPTS = 10

        // comprobado al arrancar y no en la primera ejecución: un modelo que el proveedor no registró, el modelo por defecto
        // de Embabel incluido, o más de MAX_ATTEMPTS intentos impiden arrancar con todos los problemas a la vez.
        // registered: los modelos que registró cada proveedor, por su nombre en Embabel
        fun verified(
            environment: Environment,
            registered: Map<String, Set<String>>,
        ): AiStatus {
            val provider = AiProvider.active(environment) ?: return AiStatus(null, emptyMap())
            val problems =
                AiProvider.keyed(environment).flatMap { problems(it, environment, registered[it.embabelName].orEmpty()) } +
                    defaultModelProblem(environment, registered.values.flatten().toSet())
            check(problems.isEmpty()) { "the AI configuration does not start:\n" + problems.joinToString("\n") { "- $it" } }
            return AiStatus(provider, provider.models(environment))
        }

        private fun problems(
            provider: AiProvider,
            environment: Environment,
            registered: Set<String>,
        ): List<String> {
            val unknown =
                provider.models(environment).filterValues { it !in registered }.map { (step, model) ->
                    "${AiProvider.MODELS_PREFIX}.${provider.id}.$step is '$model', which ${provider.embabelName} does not have; " +
                        "known models: ${registered.sorted()}"
                }
            val attempts = environment.getProperty("${provider.embabelPrefix}.max-attempts", Int::class.javaObjectType) ?: EMBABEL_DEFAULT_ATTEMPTS
            val retries =
                if (attempts in 1..MAX_ATTEMPTS) {
                    emptyList()
                } else {
                    listOf("${provider.embabelPrefix}.max-attempts is $attempts: it must be between 1 and $MAX_ATTEMPTS, the job queue retries")
                }
            return unknown + retries
        }

        private fun defaultModelProblem(
            environment: Environment,
            registered: Set<String>,
        ): List<String> {
            val model = environment.getProperty(AiProviderGate.DEFAULT_LLM).orEmpty()
            return if (model in
                registered
            ) {
                emptyList()
            } else {
                listOf("${AiProviderGate.DEFAULT_LLM} is '$model', which no provider has; known models: ${registered.sorted()}")
            }
        }
    }
}

@Configuration(proxyBeanMethods = false)
class AiConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    // ProviderInitialization: lo que cada proveedor de Embabel registró al arrancar; ninguno sin claves
    @Bean
    fun aiStatus(
        environment: Environment,
        initializations: ObjectProvider<ProviderInitialization>,
    ): AiStatus {
        val registered =
            initializations
                .orderedStream()
                .toList()
                .groupBy({ it.provider }, { initialization -> initialization.registeredLlms.map { it.modelId } })
                .mapValues { (_, models) -> models.flatten().toSet() }
        val status = AiStatus.verified(environment, registered)
        if (status.provider == null) {
            log.info("AI: no provider (neither ANTHROPIC_API_KEY nor OPENAI_API_KEY); the pipeline uses its deterministic double")
        } else {
            log.info("AI: provider {}, models {}", status.provider.id, status.models)
        }
        return status
    }
}
