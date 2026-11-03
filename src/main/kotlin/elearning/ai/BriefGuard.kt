package elearning.ai

import elearning.common.listAll
import elearning.course.GUARD_ORDER
import elearning.importer.AllowedSources
import elearning.importer.ImporterProperties
import elearning.importer.SourceRef
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import wasichai.core.common.Actions
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordService
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService

// el brief de un curso (RF-IA-01, E33.1), para toda escritura: la API genérica de registros incluida
//
// - lo escribe quien crea versiones del curso (CREATE sobre version_curso): darle el brief a un rol no basta. nunca por rol
// - se guarda a medias en borrador; pasa a LISTO (y sigue en LISTO) solo completo: 400 con la lista de lo que falta
// - cada fuente es owner/repo@sha:ruta de la lista permitida del importador (lms.importer.allowed-sources) o del
//   repositorio y commit de un paquete ya importado al curso (las fuentes de sus versiones); también en borrador
// - la plataforma (userId nulo) no necesita el permiso, pero sus datos cumplen lo mismo
//
// un paquete importado se reconoce por el campo fuentes de la versión que el importador creó, que el autor de un borrador
// también puede escribir: la barrera de lectura sigue siendo el adaptador de GitHub, que solo lee la lista permitida
@Component
@Order(GUARD_ORDER)
class BriefGuard(
    private val currentUser: CurrentUser,
    // RecordService recibe la lista de guardas: se pide perezosamente para no formar un ciclo
    private val records: ObjectProvider<RecordService>,
    private val metadata: ObjectProvider<MetadataService>,
    properties: ImporterProperties,
) : RecordWriteGuard {
    private val allowed = AllowedSources(properties.allowedSources)

    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.objectName != BRIEF) return
        if (change.userId != null) {
            val version =
                metadata
                    .getObject()
                    .definitionOf(VERSION)
                    .obj.id
            currentUser.requirePermission(currentUser.require(), Actions.CREATE, version)
        }
        if (change.kind == RecordChangeKind.DELETED) return
        val attributes = change.attributes.orEmpty()
        val brief = if (change.kind == RecordChangeKind.CREATED) attributes else change.before.orEmpty() + attributes
        val ready = (brief[STATE]?.toString() ?: DRAFT) == READY
        val hours = brief[HOURS]?.toString()?.toBigDecimalOrNull()
        if (hours != null && hours.signum() <= 0) throw ValidationException("Invalid brief duration", HOURS, "must be a positive number of hours")
        if (ready) {
            val missing = REQUIRED.filter { brief[it]?.toString().isNullOrBlank() }
            if (missing.isNotEmpty()) {
                throw ValidationException(
                    "Incomplete brief, missing: ${missing.joinToString()}",
                    missing.map { FieldViolation(it, "required to mark the brief $READY") },
                )
            }
        }
        if (ready || attributes.containsKey(SOURCES)) sources(brief)
    }

    private suspend fun sources(brief: Map<String, Any?>) {
        val lines = lines(brief[SOURCES]?.toString())
        val refs =
            lines.map {
                try {
                    SourceRef.parse(it)
                } catch (e: IllegalArgumentException) {
                    throw ValidationException("Invalid brief source '$it'", SOURCES, e.message ?: "expected owner/repo@sha:path")
                }
            }
        val pending = refs.filterNot { allowed.permits(it) }
        if (pending.isEmpty()) return
        val packages = imported(ref(brief[COURSE]))
        val refused = pending.filter { ref -> packages.none { it.repository.equals(ref.repository, ignoreCase = true) && it.sha == ref.sha } }
        if (refused.isNotEmpty()) {
            throw ValidationException(
                "Brief sources not allowed: ${refused.joinToString()}",
                SOURCES,
                "each source must be of lms.importer.allowed-sources or of a package already imported into the course",
            )
        }
    }

    // las raíces importadas al curso: las líneas owner/repo@sha:ruta de las fuentes de sus versiones
    private suspend fun imported(course: String?): List<SourceRef> {
        if (course == null) return emptyList()
        return records
            .getObject()
            .listAll(VERSION, mapOf(COURSE to course))
            .flatMap { lines(it.attributes[SOURCES]?.toString()) }
            .mapNotNull { runCatching { SourceRef.parse(it) }.getOrNull() }
    }

    private fun lines(text: String?): List<String> =
        text
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    // una relación puede llegar como id o expandida
    private fun ref(value: Any?): String? =
        when (value) {
            is Map<*, *> -> value["id"]?.toString()
            null -> null
            else -> value.toString().takeIf { it.isNotBlank() }
        }

    companion object {
        const val BRIEF = "brief"
        const val VERSION = "version_curso"
        const val COURSE = "curso"
        const val STATE = "estado"
        const val HOURS = "duracion_horas"
        const val SOURCES = "fuentes"
        const val DRAFT = "BORRADOR"
        const val READY = "LISTO"

        // lo que un brief LISTO tiene que traer (RF-IA-01)
        val REQUIRED = listOf("tematica", "publico", "objetivos", HOURS, "idioma", "nivel", "expertos", SOURCES)
    }
}
