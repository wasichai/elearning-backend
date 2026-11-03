package elearning.ai

import elearning.common.ref
import elearning.course.ReviewDecisionWrite
import kotlinx.coroutines.currentCoroutineContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordChangeListener
import wasichai.core.data.RecordService
import java.time.Clock
import java.util.UUID

// las intervenciones humanas sobre un borrador GENERADO (RF-IA-03, #288, docs/ai §14): cada decisión de una persona sobre lo
// que propuso la IA se añade a la ejecución que lo generó (DraftValidationStore.generatingRun), la más reciente si hay
// varias. Wasichai avisa a los listeners justo después de escribir y en la transacción de quien escribe (su ADR-038), así
// que la intervención se confirma o se deshace con el cambio:
//
// - ACCEPTED: el revisor aprueba la versión en la bandeja (EN_REVISION -> APROBADA), con el motivo si lo dio
// - REJECTED: el revisor la rechaza (EN_REVISION -> RETIRADA), con su motivo; o una persona borra el borrador generado
// - EDITED: una persona guarda un cambio en el contenido del borrador o de su temario (unidades, lecciones, evaluaciones,
//   preguntas, opciones, criterios, entornos, recursos enlazados). La nota dice qué registro y qué campos, nunca su texto
//
// Pedir cambios no es ninguna de las tres: lo que venga después lo dejan las ediciones. La plataforma (userId nulo), y con
// ella todo trabajo de IA, no interviene. Un borrador escrito a mano no tiene ejecución y no deja nada
@Component
class DraftInterventionListener(
    private val store: DraftValidationStore,
    private val log: AgentExecutionLog,
    // RecordService recibe la lista de listeners: se pide perezosamente para no formar un ciclo
    private val records: ObjectProvider<RecordService>,
    private val clock: Clock,
) : RecordChangeListener {
    override suspend fun recordChanged(change: RecordChange) {
        val user = change.userId ?: return
        if (change.objectName != VERSION && change.objectName !in PARENT) return
        val (kind, note) = intervention(change) ?: return
        val version = versionOf(change) ?: return
        val run = store.generatingRun(change.organizationId, version) ?: return
        log.intervene(change.organizationId, run, Intervention(user, kind, clock.instant(), note))
    }

    private suspend fun intervention(change: RecordChange): Pair<InterventionKind, String>? {
        val before = change.before.orEmpty()
        val after = change.after.orEmpty()
        val record = "${change.objectName} ${change.recordId}"
        if (change.objectName == VERSION) {
            val from = before[STATE]?.toString()
            val to = after[STATE]?.toString()
            when {
                change.kind == RecordChangeKind.CREATED -> return null
                change.kind == RecordChangeKind.DELETED -> return InterventionKind.REJECTED to "$record deleted"
                from == IN_REVIEW && to == APPROVED -> return InterventionKind.ACCEPTED to decision(APPROVED)
                from == IN_REVIEW && to == RETIRED -> return InterventionKind.REJECTED to decision(RETIRED)
            }
            val fields = changed(before, after) - NOT_CONTENT
            return if (fields.isEmpty()) null else InterventionKind.EDITED to "$record: ${fields.sorted().joinToString()}"
        }
        return when (change.kind) {
            RecordChangeKind.CREATED -> InterventionKind.EDITED to "$record created"
            RecordChangeKind.DELETED -> InterventionKind.EDITED to "$record deleted"
            else -> changed(before, after).takeIf { it.isNotEmpty() }?.let { InterventionKind.EDITED to "$record: ${it.sorted().joinToString()}" }
        }
    }

    // la decisión de la bandeja lleva su motivo (ReviewDecisionWrite); sin él, solo el estado al que llevó la versión
    private suspend fun decision(to: String): String {
        val reason = currentCoroutineContext()[ReviewDecisionWrite]?.reason?.takeIf { it.isNotBlank() }
        return if (reason == null) "review decision: $to" else "review decision: $to. $reason"
    }

    // la versión del registro, subiendo por su temario como lo lee quien escribe; si un eslabón ya no está o no lo ve, nada
    private suspend fun versionOf(change: RecordChange): UUID? {
        if (change.objectName == VERSION) return change.recordId
        var objectName = change.objectName
        var values = (if (change.kind == RecordChangeKind.DELETED) change.before else change.after).orEmpty()
        while (true) {
            val parent = PARENT[objectName] ?: return null
            val id = values.ref(parent)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            if (parent == VERSION) return id
            values =
                try {
                    records.getObject().get(parent, id).attributes
                } catch (_: NotFoundException) {
                    return null
                } catch (_: ForbiddenException) {
                    return null
                }
            objectName = parent
        }
    }

    // los campos que cambiaron: relaciones por su id, números por valor; lo que no es del registro (sus marcas) no cuenta
    private fun changed(
        before: Map<String, Any?>,
        after: Map<String, Any?>,
    ): Set<String> =
        (before.keys + after.keys)
            .filter { it !in SYSTEM && !it.startsWith("_") }
            .filter { normalized(before[it]) != normalized(after[it]) }
            .toSet()

    private fun normalized(value: Any?): String? {
        val text = (if (value is Map<*, *>) value["id"] else value)?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return text.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: text
    }

    private companion object {
        const val VERSION = "version_curso"
        const val STATE = "estado"
        const val IN_REVIEW = "EN_REVISION"
        const val APPROVED = "APROBADA"
        const val RETIRED = "RETIRADA"

        // el padre de cada registro del temario, hasta la versión
        val PARENT =
            mapOf(
                "unidad" to VERSION,
                "leccion" to "unidad",
                "evaluacion" to "leccion",
                "entorno_laboratorio" to "leccion",
                "recurso_leccion" to "leccion",
                "pregunta" to "evaluacion",
                "opcion" to "pregunta",
                "criterio_rubrica" to "pregunta",
            )

        // lo que cambia de una versión sin ser su contenido: el flujo editorial, el revisor, la publicación y los créditos
        val NOT_CONTENT = setOf(STATE, "revisor_id", "publicada_en", "creditos_aprobados", "politica_creditos")

        // marcas del registro, por si Wasichai las lleva junto a los atributos
        val SYSTEM = setOf("id", "created_at", "updated_at", "createdAt", "updatedAt", "created_by", "updated_by", "version")
    }
}
