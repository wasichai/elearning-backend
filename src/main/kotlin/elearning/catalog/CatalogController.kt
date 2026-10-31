package elearning.catalog

import elearning.common.LmsProperties
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

// catálogo público: anónimo y de solo lectura (ADR-0006, ADR-0011). contrato en docs/api/lms.md
@RestController
@RequestMapping("/api/lms/public/catalog")
class CatalogController(
    private val reader: CatalogReader,
    properties: LmsProperties,
) {
    // la proyección ya va hasta este tiempo por detrás: guardar la respuesta lo mismo no la hace más vieja (#68)
    private val cache = CacheControl.maxAge(properties.catalog.refresh).cachePublic()

    @GetMapping("/courses")
    suspend fun courses(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) level: String?,
        @RequestParam(required = false) modality: String?,
        @RequestParam(required = false) startsFrom: String?,
        @RequestParam(required = false) minWeeks: String?,
        @RequestParam(required = false) maxWeeks: String?,
        @RequestParam(required = false) enrollmentOpen: String?,
        @RequestParam(required = false) minTheoryHours: String?,
        @RequestParam(required = false) minPracticeHours: String?,
        @RequestParam(required = false) minAutonomousHours: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) page: String?,
        @RequestParam(required = false) size: String?,
    ): ResponseEntity<CoursePage> {
        val raw =
            RawCatalogQuery(
                q,
                category,
                level,
                modality,
                startsFrom,
                minWeeks,
                maxWeeks,
                enrollmentOpen,
                minTheoryHours,
                minPracticeHours,
                minAutonomousHours,
                sort,
                page,
                size,
            )
        return ResponseEntity.ok().cacheControl(cache).body(reader.page(parse(raw)))
    }

    // un borrador, un slug inexistente o el de otra organización reciben la misma respuesta: no se distingue qué existe
    @GetMapping("/courses/{slug}")
    suspend fun course(
        @PathVariable slug: String,
    ): ResponseEntity<CourseDetail> {
        if (!SLUG.matches(slug)) throw NotFoundException(CatalogReader.NOT_FOUND)
        return ResponseEntity.ok().cacheControl(cache).body(reader.detail(slug))
    }

    // los parámetros tal como llegan: se validan juntos en parse
    private data class RawCatalogQuery(
        val q: String?,
        val category: String?,
        val level: String?,
        val modality: String?,
        val startsFrom: String?,
        val minWeeks: String?,
        val maxWeeks: String?,
        val enrollmentOpen: String?,
        val minTheoryHours: String?,
        val minPracticeHours: String?,
        val minAutonomousHours: String?,
        val sort: String?,
        val page: String?,
        val size: String?,
    )

    // reúne todos los errores de una vez; cada uno nombra su parámetro
    private fun parse(raw: RawCatalogQuery): CatalogQuery {
        val errors = mutableListOf<FieldViolation>()
        val text = raw.q?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null && text.length > MAX_Q) errors += FieldViolation("q", "must be at most $MAX_Q characters")
        // un carácter de control (NUL incluido) no es una búsqueda y PostgreSQL rechaza el NUL en un text
        if (text != null && text.any { it.isISOControl() }) errors += FieldViolation("q", "must not contain control characters")
        val categorySlug = raw.category?.trim()?.takeIf { it.isNotEmpty() }
        if (categorySlug != null && !SLUG.matches(categorySlug)) errors += FieldViolation("category", "is not a slug")
        if (raw.level != null && raw.level !in LEVELS) errors += FieldViolation("level", "must be one of ${LEVELS.joinToString()}")
        if (raw.modality != null && raw.modality !in MODALITIES) errors += FieldViolation("modality", "must be one of ${MODALITIES.joinToString()}")
        val from =
            raw.startsFrom?.let {
                try {
                    LocalDate.parse(it)
                } catch (_: DateTimeParseException) {
                    errors += FieldViolation("startsFrom", "must be a date YYYY-MM-DD")
                    null
                }
            }
        val minWeeks = weeks("minWeeks", raw.minWeeks, errors)
        val maxWeeks = weeks("maxWeeks", raw.maxWeeks, errors)
        if (minWeeks != null && maxWeeks != null && minWeeks > maxWeeks) errors += FieldViolation("maxWeeks", "must be >= minWeeks")
        val open =
            when (raw.enrollmentOpen) {
                null -> null
                "true" -> true
                "false" -> false
                else -> {
                    errors += FieldViolation("enrollmentOpen", "must be true or false")
                    null
                }
            }
        val hours =
            HoursFilter(
                theory = hours("minTheoryHours", raw.minTheoryHours, errors),
                practice = hours("minPracticeHours", raw.minPracticeHours, errors),
                autonomous = hours("minAutonomousHours", raw.minAutonomousHours, errors),
            )
        val order = if (raw.sort == null) CatalogSort.UPCOMING else CatalogSort.of(raw.sort)
        if (order == null) errors += FieldViolation("sort", "must be one of ${CatalogSort.entries.joinToString { it.param }}")
        val pageNumber = raw.page?.toIntOrNull() ?: if (raw.page == null) 0 else -1
        if (pageNumber < 0) errors += FieldViolation("page", "must be an integer >= 0")
        val pageSize = raw.size?.toIntOrNull() ?: if (raw.size == null) DEFAULT_SIZE else -1
        if (pageSize !in 1..MAX_SIZE) errors += FieldViolation("size", "must be between 1 and $MAX_SIZE")
        if (errors.isNotEmpty()) throw ValidationException("Invalid catalog query", errors)
        return CatalogQuery(
            organization = reader.organization,
            q = text,
            category = categorySlug,
            level = raw.level,
            modality = raw.modality,
            startsFrom = from,
            minWeeks = minWeeks,
            maxWeeks = maxWeeks,
            enrollmentOpen = open,
            minHours = hours,
            sort = order!!,
            page = pageNumber,
            size = pageSize,
        )
    }

    // la duración de un curso va de 1 a 26 semanas (RF-ACA-05): fuera de eso no hay nada que filtrar
    private fun weeks(
        name: String,
        value: String?,
        errors: MutableList<FieldViolation>,
    ): Int? {
        if (value == null) return null
        val n = value.toIntOrNull()
        if (n == null || n !in 1..MAX_WEEKS) errors += FieldViolation(name, "must be an integer between 1 and $MAX_WEEKS")
        return n
    }

    // horas totales de un tipo (teoría, práctica, autónomas): un número >= 0 con hasta dos decimales
    private fun hours(
        name: String,
        value: String?,
        errors: MutableList<FieldViolation>,
    ): BigDecimal? {
        if (value == null) return null
        val n = if (HOURS.matches(value)) value.toBigDecimalOrNull() else null
        if (n == null || n > MAX_HOURS) errors += FieldViolation(name, "must be a number between 0 and $MAX_HOURS with at most two decimals")
        return n
    }

    companion object {
        const val MAX_Q = 100
        const val DEFAULT_SIZE = 12
        const val MAX_SIZE = 50
        const val MAX_WEEKS = 26
        val MAX_HOURS = BigDecimal(10000)
        private val HOURS = Regex("^[0-9]{1,5}(\\.[0-9]{1,2})?$")
        val SLUG = CatalogBuilder.PUBLIC_SLUG
        val LEVELS = setOf("INICIAL", "INTERMEDIO", "AVANZADO")
        val MODALITIES = setOf("ASINCRONA", "SINCRONA", "HIBRIDA", "PRESENCIAL")
    }
}
