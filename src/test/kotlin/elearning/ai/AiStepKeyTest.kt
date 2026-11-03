package elearning.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

// la clave de idempotencia de un paso (ADR-0033 §5, docs/ai §5), la clasificación de los errores del proveedor (§6) y los
// límites configurables (§9)
class AiStepKeyTest {
    private val course = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val sha = "ab".repeat(32)

    private fun request(
        inputs: Any = linkedMapOf("brief" to "Kotlin", "sourceIds" to listOf("s-1", "s-2"), "nested" to linkedMapOf("a" to 1, "b" to 2)),
        sources: List<SourceDigest> = listOf(SourceDigest("acme/m@1:a.adoc", sha), SourceDigest("acme/m@1:b.adoc", "cd".repeat(32))),
        model: ModelChoice? = ModelChoice("anthropic", "claude-sonnet-5-5", ModelSettings(maxTokens = 4_096, temperature = 0.2)),
        step: AgentStep = AgentStep.SOURCE_ANALYSIS,
        unit: UUID? = null,
        objective: String = "Analizar las fuentes",
        promptVersion: String = "source-analysis@1",
        templateVersion: String = "outline@1",
    ) = AiStepRequest(
        step = step,
        courseId = course,
        unitId = unit,
        objective = objective,
        inputs = inputs,
        sources = sources,
        model = model,
        promptVersion = promptVersion,
        templateVersion = templateVersion,
    )

    @Test
    fun `the same step asked in another order has the same key`() {
        val reordered =
            request(
                inputs = linkedMapOf("nested" to linkedMapOf("b" to 2, "a" to 1), "sourceIds" to listOf("s-1", "s-2"), "brief" to "Kotlin"),
                sources = listOf(SourceDigest("acme/m@1:b.adoc", "cd".repeat(32)), SourceDigest("acme/m@1:a.adoc", sha)),
            )

        assertEquals(AiJobs.keyOf(request()), AiJobs.keyOf(reordered))
        assertTrue(AiJobs.keyOf(request()).startsWith("SOURCE_ANALYSIS:"))
        assertEquals("SOURCE_ANALYSIS:".length + 64, AiJobs.keyOf(request()).length, "un hash, no las entradas: pueden ser grandes")
    }

    @Test
    fun `the key changes with the step, its inputs, its sources, its versions and its model`() {
        val base = AiJobs.keyOf(request())
        val variants =
            mapOf(
                "paso" to request(step = AgentStep.COURSE_DESIGN),
                "ámbito" to request(unit = UUID.randomUUID()),
                "objetivo" to request(objective = "Otro objetivo"),
                "entradas" to request(inputs = mapOf("brief" to "Kotlin 2")),
                "orden de una lista" to
                    request(inputs = linkedMapOf("brief" to "Kotlin", "sourceIds" to listOf("s-2", "s-1"), "nested" to linkedMapOf("a" to 1, "b" to 2))),
                "hash de una fuente" to request(sources = listOf(SourceDigest("acme/m@1:a.adoc", "ef".repeat(32)))),
                "instrucciones" to request(promptVersion = "source-analysis@2"),
                "plantilla" to request(templateVersion = "outline@2"),
                "modelo" to request(model = ModelChoice("anthropic", "claude-haiku-5-5", ModelSettings(maxTokens = 4_096, temperature = 0.2))),
                "proveedor" to request(model = ModelChoice("openai", "claude-sonnet-5-5", ModelSettings(maxTokens = 4_096, temperature = 0.2))),
                "parámetros" to request(model = ModelChoice("anthropic", "claude-sonnet-5-5", ModelSettings(maxTokens = 1_024, temperature = 0.2))),
                "esfuerzo" to
                    request(
                        model =
                            ModelChoice(
                                "anthropic",
                                "claude-sonnet-5-5",
                                ModelSettings(maxTokens = 4_096, temperature = 0.2, effort = ReasoningEffort.LOW),
                            ),
                    ),
                "doble determinista" to request(model = null),
            )
        for ((what, variant) in variants) assertNotEquals(base, AiJobs.keyOf(variant), what)
        assertEquals(
            variants.size,
            variants.values
                .map(AiJobs::keyOf)
                .toSet()
                .size,
        )
    }

    @Test
    fun `408, 429 and 5xx from the provider are transient and any other status is not`() {
        for (status in listOf(408, 429, 500, 502, 503, 529)) assertTrue(AiProviderFailure(status, "x").transient, "$status")
        for (status in listOf(400, 401, 403, 404, 413, 422)) assertFalse(AiProviderFailure(status, "x").transient, "$status")
    }

    @Test
    fun `a job type names its step and back`() {
        for (step in AgentStep.entries) assertEquals(step, AiJobs.stepOf(AiJobs.typeOf(step)))
        assertEquals("ai.source_analysis", AiJobs.typeOf(AgentStep.SOURCE_ANALYSIS))
    }

    @Test
    fun `the limits bind from lms ai limits and an empty budget means none`() {
        fun bind(values: Map<String, String>) = Binder(MapConfigurationPropertySource(values)).bindOrCreate("lms.ai.limits", AiLimits::class.java)

        val limits =
            bind(
                mapOf(
                    "lms.ai.limits.max-active-per-organization" to "2",
                    "lms.ai.limits.course-budget" to "12.50",
                    "lms.ai.limits.currency" to "EUR",
                    "lms.ai.limits.concurrency-retry-after" to "30s",
                ),
            )
        assertEquals(2, limits.maxActivePerOrganization)
        assertEquals(0, BigDecimal("12.50").compareTo(limits.courseBudget))
        assertEquals("EUR", limits.currency)
        assertEquals(Duration.ofSeconds(30), limits.concurrencyRetryAfter)
        assertEquals(Duration.ofHours(1), limits.budgetRetryAfter)
        assertNull(bind(mapOf("lms.ai.limits.course-budget" to "")).courseBudget)
        assertThrows<Exception> { bind(mapOf("lms.ai.limits.max-active-per-organization" to "0")) }
        // sin presupuesto de la organización, el período es el mes
        assertNull(limits.organizationBudget)
        assertEquals(BudgetPeriod.MONTH, limits.organizationBudgetPeriod)
        assertTrue(limits.budgeted)
        assertFalse(bind(emptyMap()).budgeted)
    }

    @Test
    fun `the organization budget binds with its period and must not be negative`() {
        fun bind(values: Map<String, String>) = Binder(MapConfigurationPropertySource(values)).bindOrCreate("lms.ai.limits", AiLimits::class.java)

        val limits = bind(mapOf("lms.ai.limits.organization-budget" to "200", "lms.ai.limits.organization-budget-period" to "DAY"))
        assertEquals(0, BigDecimal("200").compareTo(limits.organizationBudget))
        assertEquals(BudgetPeriod.DAY, limits.organizationBudgetPeriod)
        assertTrue(limits.budgeted)
        assertNull(bind(mapOf("lms.ai.limits.organization-budget" to "")).organizationBudget)
        assertThrows<Exception> { bind(mapOf("lms.ai.limits.organization-budget" to "-1")) }
        assertThrows<Exception> { bind(mapOf("lms.ai.limits.organization-budget-period" to "YEAR")) }
    }

    // el período, en UTC: una ejecución cuenta en el que empezó, y lo gastado vuelve a cero al empezar el siguiente
    @Test
    fun `a budget period starts at midnight UTC of its day or of the first of its month`() {
        val at = Instant.parse("2026-12-31T23:59:59.5Z")
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), BudgetPeriod.MONTH.start(at))
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), BudgetPeriod.MONTH.next(at))
        assertEquals(Instant.parse("2026-12-31T00:00:00Z"), BudgetPeriod.DAY.start(at))
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), BudgetPeriod.DAY.next(at))
        // el primer instante del período es suyo
        val first = Instant.parse("2027-02-01T00:00:00Z")
        assertEquals(first, BudgetPeriod.MONTH.start(first))
        assertEquals(Instant.parse("2027-03-01T00:00:00Z"), BudgetPeriod.MONTH.next(first))
        // las 23:30 del 3 en UTC−3 ya son el 4 en UTC: el período es el del 4
        assertEquals(Instant.parse("2026-11-04T00:00:00Z"), BudgetPeriod.DAY.start(Instant.parse("2026-11-03T23:30:00-03:00")))
    }
}
