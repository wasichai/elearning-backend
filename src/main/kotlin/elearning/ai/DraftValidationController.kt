package elearning.ai

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// la validación por código de un borrador (RF-IA-05, docs/api/lms.md)
@RestController
@RequestMapping("/api/lms/ai/validations")
class DraftValidationController(
    private val validations: DraftValidations,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun start(
        @RequestBody body: ValidationRequest,
    ): ValidationJob = validations.start(body)

    @GetMapping("/jobs/{id}")
    suspend fun job(
        @PathVariable id: UUID,
    ): ValidationJob = validations.job(id)

    // el último informe de la versión
    @GetMapping
    suspend fun latest(
        @RequestParam versionId: UUID,
    ): DraftValidationView = validations.latest(versionId)
}
