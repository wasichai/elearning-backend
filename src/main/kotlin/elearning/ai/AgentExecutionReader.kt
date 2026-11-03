package elearning.ai

import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.util.UUID

// la lectura del registro AgentRun (docs/api/lms.md, ADR-0033 §7), con el mismo alcance que el curso: el curso se lee
// como quien llama, así que un curso que no ve (o que no existe) es 404 y sus ejecuciones también. Además pide UPDATE
// sobre version_curso, lo que tienen quienes trabajan el contenido (CREADOR y REVISOR, docs/ai §8): el resto es 403, sin
// mirar roles. GENERAR y VALIDAR (AiPermissions) protegen encolar y validar un paso, no esta lectura
@Service
class AgentExecutionReader(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val metadata: MetadataService,
    private val store: AgentExecutionStore,
) {
    suspend fun ofCourse(courseId: UUID): List<AgentExecutionLine> {
        val organization = requireReader()
        records.get(COURSE, courseId)
        return store.ofCourse(organization, courseId, LIMIT)
    }

    suspend fun find(id: UUID): AgentExecution {
        val organization = requireReader()
        val run = store.find(organization, id) ?: throw NotFoundException("Agent execution $id not found")
        try {
            records.get(COURSE, run.courseId)
        } catch (_: NotFoundException) {
            // el curso no se ve: la ejecución tampoco existe para quien llama
            throw NotFoundException("Agent execution $id not found")
        } catch (_: ForbiddenException) {
            throw NotFoundException("Agent execution $id not found")
        }
        return run
    }

    private suspend fun requireReader(): UUID {
        val user = currentUser.require()
        currentUser.requirePermission(user, Actions.UPDATE, metadata.definitionOf(VERSION).obj.id)
        return user.organizationId
    }

    private companion object {
        const val COURSE = "curso"
        const val VERSION = "version_curso"
        const val LIMIT = 100
    }
}
