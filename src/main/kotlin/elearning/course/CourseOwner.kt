package elearning.course

import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordValueProvider
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.metadata.ObjectDefinition

// #345: el dueño de un curso es quien lo crea (curso.propietario_id). Lo pone el servidor al crear, por la SPI de
// Wasichai (ADR-077): una sola fila, una sola entrada de auditoría y la respuesta del POST ya lo trae. El campo está
// oculto y es de solo lectura en la metadata, así que el formulario de core no lo muestra, un PUT sin él no lo borra
// y un PATCH que lo nombra es un 403 de Wasichai. No da ni quita permisos: no se mira en ninguna autorización (#270).
//
// - con usuario (ADMIN incluido): el dueño es quien llama; mandar otro id es un 400, mandar el propio no cambia nada
// - la plataforma (asPlatform, automatizaciones): sin dueño. Hoy ningún código de la plataforma crea cursos; si lo
//   hiciera con un dueño explícito, es un 400 en vez de perderse sin aviso
// - al actualizar, el guard rechaza con 400 un PUT que cambie el dueño (el PUT de Wasichai ignoraría el valor en
//   silencio); el mismo valor, o no mandarlo, pasa
@Component
@Order(GUARD_ORDER)
class CourseOwner :
    RecordValueProvider,
    RecordWriteGuard {
    override suspend fun valuesFor(
        definition: ObjectDefinition,
        attributes: Map<String, Any?>,
        caller: AuthenticatedUser?,
    ): Map<String, Any?> {
        if (definition.obj.name != COURSE) return emptyMap()
        val owner = caller?.userId?.toString()
        val sent = attributes[OWNER]?.toString()?.takeIf { it.isNotBlank() }
        if (sent != null && !sent.equals(owner, ignoreCase = true)) {
            throw ValidationException("The course owner is whoever creates it", OWNER, "is set by the server to whoever creates the course")
        }
        return owner?.let { mapOf(OWNER to it) }.orEmpty()
    }

    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.objectName != COURSE || change.kind != RecordChangeKind.UPDATED) return
        val attributes = change.attributes.orEmpty()
        if (OWNER !in attributes) return
        val sent = attributes[OWNER]?.toString()?.takeIf { it.isNotBlank() }
        val stored = change.before?.get(OWNER)?.toString()
        if (!sent.equals(stored, ignoreCase = true)) {
            throw ValidationException("The course owner does not change", OWNER, "is whoever created the course and cannot be changed")
        }
    }

    companion object {
        const val COURSE = "curso"
        const val OWNER = "propietario_id"
    }
}
