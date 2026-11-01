package elearning.catalog

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// el contrato público del catálogo (docs/api/lms.md). nombres en inglés; valores de enumeración tal como el modelo.
// solo lo que se puede publicar: nunca un usuario_id, un correo ni un borrador

data class CategoryRef(
    val slug: String,
    val name: String,
)

data class WorkloadSummary(
    val totalHours: BigDecimal,
    val contactHours: BigDecimal,
    val autonomousHours: BigDecimal,
)

data class WeeklyHoursView(
    val theory: BigDecimal,
    val practice: BigDecimal,
    val supervised: BigDecimal,
    val autonomous: BigDecimal,
)

data class WorkloadView(
    val weeks: Int,
    val weekly: WeeklyHoursView,
    val theoryHours: BigDecimal,
    val practiceHours: BigDecimal,
    val supervisedHours: BigDecimal,
    val autonomousHours: BigDecimal,
    val contactHours: BigDecimal,
    val directedHours: BigDecimal,
    val totalHours: BigDecimal,
)

data class CreditProposalView(
    val system: String,
    val policyId: String,
    val policyVersion: String,
    val method: String,
    val parameters: Map<String, String>,
    val source: String,
    val totalHours: BigDecimal,
    val calculated: BigDecimal,
    val proposed: BigDecimal,
    val warnings: List<String>,
    val status: String,
)

data class CreditsView(
    val system: String?,
    val approved: BigDecimal?,
    val proposal: CreditProposalView?,
)

data class VersionView(
    val number: Int,
    val publishedAt: Instant?,
)

data class LessonView(
    val order: Int,
    val title: String,
    val type: String,
    val durationMinutes: Int?,
    val summary: String?,
    val source: String?,
)

data class UnitView(
    val order: Int,
    val title: String,
    val description: String?,
    val objectives: List<String>,
    val estimatedHours: BigDecimal?,
    val lessons: List<LessonView>,
)

data class OfferingTeacher(
    val name: String,
    val professionalTitle: String?,
    val role: String,
)

// el estado de la matrícula de una edición hoy (RF-CAT-09): abierta, cerrada (fuera de estado o de ventana) o completa
// (abierta por calendario, pero sin plazas)
enum class EnrollmentStatus {
    OPEN,
    CLOSED,
    FULL,
}

data class OfferingView(
    val code: String,
    val status: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val timeZone: String,
    val enrollmentFrom: LocalDate?,
    val enrollmentUntil: LocalDate?,
    val enrollmentOpen: Boolean,
    val capacity: Int?,
    val schedule: String?,
    val teachers: List<OfferingTeacher>,
    // cupo agotado cuando se proyectó (lms.ocupacion_edicion): un sí o un no, nunca cuántos ni quiénes. los valores por
    // defecto leen las filas proyectadas antes de que existieran
    val full: Boolean = false,
    // lo decide la lectura, como enrollmentOpen (OfferingCalendar)
    val enrollmentStatus: EnrollmentStatus = EnrollmentStatus.CLOSED,
)

data class TeacherView(
    val name: String,
    val professionalTitle: String?,
    val bio: String?,
    val specialties: String?,
)

data class CourseDetail(
    val slug: String,
    val code: String,
    val title: String,
    val summary: String?,
    val language: String,
    val level: String,
    val modality: String,
    val category: CategoryRef?,
    val weeks: Int,
    val illustrative: Boolean,
    val prerequisiteKnowledge: String?,
    val version: VersionView,
    val objectives: List<String>,
    val learningOutcomes: List<String>,
    val requirements: String?,
    val assessment: String?,
    val certification: String?,
    val sources: List<String>,
    val workload: WorkloadView,
    val credits: CreditsView,
    val units: List<UnitView>,
    val offerings: List<OfferingView>,
    val teachers: List<TeacherView>,
)

data class NextOffering(
    val code: String,
    val status: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val timeZone: String,
    val enrollmentOpen: Boolean,
    val enrollmentStatus: EnrollmentStatus,
)

data class CourseSummary(
    val slug: String,
    val code: String,
    val title: String,
    val summary: String?,
    val language: String,
    val level: String,
    val modality: String,
    val category: CategoryRef?,
    val weeks: Int,
    val workload: WorkloadSummary,
    val nextOffering: NextOffering?,
    val illustrative: Boolean,
)

data class CategoryFacet(
    val slug: String,
    val name: String,
    val count: Long,
)

data class Facets(
    val categories: List<CategoryFacet>,
)

data class CoursePage(
    val content: List<CourseSummary>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val facets: Facets,
)
