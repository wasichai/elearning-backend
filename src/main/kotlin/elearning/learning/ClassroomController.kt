package elearning.learning

import elearning.enrollment.EnrollmentService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ValidationException

// el aula del estudiante (ADR-0012), con sesión: la cadena de Wasichai protege /api/lms/** salvo lo público
@RestController
@RequestMapping("/api/lms")
class ClassroomController(
    private val enrollments: EnrollmentService,
    private val reader: ClassroomReader,
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

    companion object {
        const val MAX_CODE = 100
    }
}
