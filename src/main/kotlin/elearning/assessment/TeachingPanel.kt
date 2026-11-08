package elearning.assessment

import com.fasterxml.jackson.annotation.JsonUnwrapped
import elearning.common.DisplayNames
import elearning.common.listAllIn
import elearning.common.listByIds
import elearning.common.ref
import elearning.learning.SessionService
import elearning.learning.SessionView
import elearning.learning.TeachingOffering
import elearning.learning.TeachingReader
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

// un elemento del panel docente (#261): la edición como en GET /offerings, con sus próximas sesiones y el principio de su
// cola de corrección (los más antiguos; la cola entera, en GET …/attempts)
data class TeachingPanelItem(
    @get:JsonUnwrapped val offering: TeachingOffering,
    val upcomingSessions: List<SessionView>,
    val oldestPending: List<PendingAttempt>,
)

// el panel docente: lo que la interfaz pedía aparte por edición (su cola y sus sesiones), en una lectura por lotes. vive aquí
// y no en learning porque lee la cola de corrección; lo decide la asignación, como el resto del espacio docente
@Service
class TeachingPanel(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val teaching: TeachingReader,
    private val attempts: AttemptStore,
    private val names: DisplayNames,
    private val clock: Clock,
) {
    // una página de las ediciones asignadas en el orden de GET /offerings. las sesiones, la cola, sus correos, nombres y lecciones
    // se leen en una lectura cada uno para toda la página: las sentencias no crecen con ella
    suspend fun page(page: PageRequest): List<TeachingPanelItem> {
        val user = currentUser.require()
        val found = teaching.assignedOfferings().drop(page.offset.toInt()).take(page.size)
        if (found.isEmpty()) return emptyList()
        val offerings = teaching.offerings(found)
        val sessions = records.listAllIn(SESSION, SESSION_OFFERING, found.map { it.id }).groupBy { it.attributes.ref(SESSION_OFFERING).orEmpty() }
        val queues = attempts.oldestPending(user.organizationId, found.map { UUID.fromString(it.id) }, user.userId, QUEUE_PREVIEW)
        val lines = queues.values.flatten()
        val emails = records.listByIds(ENROLLMENT, lines.map { it.enrollmentId.toString() }).associate { it.id to it.attributes[EMAIL]?.toString() }
        val named = names.of(user.organizationId, lines.map { it.studentId.toString() })
        val titles = records.listByIds(LESSON, lines.map { it.lessonId.toString() }).associate { it.id to it.attributes[TITLE].toString() }
        val now = Instant.now(clock)
        return found.zip(offerings) { record, offering ->
            val zone = ZoneId.of(offering.timeZone)
            TeachingPanelItem(
                offering,
                SessionService.upcoming(sessions[record.id].orEmpty(), now).map { SessionService.view(it, zone, null) },
                queues[UUID.fromString(record.id)].orEmpty().map { a ->
                    PendingAttempt(
                        attemptId = a.id.toString(),
                        enrollmentId = a.enrollmentId.toString(),
                        email = emails[a.enrollmentId.toString()],
                        name = named[a.studentId.toString()],
                        lessonId = a.lessonId.toString(),
                        lessonTitle = titles[a.lessonId.toString()].orEmpty(),
                        submittedAt = a.submittedAt,
                        pendingQuestions = a.pendingQuestions,
                    )
                },
            )
        }
    }

    companion object {
        // lo que cabe en la bandeja de cada edición; el total está en pendingAttempts
        const val QUEUE_PREVIEW = 5
        const val SESSION = "sesion"
        const val SESSION_OFFERING = "edicion"
        const val ENROLLMENT = "matricula"
        const val EMAIL = "estudiante_correo"
        const val LESSON = "leccion"
        const val TITLE = "titulo"
    }
}

// el panel docente (docs/api/lms.md): page y size como en la API de registros; sin ellos, la primera página
@RestController
@RequestMapping("/api/lms/teaching")
class TeachingPanelController(
    private val panel: TeachingPanel,
) {
    @GetMapping("/panel")
    suspend fun panel(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): List<TeachingPanelItem> = panel.page(PageRequest.of(page, size))
}
