package elearning.authoring

import java.math.BigDecimal

// las diferencias de una versión con la anterior (ADR-0018 §3, docs/api/lms.md). se calculan al pedirlas y no se guardan

data class VersionDiff(
    val baseVersion: Int?,
    val units: List<UnitDiff>,
)

data class UnitDiff(
    val order: Int,
    val change: String,
    val title: String?,
    val previousTitle: String?,
    val fields: List<String>,
    val lessons: List<LessonDiff>,
)

data class LessonDiff(
    val order: Int,
    val change: String,
    val title: String?,
    val previousTitle: String?,
    val fields: List<String>,
)

// lo que se compara de una lección o una unidad: su orden (la identidad estable), su título y sus valores por campo
data class LessonSnapshot(
    val order: Int,
    val title: String?,
    val values: Map<String, Any?>,
)

data class UnitSnapshot(
    val order: Int,
    val title: String?,
    val values: Map<String, Any?>,
    val lessons: List<LessonSnapshot>,
)

// dominio puro: una unidad es la misma si tiene el mismo orden; una lección, si tiene el mismo orden dentro de esa unidad.
// las identidades de los registros cambian con cada copia, así que no sirven para comparar
object VersionComparison {
    const val ADDED = "ADDED"
    const val REMOVED = "REMOVED"
    const val CHANGED = "CHANGED"
    const val SAME = "SAME"

    // sin versión anterior, todo es nuevo
    fun compare(
        base: List<UnitSnapshot>?,
        current: List<UnitSnapshot>,
    ): List<UnitDiff> {
        val before = (base ?: emptyList()).associateBy { it.order }
        val after = current.associateBy { it.order }
        return (before.keys + after.keys).sorted().map { order -> unit(order, before[order], after[order]) }
    }

    private fun unit(
        order: Int,
        before: UnitSnapshot?,
        after: UnitSnapshot?,
    ): UnitDiff {
        val beforeLessons = before?.lessons.orEmpty().associateBy { it.order }
        val afterLessons = after?.lessons.orEmpty().associateBy { it.order }
        val lessons = (beforeLessons.keys + afterLessons.keys).sorted().map { lesson(it, beforeLessons[it], afterLessons[it]) }
        val fields = if (before != null && after != null) changed(before.values, after.values) else emptyList()
        val change =
            when {
                before == null -> ADDED
                after == null -> REMOVED
                fields.isNotEmpty() || lessons.any { it.change != SAME } -> CHANGED
                else -> SAME
            }
        return UnitDiff(order, change, after?.title, before?.title, fields, lessons)
    }

    private fun lesson(
        order: Int,
        before: LessonSnapshot?,
        after: LessonSnapshot?,
    ): LessonDiff {
        val fields = if (before != null && after != null) changed(before.values, after.values) else emptyList()
        val change =
            when {
                before == null -> ADDED
                after == null -> REMOVED
                fields.isNotEmpty() -> CHANGED
                else -> SAME
            }
        return LessonDiff(order, change, after?.title, before?.title, fields)
    }

    // los campos cuyo valor difiere, por nombre. vacío y ausente valen lo mismo; 2 y 2.00, también
    private fun changed(
        before: Map<String, Any?>,
        after: Map<String, Any?>,
    ): List<String> = (before.keys + after.keys).filter { normalized(before[it]) != normalized(after[it]) }.sorted()

    private fun normalized(value: Any?): String? =
        when (value) {
            null -> null
            is Number -> runCatching { BigDecimal(value.toString()).stripTrailingZeros().toPlainString() }.getOrDefault(value.toString())
            else -> value.toString().takeIf { it.isNotBlank() }
        }
}
