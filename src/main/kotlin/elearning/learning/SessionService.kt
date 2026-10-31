package elearning.learning

import elearning.common.instantOf
import elearning.common.listAll
import elearning.common.ref
import elearning.course.meeting.MeetingProvider
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import wasichai.notifications.Audience
import wasichai.notifications.NotificationDraft
import wasichai.notifications.NotificationKind
import wasichai.notifications.Notifications
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

// las sesiones de una edición (ADR-0016 §5). escriben solo los comandos del docente asignado; se leen como la persona, con
// el alcance de EnrollmentReadScope
@Service
class SessionService(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val teaching: TeachingReader,
    private val meeting: MeetingProvider,
    private val transactions: TransactionalOperator,
    private val notifications: Notifications,
    private val db: DatabaseClient,
) {
    suspend fun forTeacher(code: String): List<SessionView> {
        val offering = teaching.assignedOffering(code)
        return sessionsOf(offering).map { view(it, zone(offering), null) }
    }

    suspend fun forStudent(enrollmentId: UUID): List<SessionView> {
        val enrollment = records.get(ENROLLMENT, enrollmentId)
        if (enrollment.attributes[STUDENT]?.toString() != currentUser.require().userId.toString()) throw NotFoundException(NOT_FOUND)
        // como el aula: una retirada ya no ve las sesiones ni su enlace
        if (enrollment.attributes[STATE]?.toString() == WITHDRAWN) throw NotFoundException(NOT_FOUND)
        val offering = records.get(OFFERING, UUID.fromString(enrollment.attributes.ref(OFFERING_FIELD)!!))
        val attended =
            records.listAll(ATTENDANCE, mapOf(ENROLLMENT to enrollment.id)).associate {
                it.attributes.ref(SESSION) to
                    it.attributes[STATE]?.toString()
            }
        return sessionsOf(offering).map { view(it, zone(offering), attended[it.id]) }
    }

    suspend fun attendance(id: UUID): List<AttendanceRow> {
        val (session, offering) = assigned(id)
        val taken = records.listAll(ATTENDANCE, mapOf(SESSION to session.id)).associate { it.attributes.ref(ENROLLMENT) to it.attributes[STATE]?.toString() }
        return studentsOf(offering).map { AttendanceRow(it.id, it.attributes["estudiante_correo"]?.toString(), taken[it.id]) }
    }

    // repetir lo mismo no cambia nada; cambiar lo ya registrado exige motivo. todo o nada
    suspend fun takeAttendance(
        id: UUID,
        request: AttendanceRequest,
    ): List<AttendanceRow> {
        val (session, offering) = assigned(id)
        val entries = request.records ?: invalid("records", "required")
        if (session.attributes[STATE] == CANCELLED) throw ConflictException("Esta sesión está cancelada")
        if (entries.map { it.enrollmentId }.toSet().size != entries.size) invalid("records", "a student appears twice")
        val students = studentsOf(offering).associateBy { it.id }
        val reason = request.reason?.trim()?.takeIf { it.isNotEmpty() }
        val me = currentUser.require().userId.toString()
        transactions.executeAndAwait {
            // lo ya registrado se lee con la sesión bloqueada: dos pases de lista a la vez no se saltan el motivo
            // (revisión de la Fase 3, L6), y una cancelación simultánea cuenta
            lock(session.id)
            if (records.get(SESSION, UUID.fromString(session.id)).attributes[STATE] == CANCELLED) throw ConflictException("Esta sesión está cancelada")
            val taken = records.listAll(ATTENDANCE, mapOf(SESSION to session.id)).associateBy { it.attributes.ref(ENROLLMENT) }
            validate(entries, students, taken, reason)
            write(entries, students, taken, session, reason, me)
        }
        return attendance(id)
    }

    // quién puede figurar en la lista y con qué estado; cambiar lo ya registrado exige motivo
    private suspend fun validate(
        entries: List<AttendanceEntry>,
        students: Map<String, RecordResponse>,
        taken: Map<String?, RecordResponse>,
        reason: String?,
    ) {
        for (entry in entries) {
            val student = students[entry.enrollmentId] ?: invalid("records", "${entry.enrollmentId} is not a student of this offering")
            // quien enseña y cursa la misma edición no se acredita a sí mismo
            if (student.attributes[STUDENT]?.toString() ==
                currentUser.require().userId.toString()
            ) {
                throw ForbiddenException("Nobody takes their own attendance")
            }
            if (entry.status !in ATTENDANCE_STATES) invalid("records", "status must be one of $ATTENDANCE_STATES")
            val current = taken[entry.enrollmentId]?.attributes?.get(STATE)?.toString()
            if (current != null && current != entry.status && reason == null) throw ConflictException("Cambiar una asistencia ya registrada exige un motivo")
        }
    }

    private suspend fun write(
        entries: List<AttendanceEntry>,
        students: Map<String, RecordResponse>,
        taken: Map<String?, RecordResponse>,
        session: RecordResponse,
        reason: String?,
        me: String,
    ) {
        for (entry in entries) {
            val existing = taken[entry.enrollmentId]
            when {
                existing == null -> {
                    val student = students.getValue(entry.enrollmentId!!)
                    records.create(
                        ATTENDANCE,
                        RecordRequest(
                            mapOf(
                                SESSION to session.id,
                                ENROLLMENT to student.id,
                                STUDENT to student.attributes[STUDENT].toString(),
                                STATE to entry.status,
                                "registrado_por" to me,
                            ),
                        ),
                        reason,
                    )
                }
                existing.attributes[STATE]?.toString() != entry.status ->
                    records.patch(
                        ATTENDANCE,
                        UUID.fromString(existing.id),
                        RecordRequest(mapOf(STATE to entry.status, "registrado_por" to me)),
                        reason,
                        null,
                    )
            }
        }
    }

    // una sesión a la vez: pasar lista, reprogramar y cancelar leen su estado ya con este cerrojo, hasta el commit
    private suspend fun lock(session: String) {
        db
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .bind("key", "$SESSION:$session")
            .fetch()
            .first()
            .awaitSingleOrNull()
    }

    private suspend fun studentsOf(offering: RecordResponse) =
        records.listAll(ENROLLMENT, mapOf(OFFERING_FIELD to offering.id)).filter { it.attributes[STATE]?.toString() != WITHDRAWN }

    suspend fun create(
        code: String,
        request: CreateSessionsRequest,
    ): List<SessionView> {
        val offering = teaching.assignedOffering(code)
        val zone = zone(offering)
        val title = request.title?.trim()?.takeIf { it.isNotEmpty() } ?: invalid("title", "required")
        val type = request.type?.takeIf { it in TYPES } ?: invalid("type", "must be one of $TYPES")
        val modality = request.modality?.takeIf { it in MODALITIES } ?: invalid("modality", "must be one of $MODALITIES")
        val duration = request.durationMinutes?.takeIf { it in 1..MAX_MINUTES } ?: invalid("durationMinutes", "must be between 1 and $MAX_MINUTES")
        val link =
            try {
                meeting.joinLink(request.link)
            } catch (_: IllegalArgumentException) {
                invalid("link", "must be a public https link")
            }
        val lesson = request.lessonId?.let { lessonOf(offering, it) }
        val original = request.originalSessionId?.let { originalOf(offering, it) }
        if (type == RECOVERY && original == null) invalid("originalSessionId", "a recovery session names the one it recovers")
        val starts = occurrences(request.recurrence ?: invalid("recurrence", "required"), zone)
        val teacher = currentUser.require().userId.toString()
        val created =
            transactions.executeAndAwait {
                starts.map { start ->
                    records.create(
                        SESSION,
                        RecordRequest(
                            buildMap {
                                put(OFFERING_FIELD, offering.id)
                                put("titulo", title)
                                put("tipo", type)
                                put("modalidad", modality)
                                put("inicio", start.toString())
                                put("fin", start.plus(duration.toLong(), ChronoUnit.MINUTES).toString())
                                put("estado", "PROGRAMADA")
                                put("docente_id", teacher)
                                link?.let { put("enlace", it) }
                                request.location
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { put("ubicacion", it) }
                                lesson?.let { put("leccion_id", it) }
                                original?.let { put("sesion_original_id", it) }
                            },
                        ),
                    )
                }
            }
        return created.map { view(it, zone, null) }
    }

    suspend fun reschedule(
        id: UUID,
        request: RescheduleRequest,
    ): SessionView {
        val (session, offering) = assigned(id)
        val zone = zone(offering)
        val reason = reason(request.reason)
        val start =
            try {
                ZonedDateTime.of(LocalDate.parse(request.date.orEmpty()), LocalTime.parse(request.localTime.orEmpty()), zone).toInstant()
            } catch (e: DateTimeParseException) {
                invalid("date", e.message ?: "invalid")
            }
        if (session.attributes[STATE] == CANCELLED) throw ConflictException("Esta sesión está cancelada")
        val duration = Duration.between(instantOf(session.attributes["inicio"]), instantOf(session.attributes["fin"]))
        val moment = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zone).format(start)
        return change(
            session,
            offering,
            mapOf("inicio" to start.toString(), "fin" to start.plus(duration).toString(), STATE to "REPROGRAMADA"),
            reason,
            "Sesión reprogramada: ${session.attributes["titulo"]}",
            "Nueva fecha: $moment (${zone.id}). Motivo: $reason",
        )
    }

    suspend fun cancel(
        id: UUID,
        request: CancelRequest,
    ): SessionView {
        val (session, offering) = assigned(id)
        val reason = reason(request.reason)
        if (session.attributes[STATE] == CANCELLED) throw ConflictException("Esta sesión ya está cancelada")
        return change(session, offering, mapOf(STATE to CANCELLED), reason, "Sesión cancelada: ${session.attributes["titulo"]}", "Motivo: $reason")
    }

    // el cambio y su aviso en la misma transacción: si uno falla, no hay ninguno
    private suspend fun change(
        session: RecordResponse,
        offering: RecordResponse,
        attributes: Map<String, Any?>,
        reason: String,
        title: String,
        body: String,
    ): SessionView {
        val organization = currentUser.require().organizationId
        val audience =
            records
                .listAll(ENROLLMENT, mapOf(OFFERING_FIELD to offering.id))
                .filter { it.attributes[STATE]?.toString() != WITHDRAWN }
                .mapNotNull { it.attributes[STUDENT]?.toString() }
                .map { Audience.User(UUID.fromString(it)) }
        val updated =
            transactions.executeAndAwait {
                // reprogramar y cancelar a la vez pasaban las dos comprobaciones y una cancelada volvía a REPROGRAMADA
                // (revisión de la Fase 3, L7): el estado se relee con la sesión bloqueada
                lock(session.id)
                if (records.get(SESSION, UUID.fromString(session.id)).attributes[STATE] == CANCELLED) {
                    throw ConflictException("Esta sesión está cancelada")
                }
                val written = records.patch(SESSION, UUID.fromString(session.id), RecordRequest(attributes), reason, null)
                if (audience.isNotEmpty()) {
                    notifications.publish(
                        organization,
                        NOTICE_SOURCE,
                        NotificationDraft(kind = NotificationKind.WARNING, title = title, audience = audience, body = body),
                    )
                }
                written
            }
        return view(updated, zone(offering), null)
    }

    // la sesión y su edición, solo para el docente asignado; lo demás no existe
    private suspend fun assigned(id: UUID): Pair<RecordResponse, RecordResponse> {
        val session =
            try {
                records.get(SESSION, id)
            } catch (_: ForbiddenException) {
                throw NotFoundException(NOT_FOUND)
            }
        val offering = session.attributes.ref(OFFERING_FIELD)
        if (offering == null || offering !in teaching.assigned()) throw NotFoundException(NOT_FOUND)
        return session to records.get(OFFERING, UUID.fromString(offering))
    }

    private fun reason(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() } ?: invalid("reason", "required")

    private fun occurrences(
        recurrence: RecurrenceRequest,
        zone: ZoneId,
    ): List<Instant> =
        try {
            SessionRecurrence.occurrences(
                recurrence.pattern.orEmpty(),
                recurrence.days.orEmpty().map(DayOfWeek::valueOf),
                LocalDate.parse(recurrence.startDate.orEmpty()),
                LocalDate.parse(recurrence.endDate ?: recurrence.startDate.orEmpty()),
                LocalTime.parse(recurrence.localTime.orEmpty()),
                zone,
            )
        } catch (e: IllegalArgumentException) {
            invalid("recurrence", e.message ?: "invalid")
        } catch (e: DateTimeParseException) {
            invalid("recurrence", e.message ?: "invalid")
        }

    // una lección de la versión de esa edición
    private suspend fun lessonOf(
        offering: RecordResponse,
        id: String,
    ): String {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: invalid("lessonId", "must be a lesson of this offering")
        val lesson = runCatching { records.get(LESSON, uuid) }.getOrNull() ?: invalid("lessonId", "must be a lesson of this offering")
        val unit = lesson.attributes.ref(UNIT)?.let { records.get(UNIT, UUID.fromString(it)) }
        if (unit?.attributes?.ref(VERSION) != offering.attributes.ref(VERSION)) invalid("lessonId", "must be a lesson of this offering")
        return id
    }

    private suspend fun originalOf(
        offering: RecordResponse,
        id: String,
    ): String {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: invalid("originalSessionId", "must be a session of this offering")
        val session = runCatching { records.get(SESSION, uuid) }.getOrNull() ?: invalid("originalSessionId", "must be a session of this offering")
        if (session.attributes.ref(OFFERING_FIELD) != offering.id) invalid("originalSessionId", "must be a session of this offering")
        return id
    }

    private suspend fun sessionsOf(offering: RecordResponse): List<RecordResponse> =
        records.listAll(SESSION, mapOf(OFFERING_FIELD to offering.id)).sortedBy { instantOf(it.attributes["inicio"]) }

    private fun zone(offering: RecordResponse) = ZoneId.of(offering.attributes["zona_horaria"].toString())

    private fun view(
        session: RecordResponse,
        zone: ZoneId,
        attendance: String?,
    ): SessionView {
        val a = session.attributes
        return SessionView(
            id = session.id,
            title = a["titulo"].toString(),
            type = a["tipo"].toString(),
            modality = a["modalidad"].toString(),
            start = instantOf(a["inicio"])!!,
            end = instantOf(a["fin"])!!,
            timeZone = zone.id,
            status = a["estado"].toString(),
            link = a["enlace"]?.toString(),
            location = a["ubicacion"]?.toString(),
            lessonId = a["leccion_id"]?.toString(),
            originalSessionId = a["sesion_original_id"]?.toString(),
            attendance = attendance,
        )
    }

    private fun invalid(
        field: String,
        message: String,
    ): Nothing = throw ValidationException("Invalid session", field, message)

    companion object {
        const val SESSION = "sesion"
        const val ATTENDANCE = "asistencia"
        val ATTENDANCE_STATES = setOf("PRESENTE", "TARDE", "AUSENTE", "JUSTIFICADO")
        const val ENROLLMENT = "matricula"
        const val OFFERING = "edicion_curso"
        const val OFFERING_FIELD = "edicion"
        const val LESSON = "leccion"
        const val UNIT = "unidad"
        const val VERSION = "version_curso"
        const val STUDENT = "estudiante_id"
        const val RECOVERY = "RECUPERACION"
        const val STATE = "estado"
        const val CANCELLED = "CANCELADA"
        const val WITHDRAWN = "RETIRADA"
        const val NOTICE_SOURCE = "lms.sesiones"
        const val NOT_FOUND = "No encontrado"
        const val MAX_MINUTES = 1440
        val TYPES = setOf("TEORIA", "LABORATORIO", "CONSULTA", RECOVERY)
        val MODALITIES = setOf("PRESENCIAL", "VIRTUAL")
    }
}
