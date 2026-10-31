package elearning.authoring

import elearning.common.listAll
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

    private suspend fun snapshot(version: String): List<UnitSnapshot> =
        records.listAll(UNIT, mapOf(VERSION to version)).map { unit ->
            UnitSnapshot(
                order = order(unit),
                title = unit.attributes[TITLE]?.toString(),
                values = values(unit, VERSION),
                lessons =
                    records.listAll(LESSON, mapOf(UNIT to unit.id)).map { lesson ->
                        LessonSnapshot(
                            order = order(lesson),
                            title = lesson.attributes[TITLE]?.toString(),
                            values = values(lesson, UNIT) + (RESOURCES to resources(lesson.id)),
                        )
                    },
            )
        }

    // los recursos enlazados, en su orden: cambiar uno, quitarlo o reordenarlos cambia la lección
    private suspend fun resources(lesson: String): String =
        records
            .listAll(LINK, mapOf(LESSON to lesson))
            .sortedBy { order(it) }
            .mapNotNull { it.attributes.ref(RESOURCE_VERSION) }
            .joinToString(",")

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
        const val NUMBER = "numero"
        const val ORDER = "orden"
        const val TITLE = "titulo"

        // el nombre con que los recursos enlazados aparecen en fields
        const val RESOURCES = "recursos"
    }
}
