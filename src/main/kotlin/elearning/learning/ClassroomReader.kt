package elearning.learning

import elearning.common.instantOf
import elearning.common.listAll
import elearning.common.listAllIn
import elearning.common.listByIds
import elearning.common.ref
import elearning.course.meeting.SafeLink
import org.springframework.stereotype.Component
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

// arma el aula leyendo como el estudiante: sus alcances (ADR-0012/0013) deciden qué ve. el aula es siempre la propia:
// quien ve todas las matrículas (un docente) no abre la de otro por aquí
@Component
class ClassroomReader(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val facts: AssessmentFacts,
    private val certificates: CertificateStore,
    private val clock: Clock,
) {
    // mis matrículas salvo las retiradas (una finalizada sigue legible), la de inicio más próximo primero
    suspend fun mine(): List<EnrollmentView> {
        val me = currentUser.require().userId.toString()
        return all(ENROLLMENT, mapOf(STUDENT to me))
            .filter { it.attributes[STATE]?.toString() != WITHDRAWN }
            .map { view(it) }
            .sortedWith(compareBy({ it.offering.startDate }, { it.id }))
    }

    // el panel (#261): una página de mis matrículas en el orden de mine(), cada una con sus próximas sesiones y sus
    // evaluaciones pendientes, que la interfaz pedía aparte por matrícula. por lotes: temarios, cursos, progreso, intentos,
    // sesiones, asistencia y certificados en una lectura cada uno, así las sentencias no crecen con la página
    suspend fun panel(page: PageRequest): List<ClassroomPanelItem> {
        val me = currentUser.require().userId.toString()
        val mine = all(ENROLLMENT, mapOf(STUDENT to me)).filter { it.attributes[STATE]?.toString() != WITHDRAWN }
        val offerings = records.listByIds(OFFERING, mine.mapNotNull { it.attributes.ref(OFFERING_FIELD) }).associateBy { it.id }
        val enrollments =
            mine
                .sortedWith(compareBy({ startDate(offerings.getValue(it.attributes.ref(OFFERING_FIELD)!!)) }, { it.id }))
                .drop(page.offset.toInt())
                .take(page.size)
        if (enrollments.isEmpty()) return emptyList()
        val ids = enrollments.map { it.id }
        val syllabi = syllabi(enrollments.map { it.attributes.ref(VERSION)!! })
        val courses = records.listByIds(COURSE, syllabi.values.map { it.version.attributes.ref(COURSE)!! }).associateBy { it.id }
        val progress = records.listAllIn(PROGRESS, ENROLLMENT, ids).groupBy { it.attributes.ref(ENROLLMENT).orEmpty() }
        val lessons = syllabi.values.flatMap { s -> s.units.flatMap { it.lessons } }
        val assessments =
            facts.enrollmentsLessonAssessments(currentUser.require().organizationId, ids.map(UUID::fromString), lessons.map { UUID.fromString(it.id) })
        val sessions =
            records.listAllIn(SESSION, OFFERING_FIELD, enrollments.map { it.attributes.ref(OFFERING_FIELD)!! }).groupBy {
                it.attributes.ref(OFFERING_FIELD).orEmpty()
            }
        val attendance = records.listAllIn(ATTENDANCE, ENROLLMENT, ids).groupBy { it.attributes.ref(ENROLLMENT).orEmpty() }
        val issued = certificates.ofEnrollments(ids.map(UUID::fromString))
        val now = Instant.now(clock)
        return enrollments.map { enrollment ->
            val syllabus = syllabi.getValue(enrollment.attributes.ref(VERSION)!!)
            val offering = offerings.getValue(enrollment.attributes.ref(OFFERING_FIELD)!!)
            val own = syllabus.units.flatMap { u -> u.lessons.map { it.id } }.toSet()
            val marks = attendance[enrollment.id].orEmpty()
            val present = marks.filter { it.attributes[STATE]?.toString() in PRESENT }.mapNotNull { it.attributes.ref(SESSION) }.toSet()
            val content =
                Content(
                    syllabus.version,
                    syllabus.units,
                    progress[enrollment.id].orEmpty().associateBy { it.attributes.ref(LESSON).orEmpty() },
                    assessments[enrollment.id].orEmpty().filterKeys { it in own },
                    attended(linked(sessions[offering.id].orEmpty()), present),
                )
            val certificate = issued[UUID.fromString(enrollment.id)]
            val listed = view(enrollment, content, certificate, courses.getValue(syllabus.version.attributes.ref(COURSE)!!), offering)
            if (listed.status != ACTIVE || certificate != null) return@map ClassroomPanelItem(listed, emptyList(), emptyList())
            val zone = ZoneId.of(offering.text("zona_horaria"))
            val taken = marks.associate { it.attributes.ref(SESSION) to it.attributes[STATE]?.toString() }
            ClassroomPanelItem(
                listed,
                SessionService.upcoming(sessions[offering.id].orEmpty(), now).map { SessionService.view(it, zone, taken[it.id]) },
                pending(content),
            )
        }
    }

    // las evaluaciones que aún se pueden hacer, en el orden del curso: no aprobadas y con un intento abierto o por usar
    private fun pending(content: Content): List<PendingAssessment> =
        content.units
            .flatMap { it.lessons }
            .mapNotNull { lesson -> content.assessments[lesson.id]?.let { lesson.id to it } }
            .filter { (_, a) -> a.passedAt == null && (a.openAttemptId != null || a.attemptsUsed < a.maxAttempts) }
            .map { (lesson, a) -> PendingAssessment(lesson, assessment(a)) }

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
        val labs =
            content.units
                .flatMap { it.lessons }
                .filter { it.text(TYPE) == LAB }
                .map { it.id }
                .toSet()
        val environments = environments(labs)
        val certificate = certificates.ofEnrollment(UUID.fromString(enrollment.id))
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
                                lab = if (lesson.text(TYPE) == LAB) lab(lesson, environments[lesson.id].orEmpty()) else null,
                            )
                        },
                )
            }
        return ClassroomView(view(enrollment, content, certificate), units, achievements(units, content, certificate))
    }

    suspend fun view(enrollment: RecordResponse): EnrollmentView =
        view(enrollment, content(enrollment), certificates.ofEnrollment(UUID.fromString(enrollment.id)))

    private suspend fun view(
        enrollment: RecordResponse,
        content: Content,
        certificate: Certificate?,
    ): EnrollmentView {
        val course = records.get(COURSE, UUID.fromString(content.version.attributes.ref(COURSE)!!))
        val offering = records.get(OFFERING, UUID.fromString(enrollment.attributes.ref(OFFERING_FIELD)!!))
        return view(enrollment, content, certificate, course, offering)
    }

    // con el curso y la edición ya leídos: el panel los lee por lotes
    private fun view(
        enrollment: RecordResponse,
        content: Content,
        certificate: Certificate?,
        course: RecordResponse,
        offering: RecordResponse,
    ): EnrollmentView {
        val a = enrollment.attributes
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
                    startDate = startDate(offering),
                    endDate = LocalDate.parse(offering.text("fecha_fin").take(10)),
                    timeZone = offering.text("zona_horaria"),
                ),
            progress = summary(lessons, content),
            nextLesson = next?.let { NextLesson(it.id, it.text(TITLE), unitOrder.getValue(it.id), it.int(ORDER)) },
            certificateCode = certificate?.code,
        )
    }

    // los logros salen de lo ya calculado para el aula, sin otra lectura: la unidad con todas sus lecciones contables
    // completadas (fechada por la última), cada evaluación aprobada (solo cuenta un intento calificado, el que muestra su
    // calificación; fechada por el envío del primero que aprobó) y el certificado. por fecha; un logro sin ella, al final
    private fun achievements(
        units: List<UnitView>,
        content: Content,
        certificate: Certificate?,
    ): List<Achievement> {
        val completed =
            units
                .filter { it.progress.totalLessons > 0 && it.progress.completedLessons == it.progress.totalLessons }
                .map { unit ->
                    val at =
                        unit.lessons
                            .filter { it.completion != "NOT_YET" }
                            .mapNotNull { it.progress.completedAt }
                            .maxOrNull()
                    Achievement(UNIT_COMPLETED, at, unit.id)
                }
        val passed =
            units
                .flatMap { it.lessons }
                .mapNotNull { content.assessments[it.id] }
                .filter { it.passedAt != null }
                .map { Achievement(ASSESSMENT_PASSED, it.passedAt, it.id) }
        val issued = listOfNotNull(certificate?.let { Achievement(CERTIFICATE_ISSUED, it.issuedAt, it.code) })
        return (completed + passed + issued).sortedWith(compareBy(nullsLast<Instant>()) { it.at })
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

    // varias versiones, por id, con las versiones, las unidades y las lecciones en una lectura cada una (#261)
    private suspend fun syllabi(versions: List<String>): Map<String, Syllabus> {
        val found = records.listByIds(VERSION, versions)
        val units = records.listAllIn(UNIT, VERSION, found.map { it.id }).sortedWith(compareBy({ it.int(ORDER) }, { it.id }))
        val lessons =
            records
                .listAllIn(LESSON, UNIT, units.map { it.id })
                .sortedWith(compareBy({ it.int(ORDER) }, { it.id }))
                .groupBy { it.attributes.ref(UNIT).orEmpty() }
        val byVersion = units.groupBy { it.attributes.ref(VERSION).orEmpty() }
        return found.associate { version -> version.id to Syllabus(version, byVersion[version.id].orEmpty().map { Unit(it, lessons[it.id].orEmpty()) }) }
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

    // los entornos de los laboratorios, en su orden y en una lectura. como el estudiante: ContentReadScope solo deja los de
    // sus matrículas. un enlace que ya no pasa SafeLink (la regla se endureció) no se muestra
    private suspend fun environments(lessons: Set<String>): Map<String, List<LabEnvironmentView>> {
        if (lessons.isEmpty()) return emptyMap()
        return records
            .listAllIn(LAB_ENVIRONMENT, LESSON, lessons)
            .filter { SafeLink.isAllowed(it.text(LINK)) }
            .sortedWith(compareBy({ it.int(ORDER) }, { it.id }))
            .groupBy({ it.attributes.ref(LESSON)!! }) { LabEnvironmentView(it.id, it.text(TITLE), it.text(LINK)) }
    }

    private fun lab(
        lesson: RecordResponse,
        environments: List<LabEnvironmentView>,
    ) = LabView(
        objective = lesson.textOrNull("objetivo"),
        requirements = lesson.lines("requisitos"),
        milestones = lesson.lines("hitos"),
        acceptanceCriteria = lesson.lines("criterios_aceptacion"),
        deliverables = lesson.lines("entregables"),
        environments = environments,
    )

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
        const val ACTIVE = "ACTIVA"
        const val RESOURCE = "recurso"
        const val RESOURCE_VERSION = "version_recurso"
        const val RESOURCE_LINK = "recurso_leccion"
        const val LAB_ENVIRONMENT = "entorno_laboratorio"
        const val LAB = "LABORATORIO"
        const val LINK = "enlace"
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

        const val UNIT_COMPLETED = "UNIT_COMPLETED"
        const val ASSESSMENT_PASSED = "ASSESSMENT_PASSED"
        const val CERTIFICATE_ISSUED = "CERTIFICATE_ISSUED"

        // sin su evaluación o su sesión definidas todavía no se pueden completar: no cuentan para el progreso
        val NOT_YET = setOf("EVALUACION", "SESION")
    }
}

private fun startDate(offering: RecordResponse): LocalDate = LocalDate.parse(offering.text("fecha_inicio").take(10))

private fun RecordResponse.text(name: String): String = attributes[name]?.toString().orEmpty()

private fun RecordResponse.textOrNull(name: String): String? = attributes[name]?.toString()?.takeIf { it.isNotBlank() }

private fun RecordResponse.int(name: String): Int = (attributes[name] as? Number)?.toInt() ?: attributes[name]?.toString()?.toIntOrNull() ?: 0

// un campo «uno por línea» como lista, sin las líneas vacías
private fun RecordResponse.lines(name: String): List<String> =
    textOrNull(name)
        ?.lines()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
