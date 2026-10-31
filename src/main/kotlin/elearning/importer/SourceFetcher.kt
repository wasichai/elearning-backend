package elearning.importer

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import reactor.core.publisher.Mono
import java.time.Duration

// el importador lee sus fuentes por este puerto (ADR-0018 §5): solo repositorios de la lista configurada y en un commit
// exacto. ninguna URL viene del usuario, lo que corta el SSRF por construcción. el único adaptador es la API de GitHub
fun interface SourceFetcher {
    // los bytes del archivo en ese commit; falla con SourceFetchException
    fun fetch(ref: SourceRef): Mono<ByteArray>
}

class SourceFetchException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    enum class Reason {
        // el repositorio (o ese commit) no está en lms.importer.allowed-sources
        NOT_ALLOWED,

        // el repositorio, el commit o la ruta no existen (o el token no los ve)
        NOT_FOUND,

        // pasa de lms.importer.max-bytes
        TOO_LARGE,

        // tiempo límite, redirección, error del servidor o de red: quizá funcione más tarde
        UNAVAILABLE,
    }
}

// lms.importer.* — el host no se configura: es fijo en el adaptador
@ConfigurationProperties("lms.importer")
data class ImporterProperties(
    val allowedSources: List<String> = emptyList(),
    val timeout: Duration = Duration.ofSeconds(10),
    val maxBytes: DataSize = DataSize.ofMegabytes(2),
    // opcional: repositorios privados y más cuota de la API. nunca en un archivo, solo por entorno
    val githubToken: String = "",
)
