package elearning.ai

import elearning.course.meeting.SafeLink
import elearning.importer.SourceRef
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.HexFormat

// la validación por código de un borrador de versión de curso (RF-IA-05, docs/ai §1 paso 6): estructura, referencias,
// alineación objetivos-evaluación, enlaces (SafeLink), duplicidad por hash, accesibilidad (texto alternativo y jerarquía de
// títulos) y metadatos. Determinista y sin red: no visita ningún enlace ni lee ninguna fuente, solo lo que el borrador dice.
// Cada problema lleva su gravedad, como el informe del importador: BLOCKING impide enviar a revisión un borrador generado
// (GeneratedDraftGuard); WARNING es algo que revisar. El texto del borrador es dato no confiable: aquí solo se mide

enum class ValidationCategory { STRUCTURE, REFERENCES, ALIGNMENT, LINKS, DUPLICATES, ACCESSIBILITY, METADATA }

enum class ValidationSeverity { BLOCKING, WARNING }

// cada problema que se sabe encontrar, con su categoría y su gravedad; uno nuevo entra aquí y en docs/ai §13
enum class ValidationKind(
    val category: ValidationCategory,
    val severity: ValidationSeverity,
) {
    // la versión no tiene unidades
    EMPTY_VERSION(ValidationCategory.STRUCTURE, ValidationSeverity.BLOCKING),

    // una unidad sin lecciones
    EMPTY_UNIT(ValidationCategory.STRUCTURE, ValidationSeverity.BLOCKING),

    // dos hermanas con el mismo orden: la secuencia no está definida
    REPEATED_ORDER(ValidationCategory.STRUCTURE, ValidationSeverity.WARNING),

    // una lección de tipo EVALUACION sin su evaluación
    LESSON_WITHOUT_EVALUATION(ValidationCategory.STRUCTURE, ValidationSeverity.BLOCKING),

    // una evaluación sin preguntas
    EMPTY_EVALUATION(ValidationCategory.STRUCTURE, ValidationSeverity.BLOCKING),

    // una pregunta que se corrige sola sin respuesta correcta
    QUESTION_WITHOUT_ANSWER(ValidationCategory.STRUCTURE, ValidationSeverity.BLOCKING),

    // una unidad o lección sin traza a su fuente (repo@sha:ruta#ancla)
    MISSING_SOURCE_REF(ValidationCategory.REFERENCES, ValidationSeverity.WARNING),

    // una traza que no es repo@sha:ruta#ancla
    INVALID_SOURCE_REF(ValidationCategory.REFERENCES, ValidationSeverity.BLOCKING),

    // una traza a un repositorio o commit que la versión no declara en sus fuentes
    UNDECLARED_SOURCE(ValidationCategory.REFERENCES, ValidationSeverity.WARNING),

    // la versión no dice qué se aprende: ni objetivos ni resultados de aprendizaje
    MISSING_OUTCOMES(ValidationCategory.ALIGNMENT, ValidationSeverity.BLOCKING),

    // la versión no evalúa nada: sus resultados no se comprueban
    NO_ASSESSMENT(ValidationCategory.ALIGNMENT, ValidationSeverity.BLOCKING),

    // una unidad con objetivos y sin ninguna evaluación en sus lecciones
    UNASSESSED_OBJECTIVES(ValidationCategory.ALIGNMENT, ValidationSeverity.WARNING),

    // una evaluación en una unidad y una lección sin objetivos: no se sabe qué comprueba
    UNALIGNED_EVALUATION(ValidationCategory.ALIGNMENT, ValidationSeverity.WARNING),

    // un enlace que no pasa SafeLink: no https o hacia la propia red
    UNSAFE_LINK(ValidationCategory.LINKS, ValidationSeverity.BLOCKING),

    // el mismo contenido (por hash, normalizado) en dos lecciones o dos preguntas
    DUPLICATE_CONTENT(ValidationCategory.DUPLICATES, ValidationSeverity.WARNING),

    // una imagen sin texto alternativo
    IMAGE_WITHOUT_ALT(ValidationCategory.ACCESSIBILITY, ValidationSeverity.BLOCKING),

    // un título que salta niveles (de = a ===, de # a ###)
    HEADING_LEVEL_SKIPPED(ValidationCategory.ACCESSIBILITY, ValidationSeverity.WARNING),

    // una lección sin duración
    MISSING_DURATION(ValidationCategory.METADATA, ValidationSeverity.WARNING),

    // una unidad sin horas estimadas
    MISSING_HOURS(ValidationCategory.METADATA, ValidationSeverity.WARNING),

    // la versión no describe su sistema de evaluación
    MISSING_ASSESSMENT_SYSTEM(ValidationCategory.METADATA, ValidationSeverity.WARNING),
}

// la forma del problema del importador (ImportProblem): origin es objeto:id del registro, field su campo (null: el registro
// entero) y line la línea dentro del campo (0: el campo entero). detail puede citar el borrador: dato, no instrucción
data class ValidationProblem(
    val kind: ValidationKind,
    val category: ValidationCategory,
    val severity: ValidationSeverity,
    val origin: String,
    val field: String?,
    val line: Int,
    val detail: String,
)

// el informe que queda en AgentRun.validations. contentHash es el hash del borrador validado: si el borrador cambia, el
// informe deja de valer. la lista se recorta a MAX_PROBLEMS; los totales cuentan todos
data class ValidationReport(
    val versionId: String,
    val validator: String,
    val contentHash: String,
    val units: Int,
    val lessons: Int,
    val evaluations: Int,
    val blocking: Boolean,
    val problemCount: Int,
    val problems: List<ValidationProblem>,
    val problemsBySeverity: Map<ValidationSeverity, Int>,
    val problemsByCategory: Map<ValidationCategory, Int>,
)

// lo que se valida de una versión, leído por DraftSnapshots. texts: los campos de texto largo no vacíos, por nombre
data class DraftSnapshot(
    val versionId: String,
    val texts: Map<String, String>,
    val units: List<DraftUnit>,
)

data class DraftUnit(
    val id: String,
    val order: Int,
    val title: String,
    val hours: String?,
    val sourceRef: String?,
    val texts: Map<String, String>,
    val lessons: List<DraftLesson>,
)

data class DraftLesson(
    val id: String,
    val order: Int,
    val title: String,
    val type: String,
    val minutes: Int?,
    val sourceRef: String?,
    val texts: Map<String, String>,
    val resources: Int,
    val labs: List<DraftLab>,
    val evaluations: List<DraftEvaluation>,
)

data class DraftLab(
    val id: String,
    val title: String,
    val link: String,
)

data class DraftEvaluation(
    val id: String,
    val title: String,
    val instructions: String?,
    val questions: List<DraftQuestion>,
)

data class DraftQuestion(
    val id: String,
    val order: Int,
    val type: String,
    val statement: String,
    val acceptedAnswers: String?,
    val options: List<DraftOption>,
)

data class DraftOption(
    val id: String,
    val order: Int,
    val text: String,
    val correct: Boolean,
)

object DraftValidator {
    // la versión de estas reglas: queda en AgentRun (prompt_version y template_version) y en el informe
    const val VERSION = "draft-validator/1"
    const val MAX_PROBLEMS = 500
    private val MAPPER: JsonMapper = JsonMapper.builder().findAndAddModules().build()
    private const val MAX_DETAIL = 500

    // el hash del borrador: el mismo contenido da el mismo hash, lo lea quien lo lea (las listas llegan ordenadas)
    fun hash(snapshot: DraftSnapshot): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MAPPER.writeValueAsBytes(snapshot)))

    fun validate(snapshot: DraftSnapshot): ValidationReport {
        val found = Problems()
        val version = "$VERSION_OBJECT:${snapshot.versionId}"
        structure(snapshot, found)
        references(snapshot, found)
        alignment(snapshot, found)
        duplicates(snapshot, found)
        if (snapshot.texts[ASSESSMENT_SYSTEM] == null) found.add(ValidationKind.MISSING_ASSESSMENT_SYSTEM, version, ASSESSMENT_SYSTEM, "")
        texts(version, snapshot.texts, found)
        for (unit in snapshot.units) {
            val origin = "$UNIT_OBJECT:${unit.id}"
            if (unit.hours == null) found.add(ValidationKind.MISSING_HOURS, origin, "horas_estimadas", unit.title)
            texts(origin, unit.texts, found)
            for (lesson in unit.lessons) {
                val at = "$LESSON_OBJECT:${lesson.id}"
                if (lesson.minutes == null) found.add(ValidationKind.MISSING_DURATION, at, "duracion_minutos", lesson.title)
                texts(at, lesson.texts, found)
                for (lab in lesson.labs) {
                    if (!SafeLink.isAllowed(lab.link)) found.add(ValidationKind.UNSAFE_LINK, "entorno_laboratorio:${lab.id}", "enlace", lab.link)
                }
                for (evaluation in lesson.evaluations) {
                    evaluation.instructions?.let { text("evaluacion:${evaluation.id}", "instrucciones", it, found) }
                    evaluation.questions.forEach { text("pregunta:${it.id}", "enunciado", it.statement, found) }
                }
            }
        }
        val all = found.list
        return ValidationReport(
            versionId = snapshot.versionId,
            validator = VERSION,
            contentHash = hash(snapshot),
            units = snapshot.units.size,
            lessons = snapshot.units.sumOf { it.lessons.size },
            evaluations = evaluations(snapshot).size,
            blocking = all.any { it.severity == ValidationSeverity.BLOCKING },
            problemCount = all.size,
            problems = all.take(MAX_PROBLEMS),
            problemsBySeverity = ValidationSeverity.entries.associateWith { s -> all.count { it.severity == s } },
            problemsByCategory = ValidationCategory.entries.associateWith { c -> all.count { it.category == c } },
        )
    }

    private fun structure(
        snapshot: DraftSnapshot,
        found: Problems,
    ) {
        if (snapshot.units.isEmpty()) found.add(ValidationKind.EMPTY_VERSION, "$VERSION_OBJECT:${snapshot.versionId}", null, "")
        repeatedOrder(snapshot.units.map { Triple("$UNIT_OBJECT:${it.id}", it.order, it.title) }, found)
        for (unit in snapshot.units) {
            if (unit.lessons.isEmpty()) found.add(ValidationKind.EMPTY_UNIT, "$UNIT_OBJECT:${unit.id}", null, unit.title)
            repeatedOrder(unit.lessons.map { Triple("$LESSON_OBJECT:${it.id}", it.order, it.title) }, found)
            for (lesson in unit.lessons) {
                if (lesson.type == EVALUATION_LESSON && lesson.evaluations.isEmpty()) {
                    found.add(ValidationKind.LESSON_WITHOUT_EVALUATION, "$LESSON_OBJECT:${lesson.id}", null, lesson.title)
                }
                for (evaluation in lesson.evaluations) {
                    if (evaluation.questions.isEmpty()) found.add(ValidationKind.EMPTY_EVALUATION, "evaluacion:${evaluation.id}", null, evaluation.title)
                    for (question in evaluation.questions) {
                        val unanswered =
                            when (question.type) {
                                in CHOICE -> question.options.none { it.correct }
                                SHORT -> question.acceptedAnswers.isNullOrBlank()
                                else -> false
                            }
                        if (unanswered) found.add(ValidationKind.QUESTION_WITHOUT_ANSWER, "pregunta:${question.id}", null, question.statement)
                    }
                }
            }
        }
    }

    private fun repeatedOrder(
        siblings: List<Triple<String, Int, String>>,
        found: Problems,
    ) {
        siblings.groupBy { it.second }.values.filter { it.size > 1 }.flatMap { it.drop(1) }.forEach { (origin, order, title) ->
            found.add(ValidationKind.REPEATED_ORDER, origin, "orden", "$order: $title")
        }
    }

    // cada unidad y cada lección con su traza válida, a una fuente que la versión declara (si declara alguna)
    private fun references(
        snapshot: DraftSnapshot,
        found: Problems,
    ) {
        val declared =
            snapshot.texts[SOURCES]
                ?.lines()
                ?.mapNotNull { line -> runCatching { SourceRef.parse(line.substringBefore('#')) }.getOrNull() }
                ?.map { "${it.repository}@${it.sha}" }
                ?.toSet()
                .orEmpty()

        fun check(
            origin: String,
            trace: String?,
            title: String,
        ) {
            if (trace == null) return found.add(ValidationKind.MISSING_SOURCE_REF, origin, SOURCE_REF, title)
            val ref = parseTrace(trace) ?: return found.add(ValidationKind.INVALID_SOURCE_REF, origin, SOURCE_REF, trace)
            if (declared.isNotEmpty() && "${ref.repository}@${ref.sha}" !in declared) found.add(ValidationKind.UNDECLARED_SOURCE, origin, SOURCE_REF, trace)
        }
        for (unit in snapshot.units) {
            check("$UNIT_OBJECT:${unit.id}", unit.sourceRef, unit.title)
            unit.lessons.forEach { check("$LESSON_OBJECT:${it.id}", it.sourceRef, it.title) }
        }
    }

    // repo@sha:ruta con un ancla opcional (#id), como las escribe el importador
    private fun parseTrace(trace: String): SourceRef? {
        val anchor = trace.substringAfter('#', "")
        if (trace.contains('#') && !ANCHOR.matches(anchor)) return null
        return runCatching { SourceRef.parse(trace.substringBefore('#')) }.getOrNull()
    }

    private fun alignment(
        snapshot: DraftSnapshot,
        found: Problems,
    ) {
        val version = "$VERSION_OBJECT:${snapshot.versionId}"
        if (snapshot.texts[OBJECTIVES] == null && snapshot.texts[OUTCOMES] == null) found.add(ValidationKind.MISSING_OUTCOMES, version, OUTCOMES, "")
        if (snapshot.units.isNotEmpty() && evaluations(snapshot).isEmpty()) found.add(ValidationKind.NO_ASSESSMENT, version, null, "")
        for (unit in snapshot.units) {
            val assessed = unit.lessons.any { it.evaluations.isNotEmpty() }
            if (unit.texts[OBJECTIVES] != null && !assessed) found.add(ValidationKind.UNASSESSED_OBJECTIVES, "$UNIT_OBJECT:${unit.id}", OBJECTIVES, unit.title)
            for (lesson in unit.lessons) {
                if (unit.texts[OBJECTIVES] != null || lesson.texts[LESSON_OBJECTIVE] != null) continue
                lesson.evaluations.forEach { found.add(ValidationKind.UNALIGNED_EVALUATION, "evaluacion:${it.id}", null, it.title) }
            }
        }
    }

    // el mismo resumen en dos lecciones, o el mismo enunciado en dos preguntas, normalizados: se marca cada repetición
    private fun duplicates(
        snapshot: DraftSnapshot,
        found: Problems,
    ) {
        val lessons = snapshot.units.flatMap { it.lessons }
        repeated(lessons.mapNotNull { l -> l.texts[SUMMARY]?.let { Triple("$LESSON_OBJECT:${l.id}", SUMMARY, it) } }, found)
        repeated(evaluations(snapshot).flatMap { e -> e.questions.map { Triple("pregunta:${it.id}", "enunciado", it.statement) } }, found)
    }

    private fun repeated(
        items: List<Triple<String, String, String>>,
        found: Problems,
    ) {
        val seen = mutableMapOf<String, String>()
        for ((origin, field, text) in items) {
            val normalized =
                text
                    .lowercase()
                    .split(WHITESPACE)
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
            if (normalized.isEmpty()) continue
            val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()))
            val first = seen.putIfAbsent(digest, origin)
            if (first != null) found.add(ValidationKind.DUPLICATE_CONTENT, origin, field, "same content as $first (sha256 $digest)")
        }
    }

    private fun texts(
        origin: String,
        texts: Map<String, String>,
        found: Problems,
    ) = texts.forEach { (field, text) -> text(origin, field, text, found) }

    // enlaces, imágenes y títulos de un campo, en AsciiDoc, Markdown o HTML, línea a línea
    private fun text(
        origin: String,
        field: String,
        text: String,
        found: Problems,
    ) {
        var level: Int? = null
        text.lines().forEachIndexed { index, line ->
            val number = index + 1
            val heading = (ASCIIDOC_HEADING.find(line) ?: MARKDOWN_HEADING.find(line))?.groupValues?.get(1)?.length
            if (heading != null) {
                if (level != null && heading > level!! + 1) {
                    found.add(ValidationKind.HEADING_LEVEL_SKIPPED, origin, field, "level $level to $heading: ${line.trim()}", number)
                }
                level = heading
            }
            val images =
                ASCIIDOC_IMAGE.findAll(line).map { it.groupValues[1] to it.groupValues[2].substringBefore(',') } +
                    MARKDOWN_IMAGE.findAll(line).map { it.groupValues[2] to it.groupValues[1] } +
                    HTML_IMAGE.findAll(line).map { tag -> tag.value to (HTML_ALT.find(tag.value)?.let { it.groupValues[2] + it.groupValues[3] } ?: "") }
            images.filter { (_, alt) -> alt.isBlank() }.forEach { (target, _) -> found.add(ValidationKind.IMAGE_WITHOUT_ALT, origin, field, target, number) }
            linksIn(line).filterNot(SafeLink::isAllowed).forEach {
                found.add(ValidationKind.UNSAFE_LINK, origin, field, it, number)
            }
        }
    }

    // los enlaces de un texto, sin la puntuación que los cierra: los de un borrador generado se comparan además con los de sus
    // fuentes (LessonDraftCheck)
    internal fun linksIn(text: String): Sequence<String> = LINK.findAll(text).map { it.value.trimEnd(*TRAILING) }

    private fun evaluations(snapshot: DraftSnapshot) = snapshot.units.flatMap { u -> u.lessons.flatMap { it.evaluations } }

    private class Problems {
        val list = mutableListOf<ValidationProblem>()

        fun add(
            kind: ValidationKind,
            origin: String,
            field: String?,
            detail: String,
            line: Int = 0,
        ) {
            list += ValidationProblem(kind, kind.category, kind.severity, origin, field, line, detail.take(MAX_DETAIL))
        }
    }

    const val VERSION_OBJECT = "version_curso"
    const val UNIT_OBJECT = "unidad"
    const val LESSON_OBJECT = "leccion"
    const val OBJECTIVES = "objetivos"
    const val OUTCOMES = "resultados_aprendizaje"
    const val ASSESSMENT_SYSTEM = "sistema_evaluacion"
    const val SOURCES = "fuentes"
    const val SUMMARY = "resumen"
    const val LESSON_OBJECTIVE = "objetivo"
    const val SOURCE_REF = "referencia_fuente"
    private const val EVALUATION_LESSON = "EVALUACION"
    private const val SHORT = "CORTA"
    private val CHOICE = setOf("UNICA", "MULTIPLE", "VERDADERO_FALSO")
    private val ANCHOR = Regex("^[A-Za-z0-9_:.-]+$")
    private val WHITESPACE = Regex("\\s+")
    private val ASCIIDOC_HEADING = Regex("^(={1,6})\\s+\\S")
    private val MARKDOWN_HEADING = Regex("^(#{1,6})\\s+\\S")

    // image::destino[alt, ancho] e image:destino[alt]; ![alt](destino); <img … alt="…">
    private val ASCIIDOC_IMAGE = Regex("image::?([^\\s\\[]+)\\[([^\\]]*)]")
    private val MARKDOWN_IMAGE = Regex("!\\[([^\\]]*)]\\(([^)\\s]*)")
    private val HTML_IMAGE = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val HTML_ALT = Regex("\\balt\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)

    // una URL con esquema (https://, http://, ftp://…) o los esquemas que ejecutan o leen en local, sin //
    private val LINK = Regex("(?i)\\b(?:[a-z][a-z0-9+.-]*://|javascript:|data:|file:|vbscript:)[^\\s\\[\\]<>\"'()]+")
    private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?')
}
