package elearning.authoring

import elearning.common.listAll
import elearning.common.listAllIn
import elearning.common.ref
import org.springframework.stereotype.Service
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService

// las diferencias de una versión que quien llama ya puede ver (el estudio o la bandeja lo comprueban antes) con la anterior
// del mismo curso: la de número más alto por debajo del suyo. todo se lee como la persona, así que sus alcances aplican
@Service
class VersionDiffer(
    private val records: RecordService,
) {
    suspend fun againstPrevious(version: RecordResponse): VersionDiff {
        val number = number(version)
        val base =
            version.attributes
                .ref(COURSE)
                ?.let { course -> records.listAll(VERSION, mapOf(COURSE to course)) }
                .orEmpty()
                .filter { number(it) < number }
                .maxByOrNull { number(it) }
        val units = VersionComparison.compare(base?.let { snapshot(it.id) }, snapshot(version.id))
        return VersionDiff(base?.let { number(it) }, units)
    }

    // el temario por niveles: unidades, sus lecciones y sus enlaces, cada nivel en una lectura (L4 de #216)
    private suspend fun snapshot(version: String): List<UnitSnapshot> {
        val units = records.listAll(UNIT, mapOf(VERSION to version))
        val lessons = records.listAllIn(LESSON, UNIT, units.map { it.id })
        val links = records.listAllIn(LINK, LESSON, lessons.map { it.id }).groupBy { it.attributes.ref(LESSON) }
        val environments = records.listAllIn(LAB_ENVIRONMENT, LESSON, lessons.map { it.id }).groupBy { it.attributes.ref(LESSON) }
        val byUnit = lessons.groupBy { it.attributes.ref(UNIT) }
        return units.map { unit ->
            UnitSnapshot(
                order = order(unit),
                title = unit.attributes[TITLE]?.toString(),
                values = values(unit, VERSION),
                lessons =
                    byUnit[unit.id].orEmpty().map { lesson ->
                        LessonSnapshot(
                            order = order(lesson),
                            title = lesson.attributes[TITLE]?.toString(),
                            values =
                                values(lesson, UNIT) +
                                    mapOf(RESOURCES to resources(links[lesson.id].orEmpty()), ENVIRONMENTS to environments(environments[lesson.id].orEmpty())),
                        )
                    },
            )
        }
    }

    // los recursos enlazados, en su orden: cambiar uno, quitarlo o reordenarlos cambia la lección
    private fun resources(links: List<RecordResponse>): String =
        links
            .sortedBy { order(it) }
            .mapNotNull { it.attributes.ref(RESOURCE_VERSION) }
            .joinToString(",")

    // los entornos de un laboratorio, en su orden (ADR-0027): cambiar su título o su enlace cambia la lección
    private fun environments(environments: List<RecordResponse>): String =
        environments
            .sortedBy { order(it) }
            .joinToString(",") { "${it.attributes[TITLE]} <${it.attributes[LINK_FIELD]}>" }

    // los valores propios del registro: sin su orden (es la identidad), su padre ni otras relaciones
    private fun values(
        record: RecordResponse,
        parent: String,
    ): Map<String, Any?> = record.attributes.filter { (k, v) -> k != ORDER && k != parent && v !is Map<*, *> }

    private fun order(record: RecordResponse) = (record.attributes[ORDER] as? Number)?.toInt() ?: 0

    private fun number(version: RecordResponse) = (version.attributes[NUMBER] as? Number)?.toInt() ?: 0

    companion object {
        const val COURSE = "curso"
        const val VERSION = "version_curso"
        const val UNIT = "unidad"
        const val LESSON = "leccion"
        const val LINK = "recurso_leccion"
        const val RESOURCE_VERSION = "version_recurso"
        const val LAB_ENVIRONMENT = "entorno_laboratorio"
        const val LINK_FIELD = "enlace"
        const val NUMBER = "numero"
        const val ORDER = "orden"
        const val TITLE = "titulo"

        // el nombre con que los recursos enlazados aparecen en fields
        const val RESOURCES = "recursos"
        const val ENVIRONMENTS = "entornos"
    }
}
