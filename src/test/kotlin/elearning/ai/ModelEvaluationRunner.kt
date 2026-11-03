package elearning.ai

import org.springframework.core.env.Environment
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.concurrent.TimeUnit

// ejecuta la comparación de modelos (docs/ai §9) y la guarda en <out>/<inicio>/: run.json (qué corrió, contra qué, en qué
// commit y con qué conjuntos, los participantes con sus parámetros efectivos, la comparación), un <tarea>/<participante>/
// <caso>.json por caso (salida, informe de validación, puntuaciones, consumo y duración) y report.md. Cada caso pasa por el
// generador del paso de producción y por su comprobación por código, con las mismas instrucciones (SourceAnalysisPrompt,
// ConsistencyReviewPrompt). Bloquea: solo desde una prueba
class ModelEvaluationRunner(
    private val sets: Path,
    private val out: Path,
    private val mode: EvaluationMode,
    private val modelAnalyzer: SourceAnalyzer,
    private val deterministicAnalyzer: SourceAnalyzer,
    private val reviewer: ConsistencyReviewer,
    private val clock: Clock = Clock.systemUTC(),
    // el commit y si docs/ai/evaluations está confirmado; null sin git
    private val git: () -> Pair<String, Boolean>? = { gitState(sets) },
) {
    data class Run(
        val dir: Path,
        val info: EvaluationRunInfo,
        val candidates: Map<String, List<EvaluationCandidate>>,
        val comparisons: List<TaskComparison>,
        val results: List<CaseResult>,
    )

    fun run(candidates: Map<EvaluationTask, List<EvaluationCandidate>>): Run {
        val started = clock.instant()
        val git = git()
        // con proveedores reales, los conjuntos y sus criterios deben estar confirmados antes de ejecutar: si no, el informe no
        // se podría reproducir desde su commit
        if (mode == EvaluationMode.PROVIDERS) {
            check(git != null) { "aiEval needs git to record the commit of the evaluation sets" }
            check(git.second) { "docs/ai/evaluations has uncommitted changes: commit the sets and their criteria before running aiEval" }
        }
        val digests = mutableMapOf<String, EvaluationSetDigest>()
        val comparisons = mutableListOf<TaskComparison>()
        val results = mutableListOf<CaseResult>()
        for ((task, participants) in candidates) {
            require(participants.isNotEmpty()) { "no candidate for ${task.dir}" }
            val (criteria, digest, taskResults) =
                when (task) {
                    EvaluationTask.SOURCE_ANALYSIS -> {
                        val set = read(task, SourceAnalysisSet::class.java)
                        Triple(set.criteria, digest(task, set.criteria, set.cases.size), participants.flatMap { c -> set.cases.map { sourceAnalysis(c, it) } })
                    }
                    EvaluationTask.CONSISTENCY_REVIEW -> {
                        val set = read(task, ConsistencyReviewSet::class.java)
                        require(participants.none { it.generator == Generator.DETERMINISTIC }) { "the consistency review has no deterministic double" }
                        Triple(
                            set.criteria,
                            digest(task, set.criteria, set.cases.size),
                            participants.flatMap { c ->
                                set.cases.map { consistencyReview(c, it) }
                            },
                        )
                    }
                }
            digests[task.dir] = digest
            results += taskResults
            comparisons += ModelComparison.of(task, criteria, participants, taskResults)
        }
        val info = EvaluationRunInfo(started.toString(), mode, git?.first, git?.second, digests)
        val dir = out.resolve(STAMP.format(started))
        Files.createDirectories(dir)
        for (result in results) write(dir.resolve(result.task).resolve(result.candidate).resolve("${result.case}.json"), result)
        val byTask = candidates.mapKeys { it.key.dir }
        write(dir.resolve("run.json"), mapOf("info" to info, "candidates" to byTask, "comparisons" to comparisons))
        Files.writeString(dir.resolve("report.md"), EvaluationReport.markdown(info, comparisons, results))
        return Run(dir, info, byTask, comparisons, results)
    }

    private fun sourceAnalysis(
        candidate: EvaluationCandidate,
        case: SourceAnalysisCase,
    ): CaseResult {
        val material = case.material()
        val sources = material.sections.map { SourceDigest(it.ref, sha256(it.text.toByteArray())) }
        val prompt =
            AiPrompt(
                step = AgentStep.SOURCE_ANALYSIS,
                objective = SourceAnalysisPrompt.objective(case.inputs.topic.take(MAX_TOPIC)),
                inputs = AiJobs.MAPPER.valueToTree<JsonNode>(case.inputs),
                sources = sources,
                model = candidate.choice(),
                promptVersion = SourceAnalysisPrompt.VERSION,
                templateVersion = SourceAnalysisPrompt.TEMPLATE,
            )
        val analyzer = if (candidate.generator == Generator.MODEL) modelAnalyzer else deterministicAnalyzer
        val (outcome, millis) = timed { outcome({ analyzer.analyze(prompt, material) }) { SourceAnalysisCheck.checked(it.value, material, it.usage) } }
        return CaseResult(
            task = EvaluationTask.SOURCE_ANALYSIS.dir,
            case = case.id,
            candidate = candidate.id,
            generator = candidate.generator,
            status = outcome.status,
            output = outcome.output,
            validations = outcome.validations,
            error = outcome.error,
            usage = outcome.usage,
            durationMs = millis,
            sources = sources,
            scores = EvaluationScores.sourceAnalysis(case, outcome),
        )
    }

    private fun consistencyReview(
        candidate: EvaluationCandidate,
        case: ConsistencyReviewCase,
    ): CaseResult {
        val hash = sha256(AiJobs.MAPPER.writeValueAsBytes(case.draft))
        val prompt =
            AiPrompt(
                step = AgentStep.CONSISTENCY_REVIEW,
                objective = ConsistencyReviewPrompt.objective(case.draft.versionId),
                inputs = AiJobs.MAPPER.valueToTree<JsonNode>(ConsistencyReviewInputs(case.draft.versionId, hash)),
                sources = emptyList(),
                model = candidate.choice(),
                promptVersion = ConsistencyReviewPrompt.VERSION,
                templateVersion = ConsistencyReviewPrompt.TEMPLATE,
            )
        val (outcome, millis) =
            timed {
                outcome(
                    { reviewer.review(prompt, case.draft) },
                ) { ConsistencyReviewCheck.checked(it.value, case.draft, hash, it.usage) }
            }
        return CaseResult(
            task = EvaluationTask.CONSISTENCY_REVIEW.dir,
            case = case.id,
            candidate = candidate.id,
            generator = candidate.generator,
            status = outcome.status,
            output = outcome.output,
            validations = outcome.validations,
            error = outcome.error,
            usage = outcome.usage,
            durationMs = millis,
            sources = emptyList(),
            scores = EvaluationScores.consistencyReview(case, outcome),
        )
    }

    // la llamada y la comprobación, como las clasifica AiStepRunner: una salida que no se deserializa o que la comprobación
    // rechaza es INVALID_OUTPUT (con lo que costó); un estado HTTP del proveedor o un fallo de red, FAILED. El mensaje es el de
    // ModelCalls, que solo dice el estado (RNF-SEG-09); de cualquier otro fallo se guarda solo su clase
    private fun <T : Any> outcome(
        call: () -> ModelReply<T>,
        check: (ModelReply<T>) -> AiReply,
    ): CaseOutcome {
        val reply =
            try {
                call()
            } catch (e: AiInvalidOutput) {
                return CaseOutcome(CaseStatus.INVALID_OUTPUT, parsed = false, validations = e.validations, error = e.message, usage = e.usage)
            } catch (e: AiProviderFailure) {
                return CaseOutcome(CaseStatus.FAILED, parsed = false, error = e.message)
            } catch (e: Exception) {
                return CaseOutcome(CaseStatus.FAILED, parsed = false, error = e.javaClass.simpleName)
            }
        return try {
            val checked = check(reply)
            CaseOutcome(CaseStatus.ACCEPTED, parsed = true, output = checked.output, validations = checked.validations, usage = reply.usage)
        } catch (e: AiInvalidOutput) {
            CaseOutcome(CaseStatus.INVALID_OUTPUT, parsed = true, output = reply.value, validations = e.validations, error = e.message, usage = reply.usage)
        }
    }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val value = block()
        return value to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    private fun <S : Any> read(
        task: EvaluationTask,
        type: Class<S>,
    ): S = AiJobs.MAPPER.readValue(sets.resolve(task.dir).resolve(SET).toFile(), type)

    private fun digest(
        task: EvaluationTask,
        criteria: String,
        cases: Int,
    ): EvaluationSetDigest {
        val dir = sets.resolve(task.dir)
        return EvaluationSetDigest(criteria, sha256(Files.readAllBytes(dir.resolve(SET))), sha256(Files.readAllBytes(dir.resolve(CRITERIA))), cases)
    }

    private fun write(
        path: Path,
        value: Any,
    ) {
        Files.createDirectories(path.parent)
        Files.writeString(path, AiJobs.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n")
    }

    companion object {
        const val SET = "set.json"
        const val CRITERIA = "criteria.md"

        // el de SourceAnalysisService
        private const val MAX_TOPIC = 200
        private const val GIT_TIMEOUT = 30L
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)

        fun gitState(sets: Path): Pair<String, Boolean>? =
            try {
                val dir = sets.toRealPath()
                val commit = command(dir, "git", "rev-parse", "HEAD").trim()
                val dirty = command(dir, "git", "status", "--porcelain", "--", dir.toString()).isNotBlank()
                commit to !dirty
            } catch (e: Exception) {
                null
            }

        private fun command(
            dir: Path,
            vararg args: String,
        ): String {
            val process = ProcessBuilder(*args).directory(dir.toAbsolutePath().toFile()).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(GIT_TIMEOUT, TimeUnit.SECONDS) && process.exitValue() == 0) { "${args.joinToString(" ")} failed: $output" }
            return output
        }

        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        // los participantes configurados de una tarea (ADR-0033 §3): por cada proveedor con clave, el modelo del paso y el de
        // referencia, sin repetir, con el max_tokens del paso (el mismo para todos: la comparación es en igualdad) y cada
        // esfuerzo pedido (null: el del proveedor, sin enviarlo). Ninguna temperatura: Claude 5 no la admite
        fun configured(
            environment: Environment,
            task: EvaluationTask,
            efforts: List<ReasoningEffort?>,
        ): List<EvaluationCandidate> {
            val maxTokens =
                checkNotNull(environment.getProperty("lms.ai.max-tokens.${task.modelKey}", Int::class.javaObjectType)) {
                    "lms.ai.max-tokens.${task.modelKey} is not configured"
                }
            return AiProvider.keyed(environment).flatMap { provider ->
                val models = provider.models(environment)
                listOf(task.modelKey, EvaluationTask.REFERENCE_KEY)
                    .mapNotNull { role -> models[role]?.let { role to it } }
                    .distinctBy { it.second }
                    .flatMap { (role, model) ->
                        efforts.map { effort -> EvaluationCandidate(Generator.MODEL, provider.id, model, role, ModelSettings(maxTokens, effort = effort)) }
                    }
            }
        }

        // LMS_AI_EVAL_EFFORTS: esfuerzos separados por comas (low, medium, high o default, el del proveedor). vacío: medium
        fun efforts(value: String?): List<ReasoningEffort?> =
            value
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.map { name ->
                    if (name.equals("default", ignoreCase = true)) {
                        null
                    } else {
                        ReasoningEffort.entries.firstOrNull { it.wire.equals(name, ignoreCase = true) }
                            ?: error("LMS_AI_EVAL_EFFORTS has '$name': use ${ReasoningEffort.entries.map { it.wire }} or default")
                    }
                }?.distinct()
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(ReasoningEffort.MEDIUM)
    }
}
