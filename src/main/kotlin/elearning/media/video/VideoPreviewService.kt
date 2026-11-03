package elearning.media.video

import org.springframework.core.io.buffer.DataBuffer
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.files.FileService
import java.util.UUID

// el vídeo validado de una versión, para reproducirlo en el estudio
class VideoPreview(
    val name: String,
    val size: Long?,
    val content: Flux<DataBuffer>,
)

// la previsualización de un vídeo renderizado (RF-MED-03, ADR-0040 §6): el propio MP4 de la versión, antes de que nadie
// decida su revisión. La ve quien decide (APROBAR) o publica (PUBLICAR) versiones de recurso, y su autor; nadie más,
// aunque lea la versión (un docente con VER_TODAS, otro creador): 403. Una versión que no ve es 404, como una sin vídeo.
// Los bytes los da wasichai-files, que comprueba su sha256 al vuelo (ADR-074 de Wasichai); aquí solo se sirven como
// video/mp4, que es lo que son: el vídeo solo lo escribe el render tras validarlo con ffprobe (RenderedVideoGuard)
@Service
class VideoPreviewService(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val metadata: MetadataService,
    private val files: FileService,
) {
    suspend fun open(versionId: UUID): VideoPreview {
        val user = currentUser.require()
        // como quien llama: una versión que no ve no existe
        val version = records.get(RenderedVideoGuard.VERSION, versionId).attributes
        val objectId = metadata.definitionOf(RenderedVideoGuard.VERSION).obj.id
        val author = version[AUTHOR]?.toString() == user.userId.toString()
        if (!author && DECIDERS.none { currentUser.hasPermission(user, it, objectId) }) {
            throw ForbiddenException("Only the author and who reviews or publishes resource versions preview their video")
        }
        val video = version[RenderedVideoGuard.VIDEO] as? Map<*, *> ?: throw NotFoundException("Resource version $versionId has no rendered video")
        val download = files.open(RenderedVideoGuard.VERSION, versionId, RenderedVideoGuard.VIDEO)
        return VideoPreview(
            name = video["name"]?.toString()?.takeIf { it.isNotBlank() } ?: "video-$versionId.mp4",
            size = (video["size"] as? Number)?.toLong(),
            content = download.content,
        )
    }

    private companion object {
        const val AUTHOR = "autor_id"
        val DECIDERS = listOf("APROBAR", "PUBLICAR")
    }
}
