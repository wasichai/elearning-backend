package elearning.media

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// generación de artefactos desde una fuente (ADR-0018 §7, docs/api/lms.md)
@RestController
@RequestMapping("/api/lms/studio")
class RenderController(
    private val renders: RenderService,
) {
    @PostMapping("/sources/{id}/render")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun render(
        @PathVariable id: UUID,
    ): RenderJob = renders.request(id)

    @GetMapping("/renders/{id}")
    suspend fun job(
        @PathVariable id: UUID,
    ): RenderJob = renders.find(id)
}
