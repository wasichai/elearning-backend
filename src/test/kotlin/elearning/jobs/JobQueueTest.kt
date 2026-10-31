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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
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
        fun slowHandler(): JobHandler =
            handler(SLOW) {
                delay(60_000)
                null
            }

        @Bean
        fun countHandler(): JobHandler = count()

        @Bean
        fun blockingHandler(): JobHandler =
            handler(BLOCKING) {
                blockingStarted.countDown()
                // bloquea el hilo de verdad, como un proceso externo
                check(blockingRelease.await(60, TimeUnit.SECONDS)) { "never released" }
                null
            }

        companion object {
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
        const val COUNT = "test.count"
        const val BLOCKING = "test.blocking"

        val calls = ConcurrentHashMap<UUID, AtomicInteger>()
        val blockingStarted = CountDownLatch(1)
        val blockingRelease = CountDownLatch(1)
    }
}
