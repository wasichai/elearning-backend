package elearning.assessment

import elearning.jobs.Job
import elearning.jobs.JobHandler
import elearning.jobs.JobProperties
import elearning.jobs.JobQueue
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

// el barrido de intentos vencidos (L4 de #183, ADR-0020): un intento abandonado seguía OPEN hasta que alguien lo tocaba,
// sin llegar a la cola del docente ni al roster. cada lms.assessment.sweep-every se encola, en la cola persistente, un
// barrido por organización con intentos vencidos; la clave hace que no haya dos a la vez. el trabajo cierra por lotes, como
// la plataforma, con lo guardado y enviado en su plazo (AttemptService.closeExpired). repetirlo no cambia nada: lo cerrado
// ya no está abierto
@Component
class ExpiredAttemptSweep(
    private val attempts: AttemptService,
    private val store: AttemptStore,
    private val queue: JobQueue,
    private val properties: JobProperties,
    private val clock: Clock,
) : JobHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type: String = TYPE

    // solo en las réplicas que ejecutan trabajos, como la purga de la cola
    @Scheduled(fixedDelayString = "\${lms.assessment.sweep-every:1m}", initialDelayString = "\${lms.jobs.initial-delay:10s}")
    suspend fun scheduled() {
        if (!properties.scheduling) return
        try {
            enqueueDue()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("expired attempt sweep could not be enqueued", e)
        }
    }

    // un barrido por organización con algo vencido; si ya hay uno pendiente o en curso, ese
    suspend fun enqueueDue() {
        for (organization in store.organizationsWithExpired(Instant.now(clock))) {
            queue.enqueue(organization, TYPE, key = TYPE)
        }
    }

    // lote a lote hasta que no queden: cada lote es una transacción corta, así un estudiante no espera al barrido entero
    override suspend fun handle(job: Job): String {
        var closed = 0
        while (true) {
            val batch = attempts.closeExpired(job.organizationId, BATCH)
            closed += batch.size
            if (batch.size < BATCH) break
        }
        if (closed > 0) log.info("closed {} expired attempts of organization {}", closed, job.organizationId)
        return """{"closed": $closed}"""
    }

    companion object {
        const val TYPE = "assessment.close-expired"
        const val BATCH = 100
    }
}
