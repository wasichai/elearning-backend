package elearning.catalog

import elearning.common.LmsProperties
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.readValue
import wasichai.core.common.NotFoundException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

// lo que la ruta pública lee de la proyección. lo que depende del día (edición próxima, inscripción abierta) se calcula
// aquí, en la zona horaria de cada edición, y no al proyectar
@Component
class CatalogReader(
    private val properties: LmsProperties,
    private val repository: CatalogRepository,
    private val clock: Clock,
) {
    suspend fun page(query: CatalogQuery): CoursePage =
        coroutineScope {
            // consultas independientes: en paralelo, no una tras otra
            val page = async { repository.page(query) }
            val categories = async { repository.categoryFacets(query) }
            val knowledge = async { repository.knowledgeFacets(query) }
            val (rows, total) = page.await()
            build(query, rows, total, Facets(categories.await(), knowledge.await()))
        }

    private fun build(
        query: CatalogQuery,
        rows: List<String>,
        total: Long,
        facets: Facets,
    ): CoursePage =
        CoursePage(
            content = rows.map { summary(parse(it)) },
            page = query.page,
            size = query.size,
            totalElements = total,
            totalPages = ceil(total.toDouble() / query.size).toInt(),
            facets = facets,
        )

    suspend fun detail(slug: String): CourseDetail {
        val row = repository.detail(properties.public.organization, slug) ?: throw NotFoundException(NOT_FOUND)
        val detail = parse(row)
        return detail.copy(offerings = OfferingCalendar.current(detail.offerings, clock))
    }

    val organization: String get() = properties.public.organization

    private fun parse(json: String): CourseDetail = PublishedCourse.MAPPER.readValue(json)

    private fun summary(d: CourseDetail) =
        CourseSummary(
            slug = d.slug,
            code = d.code,
            title = d.title,
            summary = d.summary,
            language = d.language,
            level = d.level,
            modality = d.modality,
            category = d.category,
            weeks = d.weeks,
            workload = WorkloadSummary(d.workload.totalHours, d.workload.contactHours, d.workload.autonomousHours),
            nextOffering =
                OfferingCalendar.current(d.offerings, clock).firstOrNull()?.let {
                    NextOffering(it.code, it.status, it.startDate, it.endDate, it.timeZone, it.enrollmentOpen, it.enrollmentStatus)
                },
            illustrative = d.illustrative,
        )

    companion object {
        const val NOT_FOUND = "Curso no encontrado"
    }
}

// lo que depende del día, en la zona horaria de cada edición (con su horario de verano): qué ediciones siguen vigentes
// y el estado de su inscripción hoy. abierta solo si además le quedan plazas: una completa no admite matrículas, así que
// enrollmentOpen (y su filtro) dice false. puro: el reloj entra como argumento
object OfferingCalendar {
    const val OPEN = "INSCRIPCION_ABIERTA"

    fun current(
        offerings: List<OfferingView>,
        clock: Clock,
    ): List<OfferingView> =
        offerings
            .map { o -> o to LocalDate.now(clock.withZone(ZoneId.of(o.timeZone))) }
            .filter { (o, today) -> !o.endDate.isBefore(today) }
            .map { (o, today) -> status(o, today).let { o.copy(enrollmentOpen = it == EnrollmentStatus.OPEN, enrollmentStatus = it) } }
            .sortedWith(compareBy({ it.startDate }, { it.code }))

    private fun status(
        o: OfferingView,
        today: LocalDate,
    ): EnrollmentStatus {
        val window = (o.enrollmentFrom == null || !today.isBefore(o.enrollmentFrom)) && (o.enrollmentUntil == null || !today.isAfter(o.enrollmentUntil))
        return when {
            o.status != OPEN || !window -> EnrollmentStatus.CLOSED
            o.full -> EnrollmentStatus.FULL
            else -> EnrollmentStatus.OPEN
        }
    }
}
