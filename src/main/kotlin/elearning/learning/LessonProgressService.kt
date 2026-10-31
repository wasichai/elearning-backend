package elearning.learning

import elearning.common.listAll
import elearning.common.ref
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

enum class LearningEventType { STARTED, MARKED_COMPLETE, VIDEO_ENDED }

// las reglas del progreso (ADR-0012). solo el estudiante, sobre su matrícula activa y una lección de su versión; el
// estado solo avanza y cada tipo de lección se completa con su evidencia: lectura, presentación y laboratorio cuando el
// estudiante la marca, vídeo al terminar, evaluación y sesión en F3. el tiempo con la página abierta no es evidencia.
// el evento crudo se guarda una vez por clientEventId en la misma transacción que el progreso, serializada por lección
@Service
class LessonProgressService(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val events: LearningEvents,
    private val facts: AssessmentFacts,
    private val transactions: TransactionalOperator,
    private val db: DatabaseClient,
    private val clock: Clock,
) {
    suspend fun record(
        enrollmentId: UUID,
        lessonId: UUID,
        type: LearningEventType,
        clientEventId: UUID,
    ): LessonProgress {
        val user = currentUser.require()
        // la matrícula se lee con el alcance de la persona; quien ve todas (un docente) tampoco avanza la de otro
        val enrollment = records.get(ENROLLMENT, enrollmentId)
        if (enrollment.attributes[STUDENT]?.toString() != user.userId.toString()) throw NotFoundException(NOT_FOUND)
        if (enrollment.attributes[STATE]?.toString() != ACTIVE) throw ConflictException("La matrícula no está activa")
        val lesson = records.get(LESSON, lessonId)
        val unit = lesson.attributes.ref(UNIT)?.let { records.get(UNIT, UUID.fromString(it)) }
        if (unit == null || unit.attributes.ref(VERSION) != enrollment.attributes.ref(VERSION)) throw NotFoundException(NOT_FOUND)
        // con evaluación solo la completa aprobarla, sea del tipo que sea (ADR-0016 §4)
        if (type != LearningEventType.STARTED &&
            facts.hasEvaluation(user.organizationId, lessonId)
        ) {
            throw ConflictException("Esta lección se completa aprobando su evaluación")
        }
        // con sesión enlazada la completa asistir: ni marcarla ni un vídeo
        if (type != LearningEventType.STARTED &&
            records.listAll("sesion", mapOf("leccion_id" to lessonId.toString(), "edicion" to enrollment.attributes.ref("edicion").orEmpty())).any {
                it.attributes[STATE]?.toString() !=
                    "CANCELADA"
            }
        ) {
            throw ConflictException("Esta lección se completa asistiendo a su sesión")
        }
        val evidence = evidence(type, lesson.attributes[TYPE]?.toString())

        return transactions.executeAndAwait {
            db
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .bind("key", "$PROGRESS:$enrollmentId:$lessonId")
                .fetch()
                .first()
                .awaitSingleOrNull()
            val current = find(enrollmentId, lessonId)
            val event = LearningEvent(user.organizationId, enrollmentId, lessonId, user.userId, type.name, clientEventId)
            // repetido: lo que ya hay, sin tocar nada
            if (!events.record(event)) return@executeAndAwait progress(current)
            val now = Instant.now(clock).toString()
            val written =
                when {
                    current == null ->
                        records.create(
                            PROGRESS,
                            RecordRequest(
                                buildMap {
                                    put(ENROLLMENT, enrollmentId.toString())
                                    put(LESSON, lessonId.toString())
                                    put(STUDENT, user.userId.toString())
                                    put(STATE, if (evidence == null) STARTED else COMPLETED)
                                    put(STARTED_AT, now)
                                    if (evidence != null) {
                                        put(COMPLETED_AT, now)
                                        put(EVIDENCE, evidence)
                                    }
                                },
                            ),
                        )
                    evidence != null && current.attributes[STATE]?.toString() == STARTED ->
                        records.patch(
                            PROGRESS,
                            UUID.fromString(current.id),
                            RecordRequest(mapOf(STATE to COMPLETED, COMPLETED_AT to now, EVIDENCE to evidence)),
                            null,
                            null,
                        )
                    else -> current
                }
            progress(written)
        }
    }

    // qué evidencia aporta el evento a este tipo de lección; null si solo la inicia. lo que no aplica, 409
    private fun evidence(
        type: LearningEventType,
        lessonType: String?,
    ): String? =
        when (type) {
            LearningEventType.STARTED -> null
            LearningEventType.MARKED_COMPLETE ->
                if (lessonType in EXPLICIT) EXPLICIT_EVIDENCE else throw ConflictException("Esta lección no se completa marcándola")
            LearningEventType.VIDEO_ENDED ->
                if (lessonType == VIDEO_LESSON) VIDEO_EVIDENCE else throw ConflictException("Esta lección no es un vídeo")
        }

    private suspend fun find(
        enrollment: UUID,
        lesson: UUID,
    ): RecordResponse? =
        records
            .list(
                PROGRESS,
                RecordQuery(page = PageRequest(0, 1), filters = mapOf(ENROLLMENT to enrollment.toString(), LESSON to lesson.toString()), count = false),
            ).content
            .firstOrNull()

    companion object {
        const val ENROLLMENT = "matricula"
        const val PROGRESS = "progreso_leccion"
        const val LESSON = "leccion"
        const val UNIT = "unidad"
        const val VERSION = "version_curso"
        const val STUDENT = "estudiante_id"
        const val STATE = "estado"
        const val TYPE = "tipo"
        const val ACTIVE = "ACTIVA"
        const val NOT_STARTED = "NO_INICIADA"
        const val STARTED = "INICIADA"
        const val COMPLETED = "COMPLETADA"
        const val PASSED = "APROBADA"
        const val STARTED_AT = "iniciado_en"
        const val COMPLETED_AT = "completado_en"
        const val EVIDENCE = "evidencia"
        const val EXPLICIT_EVIDENCE = "FINALIZACION_EXPLICITA"
        const val VIDEO_EVIDENCE = "FIN_DE_VIDEO"
        const val VIDEO_LESSON = "VIDEO"
        const val NOT_FOUND = "No encontrada"
        val EXPLICIT = setOf("LECTURA", "PRESENTACION", "LABORATORIO")
        val DONE = setOf(COMPLETED, PASSED)

        fun progress(record: RecordResponse?): LessonProgress =
            if (record == null) {
                LessonProgress(NOT_STARTED, null, null, null)
            } else {
                val a = record.attributes
                LessonProgress(a[STATE].toString(), instant(a[STARTED_AT]), instant(a[COMPLETED_AT]), a[EVIDENCE]?.toString())
            }

        private fun instant(value: Any?): Instant? =
            value?.toString()?.let { runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
    }
}
