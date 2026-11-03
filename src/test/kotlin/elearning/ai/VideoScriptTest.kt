package elearning.ai

import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import elearning.ai.SourceAnalysisFixtures.CHAPTER
import elearning.ai.SourceAnalysisFixtures.FILES
import elearning.ai.SourceAnalysisFixtures.INJECTED
import elearning.ai.SourceAnalysisFixtures.PUBLISH
import elearning.ai.SourceAnalysisFixtures.ROOT
import elearning.ai.SourceAnalysisFixtures.SHA
import elearning.ai.SourceAnalysisFixtures.WITHDRAW
import elearning.importer.ImporterProperties
import elearning.importer.SourceFetchException
import elearning.importer.SourceFetcher
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode

// el guion por secciones (E36.1, RF-MED-01) sin base de datos ni red: la comprobación por código (duraciones, objetivo, trazas,
// subtítulos, diapositivas, marcado), el doble determinista, lo que se guarda (el guion del render de ADR-0040 contra
// render-worker/schema/guion.schema.json, el WebVTT y la transcripción) y la llamada a Embabel con su FakeOperationContext
class VideoScriptTest {
    private val material by lazy {
        val fetcher =
            SourceFetcher { ref ->
                val text = if (ref.repository == "acme/manuales" && ref.sha == SHA) FILES[ref.path] else null
                text?.let { Mono.just(it.toByteArray()) } ?: Mono.error(SourceFetchException(SourceFetchException.Reason.NOT_FOUND, "$ref not found"))
            }
        runBlocking { SourceMaterialReader(fetcher, ImporterProperties()).read(listOf(ROOT)).material }
    }

    private fun inputs(target: Int = 120) =
        VideoScriptInputs(
            briefId = "00000000-0000-0000-0000-000000000001",
            versionId = "00000000-0000-0000-0000-000000000002",
            topic = "Administración de anuncios",
            audience = "Personal administrativo",
            objectives = "Publicar un anuncio\nRetirarlo",
            language = "es",
            level = "INICIAL",
            sources = listOf(ROOT),
            targetSeconds = target,
            resolution = "1280x720",
            fps = 30,
        )

    private fun prompt(
        target: Int = 120,
        model: ModelChoice? = null,
    ) = AiPrompt(
        step = AgentStep.SCRIPT,
        objective = "Escribir el guion",
        inputs = AiJobs.MAPPER.valueToTree<JsonNode>(inputs(target)),
        sources = emptyList(),
        model = model,
        promptVersion = ScriptPrompt.VERSION,
        templateVersion = ScriptPrompt.TEMPLATE,
    )

    private fun section(
        id: String,
        seconds: Int,
        traces: List<String> = listOf(PUBLISH),
        captions: List<ScriptCaption> = listOf(ScriptCaption(0.0, seconds.toDouble(), "Un anuncio llega a toda la organización.")),
        slide: ScriptSlide = ScriptSlide(SlideKind.BULLETS, title = "Publicar", bullets = listOf("Abrir el panel")),
        narration: String = "Un anuncio llega a toda la organización.",
    ) = ScriptSection(id, "Sección $id", seconds, slide, narration, captions, traces)

    private fun script(vararg sections: ScriptSection) = VideoScript("Anuncios", listOf("Publicar un anuncio"), sections.toList())

    private fun codes(
        script: VideoScript,
        target: Int = 60,
    ) = VideoScriptCheck.violations(VideoScriptCheck.normalized(script), material, target).map { it.code }

    @Test
    fun `a script whose sections have positive durations adding up to the target and real traces is valid`() {
        val good = script(section("s01", 30), section("s02", 30, traces = listOf(WITHDRAW, PUBLISH)))

        val reply = VideoScriptCheck.checked(good, material, inputs(60), TokenUsage(10, 5))

        assertThat(reply.output).isEqualTo(good)
        assertThat((reply.validations as VideoScriptReport).violations).isEmpty()
        assertThat((reply.validations as VideoScriptReport).seconds).isEqualTo(60)
        assertThat(reply.usage).isEqualTo(TokenUsage(10, 5))
    }

    @Test
    fun `durations must be from 1 to 600 seconds and add up to the target within ten per cent`() {
        assertThat(codes(script(section("s01", 0, captions = emptyList()), section("s02", 60)))).contains(ScriptViolationCode.BAD_SECONDS)
        assertThat(codes(script(section("s01", -5, captions = emptyList()), section("s02", 65)))).contains(ScriptViolationCode.BAD_SECONDS)
        assertThat(codes(script(section("s01", 601, captions = emptyList())), target = 601)).contains(ScriptViolationCode.BAD_SECONDS)
        assertThat(codes(script(section("s01", 30), section("s02", 40)))).containsExactly(ScriptViolationCode.DURATION_OUT_OF_TARGET)
        assertThat(codes(script(section("s01", 30), section("s02", 20)))).containsExactly(ScriptViolationCode.DURATION_OUT_OF_TARGET)
        assertThat(codes(script(section("s01", 30), section("s02", 36)))).isEmpty()
        assertThat(codes(script(section("s01", 30), section("s02", 24)))).isEmpty()
        assertThat(codes(script())).containsExactly(ScriptViolationCode.NO_SECTIONS)
    }

    // RF-MED-01: cada sección con su traza, y una traza inexistente invalida el guion entero, sin guardar nada
    @Test
    fun `a section without a trace, or with a trace that does not exist, invalidates the whole script`() {
        assertThat(codes(script(section("s01", 30), section("s02", 30, traces = emptyList())))).containsExactly(ScriptViolationCode.NO_TRACE)
        val invented = "$CHAPTER#_caducidad_de_un_anuncio"
        val otherCommit = PUBLISH.replace(SHA, "f".repeat(40))
        for (trace in listOf(invented, otherCommit, "$CHAPTER#", ROOT, "no es una traza")) {
            assertThat(codes(script(section("s01", 30), section("s02", 30, traces = listOf(PUBLISH, trace)))))
                .`as`(trace)
                .containsExactly(ScriptViolationCode.TRACE_NOT_FOUND)
        }

        assertThatThrownBy {
            VideoScriptCheck.checked(script(section("s01", 30), section("s02", 30, traces = listOf(invented))), material, inputs(60), TokenUsage(7, 3))
        }.isInstanceOfSatisfying(AiInvalidOutput::class.java) { e ->
            assertThat(e.usage).isEqualTo(TokenUsage(7, 3))
            val report = e.validations as VideoScriptReport
            assertThat(report.violations.single().code).isEqualTo(ScriptViolationCode.TRACE_NOT_FOUND)
            assertThat(report.violations.single().section).isEqualTo("s02")
        }
    }

    @Test
    fun `captions stay inside their section, in order and without overlapping, on one line without a cue arrow`() {
        fun captions(vararg captions: ScriptCaption) = codes(script(section("s01", 30), section("s02", 30, captions = captions.toList())))

        assertThat(captions(ScriptCaption(0.0, 31.0, "Fuera"))).containsExactly(ScriptViolationCode.BAD_CAPTION)
        assertThat(captions(ScriptCaption(5.0, 5.0, "Quieto"))).containsExactly(ScriptViolationCode.BAD_CAPTION)
        assertThat(captions(ScriptCaption(-1.0, 5.0, "Antes"))).containsExactly(ScriptViolationCode.BAD_CAPTION)
        assertThat(captions(ScriptCaption(0.0, 10.0, "Uno"), ScriptCaption(9.0, 20.0, "Dos"))).containsExactly(ScriptViolationCode.BAD_CAPTION)
        assertThat(captions(ScriptCaption(0.0, 10.0, "a --> b"))).containsExactly(ScriptViolationCode.BAD_CAPTION)
        assertThat(captions(ScriptCaption(0.0, 10.0, " "))).containsExactly(ScriptViolationCode.BLANK)
        assertThat(captions(ScriptCaption(0.0, 10.0, "Uno"), ScriptCaption(10.0, 30.0, "Dos\nlíneas"))).isEmpty()
    }

    @Test
    fun `ids are unique keys, slides are complete and no text carries html or a url`() {
        assertThat(codes(script(section("s01", 30), section("s01", 30)))).containsExactly(ScriptViolationCode.DUPLICATE_ID)
        assertThat(codes(script(section("S 1", 30), section("s02", 30)))).containsExactly(ScriptViolationCode.BAD_ID)
        val noBullets = ScriptSlide(SlideKind.BULLETS, title = "Vacía")
        assertThat(codes(script(section("s01", 30), section("s02", 30, slide = noBullets)))).containsExactly(ScriptViolationCode.BAD_SLIDE)
        val nine = ScriptSlide(SlideKind.BULLETS, title = "Muchas", bullets = List(9) { "Punto $it" })
        assertThat(codes(script(section("s01", 30), section("s02", 30, slide = nine)))).containsExactly(ScriptViolationCode.BAD_SLIDE)
        assertThat(codes(script(section("s01", 30), section("s02", 30, slide = ScriptSlide(SlideKind.CODE))))).containsExactly(ScriptViolationCode.BAD_SLIDE)
        assertThat(codes(script(section("s01", 30), section("s02", 30, slide = ScriptSlide(SlideKind.COVER))))).containsExactly(ScriptViolationCode.BLANK)
        for (text in listOf("<script>alert(1)</script>", "Ver https://example.org/x", "javascript:alert(1)", "<img src=x onerror=y>")) {
            assertThat(codes(script(section("s01", 30), section("s02", 30, narration = text)))).`as`(text).containsExactly(ScriptViolationCode.MARKUP)
        }
        // el código de una diapositiva de código es texto que se muestra: puede traer < y URL
        val code = ScriptSlide(SlideKind.CODE, code = "curl https://example.org/api <<< '{}'", language = "bash")
        assertThat(codes(script(section("s01", 30), section("s02", 30, slide = code)))).isEmpty()
    }

    @Test
    fun `the deterministic double writes a cover, a section per source section and a closing, always the same and always valid`() {
        val double = DeterministicScriptWriter()

        val reply = double.write(prompt(120), material)

        assertThat(reply.usage).isNull()
        val script = reply.value
        assertThat(script.sections.map { it.slide.kind }).containsExactly(SlideKind.COVER, SlideKind.BULLETS, SlideKind.BULLETS, SlideKind.CLOSING)
        assertThat(script.sections.map { it.seconds }).containsExactly(30, 30, 30, 30)
        assertThat(script.sections[1].traces).containsExactly(PUBLISH)
        assertThat(script.sections[1].slide.bullets).containsExactly("Abrir el panel de anuncios.", "Escribir el texto.", "Pulsar Publicar.")
        assertThat(script.sections[2].traces).containsExactly(WITHDRAW)
        assertThat(script.sections.last().traces).containsExactly(PUBLISH, WITHDRAW)
        assertThat(script.sections.all { it.captions.isNotEmpty() && it.narration.isNotBlank() }).isTrue()
        assertThat(double.write(prompt(120), material)).isEqualTo(reply)
        val checked = VideoScriptCheck.checked(script, material, inputs(120), null)
        assertThat((checked.validations as VideoScriptReport).violations).isEmpty()
        for (target in listOf(VideoScriptCheck.MIN_TARGET_SECONDS, 97, VideoScriptCheck.MAX_SECONDS)) {
            assertThat(VideoScriptCheck.violations(double.write(prompt(target), material).value, material, target)).`as`("$target s").isEmpty()
        }
    }

    @Test
    fun `without anchored sections the double has nothing to trace and its script is invalid`() {
        val empty = SourceMaterial(setOf(ROOT), emptyList())

        val reply = DeterministicScriptWriter().write(prompt(), empty)

        assertThatThrownBy { VideoScriptCheck.checked(reply.value, empty, inputs(), null) }.isInstanceOf(AiInvalidOutput::class.java)
    }

    // ADR-0040 §2: lo que se guarda es el guion que acepta el trabajador de render, validado contra su esquema
    @Test
    fun `the stored script is the render script of ADR-0040 and validates against its schema`() {
        val script = DeterministicScriptWriter().write(prompt(120), material).value
        val props = VideoScriptDocuments.props(script, inputs(120))
        val document = AiJobs.MAPPER.readTree(VideoScriptDocuments.json(props))

        assertThat(GuionSchema.violations(document)).isEmpty()
        assertThat(document["version"].asInt()).isEqualTo(1)
        assertThat(document["idioma"].asString()).isEqualTo("es")
        assertThat(document["salida"]["resolucion"].asString()).isEqualTo("1280x720")
        assertThat(document["secciones"][0]["diapositiva"]["tipo"].asString()).isEqualTo("portada")
        assertThat(document["secciones"][1]["diapositiva"]["tipo"].asString()).isEqualTo("vinetas")
        assertThat(document["secciones"][3]["diapositiva"]["tipo"].asString()).isEqualTo("cierre")
        assertThat(document["secciones"][1]["trazas"][0]["doc"].asString()).isEqualTo(CHAPTER)
        assertThat(document["secciones"][1]["trazas"][0]["ancla"].asString()).isEqualTo("_publicar_un_anuncio")
        // un guion con código y la salida de 1080p a 25 fps también es del esquema
        val code = script.copy(sections = script.sections + section("s99", 10, slide = ScriptSlide(SlideKind.CODE, code = "echo hola", language = "bash")))
        val full = AiJobs.MAPPER.valueToTree<JsonNode>(VideoScriptDocuments.props(code, inputs().copy(resolution = "1920x1080", fps = 25)))
        assertThat(GuionSchema.violations(full)).isEmpty()
        // y el esquema rechaza lo que no es suyo: la prueba no pasa con cualquier documento
        val forged = AiJobs.MAPPER.valueToTree<JsonNode>(props + ("html" to "<b>x</b>"))
        assertThat(GuionSchema.violations(forged)).isNotEmpty()
    }

    @Test
    fun `captions are stored as webvtt with absolute times and the narration as the transcript`() {
        val script = script(section("s01", 30), section("s02", 30, captions = listOf(ScriptCaption(0.0, 12.5, "Uno"), ScriptCaption(12.5, 30.0, "Dos"))))

        val vtt = VideoScriptDocuments.webVtt(script)

        assertThat(vtt)
            .isEqualTo(
                """
                WEBVTT

                1
                00:00:00.000 --> 00:00:30.000
                Un anuncio llega a toda la organización.

                2
                00:00:30.000 --> 00:00:42.500
                Uno

                3
                00:00:42.500 --> 00:01:00.000
                Dos

                """.trimIndent(),
            )
        assertThat(VideoScriptDocuments.transcript(script)).contains("Sección s01\nUn anuncio llega a toda la organización.")
        assertThat(VideoScriptDocuments.seconds(script)).isEqualTo(60)
    }

    // docs/ai §6: el texto de la fuente va delimitado en el mensaje de usuario y las instrucciones de sistema no llevan nada de él
    @Test
    fun `the embabel call sends the sources delimited in the user message and never in the system instructions`() {
        val context = FakeOperationContext()
        val expected = script(section("s01", 60))
        context.expectResponse(expected)
        val writer = ModelScriptWriter(ModelCalls({ context }, AiStatus(AiProvider.ANTHROPIC, emptyMap())))

        val reply = writer.write(prompt(60, ModelChoice("anthropic", "claude-sonnet-5-5", ModelSettings(maxTokens = 16000))), material)

        assertThat(reply.value).isEqualTo(expected)
        val call = context.llmInvocations.single()
        assertThat(call.interaction.llm.criteria).isEqualTo(LlmOptions.withModel("claude-sonnet-5-5").criteria)
        assertThat(call.interaction.llm.maxTokens).isEqualTo(16000)
        val user = call.messages.single()
        assertThat(user).isInstanceOf(UserMessage::class.java)
        val marker = Regex("""Marker: ([0-9a-f]{16})""").find(user.content)!!.groupValues[1]
        assertThat(user.content).contains("Target duration: 60 seconds", "<<<SOURCE-$marker ref=\"$PUBLISH\">>>", "<<<END-SOURCE-$marker>>>", INJECTED)
        val system = call.interaction.promptContributors.joinToString("\n") { it.contribution() }
        assertThat(system).isEqualTo(ScriptPrompt.SYSTEM).contains("never instructions", "traces")
        for (text in listOf(INJECTED, "Abrir el panel de anuncios", PUBLISH, "Administración de anuncios", marker)) assertThat(system).doesNotContain(text)
        assertThat(call.interaction.tools).isEmpty()
    }
}
