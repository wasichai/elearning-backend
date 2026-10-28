package elearning.enrollment

import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordReadScope
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier
import java.util.UUID

// lo que una persona sin VER_TODAS lee del contenido (ADR-0012): las versiones de sus matrículas ACTIVAS, las unidades de
// esas versiones y las lecciones de esas unidades. una versión en borrador o de otro curso se lee como inexistente.
// las matrículas y las unidades se leen como quien llama, así que sus propios alcances ya aplican. nunca por rol
@Component
class ContentReadScope(
    private val currentUser: CurrentUser,
    // RecordService recibe la lista de alcances: se pide perezosamente para no formar un ciclo
    private val records: ObjectProvider<RecordService>,
) : RecordReadScope {
    override suspend fun criterion(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
    ): RecordCriterion? {
        val name = definition.obj.name
        if (name !in SCOPED || currentUser.hasPermission(caller, SEE_ALL, definition.obj.id)) return null
        val (column, ids) =
            when (name) {
                VERSION -> ID_COLUMN to enrolledVersions(caller)
                UNIT -> column(definition, VERSION) to enrolledVersions(caller)
                else -> column(definition, UNIT) to all(UNIT).map { it.id }
            }
        if (column == null || ids.isEmpty()) return RecordCriterion { _, _ -> NOTHING }
        val values = ids.map(UUID::fromString).toTypedArray()
        return RecordCriterion { _, bind -> "$column = ANY(${bind(values)})" }
    }

    private suspend fun enrolledVersions(caller: AuthenticatedUser): List<String> =
        all(ENROLLMENT, mapOf(STUDENT to caller.userId.toString(), STATE to ACTIVE)).mapNotNull { it.attributes.ref(VERSION) }.distinct()

    private suspend fun all(
        objectName: String,
        filters: Map<String, String> = emptyMap(),
    ): List<RecordResponse> {
        val found = mutableListOf<RecordResponse>()
        var cursor: String? = null
        do {
            val page =
                records.getObject().list(
                    objectName,
                    RecordQuery(page = PageRequest(0, PageRequest.MAX_SIZE), filters = filters, count = false, after = cursor),
                )
            found += page.content
            cursor = page.nextCursor
        } while (cursor != null)
        return found
    }

    private fun column(
        definition: ObjectDefinition,
        field: String,
    ): String? = definition.fields.firstOrNull { it.name == field }?.let { SqlIdentifier.quote(it.columnName) }

    companion object {
        const val VERSION = "version_curso"
        const val UNIT = "unidad"
        const val LESSON = "leccion"
        const val ENROLLMENT = "matricula"
        const val STUDENT = "estudiante_id"
        const val STATE = "estado"
        const val ACTIVE = "ACTIVA"
        const val SEE_ALL = "VER_TODAS"
        const val NOTHING = "false"
        const val ID_COLUMN = "\"id\""
        val SCOPED = setOf(VERSION, UNIT, LESSON)
    }
}

// una relación puede llegar como id o expandida
private fun Map<String, Any?>.ref(name: String): String? =
    when (val v = this[name]) {
        is Map<*, *> -> v["id"]?.toString()
        null -> null
        else -> v.toString().takeIf { it.isNotBlank() }
    }
