package elearning.jobs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// ejecuta la cola (ADR-0018 §4, RNF-ARQ-09). cada vuelta reclama a lo sumo parallelism trabajos de los tipos que esta
// réplica sabe ejecutar y los corre en un dispatcher acotado, nunca en el hilo de WebFlux. no hace falta ClusterLock:
// SKIP LOCKED reparte los trabajos entre réplicas
@Component
class JobRunner(
    private val queue: JobQueue,
    private val properties: JobProperties,
    registered: List<JobHandler>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val handlers: Map<String, JobHandler>
    private val dispatcher = Dispatchers.IO.limitedParallelism(properties.parallelism)

    init {
        val repeated = registered.groupBy { it.type }.filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "more than one JobHandler for the job types $repeated" }
        handlers = registered.associateBy { it.type }
    }

    @Scheduled(fixedDelayString = "\${lms.jobs.poll:5s}", initialDelayString = "\${lms.jobs.initial-delay:10s}")
    suspend fun scheduled() {
        if (!properties.scheduling || handlers.isEmpty()) return
        try {
            runOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("job queue poll failed", e)
        }
    }

    // una vuelta: reclama, ejecuta y espera a que terminen. devuelve cuántos reclamó. para pruebas y operación manual
    suspend fun runOnce(): Int {
        val claimed = queue.claim(handlers.keys, properties.parallelism)
        supervisorScope {
            claimed.forEach { job ->
                launch {
                    try {
                        run(job)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // la base no respondió al cerrar el trabajo: sigue en curso y se retoma cuando caduque su reclamo
                        log.warn("job {} ({}) could not be closed", job.id, job.type, e)
                    }
                }
            }
        }
        return claimed.size
    }

    private suspend fun run(job: Job) {
        var permanent = false
        val error =
            if (job.attempt > job.maxAttempts) {
                // retomado tras perder su réplica en el último intento: no se ejecuta otra vez
                "the replica running the last attempt was lost"
            } else {
                try {
                    val handler = handlers.getValue(job.type)
                    val result = withTimeout(properties.timeout.toMillis()) { withContext(dispatcher) { handler.handle(job) } }
                    if (!queue.complete(job, result)) log.warn("job {} ({}) finished after losing its claim: result discarded", job.id, job.type)
                    return
                } catch (e: TimeoutCancellationException) {
                    "timed out after ${properties.timeout}"
                } catch (e: CancellationException) {
                    // la réplica se apaga: el reclamo caduca y otra lo retoma
                    throw e
                } catch (e: PermanentJobFailure) {
                    // reintentar no lo arregla (ADR-0022): termina en este intento
                    log.info("job {} ({}) failed for good: {}", job.id, job.type, e.message)
                    permanent = true
                    e.message ?: e.javaClass.simpleName
                } catch (e: Exception) {
                    log.warn("job {} ({}) attempt {}/{} failed", job.id, job.type, job.attempt, job.maxAttempts, e)
                    e.message ?: e.javaClass.simpleName
                }
            }
        if (!queue.fail(job, error, permanent)) log.warn("job {} ({}) failed after losing its claim: {}", job.id, job.type, error)
    }
}
