package elearning.media.video

import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import java.util.UUID

// render de vídeo desde un guion y previsualización del vídeo validado (ADR-0040, RF-MED-02/03, docs/api/lms.md)
@RestController
@RequestMapping("/api/lms/studio")
class VideoRenderController(
    private val renders: VideoRenderService,
    private val previews: VideoPreviewService,
) {
    @PostMapping("/resources/{id}/video-renders")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun render(
        @PathVariable id: UUID,
        @RequestBody body: VideoRenderRequest,
    ): VideoRenderJob = renders.request(id, body)

    @GetMapping("/video-renders/{id}")
    suspend fun job(
        @PathVariable id: UUID,
    ): VideoRenderJob = renders.find(id)

    // el MP4 validado de una versión, para previsualizarlo antes de decidir (RF-MED-03, ADR-0040 §6). Sin Range todavía:
    // el reproductor lo baja entero (pedido a Wasichai, ADR-0040 §4)
    @GetMapping("/resource-versions/{id}/video")
    suspend fun preview(
        @PathVariable id: UUID,
    ): ResponseEntity<Flux<DataBuffer>> {
        val video = previews.open(id)
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType("video/mp4"))
            .headers { headers ->
                headers.contentDisposition = ContentDisposition.inline().filename(video.name, Charsets.UTF_8).build()
                video.size?.let { headers.contentLength = it }
                headers.set("X-Content-Type-Options", "nosniff")
            }.cacheControl(CacheControl.noStore().cachePrivate())
            .body(video.content)
    }
}
