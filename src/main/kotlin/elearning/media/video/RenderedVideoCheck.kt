package elearning.media.video

import tools.jackson.databind.JsonNode
import kotlin.math.abs

// un subtítulo, en segundos desde el principio del vídeo
data class Cue(
    val from: Double,
    val to: Double,
)

// lo que un guion pide del vídeo: lo que se compara con el informe de ffprobe y con los subtítulos renderizados
data class ExpectedVideo(
    val width: Int,
    val height: Int,
    val fps: Int,
    val seconds: Int,
    val cues: List<Cue>,
)

// RF-MED-03 (ADR-0040 §5): el informe de ffprobe del trabajador (ffprobe -show_format -show_streams, en JSON) y el VTT
// renderizado contra el guion que se pidió. Sin binarios ni archivos: lo que compara son datos, y cada problema se
// devuelve con lo que se esperaba y lo que llegó. Lista vacía: el vídeo es el del guion. El sha256 de los bytes contra el
// del informe lo comprueba quien los recibe (VideoRenderJobHandler)
//
// - contenedor MP4 (no QuickTime: la marca mayor del archivo es ISO), una sola pista de vídeo H.264 en yuv420p (la que
//   reproducen los navegadores) con la resolución y los fps del guion
// - ninguna pista de audio ni de otro tipo: el guion no trae audio (la narración es texto) y los subtítulos van aparte
// - duración del contenedor y de la pista igual a la suma de las secciones, con un fotograma más 0,1 s de tolerancia
// - tamaño igual al del informe y dentro del tope
// - VTT bien formado, ordenado, sin solapes, dentro de la duración y con una señal por subtítulo del guion, en su tiempo
object RenderedVideoCheck {
    const val DEFAULT_RESOLUTION = "1280x720"
    const val DEFAULT_FPS = 30
    private const val MARGIN_SECONDS = 0.1
    private const val CUE_TOLERANCE = 0.0015
    private val MP4_BRANDS = listOf("iso", "mp4", "avc1")
    private val TIMING = Regex("""^(\S+)\s+-->\s+(\S+)(\s.*)?$""")
    private val TIMESTAMP = Regex("""^(?:(\d{2,}):)?([0-5]\d):([0-5]\d)\.(\d{3})$""")

    // los parámetros del guion con los valores por defecto del trabajador (render-worker/src/guion.mjs)
    fun expected(script: JsonNode): ExpectedVideo {
        val output = script.get("salida")
        val resolution = output?.get("resolucion")?.asString() ?: DEFAULT_RESOLUTION
        val (width, height) = resolution.split("x").map { it.toInt() }
        val cues = mutableListOf<Cue>()
        var offset = 0.0
        for (section in script.get("secciones").values()) {
            section.get("subtitulos")?.values()?.forEach { cues += Cue(offset + it.get("desde").asDouble(), offset + it.get("hasta").asDouble()) }
            offset += section.get("segundos").asInt()
        }
        return ExpectedVideo(width, height, output?.get("fps")?.asInt() ?: DEFAULT_FPS, ScriptSchema.durationOf(script), cues)
    }

    fun video(
        ffprobe: JsonNode?,
        expected: ExpectedVideo,
        reportedBytes: Long?,
        maxBytes: Long,
    ): List<String> {
        if (ffprobe == null || !ffprobe.isObject) return listOf("the render report has no ffprobe output")
        val problems = mutableListOf<String>()
        val format = ffprobe.get("format")
        val formats =
            format
                ?.get("format_name")
                ?.asString()
                ?.split(",")
                .orEmpty()
        val brand =
            format
                ?.get("tags")
                ?.get("major_brand")
                ?.asString()
                ?.trim()
                ?.lowercase()
        if ("mp4" !in formats || brand == null || MP4_BRANDS.none { brand.startsWith(it) }) {
            problems += "the container is not MP4 (format ${formats.joinToString(",").ifEmpty { "unknown" }}, brand ${brand ?: "none"})"
        }
        val streams = ffprobe.get("streams")?.values().orEmpty()
        val videos = streams.filter { it.text("codec_type") == "video" }
        if (videos.size != 1) problems += "the video has ${videos.size} video tracks, not 1"
        videos.firstOrNull()?.let { stream ->
            if (stream.text("codec_name") != "h264") problems += "the video track is ${stream.text("codec_name")}, not h264"
            if (stream.text("pix_fmt") != "yuv420p") problems += "the video track is ${stream.text("pix_fmt")}, not yuv420p"
            val width = stream.get("width")?.asInt()
            val height = stream.get("height")?.asInt()
            if (width != expected.width || height != expected.height) {
                problems += "the video is ${width}x$height, not ${expected.width}x${expected.height}"
            }
            val rate = rate(stream.text("r_frame_rate"))
            if (rate == null || abs(rate - expected.fps) > 0.01) problems += "the video runs at ${stream.text("r_frame_rate")} fps, not ${expected.fps}"
            stream.text("duration")?.toDoubleOrNull()?.let { duration(it, expected, "video track")?.let(problems::add) }
        }
        val audio = streams.count { it.text("codec_type") == "audio" }
        if (audio > 0) problems += "the video has $audio audio tracks, but its script has no audio"
        streams
            .mapNotNull { it.text("codec_type") }
            .filter { it != "video" && it != "audio" }
            .distinct()
            .forEach { problems += "the video has an unexpected $it track" }
        val seconds = format?.text("duration")?.toDoubleOrNull()
        if (seconds == null) problems += "ffprobe does not give the duration" else duration(seconds, expected, "video")?.let(problems::add)
        val size = format?.text("size")?.toLongOrNull()
        if (reportedBytes == null || size != reportedBytes) problems += "ffprobe measured $size bytes, the report says $reportedBytes"
        if (reportedBytes != null && reportedBytes > maxBytes) problems += "the video takes $reportedBytes bytes, more than $maxBytes"
        return problems
    }

    fun subtitles(
        vtt: String,
        expected: ExpectedVideo,
    ): List<String> {
        val cues =
            try {
                parseVtt(vtt)
            } catch (e: IllegalArgumentException) {
                return listOf("the subtitles are not valid WebVTT: ${e.message}")
            }
        val problems = mutableListOf<String>()
        var previous = 0.0
        cues.forEachIndexed { i, cue ->
            if (cue.to <= cue.from) problems += "subtitle ${i + 1} ends before it starts"
            if (cue.from < previous) problems += "subtitle ${i + 1} is out of order or overlaps the previous one"
            if (cue.to > expected.seconds + CUE_TOLERANCE) problems += "subtitle ${i + 1} ends after the video (${expected.seconds} s)"
            previous = maxOf(previous, cue.to)
        }
        if (cues.size != expected.cues.size) {
            problems += "the subtitles have ${cues.size} cues, the script ${expected.cues.size}"
        } else {
            cues.zip(expected.cues).forEachIndexed { i, (got, wanted) ->
                if (abs(got.from - wanted.from) > CUE_TOLERANCE || abs(got.to - wanted.to) > CUE_TOLERANCE) {
                    problems += "subtitle ${i + 1} is at ${got.from}-${got.to} s, the script puts it at ${wanted.from}-${wanted.to} s"
                }
            }
        }
        return problems
    }

    // WebVTT: la cabecera WEBVTT y bloques separados por una línea vacía; cada señal, un identificador opcional, su
    // tiempo y al menos una línea de texto. Los bloques NOTE se saltan; cualquier otro bloque es un error
    fun parseVtt(text: String): List<Cue> {
        val lines = text.removePrefix("").replace("\r\n", "\n").split("\n")
        val header = lines.firstOrNull().orEmpty()
        require(header == "WEBVTT" || header.startsWith("WEBVTT ") || header.startsWith("WEBVTT\t")) { "it does not start with WEBVTT" }
        val blocks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) {
                if (current.isNotEmpty()) blocks += current
                current = mutableListOf()
            } else {
                current += line
            }
        }
        if (current.isNotEmpty()) blocks += current
        // lo que sigue a la cabecera sin línea vacía es parte de ella
        val body = if (lines.size > 1 && lines[1].isNotBlank()) blocks.drop(1) else blocks
        return body.filterNot { it.first().startsWith("NOTE") }.mapIndexed { i, block ->
            val timing = block.indexOfFirst { "-->" in it }
            require(timing == 0 || timing == 1) { "block ${i + 1} has no timing line" }
            require(block.size > timing + 1) { "cue ${i + 1} has no text" }
            val match = TIMING.matchEntire(block[timing].trim()) ?: throw IllegalArgumentException("cue ${i + 1} has a malformed timing line")
            Cue(seconds(match.groupValues[1], i), seconds(match.groupValues[2], i))
        }
    }

    private fun seconds(
        timestamp: String,
        cue: Int,
    ): Double {
        val m = TIMESTAMP.matchEntire(timestamp) ?: throw IllegalArgumentException("cue ${cue + 1} has a malformed timestamp $timestamp")
        val (h, min, s, ms) = m.destructured
        return (h.ifEmpty { "0" }.toLong() * 3600 + min.toLong() * 60 + s.toLong()) + ms.toLong() / 1000.0
    }

    private fun duration(
        seconds: Double,
        expected: ExpectedVideo,
        what: String,
    ): String? {
        val tolerance = 1.0 / expected.fps + MARGIN_SECONDS
        return if (abs(seconds - expected.seconds) > tolerance) "the $what lasts $seconds s, not ${expected.seconds} s" else null
    }

    // "30/1", "30000/1001"
    private fun rate(text: String?): Double? {
        val parts = text?.split("/") ?: return null
        val num = parts[0].toDoubleOrNull() ?: return null
        val den = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        return if (den == 0.0) null else num / den
    }

    private fun JsonNode.text(name: String): String? = get(name)?.takeIf { !it.isNull }?.asString()
}
