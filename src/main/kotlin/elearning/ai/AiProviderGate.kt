package elearning.ai

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

// las autoconfiguraciones de proveedor de Embabel no son condicionales: sin su clave, el contexto no arranca. Esta compuerta
// (ADR-0033 §2) saca la de cada proveedor sin clave y, si no queda ninguno, también la de la plataforma, que exige al menos
// un modelo: entonces la canalización no tiene proveedor y usa el doble determinista. El precedente es EmbabelGate de
// Wasichai, que solo conoce Anthropic. Registrada en META-INF/spring.factories: corre antes de crear el contexto
class AiProviderGate : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        val keyed = AiProvider.keyed(environment)
        // el modelo por defecto de Embabel, el del proveedor activo: sin él, Embabel busca uno de OpenAI aunque solo haya
        // clave de Anthropic. al final, para que embabel.models.default-llm en la configuración gane
        AiProvider.active(environment)?.models(environment)?.get(AiProvider.DEFAULT_MODEL)?.let { model ->
            environment.propertySources.addLast(MapPropertySource(DEFAULTS_SOURCE, mapOf(DEFAULT_LLM to model)))
        }
        val excluded = AiProvider.entries.filterNot { it in keyed }.map { it.autoConfiguration } + if (keyed.isEmpty()) PLATFORM else emptyList()
        if (excluded.isEmpty()) return
        // se suman a las exclusiones de la aplicación, no las reemplazan. Binder y no getProperty: una lista de yaml llega
        // como exclude[0], exclude[1]…, que getProperty no ve; una cadena con comas también se enlaza
        val existing = Binder.get(environment).bind(EXCLUDE, Bindable.listOf(String::class.java)).orElse(null) ?: emptyList()
        environment.propertySources.addFirst(MapPropertySource(SOURCE_NAME, mapOf(EXCLUDE to (existing + excluded).distinct().joinToString(","))))
    }

    companion object {
        const val SOURCE_NAME = "lms-ai-provider-gate"
        const val DEFAULTS_SOURCE = "lms-ai-provider-defaults"
        const val DEFAULT_LLM = "embabel.models.default-llm"
        private const val EXCLUDE = "spring.autoconfigure.exclude"

        // toda la autoconfiguración de embabel-agent-platform-autoconfigure 1.5.4: sacar solo la de los modelos mueve el
        // fallo, no lo evita, porque la plataforma comprueba que haya un modelo
        val PLATFORM =
            listOf(
                "com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration",
                "com.embabel.agent.autoconfigure.platform.cache.AgentCacheProviderAutoConfiguration",
                "com.embabel.agent.autoconfigure.platform.cache.CacheSnapshotStoreAutoConfiguration",
                "com.embabel.agent.autoconfigure.platform.LlmDecisionServicesAutoConfiguration",
                "com.embabel.agent.autoconfigure.platform.DecisionServiceRegistryAutoConfiguration",
            )
    }
}
