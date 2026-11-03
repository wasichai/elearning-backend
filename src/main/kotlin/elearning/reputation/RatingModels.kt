package elearning.reputation

import java.time.Instant
import java.time.LocalDate

// lo que se pide en cada dimensión: su clave y su nombre
data class RatingDimension(
    val key: String,
    val name: String,
)

// un docente o un recurso que la matrícula puede valorar. reason, cuando no puede: CLOSED (ventana cerrada), ENROLLMENT
// (matrícula ni activa ni finalizada), PROGRESS (progreso bajo el mínimo), LESSON (ninguna lección que lo usa completada),
// OWN (lo suyo: conflicto de interés) o RATED (ya lo valoró: se edita la suya, ratingId)
data class RatingTarget(
    val type: String,
    val id: String,
    val name: String?,
    val eligible: Boolean,
    val reason: String?,
    val ratingId: String?,
)

data class RatingEligibility(
    val enrollmentId: String,
    val open: Boolean,
    val closesOn: LocalDate?,
    val progressPercent: Int,
    val minProgress: Int,
    val teachers: List<RatingTarget>,
    val resources: List<RatingTarget>,
    // por destino (DOCENCIA, RECURSO): las dimensiones activas, en orden
    val dimensions: Map<String, List<RatingDimension>>,
)

// una valoración propia. moderation: PENDIENTE, PUBLICADA u OCULTA; null sin comentario
data class RatingView(
    val id: String,
    val enrollmentId: String?,
    val type: String?,
    val targetId: String?,
    val targetName: String?,
    val scores: Map<String, Int>,
    val comment: String?,
    val moderation: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

// crear: type DOCENCIA (targetId, la asignación) o RECURSO (targetId, la versión de recurso). scores por clave de dimensión
data class RateRequest(
    val type: String? = null,
    val targetId: String? = null,
    val scores: Map<String, Int>? = null,
    val comment: String? = null,
)

// editar: los puntajes enviados reemplazan a los guardados; el comentario, también (null o vacío lo quita)
data class RatingUpdateRequest(
    val scores: Map<String, Int>? = null,
    val comment: String? = null,
)
