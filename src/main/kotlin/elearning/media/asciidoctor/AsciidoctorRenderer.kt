package elearning.media.asciidoctor

import elearning.media.ArtifactFormat
import elearning.media.DocumentRenderer
import elearning.media.RenderException
import elearning.media.RenderedDocument
import elearning.media.RendererUnavailableException
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// lms.media.asciidoctor.*: los ejecutables (nombre en el PATH o ruta absoluta), el tiempo límite de cada generación y el
// tamaño máximo de lo que produce (el del campo archivo de artefacto_generado)
@ConfigurationProperties("lms.media.asciidoctor")
data class AsciidoctorProperties(
    val html: String = "asciidoctor",
    val pdf: String = "asciidoctor-pdf",
    val timeout: Duration = Duration.ofMinutes(2),
    val maxBytes: DataSize = DataSize.ofMegabytes(25),
) {
    init {
        require(html.isNotBlank() && pdf.isNotBlank()) { "lms.media.asciidoctor.html and .pdf must name an executable" }
        require(timeout > Duration.ZERO) { "lms.media.asciidoctor.timeout must be positive" }
        require(maxBytes.toBytes() > 0) { "lms.media.asciidoctor.max-bytes must be positive" }
    }
}

// el adaptador de DocumentRenderer (ADR-0018 §7): Asciidoctor como proceso externo, el único sitio del código que lanza
// uno (ArchitectureTest). la fuente no es de confianza (CLAUDE.md, regla 6), así que:
// - modo seguro (--safe-mode secure): sin include:: ni lectura de archivos fuera de su carpeta, sin docinfo y sin
//   allow-uri-read, que solo se activa desde la línea de órdenes: el documento no lee nada de la red
// - una carpeta temporal propia por generación, que es su base y su HOME, y que se borra al terminar
// - sin las variables de entorno del servidor (credenciales): solo las que Ruby necesita para encontrarse
// - tiempo límite: pasado, el proceso se mata. la cola no puede cortar lo que bloquea, así que lo corta este adaptador
// - lo que produce, con tope de tamaño
// sin el ejecutable, la generación falla con un error claro: la imagen de despliegue lo trae (F6), Docker dentro del
// servidor no (ADR-0018, alternativas)
@Component
class AsciidoctorRenderer(
    private val properties: AsciidoctorProperties,
) : DocumentRenderer {
    // la versión de cada ejecutable, leída la primera vez que se usa
    private val versions = ConcurrentHashMap<String, String>()

    override fun render(
        source: ByteArray,
        format: ArtifactFormat,
    ): RenderedDocument {
        val command = commandFor(format)
        val version = versions[command] ?: version(command).also { versions[command] = it }
        val dir = Files.createTempDirectory(TEMP_PREFIX)
        try {
            val input = dir.resolve(INPUT)
            Files.write(input, source)
            val output = dir.resolve("$OUTPUT.${format.extension}")
            run(arguments(command, dir, input, output), dir, properties.timeout)
            if (!Files.isRegularFile(output) || Files.size(output) == 0L) throw RenderException("$command produced no ${format.name} output")
            val size = Files.size(output)
            if (size > properties.maxBytes.toBytes()) {
                throw RenderException("$command produced $size bytes of ${format.name}, more than the ${properties.maxBytes.toBytes()} allowed")
            }
            return RenderedDocument(format, Files.readAllBytes(output), File(command).name, version)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    internal fun commandFor(format: ArtifactFormat): String =
        when (format) {
            ArtifactFormat.HTML -> properties.html
            ArtifactFormat.PDF -> properties.pdf
        }

    // la primera línea de --version, p. ej. «Asciidoctor PDF 2.3.19 using Asciidoctor 2.0.23 [https://asciidoctor.org]»
    private fun version(command: String): String {
        val dir = Files.createTempDirectory(TEMP_PREFIX)
        try {
            val output = run(listOf(command, "--version"), dir, VERSION_TIMEOUT)
            val first = output.lines().firstOrNull { it.isNotBlank() } ?: return "unknown"
            return first.trim().take(MAX_VERSION)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // lo que el proceso escribió (salida y errores juntos), si terminó bien
    private fun run(
        arguments: List<String>,
        dir: Path,
        timeout: Duration,
    ): String {
        val command = arguments.first()
        val log = dir.resolve(LOG).toFile()
        val builder =
            ProcessBuilder(arguments)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log)
                .redirectInput(ProcessBuilder.Redirect.from(NULL_FILE))
        val environment = builder.environment()
        environment.keys.filter { it !in PASSED_ENVIRONMENT }.forEach { environment.remove(it) }
        environment["HOME"] = dir.toString()
        environment.putIfAbsent("LANG", "C.UTF-8")
        val process =
            try {
                builder.start()
            } catch (e: IOException) {
                throw RendererUnavailableException(
                    "$command is not available on this server (${e.message}): the deployment image must include Asciidoctor (ADR-0018 §7)",
                    e,
                )
            }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                throw RenderException("$command timed out after $timeout")
            }
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw RenderException("$command was interrupted", e, permanent = false)
        }
        val output = if (log.isFile) log.readText().takeLast(MAX_LOG) else ""
        if (process.exitValue() != 0) throw RenderException("$command failed with exit code ${process.exitValue()}: ${output.trim()}")
        return output
    }

    companion object {
        private const val TEMP_PREFIX = "lms-render-"
        private const val INPUT = "source.adoc"
        private const val OUTPUT = "document"
        private const val LOG = "render.log"
        private const val MAX_LOG = 1000
        private const val MAX_VERSION = 200
        private val VERSION_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val NULL_FILE = File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")

        // lo justo para que el intérprete de Ruby y sus gemas se encuentren
        private val PASSED_ENVIRONMENT = setOf("PATH", "LANG", "LC_ALL", "GEM_HOME", "GEM_PATH")

        // la línea de órdenes: modo seguro, la carpeta temporal como base y salida a un archivo conocido
        internal fun arguments(
            command: String,
            dir: Path,
            input: Path,
            output: Path,
        ): List<String> = listOf(command, "--safe-mode", "secure", "--base-dir", dir.toString(), "--out-file", output.toString(), input.toString())
    }
}
