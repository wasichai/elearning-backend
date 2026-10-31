package elearning.common

import wasichai.core.common.PageRequest
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import java.time.Instant
import java.time.OffsetDateTime

// todos los registros que la persona puede leer con esos filtros exactos, página a página por cursor (keyset)
suspend fun RecordService.listAll(
    objectName: String,
    filters: Map<String, String> = emptyMap(),
): List<RecordResponse> {
    val found = mutableListOf<RecordResponse>()
    var cursor: String? = null
    do {
        val page = list(objectName, RecordQuery(page = PageRequest(0, PageRequest.MAX_SIZE), filters = filters, count = false, after = cursor))
        found += page.content
        cursor = page.nextCursor
    } while (cursor != null)
    return found
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
