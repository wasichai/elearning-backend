package elearning.catalog

import elearning.course.PrerequisiteKnowledge
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class CatalogQuery(
    val organization: String,
    val q: String? = null,
    val category: String? = null,
    val level: String? = null,
    val modality: String? = null,
    val startsFrom: LocalDate? = null,
    val minWeeks: Int? = null,
    val maxWeeks: Int? = null,
    val enrollmentOpen: Boolean? = null,
    val minHours: HoursFilter = HoursFilter(),
    // una clave del vocabulario conocimiento_previo, o PrerequisiteKnowledge.NONE: los que no declaran ninguna (ADR-0028)
    val prerequisiteKnowledge: String? = null,
    val sort: CatalogSort = CatalogSort.UPCOMING,
    val page: Int = 0,
    val size: Int = 12,
)

// mínimo de horas totales por tipo, como las publica la ficha (workload.theoryHours, practiceHours, autonomousHours)
data class HoursFilter(
    val theory: BigDecimal? = null,
    val practice: BigDecimal? = null,
    val autonomous: BigDecimal? = null,
)

enum class CatalogSort(
    val param: String,
    val orderBy: String,
) {
    // la próxima edición primero; sin edición próxima, al final
    // la edición que sigue vigente (en curso o por empezar) que antes empieza; sin ninguna, al final
    UPCOMING("upcoming", "(SELECT min(i) FROM unnest(inicios, fines) AS e(i, f) WHERE f >= CURRENT_DATE) ASC NULLS LAST, lower(titulo), slug"),
    TITLE("title", "lower(titulo), slug"),
    DURATION("duration", "semanas, lower(titulo), slug"),
    ;

    companion object {
        fun of(param: String): CatalogSort? = entries.firstOrNull { it.param == param }
    }
}

data class StoredCourse(
    val slug: String,
    val hash: String,
)

// lms.catalogo_publicado con DatabaseClient y valores siempre ligados (ADR-0011)
@Repository
class CatalogRepository(
    private val db: DatabaseClient,
    private val clock: Clock,
) {
    suspend fun stored(organization: String): List<StoredCourse> =
        db
            .sql("SELECT slug, hash FROM lms.catalogo_publicado WHERE organizacion = :org")
            .bind("org", organization)
            .map { row, _ -> StoredCourse(row.get("slug", String::class.java)!!, row.get("hash", String::class.java)!!) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun upsert(
        organization: String,
        course: PublishedCourse,
    ) {
        val d = course.detail
        db
            .sql(
                """
                INSERT INTO lms.catalogo_publicado
                    (organizacion, slug, codigo, titulo, resumen, categoria_slug, categoria_nombre, nivel, modalidad, semanas, inicios, fines, ficha, hash, actualizado_en)
                VALUES (:org, :slug, :codigo, :titulo, :resumen, :categoriaSlug, :categoriaNombre, :nivel, :modalidad, :semanas, :inicios, :fines, CAST(:ficha AS jsonb), :hash, now())
                ON CONFLICT (organizacion, slug) DO UPDATE SET
                    codigo = EXCLUDED.codigo, titulo = EXCLUDED.titulo, resumen = EXCLUDED.resumen,
                    categoria_slug = EXCLUDED.categoria_slug, categoria_nombre = EXCLUDED.categoria_nombre,
                    nivel = EXCLUDED.nivel, modalidad = EXCLUDED.modalidad, semanas = EXCLUDED.semanas,
                    inicios = EXCLUDED.inicios, fines = EXCLUDED.fines, ficha = EXCLUDED.ficha, hash = EXCLUDED.hash, actualizado_en = now()
                """.trimIndent(),
            ).bind("org", organization)
            .bind("slug", d.slug)
            .bind("codigo", d.code)
            .bind("titulo", d.title)
            .bindNullable("resumen", d.summary)
            .bindNullable("categoriaSlug", d.category?.slug)
            .bindNullable("categoriaNombre", d.category?.name)
            .bind("nivel", d.level)
            .bind("modalidad", d.modality)
            .bind("semanas", d.weeks)
            .bind("inicios", course.startDates.toTypedArray())
            .bind("fines", course.endDates.toTypedArray())
            .bind("ficha", course.json())
            .bind("hash", course.hash)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // matriculados por edición de una organización (lms.ocupacion_edicion, ADR-0013): solo el número, para saber si una
    // edición está completa. lo lee la proyección, en segundo plano; la ruta pública nunca
    suspend fun occupancy(organization: UUID): Map<String, Int> =
        db
            .sql("SELECT edicion_id, matriculados FROM lms.ocupacion_edicion WHERE organizacion_id = :org")
            .bind("org", organization)
            .map { row, _ -> row.get("edicion_id", UUID::class.java)!!.toString() to row.get("matriculados", Number::class.java)!!.toInt() }
            .all()
            .collectList()
            .awaitSingle()
            .toMap()

    suspend fun delete(
        organization: String,
        slugs: Collection<String>,
    ): Long =
        if (slugs.isEmpty()) {
            0
        } else {
            db
                .sql("DELETE FROM lms.catalogo_publicado WHERE organizacion = :org AND slug = ANY(:slugs)")
                .bind("org", organization)
                .bind("slugs", slugs.toTypedArray())
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }

    suspend fun deleteAll(): Long =
        db
            .sql("DELETE FROM lms.catalogo_publicado")
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    // las filas de otras organizaciones (la pública cambió de slug)
    suspend fun deleteOtherOrganizations(organization: String): Long =
        db
            .sql("DELETE FROM lms.catalogo_publicado WHERE organizacion <> :org")
            .bind("org", organization)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    suspend fun page(query: CatalogQuery): Pair<List<String>, Long> {
        val (where, bindings) = where(query)
        val total = count(where, bindings)
        var spec =
            db.sql(
                "SELECT ficha::text AS ficha FROM lms.catalogo_publicado WHERE $where ORDER BY ${query.sort.orderBy} LIMIT :limit OFFSET :offset",
            )
        bindings.forEach { (k, v) -> spec = spec.bind(k, v) }
        val rows =
            spec
                .bind("limit", query.size)
                .bind("offset", query.page.toLong() * query.size)
                .map { row, _ -> row.get("ficha", String::class.java)!! }
                .all()
                .collectList()
                .awaitSingle()
        return rows to total
    }

    // las categorías con cursos según todos los filtros menos el de categoría: para pintar el filtro
    suspend fun categoryFacets(query: CatalogQuery): List<CategoryFacet> {
        val (where, bindings) = where(query.copy(category = null))
        var spec =
            db.sql(
                """
                SELECT categoria_slug, categoria_nombre, count(*) AS n FROM lms.catalogo_publicado
                WHERE $where AND categoria_slug IS NOT NULL GROUP BY categoria_slug, categoria_nombre ORDER BY categoria_nombre
                """.trimIndent(),
            )
        bindings.forEach { (k, v) -> spec = spec.bind(k, v) }
        return spec
            .map { row, _ ->
                CategoryFacet(
                    row.get("categoria_slug", String::class.java)!!,
                    row.get("categoria_nombre", String::class.java)!!,
                    row.get("n", Number::class.java)!!.toLong(),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    // las claves de conocimientos previos con cursos según todos los filtros menos el suyo, como las categorías
    suspend fun knowledgeFacets(query: CatalogQuery): List<KnowledgeFacet> {
        val (where, bindings) = where(query.copy(prerequisiteKnowledge = null))
        var spec =
            db.sql(
                """
                SELECT t->>'key' AS clave, max(t->>'name') AS nombre, count(*) AS n FROM lms.catalogo_publicado
                CROSS JOIN LATERAL jsonb_array_elements(COALESCE(ficha->'$KNOWLEDGE', '[]'::jsonb)) t
                WHERE $where GROUP BY t->>'key' ORDER BY nombre, clave
                """.trimIndent(),
            )
        bindings.forEach { (k, v) -> spec = spec.bind(k, v) }
        return spec
            .map { row, _ ->
                KnowledgeFacet(
                    row.get("clave", String::class.java)!!,
                    row.get("nombre", String::class.java)!!,
                    row.get("n", Number::class.java)!!.toLong(),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    suspend fun detail(
        organization: String,
        slug: String,
    ): String? =
        db
            .sql("SELECT ficha::text AS ficha FROM lms.catalogo_publicado WHERE organizacion = :org AND slug = :slug")
            .bind("org", organization)
            .bind("slug", slug)
            .map { row, _ -> row.get("ficha", String::class.java)!! }
            .one()
            .awaitFirstOrNull()

    private suspend fun count(
        where: String,
        bindings: Map<String, Any>,
    ): Long {
        var spec = db.sql("SELECT count(*) AS n FROM lms.catalogo_publicado WHERE $where")
        bindings.forEach { (k, v) -> spec = spec.bind(k, v) }
        return spec
            .map { row, _ -> row.get("n", Number::class.java)!!.toLong() }
            .one()
            .awaitSingle()
    }

    // el texto de la consulta solo lleva nombres de parámetro fijos; los valores van siempre ligados. una faceta se cuenta
    // con la consulta sin su propio filtro
    private fun where(query: CatalogQuery): Pair<String, Map<String, Any>> {
        val clauses = mutableListOf("organizacion = :org")
        val bindings = mutableMapOf<String, Any>("org" to query.organization)
        query.q?.let {
            clauses += "(titulo ILIKE :q ESCAPE '\\' OR resumen ILIKE :q ESCAPE '\\')"
            bindings["q"] = "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        query.category?.let {
            clauses += "categoria_slug = :category"
            bindings["category"] = it
        }
        query.level?.let {
            clauses += "nivel = :level"
            bindings["level"] = it
        }
        query.modality?.let {
            clauses += "modalidad = :modality"
            bindings["modality"] = it
        }
        query.startsFrom?.let {
            clauses += "EXISTS (SELECT 1 FROM unnest(inicios) s WHERE s >= :startsFrom)"
            bindings["startsFrom"] = it
        }
        query.minWeeks?.let {
            clauses += "semanas >= :minWeeks"
            bindings["minWeeks"] = it
        }
        query.maxWeeks?.let {
            clauses += "semanas <= :maxWeeks"
            bindings["maxWeeks"] = it
        }
        // lo mismo que enrollmentOpen de la ficha (OfferingCalendar), en la zona horaria de cada edición y con el reloj
        // de la aplicación: el texto de la consulta no depende del valor
        query.enrollmentOpen?.let {
            clauses += if (it) "EXISTS ($OPEN_OFFERING)" else "NOT EXISTS ($OPEN_OFFERING)"
            bindings["now"] = OffsetDateTime.now(clock)
        }
        query.minHours.theory?.let {
            clauses += "(ficha->'workload'->>'theoryHours')::numeric >= :minTheoryHours"
            bindings["minTheoryHours"] = it
        }
        query.minHours.practice?.let {
            clauses += "(ficha->'workload'->>'practiceHours')::numeric >= :minPracticeHours"
            bindings["minPracticeHours"] = it
        }
        query.minHours.autonomous?.let {
            clauses += "(ficha->'workload'->>'autonomousHours')::numeric >= :minAutonomousHours"
            bindings["minAutonomousHours"] = it
        }
        // lo que declaró la versión publicada, tal como está en la ficha (ADR-0028)
        query.prerequisiteKnowledge?.let {
            if (it == PrerequisiteKnowledge.NONE) {
                clauses += "jsonb_array_length(COALESCE(ficha->'$KNOWLEDGE', '[]'::jsonb)) = 0"
            } else {
                clauses += "ficha->'$KNOWLEDGE' @> jsonb_build_array(jsonb_build_object('key', CAST(:knowledge AS text)))"
                bindings["knowledge"] = it
            }
        }
        return clauses.joinToString(" AND ") to bindings
    }
}

// el nombre del campo de la ficha (CourseDetail.prerequisiteKnowledgeTags): fijo, nunca un valor
private const val KNOWLEDGE = "prerequisiteKnowledgeTags"

// una edición de la ficha con la inscripción abierta hoy: INSCRIPCION_ABIERTA, con plazas, sin terminar y dentro de la
// ventana
private val OPEN_OFFERING =
    """
    SELECT 1 FROM jsonb_array_elements(ficha->'offerings') o
    CROSS JOIN LATERAL (SELECT (CAST(:now AS timestamptz) AT TIME ZONE (o->>'timeZone'))::date AS hoy) t
    WHERE o->>'status' = '${OfferingCalendar.OPEN}'
      AND NOT COALESCE((o->>'full')::boolean, false)
      AND (o->>'endDate')::date >= t.hoy
      AND (o->>'enrollmentFrom' IS NULL OR (o->>'enrollmentFrom')::date <= t.hoy)
      AND (o->>'enrollmentUntil' IS NULL OR (o->>'enrollmentUntil')::date >= t.hoy)
    """.trimIndent()

private fun DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: String?,
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, String::class.java) else bind(name, value)
