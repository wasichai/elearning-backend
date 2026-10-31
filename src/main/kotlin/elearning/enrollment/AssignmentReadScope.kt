package elearning.enrollment

import org.springframework.stereotype.Component
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordReadScope
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier

// las asignaciones docentes (docente_edicion): cada persona lee solo las suyas. leer las de todos ataba cada perfil_docente
// público a la cuenta de su docente (usuario_id) para DOCENTE, CREADOR y REVISOR (revisión de la Fase 3, «otros bajos»).
// quien administra las asignaciones (UPDATE sobre el objeto) las lee todas. nunca por nombre de rol
@Component
class AssignmentReadScope(
    private val currentUser: CurrentUser,
) : RecordReadScope {
    override suspend fun criterion(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
    ): RecordCriterion? {
        if (definition.obj.name != ASSIGNMENT) return null
        if (currentUser.hasPermission(caller, MANAGE, definition.obj.id)) return null
        // sin la columna no se sabe de quién es: no se lee ninguna (falla cerrada)
        val teacher =
            definition.fields.firstOrNull { it.name == TEACHER }?.let { SqlIdentifier.quote(it.columnName) }
                ?: return RecordCriterion { _, _ -> "false" }
        val me = caller.userId
        return RecordCriterion { _, bind -> "$teacher = ${bind(me)}" }
    }

    companion object {
        const val ASSIGNMENT = "docente_edicion"
        const val TEACHER = "usuario_id"
        const val MANAGE = "UPDATE"
    }
}
