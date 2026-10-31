package elearning.learning

import java.time.Instant

// el contrato de las sesiones (docs/api/lms.md, ADR-0016)

data class SessionView(
    val id: String,
    val title: String,
    val type: String,
    val modality: String,
    val start: Instant,
    val end: Instant,
    val timeZone: String,
    val status: String,
    val link: String?,
    val location: String?,
    val lessonId: String?,
    val originalSessionId: String?,
    val attendance: String?,
)

data class RescheduleRequest(
    val date: String? = null,
    val localTime: String? = null,
    val reason: String? = null,
)

data class CancelRequest(
    val reason: String? = null,
)

data class RecurrenceRequest(
    val pattern: String? = null,
    val days: List<String>? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val localTime: String? = null,
)

data class CreateSessionsRequest(
    val title: String? = null,
    val type: String? = null,
    val modality: String? = null,
    val durationMinutes: Int? = null,
    val link: String? = null,
    val location: String? = null,
    val lessonId: String? = null,
    val originalSessionId: String? = null,
    val recurrence: RecurrenceRequest? = null,
)
