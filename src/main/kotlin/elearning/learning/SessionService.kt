package elearning.learning

import elearning.common.instantOf
import elearning.common.listAll
import elearning.common.ref
import elearning.course.meeting.MeetingProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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
) {
    suspend fun forTeacher(code: String): List<SessionView> {
        val offering = teaching.assignedOffering(code)
        return sessionsOf(offering).map { view(it, zone(offering), null) }
    }

    suspend fun forStudent(enrollmentId: UUID): List<SessionView> {
        val enrollment = records.get(ENROLLMENT, enrollmentId)
        if (enrollment.attributes[STUDENT]?.toString() != currentUser.require().userId.toString()) throw NotFoundException(NOT_FOUND)
        val offering = records.get(OFFERING, UUID.fromString(enrollment.attributes.ref(OFFERING_FIELD)!!))
        return sessionsOf(offering).map { view(it, zone(offering), null) }
    }

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
        } catch (e: java.time.format.DateTimeParseException) {
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
        const val ENROLLMENT = "matricula"
        const val OFFERING = "edicion_curso"
        const val OFFERING_FIELD = "edicion"
        const val LESSON = "leccion"
        const val UNIT = "unidad"
        const val VERSION = "version_curso"
        const val STUDENT = "estudiante_id"
        const val RECOVERY = "RECUPERACION"
        const val NOT_FOUND = "No encontrado"
        const val MAX_MINUTES = 1440
        val TYPES = setOf("TEORIA", "LABORATORIO", "CONSULTA", RECOVERY)
        val MODALITIES = setOf("PRESENCIAL", "VIRTUAL")
    }
}
