package elearning.ai

import elearning.course.GUARD_ORDER
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import wasichai.core.common.ConflictException
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordService
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard
import java.util.UUID

// un borrador GENERADO (DraftValidationStore.isGenerated) no entra en revisión sin su informe de validación (RF-IA-05):
// 409 si no tiene ninguno, si el último no corresponde al contenido actual (otro hash: se editó después de validarlo) o si
// tiene errores bloqueantes. Para toda escritura que lleve version_curso de BORRADOR a EN_REVISION: el envío del estudio y
// la API genérica de registros. Un borrador escrito a mano no pasa por aquí
@Component
@Order(GUARD_ORDER)
class GeneratedDraftGuard(
    private val store: DraftValidationStore,
    private val executions: AgentExecutionStore,
    private val snapshots: DraftSnapshots,
    // RecordService recibe la lista de guardas: se pide perezosamente para no formar un ciclo
    private val records: ObjectProvider<RecordService>,
) : RecordWriteGuard {
    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.objectName != VERSION || change.kind == RecordChangeKind.CREATED || change.kind == RecordChangeKind.DELETED) return
        val attributes = change.attributes.orEmpty()
        val from = change.before?.get(STATE)?.toString() ?: DRAFT
        if (from != DRAFT || !attributes.containsKey(STATE) || attributes[STATE]?.toString() != IN_REVIEW) return
        val organization = change.organizationId
        val id: UUID = change.recordId ?: return
        if (!store.isGenerated(organization, id)) return
        val report =
            store.latestValidation(organization, id)?.let { executions.find(organization, it) }?.validations
                ?: throw ConflictException("A generated draft goes to review with its validation report: ask for a validation first")
        val current = DraftValidator.hash(snapshots.of(records.getObject().get(VERSION, id)))
        if (report.path("contentHash").asString() != current) {
            throw ConflictException("The validation report does not match the current draft: it changed after validating, validate it again")
        }
        if (report.path("blocking").asBoolean()) {
            val count = report.path("problemsBySeverity").path(ValidationSeverity.BLOCKING.name).asInt()
            throw ConflictException("The validation report of this generated draft has $count blocking problems")
        }
    }

    private companion object {
        const val VERSION = "version_curso"
        const val STATE = "estado"
        const val DRAFT = "BORRADOR"
        const val IN_REVIEW = "EN_REVISION"
    }
}
