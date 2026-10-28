package elearning.learning

import elearning.enrollment.EnrollmentService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ValidationException
import java.util.UUID

// el aula del estudiante (ADR-0012), con sesión: la cadena de Wasichai protege /api/lms/** salvo lo público
@RestController
@RequestMapping("/api/lms")
class ClassroomController(
    private val enrollments: EnrollmentService,
    private val reader: ClassroomReader,
    private val progress: LessonProgressService,
) {
    // 201 si se creó, 200 si ya existía: repetir la matrícula devuelve la misma
    @PostMapping("/enrollments")
    suspend fun enroll(
        @RequestBody body: EnrollRequest,
    ): ResponseEntity<EnrollmentView> {
        val code =
            body.offering?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_CODE }
                ?: throw ValidationException("Invalid enrollment", "offering", "must be an offering code")
        val result = enrollments.enroll(code)
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(reader.view(result.enrollment))
    }

    // mis cursos: mis matrículas activas, la de inicio más próximo primero
    @GetMapping("/classroom/enrollments")
    suspend fun mine(): List<EnrollmentView> = reader.mine()

    // el aula de una matrícula propia; ajena o inexistente, 404
    @GetMapping("/classroom/enrollments/{enrollmentId}")
    suspend fun classroom(
        @PathVariable enrollmentId: UUID,
    ): ClassroomView = reader.classroom(enrollmentId)

    // un evento de aprendizaje → el progreso de la lección. idempotente por clientEventId
    @PostMapping("/classroom/enrollments/{enrollmentId}/lessons/{lessonId}/events")
    suspend fun event(
        @PathVariable enrollmentId: UUID,
        @PathVariable lessonId: UUID,
        @RequestBody body: LearningEventRequest,
    ): LessonProgress {
        val type =
            LearningEventType.entries.firstOrNull { it.name == body.type }
                ?: throw ValidationException("Invalid event", "type", "must be one of ${LearningEventType.entries.joinToString()}")
        val clientEventId =
            body.clientEventId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw ValidationException("Invalid event", "clientEventId", "must be a UUID")
        return progress.record(enrollmentId, lessonId, type, clientEventId)
    }

    companion object {
        const val MAX_CODE = 100
    }
}
