package elearning.learning

import elearning.common.listAll
import elearning.common.listAllIn
import elearning.common.ref
import elearning.jobs.Job
import elearning.jobs.JobHandler
import elearning.jobs.JobQueue
import elearning.learning.PublicationNotices.ACTIVE
import elearning.learning.PublicationNotices.COURSE
import elearning.learning.PublicationNotices.ENROLLMENT
import elearning.learning.PublicationNotices.PUBLISHED
import elearning.learning.PublicationNotices.STATE
import elearning.learning.PublicationNotices.STUDENT
import elearning.learning.PublicationNotices.VERSION
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ForbiddenException
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeListener
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordService
import wasichai.notifications.Audience
import wasichai.notifications.NotificationDraft
import wasichai.notifications.NotificationKind
import wasichai.notifications.Notifications
import java.util.UUID

// el aviso de publicación de contenido (RF-AUL-09). lo publicado no cambia (EditorialWorkflowGuard) y solo se matricula en una versión
// publicada: el contenido que llega a alguien ya matriculado es una versión nueva de su curso. al pasar una version_curso a
// PUBLICADA, por la bandeja, la API de registros o la plataforma, se avisa a quien cursa otra versión de ese curso con su
// matrícula ACTIVA; nunca a una retirada ni a otro curso. una version_recurso publicada no avisa: una lección solo enlaza
// recursos ya publicados y en un temario en borrador (EditorialWorkflowGuard), así que nadie matriculado la usa aún.
//
// quien publica no lee las matrículas (sin VER_TODAS sobre datos de estudiantes, ADR-0014) y asPlatform no corre dentro de
// una petición: el listener encola un trabajo, en la transacción de la publicación, y el trabajo avisa como la plataforma
object PublicationNotices {
    const val TYPE = "aviso-publicacion"
    const val SOURCE = "lms.publicaciones"

    private val MAPPER: JsonMapper = JsonMapper.builder().build()

    fun payload(version: UUID): String = MAPPER.writeValueAsString(mapOf(VERSION_ID to version.toString()))

    fun versionOf(job: Job): UUID = UUID.fromString(MAPPER.readTree(job.payload).required(VERSION_ID).asString())

    // con clave: repetir el trabajo actualiza el mismo aviso, no escribe otro
    fun key(version: UUID) = "version/$version"

    const val VERSION = "version_curso"
    const val COURSE = "curso"
    const val ENROLLMENT = "matricula"
    const val STUDENT = "estudiante_id"
    const val STATE = "estado"
    const val PUBLISHED = "PUBLICADA"
    const val ACTIVE = "ACTIVA"
    private const val VERSION_ID = "versionId"
}

@Component
class PublicationNoticeListener(
    private val queue: JobQueue,
    // RecordService recibe la lista de listeners: se pide perezosamente para no formar un ciclo
    private val records: ObjectProvider<RecordService>,
) : RecordChangeListener {
    override suspend fun recordChanged(change: RecordChange) {
        if (change.objectName != VERSION) return
        if (change.after?.get(STATE)?.toString() != PUBLISHED || change.before?.get(STATE)?.toString() == PUBLISHED) return
        val course = change.after.orEmpty().ref(COURSE) ?: return
        // sin otra versión del curso no hay nadie matriculado a quien avisar: la primera publicación no encola nada
        // (si quien publica no las lee, se encola igual y decide el trabajo)
        val siblings =
            try {
                records
                    .getObject()
                    .list(VERSION, RecordQuery(page = PageRequest(0, 2), filters = mapOf(COURSE to course), count = false))
                    .content
                    .map { it.id }
            } catch (_: ForbiddenException) {
                null
            }
        if (siblings != null && siblings.none { it != change.recordId.toString() }) return
        queue.enqueue(change.organizationId, PublicationNotices.TYPE, PublicationNotices.payload(change.recordId), key = change.recordId.toString())
    }
}

// corre en la cola, fuera del hilo de WebFlux, como la plataforma. un aviso por publicación con cada estudiante como
// destinatario propio: nadie ve en su bandeja ni en su correo quién más lo recibió (docs/security)
@Component
class PublicationNoticeJobHandler(
    private val records: RecordService,
    private val notifications: Notifications,
) : JobHandler {
    override val type: String = PublicationNotices.TYPE

    override suspend fun handle(job: Job): String? {
        val versionId = PublicationNotices.versionOf(job)
        records.asPlatform<Unit>(job.organizationId, AUDIT_SOURCE) {
            val version = records.get(VERSION, versionId)
            // retirada antes de que corriera el trabajo: ya no hay nada nuevo que anunciar
            if (version.attributes[STATE]?.toString() != PUBLISHED) return@asPlatform
            val courseId = version.attributes.ref(COURSE) ?: return@asPlatform
            val others = records.listAll(VERSION, mapOf(COURSE to courseId)).map { it.id }.filter { it != version.id }
            val audience =
                records
                    .listAllIn(ENROLLMENT, VERSION, others)
                    .filter { it.attributes[STATE]?.toString() == ACTIVE }
                    .mapNotNull { it.attributes[STUDENT]?.toString() }
                    .distinct()
                    .map { Audience.User(UUID.fromString(it)) }
            if (audience.isEmpty()) return@asPlatform
            val course = records.get(COURSE, UUID.fromString(courseId)).attributes
            notifications.publish(
                job.organizationId,
                PublicationNotices.SOURCE,
                NotificationDraft(
                    kind = NotificationKind.INFO,
                    title = "Nueva versión publicada: ${course["titulo"]} (${course["codigo"]})",
                    audience = audience,
                    body =
                        "Se publicó la versión ${version.attributes["numero"]} del curso. Tu matrícula sigue en la versión que cursas: " +
                            "lo publicado no cambia.",
                    key = PublicationNotices.key(versionId),
                ),
            )
        }
        return null
    }

    companion object {
        const val AUDIT_SOURCE = "job:aviso-publicacion"
    }
}
