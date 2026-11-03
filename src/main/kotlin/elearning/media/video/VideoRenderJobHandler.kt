package elearning.media.video

import elearning.jobs.Job
import elearning.jobs.JobCancelled
import elearning.jobs.JobHandler
import elearning.jobs.JobQueue
import elearning.jobs.PermanentJobFailure
import elearning.jobs.Requesters
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.UUID

// el render de vídeo en la cola (ADR-0040 §3, RF-MED-02): corre en el dispatcher de lms.trabajo, nunca en el hilo de WebFlux,
// y no renderiza: se lo pide al trabajador (render-worker/) por HTTP interno y espera su informe. Como mucho
// lms.render.max-concurrent renders en curso entre todas las réplicas (los demás esperan en QUEUED) y su propio tiempo límite,
// lms.render.timeout. Antes de pedirlo vuelve a mirar que quien lo pidió pueda todavía (ADR-0022) y que el guion siga
// cumpliendo el esquema; antes de pedirlo y antes de recoger, que no lo hayan cancelado. Recoge el vídeo y los subtítulos
// comprobando su sha256 y su tamaño contra el informe, y los borra del trabajador.
//
// Lo que queda en el trabajo: los datos (guion y quién) al encolar; el informe (sha256 y tamaños, ffprobe, versiones de
// Remotion y del trabajador) como resultado; el error, si falla. Validar el informe de ffprobe contra el guion y guardar el
// MP4 y el VTT en wasichai-files es E37.3 (#298), en el punto marcado abajo
@Component
@ConditionalOnProperty(prefix = "lms.render", name = ["enabled"], havingValue = "true")
class VideoRenderJobHandler(
    private val properties: RenderProperties,
    private val queue: JobQueue,
    private val requesters: Requesters,
    @Value("\${wasichai.files.max-bytes:10MB}") filesMaxBytes: DataSize,
) : JobHandler {
    private val worker = RenderWorkerClient(properties)

    init {
        // ADR-0040 §7: el vídeo se guarda en wasichai-files, que no aceptaría uno del tamaño que se permite renderizar
        require(filesMaxBytes.toBytes() >= properties.maxBytes.toBytes()) {
            "wasichai.files.max-bytes ($filesMaxBytes) must be at least lms.render.max-bytes (${properties.maxBytes}) when lms.render.enabled is true"
        }
    }

    override val type: String = VideoRenders.TYPE
    override val timeout: Duration = properties.timeout
    override val maxConcurrent: Int = properties.maxConcurrent

    override suspend fun handle(job: Job): String {
        val payload = VideoRenders.MAPPER.readTree(job.payload)
        val requester = UUID.fromString(payload.required(VideoRenders.REQUESTER_ID).asString())
        val script = payload.required(VideoRenders.SCRIPT)
        requesters.requireStillAllowed(job.organizationId, requester, VideoRenders.REQUIRED)
        // la configuración pudo cambiar desde que se encoló (lms.render.max-seconds)
        val violations = ScriptSchema.validate(script, properties.maxSeconds)
        if (violations.isNotEmpty()) throw PermanentJobFailure("The video script is not valid: ${VideoRenders.describe(violations)}")
        if (queue.cancelled(job)) throw JobCancelled(job)
        return try {
            val report = VideoRenders.MAPPER.readTree(worker.render(job.id, request(script)))
            val video = collect(job, report, VIDEO, properties.maxBytes.toBytes())
            val subtitles = collect(job, report, SUBTITLES, MAX_SUBTITLE_BYTES)
            if (queue.cancelled(job)) {
                worker.delete(job.id)
                throw JobCancelled(job)
            }
            // E37.3 (#298): aquí se valida el informe de ffprobe contra el guion y se guardan el MP4 y el VTT en wasichai-files
            worker.delete(job.id)
            VideoRenders.MAPPER.writeValueAsString(
                mapOf(
                    "video" to video,
                    "subtitles" to subtitles,
                    "durationSeconds" to report.get("duracionSegundos")?.asInt(),
                    "versions" to report.get("versiones"),
                    "ffprobe" to report.get("ffprobe"),
                ),
            )
        } catch (e: RenderWorkerException) {
            if (e.permanent) throw PermanentJobFailure(e.message ?: "The render failed", e)
            throw e
        }
    }

    // lo que se manda al trabajador: el guion y las imágenes que nombra. hasta que el guion de RF-MED-01 (#293) las tome de
    // los archivos del curso, un guion con imágenes no se encola (VideoRenderService), así que no hay ninguna
    private fun request(script: JsonNode): String = VideoRenders.MAPPER.writeValueAsString(mapOf("guion" to script, "recursos" to emptyMap<String, String>()))

    // descarga y comprueba un archivo contra el informe: sha256 y tamaño iguales, o el render no vale
    private suspend fun collect(
        job: Job,
        report: JsonNode,
        name: String,
        maxBytes: Long,
    ): WorkerFile {
        val described = report.get(if (name == VIDEO) "video" else "subtitulos") ?: throw PermanentJobFailure("The render report does not describe $name")
        val file = worker.download(job.id, name, maxBytes)
        val sha256 = described.get("sha256")?.asString()
        val bytes = described.get("bytes")?.asLong()
        if (!file.sha256.equals(sha256, ignoreCase = true) || file.bytes != bytes) {
            // se pudo cortar a mitad: otro intento vuelve a descargarlo (el render no se repite, el PUT es idempotente)
            throw RenderWorkerException("The downloaded $name does not match the render report (sha256 $sha256, $bytes bytes)", permanent = false)
        }
        return file
    }

    private companion object {
        const val VIDEO = "video.mp4"
        const val SUBTITLES = "subtitulos.vtt"
        const val MAX_SUBTITLE_BYTES = 1024L * 1024
    }
}
