package elearning.media

import elearning.common.ref
import elearning.jobs.JobQueue
import elearning.jobs.JobState
import elearning.jobs.JobStatus
import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.util.UUID

// un trabajo de generación tal como lo ve quien lo pidió (docs/api/lms.md)
data class RenderJob(
    val id: String,
    val status: String,
    val error: String?,
    val versionId: String?,
    val versionNumber: Int?,
    val artifacts: List<GeneratedArtifact>,
)

// pedir y seguir una generación (ADR-0018 §7). La generación crea una version_recurso en nombre de quien la pide, así que
// exige lo mismo que crearla a mano: CREATE sobre version_recurso, y leer la fuente y su recurso como esa persona. El
// trabajo corre luego como la plataforma, en la cola: aquí nada bloquea
@Service
class RenderService(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val metadata: MetadataService,
    private val queue: JobQueue,
) {
    suspend fun request(sourceId: UUID): RenderJob {
        val user = currentUser.require()
        requireCreator()
        val source = records.get(RenderJobHandler.SOURCE, sourceId)
        val resourceId = source.attributes.ref(RenderJobHandler.RESOURCE) ?: throw NotFoundException("Source $sourceId has no resource")
        val type = records.get(RenderJobHandler.RESOURCE, UUID.fromString(resourceId)).attributes[RenderJobHandler.TYPE_FIELD]?.toString()
        if (RenderJobHandler.primaryFormat(type) == null) throw ConflictException("Only HTML or PDF resources are generated from a source, not $type")
        val payload =
            RenderJobHandler.MAPPER.writeValueAsString(
                mapOf(RenderJobHandler.SOURCE_ID to sourceId.toString(), RenderJobHandler.REQUESTER_ID to user.userId.toString()),
            )
        val id = queue.enqueue(user.organizationId, RenderJobHandler.TYPE, payload)
        return find(id)
    }

    // solo los trabajos de generación de la organización, y solo para quien puede pedirlos
    suspend fun find(id: UUID): RenderJob {
        val user = currentUser.require()
        requireCreator()
        val status = queue.find(user.organizationId, id)?.takeIf { it.type == RenderJobHandler.TYPE } ?: throw NotFoundException("Render job $id not found")
        return view(status)
    }

    private suspend fun requireCreator() {
        val user = currentUser.require()
        currentUser.requirePermission(user, Actions.CREATE, metadata.definitionOf(RenderJobHandler.VERSION).obj.id)
    }

    private fun view(status: JobStatus): RenderJob {
        val result = status.result?.let { RenderJobHandler.MAPPER.readTree(it) }
        return RenderJob(
            id = status.id.toString(),
            status = STATUS.getValue(status.state),
            error = status.error,
            versionId = result?.get("versionId")?.asString(),
            versionNumber = result?.get("versionNumber")?.asInt(),
            artifacts =
                result?.get("artifacts")?.values()?.map { GeneratedArtifact(it["id"].asString(), it["format"].asString(), it["sha256"].asString()) }
                    ?: emptyList(),
        )
    }

    private companion object {
        // los mismos nombres que el trabajo de importación (docs/api/lms.md)
        val STATUS =
            mapOf(
                JobState.QUEUED to "PENDIENTE",
                JobState.RUNNING to "EN_CURSO",
                JobState.DONE to "HECHO",
                JobState.FAILED to "FALLIDO",
            )
    }
}
