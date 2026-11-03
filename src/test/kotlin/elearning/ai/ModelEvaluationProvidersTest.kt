package elearning.ai

import elearning.LmsApiTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import java.nio.file.Paths

// la comparación de modelos contra los proveedores de verdad (E35.1, RF-IA-09, docs/ai §9): solo con ./gradlew aiEval, a mano,
// porque necesita las claves y cuesta dinero. Ni build, ni check, ni integrationTest la ejecutan (etiqueta ai-eval) y ninguna
// prueba de CI llama a api.anthropic.com ni a api.openai.com (ADR-0033 §11). Compara, en cada tarea de docs/ai/evaluations,
// el modelo del paso y el de referencia de cada proveedor con clave, con el doble determinista como línea base fuera de la
// comparación, y deja run.json, cada caso y report.md en build/ai-eval/<inicio>/
@Tag(ModelEvaluationProvidersTest.TAG)
class ModelEvaluationProvidersTest : LmsApiTest() {
    @Autowired
    private lateinit var settings: Environment

    @Autowired
    private lateinit var modelAnalyzer: ModelSourceAnalyzer

    @Autowired
    private lateinit var deterministicAnalyzer: DeterministicSourceAnalyzer

    @Autowired
    private lateinit var reviewer: ConsistencyReviewer

    @Test
    fun `compare the configured models on the fixed sets`() {
        check(AiProvider.keyed(settings).isNotEmpty()) { "aiEval needs ANTHROPIC_API_KEY or OPENAI_API_KEY in the environment" }
        val efforts = ModelEvaluationRunner.efforts(System.getenv(EFFORTS))
        val wanted =
            System
                .getenv(TASKS)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        val tasks = EvaluationTask.entries.filter { wanted.isEmpty() || it.dir in wanted }
        check(tasks.isNotEmpty()) { "$TASKS names no task: use ${EvaluationTask.entries.map { it.dir }}" }
        val candidates =
            tasks.associateWith { task ->
                val models = ModelEvaluationRunner.configured(settings, task, efforts)
                if (task == EvaluationTask.SOURCE_ANALYSIS) listOf(EvaluationCandidate.DETERMINISTIC) + models else models
            }
        val runner =
            ModelEvaluationRunner(
                Paths.get(System.getProperty("lms.ai.eval.sets", "docs/ai/evaluations")),
                Paths.get(System.getProperty("lms.ai.eval.out", "build/ai-eval")),
                EvaluationMode.PROVIDERS,
                modelAnalyzer,
                deterministicAnalyzer,
                reviewer,
            )

        val run = runner.run(candidates)

        println("aiEval: ${run.results.size} cases, report in ${run.dir.resolve("report.md").toAbsolutePath()}")
    }

    companion object {
        const val TAG = "ai-eval"

        // esfuerzos separados por comas (low, medium, high, default); tareas separadas por comas (vacío: todas)
        const val EFFORTS = "LMS_AI_EVAL_EFFORTS"
        const val TASKS = "LMS_AI_EVAL_TASKS"
    }
}
