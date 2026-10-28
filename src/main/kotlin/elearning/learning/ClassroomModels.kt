package elearning.learning

import java.time.Instant
import java.time.LocalDate

// el contrato del aula (docs/api/lms.md, ADR-0012). nombres en inglés; valores de enumeración como el modelo

data class CourseRef(
    val slug: String,
    val code: String,
    val title: String,
)

data class OfferingRef(
    val code: String,
    val status: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val timeZone: String,
)

data class ProgressSummary(
    val completedLessons: Int,
    val totalLessons: Int,
    val percent: Int,
)

data class NextLesson(
    val id: String,
    val title: String,
    val unitOrder: Int,
    val lessonOrder: Int,
)

data class EnrollmentView(
    val id: String,
    val status: String,
    val enrolledAt: Instant?,
    val course: CourseRef,
    val version: Int,
    val offering: OfferingRef,
    val progress: ProgressSummary,
    val nextLesson: NextLesson?,
)

data class EnrollRequest(
    val offering: String? = null,
)
