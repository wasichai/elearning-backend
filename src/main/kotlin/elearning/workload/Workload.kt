package elearning.workload

import java.math.BigDecimal

// horas por semana de cada tipo de actividad. contacto = teoría + práctica/laboratorio; supervisadas son trabajo
// dirigido que no es contacto (tutorías, prácticas supervisadas); autónomas, el estudio por cuenta propia
data class WeeklyHours(
    val theory: BigDecimal = BigDecimal.ZERO,
    val practice: BigDecimal = BigDecimal.ZERO,
    val supervised: BigDecimal = BigDecimal.ZERO,
    val autonomous: BigDecimal = BigDecimal.ZERO,
) {
    init {
        listOf(theory, practice, supervised, autonomous).forEach { require(it.signum() >= 0) { "hours cannot be negative: $it" } }
        require(total <= HOURS_IN_A_WEEK) { "a week has $HOURS_IN_A_WEEK hours, not $total" }
    }

    val total: BigDecimal get() = theory + practice + supervised + autonomous

    companion object {
        val HOURS_IN_A_WEEK = BigDecimal(168)
    }
}

// carga formativa de una versión de curso: lo que se mide, no lo que vale en créditos (eso es CreditPolicy)
data class Workload(
    val weeks: Int,
    val weekly: WeeklyHours,
) {
    init {
        require(weeks in 1..MAX_WEEKS) { "weeks must be between 1 and $MAX_WEEKS, not $weeks" }
    }

    private val weeksFactor = BigDecimal(weeks)

    val theoryHours: BigDecimal get() = weekly.theory * weeksFactor
    val practiceHours: BigDecimal get() = weekly.practice * weeksFactor
    val supervisedHours: BigDecimal get() = weekly.supervised * weeksFactor
    val autonomousHours: BigDecimal get() = weekly.autonomous * weeksFactor
    val contactHours: BigDecimal get() = theoryHours + practiceHours
    val directedHours: BigDecimal get() = contactHours + supervisedHours
    val totalHours: BigDecimal get() = directedHours + autonomousHours

    companion object {
        // dos años: un programa más largo es otra cosa que un curso
        const val MAX_WEEKS = 104
    }
}
