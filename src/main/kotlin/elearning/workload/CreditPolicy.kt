package elearning.workload

import java.math.BigDecimal
import java.math.RoundingMode

// PE_CREDITO: el crédito de la Ley 30220 del Perú, en horas lectivas (ADR-0036). ninguno se convierte en otro (RF-CRE-06)
enum class CreditSystem { ECTS, SCH, PE_CREDITO }

// a propósito sin APROBADO: aprobar créditos lo hace una persona con autoridad académica en un acto registrado
// (aprobacion_creditos, ADR-0035), nunca este cálculo
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

// las políticas de referencia, una por sistema y con los parámetros de la norma: proponen para una versión de curso que
// no está ligada a una política en datos (ADR-0007, ADR-0036)
object ReferencePolicies {
    fun of(system: CreditSystem): CreditPolicy =
        when (system) {
            CreditSystem.ECTS -> EctsPolicy()
            CreditSystem.SCH -> SemesterCreditHourPolicy()
            CreditSystem.PE_CREDITO -> PeruLectiveHoursPolicy()
        }
}

// los métodos de cálculo que sabe el código, cada uno de un sistema. la política (en datos) elige uno, fija sus
// parámetros y lleva su fuente y su versión (ADR-0036)
enum class CreditMethod(
    val system: CreditSystem,
) {
    ECTS_CARGA_TOTAL(CreditSystem.ECTS),
    US_SCH_34CFR600_2(CreditSystem.SCH),
    PE_LEY30220_HORAS_LECTIVAS(CreditSystem.PE_CREDITO),
}

object CreditMethods {
    // una política en datos como regla de cálculo. un parámetro desconocido, que no es un número o que se sale de la norma
    // (ECTS fuera de 25–30 h, PE bajo 16 o 32 h) es IllegalArgumentException: quien la guarda responde 400. sin
    // parámetros, los de la política de referencia
    fun of(
        method: CreditMethod,
        parameters: Map<String, String>,
        id: String,
        version: String,
        source: String,
    ): CreditPolicy =
        when (method) {
            CreditMethod.ECTS_CARGA_TOTAL -> {
                val p = numbers(parameters, setOf(ECTS_HOURS, STEP))
                EctsPolicy(p[ECTS_HOURS] ?: BigDecimal(27), p[STEP] ?: HALF, id, version, source)
            }
            CreditMethod.US_SCH_34CFR600_2 -> {
                // instruccionDirecta solo describe qué cuenta como instrucción: la de referencia, teoría y práctica
                parameters[SCH_DIRECT]?.let { require(it == SCH_DIRECT_VALUE) { "$SCH_DIRECT must be $SCH_DIRECT_VALUE, not '$it'" } }
                val p = numbers(parameters - SCH_DIRECT, setOf(SCH_INSTRUCTION, SCH_OUT_OF_CLASS, SCH_WEEKS, STEP))
                SemesterCreditHourPolicy(
                    p[SCH_INSTRUCTION] ?: BigDecimal.ONE,
                    p[SCH_OUT_OF_CLASS] ?: BigDecimal(2),
                    p[SCH_WEEKS] ?: BigDecimal(15),
                    p[STEP] ?: HALF,
                    id,
                    version,
                    source,
                )
            }
            CreditMethod.PE_LEY30220_HORAS_LECTIVAS -> {
                val p = numbers(parameters, setOf(PE_THEORY, PE_PRACTICE, STEP))
                PeruLectiveHoursPolicy(p[PE_THEORY] ?: BigDecimal(16), p[PE_PRACTICE] ?: BigDecimal(32), p[STEP] ?: HALF, id, version, source)
            }
        }

    private fun numbers(
        parameters: Map<String, String>,
        known: Set<String>,
    ): Map<String, BigDecimal> {
        val unknown = parameters.keys - known
        require(unknown.isEmpty()) { "unknown parameters ${unknown.sorted()}: the method takes ${known.sorted()}" }
        return parameters.mapValues { (name, value) -> requireNotNull(value.trim().toBigDecimalOrNull()) { "parameter $name must be a number, not '$value'" } }
    }

    private val HALF = BigDecimal("0.5")
    const val STEP = "paso"
    const val ECTS_HOURS = "horasPorCredito"
    const val SCH_INSTRUCTION = "horasInstruccionPorSemana"
    const val SCH_OUT_OF_CLASS = "horasFueraDeClasePorSemana"
    const val SCH_WEEKS = "semanasDeReferencia"
    const val SCH_DIRECT = "instruccionDirecta"
    const val SCH_DIRECT_VALUE = "teoria+practica"
    const val PE_THEORY = "horasTeoriaPorCredito"
    const val PE_PRACTICE = "horasPracticaPorCredito"
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
    override val id: String = "ects-carga-total",
    override val version: String = "1",
    val source: String = "ECTS Users' Guide (EHEA): 60 créditos = 1500–1800 h; 1 crédito = 25–30 h",
) : CreditPolicy {
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
            source = source,
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
    override val id: String = "sch-34cfr600.2-trabajo-equivalente",
    override val version: String = "1",
    val source: String = "34 CFR 600.2, definición de credit hour",
) : CreditPolicy {
    override val system = CreditSystem.SCH

    init {
        require(listOf(instructionHoursPerWeek, outOfClassHoursPerWeek, referenceWeeks, step).all { it.signum() > 0 }) {
            "every parameter must be positive"
        }
    }

    override fun propose(workload: Workload): CreditProposal {
        val hoursPerCredit = (instructionHoursPerWeek + outOfClassHoursPerWeek) * referenceWeeks
        val calculated = divide(workload.totalHours, hoursPerCredit)
        val proposed = floorToStep(calculated, step)
        // el piso se mide contra lo que se propone, no contra el cociente sin redondear
        val instructionFloor = proposed * instructionHoursPerWeek * referenceWeeks
        val warnings =
            if (workload.contactHours < instructionFloor) {
                listOf(
                    "la instrucción directa (${workload.contactHours.toPlainString()} h) no llega al mínimo de " +
                        "${instructionFloor.setScale(2, RoundingMode.HALF_UP).toPlainString()} h para ${proposed.toPlainString()} créditos",
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
            source = source,
            totalHours = workload.totalHours,
            calculated = calculated,
            proposed = proposed,
            warnings = warnings,
        )
    }
}

// Perú, Ley 30220 art. 39: un crédito académico es como mínimo 16 h lectivas de teoría o el doble (32) de práctica. solo
// cuentan las horas lectivas: el estudio autónomo no figura en la ley. las supervisadas tampoco se suman: su equivalencia,
// como la de la modalidad a distancia, la decide la universidad, y por eso se avisa en lugar de calcularla
class PeruLectiveHoursPolicy(
    val theoryHoursPerCredit: BigDecimal = BigDecimal(16),
    val practiceHoursPerCredit: BigDecimal = BigDecimal(32),
    val step: BigDecimal = BigDecimal("0.5"),
    override val id: String = "pe-ley30220-horas-lectivas",
    override val version: String = "1",
    val source: String = "Ley 30220, Ley Universitaria (Perú), art. 39: 1 crédito = 16 h lectivas de teoría o 32 de práctica como mínimo",
) : CreditPolicy {
    override val system = CreditSystem.PE_CREDITO

    init {
        require(theoryHoursPerCredit >= MIN_THEORY) { "theory hours per credit must be at least $MIN_THEORY (Ley 30220), not $theoryHoursPerCredit" }
        require(practiceHoursPerCredit >= MIN_PRACTICE) { "practice hours per credit must be at least $MIN_PRACTICE (Ley 30220), not $practiceHoursPerCredit" }
        require(step.signum() > 0) { "step must be positive" }
    }

    override fun propose(workload: Workload): CreditProposal {
        val calculated = (divide(workload.theoryHours, theoryHoursPerCredit) + divide(workload.practiceHours, practiceHoursPerCredit)).stripTrailingZeros()
        val warnings =
            if (workload.supervisedHours.signum() > 0) {
                listOf("las ${workload.supervisedHours.toPlainString()} h supervisadas no se cuentan: su equivalencia la decide la universidad")
            } else {
                emptyList()
            }
        return CreditProposal(
            system = system,
            policyId = id,
            policyVersion = version,
            method =
                "horas de teoría / horas por crédito de teoría + horas de práctica / horas por crédito de práctica, sin estudio autónomo, " +
                    "redondeo hacia abajo al paso",
            parameters =
                mapOf(
                    "horasTeoriaPorCredito" to theoryHoursPerCredit.toPlainString(),
                    "horasPracticaPorCredito" to practiceHoursPerCredit.toPlainString(),
                    "paso" to step.toPlainString(),
                ),
            source = source,
            totalHours = workload.totalHours,
            calculated = calculated,
            proposed = floorToStep(calculated, step),
            warnings = warnings,
        )
    }

    companion object {
        private val MIN_THEORY = BigDecimal(16)
        private val MIN_PRACTICE = BigDecimal(32)
    }
}
