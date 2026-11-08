package elearning.media

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

// la lección de lectura como documento de bloques (ADR-0045): un vocabulario cerrado que la interfaz pinta con sus propios
// componentes. nada de HTML, clases ni estilos del autor: solo texto y estos tipos. el contrato está en docs/api/lms.md
data class ReadingDocument(
    val title: String?,
    val blocks: List<Block>,
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(Heading::class, name = "heading"),
    JsonSubTypes.Type(Paragraph::class, name = "paragraph"),
    JsonSubTypes.Type(ListBlock::class, name = "list"),
    JsonSubTypes.Type(Table::class, name = "table"),
    JsonSubTypes.Type(Code::class, name = "code"),
    JsonSubTypes.Type(Quote::class, name = "quote"),
    JsonSubTypes.Type(Admonition::class, name = "admonition"),
    JsonSubTypes.Type(Figure::class, name = "figure"),
    JsonSubTypes.Type(Rule::class, name = "rule"),
)
sealed interface Block

// level 2..4; id, el ancla de Asciidoctor saneada a [A-Za-z0-9_-] y única en el documento
data class Heading(
    val level: Int,
    val id: String?,
    val content: List<Inline>,
) : Block

data class Paragraph(
    val content: List<Inline>,
) : Block

// cada elemento, sus bloques
data class ListBlock(
    val ordered: Boolean,
    val items: List<List<Block>>,
) : Block

data class Table(
    val caption: List<Inline>?,
    val head: List<List<Cell>>,
    val body: List<List<Cell>>,
) : Block

data class Cell(
    val content: List<Block>,
    val colspan: Int = 1,
    val rowspan: Int = 1,
    val header: Boolean,
)

data class Code(
    val language: String?,
    val text: String,
) : Block

data class Quote(
    val content: List<Block>,
    val attribution: List<Inline>?,
) : Block

// kind: note | tip | important | warning | caution
data class Admonition(
    val kind: String,
    val content: List<Block>,
) : Block

// src, solo data:image/(png|jpeg|gif|webp) en base64: nunca una URL que el navegador fuera a pedir
data class Figure(
    val src: String,
    val alt: String,
    val caption: List<Inline>?,
) : Block

data object Rule : Block

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(Text::class, name = "text"),
    JsonSubTypes.Type(Emphasis::class, name = "em"),
    JsonSubTypes.Type(Strong::class, name = "strong"),
    JsonSubTypes.Type(InlineCode::class, name = "code"),
    JsonSubTypes.Type(Link::class, name = "link"),
    JsonSubTypes.Type(Break::class, name = "break"),
)
sealed interface Inline

data class Text(
    val text: String,
) : Inline

data class Emphasis(
    val content: List<Inline>,
) : Inline

data class Strong(
    val content: List<Inline>,
) : Inline

data class InlineCode(
    val text: String,
) : Inline

// href: http, https, mailto o #ancla (con el ancla saneada como la de los encabezados)
data class Link(
    val href: String,
    val content: List<Inline>,
) : Inline

data object Break : Inline
