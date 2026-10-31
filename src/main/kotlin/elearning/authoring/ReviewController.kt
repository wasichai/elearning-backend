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

// la bandeja del revisor (ADR-0018 §2, docs/api/lms.md)
@RestController
@RequestMapping("/api/lms/review")
class ReviewController(
    private val review: ReviewService,
) {
    @GetMapping("/versions")
    suspend fun inbox(): List<StudioVersion> = review.inbox()

    @GetMapping("/versions/{id}")
    suspend fun detail(
        @PathVariable id: UUID,
    ): StudioVersionDetail = review.detail(id)

    // contra la versión anterior del mismo curso (ADR-0018 §3)
    @GetMapping("/versions/{id}/diff")
    suspend fun diff(
        @PathVariable id: UUID,
    ): VersionDiff = review.diff(id)

    @PostMapping("/versions/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun comment(
        @PathVariable id: UUID,
        @RequestBody body: CommentRequest,
    ): ReviewComment = review.comment(id, body)

    @PostMapping("/comments/{id}/resolve")
    suspend fun resolve(
        @PathVariable id: UUID,
    ): ReviewComment = review.resolve(id)

    @PostMapping("/versions/{id}/decision")
    suspend fun decide(
        @PathVariable id: UUID,
        @RequestBody body: DecisionRequest,
    ): StudioVersion = review.decide(id, body)

    @PostMapping("/versions/{id}/publish")
    suspend fun publish(
        @PathVariable id: UUID,
    ): StudioVersion = review.publish(id)
}
