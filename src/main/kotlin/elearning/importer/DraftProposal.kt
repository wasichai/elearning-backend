package elearning.importer

// una lección propuesta: el título de su sección y su traza repo@sha:ruta#ancla. nada del cuerpo (ADR-0018 §6)
data class ProposedLesson(
    val title: String,
    val trace: String,
)

data class ProposedUnit(
    val title: String,
    val trace: String,
    val lessons: List<ProposedLesson>,
)

// la propuesta de estructura (ADR-0010 etapa 2), dominio puro: cada sección de nivel 1 (o una parte de nivel 0 que no
// es el título del documento) es una unidad, y cada sección de nivel 2 dentro de ella, una lección. una unidad sin
// secciones de nivel 2 lleva una lección con su propio título, para que su traza no se pierda. lo más profundo queda
// dentro de su lección. los títulos son texto del manual: dato, no instrucción, y se guardan tal cual, recortados
object DraftProposal {
    const val MAX_TITLE = 200

    fun of(
        root: SourceRef,
        analysis: IncludeAnalysis,
    ): List<ProposedUnit> {
        val documentTitle = analysis.sections.firstOrNull()?.takeIf { it.level == 0 && it.location.path == analysis.master }
        val units = mutableListOf<Pair<Section, MutableList<Section>>>()
        for (section in analysis.sections) {
            when {
                section === documentTitle -> Unit
                section.level <= 1 -> units += section to mutableListOf<Section>()
                section.level == 2 -> {
                    // una lección antes del primer capítulo cuelga del título del documento (o de la raíz)
                    if (units.isEmpty()) units += (documentTitle ?: Section(Location(analysis.master, 0), 1, root.path, null)) to mutableListOf<Section>()
                    units.last().second += section
                }
            }
        }
        if (units.isEmpty() && documentTitle != null) units += documentTitle to mutableListOf<Section>()
        return units.map { (unit, lessons) ->
            val proposed = lessons.ifEmpty { listOf(unit) }.map { ProposedLesson(title(it), trace(root, it)) }
            ProposedUnit(title(unit), trace(root, unit), proposed)
        }
    }

    fun trace(
        root: SourceRef,
        section: Section,
    ): String = origin(root, section.location.path) + (section.id?.let { "#$it" } ?: "")

    fun origin(
        root: SourceRef,
        path: String,
    ): String = "${root.repository}@${root.sha}:$path"

    private fun title(section: Section) = section.title.take(MAX_TITLE)
}
