package elearning.ai

import com.embabel.common.ai.autoconfig.ProviderInitialization
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.LocalDate
import java.time.ZoneOffset

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

        const val DATA_BINDING_ATTEMPTS = "embabel.agent.platform.llm-operations.data-binding.max-attempts"

        // comprobado al arrancar y no en la primera ejecución: un modelo que el proveedor no registró, el modelo por defecto
        // de Embabel incluido, o más de MAX_ATTEMPTS intentos impiden arrancar con todos los problemas a la vez.
        // registered: los modelos que registró cada proveedor, por su nombre en Embabel. on: el día de las tarifas (hoy, en UTC,
        // como el de AgentExecutionLog)
        fun verified(
            environment: Environment,
            registered: Map<String, Set<String>>,
            on: LocalDate = LocalDate.now(ZoneOffset.UTC),
        ): AiStatus {
            val provider = AiProvider.active(environment) ?: return AiStatus(null, emptyMap())
            val problems =
                AiProvider.keyed(environment).flatMap { problems(it, environment, registered[it.embabelName].orEmpty()) } +
                    defaultModelProblem(environment, registered.values.flatten().toSet()) +
                    dataBindingProblem(environment) +
                    budgetProblems(environment, provider, on)
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

        // el reintento de Embabel que de verdad envuelve cada llamada tipada, para los dos proveedores: repite cualquier fallo,
        // un 400 o una salida que no se deserializa incluidos (la segunda vez con el error de validación). con 10, su valor por
        // defecto, un 400 serían 10 peticiones
        private fun dataBindingProblem(environment: Environment): List<String> {
            val attempts = environment.getProperty(DATA_BINDING_ATTEMPTS, Int::class.javaObjectType) ?: EMBABEL_DEFAULT_ATTEMPTS
            return if (attempts in 1..MAX_ATTEMPTS) {
                emptyList()
            } else {
                listOf("$DATA_BINDING_ATTEMPTS is $attempts: it must be between 1 and $MAX_ATTEMPTS, the job queue retries")
            }
        }

        // con presupuesto por curso (lms.ai.limits.course-budget), el modelo de cada paso del proveedor activo (el de su clave
        // de lms.ai.max-tokens, o el por defecto, como AiStepModels) necesita una tarifa vigente en la moneda del presupuesto:
        // sin ella su coste queda null, no suma en AgentExecutionStore.spent y el presupuesto no se cumpliría nunca (revisión de
        // seguridad de la F5, #302)
        private fun budgetProblems(
            environment: Environment,
            provider: AiProvider,
            on: LocalDate,
        ): List<String> {
            val binder = Binder.get(environment)
            val limits = binder.bindOrCreate("lms.ai.limits", AiLimits::class.java)
            if (limits.courseBudget == null) return emptyList()
            val rates = binder.bindOrCreate("lms.ai", AiRates::class.java)
            val models = provider.models(environment)
            val steps = binder.bindOrCreate("lms.ai", AiStepSettings::class.java).maxTokens.keys
            return steps
                .mapNotNull { models[it] ?: models[AiProvider.DEFAULT_MODEL] }
                .distinct()
                .sorted()
                .filter { rates.rateFor(provider.id, it, on)?.currency != limits.currency }
                .map { model ->
                    "lms.ai.limits.course-budget is set, but ${provider.id} '$model' has no rate in ${limits.currency} on $on in lms.ai.rates: " +
                        "its cost would not count and the budget would never be spent"
                }
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
