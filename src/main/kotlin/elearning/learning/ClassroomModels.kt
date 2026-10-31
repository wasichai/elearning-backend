package elearning.learning

import com.fasterxml.jackson.annotation.JsonUnwrapped
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
    val certificateCode: String?,
)

data class ClassroomView(
    @get:JsonUnwrapped val enrollment: EnrollmentView,
    val units: List<UnitView>,
)

data class UnitView(
    val id: String,
    val order: Int,
    val title: String,
    val description: String?,
    val progress: ProgressSummary,
    val lessons: List<LessonView>,
)

data class LessonView(
    val id: String,
    val order: Int,
    val title: String,
    val type: String,
    val durationMinutes: Int?,
    val summary: String?,
    val completion: String,
    val progress: LessonProgress,
    val resources: List<ResourceView>,
    val assessment: AssessmentSummary?,
)

data class AssessmentBest(
    val percent: Int?,
    val passed: Boolean?,
    val pending: Boolean,
)

data class AssessmentSummary(
    val id: String,
    val title: String,
    val maxAttempts: Int,
    val timeLimitMinutes: Int?,
    val passingPercent: Int,
    val attemptsUsed: Int,
    val openAttemptId: String?,
    val best: AssessmentBest?,
)

data class ResourceView(
    val id: String,
    val title: String,
    val type: String,
    val version: Int,
    val file: FileView?,
)

// la descarga es la de wasichai-files, con la misma sesión y el mismo alcance que el registro
data class FileView(
    val name: String,
    val contentType: String,
    val size: Long,
    val url: String,
)

// el progreso de una lección: sin registro es NO_INICIADA
data class LessonProgress(
    val status: String,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val evidence: String?,
)

data class LearningEventRequest(
    val type: String? = null,
    val clientEventId: String? = null,
)

data class EnrollRequest(
    val offering: String? = null,
)
