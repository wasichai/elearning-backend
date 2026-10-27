package elearning.catalog

import elearning.common.LmsProperties
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
    suspend fun page(query: CatalogQuery): CoursePage {
        val (rows, total) = repository.page(query)
        return CoursePage(
            content = rows.map { summary(parse(it)) },
            page = query.page,
            size = query.size,
            totalElements = total,
            totalPages = ceil(total.toDouble() / query.size).toInt(),
            facets = Facets(repository.categoryFacets(query)),
        )
    }

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
                    NextOffering(it.code, it.status, it.startDate, it.endDate, it.timeZone, it.enrollmentOpen)
                },
            illustrative = d.illustrative,
        )

    companion object {
        const val NOT_FOUND = "Curso no encontrado"
    }
}

// lo que depende del día, en la zona horaria de cada edición (con su horario de verano): qué ediciones siguen vigentes
// y si su inscripción está abierta hoy. puro: el reloj entra como argumento
object OfferingCalendar {
    const val OPEN = "INSCRIPCION_ABIERTA"

    fun current(
        offerings: List<OfferingView>,
        clock: Clock,
    ): List<OfferingView> =
        offerings
            .map { o -> o to LocalDate.now(clock.withZone(ZoneId.of(o.timeZone))) }
            .filter { (o, today) -> !o.endDate.isBefore(today) }
            .map { (o, today) -> o.copy(enrollmentOpen = enrollmentOpen(o, today)) }
            .sortedWith(compareBy({ it.startDate }, { it.code }))

    private fun enrollmentOpen(
        o: OfferingView,
        today: LocalDate,
    ) = o.status == OPEN && (o.enrollmentFrom == null || !today.isBefore(o.enrollmentFrom)) && (o.enrollmentUntil == null || !today.isAfter(o.enrollmentUntil))
}
