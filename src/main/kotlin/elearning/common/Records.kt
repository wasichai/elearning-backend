package elearning.common

import wasichai.core.common.PageRequest
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.platform.SqlIdentifier
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

// todos los registros que la persona puede leer con esos filtros exactos, página a página por cursor (keyset)
suspend fun RecordService.listAll(
    objectName: String,
    filters: Map<String, String> = emptyMap(),
    criteria: List<RecordCriterion> = emptyList(),
): List<RecordResponse> {
    val found = mutableListOf<RecordResponse>()
    var cursor: String? = null
    do {
        val query = RecordQuery(page = PageRequest(0, PageRequest.MAX_SIZE), filters = filters, criteria = criteria, count = false, after = cursor)
        val page = list(objectName, query)
        found += page.content
        cursor = page.nextCursor
    } while (cursor != null)
    return found
}

// los registros cuya relación [field] apunta a alguno de [parents], en una lectura y no una por padre (L4 de #216). el
// alcance de quien llama sigue aplicando: se suma al criterio. sin padres, nada
suspend fun RecordService.listAllIn(
    objectName: String,
    field: String,
    parents: Collection<String>,
): List<RecordResponse> {
    if (parents.isEmpty()) return emptyList()
    val ids = parents.distinct().map(UUID::fromString).toTypedArray()
    val inParents =
        RecordCriterion { definition, bind ->
            // sin el campo no hay de quién colgar: nada (falla cerrada)
            definition.fields
                .firstOrNull { it.name == field }
                ?.let { "${SqlIdentifier.quote(it.columnName)} = ANY(${bind(ids)})" } ?: "false"
        }
    return listAll(objectName, criteria = listOf(inParents))
}

// los registros con esas identidades que quien llama puede leer, en lecturas de a lo sumo una página
suspend fun RecordService.listByIds(
    objectName: String,
    ids: Collection<String>,
): List<RecordResponse> =
    ids.distinct().map(UUID::fromString).chunked(PageRequest.MAX_SIZE).flatMap { chunk ->
        list(objectName, RecordQuery(page = PageRequest(0, chunk.size), ids = chunk, count = false)).content
    }

// el id de una relación, venga como objeto ({"id": …}) o como texto
fun Map<String, Any?>.ref(name: String): String? =
    when (val v = this[name]) {
        is Map<*, *> -> v["id"]?.toString()
        null -> null
        else -> v.toString().takeIf { it.isNotBlank() }
    }

// un DATETIME de un registro: llega como instante o como texto con desplazamiento
fun instantOf(value: Any?): Instant? =
    when (value) {
        null -> null
        is Instant -> value
        is OffsetDateTime -> value.toInstant()
        else -> value.toString().let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
    }
