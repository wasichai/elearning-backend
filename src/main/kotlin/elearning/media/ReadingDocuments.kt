package elearning.media

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI
import java.text.Normalizer

// el HTML de una lectura como documento de bloques (ADR-0045). el texto del documento es dato no confiable (regla 6): pasa
// antes por GeneratedHtml, aunque ya se saneó al guardarse, y de lo que queda solo sale el vocabulario cerrado de
// ReadingDocument. nada se copia tal cual salvo el texto: ni etiquetas, ni clases, ni estilos, ni atributos del autor.
// - lo que no es del vocabulario desaparece y su texto queda como párrafo; script, estilos, marcos, objetos, SVG y
//   formularios desaparecen con su texto
// - el h1 es el título; h5 y h6 se aplanan a 4; los id, saneados a [A-Za-z0-9_-] y únicos. un encabezado sin id (el HTML
//   subido a mano) lo saca de su texto, como Asciidoctor: _ y el texto en minúsculas, sin acentos, con - entre palabras
// - enlaces solo http, https, mailto y #ancla; cualquier otro, texto
// - imágenes solo data:image/(png|jpeg|gif|webp) en base64; cualquier otra, su alt como párrafo
// - el índice (#toc) y el pie (#footer) de Asciidoctor son envoltorio: la interfaz arma su índice con los encabezados
// - un anidamiento más hondo que MAX_DEPTH se aplana a su texto: la recursión y el JSON tienen tope. todo bloque que
//   contiene bloques suma un nivel, así que ningún bloque queda a más de MAX_DEPTH niveles
// jsoup es CPU puro: quien llama lo ejecuta fuera del hilo de WebFlux (ReadingService)
object ReadingDocuments {
    fun read(html: ByteArray): ReadingDocument = convert(String(GeneratedHtml.sanitize(html), Charsets.UTF_8))

    // sin GeneratedHtml: solo para probar la conversión con lo que el saneado ya quitaría
    internal fun convert(html: String): ReadingDocument = Converter().document(Jsoup.parse(html).body())

    private class Converter {
        private val ids = mutableSetOf<String>()
        private var title: String? = null

        fun document(body: Element): ReadingDocument {
            val blocks = blocks(body, 0)
            return ReadingDocument(title, blocks)
        }

        // los bloques de un contenedor: el texto suelto y lo que va en línea entre bloques forman párrafos
        private fun blocks(
            container: Element,
            depth: Int,
        ): List<Block> {
            if (depth > MAX_DEPTH) return listOfNotNull(paragraph(listOf(Text(container.text()))))
            val out = mutableListOf<Block>()
            val loose = mutableListOf<Inline>()

            fun flush() {
                paragraph(loose)?.let(out::add)
                loose.clear()
            }
            for (node in container.childNodes()) {
                when {
                    node is TextNode -> loose += Text(node.wholeText)
                    node !is Element -> Unit
                    node.normalName() in INLINE -> loose += inline(node, 0)
                    else -> {
                        flush()
                        out += block(node, depth + 1)
                    }
                }
            }
            flush()
            return out
        }

        private fun block(
            element: Element,
            depth: Int,
        ): List<Block> {
            // un camino que no pase por blocks (una lista dentro de otra sin li) también tiene tope
            if (depth > MAX_DEPTH) return listOfNotNull(paragraph(listOf(Text(element.text()))))
            val tag = element.normalName()
            return when {
                tag in DROPPED -> emptyList()
                tag == "div" && element.id() in CHROME -> emptyList()
                tag == "h1" && title == null -> {
                    title = collapse(element.text()).trim().takeIf { it.isNotEmpty() }
                    emptyList()
                }
                tag == "h1" -> heading(element, 2)
                tag in HEADINGS -> heading(element, HEADINGS.getValue(tag))
                tag == "p" -> listOfNotNull(paragraph(inlines(element, 0)))
                tag == "ul" || tag == "ol" -> list(element, depth)
                tag == "table" -> table(element, depth)
                tag == "pre" -> listOf(code(element))
                tag == "blockquote" -> listOf(Quote(blocks(element, depth + 1), null))
                tag == "hr" -> listOf(Rule)
                tag == "img" -> figure(element, null)
                tag == "figure" -> imageBlock(element, element.children().firstOrNull { it.normalName() == "figcaption" }, depth)
                tag == "div" && element.hasClass("admonitionblock") -> admonition(element, depth)
                tag == "div" && element.hasClass("quoteblock") -> quote(element, depth)
                tag == "div" && element.hasClass("imageblock") -> imageBlock(element, element.children().firstOrNull { it.hasClass("title") }, depth)
                else -> blocks(element, depth)
            }
        }

        private fun heading(
            element: Element,
            level: Int,
        ): List<Block> {
            val content = trimmed(inlines(element, 0))
            if (!meaningful(content)) return emptyList()
            val id = cleanId(element.id()) ?: slug(element.text())
            return listOf(Heading(level, id?.let(::unique), content))
        }

        // cada hijo es un elemento: los li, y lo que el analizador haya dejado suelto, para no perder su texto
        private fun list(
            element: Element,
            depth: Int,
        ): List<Block> {
            val items = element.children().map { if (it.normalName() == "li") blocks(it, depth + 1) else block(it, depth + 1) }.filter { it.isNotEmpty() }
            return if (items.isEmpty()) emptyList() else listOf(ListBlock(element.normalName() == "ol", items))
        }

        private fun table(
            element: Element,
            depth: Int,
        ): List<Block> {
            val caption = element.children().firstOrNull { it.normalName() == "caption" }?.let { optional(inlines(it, 0)) }
            val head = mutableListOf<List<Cell>>()
            val body = mutableListOf<List<Cell>>()
            for (section in element.children()) {
                when (section.normalName()) {
                    "thead" -> section.children().forEach { row(it, depth)?.let(head::add) }
                    "tbody", "tfoot" -> section.children().forEach { row(it, depth)?.let(body::add) }
                    "tr" -> row(section, depth)?.let(body::add)
                }
            }
            return if (head.isEmpty() && body.isEmpty()) emptyList() else listOf(Table(caption, head, body))
        }

        private fun row(
            element: Element,
            depth: Int,
        ): List<Cell>? {
            if (element.normalName() != "tr") return null
            val cells =
                element
                    .children()
                    .filter { it.normalName() == "td" || it.normalName() == "th" }
                    .map { Cell(blocks(it, depth + 1), span(it.attr("colspan")), span(it.attr("rowspan")), it.normalName() == "th") }
            return cells.ifEmpty { null }
        }

        // el texto tal cual, sin normalizar espacios. el lenguaje, el data-lang de Asciidoctor; GeneratedHtml no conserva
        // data-*, así que de lo guardado sale de su clase language-<nombre>. solo si es un nombre
        private fun code(element: Element): Block {
            val code = element.children().firstOrNull { it.normalName() == "code" }
            val language =
                code?.attr("data-lang")?.takeIf { it.isNotEmpty() }
                    ?: code?.classNames()?.firstOrNull { it.startsWith(LANGUAGE_CLASS) }?.removePrefix(LANGUAGE_CLASS)
            return Code(language?.takeIf { LANGUAGE.matches(it) }, element.wholeText())
        }

        private fun admonition(
            element: Element,
            depth: Int,
        ): List<Block> {
            val kind = element.classNames().firstOrNull { it in ADMONITIONS } ?: return blocks(element, depth)
            val content = element.selectFirst("> table > tbody > tr > td.content") ?: return blocks(element, depth)
            return listOf(Admonition(kind, blocks(content, depth + 1)))
        }

        private fun quote(
            element: Element,
            depth: Int,
        ): List<Block> {
            val quoted = element.children().firstOrNull { it.normalName() == "blockquote" } ?: return blocks(element, depth)
            val attribution =
                element.children().firstOrNull { it.hasClass("attribution") }?.let { attribution ->
                    val content = trimmed(inlines(attribution, 0))
                    val first = content.firstOrNull()
                    optional(if (first is Text) listOf(Text(first.text.trimStart('—', ' '))) + content.drop(1) else content)
                }
            return listOf(Quote(blocks(quoted, depth + 1), attribution))
        }

        // un bloque de imagen de Asciidoctor o un figure: la imagen con su leyenda; sin imagen, su contenido
        private fun imageBlock(
            element: Element,
            caption: Element?,
            depth: Int,
        ): List<Block> {
            val image = element.selectFirst("img") ?: return blocks(element, depth)
            return figure(image, caption)
        }

        private fun figure(
            image: Element,
            caption: Element?,
        ): List<Block> {
            val alt = collapse(image.attr("alt")).trim()
            val inlineCaption = caption?.let { optional(inlines(it, 0)) }
            val src = image.attr("src")
            if (DATA_IMAGE.matches(src)) return listOf(Figure(src, alt, inlineCaption))
            return listOfNotNull(paragraph(listOf(Text(alt))), inlineCaption?.let { paragraph(it) })
        }

        // lo que va en línea dentro de un elemento, con los espacios como los pinta HTML
        private fun inlines(
            element: Element,
            depth: Int,
        ): List<Inline> = normalize(element.childNodes().flatMap { inlineNode(it, depth) })

        private fun inlineNode(
            node: Node,
            depth: Int,
        ): List<Inline> =
            when (node) {
                is TextNode -> listOf(Text(node.wholeText))
                is Element -> inline(node, depth)
                else -> emptyList()
            }

        private fun inline(
            element: Element,
            depth: Int,
        ): List<Inline> {
            if (depth > MAX_DEPTH) return listOf(Text(element.text()))
            return when (element.normalName()) {
                in DROPPED -> emptyList()
                "br" -> listOf(Break)
                "em", "i" -> marked(inlines(element, depth + 1), ::Emphasis)
                "strong", "b" -> marked(inlines(element, depth + 1), ::Strong)
                "code", "kbd", "samp", "tt" -> collapse(element.text()).takeIf { it.isNotBlank() }?.let { listOf(InlineCode(it)) }.orEmpty()
                "a" -> link(element, depth)
                "img" ->
                    collapse(element.attr("alt"))
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { listOf(Text(it)) }
                        .orEmpty()
                else -> element.childNodes().flatMap { inlineNode(it, depth + 1) }
            }
        }

        private fun link(
            element: Element,
            depth: Int,
        ): List<Inline> {
            val content = inlines(element, depth + 1)
            if (!meaningful(content)) return emptyList()
            val href = href(element.attr("href")) ?: return content
            return listOf(Link(href, content))
        }

        private fun marked(
            content: List<Inline>,
            mark: (List<Inline>) -> Inline,
        ): List<Inline> = if (meaningful(content)) listOf(mark(content)) else content

        private fun paragraph(content: List<Inline>): Paragraph? {
            val clean = trimmed(normalize(content))
            return if (meaningful(clean)) Paragraph(clean) else null
        }

        private fun optional(content: List<Inline>): List<Inline>? = trimmed(content).takeIf { meaningful(it) }

        private fun unique(id: String): String {
            var candidate = id
            var n = 2
            while (!ids.add(candidate)) candidate = "$id-${n++}"
            return candidate
        }
    }

    // espacios colapsados, textos contiguos unidos, sin espacio junto a un salto
    private fun normalize(content: List<Inline>): List<Inline> {
        val out = mutableListOf<Inline>()
        for (inline in content) {
            val previous = out.lastOrNull()
            when {
                inline is Text -> {
                    val text = collapse(inline.text)
                    when {
                        previous is Text -> out[out.lastIndex] = Text(joinSpaces(previous.text, text))
                        previous == null || previous == Break -> text.trimStart().takeIf { it.isNotEmpty() }?.let { out += Text(it) }
                        text.isNotEmpty() -> out += Text(text)
                    }
                }
                inline == Break && previous is Text -> {
                    val text = previous.text.trimEnd()
                    if (text.isEmpty()) out.removeAt(out.lastIndex) else out[out.lastIndex] = Text(text)
                    out += Break
                }
                else -> out += inline
            }
        }
        return out
    }

    private fun trimmed(content: List<Inline>): List<Inline> {
        val out = content.toMutableList()
        (out.firstOrNull() as? Text)?.let { first -> first.text.trimStart().let { if (it.isEmpty()) out.removeAt(0) else out[0] = Text(it) } }
        (out.lastOrNull() as? Text)?.let { last ->
            last.text.trimEnd().let { if (it.isEmpty()) out.removeAt(out.lastIndex) else out[out.lastIndex] = Text(it) }
        }
        return out
    }

    private fun meaningful(content: List<Inline>): Boolean = content.any { it != Break && !(it is Text && it.text.isBlank()) }

    private fun joinSpaces(
        left: String,
        right: String,
    ): String = if (left.endsWith(' ') && right.startsWith(' ')) left + right.drop(1) else left + right

    private fun collapse(text: String): String = WHITESPACE.replace(text, " ")

    private fun span(value: String): Int = value.trim().toIntOrNull()?.coerceIn(1, MAX_SPAN) ?: 1

    // http y https con servidor, mailto con destinatario, o un ancla saneada como los id; nada relativo ni de otro esquema
    private fun href(value: String): String? {
        val href = value.trim()
        if (href.startsWith("#")) return cleanId(href.drop(1))?.let { "#$it" }
        if (href.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = runCatching { URI(href) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> href.takeIf { !uri.host.isNullOrEmpty() }
            "mailto" -> href.takeIf { !uri.schemeSpecificPart.isNullOrBlank() }
            else -> null
        }
    }

    // estable: el mismo texto da el mismo id, así un enlace a #_<slug> sobrevive a otra subida del archivo
    private fun slug(text: String): String? =
        NON_SLUG
            .replace(MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), ""), "-")
            .trim('-')
            .take(MAX_ID - 1)
            .trimEnd('-')
            .ifEmpty { null }
            ?.let { "_$it" }

    private fun cleanId(value: String): String? =
        value
            .filter {
                it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-'
            }.take(MAX_ID)
            .ifEmpty { null }

    internal const val MAX_DEPTH = 48
    private const val MAX_ID = 100

    // el colspan máximo de HTML
    private const val MAX_SPAN = 1000
    private val MARKS = Regex("\\p{M}+")
    private val NON_SLUG = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("[ \\t\\n\\r\\u000C]+")
    private val DATA_IMAGE = Regex("data:image/(png|jpeg|gif|webp);base64,[A-Za-z0-9+/]+={0,2}")
    private const val LANGUAGE_CLASS = "language-"
    private val LANGUAGE = Regex("[A-Za-z0-9_+#.-]{1,40}")
    private val ADMONITIONS = setOf("note", "tip", "important", "warning", "caution")
    private val HEADINGS = mapOf("h2" to 2, "h3" to 3, "h4" to 4, "h5" to 4, "h6" to 4)
    private val CHROME = setOf("toc", "footer")
    private val DROPPED =
        setOf(
            "script",
            "style",
            "template",
            "noscript",
            "iframe",
            "frame",
            "frameset",
            "object",
            "embed",
            "applet",
            "svg",
            "math",
            "canvas",
            "audio",
            "video",
            "form",
            "input",
            "button",
            "select",
            "option",
            "textarea",
            "head",
            "title",
            "meta",
            "link",
            "base",
        )
    private val INLINE =
        setOf(
            "a",
            "abbr",
            "b",
            "bdi",
            "bdo",
            "br",
            "cite",
            "code",
            "data",
            "del",
            "dfn",
            "em",
            "i",
            "ins",
            "kbd",
            "mark",
            "q",
            "s",
            "samp",
            "small",
            "span",
            "strong",
            "sub",
            "sup",
            "time",
            "tt",
            "u",
            "var",
            "wbr",
            "label",
            "font",
        )
}
