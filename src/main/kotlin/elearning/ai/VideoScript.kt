package elearning.ai

import tools.jackson.databind.JsonNode
import kotlin.math.ceil
import kotlin.math.floor

// el guion por secciones de un vídeo (paso 5 de docs/ai §1, E36.1, RF-MED-01): secciones con su duración, su diapositiva de
// una lista cerrada, su narración (la transcripción), sus subtítulos y sus trazas a las secciones de la fuente de las que sale.
// Sigue el patrón de paso tipado de docs/ai §5: una interfaz pequeña con dos implementaciones (ModelScriptWriter con Embabel y
// DeterministicScriptWriter, el doble de §7), una comprobación por código igual para las dos (VideoScriptCheck) y un JobHandler
// sobre AiStepRunner (VideoScriptJobHandler). Lo que guarda es el guion del render de ADR-0040 §2 (VideoScriptDocuments)

// la salida tipada del paso: lo que devuelve el modelo y lo que queda, ya comprobado, como salida de la ejecución
data class VideoScript(
    val title: String,
    val objectives: List<String> = emptyList(),
    val sections: List<ScriptSection>,
)

data class ScriptSection(
    // minúsculas, cifras y guiones, único en el guion
    val id: String,
    val title: String,
    // entero, de 1 a 600
    val seconds: Int,
    val slide: ScriptSlide,
    // lo que se dice: la transcripción de la sección
    val narration: String,
    // relativos al inicio de la sección
    val captions: List<ScriptCaption> = emptyList(),
    // owner/repo@sha:ruta#ancla de cada sección de la fuente de la que sale; al menos una
    val traces: List<String> = emptyList(),
)

// las diapositivas que escribe el paso, de la lista cerrada de ADR-0040 §2 (la de imagen la pone una persona: necesita un
// archivo del curso). Plana a propósito: cada tipo usa sus campos y los demás no se guardan
enum class SlideKind { COVER, BULLETS, CODE, CLOSING }

data class ScriptSlide(
    val kind: SlideKind,
    val title: String? = null,
    // COVER
    val subtitle: String? = null,
    // BULLETS: de 1 a 8
    val bullets: List<String> = emptyList(),
    // CODE: el código se muestra como texto, nunca se ejecuta
    val code: String? = null,
    val language: String? = null,
    // CLOSING
    val message: String? = null,
)

data class ScriptCaption(
    val from: Double,
    val to: Double,
    val text: String,
)

// las entradas del paso, que guardan el trabajo y la ejecución: el brief LISTO del que salen sus fuentes, la versión de recurso
// VIDEO en borrador a la que va el guion, la duración objetivo y la salida del render
data class VideoScriptInputs(
    val briefId: String,
    val versionId: String,
    val topic: String,
    val audience: String?,
    val objectives: String?,
    // BCP 47 corto (es, es-PE), el idioma del guion
    val language: String,
    val level: String?,
    // owner/repo@sha:ruta, una por fuente del brief
    val sources: List<String>,
    val targetSeconds: Int,
    // 1280x720 o 1920x1080
    val resolution: String,
    // 25 o 30
    val fps: Int,
) {
    companion object {
        fun of(inputs: JsonNode): VideoScriptInputs = AiJobs.MAPPER.treeToValue(inputs, VideoScriptInputs::class.java)
    }
}

// una implementación del paso. Bloquea: solo se llama desde su trabajo, en el dispatcher acotado de la cola. Devuelve la
// salida tal cual la produjo, sin comprobar, y el consumo (null con el doble)
interface ScriptWriter {
    fun write(
        prompt: AiPrompt,
        material: SourceMaterial,
    ): ModelReply<VideoScript>
}

// por qué no vale un guion: sin secciones o con demasiadas, un id que no es del patrón o repetido, un texto vacío o demasiado
// largo, una duración que no es de 1 a 600 o una suma fuera del objetivo, una sección sin traza o con una traza a una sección
// que el paso no leyó, un subtítulo fuera de su sección o desordenado, una diapositiva incompleta, o HTML o una URL en un texto
enum class ScriptViolationCode {
    NO_SECTIONS,
    TOO_MANY,
    BAD_ID,
    DUPLICATE_ID,
    BLANK,
    TOO_LONG,
    BAD_SECONDS,
    DURATION_OUT_OF_TARGET,
    NO_TRACE,
    TRACE_NOT_FOUND,
    BAD_CAPTION,
    BAD_SLIDE,
    MARKUP,
}

data class ScriptViolation(
    val code: ScriptViolationCode,
    // el id de la sección, o null si es del guion entero
    val section: String?,
    val detail: String,
)

// el informe de validación de la ejecución
data class VideoScriptReport(
    val sections: Int,
    val seconds: Int,
    val targetSeconds: Int,
    val violations: List<ScriptViolation>,
)

// la comprobación por código del guion, igual para el modelo y para el doble (docs/ai §5 y §6), con los límites del esquema
// del render (ADR-0040 §2, render-worker/schema/guion.schema.json) y las reglas que el esquema no expresa. A diferencia del
// análisis, no se descarta nada: un guion es una secuencia y quitarle una sección cambiaría su duración y su sentido, así que
// cualquier problema lo invalida entero (AiInvalidOutput: INVALID_OUTPUT, sin guion), con la lista en validations
object VideoScriptCheck {
    const val MAX_SECTIONS = 100
    const val MAX_SECTION_SECONDS = 600

    // lms.render.max-seconds por defecto (ADR-0040 §7): el objetivo nunca lo pasa
    const val MAX_SECONDS = 900
    const val MIN_TARGET_SECONDS = 30

    // la suma de las secciones queda a ±10 % del objetivo
    const val TARGET_TOLERANCE = 0.1
    const val MAX_TITLE = 200
    const val MAX_LINE = 300
    const val MAX_OBJECTIVES = 20
    const val MAX_BULLETS = 8
    const val MAX_CODE = 4000
    const val MAX_NARRATION = 10_000
    const val MAX_CAPTIONS = 200
    const val MAX_CAPTION = 500
    const val MAX_TRACES = 20
    const val MAX_TRACE_PART = 200

    val ID = Regex("""^[a-z0-9][a-z0-9-]{0,63}$""")
    val LANGUAGE = Regex("""^[a-z]{2}(-[A-Z]{2})?$""")
    private val CODE_LANGUAGE = Regex("""^[a-z0-9+#-]{1,32}$""")

    // una etiqueta HTML o una URL (o javascript:) en un texto que se pinta: el guion es dato, nunca marcado ni enlaces
    val MARKUP = Regex("""<\s*/?\s*[A-Za-z!][^>]*>|\b[A-Za-z][A-Za-z0-9+.-]*://\S*|\bjavascript:""", RegexOption.IGNORE_CASE)

    // los segundos que admite un objetivo
    fun targetRange(target: Int): IntRange {
        val low = ceil(target * (1 - TARGET_TOLERANCE)).toInt().coerceAtLeast(1)
        val high = floor(target * (1 + TARGET_TOLERANCE)).toInt().coerceAtMost(MAX_SECONDS)
        return low..high
    }

    fun checked(
        script: VideoScript,
        material: SourceMaterial,
        inputs: VideoScriptInputs,
        usage: TokenUsage?,
    ): AiReply {
        val normalized = normalized(script)
        val violations = violations(normalized, material, inputs.targetSeconds)
        val report = VideoScriptReport(normalized.sections.size, normalized.sections.sumOf { it.seconds.toLong() }.toInt(), inputs.targetSeconds, violations)
        if (violations.isNotEmpty()) {
            throw AiInvalidOutput("the script has ${violations.size} problems, the first: ${violations.first().detail}", usage, report)
        }
        return AiReply(normalized, usage, report)
    }

    fun violations(
        script: VideoScript,
        material: SourceMaterial,
        target: Int,
    ): List<ScriptViolation> {
        val found = mutableListOf<ScriptViolation>()

        fun add(
            code: ScriptViolationCode,
            section: String?,
            detail: String,
        ) {
            found += ScriptViolation(code, section, detail)
        }

        fun text(
            value: String?,
            max: Int,
            section: String?,
            what: String,
            required: Boolean = true,
        ) {
            if (value.isNullOrBlank()) {
                if (required) add(ScriptViolationCode.BLANK, section, "$what is blank")
                return
            }
            if (value.length > max) add(ScriptViolationCode.TOO_LONG, section, "$what has ${value.length} characters, more than $max")
            if (MARKUP.containsMatchIn(value)) add(ScriptViolationCode.MARKUP, section, "$what has HTML or a URL")
        }

        text(script.title, MAX_TITLE, null, "the title")
        if (script.objectives.size > MAX_OBJECTIVES) add(ScriptViolationCode.TOO_MANY, null, "${script.objectives.size} objectives, more than $MAX_OBJECTIVES")
        script.objectives.forEachIndexed { i, objective -> text(objective, MAX_LINE, null, "objective ${i + 1}") }
        if (script.sections.isEmpty()) add(ScriptViolationCode.NO_SECTIONS, null, "the script has no sections")
        if (script.sections.size > MAX_SECTIONS) add(ScriptViolationCode.TOO_MANY, null, "${script.sections.size} sections, more than $MAX_SECTIONS")
        val ids = mutableSetOf<String>()
        for (section in script.sections) {
            val id = section.id
            if (!ID.matches(id)) add(ScriptViolationCode.BAD_ID, id, "the section id '${id.take(MAX_TITLE)}' is not lowercase letters, digits and hyphens")
            if (!ids.add(id)) add(ScriptViolationCode.DUPLICATE_ID, id, "the section id '$id' is repeated")
            text(section.title, MAX_TITLE, id, "the section title")
            text(section.narration, MAX_NARRATION, id, "the narration")
            if (section.seconds !in 1..MAX_SECTION_SECONDS) {
                add(ScriptViolationCode.BAD_SECONDS, id, "the section lasts ${section.seconds} s, not from 1 to $MAX_SECTION_SECONDS")
            }
            slide(section, ::add, ::text)
            captions(section, ::add)
            traces(section, material, ::add)
        }
        val total = script.sections.sumOf { it.seconds.toLong() }
        val range = targetRange(target)
        if (script.sections.isNotEmpty() && total !in range.first.toLong()..range.last.toLong()) {
            add(
                ScriptViolationCode.DURATION_OUT_OF_TARGET,
                null,
                "the sections last $total s, outside ${range.first}..${range.last} s for a target of $target s",
            )
        }
        return found
    }

    private fun slide(
        section: ScriptSection,
        add: (ScriptViolationCode, String?, String) -> Unit,
        text: (String?, Int, String?, String, Boolean) -> Unit,
    ) {
        val slide = section.slide
        val id = section.id
        when (slide.kind) {
            SlideKind.COVER -> {
                text(slide.title, MAX_TITLE, id, "the cover title", true)
                text(slide.subtitle, MAX_LINE, id, "the cover subtitle", false)
            }
            SlideKind.BULLETS -> {
                text(slide.title, MAX_TITLE, id, "the slide title", true)
                if (slide.bullets.size !in
                    1..MAX_BULLETS
                ) {
                    add(ScriptViolationCode.BAD_SLIDE, id, "the slide has ${slide.bullets.size} bullets, not from 1 to $MAX_BULLETS")
                }
                slide.bullets.forEachIndexed { i, bullet -> text(bullet, MAX_LINE, id, "bullet ${i + 1}", true) }
            }
            SlideKind.CODE -> {
                // el código es texto que se muestra: puede traer < o una URL, nunca se interpreta
                text(slide.title, MAX_TITLE, id, "the slide title", false)
                if (slide.code.isNullOrBlank()) add(ScriptViolationCode.BAD_SLIDE, id, "the code slide has no code")
                if ((slide.code?.length ?: 0) > MAX_CODE) add(ScriptViolationCode.TOO_LONG, id, "the code has more than $MAX_CODE characters")
                if (slide.language != null &&
                    !CODE_LANGUAGE.matches(slide.language)
                ) {
                    add(ScriptViolationCode.BAD_SLIDE, id, "the code language is not a short key")
                }
            }
            SlideKind.CLOSING -> {
                text(slide.title, MAX_TITLE, id, "the closing title", true)
                text(slide.message, MAX_LINE, id, "the closing message", false)
            }
        }
    }

    // cada subtítulo, dentro de su sección, con desde < hasta, en orden y sin solaparse; su texto, de una línea sin «-->»
    // (cerraría la marca de tiempo de su cue en el WebVTT)
    private fun captions(
        section: ScriptSection,
        add: (ScriptViolationCode, String?, String) -> Unit,
    ) {
        val id = section.id
        if (section.captions.size > MAX_CAPTIONS) add(ScriptViolationCode.TOO_MANY, id, "${section.captions.size} captions, more than $MAX_CAPTIONS")
        var previous = 0.0
        section.captions.forEachIndexed { i, caption ->
            val n = i + 1
            if (!caption.from.isFinite() || !caption.to.isFinite() || caption.from < 0 || caption.from >= caption.to) {
                add(ScriptViolationCode.BAD_CAPTION, id, "caption $n does not go forward in time")
            } else if (caption.to > section.seconds) {
                add(ScriptViolationCode.BAD_CAPTION, id, "caption $n ends after its section")
            } else if (caption.from < previous) {
                add(ScriptViolationCode.BAD_CAPTION, id, "caption $n overlaps the previous one or is out of order")
            }
            if (caption.to.isFinite()) previous = maxOf(previous, caption.to)
            when {
                caption.text.isBlank() -> add(ScriptViolationCode.BLANK, id, "caption $n is blank")
                caption.text.length > MAX_CAPTION -> add(ScriptViolationCode.TOO_LONG, id, "caption $n has more than $MAX_CAPTION characters")
                "-->" in caption.text -> add(ScriptViolationCode.BAD_CAPTION, id, "caption $n has '-->'")
                MARKUP.containsMatchIn(caption.text) -> add(ScriptViolationCode.MARKUP, id, "caption $n has HTML or a URL")
            }
        }
    }

    // RF-MED-01: cada sección con al menos una traza, y cada traza, una sección con ancla que el paso leyó
    private fun traces(
        section: ScriptSection,
        material: SourceMaterial,
        add: (ScriptViolationCode, String?, String) -> Unit,
    ) {
        val id = section.id
        if (section.traces.isEmpty()) add(ScriptViolationCode.NO_TRACE, id, "the section has no trace to the source")
        if (section.traces.size > MAX_TRACES) add(ScriptViolationCode.TOO_MANY, id, "${section.traces.size} traces, more than $MAX_TRACES")
        for (trace in section.traces) {
            if (material.section(trace) == null) {
                add(ScriptViolationCode.TRACE_NOT_FOUND, id, "the trace '${trace.take(MAX_TRACE_PART)}' is not a section that was read")
            } else if (trace.substringBefore('#').length > MAX_TRACE_PART || trace.substringAfter('#').length > MAX_TRACE_PART) {
                add(ScriptViolationCode.TOO_LONG, id, "the trace '${trace.take(MAX_TRACE_PART)}' is too long for the script")
            }
        }
    }

    // los textos sin espacios a los lados y los subtítulos en una línea: lo mismo que se guarda
    fun normalized(script: VideoScript): VideoScript =
        VideoScript(
            title = script.title.trim(),
            objectives = script.objectives.map { it.trim() },
            sections =
                script.sections.map { section ->
                    val slide = section.slide
                    section.copy(
                        id = section.id.trim(),
                        title = section.title.trim(),
                        narration = section.narration.trim(),
                        slide =
                            slide.copy(
                                title = slide.title?.trim(),
                                subtitle = slide.subtitle?.trim()?.ifEmpty { null },
                                bullets = slide.bullets.map { it.trim() },
                                code = slide.code?.trimEnd(),
                                language = slide.language?.trim()?.ifEmpty { null },
                                message = slide.message?.trim()?.ifEmpty { null },
                            ),
                        captions = section.captions.map { it.copy(text = it.text.replace(SPACES, " ").trim()) },
                        traces = section.traces.map { it.trim() }.distinct(),
                    )
                },
        )

    private val SPACES = Regex("""\s+""")
}
