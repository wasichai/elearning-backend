package elearning.ai

import com.embabel.agent.openai.getOpenAiReasoningEffort
import com.embabel.agent.test.unit.FakeOperationContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.env.MockEnvironment
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// la comparación de modelos (E35.1, RF-IA-09) sin base de datos ni red: los conjuntos versionados de docs/ai/evaluations, las
// puntuaciones calculadas por código, el doble determinista fuera de la comparación, lo que guarda cada ejecución y el esfuerzo
// de razonamiento que llega a Embabel. La ejecución contra el doble HTTP es ModelEvaluationSmokeTest; contra los proveedores
// reales, ./gradlew aiEval (ModelEvaluationProvidersTest)
class ModelEvaluationTest {
    private val sets: Path = Paths.get("docs/ai/evaluations")
    private val clock = Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC)

    private val sourceSet = AiJobs.MAPPER.readValue(sets.resolve("source-analysis/set.json").toFile(), SourceAnalysisSet::class.java)
    private val reviewSet = AiJobs.MAPPER.readValue(sets.resolve("consistency-review/set.json").toFile(), ConsistencyReviewSet::class.java)

    private val haiku =
        EvaluationCandidate(Generator.MODEL, "anthropic", "claude-haiku-5-5", "extraction", ModelSettings(8000, effort = ReasoningEffort.MEDIUM))
    private val opus = EvaluationCandidate(Generator.MODEL, "anthropic", "claude-opus-5-5", "reference", ModelSettings(8000, effort = ReasoningEffort.MEDIUM))

    @Test
    fun `the sets name their criteria, which are written down, and what they expect exists in their own cases`() {
        for ((set, criteria) in listOf(sourceSet.task to sourceSet.criteria, reviewSet.task to reviewSet.criteria)) {
            assertThat(EvaluationTask.entries.map { it.dir }).contains(set)
            assertThat(Files.readString(sets.resolve(set).resolve("criteria.md"))).contains("`$criteria`")
        }
        assertThat(sourceSet.cases.map { it.id }).doesNotHaveDuplicates().isNotEmpty()
        for (case in sourceSet.cases) {
            val refs = case.sections.map { it.ref }
            assertThat(refs).doesNotHaveDuplicates()
            assertThat(case.expected).isNotEmpty()
            case.expected.forEach { assertThat(refs).describedAs(case.id).contains(it.source) }
        }
        assertThat(reviewSet.cases.map { it.id }).doesNotHaveDuplicates().isNotEmpty()
        for (case in reviewSet.cases) {
            assertThat(ConsistencyReviewCheck.origins(case.draft)).describedAs(case.id).containsAll(case.expected.origins)
            assertThat(case.expected.origins.isEmpty()).isEqualTo(case.expected.verdict == ConsistencyVerdict.APPROVED)
        }
        val scores = EvaluationScores.SOURCE_ANALYSIS + EvaluationScores.CONSISTENCY_REVIEW
        for (score in scores.distinct()) {
            val documented = EvaluationTask.entries.any { Files.readString(sets.resolve(it.dir).resolve("criteria.md")).contains("`$score`") }
            assertThat(documented).describedAs(score).isTrue()
        }
    }

    // la salida del doble determinista sobre el conjunto: todas sus procedencias existen y cubre lo esperado
    @Test
    fun `the source analysis scores come from code, schema, check, provenance and coverage`() {
        val case = sourceSet.cases.first { it.id == "anuncios" }
        val publish = case.sections[0]
        val withdraw = case.sections[1]
        val analysis =
            SourceAnalysis(
                concepts =
                    listOf(
                        SourceConcept("Anuncio retirado", "Deja de verse", withdraw.ref, "Un anuncio retirado deja de verse en el tablón."),
                        SourceConcept("Caducidad", "Inventado", publish.ref, "Un anuncio caduca a los tres días."),
                    ),
                procedures = listOf(SourceProcedure("Publicar", listOf("Abrir el panel"), withdraw.ref, "Un anuncio retirado deja de verse")),
            )
        val checked = SourceAnalysisCheck.checked(analysis, case.material(), null)

        val scores = EvaluationScores.sourceAnalysis(case, CaseOutcome(CaseStatus.ACCEPTED, true, checked.output, checked.validations))

        // dos de tres quedan; el procedimiento esperado cita la sección de retirar, así que solo se cubre el concepto
        assertThat(scores).containsExactlyEntriesOf(
            linkedMapOf(
                EvaluationScores.SCHEMA_VALID to 1.0,
                EvaluationScores.ACCEPTED to 1.0,
                EvaluationScores.PROVENANCE_VALID to 2.0 / 3,
                EvaluationScores.COVERAGE to 0.5,
            ),
        )
        val unparsed = EvaluationScores.sourceAnalysis(case, CaseOutcome(CaseStatus.INVALID_OUTPUT, false, error = "the model output does not match"))
        assertThat(unparsed.values).containsExactly(0.0, 0.0, 0.0, 0.0)
        val failed = EvaluationScores.sourceAnalysis(case, CaseOutcome(CaseStatus.FAILED, false, error = "the AI provider failed (429)"))
        assertThat(failed.values).containsOnlyNulls()
    }

    @Test
    fun `the consistency scores come from code, valid origins, verdict and the expected records found`() {
        val case = reviewSet.cases.first { it.id == "opcion-erronea" }
        val question = case.expected.origins.single()
        val review =
            ConsistencyReview(
                ConsistencyVerdict.CONCERNS,
                "La opción correcta contradice la lección",
                listOf(ConsistencyFinding(question, "Marca Guardar como correcta"), ConsistencyFinding("pregunta:otra", "No existe")),
            )
        val checked = ConsistencyReviewCheck.checked(review, case.draft, "h", null)

        val scores = EvaluationScores.consistencyReview(case, CaseOutcome(CaseStatus.ACCEPTED, true, checked.output, checked.validations))

        assertThat(scores).containsExactlyEntriesOf(
            linkedMapOf(
                EvaluationScores.SCHEMA_VALID to 1.0,
                EvaluationScores.ACCEPTED to 1.0,
                EvaluationScores.ORIGINS_VALID to 0.5,
                EvaluationScores.VERDICT to 1.0,
                EvaluationScores.ORIGIN_RECALL to 1.0,
            ),
        )
        val approved = reviewSet.cases.first { it.id == "coherente" }
        val wrong = ConsistencyReviewCheck.checked(review.copy(findings = emptyList()), approved.draft, "h", null)
        val wrongScores = EvaluationScores.consistencyReview(approved, CaseOutcome(CaseStatus.ACCEPTED, true, wrong.output, wrong.validations))
        assertThat(wrongScores[EvaluationScores.VERDICT]).isEqualTo(0.0)
        assertThat(wrongScores[EvaluationScores.ORIGIN_RECALL]).isNull()
        assertThat(wrongScores[EvaluationScores.ORIGINS_VALID]).isNull()
    }

    // criterio de #290: las ejecuciones del doble determinista no entran en la comparación, aunque se ejecuten y se guarden
    @Test
    fun `the deterministic double runs and is kept, but never enters the comparison`(
        @TempDir out: Path,
    ) {
        val run =
            runner(out, EvaluationMode.SMOKE, model = fixedAnalyzer()).run(
                mapOf(EvaluationTask.SOURCE_ANALYSIS to listOf(EvaluationCandidate.DETERMINISTIC, haiku)),
            )

        val comparison = run.comparisons.single()
        assertThat(comparison.compared.map { it.candidate }).containsExactly(haiku)
        assertThat(comparison.excluded).containsExactly(ExcludedCandidate(EvaluationCandidate.DETERMINISTIC_ID, ModelComparison.DETERMINISTIC_EXCLUDED))
        val deterministic = run.results.filter { it.generator == Generator.DETERMINISTIC }
        assertThat(deterministic).hasSize(sourceSet.cases.size).allSatisfy { assertThat(it.status).isEqualTo(CaseStatus.ACCEPTED) }
        assertThat(deterministic.map { it.scores[EvaluationScores.COVERAGE] }).containsOnly(1.0)
        for (case in sourceSet.cases) assertThat(run.dir.resolve("source-analysis/deterministic/${case.id}.json")).exists()
        assertThat(Files.readString(run.dir.resolve("report.md"))).contains("Fuera de la comparación", "`deterministic`")
        // y la comparación de una lista con solo el doble no tiene a nadie
        assertThat(ModelComparison.of(EvaluationTask.SOURCE_ANALYSIS, "c", listOf(EvaluationCandidate.DETERMINISTIC), deterministic).compared).isEmpty()
    }

    // criterio de #290: cada ejecución conserva modelo, parámetros efectivos (esfuerzo incluido), versiones de instrucciones,
    // salidas, puntuaciones e informe
    @Test
    fun `a run keeps the model, its effective settings, the prompt versions, the outputs, the scores and a report`(
        @TempDir out: Path,
    ) {
        val run =
            runner(out, EvaluationMode.PROVIDERS, model = fixedAnalyzer(), reviewer = reviewer()).run(
                mapOf(EvaluationTask.SOURCE_ANALYSIS to listOf(haiku, opus), EvaluationTask.CONSISTENCY_REVIEW to listOf(haiku.copy(role = "consistency"))),
            )

        assertThat(run.dir).isEqualTo(out.resolve("20261103T100000000Z"))
        val saved = AiJobs.MAPPER.readTree(run.dir.resolve("run.json").toFile())
        assertThat(saved["info"]["mode"].asString()).isEqualTo("PROVIDERS")
        assertThat(saved["info"]["commit"].asString()).isEqualTo(COMMIT)
        assertThat(saved["info"]["clean"].asBoolean()).isTrue()
        assertThat(saved["info"]["sets"]["source-analysis"]["criteria"].asString()).isEqualTo(sourceSet.criteria)
        assertThat(saved["info"]["sets"]["source-analysis"]["setSha256"].asString()).hasSize(64)
        val candidate = saved["candidates"]["source-analysis"][0]
        assertThat(candidate["model"].asString()).isEqualTo("claude-haiku-5-5")
        assertThat(candidate["settings"]["maxTokens"].asInt()).isEqualTo(8000)
        assertThat(candidate["settings"]["effort"].asString()).isEqualTo("MEDIUM")
        assertThat(candidate["settings"]["temperature"].isNull).isTrue()
        val comparison = saved["comparisons"][0]
        assertThat(comparison["promptVersion"].asString()).isEqualTo(SourceAnalysisPrompt.VERSION)
        assertThat(comparison["templateVersion"].asString()).isEqualTo(SourceAnalysisPrompt.TEMPLATE)
        assertThat(comparison["compared"]).hasSize(2)

        val case: JsonNode = AiJobs.MAPPER.readTree(run.dir.resolve("source-analysis/anthropic-claude-opus-5-5-medium/anuncios.json").toFile())
        assertThat(case["status"].asString()).isEqualTo("ACCEPTED")
        assertThat(case["output"]["concepts"]).isNotEmpty()
        assertThat(case["validations"]["discarded"]).isNotNull()
        assertThat(case["scores"][EvaluationScores.COVERAGE].asDouble()).isEqualTo(0.5)
        assertThat(case["usage"]["inputTokens"].asInt()).isEqualTo(100)
        assertThat(case["sources"]).hasSize(2)
        val opinion = AiJobs.MAPPER.readTree(run.dir.resolve("consistency-review/anthropic-claude-haiku-5-5-medium/opcion-erronea.json").toFile())
        assertThat(opinion["output"]["kind"].asString()).isEqualTo(ConsistencyOpinion.KIND)
        assertThat(opinion["scores"][EvaluationScores.VERDICT].asDouble()).isEqualTo(1.0)

        val report = Files.readString(run.dir.resolve("report.md"))
        assertThat(
            report,
        ).contains("proveedores reales", "source-analysis-criteria@1", SourceAnalysisPrompt.VERSION, "claude-opus-5-5", "medium", "coverage", "0.50")
        assertThat(report).doesNotContain(EvaluationReport.SMOKE_NOTICE)
    }

    // criterio de #290: contra el doble HTTP, prueba de humo sin cifras
    @Test
    fun `a smoke report says what ran and how each case ended, without any figure`(
        @TempDir out: Path,
    ) {
        val run = runner(out, EvaluationMode.SMOKE, model = fixedAnalyzer()).run(mapOf(EvaluationTask.SOURCE_ANALYSIS to listOf(haiku)))

        val report = Files.readString(run.dir.resolve("report.md"))
        assertThat(report).contains(EvaluationReport.SMOKE_NOTICE, "claude-haiku-5-5", "anuncios", "ACCEPTED")
        for (score in EvaluationScores.SOURCE_ANALYSIS) assertThat(report).doesNotContain(score)
        assertThat(report).doesNotContain("0.50", "Tokens")
    }

    // criterio de #290: entradas y criterios versionados antes de ejecutar
    @Test
    fun `against real providers the sets must be committed, so that the report can be reproduced from its commit`(
        @TempDir out: Path,
    ) {
        val tasks = mapOf(EvaluationTask.SOURCE_ANALYSIS to listOf(haiku))

        fun runner(git: Pair<String, Boolean>?) =
            ModelEvaluationRunner(sets, out, EvaluationMode.PROVIDERS, fixedAnalyzer(), DeterministicSourceAnalyzer(), reviewer(), clock) {
                git
            }

        assertThatThrownBy { runner(COMMIT to false).run(tasks) }.hasMessageContaining("uncommitted changes")
        assertThatThrownBy { runner(null).run(tasks) }.hasMessageContaining("needs git")
        assertThat(out).isEmptyDirectory()
        // la prueba de humo no lo exige, pero guarda lo que sepa
        val smoke =
            ModelEvaluationRunner(sets, out, EvaluationMode.SMOKE, fixedAnalyzer(), DeterministicSourceAnalyzer(), reviewer(), clock) { COMMIT to false }
        assertThat(smoke.run(tasks).info.clean).isFalse()
        // y sin inyectar nada, el estado sale del git de verdad (este repositorio)
        assertThat(ModelEvaluationRunner.gitState(sets)?.first).hasSize(40)
    }

    @Test
    fun `the candidates are the step model and the reference of each provider with a key, once each, with every effort asked`() {
        val environment =
            MockEnvironment()
                .withProperty("ANTHROPIC_API_KEY", "sk-ant-not-real")
                .withProperty("lms.ai.models.anthropic.extraction", "claude-haiku-5-5")
                .withProperty("lms.ai.models.anthropic.reference", "claude-opus-5-5")
                .withProperty("lms.ai.models.anthropic.consistency", "claude-opus-5-5")
                .withProperty("lms.ai.models.openai.extraction", "gpt-6-luna")
                .withProperty("lms.ai.max-tokens.extraction", "8000")
                .withProperty("lms.ai.max-tokens.consistency", "2000")

        val extraction = ModelEvaluationRunner.configured(environment, EvaluationTask.SOURCE_ANALYSIS, listOf(ReasoningEffort.LOW, null))
        val consistency = ModelEvaluationRunner.configured(environment, EvaluationTask.CONSISTENCY_REVIEW, listOf(ReasoningEffort.MEDIUM))

        // OpenAI no tiene clave: no participa. el de referencia y el de la opinión son el mismo: una vez
        assertThat(extraction.map { it.id }).containsExactly(
            "anthropic-claude-haiku-5-5-low",
            "anthropic-claude-haiku-5-5-provider-default",
            "anthropic-claude-opus-5-5-low",
            "anthropic-claude-opus-5-5-provider-default",
        )
        assertThat(extraction.map { it.settings!!.maxTokens }).containsOnly(8000)
        assertThat(extraction.map { it.settings!!.temperature }).containsOnlyNulls()
        assertThat(consistency.map { it.id to it.role }).containsExactly("anthropic-claude-opus-5-5-medium" to "consistency")
        assertThat(
            ModelEvaluationRunner.configured(MockEnvironment().withProperty("lms.ai.max-tokens.extraction", "1"), EvaluationTask.SOURCE_ANALYSIS, listOf(null)),
        ).isEmpty()
    }

    @Test
    fun `the efforts come from LMS_AI_EVAL_EFFORTS and are medium when not set`() {
        assertThat(ModelEvaluationRunner.efforts(null)).containsExactly(ReasoningEffort.MEDIUM)
        assertThat(ModelEvaluationRunner.efforts(" ")).containsExactly(ReasoningEffort.MEDIUM)
        assertThat(ModelEvaluationRunner.efforts("low, HIGH,default,low")).containsExactly(ReasoningEffort.LOW, ReasoningEffort.HIGH, null)
        assertThatThrownBy { ModelEvaluationRunner.efforts("max") }.hasMessageContaining("LMS_AI_EVAL_EFFORTS")
    }

    // Claude 5 no admite temperatura: el esfuerzo es lo que llega. Anthropic lo saca del presupuesto de pensamiento y OpenAI
    // de su extensión; sin esfuerzo no se envía ninguno de los dos
    @Test
    fun `the effort reaches embabel as the anthropic thinking budget and the openai reasoning effort, and nothing without it`() {
        val calls = mutableListOf<FakeOperationContext>()
        val modelCalls =
            ModelCalls({ FakeOperationContext().also { it.expectResponse("hola") }.also(calls::add) }, AiStatus(AiProvider.ANTHROPIC, emptyMap()))

        modelCalls.createObject("claude-opus-5-5", ModelSettings(1000, effort = ReasoningEffort.MEDIUM), "hola", String::class.java)
        modelCalls.createObject("claude-opus-5-5", ModelSettings(1000), "hola", String::class.java)

        val (withEffort, without) =
            calls.map {
                it.llmInvocations
                    .single()
                    .interaction.llm
            }
        assertThat(withEffort.thinking?.tokenBudget).isEqualTo(ReasoningEffort.MEDIUM.anthropicBudget)
        assertThat(withEffort.getOpenAiReasoningEffort()).isEqualTo("medium")
        assertThat(withEffort.temperature).isNull()
        assertThat(without.thinking).isNull()
        assertThat(without.getOpenAiReasoningEffort()).isNull()
        // cada esfuerzo cae en su tramo de Embabel 1.5.4 (< 1024 low, < 4096 medium, < 16384 high)
        assertThat(ReasoningEffort.LOW.anthropicBudget).isLessThan(1024)
        assertThat(ReasoningEffort.MEDIUM.anthropicBudget).isBetween(1024, 4095)
        assertThat(ReasoningEffort.HIGH.anthropicBudget).isBetween(4096, 16383)
    }

    // un análisis fijo: en cada caso, un concepto con procedencia en la última sección y otro inventado
    private fun fixedAnalyzer(): SourceAnalyzer =
        object : SourceAnalyzer {
            override fun analyze(
                prompt: AiPrompt,
                material: SourceMaterial,
            ): ModelReply<SourceAnalysis> {
                val withdraw = material.sections.last()
                return ModelReply(
                    SourceAnalysis(
                        concepts =
                            listOf(
                                SourceConcept("Retirado", "Deja de verse", withdraw.ref, withdraw.text.take(40)),
                                SourceConcept("Caducidad", "Inventado", withdraw.ref, "Un anuncio caduca a los tres días."),
                            ),
                        procedures = emptyList(),
                    ),
                    TokenUsage(100, 20),
                )
            }
        }

    // la opinión de un modelo, con el FakeOperationContext de Embabel: señala la primera pregunta de cada borrador
    private fun reviewer(): ConsistencyReviewer {
        val contexts = { FakeOperationContext() }
        return ConsistencyReviewer(
            ModelCalls(
                {
                    contexts().also { context ->
                        context.expectResponse(
                            ConsistencyReview(
                                ConsistencyVerdict.CONCERNS,
                                "Hay dudas",
                                listOf(ConsistencyFinding("pregunta:eval-opcion-q1", "La opción no es la de la lección")),
                            ),
                        )
                    }
                },
                AiStatus(AiProvider.ANTHROPIC, emptyMap()),
            ),
        )
    }

    private fun runner(
        out: Path,
        mode: EvaluationMode,
        model: SourceAnalyzer,
        reviewer: ConsistencyReviewer = reviewer(),
    ) = ModelEvaluationRunner(sets, out, mode, model, DeterministicSourceAnalyzer(), reviewer, clock) { COMMIT to true }

    private companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
