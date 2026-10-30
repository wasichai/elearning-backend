package elearning.course

import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard

// RF-ACA-05: un curso dura de 1 a 8 semanas o un semestre. la metadata solo sabe que semanas es un entero y duracion un
// enum; el rango depende de los dos y se comprueba aquí, para toda escritura (la API genérica de registros incluida y
// también la plataforma: es un dato inválido, no un permiso). En una edición, lo enviado se combina con lo guardado
@Component
@Order(GUARD_ORDER)
class CourseDurationGuard : RecordWriteGuard {
    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.objectName != EditorialWorkflowGuard.VERSION || change.kind == RecordChangeKind.DELETED) return
        val attributes = change.attributes.orEmpty()
        if (change.kind != RecordChangeKind.CREATED && DURATION !in attributes && WEEKS !in attributes) return

        fun current(name: String) = if (name in attributes) attributes[name] else change.before?.get(name)
        val kind = current(DURATION)?.toString()?.takeIf { it.isNotBlank() } ?: CourseDuration.WEEKS
        // sin semanas decide la metadata (es requerido); un valor que no es entero, también
        val weeks = current(WEEKS)?.toString()?.trim()?.toBigDecimalOrNull() ?: return
        val problem =
            if (weeks.stripTrailingZeros().scale() > 0) {
                "must be a whole number of weeks"
            } else {
                CourseDuration.problem(kind, weeks.toInt())
            }
        if (problem != null) throw ValidationException("Invalid course duration: $problem", WEEKS, problem)
    }

    private companion object {
        const val DURATION = "duracion"
        const val WEEKS = "semanas"
    }
}

// la regla, sin Spring ni Wasichai: de 1 a 8 semanas, o un semestre de verdad (de 12 a 26, con margen para exámenes y
// calendarios largos). null si la duración es válida; si no, el motivo
object CourseDuration {
    const val WEEKS = "SEMANAS"
    const val SEMESTER = "SEMESTRAL"

    private val RANGES = mapOf(WEEKS to ("a course by weeks" to 1..8), SEMESTER to ("a semester course" to 12..26))

    fun problem(
        kind: String,
        weeks: Int,
    ): String? {
        val (what, range) = RANGES[kind] ?: return "unknown duration '$kind'"
        return if (weeks in range) null else "$what lasts ${range.first} to ${range.last} weeks"
    }
}
