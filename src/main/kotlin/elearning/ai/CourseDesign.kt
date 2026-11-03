package elearning.ai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import elearning.workload.CreditProposal
import elearning.workload.CreditSystem
import elearning.workload.ReferencePolicies
import elearning.workload.WeeklyHours
import elearning.workload.Workload
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.RoundingMode

// el diseño pedagógico (paso 3 de docs/ai §1, E33.5, RF-IA-02): de un análisis de fuentes SUCCEEDED y su brief, los
// resultados de aprendizaje medibles, el temario y su secuencia (las unidades, en orden), las actividades, las evaluaciones
// alineadas con los resultados, las lecturas y los criterios de aprobación. Sigue el patrón de paso tipado (docs/ai §5): una
// interfaz con dos implementaciones (ModelCourseDesigner con Embabel y DeterministicCourseDesigner), una comprobación por
// código igual para las dos (CourseDesignCheck) y un JobHandler sobre AiStepRunner (CourseDesignJobHandler).
//
// Horas y créditos no son del modelo: el tipo no los tiene y lo que traiga de más se ignora (ignoreUnknown). La carga la
// calcula workload con las horas del brief repartidas por unidad (una semana cada una) y por tipo de actividad, y los créditos
// son las propuestas de sus políticas de referencia (CourseDesignCheck.workload)

// la salida tipada que se pide al modelo. Lo que el código descarta uno a uno (una lectura sin procedencia, una referencia a
// un resultado que no existe) no invalida el diseño; lo que lo deja inservible (sin resultados, una evaluación sin ningún
// resultado alineado), sí
@JsonIgnoreProperties(ignoreUnknown = true)
data class CourseDesign(
    val outcomes: List<LearningOutcome>,
    // en orden: el temario y su secuencia
    val units: List<DesignUnit>,
    val assessments: List<DesignAssessment>,
    val passingCriteria: List<String>,
)

// un resultado de aprendizaje medible: lo que la persona sabrá hacer y cómo se observa
@JsonIgnoreProperties(ignoreUnknown = true)
data class LearningOutcome(
    // O1, O2…: lo citan las unidades y las evaluaciones
    val id: String,
    val statement: String,
    val evidence: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DesignUnit(
    val title: String,
    val summary: String = "",
    val topics: List<String> = emptyList(),
    // los id de los resultados que trabaja
    val outcomes: List<String> = emptyList(),
    val activities: List<DesignActivity> = emptyList(),
    val readings: List<DesignReading> = emptyList(),
)

// los tipos de actividad de workload: teoría y práctica son contacto; supervisada, trabajo dirigido; autónoma, estudio propio
enum class ActivityKind { THEORY, PRACTICE, SUPERVISED, AUTONOMOUS }

@JsonIgnoreProperties(ignoreUnknown = true)
data class DesignActivity(
    val title: String,
    val kind: ActivityKind,
    val description: String = "",
)

// una lectura de las fuentes analizadas: source es la referencia de un elemento del análisis (owner/repo@sha:ruta#ancla) o
// de un archivo que leyó
@JsonIgnoreProperties(ignoreUnknown = true)
data class DesignReading(
    val title: String,
    val source: String? = null,
)

enum class AssessmentKind { QUIZ, ASSIGNMENT, PROJECT, EXAM, PRACTICAL }

@JsonIgnoreProperties(ignoreUnknown = true)
data class DesignAssessment(
    val title: String,
    val kind: AssessmentKind,
    // los id de los resultados con que se alinea: al menos uno que exista
    val outcomes: List<String> = emptyList(),
    val description: String = "",
)

// la salida de la ejecución: el diseño comprobado y la carga que calculó workload
data class CourseDesignOutput(
    val design: CourseDesign,
    val workload: DesignWorkload,
)

// la carga del diseño (workload): semanas, horas por semana de cada tipo, totales y la propuesta de créditos de cada sistema
// con su política de referencia (PROPUESTO: aprobarlos es de una persona, ADR-0035)
data class DesignWorkload(
    val weeks: Int,
    val weekly: WeeklyHours,
    val theoryHours: BigDecimal,
    val practiceHours: BigDecimal,
    val supervisedHours: BigDecimal,
    val autonomousHours: BigDecimal,
    val contactHours: BigDecimal,
    val totalHours: BigDecimal,
    val credits: List<CreditProposal>,
) {
    companion object {
        fun of(workload: Workload): DesignWorkload =
            DesignWorkload(
                weeks = workload.weeks,
                weekly = workload.weekly,
                theoryHours = plainHours(workload.theoryHours),
                practiceHours = plainHours(workload.practiceHours),
                supervisedHours = plainHours(workload.supervisedHours),
                autonomousHours = plainHours(workload.autonomousHours),
                contactHours = plainHours(workload.contactHours),
                totalHours = plainHours(workload.totalHours),
                credits = CreditSystem.entries.map { ReferencePolicies.of(it).propose(workload) },
            )
    }
}

// sin ceros a la derecha y sin notación científica: 60 h se escribe 60, no 6E+1
internal fun plainHours(value: BigDecimal): BigDecimal = value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

// las entradas del paso, que guardan el trabajo y la ejecución: el análisis SUCCEEDED del que parte, su brief y las horas de
// este, que son las que reparte workload
data class CourseDesignInputs(
    val analysisExecutionId: String,
    val briefId: String,
    val topic: String,
    val audience: String?,
    val objectives: String?,
    val language: String?,
    val level: String?,
    val hours: BigDecimal,
) {
    companion object {
        fun of(inputs: JsonNode): CourseDesignInputs = AiJobs.MAPPER.treeToValue(inputs, CourseDesignInputs::class.java)
    }
}

// lo que el paso lee antes de llamar: la salida del análisis y las referencias de las que una lectura puede salir (las de
// sus elementos y los archivos que leyó)
data class DesignMaterial(
    val analysis: SourceAnalysis,
    val references: Set<String>,
) {
    companion object {
        fun of(
            analysis: SourceAnalysis,
            files: Collection<String>,
        ): DesignMaterial =
            DesignMaterial(
                analysis,
                (analysis.concepts.mapNotNull { it.source } + analysis.procedures.mapNotNull { it.source } + files).toSet(),
            )
    }
}

// una implementación del paso. Bloquea: solo se llama desde su trabajo, en el dispatcher acotado de la cola. Devuelve la
// salida tal cual la produjo, sin comprobar, y el consumo (null con el doble)
interface CourseDesigner {
    fun design(
        prompt: AiPrompt,
        material: DesignMaterial,
    ): ModelReply<CourseDesign>
}

// lo que deja el diseño inservible: con uno, INVALID_OUTPUT
enum class DesignProblemCode {
    NO_OUTCOMES,
    BLANK_OUTCOME,
    DUPLICATE_OUTCOME,
    NO_UNITS,
    BLANK_UNIT,
    UNIT_WITHOUT_ACTIVITIES,
    NO_ASSESSMENTS,
    BLANK_ASSESSMENT,
    ASSESSMENT_NOT_ALIGNED,
    NO_PASSING_CRITERIA,
    TOO_MANY_ELEMENTS,
    TOO_MUCH_WEEKLY_LOAD,
}

data class DesignProblem(
    val code: DesignProblemCode,
    // el elemento: «assessments[2]», «units[0]»…
    val at: String,
    val detail: String? = null,
)

// lo que se descartó sin invalidar el diseño: una lectura sin procedencia o de una fuente que el análisis no leyó
// (NO_PROVENANCE, SOURCE_NOT_READ) y una referencia a un resultado que no existe (UNKNOWN_OUTCOME)
data class DesignDiscard(
    val at: String,
    val value: String?,
    val reason: String,
)

// el informe de validación de la ejecución
data class CourseDesignReport(
    val outcomes: Int,
    val units: Int,
    val assessments: Int,
    val problems: List<DesignProblem>,
    val discarded: List<DesignDiscard>,
    // los resultados que ninguna evaluación mide: aviso para quien revisa, no invalida
    val unassessedOutcomes: List<String>,
)

// la comprobación por código, igual para el modelo y para el doble (docs/ai §5): resultados con id único, enunciado y
// evidencia; unidades con título y alguna actividad; cada evaluación alineada con al menos un resultado que existe; algún
// criterio de aprobación; y la carga dentro de lo que una semana admite. Un problema es AiInvalidOutput (INVALID_OUTPUT, sin
// borrador); lo demás se descarta y queda en el informe. La carga y los créditos salen de workload, nunca del modelo
object CourseDesignCheck {
    const val MAX_OUTCOMES = 50
    const val MAX_ACTIVITIES = 30
    const val MAX_ASSESSMENTS = 50
    const val MAX_LIST = 50
    const val MAX_NAME = 200
    const val MAX_TEXT = 2000

    // una unidad es una semana del curso
    const val MAX_UNITS = Workload.MAX_WEEKS

    fun checked(
        design: CourseDesign,
        material: DesignMaterial,
        hours: BigDecimal,
        usage: TokenUsage?,
    ): AiReply {
        val problems = mutableListOf<DesignProblem>()
        val discarded = mutableListOf<DesignDiscard>()
        if (design.outcomes.size > MAX_OUTCOMES) problems += DesignProblem(DesignProblemCode.TOO_MANY_ELEMENTS, "outcomes", "more than $MAX_OUTCOMES")
        if (design.units.size > MAX_UNITS) problems += DesignProblem(DesignProblemCode.TOO_MANY_ELEMENTS, "units", "more than $MAX_UNITS")
        if (design.assessments.size > MAX_ASSESSMENTS) {
            problems += DesignProblem(DesignProblemCode.TOO_MANY_ELEMENTS, "assessments", "more than $MAX_ASSESSMENTS")
        }
        if (problems.isNotEmpty()) throw invalid(problems, report(design, problems, discarded, emptyList()), usage)

        val outcomes = outcomes(design.outcomes, problems)
        val ids = outcomes.map { it.id }.toSet()

        fun aligned(
            at: String,
            refs: List<String>,
        ): List<String> =
            refs.map { it.trim() }.filter { it.isNotEmpty() }.distinct().filter { ref ->
                (ref in ids).also { known -> if (!known) discarded += DesignDiscard(at, ref.take(MAX_NAME), UNKNOWN_OUTCOME) }
            }

        if (design.units.isEmpty()) problems += DesignProblem(DesignProblemCode.NO_UNITS, "units")
        val units =
            design.units.mapIndexed { index, unit ->
                val at = "units[$index]"
                val activities =
                    unit.activities
                        .filter { it.title.isNotBlank() }
                        .take(MAX_ACTIVITIES)
                        .map { DesignActivity(it.title.trim().take(MAX_NAME), it.kind, it.description.trim().take(MAX_TEXT)) }
                if (unit.title.isBlank()) problems += DesignProblem(DesignProblemCode.BLANK_UNIT, at)
                if (activities.isEmpty()) problems += DesignProblem(DesignProblemCode.UNIT_WITHOUT_ACTIVITIES, at)
                DesignUnit(
                    title = unit.title.trim().take(MAX_NAME),
                    summary = unit.summary.trim().take(MAX_TEXT),
                    topics = texts(unit.topics),
                    outcomes = aligned("$at.outcomes", unit.outcomes),
                    activities = activities,
                    readings = readings("$at.readings", unit.readings, material, discarded),
                )
            }

        if (design.assessments.isEmpty()) problems += DesignProblem(DesignProblemCode.NO_ASSESSMENTS, "assessments")
        val assessments =
            design.assessments.mapIndexed { index, assessment ->
                val at = "assessments[$index]"
                val refs = aligned("$at.outcomes", assessment.outcomes)
                if (assessment.title.isBlank()) problems += DesignProblem(DesignProblemCode.BLANK_ASSESSMENT, at)
                if (refs.isEmpty()) {
                    problems += DesignProblem(DesignProblemCode.ASSESSMENT_NOT_ALIGNED, at, "aligned with no learning outcome of the design")
                }
                DesignAssessment(assessment.title.trim().take(MAX_NAME), assessment.kind, refs, assessment.description.trim().take(MAX_TEXT))
            }

        val criteria = texts(design.passingCriteria)
        if (criteria.isEmpty()) problems += DesignProblem(DesignProblemCode.NO_PASSING_CRITERIA, "passingCriteria")

        val checked = CourseDesign(outcomes, units, assessments, criteria)
        val workload =
            if (problems.isNotEmpty()) {
                null
            } else {
                try {
                    workload(units, hours)
                } catch (e: IllegalArgumentException) {
                    problems += DesignProblem(DesignProblemCode.TOO_MUCH_WEEKLY_LOAD, "units", e.message)
                    null
                }
            }
        val assessed = assessments.flatMap { it.outcomes }.toSet()
        val report = report(checked, problems, discarded, outcomes.map { it.id }.filter { it !in assessed })
        if (problems.isNotEmpty() || workload == null) throw invalid(problems, report, usage)
        return AiReply(CourseDesignOutput(checked, DesignWorkload.of(workload)), usage, report)
    }

    // la carga de workload: cada unidad es una semana y las horas del brief se reparten entre los tipos de actividad según
    // cuántas actividades de cada tipo tiene el diseño. Lo que el modelo diga de horas o créditos no entra aquí
    fun workload(
        units: List<DesignUnit>,
        hours: BigDecimal,
    ): Workload {
        require(hours.signum() > 0) { "the brief must have a positive number of hours, not $hours" }
        val activities = units.flatMap { it.activities }
        require(activities.isNotEmpty()) { "the design has no activities" }
        val weeks = units.size
        val divisor = BigDecimal(activities.size) * BigDecimal(weeks)

        fun weekly(kind: ActivityKind): BigDecimal {
            val count = activities.count { it.kind == kind }
            if (count == 0) return BigDecimal.ZERO
            return plainHours((hours * BigDecimal(count)).divide(divisor, SCALE, RoundingMode.HALF_UP))
        }
        return Workload(
            weeks,
            WeeklyHours(weekly(ActivityKind.THEORY), weekly(ActivityKind.PRACTICE), weekly(ActivityKind.SUPERVISED), weekly(ActivityKind.AUTONOMOUS)),
        )
    }

    private fun outcomes(
        proposed: List<LearningOutcome>,
        problems: MutableList<DesignProblem>,
    ): List<LearningOutcome> {
        if (proposed.isEmpty()) problems += DesignProblem(DesignProblemCode.NO_OUTCOMES, "outcomes")
        val seen = mutableSetOf<String>()
        return proposed.mapIndexedNotNull { index, outcome ->
            val at = "outcomes[$index]"
            val id = outcome.id.trim()
            when {
                id.isEmpty() || outcome.statement.isBlank() || outcome.evidence.isBlank() -> {
                    problems += DesignProblem(DesignProblemCode.BLANK_OUTCOME, at, "an outcome needs its id, statement and evidence")
                    null
                }
                !seen.add(id) -> {
                    problems += DesignProblem(DesignProblemCode.DUPLICATE_OUTCOME, at, id.take(MAX_NAME))
                    null
                }
                else -> LearningOutcome(id.take(MAX_NAME), outcome.statement.trim().take(MAX_TEXT), outcome.evidence.trim().take(MAX_TEXT))
            }
        }
    }

    private fun readings(
        at: String,
        proposed: List<DesignReading>,
        material: DesignMaterial,
        discarded: MutableList<DesignDiscard>,
    ): List<DesignReading> =
        proposed.filter { it.title.isNotBlank() }.take(MAX_LIST).mapNotNull { reading ->
            val source = reading.source?.trim().orEmpty()
            when {
                source.isEmpty() -> {
                    discarded += DesignDiscard(at, reading.title.take(MAX_NAME), NO_PROVENANCE)
                    null
                }
                source !in material.references -> {
                    discarded += DesignDiscard(at, source.take(MAX_NAME), SOURCE_NOT_READ)
                    null
                }
                else -> DesignReading(reading.title.trim().take(MAX_NAME), source)
            }
        }

    private fun texts(values: List<String>): List<String> =
        values
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_LIST)
            .map { it.take(MAX_TEXT) }

    private fun report(
        design: CourseDesign,
        problems: List<DesignProblem>,
        discarded: List<DesignDiscard>,
        unassessed: List<String>,
    ) = CourseDesignReport(design.outcomes.size, design.units.size, design.assessments.size, problems.toList(), discarded.toList(), unassessed)

    private fun invalid(
        problems: List<DesignProblem>,
        report: CourseDesignReport,
        usage: TokenUsage?,
    ) = AiInvalidOutput(
        "the course design is not usable: " + problems.take(MAX_SHOWN).joinToString { "${it.at} ${it.code}" },
        usage,
        report,
    )

    const val NO_PROVENANCE = "NO_PROVENANCE"
    const val SOURCE_NOT_READ = "SOURCE_NOT_READ"
    const val UNKNOWN_OUTCOME = "UNKNOWN_OUTCOME"
    private const val SCALE = 4
    private const val MAX_SHOWN = 5
}
