package elearning.ai

import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

// lo que se guarda de un guion comprobado (RF-MED-01, ADR-0040 §2): el guion del render como JSON (el esquema de
// render-worker/schema/guion.schema.json, con sus nombres en español), los subtítulos en WebVTT y la transcripción. Todo sale
// del mismo VideoScript, así que no se contradicen
object VideoScriptDocuments {
    const val SCHEMA_VERSION = 1

    // la fuente OFL por defecto del trabajador (ADR-0040 §2): ninguna se elige ni se descarga desde el guion del paso
    const val TYPEFACE = "inter"

    // el guion del render: version, idioma, titulo, objetivos, tipografia, salida y secciones, sin campos de más ni nulos.
    // Cada traza owner/repo@sha:ruta#ancla es { doc: owner/repo@sha:ruta, ancla }
    fun props(
        script: VideoScript,
        inputs: VideoScriptInputs,
    ): Map<String, Any> =
        linkedMapOf(
            "version" to SCHEMA_VERSION,
            "idioma" to inputs.language,
            "titulo" to script.title,
            "objetivos" to script.objectives,
            "tipografia" to TYPEFACE,
            "salida" to linkedMapOf("resolucion" to inputs.resolution, "fps" to inputs.fps),
            "secciones" to
                script.sections.map { section ->
                    linkedMapOf(
                        "id" to section.id,
                        "titulo" to section.title,
                        "segundos" to section.seconds,
                        "diapositiva" to slide(section.slide),
                        "narracion" to section.narration,
                        "subtitulos" to section.captions.map { linkedMapOf("desde" to it.from, "hasta" to it.to, "texto" to it.text) },
                        "trazas" to
                            section.traces.map { trace ->
                                val doc = trace.substringBefore('#')
                                val anchor = trace.substringAfter('#', "")
                                if (anchor.isEmpty()) linkedMapOf("doc" to doc) else linkedMapOf("doc" to doc, "ancla" to anchor)
                            },
                    )
                },
        )

    private fun slide(slide: ScriptSlide): Map<String, Any> {
        val fields: List<Pair<String, Any?>> =
            when (slide.kind) {
                SlideKind.COVER -> listOf("tipo" to "portada", "titulo" to slide.title, "subtitulo" to slide.subtitle)
                SlideKind.BULLETS -> listOf("tipo" to "vinetas", "titulo" to slide.title, "vinetas" to slide.bullets)
                SlideKind.CODE -> listOf("tipo" to "codigo", "titulo" to slide.title?.ifBlank { null }, "lenguaje" to slide.language, "codigo" to slide.code)
                SlideKind.CLOSING -> listOf("tipo" to "cierre", "titulo" to slide.title, "mensaje" to slide.message)
            }
        return fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap(LinkedHashMap())
    }

    fun json(props: Map<String, Any>): String = AiJobs.MAPPER.writeValueAsString(props)

    // los subtítulos del vídeo entero: cada cue con su tiempo absoluto (el de su sección más el inicio de la sección) y en el
    // orden del guion. Uno por subtítulo del guion, que es lo que comprobará el render (ADR-0040 §5)
    fun webVtt(script: VideoScript): String =
        buildString {
            append("WEBVTT\n")
            var offset = 0L
            var cue = 0
            for (section in script.sections) {
                for (caption in section.captions) {
                    cue++
                    append('\n').append(cue).append('\n')
                    append(timestamp(offset * MILLIS + millis(caption.from)))
                    append(" --> ")
                    append(timestamp(offset * MILLIS + millis(caption.to)))
                    append('\n').append(caption.text).append('\n')
                }
                offset += section.seconds
            }
        }

    // la transcripción: el título de cada sección y lo que se dice en ella
    fun transcript(script: VideoScript): String = script.sections.joinToString("\n\n", postfix = "\n") { "${it.title}\n${it.narration}" }

    fun seconds(script: VideoScript): Int = script.sections.sumOf { it.seconds }

    fun sha256(text: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    private fun millis(seconds: Double): Long = Math.round(seconds * MILLIS)

    // HH:MM:SS.mmm
    private fun timestamp(millis: Long): String =
        String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", millis / 3_600_000, millis / 60_000 % 60, millis / 1000 % 60, millis % 1000)

    private const val MILLIS = 1000L
}
