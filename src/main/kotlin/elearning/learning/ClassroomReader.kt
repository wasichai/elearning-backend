package elearning.learning

import org.springframework.stereotype.Component
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// arma la vista de una matrícula leyendo como el estudiante: sus alcances (ADR-0012/0013) deciden qué ve
@Component
class ClassroomReader(
    private val records: RecordService,
) {
    suspend fun view(enrollment: RecordResponse): EnrollmentView {
        val a = enrollment.attributes
        val version = records.get(VERSION, UUID.fromString(a.ref(VERSION)!!))
        val course = records.get(COURSE, UUID.fromString(version.attributes.ref(COURSE)!!))
        val offering = records.get(OFFERING, UUID.fromString(a.ref(OFFERING_FIELD)!!))
        val lessons = lessonsInOrder(version.id).filter { it.type !in NOT_YET }
        // F2 sin progreso guardado todavía (E14): ninguna completada
        val completed = 0
        return EnrollmentView(
            id = enrollment.id,
            status = a[STATE].toString(),
            enrolledAt = a["matriculado_en"]?.toString()?.let { runCatching { Instant.parse(it) }.getOrNull() },
            course = CourseRef(course.text("slug"), course.text("codigo"), course.text("titulo")),
            version = (version.attributes["numero"] as? Number)?.toInt() ?: 0,
            offering =
                OfferingRef(
                    code = offering.text("codigo"),
                    status = offering.text(STATE),
                    startDate = LocalDate.parse(offering.text("fecha_inicio").take(10)),
                    endDate = LocalDate.parse(offering.text("fecha_fin").take(10)),
                    timeZone = offering.text("zona_horaria"),
                ),
            progress = ProgressSummary(completed, lessons.size, if (lessons.isEmpty()) 0 else completed * 100 / lessons.size),
            nextLesson = lessons.firstOrNull()?.let { NextLesson(it.id, it.title, it.unitOrder, it.order) },
        )
    }

    data class OrderedLesson(
        val id: String,
        val title: String,
        val type: String,
        val unitOrder: Int,
        val order: Int,
    )

    // las lecciones de la versión en orden (unidad, lección)
    suspend fun lessonsInOrder(version: String): List<OrderedLesson> =
        all(UNIT, mapOf(VERSION to version))
            .sortedWith(compareBy({ it.int("orden") }, { it.id }))
            .flatMap { unit ->
                all(LESSON, mapOf(UNIT to unit.id))
                    .sortedWith(compareBy({ it.int("orden") }, { it.id }))
                    .map { OrderedLesson(it.id, it.text("titulo"), it.text("tipo"), unit.int("orden"), it.int("orden")) }
            }

    private suspend fun all(
        objectName: String,
        filters: Map<String, String>,
    ): List<RecordResponse> {
        val found = mutableListOf<RecordResponse>()
        var cursor: String? = null
        do {
            val page = records.list(objectName, RecordQuery(page = PageRequest(0, PageRequest.MAX_SIZE), filters = filters, count = false, after = cursor))
            found += page.content
            cursor = page.nextCursor
        } while (cursor != null)
        return found
    }

    companion object {
        const val VERSION = "version_curso"
        const val COURSE = "curso"
        const val OFFERING = "edicion_curso"
        const val OFFERING_FIELD = "edicion"
        const val UNIT = "unidad"
        const val LESSON = "leccion"
        const val STATE = "estado"

        // se completan en F3 (evaluación y sesión): no cuentan para el progreso de F2
        val NOT_YET = setOf("EVALUACION", "SESION")
    }
}

private fun RecordResponse.text(name: String): String = attributes[name]?.toString().orEmpty()

private fun RecordResponse.int(name: String): Int = (attributes[name] as? Number)?.toInt() ?: attributes[name]?.toString()?.toIntOrNull() ?: 0

private fun Map<String, Any?>.ref(name: String): String? =
    when (val v = this[name]) {
        is Map<*, *> -> v["id"]?.toString()
        null -> null
        else -> v.toString().takeIf { it.isNotBlank() }
    }
