package elearning.course

import elearning.common.DetectedFileTypes
import elearning.common.FileTypes
import elearning.common.fileContentType
import elearning.common.listAll
import elearning.common.ref
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordService
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard
import java.util.UUID

// RNF-SEG-07 (ADR-0018 §8): el archivo de una versión de recurso es de un tipo que admite el tipo de su recurso
// (FileTypes.RESOURCE), sobre lo que wasichai-files detectó. Para toda escritura, la plataforma incluida: es un dato
// inválido, no un permiso. Se comprueba al poner un archivo, al mover la versión a otro recurso y al cambiar el tipo de un
// recurso cuyas versiones ya tienen archivo
@Component
@Order(GUARD_ORDER)
class FileTypeGuard(
    private val detected: DetectedFileTypes,
    // RecordService recibe la lista de guardas: se pide perezosamente para no formar un ciclo
    private val records: ObjectProvider<RecordService>,
) : RecordWriteGuard {
    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.kind != RecordChangeKind.CREATED && change.kind != RecordChangeKind.UPDATED) return
        when (change.objectName) {
            RESOURCE_VERSION -> version(change)
            RESOURCE -> resource(change)
        }
    }

    private suspend fun version(change: RecordWrite) {
        val attributes = change.attributes.orEmpty()
        val before = change.before.orEmpty()
        val incoming = detected.incoming(change, FILE)
        val moved = attributes.containsKey(RESOURCE) && attributes.ref(RESOURCE) != before.ref(RESOURCE)
        val contentType = incoming ?: (if (moved && !attributes.containsKey(FILE)) fileContentType(before[FILE]) else null) ?: return
        val resource = (attributes.ref(RESOURCE) ?: before.ref(RESOURCE))?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        val type =
            records
                .getObject()
                .get(RESOURCE, resource)
                .attributes[TYPE]
                ?.toString()
        FileTypes.require(contentType, FileTypes.allowedForResource(type), FILE, "a $type resource")
    }

    private suspend fun resource(change: RecordWrite) {
        if (change.kind != RecordChangeKind.UPDATED) return
        val attributes = change.attributes.orEmpty()
        if (!attributes.containsKey(TYPE)) return
        val type = attributes[TYPE]?.toString()
        if (type == change.before?.get(TYPE)?.toString()) return
        val allowed = FileTypes.allowedForResource(type)
        records
            .getObject()
            .listAll(RESOURCE_VERSION, mapOf(RESOURCE to change.recordId.toString()))
            .mapNotNull { fileContentType(it.attributes[FILE]) }
            .forEach { FileTypes.require(it, allowed, TYPE, "a $type resource, as one of its versions holds it") }
    }

    private companion object {
        const val RESOURCE = "recurso"
        const val RESOURCE_VERSION = "version_recurso"
        const val FILE = "archivo"
        const val TYPE = "tipo"
    }
}
