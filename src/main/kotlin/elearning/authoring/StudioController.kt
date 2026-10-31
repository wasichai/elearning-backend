package elearning.authoring

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
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
    private val reader: StudioReader,
    private val differ: VersionDiffer,
) {
    @GetMapping("/versions")
    suspend fun mine(): List<StudioVersion> = reader.mine()

    @GetMapping("/versions/{id}")
    suspend fun detail(
        @PathVariable id: UUID,
    ): StudioVersionDetail = reader.detail(id)

    // contra la versión anterior del mismo curso (ADR-0018 §3)
    @GetMapping("/versions/{id}/diff")
    suspend fun diff(
        @PathVariable id: UUID,
    ): VersionDiff = differ.againstPrevious(reader.own(id))

    @PostMapping("/versions/{id}/submit")
    suspend fun submit(
        @PathVariable id: UUID,
    ): StudioVersion = reader.submit(id)

    // responde con el mismo detalle que GET /versions/{id}
    @PostMapping("/courses/{courseId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun newVersion(
        @PathVariable courseId: UUID,
        @RequestBody body: NewVersionRequest,
    ): StudioVersionDetail = reader.detail(versions.copy(courseId, body))
}
