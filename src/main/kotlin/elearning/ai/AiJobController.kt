package elearning.ai

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// los trabajos de IA de quien los pidió (RF-IA-04, docs/api/lms.md): leer su estado y cancelarlo. Se encolan desde el paso
// que los ofrece (AiJobs.enqueue), no aquí
@RestController
@RequestMapping("/api/lms/ai/jobs")
class AiJobController(
    private val jobs: AiJobs,
) {
    @GetMapping("/{id}")
    suspend fun find(
        @PathVariable id: UUID,
    ): AiJob = jobs.find(id)

    @PostMapping("/{id}/cancel")
    suspend fun cancel(
        @PathVariable id: UUID,
    ): AiJob = jobs.cancel(id)
}
