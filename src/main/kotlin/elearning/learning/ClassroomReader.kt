package elearning.learning

import elearning.common.instantOf
import elearning.common.listAll
import elearning.common.listAllIn
import elearning.common.listByIds
import elearning.common.ref
import org.springframework.stereotype.Component
import wasichai.core.common.NotFoundException
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
    private val facts: AssessmentFacts,
    private val certificates: CertificateStore,
) {
    // mis matrículas salvo las retiradas (una finalizada sigue legible), la de inicio más próximo primero
    suspend fun mine(): List<EnrollmentView> {
        val me = currentUser.require().userId.toString()
        return all(ENROLLMENT, mapOf(STUDENT to me))
            .filter { it.attributes[STATE]?.toString() != WITHDRAWN }
            .map { view(it) }
            .sortedWith(compareBy({ it.offering.startDate }, { it.id }))
    }

    suspend fun classroom(id: UUID): ClassroomView {
        val enrollment = records.get(ENROLLMENT, id)
        if (enrollment.attributes[STUDENT]?.toString() != currentUser.require().userId.toString()) throw NotFoundException("Matrícula no encontrada")
        // una retirada no está en «Mis cursos» ni abre su aula: antes la devolvía con recursos vacíos (revisión de la Fase 2, L7).
        // una finalizada sigue legible (ADR-0016)
        if (enrollment.attributes[STATE]?.toString() == WITHDRAWN) throw NotFoundException("Matrícula no encontrada")
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
                    progress = summary(unit.lessons, content),
                    lessons =
                        unit.lessons.map { lesson ->
                            LessonView(
                                id = lesson.id,
                                order = lesson.int(ORDER),
                                title = lesson.text(TITLE),
                                type = lesson.text(TYPE),
                                durationMinutes = lesson.textOrNull("duracion_minutos")?.toIntOrNull(),
                                summary = lesson.textOrNull("resumen"),
                                completion = completion(lesson, content),
                                progress = progress(lesson, content),
                                resources = resources[lesson.id].orEmpty(),
                                assessment = content.assessments[lesson.id]?.let(::assessment),
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
        val next = lessons.firstOrNull { countable(it, content) && !done(it, content) }
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
            progress = summary(lessons, content),
            nextLesson = next?.let { NextLesson(it.id, it.text(TITLE), unitOrder.getValue(it.id), it.int(ORDER)) },
            certificateCode = certificates.codeOf(UUID.fromString(enrollment.id)),
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
        val assessments: Map<String, LessonAssessment>,
        // por lección, la sesión a la que se asistió, o null si tiene sesiones y a ninguna; solo las lecciones con sesión
        val attendedSession: Map<String, RecordResponse?>,
    )

    // el progreso de varias matrículas de una edición, leído por lotes (M4 de #183): antes el roster armaba el aula de cada
    // estudiante. el temario se lee una vez por versión, el progreso guardado por lección, los intentos de toda la edición en
    // una lectura y la asistencia la trae quien llama. sessions: las no canceladas de la edición; attendance: la registrada.
    // byEnrollment: el progreso guardado de esas matrículas y no el de toda la edición (una página del roster)
    suspend fun progress(
        offering: String,
        enrollments: List<RecordResponse>,
        sessions: List<RecordResponse>,
        attendance: List<RecordResponse>,
        byEnrollment: Boolean = false,
    ): Map<String, ProgressSummary> {
        val organization = currentUser.require().organizationId
        val linked = linked(sessions)
        val present =
            attendance
                .filter { it.attributes[STATE]?.toString() in PRESENT }
                .groupBy({ it.attributes.ref(ENROLLMENT).orEmpty() }) { it.attributes.ref(SESSION).orEmpty() }
        val result = mutableMapOf<String, ProgressSummary>()
        for ((version, group) in enrollments.groupBy { it.attributes.ref(VERSION)!! }) {
            val syllabus = syllabus(version)
            val lessons = syllabus.units.flatMap { it.lessons }
            val stored =
                if (byEnrollment) records.listAllIn(PROGRESS, ENROLLMENT, group.map { it.id }) else lessons.flatMap { all(PROGRESS, mapOf(LESSON to it.id)) }
            val progress = stored.groupBy { it.attributes.ref(ENROLLMENT).orEmpty() }
            val assessments = facts.offeringLessonAssessments(organization, UUID.fromString(offering), lessons.map { UUID.fromString(it.id) })
            for (enrollment in group) {
                val content =
                    Content(
                        syllabus.version,
                        syllabus.units,
                        progress[enrollment.id].orEmpty().associateBy { it.attributes.ref(LESSON).orEmpty() },
                        assessments[enrollment.id].orEmpty(),
                        attended(linked, present[enrollment.id].orEmpty().toSet()),
                    )
                result[enrollment.id] = summary(lessons, content)
            }
        }
        return result
    }

    private class Syllabus(
        val version: RecordResponse,
        val units: List<Unit>,
    )

    // la versión con sus unidades y lecciones en orden
    private suspend fun syllabus(version: String): Syllabus {
        val record = records.get(VERSION, UUID.fromString(version))
        val units =
            all(UNIT, mapOf(VERSION to record.id))
                .sortedWith(compareBy({ it.int(ORDER) }, { it.id }))
                .map { unit -> Unit(unit, all(LESSON, mapOf(UNIT to unit.id)).sortedWith(compareBy({ it.int(ORDER) }, { it.id }))) }
        return Syllabus(record, units)
    }

    // la versión de la matrícula con sus unidades y lecciones en orden, y el progreso guardado por lección
    private suspend fun content(enrollment: RecordResponse): Content {
        val syllabus = syllabus(enrollment.attributes.ref(VERSION)!!)
        val progress = all(PROGRESS, mapOf(ENROLLMENT to enrollment.id)).associateBy { it.attributes.ref(LESSON).orEmpty() }
        val lessons = syllabus.units.flatMap { it.lessons }.map { UUID.fromString(it.id) }
        val assessments = facts.lessonAssessments(currentUser.require().organizationId, UUID.fromString(enrollment.id), lessons)
        return Content(syllabus.version, syllabus.units, progress, assessments, attendedSessions(enrollment))
    }

    // las lecciones enlazadas a sesiones de la edición (ADR-0016 §4), sin contar las canceladas
    private suspend fun attendedSessions(enrollment: RecordResponse): Map<String, RecordResponse?> {
        val offering = enrollment.attributes.ref(OFFERING_FIELD)!!
        val linked = linked(all(SESSION, mapOf(OFFERING_FIELD to offering)))
        if (linked.isEmpty()) return emptyMap()
        val present =
            all(ATTENDANCE, mapOf(ENROLLMENT to enrollment.id))
                .filter {
                    it.attributes[STATE]?.toString() in PRESENT
                }.mapNotNull { it.attributes.ref(SESSION) }
                .toSet()
        return attended(linked, present)
    }

    // por lección, sus sesiones no canceladas
    private fun linked(sessions: List<RecordResponse>): Map<String, List<RecordResponse>> =
        sessions
            .filter { it.attributes["leccion_id"] != null && it.attributes[STATE]?.toString() != CANCELLED }
            .groupBy { it.attributes["leccion_id"].toString() }

    // por lección con sesiones, la sesión a la que se asistió o null
    private fun attended(
        linked: Map<String, List<RecordResponse>>,
        present: Set<String>,
    ): Map<String, RecordResponse?> = linked.mapValues { (_, sessions) -> sessions.firstOrNull { it.id in present } }

    // los recursos de esas lecciones, en su orden. por lotes (M1 de #104): los enlaces de esas lecciones, sus versiones y
    // sus recursos en una lectura cada uno, no un get por versión y por recurso. lo que el alcance no deja leer no se muestra
    private suspend fun resources(lessons: Set<String>): Map<String, List<ResourceView>> {
        val links = records.listAllIn(RESOURCE_LINK, LESSON, lessons)
        val versions = records.listByIds(RESOURCE_VERSION, links.mapNotNull { it.attributes.ref(RESOURCE_VERSION) }).associateBy { it.id }
        val resources = records.listByIds(RESOURCE, versions.values.mapNotNull { it.attributes.ref(RESOURCE) }).associateBy { it.id }
        return links
            .sortedWith(compareBy({ it.int(ORDER) }, { it.id }))
            .mapNotNull { link ->
                val version = versions[link.attributes.ref(RESOURCE_VERSION)] ?: return@mapNotNull null
                val resource = resources[version.attributes.ref(RESOURCE)] ?: return@mapNotNull null
                Triple(link, version, resource)
            }.groupBy({ it.first.attributes.ref(LESSON)!! }) { (_, version, resource) ->
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
        content: Content,
    ): ProgressSummary {
        val countable = lessons.filter { countable(it, content) }
        val completed = countable.count { done(it, content) }
        return ProgressSummary(completed, countable.size, if (countable.isEmpty()) 0 else completed * 100 / countable.size)
    }

    private fun countable(
        lesson: RecordResponse,
        content: Content,
    ) = completion(lesson, content) != "NOT_YET"

    // con evaluación, la completa aprobarla (ADR-0016 §4); sin ella, lo guardado
    private fun done(
        lesson: RecordResponse,
        content: Content,
    ): Boolean {
        content.assessments[lesson.id]?.let { return it.passedAt != null }
        if (lesson.id in content.attendedSession) return content.attendedSession[lesson.id] != null
        return content.progress[lesson.id]
            ?.attributes
            ?.get(STATE)
            ?.toString() in LessonProgressService.DONE
    }

    private fun completion(
        lesson: RecordResponse,
        content: Content,
    ) = when {
        lesson.id in content.assessments -> "ASSESSMENT"
        lesson.id in content.attendedSession -> "ATTENDANCE"
        lesson.text(TYPE) in NOT_YET -> "NOT_YET"
        lesson.text(TYPE) == LessonProgressService.VIDEO_LESSON -> "VIDEO_END"
        else -> "EXPLICIT"
    }

    // el aprobado se calcula de los intentos, no se guarda
    private fun progress(
        lesson: RecordResponse,
        content: Content,
    ): LessonProgress {
        val stored = LessonProgressService.progress(content.progress[lesson.id])
        content.attendedSession[lesson.id]?.let {
            return LessonProgress(
                LessonProgressService.COMPLETED,
                stored.startedAt ?: instantOf(it.attributes["inicio"]),
                instantOf(it.attributes["fin"]),
                ATTENDANCE_EVIDENCE,
            )
        }
        val assessment = content.assessments[lesson.id] ?: return stored
        return when {
            assessment.passedAt != null ->
                LessonProgress(LessonProgressService.PASSED, stored.startedAt ?: assessment.firstStartedAt, assessment.passedAt, PASSED_EVIDENCE)
            stored.status == LessonProgressService.NOT_STARTED && assessment.firstStartedAt != null ->
                LessonProgress(LessonProgressService.STARTED, assessment.firstStartedAt, null, null)
            else -> stored
        }
    }

    private fun assessment(a: LessonAssessment) =
        AssessmentSummary(
            id = a.id,
            title = a.title,
            maxAttempts = a.maxAttempts,
            timeLimitMinutes = a.timeLimitMinutes,
            passingPercent = a.passingPercent,
            attemptsUsed = a.attemptsUsed,
            openAttemptId = a.openAttemptId,
            best = if (a.bestPercent == null && !a.pending) null else AssessmentBest(a.bestPercent, a.passed, a.pending),
        )

    private suspend fun all(
        objectName: String,
        filters: Map<String, String>,
    ): List<RecordResponse> = records.listAll(objectName, filters)

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
        const val WITHDRAWN = "RETIRADA"
        const val RESOURCE = "recurso"
        const val RESOURCE_VERSION = "version_recurso"
        const val RESOURCE_LINK = "recurso_leccion"
        const val FILE = "archivo"
        const val ORDER = "orden"
        const val TITLE = "titulo"
        const val TYPE = "tipo"

        const val PASSED_EVIDENCE = "EVALUACION_APROBADA"
        const val ATTENDANCE_EVIDENCE = "ASISTENCIA"
        const val SESSION = "sesion"
        const val ATTENDANCE = "asistencia"
        const val CANCELLED = "CANCELADA"
        val PRESENT = setOf("PRESENTE", "TARDE")

        // sin su evaluación o su sesión definidas todavía no se pueden completar: no cuentan para el progreso
        val NOT_YET = setOf("EVALUACION", "SESION")
    }
}

private fun RecordResponse.text(name: String): String = attributes[name]?.toString().orEmpty()

private fun RecordResponse.textOrNull(name: String): String? = attributes[name]?.toString()?.takeIf { it.isNotBlank() }

private fun RecordResponse.int(name: String): Int = (attributes[name] as? Number)?.toInt() ?: attributes[name]?.toString()?.toIntOrNull() ?: 0
