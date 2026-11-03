package elearning.media.video

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.any
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import elearning.LmsApiTest
import elearning.jobs.JobRunner
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
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
        // por debajo del techo de wasichai-files (application.yml)
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
    private val reviewer by lazy { demoUser("revisor") }
    private val teacher by lazy { demoUser("docente") }
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
        val spec =
            when (method) {
                "POST" -> client.post().uri(path).bodyValue(body ?: emptyMap<String, Any>())
                "PATCH" -> client.patch().uri(path).bodyValue(body!!)
                else -> client.get().uri(path)
            }
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

    private fun patchVersion(
        id: String,
        token: String,
        attributes: Map<String, Any?>,
    ) = exchange("PATCH", "/api/objects/version_recurso/records/$id", token, mapOf("attributes" to attributes))

    private fun preview(
        version: String,
        token: String,
    ): Pair<HttpStatus, ByteArray?> {
        val r =
            client
                .get()
                .uri("/api/lms/studio/resource-versions/$version/video")
                .header("Authorization", token)
                .exchange()
                .expectBody(ByteArray::class.java)
                .returnResult()
        if (r.status.is2xxSuccessful) {
            assertEquals("video/mp4", r.responseHeaders.contentType.toString())
            assertEquals("nosniff", r.responseHeaders.getFirst("X-Content-Type-Options"))
            assertTrue(r.responseHeaders.contentDisposition.isInline, r.responseHeaders.toString())
        }
        return HttpStatus.valueOf(r.status.value()) to r.responseBody
    }

    private fun renderedVersion(title: String = "Validado"): Pair<String, JsonNode> {
        val resource = resource()
        val id = render(resource, script(title)).second!!["id"].asString()
        drain()
        val done = job(id).second!!
        assertEquals("HECHO", done["status"].asString(), done.toString())
        return resource to done
    }

    // criterios de #298: el vídeo validado se guarda una vez, en una versión en BORRADOR a nombre de quien lo pidió, con su
    // sha256; sus artefactos MP4 (sin copia de los bytes) y VTT guardan sha256, parámetros e informe de ffprobe
    @Test
    fun `a validated render becomes a draft version that holds the video and its sha256`() {
        val (resource, done) = renderedVersion()
        val versionId = done["versionId"].asString()
        assertEquals(1, done["versionNumber"].asInt())
        val version = get("/api/objects/version_recurso/records/$versionId", creator)["attributes"]
        assertEquals("BORRADOR", version["estado"].asString())
        assertEquals(get("/api/auth/me", creator)["userId"].asString(), version["autor_id"].asString())
        assertEquals(resource, version["recurso"].let { if (it.isObject) it["id"] else it }.asString())
        assertEquals(VIDEO_SHA256, version["video"]["sha256"].asString())
        assertTrue(version["archivo"]?.isNull ?: true, "el vídeo no se copia en el archivo de la versión")

        val artifacts = get("/api/objects/artefacto_generado/records?version_recurso=$versionId&size=10", creator)["content"].values()
        val byFormat = artifacts.associateBy { it["attributes"]["formato"].asString() }
        assertEquals(setOf("MP4", "VTT"), byFormat.keys)
        val mp4 = byFormat.getValue("MP4")["attributes"]
        assertEquals(VIDEO_SHA256, mp4["sha256"].asString())
        assertTrue(mp4["archivo"]?.isNull ?: true, "una sola copia del vídeo: la de la versión")
        assertEquals("remotion", mp4["generador"].asString())
        assertEquals("4.0.540 (render-worker 0.1.0)", mp4["version_generador"].asString())
        assertEquals(done["id"].asString(), mp4["trabajo_id"].asString())
        assertEquals(json.readTree("""{"resolucion": "1280x720", "fps": 30, "duracionSegundos": 3}"""), json.readTree(mp4["parametros"].asString()))
        assertEquals("h264", json.readTree(mp4["informe"].asString())["ffprobe"]["streams"][0]["codec_name"].asString())
        val vtt = byFormat.getValue("VTT")["attributes"]
        assertEquals(SUBTITLES_SHA256, vtt["sha256"].asString())
        assertEquals(SUBTITLES_SHA256, vtt["archivo"]["sha256"].asString())
        assertEquals(mp4["hash_fuente"].asString(), vtt["hash_fuente"].asString())

        // un render, recogido y borrado del trabajador
        worker.verify(1, putRequestedFor(urlPathMatching("/renders/.+")))
        worker.verify(1, deleteRequestedFor(urlPathMatching("/renders/.+")))
    }

    // criterios de #298: duración, resolución, pistas y subtítulos contra el guion; lo que no cuadra no crea versión, así que
    // un render fallido o incompleto nunca se ofrece para publicar
    @Test
    fun `a render that does not match its script fails without creating a version`() {
        val resource = resource()
        val cases =
            mapOf(
                "Con audio" to REPORT.replace("\"streams\":[", "\"streams\":[{\"index\":1,\"codec_type\":\"audio\",\"codec_name\":\"aac\"},"),
                "A 25 fps" to REPORT.replace("\"r_frame_rate\":\"30/1\"", "\"r_frame_rate\":\"25/1\""),
                "Más corto" to REPORT.replace("\"duration\":\"3.000000\"", "\"duration\":\"1.000000\""),
                "En 1080p" to REPORT.replace("\"width\":1280", "\"width\":1920"),
                "Sin ffprobe" to
                    json.readTree(REPORT).let {
                        (it as ObjectNode).remove("ffprobe")
                        it.toString()
                    },
            )
        val ids =
            cases.map { (title, report) ->
                val id = render(resource, script(title)).second!!["id"].asString()
                worker.stubFor(put(urlPathEqualTo("/renders/$id")).atPriority(1).willReturn(aResponse().withStatus(200).withBody(report)))
                title to id
            }
        // el tope por persona de la cola es 5 trabajos activos: el sexto, después de vaciarla
        drain()
        val subtitled = render(resource, script("Subtítulos de más")).second!!["id"].asString()
        val extraCue = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nde más\n".toByteArray()
        worker.stubFor(
            put(urlPathEqualTo("/renders/$subtitled"))
                .atPriority(1)
                .willReturn(
                    aResponse()
                        .withStatus(
                            200,
                        ).withBody(REPORT.replace(SUBTITLES_SHA256, sha256(extraCue)).replace("\"bytes\": ${SUBTITLES.size}", "\"bytes\": ${extraCue.size}")),
                ),
        )
        worker.stubFor(get(urlPathEqualTo("/renders/$subtitled/subtitulos.vtt")).atPriority(1).willReturn(aResponse().withStatus(200).withBody(extraCue)))

        drain()

        val expected =
            mapOf(
                "Con audio" to "the video has 1 audio tracks, but its script has no audio",
                "A 25 fps" to "the video runs at 25/1 fps, not 30",
                "Más corto" to "lasts 1.0 s, not 3 s",
                "En 1080p" to "the video is 1920x720, not 1280x720",
                "Sin ffprobe" to "the render report has no ffprobe output",
            )
        for ((title, id) in ids + ("Subtítulos de más" to subtitled)) {
            val failed = job(id).second!!
            assertEquals("FALLIDO", failed["status"].asString(), "$title: $failed")
            assertEquals(1, failed["attempts"].asInt(), "$title: no se reintenta")
            assertTrue(failed["error"].asString().startsWith("The rendered video does not match its script: "), failed["error"].asString())
            assertTrue(failed["error"].asString().contains(expected[title] ?: "the subtitles have 1 cues, the script 0"), "$title: ${failed["error"]}")
            assertTrue(failed["versionId"]?.isNull ?: true, title)
        }
        assertEquals(0, get("/api/objects/version_recurso/records?recurso=$resource&size=10", creator)["content"].size(), "ninguna versión")
        // lo que no vale tampoco se descarga entero: el MP4 ni se pide
        worker.verify(0, getRequestedFor(urlPathMatching("/renders/.+/video\\.mp4")))
    }

    // un MP4 que llega distinto del informe (cortado, otro archivo) no se guarda: el intento se repite y, si vuelve a no
    // cuadrar, el trabajo falla sin versión
    @Test
    fun `a video whose bytes do not match the report is not stored`() {
        val resource = resource()
        val id = render(resource, script("Cortado")).second!!["id"].asString()
        worker.stubFor(get(urlPathEqualTo("/renders/$id/video.mp4")).atPriority(1).willReturn(aResponse().withStatus(200).withBody("otro".toByteArray())))

        drain()

        val failed = job(id).second!!
        assertEquals("FALLIDO", failed["status"].asString(), failed.toString())
        assertTrue(failed["attempts"].asInt() > 1, "un archivo cortado se vuelve a pedir")
        assertTrue(failed["error"].asString().contains("does not match the render report"), failed["error"].asString())
        assertEquals(0, get("/api/objects/version_recurso/records?recurso=$resource&size=10", creator)["content"].size())
    }

    // criterio de #298: una versión de un recurso VIDEO no se envía a revisión ni se publica sin vídeo validado, y ese vídeo
    // solo lo pone el render: ni el autor ni el administrador lo escriben por la API de registros
    @Test
    fun `a video version without a validated video is not offered for publishing`() {
        val resource = resource()
        val me = get("/api/auth/me", creator)["userId"].asString()
        val (created, empty) =
            exchange(
                "POST",
                "/api/objects/version_recurso/records",
                creator,
                mapOf("attributes" to mapOf("recurso" to resource, "numero" to 1, "autor_id" to me)),
            )
        assertEquals(HttpStatus.CREATED, created, empty.toString())
        val (status, body) = patchVersion(empty!!["id"].asString(), creator, mapOf("estado" to "EN_REVISION"))
        assertEquals(HttpStatus.CONFLICT, status, body.toString())
        assertTrue(body.toString().contains("without a validated video"), body.toString())

        val (_, done) = renderedVersion("Para revisar")
        val rendered = done["versionId"].asString()
        val video = get("/api/objects/version_recurso/records/$rendered", creator)["attributes"]["video"]["id"].asString()
        for (token in listOf(creator, admin)) {
            assertEquals(HttpStatus.FORBIDDEN, patchVersion(rendered, token, mapOf("video" to null)).first, "nadie quita el vídeo")
            assertTrue(patchVersion(empty["id"].asString(), token, mapOf("video" to video)).first.is4xxClientError, "nadie lo pone")
        }
        val withVideo =
            exchange(
                "POST",
                "/api/objects/version_recurso/records",
                creator,
                mapOf("attributes" to mapOf("recurso" to resource, "numero" to 9, "autor_id" to me, "video" to video)),
            )
        assertTrue(withVideo.first.is4xxClientError, withVideo.toString())
        assertTrue(get("/api/objects/version_recurso/records/${empty["id"].asString()}", creator)["attributes"]["video"]?.isNull ?: true)
        assertEquals(HttpStatus.CONFLICT, patchVersion(empty["id"].asString(), creator, mapOf("estado" to "EN_REVISION")).first)
        assertEquals(HttpStatus.OK, patchVersion(rendered, creator, mapOf("estado" to "EN_REVISION")).first)
        assertEquals(HttpStatus.OK, patchVersion(rendered, reviewer, mapOf("estado" to "APROBADA")).first)
        assertEquals(VIDEO_SHA256, get("/api/objects/version_recurso/records/$rendered", creator)["attributes"]["video"]["sha256"].asString())
    }

    // criterio de #298: el revisor previsualiza el vídeo antes de decidir; quien no decide ni es el autor, no (403), y quien
    // no ve la versión tampoco sabe que existe (404)
    @Test
    fun `the reviewer previews the video before deciding, and nobody else but its author`() {
        val (_, done) = renderedVersion("Previsualizado")
        val version = done["versionId"].asString()
        assertEquals(HttpStatus.OK, patchVersion(version, creator, mapOf("estado" to "EN_REVISION")).first)

        val (ok, bytes) = preview(version, reviewer)
        assertEquals(HttpStatus.OK, ok)
        assertArrayEquals(VIDEO, bytes)
        assertEquals(HttpStatus.OK, preview(version, creator).first, "su autor")
        assertEquals(HttpStatus.FORBIDDEN, preview(version, teacher).first, "lee la versión (VER_TODAS) pero no decide")
        assertEquals(HttpStatus.NOT_FOUND, preview(version, student).first, "no la ve")
        assertEquals(HttpStatus.NOT_FOUND, preview(UUID.randomUUID().toString(), reviewer).first)

        // una versión sin vídeo renderizado no tiene previsualización
        val me = get("/api/auth/me", creator)["userId"].asString()
        val manual =
            exchange(
                "POST",
                "/api/objects/version_recurso/records",
                creator,
                mapOf("attributes" to mapOf("recurso" to resource(), "numero" to 1, "autor_id" to me)),
            )
        assertEquals(HttpStatus.NOT_FOUND, preview(manual.second!!["id"].asString(), reviewer).first)

        // y decide después de verla
        assertEquals(HttpStatus.OK, patchVersion(version, reviewer, mapOf("estado" to "APROBADA")).first)
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

        // la salida real de ffprobe sobre un H.264 de 1280x720, 30 fps y 3 s sin audio (RenderedVideoCheckTest), con el tamaño
        // del vídeo de mentira
        private val FFPROBE =
            JsonMapper.builder().build().readTree(VideoRenderApiTest::class.java.getResource("/render/ffprobe/ok.json")!!.readText()).also {
                (it.get("format") as ObjectNode).put("size", VIDEO.size.toString())
            }

        // el informe de render-worker (src/server.mjs) para un vídeo de 3 s
        val REPORT =
            """
            {
              "trabajo": "ignored",
              "video": {"archivo": "video.mp4", "sha256": "$VIDEO_SHA256", "bytes": ${VIDEO.size}},
              "subtitulos": {"archivo": "subtitulos.vtt", "sha256": "$SUBTITLES_SHA256", "bytes": ${SUBTITLES.size}},
              "duracionSegundos": 3,
              "salida": {"resolucion": "1280x720", "fps": 30},
              "ffprobe": $FFPROBE,
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

        fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
