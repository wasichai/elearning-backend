package elearning.workload

import java.math.BigDecimal
import java.math.RoundingMode

enum class CreditSystem { ECTS, SCH }

// a propósito sin APROBADO: aprobar créditos lo hace una persona sobre la versión del curso (creditos_aprobados),
// nunca este cálculo
enum class CreditStatus { PROPUESTO, }

// lo que hace falta para auditar una propuesta: qué regla, con qué parámetros, de qué fuente, sobre qué carga
data class CreditProposal(
    val system: CreditSystem,
    val policyId: String,
    val policyVersion: String,
    val method: String,
    val parameters: Map<String, String>,
    val source: String,
    val totalHours: BigDecimal,
    val calculated: BigDecimal,
    val proposed: BigDecimal,
    val warnings: List<String> = emptyList(),
    val status: CreditStatus = CreditStatus.PROPUESTO,
)

// una regla de crédito propone a partir de la carga medida. no hay conversión entre sistemas: un curso con créditos
// ECTS no "vale" X horas semestrales, se vuelve a calcular desde su carga con la otra política, si procede
interface CreditPolicy {
    val id: String
    val version: String
    val system: CreditSystem

    fun propose(workload: Workload): CreditProposal
}

private val SCALE = 4

private fun divide(
    hours: BigDecimal,
    perCredit: BigDecimal,
): BigDecimal = hours.divide(perCredit, SCALE, RoundingMode.HALF_UP).stripTrailingZeros()

// hacia abajo al múltiplo del paso: nunca propone crédito que la carga no sostiene
private fun floorToStep(
    value: BigDecimal,
    step: BigDecimal,
): BigDecimal = value.divide(step, 0, RoundingMode.DOWN).multiply(step).stripTrailingZeros()

// ECTS: 60 créditos por año a tiempo completo, 1500–1800 h; un crédito son 25–30 h de carga total del estudiante,
// trabajo autónomo incluido (ECTS Users' Guide)
class EctsPolicy(
    val hoursPerCredit: BigDecimal = BigDecimal(27),
    val step: BigDecimal = BigDecimal("0.5"),
) : CreditPolicy {
    override val id = "ects-carga-total"
    override val version = "1"
    override val system = CreditSystem.ECTS

    init {
        require(hoursPerCredit in MIN..MAX) { "ECTS hours per credit must be between $MIN and $MAX, not $hoursPerCredit" }
        require(step.signum() > 0) { "step must be positive" }
    }

    override fun propose(workload: Workload): CreditProposal {
        val calculated = divide(workload.totalHours, hoursPerCredit)
        return CreditProposal(
            system = system,
            policyId = id,
            policyVersion = version,
            method = "carga total del estudiante / horas por crédito, redondeo hacia abajo al paso",
            parameters = mapOf("horasPorCredito" to hoursPerCredit.toPlainString(), "paso" to step.toPlainString()),
            source = "ECTS Users' Guide (EHEA): 60 créditos = 1500–1800 h; 1 crédito = 25–30 h",
            totalHours = workload.totalHours,
            calculated = calculated,
            proposed = floorToStep(calculated, step),
        )
    }

    companion object {
        private val MIN = BigDecimal(25)
        private val MAX = BigDecimal(30)
    }
}

// semester credit hour, 34 CFR 600.2: no menos de 1 h de instrucción directa y 2 h de trabajo fuera de clase por
// semana durante unas 15 semanas, o trabajo equivalente. aquí: carga total / 45 h, y un aviso si la instrucción directa
// (teoría + práctica/laboratorio) no llega a 15 h por crédito. cómo cuenta el laboratorio lo decide la institución:
// por eso es una propuesta y por eso los parámetros viajan con ella
class SemesterCreditHourPolicy(
    val instructionHoursPerWeek: BigDecimal = BigDecimal.ONE,
    val outOfClassHoursPerWeek: BigDecimal = BigDecimal(2),
    val referenceWeeks: BigDecimal = BigDecimal(15),
    val step: BigDecimal = BigDecimal("0.5"),
) : CreditPolicy {
    override val id = "sch-34cfr600.2-trabajo-equivalente"
    override val version = "1"
    override val system = CreditSystem.SCH

    init {
        require(listOf(instructionHoursPerWeek, outOfClassHoursPerWeek, referenceWeeks, step).all { it.signum() > 0 }) {
            "every parameter must be positive"
        }
    }

    override fun propose(workload: Workload): CreditProposal {
        val hoursPerCredit = (instructionHoursPerWeek + outOfClassHoursPerWeek) * referenceWeeks
        val calculated = divide(workload.totalHours, hoursPerCredit)
        val instructionFloor = calculated * instructionHoursPerWeek * referenceWeeks
        val warnings =
            if (workload.contactHours < instructionFloor) {
                listOf(
                    "la instrucción directa (${workload.contactHours.toPlainString()} h) no llega al mínimo de " +
                        "${instructionFloor.setScale(2, RoundingMode.HALF_UP).toPlainString()} h para ${calculated.toPlainString()} créditos",
                )
            } else {
                emptyList()
            }
        return CreditProposal(
            system = system,
            policyId = id,
            policyVersion = version,
            method = "carga total / ((instrucción + fuera de clase) × semanas de referencia), redondeo hacia abajo al paso",
            parameters =
                mapOf(
                    "horasInstruccionPorSemana" to instructionHoursPerWeek.toPlainString(),
                    "horasFueraDeClasePorSemana" to outOfClassHoursPerWeek.toPlainString(),
                    "semanasDeReferencia" to referenceWeeks.toPlainString(),
                    "instruccionDirecta" to "teoria+practica",
                    "paso" to step.toPlainString(),
                ),
            source = "34 CFR 600.2, definición de credit hour",
            totalHours = workload.totalHours,
            calculated = calculated,
            proposed = floorToStep(calculated, step),
            warnings = warnings,
        )
    }
}
