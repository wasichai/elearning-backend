package elearning.ai

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.time.Duration

// lms.ai.limits (ADR-0033 §9): cuántos pasos de IA puede tener una organización pendientes o en curso a la vez y cuánto
// puede gastar un curso (la suma del coste de sus ejecuciones, en currency, con las tarifas de lms.ai.rates). Superar
// cualquiera de los dos al encolar es 429 con Retry-After; el presupuesto se vuelve a mirar antes de cada llamada y, agotado,
// el paso falla sin reintento. courseBudget null: sin tope de gasto
@ConfigurationProperties("lms.ai.limits")
data class AiLimits(
    val maxActivePerOrganization: Int = 4,
    val courseBudget: BigDecimal? = null,
    val currency: String = "USD",
    // lo que se pide esperar: un paso tarda eso en terminar; el presupuesto solo se libera si alguien lo sube
    val concurrencyRetryAfter: Duration = Duration.ofMinutes(1),
    val budgetRetryAfter: Duration = Duration.ofHours(1),
) {
    init {
        require(maxActivePerOrganization > 0) { "lms.ai.limits.max-active-per-organization must be positive" }
        require(courseBudget == null || courseBudget.signum() >= 0) { "lms.ai.limits.course-budget must not be negative" }
        require(currency.isNotBlank()) { "lms.ai.limits.currency must not be blank" }
        require(concurrencyRetryAfter > Duration.ZERO && budgetRetryAfter > Duration.ZERO) { "lms.ai.limits retry-after must be positive" }
    }
}
