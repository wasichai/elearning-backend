package elearning.reputation

import elearning.common.ScopeMemo
import org.springframework.stereotype.Component
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordReadScope
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier
import java.util.UUID

// quién lee las valoraciones y sus puntajes (ADR-0038 §7 y §24), además del READ que el valorado no tiene: el estudiante,
// solo lo suyo; quien modera (MODERAR), las valoraciones salvo las que lo tienen como destino (docente de la asignación o
// autor de la versión de recurso), por subconsultas como ADR-0025, y de los puntajes solo los suyos. Wasichai lo aplica a
// listas, detalle, historial y auditoría: lo ajeno es 404. las denuncias, quien modera todas y el resto las suyas. ADMIN no recibe alcances: no modera lo suyo por la guarda
@Component
class RatingReadScope(
    private val currentUser: CurrentUser,
    private val facts: RatingFacts,
) : RecordReadScope {
    override suspend fun criterion(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
    ): RecordCriterion? {
        val name = definition.obj.name
        if (name == RatingWriteGuard.REPORT) return ScopeMemo.of("ratings", caller, name) { reports(caller, definition) }
        if (name != RatingFacts.RATING && name != RatingFacts.SCORE) return null
        return ScopeMemo.of("ratings", caller, name) { compute(caller, definition) }
    }

    private suspend fun compute(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
    ): RecordCriterion {
        // sin las columnas no se sabe de quién es: no se lee ninguno (falla cerrada)
        val columns = definition.fields.associate { it.name to SqlIdentifier.quote(it.columnName) }
        val student = columns[RatingFacts.STUDENT] ?: return NOTHING
        val me = caller.userId
        val own = RecordCriterion { _, bind -> "$student = ${bind(me)}" }
        if (definition.obj.name != RatingFacts.RATING || !currentUser.hasPermission(caller, RatingWriteGuard.MODERATE, definition.obj.id)) return own
        val assignment = columns[RatingFacts.ASSIGNMENT] ?: return own
        val resource = columns[RatingFacts.RESOURCE_VERSION] ?: return own
        return try {
            val a = facts.table(caller.organizationId, RatingFacts.ASSIGNMENT)
            val rv = facts.table(caller.organizationId, RatingFacts.RESOURCE_VERSION)
            val teacher = a.column(RatingFacts.TEACHER)
            val author = rv.column(RatingFacts.AUTHOR)
            val organization: UUID = caller.organizationId
            RecordCriterion { _, bind ->
                val mine = "SELECT a.\"id\" FROM ${a.name} a WHERE a.organization_id = ${bind(organization)} AND a.$teacher = ${bind(me)}"
                val authored = "SELECT rv.\"id\" FROM ${rv.name} rv WHERE rv.organization_id = ${bind(organization)} AND rv.$author = ${bind(me)}"
                "(($assignment IS NULL OR $assignment NOT IN ($mine)) AND ($resource IS NULL OR $resource NOT IN ($authored)))"
            }
        } catch (_: RatingFacts.MissingModel) {
            own
        }
    }

    // las denuncias: quien modera, todas; el resto, las suyas
    private suspend fun reports(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
    ): RecordCriterion? {
        val ratings =
            try {
                facts.ratingObject(caller.organizationId)
            } catch (_: RatingFacts.MissingModel) {
                return NOTHING
            }
        if (currentUser.hasPermission(caller, RatingWriteGuard.MODERATE, ratings)) return null
        val reporter = definition.fields.firstOrNull { it.name == RatingWriteGuard.REPORTER }?.let { SqlIdentifier.quote(it.columnName) } ?: return NOTHING
        val me = caller.userId
        return RecordCriterion { _, bind -> "$reporter = ${bind(me)}" }
    }

    private companion object {
        val NOTHING = RecordCriterion { _, _ -> "false" }
    }
}
