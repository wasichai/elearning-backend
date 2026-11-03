package elearning.ai

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// lms.ai.rates: lo que cuesta cada modelo, por millón de tokens de entrada y de salida, desde una fecha (ADR-0033 §9,
// docs/ai §4). Una tarifa nueva se añade con su fecha, no reemplaza la anterior: cada ejecución usa la que regía el día
// en que empezó y la guarda. Sin tarifa para un modelo, su coste queda sin calcular (null)
@ConfigurationProperties("lms.ai")
data class AiRates(
    val rates: List<Rate> = emptyList(),
) {
    data class Rate(
        val provider: String,
        val model: String,
        val inputPerMillion: BigDecimal,
        val outputPerMillion: BigDecimal,
        val currency: String,
        val since: LocalDate,
    ) {
        init {
            require(provider in ModelChoice.PROVIDERS) { "lms.ai.rates: provider must be one of ${ModelChoice.PROVIDERS}" }
            require(model.isNotBlank()) { "lms.ai.rates: model must not be blank" }
            require(inputPerMillion.signum() >= 0 && outputPerMillion.signum() >= 0) { "lms.ai.rates: $model has a negative rate" }
            require(currency.isNotBlank()) { "lms.ai.rates: $model has no currency" }
        }

        fun cost(usage: TokenUsage): AgentExecutionCost =
            AgentExecutionCost(
                amount =
                    (inputPerMillion * usage.inputTokens.toBigDecimal() + outputPerMillion * usage.outputTokens.toBigDecimal())
                        .divide(MILLION, SCALE, RoundingMode.HALF_UP),
                currency = currency,
                inputPerMillion = inputPerMillion,
                outputPerMillion = outputPerMillion,
                rateSince = since,
            )
    }

    init {
        val repeated =
            rates
                .groupingBy { Triple(it.provider, it.model, it.since) }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        require(repeated.isEmpty()) { "lms.ai.rates: more than one rate for the same model and date: $repeated" }
    }

    // la tarifa vigente el día on: la de fecha más reciente que no sea posterior
    fun rateFor(
        provider: String,
        model: String,
        on: LocalDate,
    ): Rate? = rates.filter { it.provider == provider && it.model == model && !it.since.isAfter(on) }.maxByOrNull { it.since }
}

private val MILLION: BigDecimal = BigDecimal.valueOf(1_000_000)

// la escala de lms.ejecucion_agente.coste
private const val SCALE = 6
