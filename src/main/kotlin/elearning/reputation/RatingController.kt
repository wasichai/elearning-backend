package elearning.reputation

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import wasichai.core.data.IdempotencyKeys
import wasichai.core.identity.CurrentUser
import java.util.UUID

// las valoraciones del estudiante (ADR-0038), con sesión: desde una matrícula propia y sobre las suyas
@RestController
@RequestMapping("/api/lms")
class RatingController(
    private val ratings: RatingService,
    private val currentUser: CurrentUser,
    private val keys: IdempotencyKeys,
    private val json: JsonMapper,
) {
    // a quién puede valorar desde la matrícula y con qué dimensiones
    @GetMapping("/classroom/enrollments/{enrollmentId}/ratings")
    suspend fun eligibility(
        @PathVariable enrollmentId: UUID,
    ): RatingEligibility = ratings.eligibility(enrollmentId)

    // 201. con Idempotency-Key (ADR-058 de Wasichai), repetirla devuelve la misma respuesta sin valorar otra vez
    @PostMapping("/classroom/enrollments/{enrollmentId}/ratings")
    suspend fun rate(
        @PathVariable enrollmentId: UUID,
        @RequestBody body: RateRequest,
        @RequestHeader(IdempotencyKeys.HEADER, required = false) idempotencyKey: String?,
    ): ResponseEntity<Any> {
        if (idempotencyKey == null) return ResponseEntity.status(HttpStatus.CREATED).body(ratings.rate(enrollmentId, body))
        IdempotencyKeys.requireValid(idempotencyKey)
        val user = currentUser.require()
        val hash = keys.requestHash("POST", "/api/lms/classroom/enrollments/$enrollmentId/ratings", body)
        val outcome =
            keys.once(user.organizationId, user.userId, idempotencyKey, hash) {
                IdempotencyKeys.Answer(HttpStatus.CREATED.value(), json.writeValueAsString(ratings.rate(enrollmentId, body)))
            }
        val answer = ResponseEntity.status(outcome.answer.status).contentType(MediaType.APPLICATION_JSON)
        if (outcome.replayed) answer.header(IdempotencyKeys.REPLAYED, "true")
        return answer.body(outcome.answer.body)
    }

    @GetMapping("/ratings/mine")
    suspend fun mine(): List<RatingView> = ratings.mine()

    @PutMapping("/ratings/{ratingId}")
    suspend fun update(
        @PathVariable ratingId: UUID,
        @RequestBody body: RatingUpdateRequest,
    ): RatingView = ratings.update(ratingId, body)

    @DeleteMapping("/ratings/{ratingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable ratingId: UUID,
    ) = ratings.delete(ratingId)
}
