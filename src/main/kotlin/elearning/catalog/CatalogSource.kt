package elearning.catalog

import elearning.course.PrerequisiteKnowledgeGuard
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
            knowledge = all(PrerequisiteKnowledgeGuard.VOCABULARY),
        )

    private suspend fun all(
        objectName: String,
        filters: Map<String, String> = emptyMap(),
    ): List<RecordResponse> {
        // por cursor (keyset) y no por OFFSET: un alta o baja entre páginas no hace saltar ni repetir registros
        val found = mutableListOf<RecordResponse>()
        var cursor: String? = null
        do {
            val page = records.list(objectName, RecordQuery(page = PageRequest(0, PAGE), filters = filters, count = false, after = cursor))
            found += page.content
            cursor = page.nextCursor
        } while (cursor != null)
        return found
    }

    companion object {
        private const val PAGE = PageRequest.MAX_SIZE
    }
}
