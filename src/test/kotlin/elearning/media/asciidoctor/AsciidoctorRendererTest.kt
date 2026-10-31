package elearning.media.asciidoctor

import elearning.media.ArtifactFormat
import elearning.media.RenderException
import elearning.media.RendererUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Duration
import kotlin.time.measureTime

// el adaptador sin Asciidoctor: en CI no está instalado. Un guion de shell hace de ejecutable para probar la línea de
// órdenes, el entorno, el tiempo límite y los errores; la prueba con el Asciidoctor real es manual (docs/operations)
@DisabledOnOs(OS.WINDOWS)
class AsciidoctorRendererTest {
    @TempDir
    lateinit var bin: Path

    // un ejecutable falso: --version imprime su versión; si no, ejecuta body con $out (el archivo de salida) e $in (la fuente)
    private fun script(
        name: String,
        body: String,
    ): String {
        val file = bin.resolve(name).toFile()
        file.writeText(
            """
            #!/bin/sh
            if [ "${'$'}1" = "--version" ]; then echo "Fake $name 9.9.9"; exit 0; fi
            out=""; prev=""; in=""
            for a in "${'$'}@"; do
              if [ "${'$'}prev" = "--out-file" ]; then out="${'$'}a"; fi
              prev="${'$'}a"; in="${'$'}a"
            done
            $body
            """.trimIndent() + "\n",
        )
        file.setExecutable(true)
        return file.absolutePath
    }

    private fun renderer(
        html: String = "asciidoctor",
        pdf: String = "asciidoctor-pdf",
        timeout: Duration = Duration.ofSeconds(20),
    ) = AsciidoctorRenderer(AsciidoctorProperties(html = html, pdf = pdf, timeout = timeout))

    @Test
    fun `without the binary the render fails with a clear error`() {
        val missing = bin.resolve("no-such-asciidoctor").toString()

        val error = assertThrows<RendererUnavailableException> { renderer(html = missing).render("= Título".toByteArray(), ArtifactFormat.HTML) }

        assertThat(error.message).contains(missing).contains("not available").contains("deployment image must include Asciidoctor")
    }

    @Test
    fun `it runs in secure mode on a file of its own and keeps the generator and its version`() {
        val fake = script("fake-asciidoctor", """{ echo "ARGS $*"; echo "SOURCE"; cat "${'$'}in"; } > "${'$'}out"""")

        val rendered = renderer(html = fake).render("= Título\n\nTexto *propio*.".toByteArray(), ArtifactFormat.HTML)

        val text = String(rendered.bytes)
        assertThat(text).contains("--safe-mode secure").contains("--base-dir").contains("Texto *propio*.")
        assertThat(rendered.format).isEqualTo(ArtifactFormat.HTML)
        assertThat(rendered.generator).isEqualTo("fake-asciidoctor")
        assertThat(rendered.generatorVersion).isEqualTo("Fake fake-asciidoctor 9.9.9")
    }

    @Test
    fun `each format uses its own executable`() {
        val html = script("fake-html", """echo "<p>html</p>" > "${'$'}out"""")
        val pdf = script("fake-pdf", """printf '%%PDF-1.7 fake' > "${'$'}out"""")
        val renderer = renderer(html = html, pdf = pdf)

        assertThat(String(renderer.render("x".toByteArray(), ArtifactFormat.PDF).bytes)).startsWith("%PDF-")
        assertThat(String(renderer.render("x".toByteArray(), ArtifactFormat.HTML).bytes)).contains("<p>html</p>")
    }

    @Test
    fun `the process does not get the server environment`() {
        val fake = script("fake-env", """env > "${'$'}out"""")

        val lines = String(renderer(html = fake).render("x".toByteArray(), ArtifactFormat.HTML).bytes).lines()

        val names = lines.map { it.substringBefore('=') }.toSet()
        val leaked = System.getenv().keys - setOf("PATH", "LANG", "LC_ALL", "GEM_HOME", "GEM_PATH", "HOME", "PWD", "OLDPWD", "SHLVL", "_")
        assertThat(names).doesNotContainAnyElementsOf(leaked)
        assertThat(lines.first { it.startsWith("HOME=") }).contains("lms-render-")
    }

    @Test
    fun `a render that takes too long is killed`() {
        val fake = script("fake-slow", "sleep 30")

        val took =
            measureTime {
                val slow = renderer(html = fake, timeout = Duration.ofSeconds(1))
                val error = assertThrows<RenderException> { slow.render("x".toByteArray(), ArtifactFormat.HTML) }
                assertThat(error.message).contains("timed out")
            }

        assertThat(took.inWholeSeconds).isLessThan(15)
    }

    @Test
    fun `a failed render says why and a render without output fails`() {
        val failing = script("fake-failing", """echo "asciidoctor: FAILED: boom" >&2; exit 3""")
        val silent = script("fake-silent", "exit 0")

        val failed = assertThrows<RenderException> { renderer(html = failing).render("x".toByteArray(), ArtifactFormat.HTML) }
        val empty = assertThrows<RenderException> { renderer(html = silent).render("x".toByteArray(), ArtifactFormat.HTML) }

        assertThat(failed.message).contains("exit code 3").contains("boom")
        assertThat(empty.message).contains("no HTML output")
    }

    @Test
    fun `nothing is left in the temporary directory`() {
        val fake = script("fake-clean", """echo ok > "${'$'}out"; echo "${'$'}out" > "${'$'}HOME/where"""")
        val before = File(System.getProperty("java.io.tmpdir")).list { _, name -> name.startsWith("lms-render-") }.orEmpty().toSet()

        renderer(html = fake).render("x".toByteArray(), ArtifactFormat.HTML)

        val after = File(System.getProperty("java.io.tmpdir")).list { _, name -> name.startsWith("lms-render-") }.orEmpty().toSet()
        assertThat(after - before).isEmpty()
    }
}
