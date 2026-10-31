package elearning.authoring

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// el estudio del creador (ADR-0018, docs/api/lms.md)
@RestController
@RequestMapping("/api/lms/studio")
class StudioController(
    private val versions: NewVersionService,
) {
    @PostMapping("/courses/{courseId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun newVersion(
        @PathVariable courseId: UUID,
        @RequestBody body: NewVersionRequest,
    ): NewVersionResult = versions.copy(courseId, body)
}
