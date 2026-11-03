package elearning.media.video

import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import tools.jackson.databind.json.JsonMapper
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.UUID

// un fallo al hablar con el trabajador. permanent: reintentar no lo arregla (el trabajador no existe, rechazó el guion o el
// secreto, el render es imposible); si no, la cola lo reintenta (ocupado, arrancando, error de red a mitad)
class RenderWorkerException(
    message: String,
    val permanent: Boolean,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

// un archivo del render: su sha256 y su tamaño, según el informe del trabajador y comprobados al guardarlo
data class WorkerFile(
    val sha256: String,
    val bytes: Long,
)

// el cliente del trabajador de render (render-worker/, ADR-0040 §3): HTTP interno con secreto compartido, con el WebClient
// reactivo, sin bloquear hilos y sin seguir redirecciones. Solo existe con lms.render.enabled (VideoRenderJobHandler).
// El secreto nunca va en un mensaje de error
class RenderWorkerClient(
    private val properties: RenderProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client =
        WebClient
            .builder()
            .baseUrl(properties.workerUrl.trimEnd('/'))
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient
                        .create()
                        .followRedirect(false)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                        // el PUT responde cuando el render termina
                        .responseTimeout(properties.timeout),
                ),
            ).exchangeStrategies(ExchangeStrategies.builder().codecs { it.defaultCodecs().maxInMemorySize(MAX_REPORT_BYTES) }.build())
            .defaultHeaders { it.setBearerAuth(properties.workerToken) }
            .build()

    // PUT /renders/{trabajo}: espera al informe (JSON). idempotente por trabajo: un reintento recibe el mismo informe
    suspend fun render(
        job: UUID,
        request: String,
    ): String =
        call("render") {
            client
                .put()
                .uri("/renders/{id}", job)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchangeToMono { response ->
                    if (response.statusCode().is2xxSuccessful) response.bodyToMono(String::class.java) else rejected(response)
                }
        }

    // GET /renders/{trabajo}/{name}: los bytes tal como llegan, sin juntarlos, cortados en cuanto pasan de maxBytes. quien los
    // consume los libera; su sha256 lo comprueba quien los guarda (wasichai-files lo calcula al guardarlos)
    fun stream(
        job: UUID,
        name: String,
        maxBytes: Long,
    ): Flux<DataBuffer> =
        Flux
            .defer {
                var size = 0L
                client
                    .get()
                    .uri("/renders/{id}/{name}", job, name)
                    .retrieve()
                    .onStatus({ !it.is2xxSuccessful }) { rejected<Throwable>(it) }
                    .bodyToFlux(DataBuffer::class.java)
                    .map { buffer ->
                        size += buffer.readableByteCount()
                        if (size > maxBytes) {
                            DataBufferUtils.release(buffer)
                            throw RenderWorkerException("The rendered $name is larger than $maxBytes bytes", permanent = true)
                        }
                        buffer
                    }
            }.onErrorMap({ it !is RenderWorkerException }) { network("download $name", it) }

    // lo mismo, junto en memoria: solo para lo pequeño (los subtítulos)
    suspend fun fetch(
        job: UUID,
        name: String,
        maxBytes: Long,
    ): ByteArray {
        val joined = DataBufferUtils.join(stream(job, name, maxBytes)).awaitSingleOrNull() ?: return ByteArray(0)
        try {
            return ByteArray(joined.readableByteCount()).also { joined.read(it) }
        } finally {
            DataBufferUtils.release(joined)
        }
    }

    // DELETE /renders/{trabajo}: lo que no se borre aquí lo borra el trabajador pasada una hora, así que un fallo solo se avisa
    suspend fun delete(job: UUID) {
        try {
            call("delete") {
                client
                    .delete()
                    .uri("/renders/{id}", job)
                    .exchangeToMono { response ->
                        if (response.statusCode().is2xxSuccessful) response.releaseBody().thenReturn("") else rejected(response)
                    }
            }
        } catch (e: RenderWorkerException) {
            log.warn("render {} could not be deleted from the worker: {}", job, e.message)
        }
    }

    private suspend fun <T : Any> call(
        what: String,
        request: () -> Mono<T>,
    ): T =
        request()
            .onErrorMap({ it !is RenderWorkerException }) { network(what, it) }
            .awaitSingle()

    // la respuesta de error del trabajador: { error, issues? }
    private fun <T : Any> rejected(response: ClientResponse): Mono<T> {
        val status = response.statusCode().value()
        return response
            .bodyToMono(String::class.java)
            .defaultIfEmpty("")
            .flatMap { body -> Mono.error(statusFailure(status, body)) }
    }

    private fun statusFailure(
        status: Int,
        body: String,
    ): RenderWorkerException {
        val detail = detailOf(body)
        return when (status) {
            HTTP_UNAUTHORIZED ->
                RenderWorkerException(
                    "The render worker refused the shared secret: LMS_RENDER_WORKER_TOKEN must be the RENDER_WORKER_TOKEN of the worker",
                    permanent = true,
                )
            in PERMANENT -> RenderWorkerException("The render worker rejected the video ($status): $detail", permanent = true)
            HTTP_UNAVAILABLE -> RenderWorkerException("The render worker is busy or starting (503): $detail", permanent = false)
            else -> RenderWorkerException("The render worker answered $status: $detail", permanent = false)
        }
    }

    // trabajador inexistente (la conexión se rechaza, el nombre no existe o no contesta al conectar): permanente, con lo que
    // hay que mirar. Lo demás (se corta a mitad, tarda más que lms.render.timeout) se reintenta
    private fun network(
        what: String,
        error: Throwable,
    ): RenderWorkerException {
        val causes = generateSequence(error) { it.cause }.toList()
        val unreachable = causes.firstOrNull { it is ConnectException || it is UnknownHostException }
        return if (unreachable != null) {
            RenderWorkerException(
                "The render worker at ${properties.workerUrl} cannot be reached (${unreachable.javaClass.simpleName}): " +
                    "check that the render-worker container is running and that LMS_RENDER_WORKER_URL points to it",
                permanent = true,
                error,
            )
        } else {
            RenderWorkerException("The render worker failed to $what: ${error.message ?: error.javaClass.simpleName}", permanent = false, error)
        }
    }

    private fun detailOf(body: String): String =
        runCatching {
            val node = MAPPER.readTree(body)
            val issues =
                node
                    .get("issues")
                    ?.values()
                    ?.take(MAX_ISSUES)
                    ?.joinToString("; ") { "${it.get("path")?.asString()}: ${it.get("message")?.asString()}" }
            listOfNotNull(node.get("error")?.asString(), issues).joinToString(": ")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(MAX_DETAIL).ifBlank { "no detail" }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val MAX_REPORT_BYTES = 2 * 1024 * 1024
        private const val MAX_ISSUES = 5
        private const val MAX_DETAIL = 500
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_UNAVAILABLE = 503

        // 400 esquema, 409 el mismo trabajo con otro guion, 413 demasiado grande, 415 no es JSON, 422 render imposible (ADR-0040 §3)
        private val PERMANENT = setOf(400, 409, 413, 415, 422)
        private val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
