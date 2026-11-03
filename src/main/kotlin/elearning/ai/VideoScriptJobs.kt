package elearning.ai

import elearning.common.listAll
import elearning.common.ref
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
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import java.util.UUID

// el trabajo ai.script: lee las fuentes del brief, llama al escritor del modelo encolado (o al doble sin modelo), comprueba el
// guion por código y, si vale, lo guarda como la versión siguiente del guion de su versión de recurso (VideoScriptStore), en la
// transacción que cierra el trabajo. Lo demás (permisos al ejecutar, idempotencia, reintentos, cancelación, presupuesto y
// AgentRun) es de AiStepRunner. Un guion inválido queda INVALID_OUTPUT, sin salida, sin executionId y sin guion guardado
@Component
class VideoScriptJobHandler(
    private val runner: AiStepRunner,
    private val reader: SourceMaterialReader,
    private val model: ModelScriptWriter,
    private val double: DeterministicScriptWriter,
    private val scripts: VideoScriptStore,
) : JobHandler {
    override val type = AiJobs.typeOf(AgentStep.SCRIPT)

    override suspend fun handle(job: Job): String {
        val request = AiJobs.payloadOf(job.payload)
        val inputs = VideoScriptInputs.of(request.inputs)
        return runner.run(
            job,
            read = { reader.read(inputs.sources) },
            generator = { prompt, material ->
                val writer: ScriptWriter = if (prompt.model == null) double else model
                val reply = writer.write(prompt, material)
                VideoScriptCheck.checked(reply.value, material, inputs, reply.usage)
            },
            draft = { execution -> scripts.save(job, request.requester, inputs, execution) },
        )
    }
}

// guion_video (RF-MED-01): cada guion es una versión, de solo inserción, de su version_recurso VIDEO. Lo escribe solo este
// trabajo, como la plataforma, con el número siguiente; repetir el trabajo (la réplica murió tras escribir) no duplica:
// trabajo_id es único. La versión de recurso tiene que seguir en BORRADOR y ser de quien lo pidió, como al encolar: si se envió
// a revisión o se publicó entre medias, el trabajo falla sin guion (y VideoScriptGuard lo rechazaría igual)
@Service
class VideoScriptStore(
    private val records: RecordService,
) {
    suspend fun save(
        job: Job,
        requester: UUID,
        inputs: VideoScriptInputs,
        execution: AgentExecution,
    ) {
        records.asPlatform(job.organizationId, AUDIT_SOURCE) {
            if (records.listAll(SCRIPT, mapOf(JOB to job.id.toString())).isNotEmpty()) return@asPlatform
            val version = records.get(VERSION, UUID.fromString(inputs.versionId)).attributes
            if (version[STATE]?.toString() != DRAFT) {
                throw PermanentJobFailure("The resource version ${inputs.versionId} is no longer a draft: its script cannot change")
            }
            if (version[AUTHOR]?.toString() != requester.toString()) {
                throw PermanentJobFailure("The resource version ${inputs.versionId} is not of who asked for the script")
            }
            val output = checkNotNull(execution.output) { "a SUCCEEDED script execution has its output" }
            val script = AiJobs.MAPPER.treeToValue(output, VideoScript::class.java)
            val json = VideoScriptDocuments.json(VideoScriptDocuments.props(script, inputs))
            val number = records.listAll(SCRIPT, mapOf(VERSION to inputs.versionId)).maxOfOrNull { numberOf(it.attributes[NUMBER]) }?.plus(1) ?: 1
            records.create(
                SCRIPT,
                RecordRequest(
                    mapOf(
                        VERSION to inputs.versionId,
                        NUMBER to number,
                        SCRIPT_FIELD to json,
                        SHA256 to VideoScriptDocuments.sha256(json),
                        CAPTIONS to VideoScriptDocuments.webVtt(script),
                        TRANSCRIPT to VideoScriptDocuments.transcript(script),
                        SECONDS to VideoScriptDocuments.seconds(script),
                        EXECUTION to execution.id.toString(),
                        JOB to job.id.toString(),
                    ),
                ),
            )
        }
    }

    private fun numberOf(value: Any?): Int = value?.toString()?.toBigDecimalOrNull()?.toInt() ?: 0

    companion object {
        const val SCRIPT = "guion_video"
        const val VERSION = "version_recurso"
        const val RESOURCE = "recurso"
        const val RESOURCE_TYPE = "tipo"
        const val VIDEO = "VIDEO"
        const val STATE = "estado"
        const val DRAFT = "BORRADOR"
        const val AUTHOR = "autor_id"
        const val NUMBER = "numero"
        const val SCRIPT_FIELD = "guion"
        const val SHA256 = "sha256"
        const val CAPTIONS = "subtitulos_vtt"
        const val TRANSCRIPT = "transcripcion"
        const val SECONDS = "duracion_segundos"
        const val EXECUTION = "ejecucion_agente_id"
        const val JOB = "trabajo_id"
        private const val AUDIT_SOURCE = "job:ai.script"
    }
}

data class VideoScriptRequest(
    val briefId: UUID? = null,
    // la version_recurso VIDEO en BORRADOR a la que va el guion
    val versionId: UUID? = null,
    val targetSeconds: Int? = null,
    val resolution: String? = null,
    val fps: Int? = null,
)

// encolar el guion de un vídeo (docs/api/lms.md): de las fuentes de un brief LISTO, para una version_recurso de un recurso VIDEO
// en BORRADOR de quien llama. Exige GENERAR (AiJobs) y ver el brief y la versión como quien llama (404). Con proveedor, el
// modelo del guion y su max_tokens; sin él, el doble determinista
@Service
class VideoScriptService(
    private val permissions: AiPermissions,
    private val records: RecordService,
    private val jobs: AiJobs,
    private val models: AiStepModels,
) {
    suspend fun enqueue(request: VideoScriptRequest): AiJob {
        val user = permissions.requireToEnqueue(AiAction.GENERAR)
        val briefId = request.briefId ?: throw ValidationException("briefId is required", "briefId", "required")
        val versionId = request.versionId ?: throw ValidationException("versionId is required", "versionId", "required")
        val target = request.targetSeconds ?: DEFAULT_SECONDS
        if (target !in VideoScriptCheck.MIN_TARGET_SECONDS..VideoScriptCheck.MAX_SECONDS) {
            throw ValidationException(
                "Invalid target duration",
                "targetSeconds",
                "must be from ${VideoScriptCheck.MIN_TARGET_SECONDS} to ${VideoScriptCheck.MAX_SECONDS} seconds",
            )
        }
        val resolution = request.resolution ?: RESOLUTIONS.first()
        if (resolution !in RESOLUTIONS) throw ValidationException("Invalid resolution", "resolution", "must be one of ${RESOLUTIONS.joinToString()}")
        val fps = request.fps ?: FPS.last()
        if (fps !in FPS) throw ValidationException("Invalid frame rate", "fps", "must be one of ${FPS.joinToString()}")

        val brief = records.get(BriefGuard.BRIEF, briefId).attributes
        if (brief[BriefGuard.STATE]?.toString() != BriefGuard.READY) {
            throw ConflictException("The brief $briefId is not ${BriefGuard.READY}: a script is written from a ready brief")
        }
        val version = records.get(VideoScriptStore.VERSION, versionId).attributes
        val resourceId = version.ref(VideoScriptStore.RESOURCE) ?: throw ConflictException("The resource version $versionId has no resource")
        val resource = records.get(VideoScriptStore.RESOURCE, UUID.fromString(resourceId)).attributes
        if (resource[VideoScriptStore.RESOURCE_TYPE]?.toString() != VideoScriptStore.VIDEO) {
            throw ConflictException("The resource of $versionId is not a ${VideoScriptStore.VIDEO}: only a video has a script")
        }
        // lo publicado no cambia (regla 5): el guion es de un borrador, y del borrador de quien lo pide, como su contenido
        if (version[VideoScriptStore.STATE]?.toString() != VideoScriptStore.DRAFT) {
            throw ConflictException("The resource version $versionId is not a draft: a published or reviewed version keeps its script")
        }
        if (version[VideoScriptStore.AUTHOR]?.toString() != user.userId.toString()) {
            throw ForbiddenException("Only the author of the resource version $versionId writes its script")
        }
        val language = brief["idioma"]?.toString()?.trim().orEmpty()
        if (!VideoScriptCheck.LANGUAGE.matches(language)) {
            throw ConflictException("The language of the brief $briefId is not a short BCP 47 tag (es, es-PE): the script needs it")
        }
        val topic = brief["tematica"].toString()
        val inputs =
            VideoScriptInputs(
                briefId = briefId.toString(),
                versionId = versionId.toString(),
                topic = topic,
                audience = brief["publico"]?.toString(),
                objectives = brief["objetivos"]?.toString(),
                language = language,
                level = brief["nivel"]?.toString(),
                sources =
                    brief[BriefGuard.SOURCES]
                        .toString()
                        .lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                targetSeconds = target,
                resolution = resolution,
                fps = fps,
            )
        val course = UUID.fromString(brief.ref(BriefGuard.COURSE) ?: throw ConflictException("The brief $briefId has no course"))
        val id =
            jobs.enqueue(
                AiStepRequest(
                    step = AgentStep.SCRIPT,
                    courseId = course,
                    objective = "Write the script of a $target-second video on «${topic.take(MAX_TOPIC)}» from the sources of its brief",
                    inputs = inputs,
                    model = models.choice(MODEL_KEY),
                    promptVersion = ScriptPrompt.VERSION,
                    templateVersion = ScriptPrompt.TEMPLATE,
                ),
            )
        return jobs.find(id)
    }

    private companion object {
        // la clave del modelo y de max_tokens del paso en lms.ai.models y lms.ai.max-tokens
        const val MODEL_KEY = "script"
        const val MAX_TOPIC = 200
        const val DEFAULT_SECONDS = 180

        // la salida del render (ADR-0040 §2): 720p y 30 fps por defecto
        val RESOLUTIONS = listOf("1280x720", "1920x1080")
        val FPS = listOf(25, 30)
    }
}

@RestController
@RequestMapping("/api/lms/ai/scripts")
class VideoScriptController(
    private val scripts: VideoScriptService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun enqueue(
        @RequestBody body: VideoScriptRequest,
    ): AiJob = scripts.enqueue(body)
}
