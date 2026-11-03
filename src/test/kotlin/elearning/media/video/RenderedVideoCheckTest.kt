package elearning.media.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.File

// RF-MED-03 (ADR-0040 §5): el informe de ffprobe y el VTT contra el guion. Los informes de src/test/resources/render/ffprobe
// son la salida real de `ffprobe -v error -print_format json -show_format -show_streams` (la orden del trabajador,
// render-worker/src/ffprobe.mjs) sobre vídeos hechos con ffmpeg (testsrc2): ok, H.264 yuv420p 1280x720 a 30 fps y 3 s sin
// audio, como el guion mínimo; audio, el mismo con una pista AAC; other, 1920x1080 a 25 fps, 5 s y yuv444p; qt, el de ok en
// un .mov. completo.vtt es lo que buildVtt (render-worker/src/vtt.mjs) produce para el ejemplo completo
class RenderedVideoCheckTest {
    private val mapper = JsonMapper.builder().build()
    private val examples = File("render-worker/schema/ejemplos/validos")

    private fun resource(name: String): String = javaClass.getResource("/render/$name")!!.readText()

    private fun probe(name: String): JsonNode = mapper.readTree(resource("ffprobe/$name.json"))

    private fun script(name: String): JsonNode = mapper.readTree(File(examples, name))

    private val minimal by lazy { RenderedVideoCheck.expected(script("minimo.json")) }

    private fun sizeOf(probe: JsonNode): Long =
        probe
            .get("format")
            .get("size")
            .asString()
            .toLong()

    @Test
    fun `the script gives what is expected, with the defaults of the worker`() {
        assertEquals(ExpectedVideo(1280, 720, 30, 3, emptyList()), minimal)
        val complete = RenderedVideoCheck.expected(script("completo.json"))
        assertEquals(listOf(1280, 720, 25, 22), listOf(complete.width, complete.height, complete.fps, complete.seconds))
        assertEquals(listOf(Cue(0.0, 3.5), Cue(4.0, 6.5), Cue(6.5, 9.0)), complete.cues)
    }

    @Test
    fun `a real h264 mp4 of the resolution, fps and duration of the script passes`() {
        val ok = probe("ok")
        assertEquals(emptyList<String>(), RenderedVideoCheck.video(ok, minimal, sizeOf(ok), 256L * 1024 * 1024))
    }

    @Test
    fun `an audio track, another resolution, fps, pixel format or duration, or a mov do not pass`() {
        val audio = probe("audio")
        assertEquals(
            listOf("the video has 1 audio tracks, but its script has no audio"),
            RenderedVideoCheck.video(audio, minimal, sizeOf(audio), Long.MAX_VALUE),
        )

        val other = probe("other")
        val problems = RenderedVideoCheck.video(other, minimal, sizeOf(other), Long.MAX_VALUE)
        assertEquals(
            listOf(
                "the video track is yuv444p, not yuv420p",
                "the video is 1920x1080, not 1280x720",
                "the video runs at 25/1 fps, not 30",
                "the video track lasts 5.0 s, not 3 s",
                "the video lasts 5.0 s, not 3 s",
            ),
            problems,
        )

        val qt = probe("qt")
        assertEquals(
            listOf("the container is not MP4 (format mov,mp4,m4a,3gp,3g2,mj2, brand qt)"),
            RenderedVideoCheck.video(qt, minimal, sizeOf(qt), Long.MAX_VALUE),
        )
    }

    @Test
    fun `the duration allows one frame and a tenth of a second`() {
        fun lasting(seconds: String): JsonNode =
            probe("ok").deepCopy().also {
                (it.get("format") as ObjectNode).put("duration", seconds)
                (it.get("streams").get(0) as ObjectNode).put("duration", seconds)
            }
        val inside = lasting("3.130000")
        assertEquals(emptyList<String>(), RenderedVideoCheck.video(inside, minimal, sizeOf(inside), Long.MAX_VALUE))
        val outside = lasting("2.860000")
        assertEquals(2, RenderedVideoCheck.video(outside, minimal, sizeOf(outside), Long.MAX_VALUE).size)
    }

    @Test
    fun `no report, another size or a video above the cap do not pass`() {
        assertEquals(listOf("the render report has no ffprobe output"), RenderedVideoCheck.video(null, minimal, 1, 1))
        val ok = probe("ok")
        assertEquals(listOf("ffprobe measured ${sizeOf(ok)} bytes, the report says 10"), RenderedVideoCheck.video(ok, minimal, 10, Long.MAX_VALUE))
        assertEquals(
            listOf("the video takes ${sizeOf(ok)} bytes, more than 1000"),
            RenderedVideoCheck.video(ok, minimal, sizeOf(ok), 1000),
        )
        val empty = mapper.readTree("""{"streams": [], "format": {"format_name": "mp4", "tags": {"major_brand": "isom"}}}""")
        assertTrue(RenderedVideoCheck.video(empty, minimal, 1, 1).contains("the video has 0 video tracks, not 1"))
    }

    @Test
    fun `the subtitles of the worker pass, one cue per subtitle of the script and at its time`() {
        val complete = RenderedVideoCheck.expected(script("completo.json"))
        assertEquals(emptyList<String>(), RenderedVideoCheck.subtitles(resource("completo.vtt"), complete))
        assertEquals(emptyList<String>(), RenderedVideoCheck.subtitles("WEBVTT\n", minimal))
        assertEquals(
            listOf(Cue(0.0, 3.5), Cue(3723.25, 3724.0)),
            RenderedVideoCheck.parseVtt(
                "WEBVTT - con cabecera\r\n\r\nNOTE una nota\r\n\r\nuno\r\n00:00.000 --> 00:03.500 align:start\r\nhola\r\n\r\n01:02:03.250 --> 01:02:04.000\r\nadiós\r\n",
            ),
        )
    }

    @Test
    fun `malformed, unordered, overlapping, too long or missing subtitles do not pass`() {
        val complete = RenderedVideoCheck.expected(script("completo.json"))
        assertEquals(
            listOf("the subtitles are not valid WebVTT: it does not start with WEBVTT"),
            RenderedVideoCheck.subtitles("00:00.000 --> 00:01.000\nhola\n", complete),
        )
        assertEquals(
            listOf("the subtitles are not valid WebVTT: cue 1 has no text"),
            RenderedVideoCheck.subtitles("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n", complete),
        )
        assertEquals(
            listOf("the subtitles are not valid WebVTT: cue 1 has a malformed timestamp 0:0:1"),
            RenderedVideoCheck.subtitles("WEBVTT\n\n0:0:1 --> 00:00:02.000\nhola\n", complete),
        )
        assertEquals(
            listOf("the subtitles are not valid WebVTT: block 1 has no timing line"),
            RenderedVideoCheck.subtitles("WEBVTT\n\nSTYLE\n::cue { color: red }\n", complete),
        )
        val shuffled = "WEBVTT\n\n00:00:04.000 --> 00:00:06.500\nb\n\n00:00:00.000 --> 00:00:03.500\na\n\n00:00:06.000 --> 00:00:30.000\nc\n"
        val problems = RenderedVideoCheck.subtitles(shuffled, complete)
        assertTrue(problems.contains("subtitle 2 is out of order or overlaps the previous one"), problems.toString())
        assertTrue(problems.contains("subtitle 3 is out of order or overlaps the previous one"), problems.toString())
        assertTrue(problems.contains("subtitle 3 ends after the video (22 s)"), problems.toString())
        assertTrue(problems.contains("subtitle 1 is at 4.0-6.5 s, the script puts it at 0.0-3.5 s"), problems.toString())
        assertEquals(listOf("the subtitles have 0 cues, the script 3"), RenderedVideoCheck.subtitles("WEBVTT\n", complete))
    }
}
