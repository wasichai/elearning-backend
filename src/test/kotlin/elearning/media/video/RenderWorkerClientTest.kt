package elearning.media.video

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

// el cliente del trabajador contra un WireMock que hace de render-worker: qué errores son permanentes y cuáles se
// reintentan, el secreto en la cabecera y nunca en un mensaje, y la descarga comprobada al vuelo
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RenderWorkerClientTest {
    private val worker = WireMockServer(options().bindAddress("127.0.0.1").dynamicPort()).also { it.start() }
    private val token = "a-shared-secret-for-the-render-worker-1234"
    private val job = UUID.randomUUID()

    private fun client(
        url: String = "http://127.0.0.1:${worker.port()}",
        timeout: Duration = Duration.ofSeconds(5),
    ) = RenderWorkerClient(RenderProperties(enabled = true, workerUrl = url, workerToken = token, timeout = timeout))

    @BeforeEach
    fun reset() = worker.resetAll()

    @AfterAll
    fun stop() = worker.stop()

    private fun failure(block: suspend () -> Unit): RenderWorkerException = assertThrows<RenderWorkerException> { runBlocking { block() } }

    @Test
    fun `a render sends the script with the shared secret and returns the report`() {
        worker.stubFor(put(urlPathEqualTo("/renders/$job")).willReturn(aResponse().withStatus(200).withBody("""{"trabajo": "$job"}""")))

        val report = runBlocking { client().render(job, """{"guion": {"version": 1}}""") }

        assertEquals("""{"trabajo": "$job"}""", report)
        worker.verify(
            putRequestedFor(urlPathEqualTo("/renders/$job"))
                .withHeader("Authorization", equalTo("Bearer $token"))
                .withRequestBody(equalToJson("""{"guion": {"version": 1}}""")),
        )
    }

    @Test
    fun `what the worker rejects is permanent and says why`() {
        for (status in listOf(400, 409, 413, 422)) {
            worker.stubFor(
                put(urlPathEqualTo("/renders/$job")).willReturn(
                    aResponse().withStatus(status).withBody("""{"error": "nope", "issues": [{"path": "guion.componente", "message": "Unrecognized key"}]}"""),
                ),
            )
            val error = failure { client().render(job, "{}") }
            assertTrue(error.permanent, "$status")
            assertEquals("The render worker rejected the video ($status): nope: guion.componente: Unrecognized key", error.message)
        }
    }

    @Test
    fun `a wrong shared secret is permanent and the secret is not in the message`() {
        worker.stubFor(put(urlPathEqualTo("/renders/$job")).willReturn(aResponse().withStatus(401).withBody("""{"error": "missing or wrong bearer token"}""")))

        val error = failure { client().render(job, "{}") }

        assertTrue(error.permanent)
        assertTrue(error.message!!.contains("LMS_RENDER_WORKER_TOKEN"), error.message)
        assertFalse(error.message!!.contains(token))
    }

    @Test
    fun `a busy worker, an unexpected error or a slow answer are retried`() {
        worker.stubFor(put(urlPathEqualTo("/renders/$job")).willReturn(aResponse().withStatus(503).withBody("""{"error": "already rendering 1 videos"}""")))
        assertFalse(failure { client().render(job, "{}") }.permanent)

        worker.stubFor(put(urlPathEqualTo("/renders/$job")).willReturn(aResponse().withStatus(500).withBody("""{"error": "internal error"}""")))
        assertFalse(failure { client().render(job, "{}") }.permanent)

        worker.stubFor(put(urlPathEqualTo("/renders/$job")).willReturn(aResponse().withStatus(200).withBody("{}").withFixedDelay(2_000)))
        assertFalse(failure { client(timeout = Duration.ofMillis(300)).render(job, "{}") }.permanent)
    }

    // criterio de #297: sin trabajador, fallo permanente con un mensaje claro
    @Test
    fun `a worker that is not there is a permanent failure that says what to check`() {
        val closed = ServerSocket(0).use { it.localPort }

        val refused = failure { client("http://127.0.0.1:$closed").render(job, "{}") }
        assertTrue(refused.permanent)
        assertTrue(refused.message!!.startsWith("The render worker at http://127.0.0.1:$closed cannot be reached"), refused.message)
        assertTrue(refused.message!!.contains("render-worker container is running"), refused.message)

        val unknown = failure { client("http://render-worker.invalid:8095").render(job, "{}") }
        assertTrue(unknown.permanent, unknown.message)
        assertTrue(unknown.message!!.contains("cannot be reached"), unknown.message)
    }

    @Test
    fun `a download is hashed on the fly and capped`() {
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        worker.stubFor(get(urlPathEqualTo("/renders/$job/video.mp4")).willReturn(aResponse().withStatus(200).withBody(bytes)))

        val file = runBlocking { client().download(job, "video.mp4", 200_000) }

        assertEquals(WorkerFile(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), 100_000), file)
        val tooLarge = failure { client().download(job, "video.mp4", 50_000) }
        assertTrue(tooLarge.permanent)
        assertEquals("The rendered video.mp4 is larger than 50000 bytes", tooLarge.message)

        worker.stubFor(get(urlPathEqualTo("/renders/$job/video.mp4")).willReturn(aResponse().withStatus(404).withBody("""{"error": "not finished"}""")))
        assertFalse(failure { client().download(job, "video.mp4", 200_000) }.permanent, "el trabajador se reinició: otro intento lo vuelve a pedir")
    }

    @Test
    fun `a delete that fails does not fail the job`() {
        worker.stubFor(delete(urlPathEqualTo("/renders/$job")).willReturn(aResponse().withStatus(409).withBody("""{"error": "still running"}""")))

        runBlocking { client().delete(job) }

        worker.verify(deleteRequestedFor(urlPathEqualTo("/renders/$job")).withHeader("Authorization", equalTo("Bearer $token")))
    }
}
