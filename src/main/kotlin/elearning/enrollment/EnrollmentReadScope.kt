package elearning.enrollment

import org.springframework.stereotype.Component
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordReadScope
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier

// cada persona lee solo sus matrículas y su progreso, salvo quien tiene la acción declarada VER_TODAS (ADR-0012). Wasichai lo pide
// para toda lectura de una persona o cuenta de servicio (nunca para ADMIN ni la plataforma) y lo aplica en listas,
// detalle, relaciones, historial y auditoría: lo ajeno se lee como inexistente (404). nunca por nombre de rol
@Component
class EnrollmentReadScope(
    private val currentUser: CurrentUser,
) : RecordReadScope {
    override suspend fun criterion(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
    ): RecordCriterion? {
        if (definition.obj.name !in OWNED) return null
        if (currentUser.hasPermission(caller, SEE_ALL, definition.obj.id)) return null
        // sin la columna no se sabe de quién es: no se lee ninguna (falla cerrada)
        val column =
            definition.fields.firstOrNull { it.name == STUDENT }?.let { SqlIdentifier.quote(it.columnName) }
                ?: return RecordCriterion { _, _ -> NOTHING }
        val me = caller.userId
        return RecordCriterion { _, bind -> "$column = ${bind(me)}" }
    }

    companion object {
        const val ENROLLMENT = "matricula"
        const val PROGRESS = "progreso_leccion"
        val OWNED = setOf(ENROLLMENT, PROGRESS)
        const val STUDENT = "estudiante_id"
        const val SEE_ALL = "VER_TODAS"
        const val NOTHING = "false"
    }
}
