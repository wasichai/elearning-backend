package elearning.media

import org.jsoup.Jsoup
import org.jsoup.nodes.DocumentType
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

// el HTML generado se sanea con lista blanca antes de guardarse (docs/security/README.md §5.2, ADR-0022, L3 de #216). en modo
// secure, Asciidoctor deja pasar el HTML del autor (++++, pass:[]): script, manejadores de eventos, iframe, formularios.
// del cuerpo queda lo de la lista, con sus clases y anclas internas, y URL http, https o mailto; de la cabecera, el título y
// el estilo que pone Asciidoctor (lo que escribe el autor nunca llega a la cabecera), sin hojas externas ni scripts. el
// aislamiento de §4.4 sigue: esto es una capa más
object GeneratedHtml {
    private val SAFELIST: Safelist =
        Safelist
            .relaxed()
            .addTags(
                "abbr",
                "article",
                "aside",
                "del",
                "details",
                "figcaption",
                "figure",
                "footer",
                "header",
                "hr",
                "ins",
                "kbd",
                "main",
                "mark",
                "nav",
                "s",
                "samp",
                "section",
                "summary",
                "var",
            ).addAttributes(":all", "class", "id", "title", "lang", "dir")
            .addProtocols("a", "href", "#")

    fun sanitize(html: ByteArray): ByteArray {
        val dirty = Jsoup.parse(String(html, Charsets.UTF_8))
        val clean = Cleaner(SAFELIST).clean(dirty)
        clean.prependChild(DocumentType("html", "", ""))
        val head = clean.head()
        head.appendElement("meta").attr("charset", "utf-8")
        head.appendElement("title").text(dirty.title())
        dirty.head().select("style").forEach { head.appendChild(it.clone()) }
        return clean.outerHtml().toByteArray(Charsets.UTF_8)
    }

    fun sanitize(document: RenderedDocument): RenderedDocument =
        if (document.format == ArtifactFormat.HTML) {
            RenderedDocument(document.format, sanitize(document.bytes), document.generator, document.generatorVersion)
        } else {
            document
        }
}
