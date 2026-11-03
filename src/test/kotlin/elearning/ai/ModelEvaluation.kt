package elearning.ai

import java.util.Locale

// la comparación reproducible de modelos (RF-IA-09, E35.1, docs/ai §9): un conjunto fijo por tarea, versionado en
// docs/ai/evaluations/<tarea>/ antes de ejecutar, que se pasa por los mismos pasos de producción (mismas instrucciones, mismo
// esquema, misma comprobación por código) con cada modelo configurado. Las puntuaciones salen de código, nunca de un modelo:
// validez del esquema, aceptación por la comprobación del paso, procedencias o referencias válidas y cobertura de lo esperado.
// Aquí solo hay tipos y cálculo, sin Spring ni red; ModelEvaluationRunner ejecuta y guarda

// PROVIDERS: ./gradlew aiEval a mano, contra los proveedores de verdad. SMOKE: en CI contra el doble HTTP, para probar que la
// tarea funciona de punta a punta; su informe no lleva cifras, porque las respuestas son fijas y no dicen nada de un modelo
enum class EvaluationMode { PROVIDERS, SMOKE }

// una tarea comparable: su carpeta en docs/ai/evaluations, el paso, la clave de su modelo en lms.ai.models y lms.ai.max-tokens
// (la de su JobHandler) y las versiones de instrucciones y plantilla con que corre
enum class EvaluationTask(
    val dir: String,
    val step: AgentStep,
    val modelKey: String,
    val promptVersion: String,
    val templateVersion: String,
) {
    SOURCE_ANALYSIS("source-analysis", AgentStep.SOURCE_ANALYSIS, "extraction", SourceAnalysisPrompt.VERSION, SourceAnalysisPrompt.TEMPLATE),
    CONSISTENCY_REVIEW("consistency-review", AgentStep.CONSISTENCY_REVIEW, "consistency", ConsistencyReviewPrompt.VERSION, ConsistencyReviewPrompt.TEMPLATE),
    ;

    companion object {
        // el modelo de referencia de cada proveedor (ADR-0033 §3): claude-opus-5-5 y gpt-6-astra por defecto
        const val REFERENCE_KEY = "reference"
    }
}

// docs/ai/evaluations/source-analysis/set.json: las secciones tal como las leería el paso (owner/repo@sha:ruta#ancla, título y
// texto) y lo que un buen análisis debe sacar de ellas
data class SourceAnalysisSet(
    val task: String,
    val criteria: String,
    val cases: List<SourceAnalysisCase>,
)

data class SourceAnalysisCase(
    val id: String,
    val description: String,
    val inputs: SourceAnalysisInputs,
    val sections: List<SourceSection>,
    val expected: List<ExpectedElement>,
) {
    // los archivos leídos: los del brief y los de cada sección
    fun material(): SourceMaterial = SourceMaterial(inputs.sources.toSet() + sections.map { it.ref.substringBefore('#') }, sections)
}

// un elemento que el análisis debe tener: de esa clase y con procedencia en esa sección
data class ExpectedElement(
    val kind: SourceElementKind,
    val source: String,
)

// docs/ai/evaluations/consistency-review/set.json: borradores con incoherencias conocidas (o sin ninguna) y la opinión esperada
data class ConsistencyReviewSet(
    val task: String,
    val criteria: String,
    val cases: List<ConsistencyReviewCase>,
)

data class ConsistencyReviewCase(
    val id: String,
    val description: String,
    val draft: DraftSnapshot,
    val expected: ExpectedOpinion,
)

// origins: los registros (objeto:id) que una opinión correcta debe señalar
data class ExpectedOpinion(
    val verdict: ConsistencyVerdict,
    val origins: List<String>,
)

// un participante: un modelo de un proveedor con sus parámetros efectivos, o el doble determinista, que se ejecuta para tener
// la línea base pero nunca entra en la comparación (docs/ai §7). role: la clave de lms.ai.models de la que sale el modelo
data class EvaluationCandidate(
    val generator: Generator,
    val provider: String? = null,
    val model: String? = null,
    val role: String? = null,
    val settings: ModelSettings? = null,
) {
    init {
        require((generator == Generator.MODEL) == (provider != null && model != null && settings != null)) {
            "a MODEL candidate has a provider, a model and its settings; the deterministic double has none"
        }
    }

    // el nombre de su carpeta de resultados y de su fila en el informe
    val id: String
        get() = if (generator == Generator.DETERMINISTIC) DETERMINISTIC_ID else "$provider-$model-${settings?.effort?.wire ?: DEFAULT_EFFORT}"

    fun choice(): ModelChoice? = if (generator == Generator.MODEL) ModelChoice(provider!!, model!!, settings!!) else null

    companion object {
        const val DETERMINISTIC_ID = "deterministic"

        // sin esfuerzo explícito no se envía ninguno y vale el del proveedor
        const val DEFAULT_EFFORT = "provider-default"

        val DETERMINISTIC = EvaluationCandidate(Generator.DETERMINISTIC)
    }
}

// ACCEPTED: la salida pasó la comprobación del paso. INVALID_OUTPUT: no se deserializó o la comprobación la rechazó entera.
// FAILED: el proveedor falló (estado HTTP o red); no dice nada de la calidad del modelo y no puntúa
enum class CaseStatus { ACCEPTED, INVALID_OUTPUT, FAILED }

// lo que salió de un caso con un participante, antes de puntuar. parsed: la salida se deserializó en el tipo del paso.
// output: la salida comprobada (ACCEPTED) o la que devolvió el generador y la comprobación rechazó; validations: el informe
// de la comprobación
data class CaseOutcome(
    val status: CaseStatus,
    val parsed: Boolean,
    val output: Any? = null,
    val validations: Any? = null,
    val error: String? = null,
    val usage: TokenUsage? = null,
)

// un caso ejecutado y puntuado: lo que se guarda en <resultados>/<tarea>/<participante>/<caso>.json
data class CaseResult(
    val task: String,
    val case: String,
    val candidate: String,
    val generator: Generator,
    val status: CaseStatus,
    val output: Any?,
    val validations: Any?,
    val error: String?,
    val usage: TokenUsage?,
    val durationMs: Long,
    val sources: List<SourceDigest>,
    // nombre de la puntuación -> valor entre 0 y 1; null: no aplica en este caso (p. ej. sin hallazgos que validar)
    val scores: Map<String, Double?>,
)

// las puntuaciones, calculadas por código a partir de la salida, su informe de validación y lo esperado del caso. Un caso
// FAILED no puntúa (todas null): un 429 no es un defecto del modelo. Las de cada tarea están en su criteria.md
object EvaluationScores {
    const val SCHEMA_VALID = "schema_valid"
    const val ACCEPTED = "accepted"
    const val PROVENANCE_VALID = "provenance_valid"
    const val COVERAGE = "coverage"
    const val ORIGINS_VALID = "origins_valid"
    const val VERDICT = "verdict"
    const val ORIGIN_RECALL = "origin_recall"

    val SOURCE_ANALYSIS = listOf(SCHEMA_VALID, ACCEPTED, PROVENANCE_VALID, COVERAGE)
    val CONSISTENCY_REVIEW = listOf(SCHEMA_VALID, ACCEPTED, ORIGINS_VALID, VERDICT, ORIGIN_RECALL)

    fun names(task: EvaluationTask): List<String> =
        when (task) {
            EvaluationTask.SOURCE_ANALYSIS -> SOURCE_ANALYSIS
            EvaluationTask.CONSISTENCY_REVIEW -> CONSISTENCY_REVIEW
        }

    // schema_valid: la salida se deserializó. accepted: pasó SourceAnalysisCheck. provenance_valid: elementos que quedaron entre
    // todos los que devolvió (los descartados, con su motivo, en validations). coverage: elementos esperados del caso que tienen
    // uno de su clase con procedencia en su sección
    fun sourceAnalysis(
        case: SourceAnalysisCase,
        outcome: CaseOutcome,
    ): Map<String, Double?> {
        if (outcome.status == CaseStatus.FAILED) return SOURCE_ANALYSIS.associateWith { null }
        val report = outcome.validations as? SourceAnalysisReport
        val analysis = (outcome.output as? SourceAnalysis).takeIf { outcome.status == CaseStatus.ACCEPTED }
        val kept = report?.let { it.concepts + it.procedures } ?: 0
        val total = kept + (report?.discarded?.size ?: 0)
        val found =
            analysis
                ?.let { a -> a.concepts.map { SourceElementKind.CONCEPT to it.source } + a.procedures.map { SourceElementKind.PROCEDURE to it.source } }
                .orEmpty()
                .toSet()
        val covered = case.expected.count { (it.kind to it.source) in found }
        return linkedMapOf(
            SCHEMA_VALID to flag(outcome.parsed),
            ACCEPTED to flag(outcome.status == CaseStatus.ACCEPTED),
            PROVENANCE_VALID to
                if (outcome.parsed && total > 0) {
                    kept.toDouble() / total
                } else if (outcome.parsed) {
                    null
                } else {
                    0.0
                },
            COVERAGE to if (case.expected.isEmpty()) null else covered.toDouble() / case.expected.size,
        )
    }

    // schema_valid y accepted, como arriba (ConsistencyReviewCheck). origins_valid: hallazgos que quedaron entre todos los que
    // devolvió (uno que cita un registro que no está en el borrador se descarta). verdict: el veredicto es el esperado.
    // origin_recall: registros esperados que señala algún hallazgo que quedó
    fun consistencyReview(
        case: ConsistencyReviewCase,
        outcome: CaseOutcome,
    ): Map<String, Double?> {
        if (outcome.status == CaseStatus.FAILED) return CONSISTENCY_REVIEW.associateWith { null }
        val report = outcome.validations as? ConsistencyReviewReport
        val opinion = (outcome.output as? ConsistencyOpinion).takeIf { outcome.status == CaseStatus.ACCEPTED }
        val total = (report?.findings ?: 0) + (report?.discarded?.size ?: 0)
        val origins =
            opinion
                ?.findings
                ?.mapNotNull { it.origin }
                .orEmpty()
                .toSet()
        return linkedMapOf(
            SCHEMA_VALID to flag(outcome.parsed),
            ACCEPTED to flag(outcome.status == CaseStatus.ACCEPTED),
            ORIGINS_VALID to
                if (report != null && total > 0) {
                    report.findings.toDouble() / total
                } else if (outcome.parsed) {
                    null
                } else {
                    0.0
                },
            VERDICT to flag(opinion?.verdict == case.expected.verdict),
            ORIGIN_RECALL to
                if (case.expected.origins.isEmpty()) {
                    null
                } else {
                    case.expected.origins
                        .count { it in origins }
                        .toDouble() / case.expected.origins.size
                },
        )
    }

    private fun flag(value: Boolean) = if (value) 1.0 else 0.0
}

// la fila de un participante en la comparación: casos, cuántos aceptó la comprobación, cuántos fallaron por el proveedor, la
// media de cada puntuación sobre los casos en que aplica y los tokens que informó el proveedor
data class CandidateSummary(
    val candidate: EvaluationCandidate,
    val cases: Int,
    val accepted: Int,
    val failed: Int,
    val scores: Map<String, Double?>,
    val inputTokens: Long,
    val outputTokens: Long,
)

data class ExcludedCandidate(
    val candidate: String,
    val reason: String,
)

data class TaskComparison(
    val task: String,
    val criteria: String,
    val promptVersion: String,
    val templateVersion: String,
    val scoreNames: List<String>,
    val compared: List<CandidateSummary>,
    val excluded: List<ExcludedCandidate>,
)

object ModelComparison {
    const val DETERMINISTIC_EXCLUDED = "deterministic double (docs/ai §7): a baseline that never enters the comparison"

    // solo los modelos: el doble determinista se ejecuta y se guarda, pero queda fuera, con su motivo
    fun of(
        task: EvaluationTask,
        criteria: String,
        candidates: List<EvaluationCandidate>,
        results: List<CaseResult>,
    ): TaskComparison {
        val names = EvaluationScores.names(task)
        val byCandidate = results.groupBy { it.candidate }
        val (models, doubles) = candidates.partition { it.generator == Generator.MODEL }
        val compared =
            models.map { candidate ->
                val own = byCandidate[candidate.id].orEmpty()
                CandidateSummary(
                    candidate = candidate,
                    cases = own.size,
                    accepted = own.count { it.status == CaseStatus.ACCEPTED },
                    failed = own.count { it.status == CaseStatus.FAILED },
                    scores = names.associateWith { name -> own.mapNotNull { it.scores[name] }.takeIf { it.isNotEmpty() }?.average() },
                    inputTokens = own.sumOf { it.usage?.inputTokens?.toLong() ?: 0L },
                    outputTokens = own.sumOf { it.usage?.outputTokens?.toLong() ?: 0L },
                )
            }
        return TaskComparison(
            task = task.dir,
            criteria = criteria,
            promptVersion = task.promptVersion,
            templateVersion = task.templateVersion,
            scoreNames = names,
            compared = compared,
            excluded = doubles.map { ExcludedCandidate(it.id, DETERMINISTIC_EXCLUDED) },
        )
    }
}

// lo que identifica la ejecución: cuándo, contra qué, en qué commit y con qué conjuntos (sha256 de set.json y criteria.md)
data class EvaluationRunInfo(
    val startedAt: String,
    val mode: EvaluationMode,
    val commit: String?,
    val clean: Boolean?,
    val sets: Map<String, EvaluationSetDigest>,
)

data class EvaluationSetDigest(
    val criteria: String,
    val setSha256: String,
    val criteriaSha256: String,
    val cases: Int,
)

// el informe en Markdown (report.md). Con SMOKE no lleva cifras: dice qué corrió y cómo terminó cada caso
object EvaluationReport {
    fun markdown(
        info: EvaluationRunInfo,
        comparisons: List<TaskComparison>,
        results: List<CaseResult>,
    ): String =
        buildString {
            append("# Comparación de modelos (RF-IA-09)\n\n")
            append("- Inicio: ").append(info.startedAt).append('\n')
            append("- Modo: ").append(if (info.mode == EvaluationMode.PROVIDERS) "proveedores reales" else "prueba de humo contra el doble HTTP").append('\n')
            append("- Commit: ").append(info.commit ?: "desconocido")
            if (info.clean == false) append(" (con cambios sin confirmar)")
            append('\n')
            if (info.mode == EvaluationMode.SMOKE) {
                append("\n").append(SMOKE_NOTICE).append('\n')
            }
            for (comparison in comparisons) {
                val digest = info.sets[comparison.task]
                append("\n## ").append(comparison.task).append("\n\n")
                append("Criterios `").append(comparison.criteria).append("`, instrucciones `").append(comparison.promptVersion)
                append("`, plantilla `").append(comparison.templateVersion).append('`')
                if (digest != null) {
                    append(", conjunto sha256 `")
                        .append(digest.setSha256.take(SHORT_HASH))
                        .append("` (")
                        .append(digest.cases)
                        .append(" casos)")
                }
                append(".\n\n")
                append("| Participante | Proveedor | Papel | Esfuerzo | max_tokens | Casos | Aceptados | Fallidos |")
                if (info.mode == EvaluationMode.PROVIDERS) {
                    comparison.scoreNames.forEach { append(' ').append(it).append(" |") }
                    append(" Tokens entrada | Tokens salida |")
                }
                append('\n')
                append("| --- | --- | --- | --- | --- | --- | --- | --- |")
                if (info.mode == EvaluationMode.PROVIDERS) repeat(comparison.scoreNames.size + 2) { append(" --- |") }
                append('\n')
                for (row in comparison.compared) {
                    val c = row.candidate
                    append("| ")
                        .append(c.model)
                        .append(" | ")
                        .append(c.provider)
                        .append(" | ")
                        .append(c.role)
                        .append(" | ")
                    append(c.settings?.effort?.wire ?: EvaluationCandidate.DEFAULT_EFFORT).append(" | ").append(c.settings?.maxTokens).append(" | ")
                    append(row.cases)
                        .append(" | ")
                        .append(row.accepted)
                        .append(" | ")
                        .append(row.failed)
                        .append(" |")
                    if (info.mode == EvaluationMode.PROVIDERS) {
                        comparison.scoreNames.forEach { append(' ').append(number(row.scores[it])).append(" |") }
                        append(' ')
                            .append(row.inputTokens)
                            .append(" | ")
                            .append(row.outputTokens)
                            .append(" |")
                    }
                    append('\n')
                }
                if (comparison.excluded.isNotEmpty()) {
                    append("\nFuera de la comparación:\n\n")
                    comparison.excluded.forEach {
                        append("- `")
                            .append(it.candidate)
                            .append("`: ")
                            .append(it.reason)
                            .append('\n')
                    }
                }
                append("\nPor caso:\n\n| Participante | Caso | Estado |\n| --- | --- | --- |\n")
                results.filter { it.task == comparison.task }.forEach {
                    append("| ")
                        .append(it.candidate)
                        .append(" | ")
                        .append(it.case)
                        .append(" | ")
                        .append(it.status)
                    if (it.error != null) append(": ").append(it.error.replace("|", "/"))
                    append(" |\n")
                }
            }
        }

    const val SMOKE_NOTICE = "Sin cifras: las respuestas son las fijas del doble HTTP y no dicen nada de ningún modelo."

    private fun number(value: Double?) = value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "n/a"

    private const val SHORT_HASH = 12
}
