package elearning.ai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import elearning.course.meeting.SafeLink
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

// el borrador con plantillas versionadas (paso 4 de docs/ai §1, E33.6): de un diseño pedagógico SUCCEEDED, las lecciones de
// cada unidad en la fuente canónica, AsciiDoc. Sigue el patrón de paso tipado (docs/ai §5): una interfaz con dos
// implementaciones (ModelLessonDrafter con Embabel y DeterministicLessonDrafter), una comprobación por código igual para las dos
// (LessonDraftCheck) y un JobHandler sobre AiStepRunner.runWriting (LessonDraftJobHandler), que deja el borrador como
// version_curso BORRADOR a nombre de quien lo pidió (LessonDraftWriter).
//
// El modelo no escribe el documento: escribe las partes de cada lección (objetivo, cuerpo, verificación) y el código las
// junta con la plantilla versionada (LessonTemplate). La plantilla y su versión quedan en AgentRun (version_plantilla) y en la
// versión (plantilla_ia)

// la salida tipada que se pide al modelo: una entrada por unidad del diseño, en su orden
@JsonIgnoreProperties(ignoreUnknown = true)
data class LessonDraft(
    val units: List<LessonDraftUnit>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LessonDraftUnit(
    val lessons: List<LessonDraftLesson> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LessonDraftLesson(
    val title: String,
    val summary: String = "",
    // lo que la persona sabrá hacer al terminarla
    val objective: String = "",
    val durationMinutes: Int? = null,
    // la referencia de la sección de las fuentes de la que sale (owner/repo@sha:ruta#ancla)
    val source: String? = null,
    // el cuerpo en AsciiDoc, con secciones de nivel 2 (==) o más y sin título de documento
    val body: String = "",
    // cómo comprueba la persona que lo logró: la sección de verificación de la plantilla
    val checks: List<String> = emptyList(),
)

// la salida de la ejecución: las unidades del diseño con sus lecciones ya en AsciiDoc, la plantilla con que se juntaron y la
// versión de curso que las guarda (versionId y versionNumber los pone LessonDraftWriter al crearla: docs/ai §13)
data class LessonDraftOutput(
    val template: String,
    val units: List<DraftedUnit>,
    // de la versión, sacado del diseño por código: sus semanas y horas (workload), sus resultados y su sistema de evaluación
    val load: DesignedLoad,
    val outcomes: List<String>,
    val assessmentSystem: List<String>,
    val versionId: String? = null,
    val versionNumber: Int? = null,
)

data class DraftedUnit(
    val title: String,
    val summary: String,
    // los enunciados de los resultados de aprendizaje que trabaja
    val objectives: List<String>,
    val lessons: List<DraftedLesson>,
)

data class DraftedLesson(
    val title: String,
    val summary: String,
    val objective: String,
    val durationMinutes: Int?,
    val source: String?,
    // la lección entera, con la plantilla: la fuente canónica de su recurso
    val asciidoc: String,
)

// las entradas del paso: el diseño SUCCEEDED del que parte, su análisis y su brief, y las fuentes del brief, que se vuelven a
// leer (en el mismo commit exacto) para darle al generador su texto y saber qué enlaces traen
data class LessonDraftInputs(
    val designExecutionId: String,
    val analysisExecutionId: String,
    val briefId: String,
    val topic: String,
    val language: String?,
    val sources: List<String>,
) {
    companion object {
        fun of(inputs: JsonNode): LessonDraftInputs = AiJobs.MAPPER.treeToValue(inputs, LessonDraftInputs::class.java)
    }
}

// el diseño SUCCEEDED tal como lo guardó su ejecución (CourseDesignOutput): el diseño y, de su carga, las semanas y las horas de
// cada una, que pasan a la versión. Lo demás de la carga (totales, créditos PROPUESTO) no se lee aquí
@JsonIgnoreProperties(ignoreUnknown = true)
data class DesignedCourse(
    val design: CourseDesign,
    val workload: DesignedLoad,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DesignedLoad(
    val weeks: Int,
    val weekly: DesignedWeek,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DesignedWeek(
    val theory: BigDecimal = BigDecimal.ZERO,
    val practice: BigDecimal = BigDecimal.ZERO,
    val supervised: BigDecimal = BigDecimal.ZERO,
    val autonomous: BigDecimal = BigDecimal.ZERO,
) {
    fun total(): BigDecimal = plainHours(theory + practice + supervised + autonomous)
}

// lo que el paso lee antes de llamar: el diseño con su carga, las secciones de las fuentes con su texto (de las que puede salir
// una lección) y los enlaces que ya traen esas fuentes
data class LessonDraftMaterial(
    val design: DesignedCourse,
    val sections: List<SourceSection>,
    val sourceLinks: Set<String>,
) {
    val references: Set<String> get() = sections.map { it.ref }.toSet()

    companion object {
        fun of(
            design: DesignedCourse,
            sources: SourceMaterial,
        ): LessonDraftMaterial = LessonDraftMaterial(design, sources.sections, sources.sections.flatMap { DraftValidator.linksIn(it.text) }.toSet())
    }
}

// una implementación del paso. Bloquea: solo se llama desde su trabajo, en el dispatcher acotado de la cola. Devuelve la
// salida tal cual la produjo, sin comprobar, y el consumo (null con el doble)
interface LessonDrafter {
    fun draft(
        prompt: AiPrompt,
        material: LessonDraftMaterial,
    ): ModelReply<LessonDraft>
}

// la plantilla de una lección, versionada: cambiar su texto es subir VERSION (entra en AgentRun, en la clave de idempotencia
// y en la versión de curso). Título, resumen, Objetivo, el cuerpo, Verificación y la fuente, en el idioma del brief. Un título
// de documento (=) dentro del cuerpo baja a sección (==): la lección tiene uno solo, el de la plantilla
object LessonTemplate {
    const val NAME = "lesson-draft"
    const val VERSION = "$NAME@1"

    private data class Labels(
        val objective: String,
        val check: String,
        val source: String,
    )

    private val SPANISH = Labels("Objetivo", "Verificación", "Fuente")
    private val ENGLISH = Labels("Objective", "Check", "Source")
    private val DOCUMENT_TITLE = Regex("^=(\\s)")

    fun isSpanish(language: String?): Boolean = language?.lowercase()?.startsWith("es") == true

    fun render(
        title: String,
        summary: String,
        objective: String,
        body: String,
        checks: List<String>,
        source: String?,
        language: String?,
    ): String {
        val labels = if (isSpanish(language)) SPANISH else ENGLISH
        val lang = language?.takeIf { it.matches(LANGUAGE) } ?: if (isSpanish(language)) "es" else "en"
        return buildString {
            append("= ").append(oneLine(title)).append('\n')
            append(":lang: ").append(lang).append("\n\n")
            if (summary.isNotBlank()) append(summary.trim()).append("\n\n")
            append("== ")
                .append(labels.objective)
                .append("\n\n")
                .append(objective.trim())
                .append("\n\n")
            append(body.trim().lines().joinToString("\n") { it.replace(DOCUMENT_TITLE, "==$1") }).append("\n\n")
            append("== ").append(labels.check).append("\n\n")
            checks.forEach { append("* ").append(oneLine(it)).append('\n') }
            if (source != null) {
                append('\n')
                    .append(labels.source)
                    .append(": `")
                    .append(source)
                    .append("`\n")
            }
        }
    }

    private fun oneLine(text: String) = text.trim().lines().joinToString(" ") { it.trim() }

    private val LANGUAGE = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
}

// lo que deja el borrador inservible: con uno, INVALID_OUTPUT
enum class LessonDraftProblemCode {
    UNITS_MISMATCH,
    UNIT_WITHOUT_LESSONS,
    BLANK_LESSON,
    LESSON_WITHOUT_CHECKS,
    TOO_MANY_ELEMENTS,
    LESSON_TOO_LONG,
}

data class LessonDraftProblem(
    val code: LessonDraftProblemCode,
    // el elemento: «units[1].lessons[0]»…
    val at: String,
    val detail: String? = null,
)

// por qué un enlace del borrador queda para revisión: no está en ninguna de las fuentes leídas, o no pasa SafeLink (https
// público). No se quita: lo decide quien revisa
enum class LinkReviewReason { NOT_IN_SOURCES, UNSAFE }

data class LinkToReview(
    val at: String,
    val lesson: String,
    val url: String,
    val reason: LinkReviewReason,
)

// el informe de validación de la ejecución: lo descartado (una fuente que no es una sección leída, una duración fuera de
// rango) y los enlaces para revisión
data class LessonDraftReport(
    val units: Int,
    val lessons: Int,
    val problems: List<LessonDraftProblem>,
    val discarded: List<DesignDiscard>,
    val linksToReview: List<LinkToReview>,
)

// la comprobación por código, igual para el modelo y para el doble (docs/ai §5): una entrada por unidad del diseño, cada una
// con alguna lección; cada lección con título, objetivo, cuerpo y alguna verificación, y de un tamaño que cabe en su fuente.
// Una fuente que no es la referencia de una sección leída (NO_PROVENANCE, SOURCE_NOT_READ) y una duración fuera de rango
// (INVALID_DURATION) se descartan y quedan en el informe; los enlaces del texto que no están en las fuentes o no son seguros
// se marcan para revisión (linksToReview). El AsciiDoc de cada lección lo junta LessonTemplate
object LessonDraftCheck {
    const val MAX_LESSONS = 20
    const val MAX_NAME = 200
    const val MAX_TEXT = 2000
    const val MAX_CHECKS = 20

    // en caracteres: una fuente_recurso admite 1 MB, y una lección no es un manual
    const val MAX_ASCIIDOC = 100_000
    const val MAX_MINUTES = 600

    const val NO_PROVENANCE = "NO_PROVENANCE"
    const val SOURCE_NOT_READ = "SOURCE_NOT_READ"
    const val INVALID_DURATION = "INVALID_DURATION"
    private const val MAX_SHOWN = 5

    fun checked(
        draft: LessonDraft,
        material: LessonDraftMaterial,
        language: String?,
        usage: TokenUsage?,
    ): AiReply {
        val design = material.design.design
        val problems = mutableListOf<LessonDraftProblem>()
        val discarded = mutableListOf<DesignDiscard>()
        val links = mutableListOf<LinkToReview>()
        if (draft.units.size != design.units.size) {
            problems +=
                LessonDraftProblem(LessonDraftProblemCode.UNITS_MISMATCH, "units", "${draft.units.size} units for the ${design.units.size} of the design")
        }
        val statements = design.outcomes.associate { it.id to it.statement }
        val units =
            design.units.zip(draft.units).mapIndexed { u, (planned, drafted) ->
                val at = "units[$u]"
                if (drafted.lessons.isEmpty()) problems += LessonDraftProblem(LessonDraftProblemCode.UNIT_WITHOUT_LESSONS, at)
                if (drafted.lessons.size >
                    MAX_LESSONS
                ) {
                    problems += LessonDraftProblem(LessonDraftProblemCode.TOO_MANY_ELEMENTS, at, "more than $MAX_LESSONS lessons")
                }
                DraftedUnit(
                    title = planned.title,
                    summary = planned.summary,
                    objectives = planned.outcomes.mapNotNull { statements[it] },
                    lessons =
                        drafted.lessons.take(MAX_LESSONS).mapIndexed {
                            l,
                            lesson,
                            ->
                            lesson("$at.lessons[$l]", lesson, material, language, problems, discarded, links)
                        },
                )
            }
        val output =
            LessonDraftOutput(
                template = LessonTemplate.VERSION,
                units = units,
                load = material.design.workload,
                outcomes = design.outcomes.map { "${it.id}. ${it.statement}" },
                assessmentSystem = design.assessments.map { "${it.title} (${it.outcomes.joinToString()})" } + design.passingCriteria,
            )
        val report = LessonDraftReport(units.size, units.sumOf { it.lessons.size }, problems.toList(), discarded.toList(), links.toList())
        if (problems.isNotEmpty()) {
            throw AiInvalidOutput("the lesson draft is not usable: " + problems.take(MAX_SHOWN).joinToString { "${it.at} ${it.code}" }, usage, report)
        }
        return AiReply(output, usage, report)
    }

    private fun lesson(
        at: String,
        lesson: LessonDraftLesson,
        material: LessonDraftMaterial,
        language: String?,
        problems: MutableList<LessonDraftProblem>,
        discarded: MutableList<DesignDiscard>,
        links: MutableList<LinkToReview>,
    ): DraftedLesson {
        val title = lesson.title.trim().take(MAX_NAME)
        val objective = lesson.objective.trim().take(MAX_TEXT)
        val summary = lesson.summary.trim().take(MAX_TEXT)
        val checks =
            lesson.checks
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_CHECKS)
                .map { it.take(MAX_TEXT) }
        if (title.isEmpty() || objective.isEmpty() || lesson.body.isBlank()) {
            problems += LessonDraftProblem(LessonDraftProblemCode.BLANK_LESSON, at, "a lesson needs its title, objective and body")
        }
        if (checks.isEmpty()) problems += LessonDraftProblem(LessonDraftProblemCode.LESSON_WITHOUT_CHECKS, at)
        val source = lesson.source?.trim().orEmpty()
        val kept =
            when {
                source.isEmpty() -> {
                    discarded += DesignDiscard("$at.source", title.ifEmpty { null }, NO_PROVENANCE)
                    null
                }
                source !in material.references -> {
                    discarded += DesignDiscard("$at.source", source.take(MAX_NAME), SOURCE_NOT_READ)
                    null
                }
                else -> source
            }
        val minutes =
            lesson.durationMinutes?.takeIf { it in 1..MAX_MINUTES }.also {
                if (it == null &&
                    lesson.durationMinutes != null
                ) {
                    discarded += DesignDiscard("$at.durationMinutes", lesson.durationMinutes.toString(), INVALID_DURATION)
                }
            }
        val asciidoc = LessonTemplate.render(title, summary, objective, lesson.body, checks, kept, language)
        if (asciidoc.length > MAX_ASCIIDOC) {
            problems += LessonDraftProblem(LessonDraftProblemCode.LESSON_TOO_LONG, at, "${asciidoc.length} characters, more than $MAX_ASCIIDOC")
        }
        // lo que escribió el generador: la referencia de la fuente la pone la plantilla y no es un enlace
        val written = listOf(title, summary, objective, lesson.body).plus(checks).joinToString("\n")
        DraftValidator.linksIn(written).distinct().forEach { url ->
            val reason =
                when {
                    !SafeLink.isAllowed(url) -> LinkReviewReason.UNSAFE
                    url !in material.sourceLinks -> LinkReviewReason.NOT_IN_SOURCES
                    else -> null
                }
            if (reason != null) links += LinkToReview(at, title, url.take(MAX_TEXT), reason)
        }
        return DraftedLesson(title, summary, objective, minutes, kept, asciidoc.take(MAX_ASCIIDOC))
    }
}
