package elearning.course

import elearning.common.listAll
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import wasichai.core.common.ConflictException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordService
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard

// RF-CAT-08 (ADR-0028): las claves de conocimientos previos de una versión son las del vocabulario conocimiento_previo,
// una por línea. la metadata solo sabe que es un texto largo; esto lo hace cumplir para toda escritura (la API genérica
// incluida). una clave declarada no cambia ni se borra: la nombran versiones publicadas, que no cambian
@Component
@Order(GUARD_ORDER)
class PrerequisiteKnowledgeGuard(
    // RecordService recibe la lista de guardas: se pide perezosamente para no formar un ciclo
    private val records: ObjectProvider<RecordService>,
) : RecordWriteGuard {
    override suspend fun beforeWrite(change: RecordWrite) {
        when (change.objectName) {
            EditorialWorkflowGuard.VERSION -> version(change)
            VOCABULARY -> vocabulary(change)
        }
    }

    private suspend fun version(change: RecordWrite) {
        val attributes = change.attributes ?: return
        if (FIELD !in attributes) return
        val keys = PrerequisiteKnowledge.keys(attributes[FIELD]?.toString())
        val malformed = keys.filterNot { PrerequisiteKnowledge.isKey(it) }
        if (malformed.isNotEmpty()) throw ValidationException("Invalid prerequisite knowledge keys: ${malformed.joinToString()}", FIELD, MALFORMED)
        // la plataforma (importación, semilla) es código de confianza: el formato sí, el vocabulario lo comprueba la persona
        if (change.userId == null || keys.isEmpty()) return
        val declared = records.getObject().listAll(VOCABULARY).mapNotNull { it.attributes[KEY]?.toString() }.toSet()
        val unknown = keys.filter { it !in declared }
        if (unknown.isNotEmpty()) {
            throw ValidationException("Unknown prerequisite knowledge keys: ${unknown.joinToString()}", FIELD, "must be keys of $VOCABULARY")
        }
    }

    private fun vocabulary(change: RecordWrite) {
        if (change.kind == RecordChangeKind.DELETED) throw ConflictException(KEPT)
        val attributes = change.attributes.orEmpty()
        if (KEY !in attributes) return
        val key = attributes[KEY]?.toString()?.trim().orEmpty()
        if (!PrerequisiteKnowledge.isKey(key)) throw ValidationException("Invalid prerequisite knowledge key", KEY, MALFORMED)
        val before = change.before?.get(KEY)?.toString()
        if (change.kind != RecordChangeKind.CREATED && before != null && before != key) throw ConflictException(KEPT)
    }

    companion object {
        const val VOCABULARY = "conocimiento_previo"
        const val FIELD = "conocimientos_previos_claves"
        const val KEY = "clave"
        private const val MALFORMED = "lowercase letters, digits and hyphens, one key per line; 'none' is reserved"
        private const val KEPT = "A prerequisite knowledge key is named by published versions: it neither changes nor is deleted"
    }
}

// la regla, sin Spring ni Wasichai: la usan la guarda, la proyección y el filtro del catálogo
object PrerequisiteKnowledge {
    // el valor del filtro para «sin conocimientos previos»: ninguna clave puede llamarse así
    const val NONE = "none"

    private val KEY = Regex("^[a-z0-9][a-z0-9-]{0,49}$")

    fun isKey(value: String): Boolean = value != NONE && KEY.matches(value)

    // una por línea, sin espacios alrededor ni líneas vacías ni repetidas, en el orden en que se declararon
    fun keys(text: String?): List<String> =
        text
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
}
