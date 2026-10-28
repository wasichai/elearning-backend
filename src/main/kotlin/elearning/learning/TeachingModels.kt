package elearning.learning

import java.time.LocalDate

// el contrato del espacio docente (docs/api/lms.md, ADR-0016)

data class TeachingOffering(
    val code: String,
    val status: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val timeZone: String,
    val course: CourseRef,
    val enrolled: Int,
    val pendingAttempts: Int,
)

data class AttendanceSummary(
    val attended: Int,
    val total: Int,
)

data class RosterAssessment(
    val lessonId: String,
    val title: String,
    val bestPercent: Int?,
    val passed: Boolean?,
    val pending: Boolean,
)

data class RosterRow(
    val enrollmentId: String,
    val email: String?,
    val status: String,
    val progress: ProgressSummary,
    val attendance: AttendanceSummary,
    val assessments: List<RosterAssessment>,
)
