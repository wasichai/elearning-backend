package elearning.ai

import elearning.jobs.Requesters
import elearning.jobs.RequiredPermission
import org.springframework.stereotype.Service
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.util.UUID

// las acciones declaradas de la IA sobre version_curso (ADR-0033 §10, RF-IA-07): GENERAR encola un paso que produce un
// borrador, VALIDAR lanza y acepta su informe de validación. Aprobar y publicar siguen siendo APROBAR y PUBLICAR (F4) y
// ningún trabajo de IA las tiene. VER_INDICADORES_IA lee los indicadores de proceso de la canalización (RF-IA-10, docs/ai
// §10): no encola nada
enum class AiAction { GENERAR, VALIDAR, VER_INDICADORES_IA }

// la comprobación de esas acciones, en los dos momentos de ADR-0022 §4: al encolar, como quien llama (403 si no la
// tiene), y al ejecutar, con los roles guardados de quien lo pidió (PermanentJobFailure si ya no la tiene). Lo decide
// Wasichai; aquí no se mira ningún nombre de rol
@Service
class AiPermissions(
    private val currentUser: CurrentUser,
    private val metadata: MetadataService,
    private val requesters: Requesters,
) {
    suspend fun requireToEnqueue(action: AiAction): AuthenticatedUser = require(action)

    // la acción, como quien llama: 403 si no la tiene
    suspend fun require(action: AiAction): AuthenticatedUser {
        val user = currentUser.require()
        currentUser.requirePermission(user, action.name, metadata.loadDefinition(user.organizationId, VERSION).obj.id)
        return user
    }

    suspend fun requireStillAllowed(
        organization: UUID,
        requester: UUID,
        action: AiAction,
    ) = requesters.requireStillAllowed(organization, requester, listOf(RequiredPermission(action.name, VERSION)))

    private companion object {
        const val VERSION = "version_curso"
    }
}
