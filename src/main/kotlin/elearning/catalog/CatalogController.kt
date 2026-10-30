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
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) page: String?,
        @RequestParam(required = false) size: String?,
    ): ResponseEntity<CoursePage> = ResponseEntity.ok().cacheControl(cache).body(reader.page(parse(q, category, level, modality, startsFrom, sort, page, size)))

    // un borrador, un slug inexistente o el de otra organización reciben la misma respuesta: no se distingue qué existe
    @GetMapping("/courses/{slug}")
    suspend fun course(
        @PathVariable slug: String,
    ): ResponseEntity<CourseDetail> {
        if (!SLUG.matches(slug)) throw NotFoundException(CatalogReader.NOT_FOUND)
        return ResponseEntity.ok().cacheControl(cache).body(reader.detail(slug))
    }

    // reúne todos los errores de una vez; cada uno nombra su parámetro
    private fun parse(
        q: String?,
        category: String?,
        level: String?,
        modality: String?,
        startsFrom: String?,
        sort: String?,
        page: String?,
        size: String?,
    ): CatalogQuery {
        val errors = mutableListOf<FieldViolation>()
        val text = q?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null && text.length > MAX_Q) errors += FieldViolation("q", "must be at most $MAX_Q characters")
        // un carácter de control (NUL incluido) no es una búsqueda y PostgreSQL rechaza el NUL en un text
        if (text != null && text.any { it.isISOControl() }) errors += FieldViolation("q", "must not contain control characters")
        val categorySlug = category?.trim()?.takeIf { it.isNotEmpty() }
        if (categorySlug != null && !SLUG.matches(categorySlug)) errors += FieldViolation("category", "is not a slug")
        if (level != null && level !in LEVELS) errors += FieldViolation("level", "must be one of ${LEVELS.joinToString()}")
        if (modality != null && modality !in MODALITIES) errors += FieldViolation("modality", "must be one of ${MODALITIES.joinToString()}")
        val from =
            startsFrom?.let {
                try {
                    LocalDate.parse(it)
                } catch (_: DateTimeParseException) {
                    errors += FieldViolation("startsFrom", "must be a date YYYY-MM-DD")
                    null
                }
            }
        val order = if (sort == null) CatalogSort.UPCOMING else CatalogSort.of(sort)
        if (order == null) errors += FieldViolation("sort", "must be one of ${CatalogSort.entries.joinToString { it.param }}")
        val pageNumber = page?.toIntOrNull() ?: if (page == null) 0 else -1
        if (pageNumber < 0) errors += FieldViolation("page", "must be an integer >= 0")
        val pageSize = size?.toIntOrNull() ?: if (size == null) DEFAULT_SIZE else -1
        if (pageSize !in 1..MAX_SIZE) errors += FieldViolation("size", "must be between 1 and $MAX_SIZE")
        if (errors.isNotEmpty()) throw ValidationException("Invalid catalog query", errors)
        return CatalogQuery(reader.organization, text, categorySlug, level, modality, from, order!!, pageNumber, pageSize)
    }

    companion object {
        const val MAX_Q = 100
        const val DEFAULT_SIZE = 12
        const val MAX_SIZE = 50
        val SLUG = CatalogBuilder.PUBLIC_SLUG
        val LEVELS = setOf("INICIAL", "INTERMEDIO", "AVANZADO")
        val MODALITIES = setOf("ASINCRONA", "SINCRONA", "HIBRIDA", "PRESENCIAL")
    }
}
