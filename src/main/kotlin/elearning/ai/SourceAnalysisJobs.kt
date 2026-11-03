package elearning.ai

import elearning.jobs.Job
import elearning.jobs.JobHandler
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ConflictException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordService
import java.util.UUID

// el trabajo ai.source_analysis: lee las fuentes, llama al analizador del modelo encolado (o al doble sin modelo) y comprueba
// la salida por código. Lo demás (permisos al ejecutar, idempotencia, reintentos, cancelación, presupuesto y AgentRun) es de
// AiStepRunner. El borrador de este paso es la salida de su ejecución SUCCEEDED, que lee el paso siguiente por su id: no hay
// registro que escribir, así que no pasa draft. Una salida inválida queda INVALID_OUTPUT, sin salida ni executionId
@Component
class SourceAnalysisJobHandler(
    private val runner: AiStepRunner,
    private val reader: SourceMaterialReader,
    private val model: ModelSourceAnalyzer,
    private val double: DeterministicSourceAnalyzer,
) : JobHandler {
    override val type = AiJobs.typeOf(AgentStep.SOURCE_ANALYSIS)

    override suspend fun handle(job: Job): String =
        runner.run(
            job,
            read = { request -> reader.read(SourceAnalysisInputs.of(request.inputs).sources) },
            generator = { prompt, material ->
                val analyzer: SourceAnalyzer = if (prompt.model == null) double else model
                val reply = analyzer.analyze(prompt, material)
                SourceAnalysisCheck.checked(reply.value, material, reply.usage)
            },
        )
}

data class SourceAnalysisRequest(
    val briefId: UUID? = null,
)

// encolar el análisis de las fuentes de un brief LISTO (docs/api/lms.md). Exige GENERAR (AiJobs) y ver el brief como quien
// llama (404). Con proveedor, el modelo de extracción y su max_tokens; sin él, el doble determinista
@Service
class SourceAnalysisService(
    private val permissions: AiPermissions,
    private val records: RecordService,
    private val jobs: AiJobs,
    private val models: AiStepModels,
) {
    suspend fun enqueue(request: SourceAnalysisRequest): AiJob {
        permissions.requireToEnqueue(AiAction.GENERAR)
        val briefId = request.briefId ?: throw ValidationException("briefId is required", "briefId", "required")
        val brief = records.get(BriefGuard.BRIEF, briefId).attributes
        if (brief[BriefGuard.STATE]?.toString() != BriefGuard.READY) {
            throw ConflictException("The brief $briefId is not ${BriefGuard.READY}: only a ready brief is analyzed")
        }
        val course = UUID.fromString(ref(brief[BriefGuard.COURSE]))
        val topic = brief["tematica"].toString()
        val inputs =
            SourceAnalysisInputs(
                briefId = briefId.toString(),
                topic = topic,
                audience = brief["publico"]?.toString(),
                objectives = brief["objetivos"]?.toString(),
                language = brief["idioma"]?.toString(),
                level = brief["nivel"]?.toString(),
                sources =
                    brief[BriefGuard.SOURCES]
                        .toString()
                        .lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
            )
        val id =
            jobs.enqueue(
                AiStepRequest(
                    step = AgentStep.SOURCE_ANALYSIS,
                    courseId = course,
                    objective = "Extract the concepts and procedures, with their provenance, of the sources of the brief «${topic.take(MAX_TOPIC)}»",
                    inputs = inputs,
                    model = models.choice(MODEL_KEY),
                    promptVersion = SourceAnalysisPrompt.VERSION,
                    templateVersion = SourceAnalysisPrompt.TEMPLATE,
                ),
            )
        return jobs.find(id)
    }

    // una relación puede llegar como id o expandida
    private fun ref(value: Any?): String =
        when (value) {
            is Map<*, *> -> value["id"].toString()
            else -> value.toString()
        }

    private companion object {
        // la clave del modelo y de max_tokens del paso en lms.ai.models y lms.ai.max-tokens
        const val MODEL_KEY = "extraction"
        const val MAX_TOPIC = 200
    }
}

@RestController
@RequestMapping("/api/lms/ai/source-analysis")
class SourceAnalysisController(
    private val analyses: SourceAnalysisService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun enqueue(
        @RequestBody body: SourceAnalysisRequest,
    ): AiJob = analyses.enqueue(body)
}
