package elearning.assessment

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.PageRequest
import java.util.UUID

// la corrección en el espacio docente (docs/api/lms.md): lo decide la asignación, no el rol
@RestController
@RequestMapping("/api/lms/teaching")
class GradingController(
    private val grading: GradingService,
) {
    // page y size opcionales, como en la API de registros: sin ninguno, la cola entera
    @GetMapping("/offerings/{code}/attempts")
    suspend fun queue(
        @PathVariable code: String,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): List<PendingAttempt> = grading.queue(code, status, if (page == null && size == null) null else PageRequest.of(page, size))

    @GetMapping("/attempts/{id}")
    suspend fun read(
        @PathVariable id: UUID,
    ): TeacherAttemptView = grading.read(id)

    @PostMapping("/attempts/{id}/grades")
    suspend fun grade(
        @PathVariable id: UUID,
        @RequestBody body: GradeRequest,
    ): TeacherAttemptView = grading.grade(id, body)
}
