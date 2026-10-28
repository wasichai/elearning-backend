package elearning.learning

import org.springframework.stereotype.Component
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// arma el aula leyendo como el estudiante: sus alcances (ADR-0012/0013) deciden qué ve. el aula es siempre la propia:
// quien ve todas las matrículas (un docente) no abre la de otro por aquí
@Component
class ClassroomReader(
    private val currentUser: CurrentUser,
    private val records: RecordService,
) {
    // mis matrículas activas, la de inicio más próximo primero
    suspend fun mine(): List<EnrollmentView> {
        val me = currentUser.require().userId.toString()
        return all(ENROLLMENT, mapOf(STUDENT to me, STATE to ACTIVE))
            .map { view(it) }
            .sortedWith(compareBy({ it.offering.startDate }, { it.id }))
    }

    suspend fun classroom(id: UUID): ClassroomView {
        val enrollment = records.get(ENROLLMENT, id)
        if (enrollment.attributes[STUDENT]?.toString() != currentUser.require().userId.toString()) throw NotFoundException("Matrícula no encontrada")
        val content = content(enrollment)
        val lessonIds =
            content.units
                .flatMap { it.lessons }
                .map { it.id }
                .toSet()
        val resources = resources(lessonIds)
        val units =
            content.units.map { unit ->
                UnitView(
                    id = unit.record.id,
                    order = unit.record.int(ORDER),
                    title = unit.record.text(TITLE),
                    description = unit.record.textOrNull("descripcion"),
                    progress = summary(unit.lessons, content.progress),
                    lessons =
                        unit.lessons.map { lesson ->
                            LessonView(
                                id = lesson.id,
                                order = lesson.int(ORDER),
                                title = lesson.text(TITLE),
                                type = lesson.text(TYPE),
                                durationMinutes = lesson.textOrNull("duracion_minutos")?.toIntOrNull(),
                                summary = lesson.textOrNull("resumen"),
                                completion = completion(lesson.text(TYPE)),
                                progress = LessonProgressService.progress(content.progress[lesson.id]),
                                resources = resources[lesson.id].orEmpty(),
                            )
                        },
                )
            }
        return ClassroomView(view(enrollment, content), units)
    }

    suspend fun view(enrollment: RecordResponse): EnrollmentView = view(enrollment, content(enrollment))

    private suspend fun view(
        enrollment: RecordResponse,
        content: Content,
    ): EnrollmentView {
        val a = enrollment.attributes
        val course = records.get(COURSE, UUID.fromString(content.version.attributes.ref(COURSE)!!))
        val offering = records.get(OFFERING, UUID.fromString(a.ref(OFFERING_FIELD)!!))
        val lessons = content.units.flatMap { it.lessons }
        val next = lessons.firstOrNull { countable(it) && !done(content.progress[it.id]) }
        val unitOrder = content.units.flatMap { u -> u.lessons.map { it.id to u.record.int(ORDER) } }.toMap()
        return EnrollmentView(
            id = enrollment.id,
            status = a[STATE].toString(),
            enrolledAt = a["matriculado_en"]?.toString()?.let { runCatching { Instant.parse(it) }.getOrNull() },
            course = CourseRef(course.text("slug"), course.text("codigo"), course.text(TITLE)),
            version = content.version.int("numero"),
            offering =
                OfferingRef(
                    code = offering.text("codigo"),
                    status = offering.text(STATE),
                    startDate = LocalDate.parse(offering.text("fecha_inicio").take(10)),
                    endDate = LocalDate.parse(offering.text("fecha_fin").take(10)),
                    timeZone = offering.text("zona_horaria"),
                ),
            progress = summary(lessons, content.progress),
            nextLesson = next?.let { NextLesson(it.id, it.text(TITLE), unitOrder.getValue(it.id), it.int(ORDER)) },
        )
    }

    private class Unit(
        val record: RecordResponse,
        val lessons: List<RecordResponse>,
    )

    private class Content(
        val version: RecordResponse,
        val units: List<Unit>,
        val progress: Map<String, RecordResponse>,
    )

    // la versión de la matrícula con sus unidades y lecciones en orden, y el progreso guardado por lección
    private suspend fun content(enrollment: RecordResponse): Content {
        val version = records.get(VERSION, UUID.fromString(enrollment.attributes.ref(VERSION)!!))
        val units =
            all(UNIT, mapOf(VERSION to version.id))
                .sortedWith(compareBy({ it.int(ORDER) }, { it.id }))
                .map { unit -> Unit(unit, all(LESSON, mapOf(UNIT to unit.id)).sortedWith(compareBy({ it.int(ORDER) }, { it.id }))) }
        val progress = all(PROGRESS, mapOf(ENROLLMENT to enrollment.id)).associateBy { it.attributes.ref(LESSON).orEmpty() }
        return Content(version, units, progress)
    }

    // los recursos de esas lecciones, en su orden; cada versión de recurso y su recurso se leen una vez
    private suspend fun resources(lessons: Set<String>): Map<String, List<ResourceView>> {
        val links = all(RESOURCE_LINK, emptyMap()).filter { it.attributes.ref(LESSON) in lessons }
        val versions = links.mapNotNull { it.attributes.ref(RESOURCE_VERSION) }.distinct().associateWith { records.get(RESOURCE_VERSION, UUID.fromString(it)) }
        val resources =
            versions.values
                .mapNotNull { it.attributes.ref(RESOURCE) }
                .distinct()
                .associateWith { records.get(RESOURCE, UUID.fromString(it)) }
        return links
            .sortedWith(compareBy({ it.int(ORDER) }, { it.id }))
            .groupBy({ it.attributes.ref(LESSON)!! }) { link ->
                val version = versions.getValue(link.attributes.ref(RESOURCE_VERSION)!!)
                val resource = resources.getValue(version.attributes.ref(RESOURCE)!!)
                ResourceView(
                    id = version.id,
                    title = resource.text(TITLE),
                    type = resource.text(TYPE),
                    version = version.int("numero"),
                    file =
                        (version.attributes[FILE] as? Map<*, *>)?.let { f ->
                            FileView(
                                name = f["name"]?.toString().orEmpty(),
                                contentType = f["contentType"]?.toString().orEmpty(),
                                size = (f["size"] as? Number)?.toLong() ?: 0,
                                url = "/api/objects/$RESOURCE_VERSION/records/${version.id}/files/$FILE",
                            )
                        },
                )
            }
    }

    private fun summary(
        lessons: List<RecordResponse>,
        progress: Map<String, RecordResponse>,
    ): ProgressSummary {
        val countable = lessons.filter { countable(it) }
        val completed = countable.count { done(progress[it.id]) }
        return ProgressSummary(completed, countable.size, if (countable.isEmpty()) 0 else completed * 100 / countable.size)
    }

    private fun countable(lesson: RecordResponse) = lesson.text(TYPE) !in NOT_YET

    private fun done(progress: RecordResponse?) = progress?.attributes?.get(STATE)?.toString() in LessonProgressService.DONE

    private fun completion(type: String) =
        when (type) {
            in NOT_YET -> "NOT_YET"
            LessonProgressService.VIDEO_LESSON -> "VIDEO_END"
            else -> "EXPLICIT"
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
        const val PROGRESS = "progreso_leccion"
        const val ENROLLMENT = "matricula"
        const val STUDENT = "estudiante_id"
        const val ACTIVE = "ACTIVA"
        const val RESOURCE = "recurso"
        const val RESOURCE_VERSION = "version_recurso"
        const val RESOURCE_LINK = "recurso_leccion"
        const val FILE = "archivo"
        const val ORDER = "orden"
        const val TITLE = "titulo"
        const val TYPE = "tipo"

        // se completan en F3 (evaluación y sesión): no cuentan para el progreso de F2
        val NOT_YET = setOf("EVALUACION", "SESION")
    }
}

private fun RecordResponse.text(name: String): String = attributes[name]?.toString().orEmpty()

private fun RecordResponse.textOrNull(name: String): String? = attributes[name]?.toString()?.takeIf { it.isNotBlank() }

private fun RecordResponse.int(name: String): Int = (attributes[name] as? Number)?.toInt() ?: attributes[name]?.toString()?.toIntOrNull() ?: 0

internal fun Map<String, Any?>.ref(name: String): String? =
    when (val v = this[name]) {
        is Map<*, *> -> v["id"]?.toString()
        null -> null
        else -> v.toString().takeIf { it.isNotBlank() }
    }
