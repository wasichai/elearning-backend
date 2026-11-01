package elearning.enrollment

import elearning.common.ScopeMemo
import elearning.common.listAll
import elearning.common.ref
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import wasichai.core.common.Actions
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordReadScope
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// cada persona lee sus matrículas, su progreso y sus entregas; el docente, además, los de las ediciones que tiene asignadas
// (docente_edicion.usuario_id, ADR-0016). quien tiene la acción declarada VER_TODAS los lee todos, y hoy ningún rol la
// tiene sobre datos de estudiantes (ADR-0014). Wasichai lo pide para toda lectura de una persona o cuenta de servicio
// (nunca para ADMIN ni la plataforma) y lo aplica en listas, detalle, relaciones, historial y auditoría: lo ajeno se lee
// como inexistente (404). nunca por nombre de rol
@Component
class EnrollmentReadScope(
    private val currentUser: CurrentUser,
    // RecordService y MetadataService reciben los alcances: se piden perezosamente para no formar un ciclo
    private val records: ObjectProvider<RecordService>,
    private val metadata: ObjectProvider<MetadataService>,
    private val schemas: WasichaiSchemas,
) : RecordReadScope {
    override suspend fun criterion(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
    ): RecordCriterion? = ScopeMemo.of("enrollment", caller, definition.obj.name) { compute(caller, definition) }

    private suspend fun compute(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
    ): RecordCriterion? {
        val name = definition.obj.name
        if (name == SESSION) return sessions(caller, definition)
        if (name !in OWNED) return null
        if (currentUser.hasPermission(caller, SEE_ALL, definition.obj.id)) return null
        // sin la columna no se sabe de quién es: no se lee ninguna (falla cerrada)
        val student = column(definition, STUDENT) ?: return RecordCriterion { _, _ -> NOTHING }
        val me = caller.userId
        val taught = taughtOfferings(caller)
        if (taught.isEmpty()) return RecordCriterion { _, bind -> "$student = ${bind(me)}" }
        // el docente: lo de sus ediciones. la matrícula por su edición; el progreso por las matrículas de esas ediciones, con una
        // subconsulta (M4 de #183): antes se ligaban los ids de todas sus matrículas, un arreglo que crecía con los estudiantes
        val offerings = taught.map(UUID::fromString).toTypedArray()
        if (name == ENROLLMENT) {
            val offering = column(definition, OFFERING) ?: return RecordCriterion { _, bind -> "$student = ${bind(me)}" }
            return RecordCriterion { _, bind -> "($student = ${bind(me)} OR $offering = ANY(${bind(offerings)}))" }
        }
        val enrollment = column(definition, ENROLLMENT)
        val enrollments = enrollmentTable(caller)
        if (enrollment == null || enrollments == null) return RecordCriterion { _, bind -> "$student = ${bind(me)}" }
        val (table, offering) = enrollments
        val organization = caller.organizationId
        return RecordCriterion { _, bind ->
            val ofTaught =
                "SELECT lms_m.\"id\" FROM $table lms_m WHERE lms_m.organization_id = ${bind(organization)} " +
                    "AND lms_m.$offering = ANY(${bind(offerings)})"
            "($student = ${bind(me)} OR $enrollment IN ($ofTaught))"
        }
    }

    // la tabla de las matrículas y su columna de edición. como leerlas como el docente: sin READ sobre ellas, ninguna, y su
    // alcance ya lo da la edición asignada
    private suspend fun enrollmentTable(caller: AuthenticatedUser): Pair<String, String>? {
        val definition =
            try {
                metadata.getObject().loadDefinition(caller.organizationId, ENROLLMENT)
            } catch (_: NotFoundException) {
                return null
            }
        if (!currentUser.hasPermission(caller, Actions.READ, definition.obj.id)) return null
        val offering = column(definition, OFFERING) ?: return null
        return schemas.dataTable(definition.obj.physicalTable) to offering
    }

    // las sesiones de las ediciones que cursa o que tiene asignadas
    private suspend fun sessions(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
    ): RecordCriterion {
        val column = column(definition, OFFERING) ?: return RecordCriterion { _, _ -> NOTHING }
        val studied = all(ENROLLMENT, mapOf(STUDENT to caller.userId.toString())).mapNotNull { it.attributes.ref(OFFERING) }
        val offerings = (studied + taughtOfferings(caller)).distinct().map(UUID::fromString).toTypedArray()
        return RecordCriterion { _, bind -> "$column = ANY(${bind(offerings)})" }
    }

    // las ediciones asignadas a la persona. el alcance de docente_edicion (AssignmentReadScope) no lee nada, así que no hay
    // recursión. quien no puede leer asignaciones (un estudiante) no tiene ninguna
    private suspend fun taughtOfferings(caller: AuthenticatedUser): List<String> =
        try {
            all(ASSIGNMENT, mapOf(TEACHER to caller.userId.toString())).mapNotNull { it.attributes.ref(OFFERING_FIELD) }.distinct()
        } catch (_: ForbiddenException) {
            emptyList()
        }

    private suspend fun all(
        objectName: String,
        filters: Map<String, String>,
    ): List<RecordResponse> = records.getObject().listAll(objectName, filters)

    private fun column(
        definition: ObjectDefinition,
        field: String,
    ): String? = definition.fields.firstOrNull { it.name == field }?.let { SqlIdentifier.quote(it.columnName) }

    companion object {
        const val ENROLLMENT = "matricula"
        const val PROGRESS = "progreso_leccion"
        const val SESSION = "sesion"
        const val ASSIGNMENT = "docente_edicion"
        const val TEACHER = "usuario_id"
        const val OFFERING = "edicion"
        const val OFFERING_FIELD = "edicion_curso"
        const val STUDENT = "estudiante_id"
        const val SEE_ALL = "VER_TODAS"
        const val NOTHING = "false"
        val OWNED = setOf(ENROLLMENT, PROGRESS, "entrega", "asistencia")
    }
}
