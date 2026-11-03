package elearning.media.video

import elearning.jobs.JobQueue
import elearning.jobs.JobState
import elearning.jobs.JobStatus
import elearning.jobs.RequiredPermission
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

// lo que pide quien encola un render: el guion de vídeo (render-worker/schema/guion.schema.json)
data class VideoRenderRequest(
    val guion: JsonNode?,
)

// un render tal como lo ve quien lo pidió (docs/api/lms.md). video y subtitles: sha256 y tamaño de lo renderizado
data class VideoRenderJob(
    val id: UUID,
    val resourceId: UUID,
    val status: String,
    val attempts: Int,
    val maxAttempts: Int,
    val error: String?,
    val durationSeconds: Int,
    val video: WorkerFile?,
    val subtitles: WorkerFile?,
    val createdAt: Instant,
    val finishedAt: Instant?,
)

// pedir y seguir un render de vídeo (ADR-0040, RF-MED-02). Pedirlo exige lo mismo que crear a mano una versión del recurso
// (CREATE sobre version_recurso) y ver el recurso, que tiene que ser de tipo VIDEO; con el render apagado
// (lms.render.enabled=false, lo de por defecto) es 409. El guion se valida contra el esquema antes de encolar: lo que no lo
// cumple es 400 y nunca llega a la cola. El trabajo corre luego en la cola (VideoRenderJobHandler): aquí nada espera al render
@Service
class VideoRenderService(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val metadata: MetadataService,
    private val queue: JobQueue,
    private val properties: RenderProperties,
) {
    suspend fun request(
        resourceId: UUID,
        body: VideoRenderRequest,
    ): VideoRenderJob {
        val user = currentUser.require()
        requireCreator()
        if (!properties.enabled) throw ConflictException(VideoRenders.DISABLED)
        // como quien llama: un recurso que no ve es 404
        val type = records.get(RESOURCE, resourceId).attributes[TYPE_FIELD]?.toString()
        if (type != VIDEO) throw ConflictException("Only VIDEO resources are rendered from a video script, not $type")
        val script = body.guion ?: throw ValidationException("Invalid video script", "guion", "is required")
        val violations = ScriptSchema.validate(script, properties.maxSeconds) + unsupported(script)
        if (violations.isNotEmpty()) throw ValidationException("Invalid video script", violations.map { FieldViolation("guion.${it.path}", it.message) })
        val text = VideoRenders.MAPPER.writeValueAsString(script)
        val payload =
            VideoRenders.MAPPER.writeValueAsString(
                mapOf(VideoRenders.REQUESTER_ID to user.userId.toString(), VideoRenders.RESOURCE_ID to resourceId.toString(), VideoRenders.SCRIPT to script),
            )
        // la misma persona, el mismo recurso y el mismo guion mientras el trabajo no termina: el mismo trabajo (ADR-0022)
        val key = "${user.userId}:$resourceId:${sha256(text)}"
        val id = queue.enqueue(user.organizationId, VideoRenders.TYPE, payload, key = key, requester = user.userId)
        return find(id)
    }

    // solo los renders de la organización, para quien puede pedirlos y solo el suyo: el de otra persona, también para el
    // administrador, no existe (404), como la generación de documentos. Se lee también con el render apagado
    suspend fun find(id: UUID): VideoRenderJob {
        val user = currentUser.require()
        requireCreator()
        val status = queue.find(user.organizationId, id)
        val payload = status?.takeIf { it.type == VideoRenders.TYPE }?.let { VideoRenders.MAPPER.readTree(it.payload) }
        if (status == null || payload == null || payload.get(VideoRenders.REQUESTER_ID)?.asString() != user.userId.toString()) {
            throw NotFoundException("Video render $id not found")
        }
        return view(status, payload)
    }

    // hasta que el guion de RF-MED-01 (#293) tome las imágenes de los archivos del curso, no hay de dónde sacarlas
    private fun unsupported(script: JsonNode): List<ScriptViolation> =
        script
            .get("secciones")
            ?.values()
            ?.mapIndexedNotNull { i, section ->
                if (section.get("diapositiva")?.get("tipo")?.asString() == "imagen") {
                    ScriptViolation("secciones.$i.diapositiva", "image slides are not rendered yet: their images will come from the course files (RF-MED-01)")
                } else {
                    null
                }
            }.orEmpty()

    private suspend fun requireCreator() {
        val user = currentUser.require()
        currentUser.requirePermission(user, Actions.CREATE, metadata.definitionOf(VERSION).obj.id)
    }

    private fun view(
        status: JobStatus,
        payload: JsonNode,
    ): VideoRenderJob {
        val result = status.result?.let { VideoRenders.MAPPER.readTree(it) }
        return VideoRenderJob(
            id = status.id,
            resourceId = UUID.fromString(payload.get(VideoRenders.RESOURCE_ID).asString()),
            status = STATUS.getValue(status.state),
            attempts = status.attempts,
            maxAttempts = status.maxAttempts,
            error = status.error,
            durationSeconds = ScriptSchema.durationOf(payload.get(VideoRenders.SCRIPT)),
            video = result?.get("video")?.let(::file),
            subtitles = result?.get("subtitles")?.let(::file),
            createdAt = status.createdAt,
            finishedAt = status.finishedAt,
        )
    }

    private fun file(node: JsonNode) = WorkerFile(node.get("sha256").asString(), node.get("bytes").asLong())

    private fun sha256(text: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    private companion object {
        const val RESOURCE = "recurso"
        const val VERSION = "version_recurso"
        const val TYPE_FIELD = "tipo"
        const val VIDEO = "VIDEO"

        // los mismos nombres que la generación y la importación (docs/api/lms.md)
        val STATUS =
            mapOf(
                JobState.QUEUED to "PENDIENTE",
                JobState.RUNNING to "EN_CURSO",
                JobState.DONE to "HECHO",
                JobState.FAILED to "FALLIDO",
                JobState.CANCELLED to "CANCELADO",
            )
    }
}

// lo que comparten el servicio y los manejadores: el tipo de trabajo, sus datos y lo que se exige a quien lo pide
object VideoRenders {
    const val TYPE = "VIDEO_RENDER"
    const val REQUESTER_ID = "requesterId"
    const val RESOURCE_ID = "resourceId"
    const val SCRIPT = "guion"

    const val DISABLED =
        "Video rendering is not enabled in this deployment: it needs the render worker and lms.render.enabled=true (LMS_RENDER_ENABLED), " +
            "and whoever enables it is responsible for its Remotion license (docs/operations)"

    // lo que VideoRenderService exige al encolar y el manejador vuelve a mirar al ejecutar (ADR-0022)
    val REQUIRED = listOf(RequiredPermission(Actions.CREATE, "version_recurso"), RequiredPermission(Actions.READ, "recurso"))

    val MAPPER: JsonMapper = JsonMapper.builder().findAndAddModules().build()

    fun describe(violations: List<ScriptViolation>): String = violations.take(5).joinToString("; ") { "${it.path}: ${it.message}" }
}
