package elearning.media.video

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// render de vídeo desde un guion (ADR-0040, RF-MED-02, docs/api/lms.md)
@RestController
@RequestMapping("/api/lms/studio")
class VideoRenderController(
    private val renders: VideoRenderService,
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
}
