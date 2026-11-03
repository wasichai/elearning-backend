package elearning.reputation

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// moderación (MODERAR), denuncias del valorado (DENUNCIAR) y apelación del autor (ADR-0038 §13–§19), con sesión
@RestController
class RatingModerationController(
    private val moderation: RatingModeration,
) {
    @GetMapping("/api/lms/moderation/ratings")
    suspend fun queue(): List<ModerationItem> = moderation.queue()

    @PostMapping("/api/lms/moderation/ratings/{ratingId}/decision")
    suspend fun decide(
        @PathVariable ratingId: UUID,
        @RequestBody body: ModerationDecision,
    ): ModerationItem = moderation.decide(ratingId, body)

    @PostMapping("/api/lms/moderation/ratings/{ratingId}/appeal-decision")
    suspend fun decideAppeal(
        @PathVariable ratingId: UUID,
        @RequestBody body: AppealDecision,
    ): ModerationItem = moderation.decideAppeal(ratingId, body)

    @PostMapping("/api/lms/ratings/{ratingId}/appeal")
    suspend fun appeal(
        @PathVariable ratingId: UUID,
        @RequestBody body: AppealRequest,
    ): RatingAppealView = moderation.appeal(ratingId, body)

    // 201: la denuncia queda abierta para quien modera; no oculta nada por sí sola
    @PostMapping("/api/lms/ratings/comments/{token}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun report(
        @PathVariable token: UUID,
        @RequestBody body: ReportRequest,
    ): ReportView = moderation.report(token, body)
}
