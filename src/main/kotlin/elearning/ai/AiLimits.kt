package elearning.ai

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// lms.ai.limits (ADR-0033 §9): cuántos pasos de IA puede tener una organización pendientes o en curso a la vez, cuánto
// puede gastar un curso (la suma del coste de sus ejecuciones, en currency, con las tarifas de lms.ai.rates) y cuánto la
// organización entera en cada período (#368: sin él, quien crea cursos estrena presupuesto con cada uno). Superar cualquiera
// al encolar es 429 con Retry-After; los presupuestos se vuelven a mirar antes de cada llamada y, agotado uno, el paso falla
// sin reintento. courseBudget u organizationBudget null: sin ese tope
@ConfigurationProperties("lms.ai.limits")
data class AiLimits(
    val maxActivePerOrganization: Int = 4,
    val courseBudget: BigDecimal? = null,
    val organizationBudget: BigDecimal? = null,
    val organizationBudgetPeriod: BudgetPeriod = BudgetPeriod.MONTH,
    val currency: String = "USD",
    // lo que se pide esperar: un paso tarda eso en terminar; el presupuesto del curso solo se libera si alguien lo sube. El
    // de la organización se libera al empezar el período siguiente, y eso es lo que se pide esperar
    val concurrencyRetryAfter: Duration = Duration.ofMinutes(1),
    val budgetRetryAfter: Duration = Duration.ofHours(1),
) {
    init {
        require(maxActivePerOrganization > 0) { "lms.ai.limits.max-active-per-organization must be positive" }
        require(courseBudget == null || courseBudget.signum() >= 0) { "lms.ai.limits.course-budget must not be negative" }
        require(organizationBudget == null || organizationBudget.signum() >= 0) { "lms.ai.limits.organization-budget must not be negative" }
        require(currency.isNotBlank()) { "lms.ai.limits.currency must not be blank" }
        require(concurrencyRetryAfter > Duration.ZERO && budgetRetryAfter > Duration.ZERO) { "lms.ai.limits retry-after must be positive" }
    }

    // hay algún presupuesto: con él, cada modelo necesita su tarifa (AiStatus)
    val budgeted: Boolean get() = courseBudget != null || organizationBudget != null
}

// el período del presupuesto de la organización, en UTC como el día de las tarifas (AgentExecutionLog). Una ejecución cuenta
// en el período en que empezó
enum class BudgetPeriod {
    DAY,
    MONTH,
    ;

    // el comienzo del período que contiene at
    fun start(at: Instant): Instant {
        val day = at.atOffset(ZoneOffset.UTC).toLocalDate()
        return midnight(if (this == DAY) day else day.withDayOfMonth(1))
    }

    // el comienzo del período siguiente al que contiene at
    fun next(at: Instant): Instant {
        val start = start(at).atOffset(ZoneOffset.UTC).toLocalDate()
        return midnight(if (this == DAY) start.plusDays(1) else start.plusMonths(1))
    }

    private fun midnight(day: LocalDate): Instant = day.atStartOfDay().toInstant(ZoneOffset.UTC)
}
