package elearning.jobs

import org.springframework.stereotype.Component
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.RoleQueries
import wasichai.core.identity.UserDirectory
import wasichai.core.metadata.MetadataService
import java.util.UUID

// un permiso que se exige a quien pidió un trabajo: action sobre el objeto objectName
data class RequiredPermission(
    val action: String,
    val objectName: String,
)

// ADR-0022 (L7 de #216): un trabajo corre como la plataforma, pero en nombre de quien lo pidió, y sus permisos se miraron
// al encolar. antes de escribir, el manejador vuelve a mirar: que la persona siga habilitada en su organización y que sus
// roles guardados (los que pondría el próximo inicio de sesión, no los del token con el que encoló) le den todavía lo que
// se le exigió. lo decide Wasichai (CurrentUser.hasPermission); aquí no se mira ningún nombre de rol
@Component
class Requesters(
    private val users: UserDirectory,
    private val roles: RoleQueries,
    private val currentUser: CurrentUser,
    private val metadata: MetadataService,
) {
    // PermanentJobFailure si ya no puede: reintentar no se lo devuelve
    suspend fun requireStillAllowed(
        organization: UUID,
        requester: UUID,
        permissions: Collection<RequiredPermission>,
    ) {
        if (requester !in users.existing(organization, listOf(requester))) {
            throw PermanentJobFailure("The person who asked for this job is no longer an enabled user of the organization")
        }
        val user = AuthenticatedUser(requester, organization, "", roles.roleNamesOf(requester))
        for (permission in permissions) {
            val objectId = metadata.loadDefinition(organization, permission.objectName).obj.id
            if (!currentUser.hasPermission(user, permission.action, objectId)) {
                throw PermanentJobFailure("The person who asked for this job no longer has ${permission.action} on ${permission.objectName}")
            }
        }
    }
}
