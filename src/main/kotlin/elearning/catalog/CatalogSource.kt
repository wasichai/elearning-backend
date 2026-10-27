package elearning.catalog

import org.springframework.stereotype.Component
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService

// lee los registros del catálogo por RecordService. solo funciona dentro de asPlatform, en segundo plano: dentro de
// una petición Wasichai lo impide, y por eso existe la proyección (ADR-0011)
@Component
class CatalogSource(
    private val records: RecordService,
) {
    suspend fun load(): CatalogRecords =
        CatalogRecords(
            categories = all("categoria"),
            courses = all("curso"),
            versions = all("version_curso", mapOf("estado" to CatalogBuilder.PUBLISHED)),
            units = all("unidad"),
            lessons = all("leccion"),
            offerings = all("edicion_curso"),
            profiles = all("perfil_docente"),
            assignments = all("docente_edicion"),
        )

    private suspend fun all(
        objectName: String,
        filters: Map<String, String> = emptyMap(),
    ): List<RecordResponse> {
        val found = mutableListOf<RecordResponse>()
        var page = 0
        do {
            val chunk = records.list(objectName, RecordQuery(page = PageRequest(page, PAGE), filters = filters, count = false)).content
            found += chunk
            page++
        } while (chunk.size == PAGE)
        return found
    }

    companion object {
        private const val PAGE = PageRequest.MAX_SIZE
    }
}
