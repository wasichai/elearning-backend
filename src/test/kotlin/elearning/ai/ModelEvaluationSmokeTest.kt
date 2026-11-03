package elearning.ai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.test.context.TestPropertySource
import java.nio.file.Files
import java.nio.file.Paths

// la comparación de modelos (E35.1, RF-IA-09) de punta a punta en CI, contra el doble HTTP de los dos proveedores: la prueba
// de humo de ./gradlew aiEval. Los mismos beans y los mismos participantes que en una ejecución real (los modelos de
// application.yml de cada proveedor con clave, el del paso y el de referencia) y el esfuerzo pedido llega en cada petición;
// las respuestas son las fijas del doble, así que el informe no lleva cifras. Las anotaciones son las de
// SourceAnalysisProviderApiTest a propósito: comparten contexto de Spring
@Import(SourceAnalysisFixtures.FakeSource::class)
@TestPropertySource(properties = ["lms.jobs.scheduling=false", "lms.jobs.backoff=0s", "lms.importer.allowed-sources=acme/manuales"])
class ModelEvaluationSmokeTest : ProviderDoubleApiTest() {
    @Autowired
    private lateinit var settings: Environment

    @Autowired
    private lateinit var modelAnalyzer: ModelSourceAnalyzer

    @Autowired
    private lateinit var deterministicAnalyzer: DeterministicSourceAnalyzer

    @Autowired
    private lateinit var reviewer: ConsistencyReviewer

    private fun runner(task: EvaluationTask) =
        ModelEvaluationRunner(
            Paths.get(System.getProperty(SETS, "docs/ai/evaluations")),
            Paths.get("build/ai-eval-smoke").resolve(task.dir),
            EvaluationMode.SMOKE,
            modelAnalyzer,
            deterministicAnalyzer,
            reviewer,
        )

    @Test
    fun `the source analysis runs on its set against every configured model, with the effort asked and no temperature`() {
        ProviderDouble.anthropicReplies("messages-source-analysis.json")
        ProviderDouble.openAiReplies("responses-source-analysis.json")
        val models = ModelEvaluationRunner.configured(settings, EvaluationTask.SOURCE_ANALYSIS, listOf(ReasoningEffort.MEDIUM))

        val run = runner(EvaluationTask.SOURCE_ANALYSIS).run(mapOf(EvaluationTask.SOURCE_ANALYSIS to listOf(EvaluationCandidate.DETERMINISTIC) + models))

        // los de application.yml: extracción y referencia de cada proveedor (ADR-0033 §3)
        assertThat(models.map { it.model }).containsExactly("claude-haiku-5-5", "claude-opus-5-5", "gpt-6-luna", "gpt-6-astra")
        val cases = run.results.map { it.case }.distinct()
        assertThat(run.results).hasSize((models.size + 1) * cases.size)
        // la respuesta fija cita el caso anuncios: allí se acepta; en los demás no cita nada que exista
        val anuncios = run.results.filter { it.case == "anuncios" && it.generator == Generator.MODEL }
        assertThat(anuncios).allSatisfy {
            assertThat(it.status).isEqualTo(CaseStatus.ACCEPTED)
            assertThat(it.scores[EvaluationScores.PROVENANCE_VALID]).isEqualTo(0.4)
            assertThat(it.usage).isNotNull()
        }
        assertThat(run.results.filter { it.case != "anuncios" && it.generator == Generator.MODEL }).allSatisfy {
            assertThat(it.status).isEqualTo(CaseStatus.INVALID_OUTPUT)
        }
        assertThat(
            run.comparisons
                .single()
                .compared
                .map { it.candidate },
        ).isEqualTo(models)
        assertThat(
            run.comparisons
                .single()
                .excluded
                .map { it.candidate },
        ).containsExactly(EvaluationCandidate.DETERMINISTIC_ID)

        // Claude 5 no admite temperatura: lo que llega es el esfuerzo, como pensamiento adaptativo y output_config.effort
        val anthropic = ProviderDouble.anthropicRequests()
        assertThat(anthropic).hasSize(2 * cases.size)
        assertThat(anthropic.map { it["model"].asString() }.toSet()).containsExactlyInAnyOrder("claude-haiku-5-5", "claude-opus-5-5")
        assertThat(anthropic).allSatisfy {
            assertThat(it["output_config"]["effort"].asString()).isEqualTo("medium")
            assertThat(it["thinking"]["type"].asString()).isEqualTo("adaptive")
            assertThat(it.has("temperature")).isFalse()
            assertThat(it["max_tokens"].asInt()).isEqualTo(models.first().settings!!.maxTokens)
        }
        val openAi = ProviderDouble.openAiRequests()
        assertThat(openAi).hasSize(2 * cases.size)
        assertThat(openAi).allSatisfy {
            assertThat(it["reasoning"]["effort"].asString()).isEqualTo("medium")
            assertThat(it.has("temperature")).isFalse()
        }

        assertSaved(run, EvaluationTask.SOURCE_ANALYSIS)
    }

    @Test
    fun `the consistency review runs on its set against every configured model and keeps everything, without figures`() {
        ProviderDouble.anthropicReplies("messages-consistency-concerns.json")
        ProviderDouble.openAiReplies("responses-consistency-concerns.json")
        val models = ModelEvaluationRunner.configured(settings, EvaluationTask.CONSISTENCY_REVIEW, listOf(ReasoningEffort.LOW))

        val run = runner(EvaluationTask.CONSISTENCY_REVIEW).run(mapOf(EvaluationTask.CONSISTENCY_REVIEW to models))

        assertThat(models.map { it.model }).containsExactly("claude-haiku-5-5", "claude-opus-5-5", "gpt-6-luna", "gpt-6-astra")
        assertThat(run.results).allSatisfy {
            assertThat(it.status).isEqualTo(CaseStatus.ACCEPTED)
            // la respuesta fija dice CONCERNS y cita una pregunta que no existe: se descarta
            assertThat(it.scores[EvaluationScores.ORIGINS_VALID]).isEqualTo(0.5)
        }
        assertThat(ProviderDouble.anthropicRequests()).allSatisfy { assertThat(it["output_config"]["effort"].asString()).isEqualTo("low") }
        assertThat(ProviderDouble.openAiRequests()).allSatisfy { assertThat(it["reasoning"]["effort"].asString()).isEqualTo("low") }

        assertSaved(run, EvaluationTask.CONSISTENCY_REVIEW)
    }

    private fun assertSaved(
        run: ModelEvaluationRunner.Run,
        task: EvaluationTask,
    ) {
        val saved = AiJobs.MAPPER.readTree(run.dir.resolve("run.json").toFile())
        assertThat(saved["info"]["mode"].asString()).isEqualTo("SMOKE")
        assertThat(saved["comparisons"][0]["promptVersion"].asString()).isEqualTo(task.promptVersion)
        val models = saved["candidates"][task.dir].filter { it["generator"].asString() == "MODEL" }
        assertThat(models.map { it["settings"]["effort"].asString() }).isNotEmpty().doesNotContain("null")
        assertThat(models.map { it["settings"]["temperature"] }).allSatisfy { assertThat(it.isNull).isTrue() }
        for (result in run.results) {
            val case =
                AiJobs.MAPPER.readTree(
                    run.dir
                        .resolve(task.dir)
                        .resolve(result.candidate)
                        .resolve("${result.case}.json")
                        .toFile(),
                )
            assertThat(case["scores"].propertyNames().asSequence().toList()).isEqualTo(EvaluationScores.names(task))
            assertThat(case.has("output")).isTrue()
        }
        val report = Files.readString(run.dir.resolve("report.md"))
        assertThat(report).contains(EvaluationReport.SMOKE_NOTICE, "claude-opus-5-5", "gpt-6-astra")
        for (score in EvaluationScores.names(task)) assertThat(report).doesNotContain(score)
        // ninguna clave en lo que se guarda (RNF-SEG-09)
        Files.walk(run.dir).use { files ->
            files.filter { Files.isRegularFile(it) }.forEach {
                assertThat(Files.readString(it)).doesNotContain(ProviderDouble.ANTHROPIC_KEY, ProviderDouble.OPENAI_KEY)
            }
        }
    }

    private companion object {
        const val SETS = "lms.ai.eval.sets"
    }
}
