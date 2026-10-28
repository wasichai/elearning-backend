package elearning.catalog

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDate

data class CatalogQuery(
    val organization: String,
    val q: String? = null,
    val category: String? = null,
    val level: String? = null,
    val modality: String? = null,
    val startsFrom: LocalDate? = null,
    val sort: CatalogSort = CatalogSort.UPCOMING,
    val page: Int = 0,
    val size: Int = 12,
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
        val (where, bindings) = where(query, withCategory = true)
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
        val (where, bindings) = where(query, withCategory = false)
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

    // el texto de la consulta solo lleva nombres de parámetro fijos; los valores van siempre ligados
    private fun where(
        query: CatalogQuery,
        withCategory: Boolean,
    ): Pair<String, Map<String, Any>> {
        val clauses = mutableListOf("organizacion = :org")
        val bindings = mutableMapOf<String, Any>("org" to query.organization)
        query.q?.let {
            clauses += "(titulo ILIKE :q ESCAPE '\\' OR resumen ILIKE :q ESCAPE '\\')"
            bindings["q"] = "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        if (withCategory) {
            query.category?.let {
                clauses += "categoria_slug = :category"
                bindings["category"] = it
            }
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
        return clauses.joinToString(" AND ") to bindings
    }
}

private fun DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: String?,
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, String::class.java) else bind(name, value)
