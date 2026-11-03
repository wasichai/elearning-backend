package elearning.ai

import elearning.common.ref
import elearning.course.GUARD_ORDER
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordService
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard
import java.util.UUID

// el guion de un vídeo no cambia una vez publicado (regla 5, RF-MED-01), para toda escritura: la API genérica de registros
// incluida. guion_video es de solo inserción (appendOnly: nada lo actualiza ni lo borra) y solo lo escribe su trabajo
// (apiOnly, y ningún rol tiene CREATE); esta guarda cierra lo que queda:
//
// - una persona no lo escribe nunca: 403 aunque tuviera el permiso
// - la plataforma solo añade un guion a una version_recurso de un recurso VIDEO que sigue en BORRADOR (409 si no): una versión
//   en revisión, aprobada, publicada o retirada conserva los guiones que tenía. Cambiar el guion es otra versión del guion de un
//   borrador; cambiar el de un vídeo publicado, una versión nueva del recurso
@Component
@Order(GUARD_ORDER)
class VideoScriptGuard(
    // RecordService recibe la lista de guardas: se pide perezosamente para no formar un ciclo
    private val records: ObjectProvider<RecordService>,
) : RecordWriteGuard {
    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.objectName != VideoScriptStore.SCRIPT) return
        if (change.userId != null) throw ForbiddenException("A video script is written only by its AI job")
        if (change.kind != RecordChangeKind.CREATED) throw ConflictException("A video script never changes: write another version of it")
        val versionId = change.attributes.orEmpty().ref(VideoScriptStore.VERSION) ?: return
        val service = records.getObject()
        val version = service.get(VideoScriptStore.VERSION, UUID.fromString(versionId)).attributes
        if (version[VideoScriptStore.STATE]?.toString() != VideoScriptStore.DRAFT) {
            throw ConflictException("The resource version $versionId is not a draft: its scripts do not change")
        }
        val resource = version.ref(VideoScriptStore.RESOURCE)?.let { service.get(VideoScriptStore.RESOURCE, UUID.fromString(it)).attributes }
        if (resource?.get(VideoScriptStore.RESOURCE_TYPE)?.toString() != VideoScriptStore.VIDEO) {
            throw ConflictException("Only the version of a ${VideoScriptStore.VIDEO} resource has a script")
        }
    }
}
