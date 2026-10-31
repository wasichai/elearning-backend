package elearning.media

// los formatos que se generan desde una fuente (enum formato_artefacto de model/model.json)
enum class ArtifactFormat(
    val extension: String,
    val contentType: String,
) {
    HTML("html", "text/html"),
    PDF("pdf", "application/pdf"),
}

// lo que produjo el generador: los bytes y quién los hizo, para que el artefacto identifique su origen (RF-CON-03)
class RenderedDocument(
    val format: ArtifactFormat,
    val bytes: ByteArray,
    val generator: String,
    val generatorVersion: String,
)

// el puerto de generación (ADR-0018 §7): de una fuente AsciiDoc a un artefacto. la fuente es dato no confiable (CLAUDE.md,
// regla 6): el adaptador la procesa en modo seguro, sin red ni sistema de archivos ajeno y con tiempo límite. bloquea:
// solo se llama desde la cola, nunca desde el hilo de WebFlux
interface DocumentRenderer {
    fun render(
        source: ByteArray,
        format: ArtifactFormat,
    ): RenderedDocument
}

// la generación no pudo hacerse: el mensaje es claro y va al error del trabajo, que ve quien la pidió
open class RenderException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

// falta el generador en esta réplica: la imagen de despliegue debe traerlo (ADR-0018 §7)
class RendererUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RenderException(message, cause)
