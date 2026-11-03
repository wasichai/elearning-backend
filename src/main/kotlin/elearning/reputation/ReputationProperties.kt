package elearning.reputation

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// lms.reputation.* (ADR-0038): los valores por defecto de la política de #268, a confirmar por el titular
@ConfigurationProperties("lms.reputation")
data class ReputationProperties(
    // porcentaje de progreso del aula desde el que se valora a un docente (o la matrícula FINALIZADA)
    val minProgress: Int = 50,
    // muestra mínima de un indicador; por debajo del piso vale el piso (regla 9)
    val minSample: Int = 5,
    // cuánto tras fecha_fin de la edición se puede crear, editar o retirar una valoración
    val windowAfterEnd: Duration = Duration.ofDays(30),
    // pasado esto tras fecha_fin, el docente ve sus resultados aunque falten resultados de matrículas (regla 8)
    val releaseAfterEnd: Duration = Duration.ofDays(60),
    // altas, ediciones y retiradas por persona y día UTC; una más es 429
    val maxEditsPerDay: Int = 5,
    // denuncias por persona y día UTC; una más es 429
    val maxReportsPerDay: Int = 10,
) {
    init {
        require(minProgress in 0..100) { "lms.reputation.min-progress must be a percent" }
        require(!windowAfterEnd.isNegative) { "lms.reputation.window-after-end must not be negative" }
        require(releaseAfterEnd >= windowAfterEnd) { "lms.reputation.release-after-end must not be shorter than window-after-end" }
        require(maxEditsPerDay > 0 && maxReportsPerDay > 0) { "lms.reputation daily limits must be positive" }
    }

    // la muestra que se exige de verdad: nunca menos que el piso
    val sample: Int get() = maxOf(minSample, SAMPLE_FLOOR)

    companion object {
        const val SAMPLE_FLOOR = 3
    }
}
