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

// lo que una persona sin VER_TODAS lee del contenido (ADR-0012, ADR-0013): las versiones PUBLICADAS o de sus matrículas
// ACTIVAS, y las unidades, lecciones y ediciones de esas versiones. un borrador se lee como inexistente. los recursos
// (E13.1) quedan solo para las versiones de sus matrículas.
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
        // ADR-0013: la versión se ve si está publicada o es de una matrícula activa; lo demás cuelga de las versiones
        // que se ven (las lee como quien llama, así que el alcance de version_curso ya aplica)
        if (name == VERSION) {
            val state = column(definition, STATE) ?: return RecordCriterion { _, _ -> NOTHING }
            val enrolled = enrolledVersions(caller).map(UUID::fromString).toTypedArray()
            return RecordCriterion { _, bind -> "($state = ${bind(PUBLISHED)} OR $ID_COLUMN = ANY(${bind(enrolled)}))" }
        }
        val (column, ids) =
            when (name) {
                UNIT, OFFERING -> column(definition, VERSION) to all(VERSION).map { it.id }
                LESSON -> column(definition, UNIT) to all(UNIT).map { it.id }
                // los recursos, solo por las lecciones de las versiones en que está matriculado (ADR-0013)
                RESOURCE_LINK -> column(definition, LESSON) to enrolledLessons(caller)
                RESOURCE_VERSION -> ID_COLUMN to all(RESOURCE_LINK).mapNotNull { it.attributes.ref(RESOURCE_VERSION) }.distinct()
                else -> ID_COLUMN to all(RESOURCE_VERSION).mapNotNull { it.attributes.ref(RESOURCE) }.distinct()
            }
        if (column == null || ids.isEmpty()) return RecordCriterion { _, _ -> NOTHING }
        val values = ids.map(UUID::fromString).toTypedArray()
        return RecordCriterion { _, bind -> "$column = ANY(${bind(values)})" }
    }

    private suspend fun enrolledLessons(caller: AuthenticatedUser): List<String> =
        enrolledVersions(caller)
            .flatMap { version -> all(UNIT, mapOf(VERSION to version)) }
            .flatMap { unit -> all(LESSON, mapOf(UNIT to unit.id)) }
            .map { it.id }

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
        const val OFFERING = "edicion_curso"
        const val RESOURCE = "recurso"
        const val RESOURCE_VERSION = "version_recurso"
        const val RESOURCE_LINK = "recurso_leccion"
        const val PUBLISHED = "PUBLICADA"
        const val ENROLLMENT = "matricula"
        const val STUDENT = "estudiante_id"
        const val STATE = "estado"
        const val ACTIVE = "ACTIVA"
        const val SEE_ALL = "VER_TODAS"
        const val NOTHING = "false"
        const val ID_COLUMN = "\"id\""
        val SCOPED = setOf(VERSION, UNIT, LESSON, OFFERING, RESOURCE, RESOURCE_VERSION, RESOURCE_LINK)
    }
}
