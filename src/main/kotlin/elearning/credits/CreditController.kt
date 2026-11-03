package elearning.credits

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

// las políticas de crédito en datos (RF-CRE-07, ADR-0036, docs/api/lms.md). el borrador se escribe por la API de registros
@RestController
@RequestMapping("/api/lms/credits/policies")
class CreditPolicyController(
    private val policies: CreditPolicyService,
) {
    @GetMapping
    suspend fun list(
        @RequestParam(required = false) jurisdiction: String?,
        @RequestParam(required = false) system: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) program: String?,
    ): List<CreditPolicyView> = policies.list(jurisdiction, system, status, program)

    @GetMapping("/{id}")
    suspend fun get(
        @PathVariable id: UUID,
    ): CreditPolicyView = policies.get(id)

    @PostMapping("/{id}/publish")
    suspend fun publish(
        @PathVariable id: UUID,
    ): CreditPolicyView = policies.publish(id)

    @PostMapping("/{id}/retire")
    suspend fun retire(
        @PathVariable id: UUID,
    ): CreditPolicyView = policies.retire(id)

    // la versión siguiente de la misma clave, en borrador; la de origen no cambia
    @PostMapping("/{id}/new-version")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun newVersion(
        @PathVariable id: UUID,
    ): CreditPolicyView = policies.newVersion(id)
}
