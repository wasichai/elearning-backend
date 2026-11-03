package elearning.media.video

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.any
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import elearning.LmsApiTest
import elearning.jobs.JobRunner
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.concurrent.thread

private const val TOKEN = "a-shared-secret-for-the-render-worker-tests"

// el render de vídeo con el render encendido (ADR-0040, RF-MED-02, criterios de #297). El trabajador es un WireMock que
// hace de render-worker y tarda en contestar, como un render de verdad: así se ve que lo que pasa del límite de concurrencia
// espera en la cola y que la API sigue respondiendo mientras tanto. La cola se mueve a mano con runOnce
@TestPropertySource(
    properties = [
        "lms.jobs.scheduling=false",
        "lms.jobs.backoff=0s",
        "lms.render.enabled=true",
        "lms.render.worker-token=$TOKEN",
        "lms.render.max-concurrent=1",
        "lms.render.timeout=1m",
        // el techo de wasichai-files de las pruebas es 25 MB (application.yml)
        "lms.render.max-bytes=20MB",
    ],
)
class VideoRenderApiTest : LmsApiTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var db: DatabaseClient

    private val creator by lazy { demoUser("creador") }
    private val student by lazy { demoUser("estudiante") }
    private val admin by lazy { bearer() }
    private val examples = File("render-worker/schema/ejemplos")

    @BeforeEach
    fun cleanWorker() {
        drain()
        worker.resetAll()
        stubWorker()
    }

    private fun drain() =
        runBlocking {
            while (runner.runOnce() > 0) Unit
        }

    private fun exchange(
        method: String,
        path: String,
        token: String,
        body: Any? = null,
    ): Pair<HttpStatus, JsonNode?> {
        val spec = if (method == "POST") client.post().uri(path).bodyValue(body ?: emptyMap<String, Any>()) else client.get().uri(path)
        val r =
            spec
                .header("Authorization", token)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(r.status.value()) to r.responseBody?.let { runCatching { json.readTree(it) }.getOrNull() }
    }

    private fun resource(
        type: String = "VIDEO",
        token: String = creator,
    ): String {
        val (status, body) =
            exchange(
                "POST",
                "/api/objects/recurso/records",
                token,
                mapOf("attributes" to mapOf("titulo" to "Vídeo ${UUID.randomUUID()}", "tipo" to type, "idioma" to "es")),
            )
        assertEquals(HttpStatus.CREATED, status, body.toString())
        return body!!["id"].asString()
    }

    private fun script(
        title: String = "Un vídeo mínimo",
        name: String = "validos/minimo.json",
    ): Map<*, *> {
        val script = json.readValue(File(examples, name), Map::class.java).toMutableMap()
        script["titulo"] = title
        return script
    }

    private fun render(
        resource: String,
        script: Any,
        token: String = creator,
    ): Pair<HttpStatus, JsonNode?> = exchange("POST", "/api/lms/studio/resources/$resource/video-renders", token, mapOf("guion" to script))

    private fun job(
        id: String,
        token: String = creator,
    ) = exchange("GET", "/api/lms/studio/video-renders/$id", token)

    private fun row(id: String): Map<String, Any?> =
        runBlocking {
            db
                .sql("SELECT datos::text AS datos, resultado::text AS resultado, error, estado, intentos FROM lms.trabajo WHERE id = :id")
                .bind("id", UUID.fromString(id))
                .fetch()
                .one()
                .awaitSingle()
        }

    // criterios de #297: encolado en lms.trabajo, fuera de WebFlux, con límite de concurrencia; lo que pasa del límite espera
    // en la cola y la API responde con su latencia normal mientras el renderizador (lento) trabaja
    @Test
    fun `more renders than the limit wait in the queue while the api answers as usual`() {
        val resource = resource()
        val ids =
            (1..3).map { n ->
                val (status, body) = render(resource, script("Vídeo $n"))
                assertEquals(HttpStatus.ACCEPTED, status, body.toString())
                assertEquals("PENDIENTE", body!!["status"].asString())
                assertEquals(3, body["durationSeconds"].asInt())
                body["id"].asString()
            }
        val again = render(resource, script("Vídeo 1")).second!!["id"].asString()
        assertEquals(ids[0], again, "el mismo guion mientras no termina: el mismo trabajo")

        val queue = thread { drain() }
        try {
            val deadline = System.currentTimeMillis() + 30_000
            while (ids.none { job(it).second!!["status"].asString() == "EN_CURSO" }) {
                check(System.currentTimeMillis() < deadline) { "ningún render empezó" }
                Thread.sleep(20)
            }
            val states = ids.map { job(it).second!! }
            assertEquals(listOf("EN_CURSO", "PENDIENTE", "PENDIENTE"), states.map { it["status"].asString() }.sorted())
            assertEquals(listOf(0, 0, 1), states.map { it["attempts"].asInt() }.sorted(), "esperar en la cola no gasta intentos")

            // la API no espera al render: lo que dura una petición con el trabajador ocupado
            val started = System.nanoTime()
            val (status, _) = job(ids[0])
            val millis = (System.nanoTime() - started) / 1_000_000
            assertEquals(HttpStatus.OK, status)
            assertTrue(millis < WORKER_DELAY_MILLIS / 2, "la API tardó $millis ms con un render en curso")
        } finally {
            queue.join(120_000)
        }

        for (id in ids) {
            val done = job(id).second!!
            assertEquals("HECHO", done["status"].asString(), done.toString())
            assertEquals(1, done["attempts"].asInt())
            assertEquals(VIDEO_SHA256, done["video"]["sha256"].asString())
            assertEquals(VIDEO.size.toLong(), done["video"]["bytes"].asLong())
            assertEquals(SUBTITLES_SHA256, done["subtitles"]["sha256"].asString())
        }
        // cada render, una vez y con el secreto; nunca dos a la vez en el trabajador
        worker.verify(3, putRequestedFor(urlPathMatching("/renders/.+")).withHeader("Authorization", equalTo("Bearer $TOKEN")))
        val spans =
            worker.allServeEvents
                .filter { it.request.method.value() == "PUT" }
                .map { it.request.loggedDate.time to it.request.loggedDate.time + it.timing.totalTime }
                .sortedBy { it.first }
        spans.zipWithNext().forEach { (a, b) -> assertTrue(b.first >= a.second - 50, "dos renders a la vez: $spans") }
    }

    // criterio de #297: parámetros, salida y errores quedan en el trabajo
    @Test
    fun `the job keeps the script, the report and the error`() {
        val resource = resource()
        val ok = render(resource, script("Guardado")).second!!["id"].asString()
        val failing = render(resource, script("Imposible")).second!!["id"].asString()
        worker.stubFor(
            put(urlPathEqualTo("/renders/$failing"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(422).withBody("""{"error": "the video could not be rendered: Chrome crashed"}""")),
        )

        drain()

        val done = row(ok)
        val payload = json.readTree(done["datos"] as String)
        assertEquals("Guardado", payload["guion"]["titulo"].asString())
        assertEquals(resource, payload["resourceId"].asString())
        val result = json.readTree(done["resultado"] as String)
        assertEquals(VIDEO_SHA256, result["video"]["sha256"].asString())
        assertEquals("h264", result["ffprobe"]["streams"][0]["codec_name"].asString())
        assertEquals("4.0.540", result["versions"]["remotion"].asString())
        assertNull(done["error"])
        assertTrue(!(done["datos"] as String).contains(TOKEN) && !(done["resultado"] as String).contains(TOKEN), "el secreto no va al trabajo")

        val failed = row(failing)
        assertEquals("FAILED", failed["estado"])
        assertEquals(1, (failed["intentos"] as Number).toInt(), "lo que el trabajador rechaza no se reintenta")
        assertEquals("The render worker rejected the video (422): the video could not be rendered: Chrome crashed", failed["error"])
        assertEquals("The render worker rejected the video (422): the video could not be rendered: Chrome crashed", job(failing).second!!["error"].asString())
    }

    // ADR-0039 §5: lo que no cumple el esquema (código, HTML, una URL) no llega a la cola ni al trabajador
    @Test
    fun `a script with code, html or a url is refused before the queue`() {
        val resource = resource()
        for (name in listOf("codigo-componente.json", "diapositiva-html.json", "imagen-url.json", "regla-subtitulos-solapados.json")) {
            val (status, body) = render(resource, script(name = "invalidos/$name"))
            assertEquals(HttpStatus.BAD_REQUEST, status, "$name: $body")
        }
        val (status, body) = render(resource, script(name = "validos/completo.json"))
        assertEquals(HttpStatus.BAD_REQUEST, status, "las imágenes aún no tienen de dónde salir: $body")
        assertTrue(body.toString().contains("image slides are not rendered yet"), body.toString())
        assertEquals(HttpStatus.BAD_REQUEST, exchange("POST", "/api/lms/studio/resources/$resource/video-renders", creator, mapOf("otro" to 1)).first)
        drain()
        assertEquals(0, worker.allServeEvents.size)
    }

    @Test
    fun `only who may create versions asks, for a video resource they see, and only they follow it`() {
        val resource = resource()
        assertEquals(HttpStatus.FORBIDDEN, render(resource, script(), student).first)
        assertEquals(HttpStatus.NOT_FOUND, render(UUID.randomUUID().toString(), script()).first)
        assertEquals(HttpStatus.CONFLICT, render(resource("HTML"), script()).first)
        val id = render(resource, script("Mío")).second!!["id"].asString()
        assertEquals(HttpStatus.NOT_FOUND, job(id, admin).first, "el de otra persona no existe, tampoco para el administrador")
        assertEquals(HttpStatus.FORBIDDEN, job(id, student).first)
        assertEquals(HttpStatus.NOT_FOUND, job(UUID.randomUUID().toString()).first)
        drain()
    }

    private fun stubWorker() {
        worker.stubFor(
            put(urlPathMatching("/renders/[0-9a-f-]+"))
                .atPriority(5)
                .willReturn(aResponse().withStatus(200).withBody(REPORT).withFixedDelay(WORKER_DELAY_MILLIS.toInt())),
        )
        worker.stubFor(get(urlPathMatching("/renders/[0-9a-f-]+/video\\.mp4")).willReturn(aResponse().withStatus(200).withBody(VIDEO)))
        worker.stubFor(get(urlPathMatching("/renders/[0-9a-f-]+/subtitulos\\.vtt")).willReturn(aResponse().withStatus(200).withBody(SUBTITLES)))
        worker.stubFor(delete(urlPathMatching("/renders/[0-9a-f-]+")).willReturn(aResponse().withStatus(204)))
        worker.stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse().withStatus(404)))
    }

    companion object {
        const val WORKER_DELAY_MILLIS = 1_500L

        val VIDEO = "a fake mp4: the worker double never renders".toByteArray()
        val SUBTITLES = "WEBVTT\n".toByteArray()
        val VIDEO_SHA256 = sha256(VIDEO)
        val SUBTITLES_SHA256 = sha256(SUBTITLES)

        // el informe de render-worker (src/server.mjs) para un vídeo de 3 s
        val REPORT =
            """
            {
              "trabajo": "ignored",
              "video": {"archivo": "video.mp4", "sha256": "$VIDEO_SHA256", "bytes": ${VIDEO.size}},
              "subtitulos": {"archivo": "subtitulos.vtt", "sha256": "$SUBTITLES_SHA256", "bytes": ${SUBTITLES.size}},
              "duracionSegundos": 3,
              "salida": {"resolucion": "1280x720", "fps": 30},
              "ffprobe": {"streams": [{"codec_type": "video", "codec_name": "h264", "width": 1280, "height": 720}], "format": {"duration": "3.000000"}},
              "versiones": {"trabajador": "0.1.0", "remotion": "4.0.540"},
              "milisegundos": 1500
            }
            """.trimIndent()

        private val worker: WireMockServer by lazy {
            WireMockServer(options().bindAddress("127.0.0.1").dynamicPort()).also { server ->
                server.start()
                Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun workerUrl(registry: DynamicPropertyRegistry) {
            registry.add("lms.render.worker-url") { "http://127.0.0.1:${worker.port()}" }
        }

        private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
