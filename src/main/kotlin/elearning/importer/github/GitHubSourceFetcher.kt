package elearning.importer.github

import elearning.importer.AllowedSources
import elearning.importer.ImporterProperties
import elearning.importer.SourceFetchException
import elearning.importer.SourceFetchException.Reason
import elearning.importer.SourceFetcher
import elearning.importer.SourceRef
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpHeaders
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import java.util.concurrent.TimeoutException

// el adaptador de SourceFetcher: la API de contenidos de GitHub en un host fijo, sin seguir redirecciones, con tiempo
// límite y tamaño máximo. el único sitio del código que conoce ese host (RNF-ARQ-08)
@Component
class GitHubSourceFetcher internal constructor(
    private val properties: ImporterProperties,
    baseUrl: String,
) : SourceFetcher {
    @Autowired
    constructor(properties: ImporterProperties) : this(properties, API)

    private val allowed = AllowedSources(properties.allowedSources)
    private val maxBytes = properties.maxBytes.toBytes()

    // cliente propio: Boot 4 no ofrece WebClient.Builder, y aquí no se quiere ningún valor por defecto compartido
    private val client =
        WebClient
            .builder()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(HttpClient.create().followRedirect(false).responseTimeout(properties.timeout)))
            .exchangeStrategies(
                ExchangeStrategies.builder().codecs { it.defaultCodecs().maxInMemorySize(maxBytes.toInt()) }.build(),
            ).defaultHeader(HttpHeaders.ACCEPT, RAW)
            .defaultHeader(API_VERSION_HEADER, API_VERSION)
            .defaultHeaders { headers -> properties.githubToken.takeIf { it.isNotBlank() }?.let { headers.setBearerAuth(it) } }
            .build()

    override fun fetch(ref: SourceRef): Mono<ByteArray> {
        if (!allowed.permits(ref)) return Mono.error(SourceFetchException(Reason.NOT_ALLOWED, "$ref is not an allowed source"))
        return client
            .get()
            .uri { builder ->
                builder
                    .pathSegment("repos", ref.owner, ref.repo, "contents", *ref.path.split('/').toTypedArray())
                    .queryParam("ref", ref.sha)
                    .build()
            }.exchangeToMono { response ->
                val status = response.statusCode().value()
                val length = response.headers().contentLength()
                when {
                    status == HTTP_NOT_FOUND -> reject(response, failure(Reason.NOT_FOUND, ref, "not found"))
                    status !in OK -> reject(response, failure(Reason.UNAVAILABLE, ref, "answered $status"))
                    length.isPresent && length.asLong > maxBytes -> reject(response, tooLarge(ref))
                    else -> response.bodyToMono(ByteArray::class.java).defaultIfEmpty(ByteArray(0))
                }
            }.timeout(properties.timeout)
            .onErrorMap({ it !is SourceFetchException }) { error ->
                when {
                    generateSequence(error) { it.cause }.any { it is DataBufferLimitException } -> tooLarge(ref)
                    error is TimeoutException -> failure(Reason.UNAVAILABLE, ref, "timed out", error)
                    else -> failure(Reason.UNAVAILABLE, ref, "could not be read", error)
                }
            }
    }

    // el cuerpo se descarta para devolver la conexión; una redirección cae aquí y nunca se sigue
    private fun reject(
        response: ClientResponse,
        error: SourceFetchException,
    ): Mono<ByteArray> = response.releaseBody().then(Mono.error(error))

    private fun tooLarge(ref: SourceRef) = failure(Reason.TOO_LARGE, ref, "is larger than $maxBytes bytes")

    private fun failure(
        reason: Reason,
        ref: SourceRef,
        detail: String,
        cause: Throwable? = null,
    ) = SourceFetchException(reason, "$ref $detail", cause)

    companion object {
        const val API = "https://api.github.com"
        private const val HTTP_NOT_FOUND = 404
        private val OK = 200..299

        // el archivo tal cual, no el JSON con el contenido en base64
        private const val RAW = "application/vnd.github.raw"
        private const val API_VERSION_HEADER = "X-GitHub-Api-Version"
        private const val API_VERSION = "2022-11-28"
    }
}
