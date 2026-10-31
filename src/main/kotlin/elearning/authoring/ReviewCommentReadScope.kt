package elearning.authoring

import elearning.common.ScopeMemo
import elearning.common.listAll
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import wasichai.core.common.ForbiddenException
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordReadScope
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier
import java.util.UUID

// los comentarios de revisión (L5 de #216): antes, cualquiera con READ (todo CREADOR) leía por la API de registros los de
// todas las versiones, con sus autores. ahora los lee quien publica (PUBLICAR sobre version_curso, que reparte revisores),
// el autor de la versión, quien la puede revisar (APROBAR, no es el autor y no está asignada a otra persona, como en la
// bandeja) y quien escribió el comentario. lo demás no existe (404). nunca por rol
@Component
class ReviewCommentReadScope(
    private val currentUser: CurrentUser,
    // RecordService recibe la lista de alcances: se pide perezosamente para no formar un ciclo
    private val records: ObjectProvider<RecordService>,
    private val metadata: ObjectProvider<MetadataService>,
) : RecordReadScope {
    override suspend fun criterion(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
    ): RecordCriterion? {
        if (definition.obj.name != COMMENT) return null
        return ScopeMemo.of("review-comments", caller, COMMENT) { compute(caller, definition) }
    }

    private suspend fun compute(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
    ): RecordCriterion? {
        val versionObject =
            metadata
                .getObject()
                .definitionOf(VERSION)
                .obj.id
        if (currentUser.hasPermission(caller, PUBLISH, versionObject)) return null
        // sin las columnas no se sabe de qué versión ni de quién es: no se lee ninguno (falla cerrada)
        val version = column(definition, VERSION) ?: return RecordCriterion { _, _ -> NOTHING }
        val writer = column(definition, AUTHOR) ?: return RecordCriterion { _, _ -> NOTHING }
        val me = caller.userId.toString()
        val reviewer = currentUser.hasPermission(caller, APPROVE, versionObject)
        // las versiones se leen como quien llama: su propio alcance ya aplica. quien no revisa, solo las suyas
        val visible =
            versions(if (reviewer) emptyMap() else mapOf(AUTHOR to me))
                .filter { it.attributes[AUTHOR]?.toString() == me || (reviewer && reviews(it, me)) }
                .map { UUID.fromString(it.id) }
                .toTypedArray()
        val self = caller.userId
        return RecordCriterion { _, bind -> "($version = ANY(${bind(visible)}) OR $writer = ${bind(self)})" }
    }

    // como en la bandeja: no es el autor y no está asignada a otra persona
    private fun reviews(
        version: RecordResponse,
        me: String,
    ): Boolean {
        val author = version.attributes[AUTHOR]?.toString()
        val assigned = version.attributes[REVIEWER]?.toString()
        return author != null && author != me && (assigned == null || assigned == me)
    }

    private suspend fun versions(filters: Map<String, String>): List<RecordResponse> =
        try {
            records.getObject().listAll(VERSION, filters)
        } catch (_: ForbiddenException) {
            emptyList()
        }

    private fun column(
        definition: ObjectDefinition,
        field: String,
    ): String? = definition.fields.firstOrNull { it.name == field }?.let { SqlIdentifier.quote(it.columnName) }

    companion object {
        const val COMMENT = "comentario_revision"
        const val VERSION = "version_curso"
        const val AUTHOR = "autor_id"
        const val REVIEWER = "revisor_id"
        const val APPROVE = "APROBAR"
        const val PUBLISH = "PUBLICAR"
        const val NOTHING = "false"
    }
}
