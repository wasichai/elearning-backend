package elearning.learning

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ValidationException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

// las sesiones en el aula y en el espacio docente (docs/api/lms.md)
@RestController
@RequestMapping("/api/lms")
class SessionController(
    private val sessions: SessionService,
) {
    @GetMapping("/classroom/enrollments/{enrollmentId}/sessions")
    suspend fun forStudent(
        @PathVariable enrollmentId: UUID,
    ): List<SessionView> = sessions.forStudent(enrollmentId)

    // la agenda de todas mis matrículas no retiradas en [from, to), ISO-8601 los dos
    @GetMapping("/classroom/sessions")
    suspend fun agenda(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): List<AgendaSessionView> = sessions.agendaForStudent(instant("from", from), instant("to", to))

    @GetMapping("/teaching/offerings/{code}/sessions")
    suspend fun forTeacher(
        @PathVariable code: String,
    ): List<SessionView> = sessions.forTeacher(code)

    @PostMapping("/teaching/offerings/{code}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @PathVariable code: String,
        @RequestBody body: CreateSessionsRequest,
    ): List<SessionView> = sessions.create(code, body)

    @PostMapping("/teaching/sessions/{id}/reschedule")
    suspend fun reschedule(
        @PathVariable id: UUID,
        @RequestBody body: RescheduleRequest,
    ): SessionView = sessions.reschedule(id, body)

    @GetMapping("/teaching/sessions/{id}/attendance")
    suspend fun attendance(
        @PathVariable id: UUID,
    ): List<AttendanceRow> = sessions.attendance(id)

    @PutMapping("/teaching/sessions/{id}/attendance")
    suspend fun takeAttendance(
        @PathVariable id: UUID,
        @RequestBody body: AttendanceRequest,
    ): List<AttendanceRow> = sessions.takeAttendance(id, body)

    @PostMapping("/teaching/sessions/{id}/cancel")
    suspend fun cancel(
        @PathVariable id: UUID,
        @RequestBody body: CancelRequest,
    ): SessionView = sessions.cancel(id, body)

    private fun instant(
        field: String,
        value: String?,
    ): Instant =
        try {
            OffsetDateTime.parse(value ?: throw ValidationException("Invalid agenda", field, "required")).toInstant()
        } catch (_: DateTimeParseException) {
            throw ValidationException("Invalid agenda", field, "must be an ISO-8601 date and time, like 2026-11-10T00:00:00Z")
        }
}
