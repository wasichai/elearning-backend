package elearning.privacy

import elearning.common.listAll
import elearning.jobs.Job
import elearning.jobs.JobHandler
import elearning.jobs.JobProperties
import elearning.jobs.JobQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import tools.jackson.databind.json.JsonMapper
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// por qué se suprime: la persona retiró su aceptación o venció el plazo que aprobó
enum class ErasureReason { WITHDRAWN, EXPIRED }

// la supresión de los datos personales de una persona (ADR-0030 §2, ADR-0037, RNF-SEG-11). corre en la cola, como la
// plataforma, en una sola transacción: o queda todo suprimido y disociado, o nada y se reintenta. es idempotente: lo
// suprimido ya no está y lo disociado ya no lleva su id, así que repetirla no encuentra nada. qué se suprime y qué se
// disocia, y por qué, está en ADR-0037; en corto:
//
// - se suprime lo que solo sirve a la persona: eventos de aprendizaje, progreso, asistencias, entregas (su archivo lo
//   borra la limpieza de wasichai-files), intentos sin calificar con sus notas parciales, sus trabajos y su aceptación
// - se disocia lo que la organización emitió y conserva: la matrícula (con su resultado), los intentos calificados (sin
//   sus respuestas) y el certificado (sin el nombre del titular: el código sigue verificando el papel). llevan desde ahora
//   un seudónimo aleatorio que no se guarda en ninguna parte. también las ejecuciones de agente que pidió o en las que
//   intervino: quedan como evidencia del proceso editorial, con el seudónimo en su lugar
// - el perfil docente pierde su contenido y deja de publicarse; lo que la persona hizo en nombre de la organización
//   (autoría, revisiones, notas que puso) sigue con su id de cuenta, que es de Wasichai, como su auditoría
//
// la constancia (lms.supresion) cuenta cuántos registros de cada clase, sin la persona ni su seudónimo. cada
// lms.privacy.sweep-every se encolan las aceptaciones vencidas y las retiradas cuya supresión no terminó
@Component
class PersonalDataErasure(
    private val records: RecordService,
    private val terms: TermsStore,
    private val queue: JobQueue,
    private val properties: JobProperties,
    private val transactions: TransactionalOperator,
    private val db: DatabaseClient,
    private val clock: Clock,
) : JobHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type: String = TYPE

    // solo en las réplicas que ejecutan trabajos, como la purga de la cola
    @Scheduled(fixedDelayString = "\${lms.privacy.sweep-every:1h}", initialDelayString = "\${lms.jobs.initial-delay:10s}")
    suspend fun scheduled() {
        if (!properties.scheduling) return
        try {
            enqueueDue()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("personal data erasures could not be enqueued", e)
        }
    }

    // una supresión por aceptación retirada o vencida; si ya hay una pendiente o en curso, esa. devuelve cuántas miró
    suspend fun enqueueDue(): Int {
        val due = terms.due(Instant.now(clock))
        due.forEach { enqueue(it.organization, it.user, it.reason) }
        return due.size
    }

    // la clave es la persona: dos peticiones (retirar y el barrido) no hacen dos supresiones a la vez. no cuenta en el tope
    // de trabajos de nadie: lo pide la persona o el plazo, y repetirlo devuelve el mismo
    suspend fun enqueue(
        organization: UUID,
        user: UUID,
        reason: ErasureReason,
    ): UUID = queue.enqueue(organization, TYPE, MAPPER.writeValueAsString(mapOf(USER to user.toString(), REASON to reason.name)), key = user.toString())

    override suspend fun handle(job: Job): String {
        val payload = MAPPER.readTree(job.payload)
        val user = UUID.fromString(payload.required(USER).asString())
        val reason = ErasureReason.valueOf(payload.required(REASON).asString())
        val org = job.organizationId
        return records.asPlatform(org, AUDIT_SOURCE) {
            transactions.executeAndAwait {
                // volvió a aceptar después de que se encolara: no hay nada que suprimir
                if (terms.isValid(org, user, Instant.now(clock))) return@executeAndAwait MAPPER.writeValueAsString(mapOf("skipped" to true))
                // los disparadores de lo que solo se añade lo dejan pasar solo en esta transacción (V15)
                db
                    .sql("SELECT set_config('lms.supresion', 'on', true)")
                    .fetch()
                    .first()
                    .awaitSingleOrNull()
                val erased = linkedMapOf<String, Long>()
                val dissociated = linkedMapOf<String, Long>()
                val pseudonym = UUID.randomUUID()

                erased["evento_aprendizaje"] = sql("DELETE FROM lms.evento_aprendizaje WHERE organizacion_id = :org AND estudiante_id = :user", org, user)
                for (name in listOf(PROGRESS, ATTENDANCE, SUBMISSION)) erased[name] = deleteRecords(name, user)
                // las notas parciales de un intento sin calificar caen con él; las de uno calificado se quedan con él
                erased["calificacion"] =
                    sql(
                        """
                        DELETE FROM lms.calificacion WHERE intento_id IN (
                            SELECT id FROM lms.intento WHERE organizacion_id = :org AND estudiante_id = :user AND estado <> 'GRADED'
                        )
                        """.trimIndent(),
                        org,
                        user,
                    )
                erased["intento"] = sql("DELETE FROM lms.intento WHERE organizacion_id = :org AND estudiante_id = :user AND estado <> 'GRADED'", org, user)
                dissociated["intento"] =
                    sql(
                        """
                        UPDATE lms.intento SET estudiante_id = :pseudonym, respuestas = '[]'::jsonb
                        WHERE organizacion_id = :org AND estudiante_id = :user AND estado = 'GRADED'
                        """.trimIndent(),
                        org,
                        user,
                        pseudonym,
                    )
                dissociated["certificado"] =
                    sql(
                        """
                        UPDATE lms.certificado SET estudiante_id = :pseudonym, datos = jsonb_set(datos, '{holderName}', 'null')
                        WHERE organizacion_id = :org AND estudiante_id = :user
                        """.trimIndent(),
                        org,
                        user,
                        pseudonym,
                    )
                // la ejecución de un agente se queda, es la evidencia del proceso editorial: sin quien la pidió ni quien
                // intervino (V18), el resto de las intervenciones igual y en su orden
                dissociated["ejecucion_agente"] =
                    sql(
                        """
                        UPDATE lms.ejecucion_agente SET
                            solicitante_id = CASE WHEN solicitante_id = :user THEN :pseudonym ELSE solicitante_id END,
                            intervenciones = (
                                SELECT coalesce(jsonb_agg(
                                    CASE WHEN i ->> 'userId' = CAST(:user AS text)
                                        THEN jsonb_set(i, '{userId}', to_jsonb(CAST(:pseudonym AS text))) ELSE i END
                                    ORDER BY n), '[]'::jsonb)
                                FROM jsonb_array_elements(intervenciones) WITH ORDINALITY AS e (i, n)
                            )
                        WHERE organizacion_id = :org
                            AND (solicitante_id = :user OR intervenciones @> jsonb_build_array(jsonb_build_object('userId', CAST(:user AS text))))
                        """.trimIndent(),
                        org,
                        user,
                        pseudonym,
                    )
                dissociated[ENROLLMENT] = dissociateEnrollments(user, pseudonym)
                dissociated[PROFILE] = dissociateProfile(user)
                erased["trabajo"] = sql("DELETE FROM lms.trabajo WHERE organizacion_id = :org AND solicitante = :user", org, user)
                erased["aceptacion_terminos"] = terms.delete(org, user)
                record(job, reason, erased, dissociated)
                MAPPER.writeValueAsString(mapOf("reason" to reason.name, "erased" to erased, "dissociated" to dissociated))
            }
        }
    }

    private suspend fun deleteRecords(
        objectName: String,
        user: UUID,
    ): Long {
        val found = records.listAll(objectName, mapOf(STUDENT to user.toString()))
        found.forEach { records.delete(objectName, UUID.fromString(it.id), AUDIT_REASON) }
        return found.size.toLong()
    }

    // la matrícula se queda, con su resultado, a nombre del seudónimo y sin correo. una activa queda RETIRADA: nadie
    // puede seguirla
    private suspend fun dissociateEnrollments(
        user: UUID,
        pseudonym: UUID,
    ): Long {
        val found = records.listAll(ENROLLMENT, mapOf(STUDENT to user.toString()))
        found.forEach {
            val changes =
                buildMap<String, Any?> {
                    put(STUDENT, pseudonym.toString())
                    put(STUDENT_EMAIL, null)
                    if (it.attributes[STATE]?.toString() == ACTIVE) put(STATE, WITHDRAWN)
                }
            records.patch(ENROLLMENT, UUID.fromString(it.id), RecordRequest(changes), AUDIT_REASON)
        }
        return found.size.toLong()
    }

    // el perfil sigue enlazado a la cuenta mientras la persona enseñe (sus asignaciones cuelgan de él), sin nada que la
    // describa y fuera del catálogo. sigue con el mismo id de cuenta: uno ya vaciado no se cuenta otra vez
    private suspend fun dissociateProfile(user: UUID): Long {
        val found =
            records.listAll(PROFILE, mapOf(PROFILE_USER to user.toString())).filter { profile ->
                CLEARED_PROFILE.any { (field, value) -> profile.attributes[field] != value }
            }
        found.forEach { records.patch(PROFILE, UUID.fromString(it.id), RecordRequest(CLEARED_PROFILE), AUDIT_REASON) }
        return found.size.toLong()
    }

    // una por trabajo: si el intento anterior escribió y no llegó a cerrarse, no se escribe otra
    private suspend fun record(
        job: Job,
        reason: ErasureReason,
        erased: Map<String, Long>,
        dissociated: Map<String, Long>,
    ) {
        db
            .sql(
                """
                INSERT INTO lms.supresion (organizacion_id, trabajo_id, motivo, suprimidos, disociados, ejecutada_en)
                VALUES (:org, :job, :reason, CAST(:erased AS jsonb), CAST(:dissociated AS jsonb), :at)
                ON CONFLICT (trabajo_id) DO NOTHING
                """.trimIndent(),
            ).bind("org", job.organizationId)
            .bind("job", job.id)
            .bind("reason", reason.name)
            .bind("erased", MAPPER.writeValueAsString(erased))
            .bind("dissociated", MAPPER.writeValueAsString(dissociated))
            .bind("at", Instant.now(clock).atOffset(ZoneOffset.UTC))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun sql(
        statement: String,
        org: UUID,
        user: UUID,
        pseudonym: UUID? = null,
    ): Long {
        val spec = db.sql(statement).bind("org", org).bind("user", user)
        return (if (pseudonym == null) spec else spec.bind("pseudonym", pseudonym)).fetch().rowsUpdated().awaitSingle()
    }

    companion object {
        const val TYPE = "privacy.erase"
        const val USER = "userId"
        const val REASON = "reason"
        private const val AUDIT_SOURCE = "job:privacy-erase"
        private const val AUDIT_REASON = "Supresión de datos personales (RNF-SEG-11)"

        const val ENROLLMENT = "matricula"
        const val PROGRESS = "progreso_leccion"
        const val ATTENDANCE = "asistencia"
        const val SUBMISSION = "entrega"
        const val PROFILE = "perfil_docente"
        private const val STUDENT = "estudiante_id"
        private const val STUDENT_EMAIL = "estudiante_correo"
        private const val STATE = "estado"
        private const val ACTIVE = "ACTIVA"
        private const val WITHDRAWN = "RETIRADA"
        private const val PROFILE_USER = "usuario_id"
        private const val PUBLIC_NAME = "nombre_publico"
        const val ANONYMOUS_TEACHER = "Perfil suprimido"
        private val CLEARED_PROFILE: Map<String, Any?> =
            mapOf(
                PUBLIC_NAME to ANONYMOUS_TEACHER,
                "titulo_profesional" to null,
                "biografia" to null,
                "especialidades" to null,
                "perfil_publico" to false,
            )

        private val MAPPER: JsonMapper = JsonMapper.builder().findAndAddModules().build()
    }
}
