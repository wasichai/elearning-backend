package elearning.importer

// lo que el analizador del grafo include:: saca de una fuente AsciiDoc (RF-IMP-02, RF-IMP-04). Toda ruta es relativa a
// la raíz de la fuente, con '/', y cada elemento lleva su procedencia (ruta y línea) para la traza repo@sha:ruta#ancla
data class IncludeAnalysis(
    val master: String,
    // los archivos leídos, sin repetir, en el orden en que se encontraron
    val files: List<String>,
    val includes: List<IncludeEdge>,
    // las secciones en orden de documento, con su nivel ya desplazado por leveloffset
    val sections: List<Section>,
    val anchors: List<Anchor>,
    val images: List<ImageReference>,
    val references: List<CrossReference>,
    val defects: List<Defect>,
)

// línea 1 es la primera; 0 señala el archivo entero
data class Location(
    val path: String,
    val line: Int,
)

// un include:: seguido: dónde está la directiva, a qué archivo lleva y el leveloffset que rige dentro de él
data class IncludeEdge(
    val from: Location,
    val target: String,
    val levelOffset: Int,
)

// id es el ancla explícita o la generada como Asciidoctor; null solo para el título del documento sin ancla
data class Section(
    val location: Location,
    val level: Int,
    val title: String,
    val id: String?,
)

data class Anchor(
    val id: String,
    val location: Location,
)

// path es null cuando la imagen es remota o sale de la raíz: entonces no se comprueba ni se copia
data class ImageReference(
    val location: Location,
    val target: String,
    val path: String?,
    val alt: String,
)

// target tal como está escrito: «id», «archivo.adoc#id» o «archivo.adoc»
data class CrossReference(
    val location: Location,
    val target: String,
)

data class Defect(
    val kind: DefectKind,
    val location: Location,
    val detail: String,
)

enum class DefectKind {
    MISSING_INCLUDE,
    INCLUDE_OUTSIDE_ROOT,
    INCLUDE_CYCLE,
    INCLUDE_TOO_DEEP,
    UNRESOLVED_ATTRIBUTE,
    UNSUPPORTED_INCLUDE_OPTION,
    MISSING_IMAGE,
    IMAGE_OUTSIDE_ROOT,
    BROKEN_REFERENCE,
    DUPLICATE_ANCHOR,
}
