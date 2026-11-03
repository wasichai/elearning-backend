package elearning.media.video

import elearning.common.listAll
import elearning.common.ref
import elearning.jobs.Job
import elearning.jobs.JobCancelled
import elearning.jobs.JobHandler
import elearning.jobs.JobQueue
import elearning.jobs.PermanentJobFailure
import elearning.jobs.Requesters
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.util.unit.DataSize
import reactor.core.publisher.Flux
import tools.jackson.databind.JsonNode
import wasichai.core.common.NotFoundException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.files.FileService
import wasichai.files.FileUpload
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

// el render de vídeo en la cola (ADR-0040 §3, RF-MED-02): corre en el dispatcher de lms.trabajo, nunca en el hilo de WebFlux,
// y no renderiza: se lo pide al trabajador (render-worker/) por HTTP interno y espera su informe. Como mucho
// lms.render.max-concurrent renders en curso entre todas las réplicas (los demás esperan en QUEUED) y su propio tiempo límite,
// lms.render.timeout. Antes de pedirlo vuelve a mirar que quien lo pidió pueda todavía (ADR-0022) y que el guion siga
// cumpliendo el esquema; antes de pedirlo y antes de guardar, que no lo hayan cancelado.
//
// Sin validar no hay vídeo (RF-MED-03, ADR-0040 §5): el informe de ffprobe y el VTT se comparan con el guion
// (RenderedVideoCheck) y lo que no cuadra es un fallo permanente que no crea nada, así que un render fallido o incompleto
// nunca llega a una versión que se pueda enviar a revisión. Lo que cuadra se guarda como la plataforma (asPlatform):
// - el MP4, una sola vez, en el campo video de una version_recurso nueva en BORRADOR a nombre de quien lo pidió (ADR-0040 §4),
//   pasado a wasichai-files tal como llega del trabajador; su sha256 (el que calcula wasichai-files) es el del informe o el
//   intento se repite
// - su artefacto_generado MP4, sin archivo: sha256, parámetros, informe de ffprobe, generador y trabajo
// - el VTT, en su artefacto_generado VTT, con su archivo
// versión y artefactos en una transacción; lo subido que no llegue a adjuntarse lo borra la limpieza de wasichai-files.
// Repetir el trabajo (la réplica murió tras escribir) devuelve lo ya escrito: (trabajo_id, formato) es único
@Component
@ConditionalOnProperty(prefix = "lms.render", name = ["enabled"], havingValue = "true")
class VideoRenderJobHandler(
    private val properties: RenderProperties,
    private val queue: JobQueue,
    private val requesters: Requesters,
    private val records: RecordService,
    private val files: FileService,
    private val transactions: TransactionalOperator,
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
        val resource = UUID.fromString(payload.required(VideoRenders.RESOURCE_ID).asString())
        val script = payload.required(VideoRenders.SCRIPT)
        records.asPlatform(job.organizationId, AUDIT_SOURCE) { existing(job.id) }?.let {
            worker.delete(job.id)
            return it
        }
        requesters.requireStillAllowed(job.organizationId, requester, VideoRenders.REQUIRED)
        // la configuración pudo cambiar desde que se encoló (lms.render.max-seconds)
        val violations = ScriptSchema.validate(script, properties.maxSeconds)
        if (violations.isNotEmpty()) throw PermanentJobFailure("The video script is not valid: ${VideoRenders.describe(violations)}")
        if (queue.cancelled(job)) throw JobCancelled(job)
        return try {
            val report = VideoRenders.MAPPER.readTree(worker.render(job.id, request(script)))
            val subtitles = validated(job, report, script)
            if (queue.cancelled(job)) {
                worker.delete(job.id)
                throw JobCancelled(job)
            }
            val result = records.asPlatform(job.organizationId, AUDIT_SOURCE) { store(job, requester, resource, script, report, subtitles) }
            worker.delete(job.id)
            result
        } catch (e: RenderWorkerException) {
            if (e.permanent) throw PermanentJobFailure(e.message ?: "The render failed", e)
            throw e
        }
    }

    // RF-MED-03: el informe contra el guion y los subtítulos descargados contra el informe y el guion. devuelve el VTT
    private suspend fun validated(
        job: Job,
        report: JsonNode,
        script: JsonNode,
    ): ByteArray {
        val expected = RenderedVideoCheck.expected(script)
        val video = described(report, "video")
        val problems = RenderedVideoCheck.video(report.get("ffprobe"), expected, video.bytes, properties.maxBytes.toBytes()).toMutableList()
        val described = described(report, "subtitulos")
        if (problems.isEmpty()) {
            val vtt = worker.fetch(job.id, SUBTITLES, MAX_SUBTITLE_BYTES)
            if (!sha256(vtt).equals(described.sha256, ignoreCase = true) || vtt.size.toLong() != described.bytes) {
                // se pudo cortar a mitad: otro intento lo vuelve a descargar (el render no se repite, el PUT es idempotente)
                throw RenderWorkerException("The downloaded $SUBTITLES does not match the render report", permanent = false)
            }
            problems += RenderedVideoCheck.subtitles(vtt.toString(Charsets.UTF_8), expected)
            if (problems.isEmpty()) return vtt
        }
        worker.delete(job.id)
        throw PermanentJobFailure("The rendered video does not match its script: ${problems.take(MAX_PROBLEMS).joinToString("; ")}")
    }

    private suspend fun store(
        job: Job,
        requester: UUID,
        resourceId: UUID,
        script: JsonNode,
        report: JsonNode,
        subtitles: ByteArray,
    ): String {
        val resource =
            try {
                records.get(RESOURCE, resourceId)
            } catch (e: NotFoundException) {
                throw PermanentJobFailure("The resource $resourceId no longer exists", e)
            }
        val type = resource.attributes[TYPE_FIELD]?.toString()
        if (type != VIDEO_TYPE) throw PermanentJobFailure("The resource $resourceId is now $type, not VIDEO")
        val video = described(report, "video")
        val base = "${resource.attributes[CODE]?.toString()?.takeIf { it.isNotBlank() } ?: "recurso"}-video-${job.id.toString().take(8)}"
        // dentro de asPlatform: solo la escritura de este mismo bloque puede adjuntarlos (ADR-068 de Wasichai). el MP4 pasa
        // del trabajador a wasichai-files sin otra copia; wasichai-files lo detecta como octet-stream (ADR-0040 §4)
        val mp4 = files.stage(VERSION, VIDEO_FIELD, FileUpload("$base.mp4", OCTET_STREAM, worker.stream(job.id, VIDEO, properties.maxBytes.toBytes())))
        if (!mp4.sha256.equals(video.sha256, ignoreCase = true)) {
            throw RenderWorkerException("The stored $VIDEO does not match the render report (sha256 ${video.sha256})", permanent = false)
        }
        val vtt = files.stage(ARTIFACT, FILE, FileUpload("$base.vtt", VTT_TYPE, Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(subtitles))))
        if (!vtt.sha256.equals(
                sha256(subtitles),
                ignoreCase = true,
            )
        ) {
            throw RenderWorkerException("The stored $SUBTITLES does not match its hash", permanent = false)
        }

        val scriptHash = sha256(VideoRenders.MAPPER.writeValueAsBytes(script))
        val expected = RenderedVideoCheck.expected(script)
        val versions = report.get("versiones")
        val generatorVersion = "${versions?.get("remotion")?.asString() ?: "?"} (render-worker ${versions?.get("trabajador")?.asString() ?: "?"})"
        val parameters =
            VideoRenders.MAPPER.writeValueAsString(
                mapOf("resolucion" to "${expected.width}x${expected.height}", "fps" to expected.fps, "duracionSegundos" to expected.seconds),
            )
        val evidence =
            VideoRenders.MAPPER.writeValueAsString(
                mapOf(
                    "video" to video,
                    "subtitles" to WorkerFile(vtt.sha256, subtitles.size.toLong()),
                    "ffprobe" to report.get("ffprobe"),
                    "versions" to versions,
                ),
            )
        return transactions.executeAndAwait {
            val number = records.listAll(VERSION, mapOf(RESOURCE to resourceId.toString())).maxOfOrNull { numberOf(it.attributes[NUMBER]) }?.plus(1) ?: 1
            val version =
                records.create(
                    VERSION,
                    RecordRequest(
                        mapOf(
                            RESOURCE to resourceId.toString(),
                            NUMBER to number,
                            AUTHOR to requester.toString(),
                            STATE to DRAFT,
                            VIDEO_FIELD to mp4.id.toString(),
                        ),
                    ),
                )

            suspend fun artifact(
                format: String,
                sha256: String,
                file: String?,
            ) = records.create(
                ARTIFACT,
                RecordRequest(
                    mapOf(
                        VERSION to version.id,
                        FORMAT to format,
                        SHA256 to sha256,
                        SOURCE_HASH to scriptHash,
                        GENERATOR to GENERATOR_NAME,
                        GENERATOR_VERSION to generatorVersion,
                        JOB to job.id.toString(),
                        PARAMETERS to parameters,
                        REPORT to evidence,
                    ) + listOfNotNull(file?.let { FILE to it }),
                ),
            )
            artifact(MP4, mp4.sha256, null)
            artifact(VTT, vtt.sha256, vtt.id.toString())
            result(version.id, number, evidence)
        }
    }

    // lo que ya escribió este trabajo en un intento anterior que no llegó a cerrarse
    private suspend fun existing(job: UUID): String? {
        val artifact = records.listAll(ARTIFACT, mapOf(JOB to job.toString())).firstOrNull { it.attributes[FORMAT]?.toString() == MP4 } ?: return null
        val versionId = artifact.attributes.ref(VERSION) ?: return null
        val version = records.get(VERSION, UUID.fromString(versionId))
        return result(versionId, numberOf(version.attributes[NUMBER]), artifact.attributes[REPORT].toString())
    }

    // lo que queda en el trabajo: la versión creada y el informe con el que se validó
    private fun result(
        versionId: String,
        number: Int,
        evidence: String,
    ): String {
        val node = VideoRenders.MAPPER.readTree(evidence)
        return VideoRenders.MAPPER.writeValueAsString(
            mapOf(
                "versionId" to versionId,
                "versionNumber" to number,
                "video" to node.get("video"),
                "subtitles" to node.get("subtitles"),
                "ffprobe" to node.get("ffprobe"),
                "versions" to node.get("versions"),
            ),
        )
    }

    // lo que manda al trabajador: el guion y las imágenes que nombra. hasta que el guion de RF-MED-01 (#293) las tome de
    // los archivos del curso, un guion con imágenes no se encola (VideoRenderService), así que no hay ninguna
    private fun request(script: JsonNode): String = VideoRenders.MAPPER.writeValueAsString(mapOf("guion" to script, "recursos" to emptyMap<String, String>()))

    private fun described(
        report: JsonNode,
        name: String,
    ): WorkerFile {
        val node = report.get(name) ?: throw PermanentJobFailure("The render report does not describe $name")
        val sha256 = node.get("sha256")?.asString() ?: throw PermanentJobFailure("The render report gives no sha256 for $name")
        val bytes = node.get("bytes")?.asLong() ?: throw PermanentJobFailure("The render report gives no size for $name")
        return WorkerFile(sha256, bytes)
    }

    private companion object {
        const val AUDIT_SOURCE = "job:video-render"
        const val VIDEO = "video.mp4"
        const val SUBTITLES = "subtitulos.vtt"
        const val MAX_SUBTITLE_BYTES = 1024L * 1024
        const val MAX_PROBLEMS = 5
        const val OCTET_STREAM = "application/octet-stream"
        const val VTT_TYPE = "text/vtt"
        const val GENERATOR_NAME = "remotion"

        const val RESOURCE = "recurso"
        const val VERSION = "version_recurso"
        const val ARTIFACT = "artefacto_generado"
        const val TYPE_FIELD = "tipo"
        const val VIDEO_TYPE = "VIDEO"
        const val CODE = "codigo"
        const val NUMBER = "numero"
        const val AUTHOR = "autor_id"
        const val STATE = "estado"
        const val DRAFT = "BORRADOR"
        const val VIDEO_FIELD = "video"
        const val FILE = "archivo"
        const val FORMAT = "formato"
        const val MP4 = "MP4"
        const val VTT = "VTT"
        const val SHA256 = "sha256"
        const val SOURCE_HASH = "hash_fuente"
        const val GENERATOR = "generador"
        const val GENERATOR_VERSION = "version_generador"
        const val JOB = "trabajo_id"
        const val PARAMETERS = "parametros"
        const val REPORT = "informe"

        fun numberOf(value: Any?): Int = (value as? Number)?.toInt() ?: value?.toString()?.toBigDecimalOrNull()?.toInt() ?: 0

        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
