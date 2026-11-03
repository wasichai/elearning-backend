package elearning.ai

import elearning.jobs.Job
import elearning.jobs.JobHandler
import elearning.jobs.PermanentJobFailure
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordService
import java.util.UUID

// el trabajo ai.course_design: lee la salida del análisis de fuentes por su ejecución, llama al diseñador del modelo encolado
// (o al doble sin modelo) y comprueba la salida por código, con la carga de workload. Lo demás (permisos al ejecutar,
// idempotencia, reintentos, cancelación, presupuesto y AgentRun) es de AiStepRunner. Como el análisis, su borrador es la salida
// de su ejecución SUCCEEDED: no escribe registros. Una salida inválida queda INVALID_OUTPUT, sin salida ni executionId
@Component
class CourseDesignJobHandler(
    private val runner: AiStepRunner,
    private val store: AgentExecutionStore,
    private val model: ModelCourseDesigner,
    private val double: DeterministicCourseDesigner,
) : JobHandler {
    override val type = AiJobs.typeOf(AgentStep.COURSE_DESIGN)

    override suspend fun handle(job: Job): String =
        runner.run(
            job,
            read = { request -> AiStepInput(material(job, CourseDesignInputs.of(request.inputs)), request.sources) },
            generator = { prompt, material ->
                val designer: CourseDesigner = if (prompt.model == null) double else model
                val reply = designer.design(prompt, material)
                CourseDesignCheck.checked(reply.value, material, CourseDesignInputs.of(prompt.inputs).hours, reply.usage)
            },
        )

    // el análisis del que parte, que al encolar ya era SUCCEEDED: una ejecución terminada no cambia, así que si falta o no lo
    // es, reintentar no lo arregla
    private suspend fun material(
        job: Job,
        inputs: CourseDesignInputs,
    ): DesignMaterial {
        val id = UUID.fromString(inputs.analysisExecutionId)
        val analysis = store.find(job.organizationId, id)
        val output = analysis?.output
        if (analysis == null || analysis.step != AgentStep.SOURCE_ANALYSIS || analysis.status != AgentExecutionStatus.SUCCEEDED || output == null) {
            throw PermanentJobFailure("The source analysis $id is not a succeeded execution")
        }
        return DesignMaterial.of(AiJobs.MAPPER.treeToValue(output, SourceAnalysis::class.java), analysis.sources.map { it.ref })
    }
}

data class CourseDesignRequest(
    // la ejecución SUCCEEDED del análisis de fuentes (el executionId de su trabajo)
    val executionId: UUID? = null,
)

// encolar el diseño de un análisis de fuentes SUCCEEDED (docs/api/lms.md). Exige GENERAR (AiJobs) y ver el curso del análisis
// como quien llama (404, como su lectura); su brief tiene que seguir LISTO, y sus horas son las que reparte workload. Las
// fuentes de la ejecución son las del análisis, con su hash. Con proveedor, el modelo de diseño y su max_tokens; sin él, el doble
@Service
class CourseDesignService(
    private val permissions: AiPermissions,
    private val records: RecordService,
    private val store: AgentExecutionStore,
    private val jobs: AiJobs,
    private val models: AiStepModels,
) {
    suspend fun enqueue(request: CourseDesignRequest): AiJob {
        val user = permissions.requireToEnqueue(AiAction.GENERAR)
        val executionId = request.executionId ?: throw ValidationException("executionId is required", "executionId", "required")
        val analysis =
            store.find(user.organizationId, executionId)?.takeIf { it.step == AgentStep.SOURCE_ANALYSIS }
                ?: throw NotFoundException("Source analysis $executionId not found")
        try {
            records.get(COURSE, analysis.courseId)
        } catch (_: NotFoundException) {
            throw NotFoundException("Source analysis $executionId not found")
        } catch (_: ForbiddenException) {
            throw NotFoundException("Source analysis $executionId not found")
        }
        if (analysis.status != AgentExecutionStatus.SUCCEEDED) {
            throw ConflictException("The source analysis $executionId is ${analysis.status}: only a succeeded analysis is designed")
        }
        val briefId = SourceAnalysisInputs.of(analysis.inputs).briefId
        val brief = records.get(BriefGuard.BRIEF, UUID.fromString(briefId)).attributes
        if (brief[BriefGuard.STATE]?.toString() != BriefGuard.READY) {
            throw ConflictException("The brief $briefId is not ${BriefGuard.READY}: only the analysis of a ready brief is designed")
        }
        val hours =
            brief[BriefGuard.HOURS]?.toString()?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                ?: throw ConflictException("The brief $briefId has no duration in hours")
        val topic = brief["tematica"].toString()
        val inputs =
            CourseDesignInputs(
                analysisExecutionId = executionId.toString(),
                briefId = briefId,
                topic = topic,
                audience = brief["publico"]?.toString(),
                objectives = brief["objetivos"]?.toString(),
                language = brief["idioma"]?.toString(),
                level = brief["nivel"]?.toString(),
                hours = plainHours(hours),
            )
        val id =
            jobs.enqueue(
                AiStepRequest(
                    step = AgentStep.COURSE_DESIGN,
                    courseId = analysis.courseId,
                    objective = "Design the course «${topic.take(MAX_TOPIC)}» from the analysis of its sources",
                    inputs = inputs,
                    sources = analysis.sources,
                    model = models.choice(MODEL_KEY),
                    promptVersion = CourseDesignPrompt.VERSION,
                    templateVersion = CourseDesignPrompt.TEMPLATE,
                ),
            )
        return jobs.find(id)
    }

    private companion object {
        const val COURSE = "curso"

        // la clave del modelo y de max_tokens del paso en lms.ai.models y lms.ai.max-tokens
        const val MODEL_KEY = "design"
        const val MAX_TOPIC = 200
    }
}

@RestController
@RequestMapping("/api/lms/ai/course-design")
class CourseDesignController(
    private val designs: CourseDesignService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun enqueue(
        @RequestBody body: CourseDesignRequest,
    ): AiJob = designs.enqueue(body)
}
