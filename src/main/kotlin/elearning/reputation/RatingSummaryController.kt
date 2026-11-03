package elearning.reputation

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

// lo que ve el valorado (ADR-0038 §8–§11) y el criterio público con que se calcula
@RestController
class RatingSummaryController(
    private val summaries: RatingSummaries,
) {
    // el resumen del docente en una edición que tiene asignada; otra edición, 404
    @GetMapping("/api/lms/teaching/offerings/{code}/ratings")
    suspend fun teacher(
        @PathVariable code: String,
    ): TeacherRatingSummary = summaries.teacher(code)

    // los de las versiones de recurso de las que la persona es autora
    @GetMapping("/api/lms/ratings/resources")
    suspend fun resources(): List<ResourceRatingSummary> = summaries.resources()

    // sin sesión: cada cifra enlaza aquí (RF-REP-06)
    @GetMapping(RatingCriterion.PATH)
    fun criterion(): RatingCriterionView = summaries.criterion()
}
