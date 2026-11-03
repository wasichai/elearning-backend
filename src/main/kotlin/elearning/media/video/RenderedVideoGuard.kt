package elearning.media.video

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

// el vídeo renderizado de una versión de recurso (RF-MED-03, ADR-0040 §4 y §5), para toda escritura de una persona, la API
// genérica de registros incluida (con el render apagado también: el campo existe igual):
// - el campo video solo lo escribe la plataforma, dentro del trabajo de render y después de validar el vídeo contra su
//   guion: nadie lo pone, lo cambia ni lo quita (403), ADMIN incluido. así, una versión con video tiene un vídeo validado
// - una versión de un recurso VIDEO no entra en revisión, ni se aprueba ni se publica sin vídeo: el renderizado y validado o
//   el archivo subido a mano, como hasta ahora (409). un render fallido o incompleto no crea versión, y una versión sin
//   ninguno de los dos no se ofrece para publicar. el borrador sin vídeo sí existe: el archivo se sube después de crearlo,
//   y por eso tampoco se mira al crear directamente en otro estado (importar historia, que pide la acción de ese estado)
// la plataforma (userId nulo) es código de confianza y no pasa por aquí. va después de EditorialWorkflowGuard: quien no
// puede dar el paso recibe antes su 403
@Component
@Order(GUARD_ORDER + 10)
class RenderedVideoGuard(
    // RecordService recibe la lista de guardas: se pide perezosamente para no formar un ciclo
    private val records: ObjectProvider<RecordService>,
) : RecordWriteGuard {
    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.userId == null || change.objectName != VERSION) return
        if (change.kind != RecordChangeKind.CREATED && change.kind != RecordChangeKind.UPDATED) return
        val before = change.before.orEmpty()
        val attributes = change.attributes.orEmpty()
        if (attributes.containsKey(VIDEO) && fileId(attributes[VIDEO]) != fileId(before[VIDEO])) throw ForbiddenException(PLATFORM_ONLY)
        if (change.kind != RecordChangeKind.UPDATED || !attributes.containsKey(STATE)) return
        val from = before[STATE]?.toString() ?: DRAFT
        val to = attributes[STATE]?.toString() ?: DRAFT
        if (from == to || to !in NEEDS_VIDEO) return
        val resource = (attributes.ref(RESOURCE) ?: before.ref(RESOURCE))?.let(UUID::fromString) ?: return
        val type =
            records
                .getObject()
                .get(RESOURCE, resource)
                .attributes[TYPE]
                ?.toString()
        if (type != VIDEO_TYPE) return
        val file = if (attributes.containsKey(FILE)) attributes[FILE] else before[FILE]
        if (fileId(before[VIDEO]) == null && fileId(file) == null) throw ConflictException(NO_VIDEO)
    }

    // un archivo llega como su id o como su descriptor ({ id, sha256, … })
    private fun fileId(value: Any?): String? =
        when (value) {
            null -> null
            is Map<*, *> -> value["id"]?.toString()
            else -> value.toString()
        }?.takeIf { it.isNotBlank() }

    companion object {
        const val VERSION = "version_recurso"
        const val RESOURCE = "recurso"
        const val TYPE = "tipo"
        const val VIDEO_TYPE = "VIDEO"
        const val VIDEO = "video"
        const val FILE = "archivo"
        const val STATE = "estado"
        const val DRAFT = "BORRADOR"
        val NEEDS_VIDEO = setOf("EN_REVISION", "APROBADA", "PUBLICADA")

        const val PLATFORM_ONLY = "The rendered video of a resource version is written only by the video render, after validating it"
        const val NO_VIDEO =
            "A VIDEO resource version without a validated video cannot be submitted for review, approved or published: " +
                "render it from a script (POST /api/lms/studio/resources/{id}/video-renders) or upload its file first"
    }
}
