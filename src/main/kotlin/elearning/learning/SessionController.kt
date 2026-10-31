package elearning.learning

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
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

    @PostMapping("/teaching/sessions/{id}/cancel")
    suspend fun cancel(
        @PathVariable id: UUID,
        @RequestBody body: CancelRequest,
    ): SessionView = sessions.cancel(id, body)
}
