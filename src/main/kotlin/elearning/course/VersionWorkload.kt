package elearning.course

import elearning.workload.WeeklyHours
import elearning.workload.Workload
import java.math.BigDecimal

// la carga de una versión de curso desde sus atributos, tal como los entrega Wasichai: la leen la ficha del catálogo y la
// aprobación de créditos, que recalcula en el servidor lo que propone (ADR-0035)
object VersionWorkload {
    fun of(attributes: Map<String, Any?>): Workload =
        Workload(
            weeks = int(attributes["semanas"]) ?: 1,
            weekly =
                WeeklyHours(
                    theory = decimal(attributes["horas_teoria_semana"]) ?: BigDecimal.ZERO,
                    practice = decimal(attributes["horas_practica_semana"]) ?: BigDecimal.ZERO,
                    supervised = decimal(attributes["horas_supervisadas_semana"]) ?: BigDecimal.ZERO,
                    autonomous = decimal(attributes["horas_autonomas_semana"]) ?: BigDecimal.ZERO,
                ),
        )

    private fun int(value: Any?): Int? =
        when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }

    private fun decimal(value: Any?): BigDecimal? =
        when (value) {
            is BigDecimal -> value
            is Number -> BigDecimal(value.toString())
            is String -> value.toBigDecimalOrNull()
            else -> null
        }
}
