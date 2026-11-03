package elearning.jobs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.RetryLaterException
import wasichai.test.WasichaiIntegrationTest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// la cola persistente (ADR-0018 §4, RNF-ARQ-09) con manejadores de prueba. el @Scheduled está apagado: cada prueba da
// las vueltas con runOnce, y una segunda réplica es otro JobRunner sobre la misma tabla
@Import(JobQueueTest.Handlers::class)
@TestPropertySource(
    properties = [
        "lms.jobs.scheduling=false",
        "lms.jobs.backoff=0s",
        "lms.jobs.parallelism=4",
        "lms.jobs.timeout=2s",
        "lms.jobs.max-active-per-requester=2",
    ],
)
class JobQueueTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var queue: JobQueue

    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var properties: JobProperties

    @Autowired
    private lateinit var db: DatabaseClient

    private val json = JsonMapper.builder().build()
    private val org = UUID.randomUUID()

    private fun status(id: UUID): JobStatus = runBlocking { queue.find(org, id) }!!

    private fun drain() = runBlocking { runner.drainAll() }

    // la cola es de todas las pruebas: lo que otras dejaron pendiente (un aviso de publicación, una generación) va antes en
    // el orden de reclamo y se colaría en las vueltas contadas de estas
    @BeforeEach
    fun drainLeftovers() = drain()

    @Test
    fun `a job runs off the request thread and keeps its result`() {
        val id = runBlocking { queue.enqueue(org, ECHO, """{"n": 1}""") }
        assertEquals(JobState.QUEUED, status(id).state)

        drain()

        val done = status(id)
        assertEquals(JobState.DONE, done.state)
        assertEquals(1, done.attempts)
        assertNull(done.error)
        assertNotNull(done.finishedAt)
        assertEquals(json.readTree("""{"echo": {"n": 1}}"""), json.readTree(done.result))
        assertEquals(1, calls.getValue(id).get())
        assertNull(runBlocking { queue.find(UUID.randomUUID(), id) }, "otra organización no lo ve")
    }

    @Test
    fun `enqueueing with the same key while the job waits returns the same job and runs it once`() {
        val key = UUID.randomUUID().toString()
        val first = runBlocking { queue.enqueue(org, ECHO, key = key) }
        val again = runBlocking { queue.enqueue(org, ECHO, """{"other": true}""", key = key) }
        val otherOrganization = runBlocking { queue.enqueue(UUID.randomUUID(), ECHO, key = key) }
        val withoutKey = runBlocking { listOf(queue.enqueue(org, ECHO), queue.enqueue(org, ECHO)) }

        assertEquals(first, again)
        assertNotEquals(first, otherOrganization, "la clave vale dentro de su organización")
        assertNotEquals(withoutKey[0], withoutKey[1], "sin clave, cada encolado es un trabajo")
        drain()
        assertEquals(1, calls.getValue(first).get())
        assertEquals(json.readTree("""{"echo": {}}"""), json.readTree(status(first).result), "vale el primer encolado")
    }

    // ADR-0022: la clave dedup lo que está en marcha, no lo que ya terminó
    @Test
    fun `once the job is over its key makes a new job`() {
        val key = UUID.randomUUID().toString()
        val first = runBlocking { queue.enqueue(org, ECHO, key = key) }
        drain()

        val second = runBlocking { queue.enqueue(org, ECHO, key = key) }

        assertNotEquals(first, second)
        assertEquals(JobState.DONE, status(first).state)
        assertEquals(JobState.QUEUED, status(second).state)
        drain()
    }

    // M2 de #216: nadie llena la cola. el tope de la prueba es 2
    @Test
    fun `a person cannot have more active jobs than the limit, and may again once they end`() {
        val person = UUID.randomUUID()
        val type = "test.capped-${UUID.randomUUID()}"
        val active = runBlocking { (1..2).map { queue.enqueue(org, type, key = "k$it", requester = person) } }

        val refused = assertThrows<RetryLaterException> { runBlocking { queue.enqueue(org, type, key = "k3", requester = person) } }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, refused.status)
        assertEquals(active[0], runBlocking { queue.enqueue(org, type, key = "k1", requester = person) }, "lo ya pedido no cuenta otra vez")
        assertNotNull(runBlocking { queue.enqueue(org, type, requester = UUID.randomUUID()) }, "el tope es por persona")
        assertNotNull(runBlocking { queue.enqueue(UUID.randomUUID(), type, requester = person) }, "y por organización")

        val claimed = runBlocking { queue.claim(listOf(type), 1) }.single()
        assertTrue(runBlocking { queue.complete(claimed, null) })
        assertNotNull(runBlocking { queue.enqueue(org, type, key = "k3", requester = person) }, "terminado uno, cabe otro")
    }

    @Test
    fun `a failing job is retried up to its limit and then stays failed with its error`() {
        val id = runBlocking { queue.enqueue(org, FLAKY, maxAttempts = 3) }

        for (attempt in 1..2) {
            runBlocking { runner.runOnce() }
            val retry = status(id)
            assertEquals(JobState.QUEUED, retry.state)
            assertEquals(attempt, retry.attempts)
            assertEquals("boom $attempt", retry.error)
        }
        runBlocking { runner.runOnce() }
        drain()

        val failed = status(id)
        assertEquals(JobState.FAILED, failed.state)
        assertEquals(3, failed.attempts)
        assertEquals("boom 3", failed.error)
        assertNotNull(failed.finishedAt)
        assertNull(failed.result)
    }

    // ADR-0022: lo que reintentar no arregla termina en su intento
    @Test
    fun `a permanent failure is not retried`() {
        val id = runBlocking { queue.enqueue(org, DOOMED, maxAttempts = 3) }

        drain()

        val failed = status(id)
        assertEquals(JobState.FAILED, failed.state)
        assertEquals(1, failed.attempts)
        assertEquals("no way", failed.error)
        assertNotNull(failed.finishedAt)
    }

    @Test
    fun `a job that exceeds the time limit fails`() {
        val id = runBlocking { queue.enqueue(org, SLOW, maxAttempts = 1) }

        drain()

        val failed = status(id)
        assertEquals(JobState.FAILED, failed.state)
        assertTrue(failed.error!!.startsWith("timed out"), failed.error)
    }

    @Test
    fun `a replica only claims the types it can run`() {
        val id = runBlocking { queue.enqueue(org, "test.nobody-runs-this") }

        drain()

        assertEquals(JobState.QUEUED, status(id).state)
        assertEquals(0, status(id).attempts)
    }

    @Test
    fun `two replicas polling together run every job exactly once`() {
        val ids = runBlocking { (1..30).map { queue.enqueue(org, COUNT) } }
        val other = JobRunner(queue, properties, listOf(Handlers.count()))

        runBlocking {
            for (replica in listOf(runner, other, runner, other)) {
                launch(Dispatchers.IO) { replica.drainAll() }
            }
        }

        assertTrue(ids.all { status(it).state == JobState.DONE }, ids.map { status(it).state }.toString())
        assertEquals(ids.associateWith { 1 }, ids.associateWith { calls[it]?.get() ?: 0 })
    }

    @Test
    fun `a running job whose replica died is claimed again once its claim is stale`() {
        val id = runBlocking { queue.enqueue(org, ECHO) }
        abandon(id, attempts = 1)

        drain()

        val done = status(id)
        assertEquals(JobState.DONE, done.state)
        assertEquals(2, done.attempts)
    }

    @Test
    fun `a stale job on its last attempt fails without running again`() {
        val id = runBlocking { queue.enqueue(org, ECHO, maxAttempts = 1) }
        abandon(id, attempts = 1)

        drain()

        assertEquals(JobState.FAILED, status(id).state)
        assertEquals("the replica running the last attempt was lost", status(id).error)
        assertNull(calls[id])
    }

    @Test
    fun `a replica that lost its claim cannot close the job`() {
        val type = "test.fence-${UUID.randomUUID()}"
        val id = runBlocking { queue.enqueue(org, type) }
        val lost = runBlocking { queue.claim(listOf(type), 1) }.single()
        abandon(id, attempts = 1)
        val current = runBlocking { queue.claim(listOf(type), 1) }.single()

        assertEquals(2, current.attempt)
        assertFalse(runBlocking { queue.complete(lost, """{"from": "lost"}""") })
        assertFalse(runBlocking { queue.fail(lost, "late") })
        assertTrue(runBlocking { queue.complete(current, """{"from": "current"}""") })
        assertEquals(json.readTree("""{"from": "current"}"""), json.readTree(status(id).result))
    }

    // ADR-0022: un manejador cierra su trabajo en su transacción y el runner lo cierra otra vez con el mismo resultado
    @Test
    fun `closing the same attempt again is harmless and still fenced`() {
        val type = "test.twice-${UUID.randomUUID()}"
        val id = runBlocking { queue.enqueue(org, type) }
        val claimed = runBlocking { queue.claim(listOf(type), 1) }.single()

        assertTrue(runBlocking { queue.complete(claimed, """{"n": 1}""") })
        assertTrue(runBlocking { queue.complete(claimed, """{"n": 1}""") })
        assertFalse(runBlocking { queue.complete(claimed.copy(attempt = 2), """{"n": 2}""") })
        assertFalse(runBlocking { queue.fail(claimed, "late") })
        assertEquals(JobState.DONE, status(id).state)
        assertEquals(json.readTree("""{"n": 1}"""), json.readTree(status(id).result))
    }

    // ADR-0033 §5: cancelar termina el trabajo, pendiente o en curso, y quien lo ejecuta ya no puede cerrarlo
    @Test
    fun `a cancelled job is never claimed, its runner cannot close it and its key is free again`() {
        val type = "test.cancel-${UUID.randomUUID()}"
        val running = runBlocking { queue.enqueue(org, type, key = "r") }
        val claimed = runBlocking { queue.claim(listOf(type), 1) }.single()
        val waiting = runBlocking { queue.enqueue(org, type, key = "w") }
        assertEquals(running, claimed.id)

        assertFalse(runBlocking { queue.cancel(UUID.randomUUID(), waiting) }, "otra organización no lo cancela")
        assertTrue(runBlocking { queue.cancel(org, waiting) })
        assertTrue(runBlocking { queue.cancel(org, running) })
        assertFalse(runBlocking { queue.cancel(org, running) }, "ya terminado")

        assertEquals(JobState.CANCELLED, status(waiting).state)
        assertNotNull(status(waiting).finishedAt)
        assertTrue(runBlocking { queue.cancelled(claimed) })
        assertFalse(runBlocking { queue.complete(claimed, """{"late": true}""") })
        assertFalse(runBlocking { queue.fail(claimed, "late") })
        assertEquals(JobState.CANCELLED, status(running).state)
        assertNull(status(running).result)
        assertTrue(runBlocking { queue.claim(listOf(type), 2) }.isEmpty())
        assertEquals(0, runBlocking { queue.activeCount(org, listOf(type)) })
        assertNotEquals(waiting, runBlocking { queue.enqueue(org, type, key = "w") }, "cancelado, la clave queda libre")
        assertEquals(1, runBlocking { queue.activeCount(org, listOf(type)) })
        runBlocking { queue.cancel(org, queue.active(org, type, "w")!!) }
    }

    @Test
    fun `a handler that sees its job cancelled stops without failing it`() {
        val id = runBlocking { queue.enqueue(org, CANCELLING) }

        drain()

        val cancelled = status(id)
        assertEquals(JobState.CANCELLED, cancelled.state)
        assertEquals(1, cancelled.attempts)
        assertNull(cancelled.error)
    }

    // L5 de #216: un trabajo terminado no se guarda para siempre; lo pendiente y lo reciente, sí
    @Test
    fun `finished jobs older than the retention are purged`() {
        val old = runBlocking { listOf(queue.enqueue(org, ECHO), queue.enqueue(org, FLAKY, maxAttempts = 1), queue.enqueue(org, "test.nobody-runs-this")) }
        val recent = runBlocking { queue.enqueue(org, ECHO) }
        drain()
        val waiting = runBlocking { queue.enqueue(org, "test.nobody-runs-this") }
        assertTrue(runBlocking { queue.cancel(org, old[2]) })
        runBlocking {
            db
                .sql("UPDATE lms.trabajo SET terminado_en = now() - interval '31 days' WHERE id = ANY(:ids)")
                .bind("ids", old.toTypedArray())
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        assertEquals(JobState.FAILED, status(old[1]).state)
        assertEquals(JobState.CANCELLED, status(old[2]).state)

        assertTrue(runBlocking { queue.purge() } >= 3)

        assertTrue(old.all { runBlocking { queue.find(org, it) } == null })
        assertEquals(JobState.DONE, status(recent).state)
        assertEquals(JobState.QUEUED, status(waiting).state)
    }

    // RNF-ARQ-09: la API responde mientras un trabajo que bloquea está en curso
    @Test
    fun `the api answers while a blocking job is running`() {
        val id = runBlocking { queue.enqueue(org, BLOCKING) }
        val worker = thread { drain() }
        try {
            assertTrue(blockingStarted.await(30, TimeUnit.SECONDS), "el trabajo no empezó")
            assertEquals(JobState.RUNNING, status(id).state)

            client
                .get()
                .uri("/api/lms/public/openapi")
                .exchange()
                .expectStatus()
                .isOk
        } finally {
            blockingRelease.countDown()
            worker.join(TimeUnit.SECONDS.toMillis(30))
        }
        assertEquals(JobState.DONE, status(id).state)
    }

    // ADR-0040 §3: un tipo con límite de concurrencia no se reclama mientras tenga tantos en curso, contando todas las
    // réplicas; los demás esperan en QUEUED sin gastar intentos
    @Test
    fun `a type with a concurrency limit keeps the rest queued, without spending attempts, on every replica`() {
        val ids = runBlocking { (1..3).map { queue.enqueue(org, CAPPED) } }
        val other = JobRunner(queue, properties, listOf(Handlers.capped()))
        val worker = thread { drain() }
        try {
            assertTrue(cappedStarted.tryAcquire(30, TimeUnit.SECONDS), "el trabajo no empezó")
            assertEquals(listOf(JobState.QUEUED, JobState.QUEUED, JobState.RUNNING), ids.map { status(it).state }.sorted())
            assertEquals(listOf(0, 0, 1), ids.map { status(it).attempts }.sorted())
            assertEquals(0, runBlocking { other.runOnce() }, "otra réplica tampoco reclama uno más")
            assertTrue(runBlocking { queue.claimCapped(CAPPED, 1, 5) }.isEmpty())
        } finally {
            cappedRelease.release(3)
            worker.join(TimeUnit.SECONDS.toMillis(60))
        }
        assertEquals(List(3) { JobState.DONE }, ids.map { status(it).state })
        assertEquals(List(3) { 1 }, ids.map { status(it).attempts }, "esperar en la cola no gasta intentos")
        assertEquals(1, cappedPeak.get(), "nunca más de uno a la vez")
    }

    @Test
    fun `a capped claim takes only what fits and ignores stale claims`() {
        val type = "test.capped-claim-${UUID.randomUUID()}"
        val ids = runBlocking { (1..4).map { queue.enqueue(org, type) } }

        assertEquals(2, runBlocking { queue.claimCapped(type, 2, 5) }.size)
        assertTrue(runBlocking { queue.claimCapped(type, 2, 5) }.isEmpty())
        abandon(ids.first { status(it).state == JobState.RUNNING }, attempts = 1)
        val retaken = runBlocking { queue.claimCapped(type, 2, 5) }
        assertEquals(1, retaken.size, "el reclamo caducado no cuenta y se retoma: uno")
        assertEquals(1, runBlocking { queue.claimCapped(type, 4, 1) }.size, "como mucho limit")
        runBlocking { ids.forEach { queue.cancel(org, it) } }
    }

    // ADR-0040 §3: un manejador puede tener su propio tiempo límite, mayor que lms.jobs.timeout (2 s aquí)
    @Test
    fun `a handler with its own time limit may run longer than the queue's`() {
        val id = runBlocking { queue.enqueue(org, PATIENT, maxAttempts = 1) }

        drain()

        assertEquals(JobState.DONE, status(id).state, status(id).error)
    }

    @Test
    fun `a handler time limit longer than the lease does not start`() {
        val handler =
            object : JobHandler {
                override val type = "test.too-patient"
                override val timeout = properties.lease

                override suspend fun handle(job: Job): String? = null
            }

        val error = assertThrows<IllegalArgumentException> { JobRunner(queue, properties, listOf(handler)) }
        assertTrue(error.message!!.startsWith("lms.jobs.lease must be longer than"), error.message)
    }

    // vueltas hasta que no quede nada que reclamar
    private suspend fun JobRunner.drainAll() {
        while (true) {
            if (runOnce() == 0) return
        }
    }

    // simula una réplica que murió con el trabajo en curso: su reclamo es de hace un día
    private fun abandon(
        id: UUID,
        attempts: Int,
    ) = runBlocking {
        db
            .sql("UPDATE lms.trabajo SET estado = 'RUNNING', intentos = :attempts, reclamado_en = now() - interval '1 day' WHERE id = :id")
            .bind("attempts", attempts)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Handlers {
        @Bean
        fun echoHandler(): JobHandler = handler(ECHO) { """{"echo": ${it.payload}}""" }

        @Bean
        fun flakyHandler(): JobHandler = handler(FLAKY) { error("boom ${it.attempt}") }

        @Bean
        fun doomedHandler(): JobHandler = handler(DOOMED) { throw PermanentJobFailure("no way") }

        @Bean
        fun slowHandler(): JobHandler =
            handler(SLOW) {
                delay(60_000)
                null
            }

        @Bean
        fun countHandler(): JobHandler = count()

        @Bean
        fun cappedHandler(): JobHandler = capped()

        @Bean
        fun patientHandler(): JobHandler =
            object : JobHandler {
                override val type = PATIENT
                override val timeout: Duration = Duration.ofSeconds(10)

                override suspend fun handle(job: Job): String? {
                    delay(3_000)
                    return null
                }
            }

        // se cancela a sí mismo, como lo haría quien lo pidió mientras corre, y lo ve antes de seguir
        @Bean
        fun cancellingHandler(queue: JobQueue): JobHandler =
            handler(CANCELLING) {
                queue.cancel(it.organizationId, it.id)
                if (queue.cancelled(it)) throw JobCancelled(it)
                null
            }

        @Bean
        fun blockingHandler(): JobHandler =
            handler(BLOCKING) {
                blockingStarted.countDown()
                // bloquea el hilo de verdad, como un proceso externo
                check(blockingRelease.await(60, TimeUnit.SECONDS)) { "never released" }
                null
            }

        companion object {
            // uno a la vez; cada uno espera a que la prueba lo suelte. cappedPeak guarda cuántos llegó a haber a la vez
            fun capped(): JobHandler =
                object : JobHandler {
                    override val type = CAPPED
                    override val maxConcurrent = 1

                    override suspend fun handle(job: Job): String? {
                        val now = cappedRunning.incrementAndGet()
                        cappedPeak.accumulateAndGet(now, ::maxOf)
                        try {
                            cappedStarted.release()
                            check(cappedRelease.tryAcquire(60, TimeUnit.SECONDS)) { "never released" }
                        } finally {
                            cappedRunning.decrementAndGet()
                        }
                        return null
                    }
                }

            fun count(): JobHandler =
                handler(COUNT) {
                    delay(5)
                    null
                }

            private fun handler(
                jobType: String,
                body: suspend (Job) -> String?,
            ): JobHandler =
                object : JobHandler {
                    override val type = jobType

                    override suspend fun handle(job: Job): String? {
                        calls.computeIfAbsent(job.id) { AtomicInteger() }.incrementAndGet()
                        return body(job)
                    }
                }
        }
    }

    companion object {
        const val ECHO = "test.echo"
        const val FLAKY = "test.flaky"
        const val SLOW = "test.slow"
        const val DOOMED = "test.doomed"
        const val COUNT = "test.count"
        const val BLOCKING = "test.blocking"
        const val CANCELLING = "test.cancelling"
        const val CAPPED = "test.capped"
        const val PATIENT = "test.patient"

        val calls = ConcurrentHashMap<UUID, AtomicInteger>()
        val blockingStarted = CountDownLatch(1)
        val blockingRelease = CountDownLatch(1)
        val cappedStarted = Semaphore(0)
        val cappedRelease = Semaphore(0)
        val cappedRunning = AtomicInteger()
        val cappedPeak = AtomicInteger()
    }
}
