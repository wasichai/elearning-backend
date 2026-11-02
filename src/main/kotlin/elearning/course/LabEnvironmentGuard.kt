package elearning.course

import elearning.course.meeting.SafeLink
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard

// RF-LAB-03 y RNF-SEG-08 (ADR-0027): el enlace de un entorno de laboratorio es https y público, con la misma regla que el
// de una sesión (SafeLink). el servidor nunca lo abre: solo lo muestra a los matriculados. para toda escritura, la
// plataforma incluida: es un dato inválido, no un permiso
@Component
@Order(GUARD_ORDER)
class LabEnvironmentGuard : RecordWriteGuard {
    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.objectName != ENVIRONMENT) return
        if (change.kind != RecordChangeKind.CREATED && change.kind != RecordChangeKind.UPDATED) return
        val attributes = change.attributes.orEmpty()
        if (change.kind == RecordChangeKind.UPDATED && !attributes.containsKey(LINK)) return
        val link = attributes[LINK]?.toString().orEmpty()
        if (!SafeLink.isAllowed(link)) throw ValidationException("Invalid environment link", LINK, "must be a public https link")
    }

    private companion object {
        const val ENVIRONMENT = "entorno_laboratorio"
        const val LINK = "enlace"
    }
}
