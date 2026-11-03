package elearning.jobs

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID

// un trabajo reclamado: lo que recibe su manejador. payload es el JSON con el que se encoló; attempt empieza en 1
data class Job(
    val id: UUID,
    val organizationId: UUID,
    val type: String,
    val payload: String,
    val attempt: Int,
    val maxAttempts: Int,
)

// CANCELLED (ADR-0033 §5): alguien lo canceló pendiente o en curso; como DONE y FAILED, está terminado
enum class JobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

// lo que se puede contar de un trabajo: su estado, su último error y su resultado (JSON). payload es el JSON con el que
// se encoló: quien lo expone decide con él a quién se lo cuenta (el importador, solo a quien lo pidió)
data class JobStatus(
    val id: UUID,
    val type: String,
    val payload: String,
    val state: JobState,
    val attempts: Int,
    val maxAttempts: Int,
    val error: String?,
    val result: String?,
    val createdAt: Instant,
    val finishedAt: Instant?,
)

// el puerto de la cola: un bean por tipo de trabajo. handle corre fuera del hilo de WebFlux, en un dispatcher acotado, y
// puede bloquear; lo que devuelve (JSON o null) queda como resultado. una excepción cuenta como intento fallido y se
// reintenta hasta maxAttempts, salvo PermanentJobFailure, que termina el trabajo. un manejador que bloquea (un proceso
// externo) pone su propio tiempo límite: el de la cola solo corta lo que suspende
interface JobHandler {
    val type: String

    // su propio tiempo límite (ADR-0040 §3), para lo que puede pasar de lms.jobs.timeout (un render); null: lms.jobs.timeout.
    // lms.jobs.lease tiene que superarlo, o un trabajo en curso se reclamaría dos veces: sin eso no arranca
    val timeout: Duration? get() = null

    // trabajos de este tipo en curso a la vez, contando todas las réplicas (ADR-0040 §3): con tantos en RUNNING, los demás
    // esperan en QUEUED sin gastar intentos. null: sin límite propio, solo lms.jobs.parallelism por réplica
    val maxConcurrent: Int? get() = null

    suspend fun handle(job: Job): String?
}

// un fallo que reintentar no arregla (ADR-0022): la cola deja el trabajo FAILED en este intento, con este mensaje como
// error. el mensaje lo lee quien pidió el trabajo
class PermanentJobFailure(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

// el manejador vio que su trabajo fue cancelado (JobQueue.cancelled) y lo deja: la cola no lo cierra otra vez, ya está
// CANCELLED. lo que hizo antes de verlo (una llamada al modelo, su registro) queda; lo que haría después, no
class JobCancelled(
    job: Job,
) : RuntimeException("job ${job.id} (${job.type}) was cancelled")

// lms.jobs.*
@ConfigurationProperties("lms.jobs")
data class JobProperties(
    // false: esta réplica no ejecuta trabajos (sí los encola)
    val scheduling: Boolean = true,
    val poll: Duration = Duration.ofSeconds(5),
    // trabajos a la vez por réplica: los que reclama en cada vuelta y el tamaño de su dispatcher
    val parallelism: Int = 2,
    val maxAttempts: Int = 3,
    // espera antes del reintento n: backoff × n
    val backoff: Duration = Duration.ofSeconds(30),
    val timeout: Duration = Duration.ofMinutes(10),
    // un trabajo en curso cuyo reclamo es más viejo que esto se da por perdido (la réplica murió) y se vuelve a reclamar
    val lease: Duration = Duration.ofMinutes(15),
    // trabajos pendientes o en curso que una persona puede tener a la vez (ADR-0022): uno más es 429
    val maxActivePerRequester: Int = 5,
    // un trabajo terminado se guarda esto y luego se borra (ADR-0022); la pasada de borrado corre cada purge-every
    val retention: Duration = Duration.ofDays(30),
    val purgeEvery: Duration = Duration.ofHours(1),
) {
    init {
        require(parallelism > 0) { "lms.jobs.parallelism must be positive" }
        require(maxAttempts > 0) { "lms.jobs.max-attempts must be positive" }
        require(!backoff.isNegative) { "lms.jobs.backoff must not be negative" }
        require(maxActivePerRequester > 0) { "lms.jobs.max-active-per-requester must be positive" }
        require(retention > Duration.ZERO) { "lms.jobs.retention must be positive" }
        require(lease > timeout) { "lms.jobs.lease must be longer than lms.jobs.timeout, or a running job would be claimed twice" }
    }
}
