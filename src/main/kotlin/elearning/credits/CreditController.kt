package elearning.credits

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// aprobación de créditos por una autoridad académica (RF-CRE-08, ADR-0035, docs/api/lms.md)
@RestController
@RequestMapping("/api/lms/credits")
class CreditController(
    private val credits: CreditApprovalService,
) {
    @GetMapping("/versions/{id}/proposal")
    suspend fun proposal(
        @PathVariable id: UUID,
    ): VersionCredits = credits.credits(id)

    @GetMapping("/versions/{id}/approvals")
    suspend fun approvals(
        @PathVariable id: UUID,
    ): List<CreditApprovalView> = credits.approvals(id)

    @PostMapping("/versions/{id}/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun approve(
        @PathVariable id: UUID,
        @RequestBody body: CreditApprovalRequest,
    ): CreditApprovalView = credits.approve(id, body)

    @PostMapping("/approvals/{id}/revoke")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun revoke(
        @PathVariable id: UUID,
        @RequestBody body: CreditRevocationRequest,
    ): CreditApprovalView = credits.revoke(id, body)
}
