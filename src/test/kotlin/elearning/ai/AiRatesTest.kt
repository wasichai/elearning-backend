package elearning.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.math.BigDecimal
import java.time.LocalDate

// la tarifa configurada y el coste de una ejecución (ADR-0033 §9, docs/ai §4)
class AiRatesTest {
    private fun d(value: String) = BigDecimal(value)

    private fun same(
        expected: String,
        actual: BigDecimal,
    ) = assertEquals(0, d(expected).compareTo(actual), "esperaba $expected, fue $actual")

    private fun rate(
        since: String,
        input: String = "3",
        output: String = "15",
        model: String = "claude-sonnet-5-5",
    ) = AiRates.Rate("anthropic", model, d(input), d(output), "USD", LocalDate.parse(since))

    @Test
    fun `the cost is the tokens times the rate per million, kept with the rate and its date`() {
        val cost = rate("2026-11-01").cost(TokenUsage(inputTokens = 1_200, outputTokens = 300))

        // (1 200 × 3 + 300 × 15) / 1 000 000
        same("0.0081", cost.amount)
        assertEquals("USD", cost.currency)
        same("3", cost.inputPerMillion)
        same("15", cost.outputPerMillion)
        assertEquals(LocalDate.parse("2026-11-01"), cost.rateSince)
    }

    @Test
    fun `the rate of a day is the latest that already applied, and none before the first or for another model`() {
        val rates = AiRates(listOf(rate("2026-01-01", input = "3"), rate("2026-12-01", input = "4"), rate("2026-01-01", model = "claude-haiku-5-5")))

        same("3", rates.rateFor("anthropic", "claude-sonnet-5-5", LocalDate.parse("2026-11-30"))!!.inputPerMillion)
        same("4", rates.rateFor("anthropic", "claude-sonnet-5-5", LocalDate.parse("2026-12-01"))!!.inputPerMillion)
        assertNull(rates.rateFor("anthropic", "claude-sonnet-5-5", LocalDate.parse("2025-12-31")))
        assertNull(rates.rateFor("openai", "claude-sonnet-5-5", LocalDate.parse("2026-11-30")))
        assertNull(rates.rateFor("anthropic", "claude-opus-5-5", LocalDate.parse("2026-11-30")))
    }

    @Test
    fun `a rate is validated and a model has one rate per date`() {
        assertThrows<IllegalArgumentException> { rate("2026-01-01", input = "-1") }
        assertThrows<IllegalArgumentException> { AiRates.Rate("acme", "m", d("1"), d("1"), "USD", LocalDate.parse("2026-01-01")) }
        assertThrows<IllegalArgumentException> { AiRates(listOf(rate("2026-01-01"), rate("2026-01-01", input = "4"))) }
    }

    @Test
    fun `rates bind from lms ai rates`() {
        val source =
            MapConfigurationPropertySource(
                mapOf(
                    "lms.ai.rates[0].provider" to "openai",
                    "lms.ai.rates[0].model" to "gpt-6.1-sol",
                    "lms.ai.rates[0].input-per-million" to "2.5",
                    "lms.ai.rates[0].output-per-million" to "10",
                    "lms.ai.rates[0].currency" to "USD",
                    "lms.ai.rates[0].since" to "2026-11-03",
                ),
            )
        val rates = Binder(source).bind("lms.ai", AiRates::class.java).get()

        val rate = rates.rateFor("openai", "gpt-6.1-sol", LocalDate.parse("2026-11-03"))!!
        same("2.5", rate.inputPerMillion)
        same("10", rate.outputPerMillion)
    }
}
