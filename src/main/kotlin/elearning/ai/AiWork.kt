package elearning.ai

import elearning.course.GUARD_ORDER
import kotlinx.coroutines.currentCoroutineContext
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import wasichai.core.common.ForbiddenException
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// lo que hace un trabajo de IA: un paso de la canalización (AiStepRunner) o una validación (DraftValidationJobHandler).
// Corre como la plataforma, que EditorialWorkflowGuard deja pasar por ser código de confianza; esta marca es la que deja a
// AiWorkGuard negarle aprobar y publicar (regla 6, RF-IA-08, #288)
class AiWork : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AiWork>
}

// la IA propone, una persona aprueba: dentro de un trabajo de IA ninguna versión (de curso o de recurso) se crea ni pasa a
// aprobada o publicada, ni recibe fecha de publicación (403). Aprobar sigue siendo de la bandeja, con una persona distinta
// del autor (EditorialWorkflowGuard), y publicar, de quien tiene PUBLICAR. Va antes que las demás guardas: lo que diga otra
// no cambia la respuesta
@Component
@Order(GUARD_ORDER - 1)
class AiWorkGuard : RecordWriteGuard {
    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.objectName !in VERSIONS || change.kind == RecordChangeKind.DELETED) return
        if (currentCoroutineContext()[AiWork] == null) return
        val attributes = change.attributes.orEmpty()
        val before = if (change.kind == RecordChangeKind.CREATED) emptyMap() else change.before.orEmpty()
        val state = attributes[STATE]?.toString()
        if (attributes.containsKey(STATE) && state in DECIDED && state != before[STATE]?.toString()) throw ForbiddenException(MESSAGE)
        val published = attributes[PUBLISHED_AT]?.toString()?.takeIf { it.isNotBlank() }
        if (published != null && published != before[PUBLISHED_AT]?.toString()) throw ForbiddenException(MESSAGE)
    }

    private companion object {
        val VERSIONS = setOf("version_curso", "version_recurso")
        val DECIDED = setOf("APROBADA", "PUBLICADA")
        const val STATE = "estado"
        const val PUBLISHED_AT = "publicada_en"
        const val MESSAGE = "An AI job neither approves nor publishes: a person does (RF-IA-08)"
    }
}
