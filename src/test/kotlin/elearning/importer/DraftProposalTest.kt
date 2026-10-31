package elearning.importer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// la propuesta de estructura (ADR-0010 etapa 2) sobre un análisis armado a mano
class DraftProposalTest {
    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val root = SourceRef("acme", "manuales", sha, "manual.adoc")

    private fun section(
        level: Int,
        title: String,
        id: String? = "_${title.lowercase()}",
        path: String = "cap.adoc",
    ) = Section(Location(path, 1), level, title, id)

    private fun analysis(vararg sections: Section) =
        IncludeAnalysis("manual.adoc", listOf("manual.adoc"), emptyList(), sections.toList(), emptyList(), emptyList(), emptyList(), emptyList())

    @Test
    fun `chapters are units and their sections lessons, each lesson with its trace`() {
        val units =
            DraftProposal.of(
                root,
                analysis(
                    section(0, "Manual", id = null, path = "manual.adoc"),
                    section(1, "Instalar"),
                    section(2, "Requisitos"),
                    section(3, "Detalle"),
                    section(2, "Pasos"),
                    section(1, "Configurar", id = "configurar"),
                ),
            )

        assertThat(units.map { it.title }).containsExactly("Instalar", "Configurar")
        assertThat(units[0].lessons.map { it.title }).containsExactly("Requisitos", "Pasos")
        assertThat(units[0].lessons[0].trace).isEqualTo("acme/manuales@$sha:cap.adoc#_requisitos")
        // sin secciones dentro, una lección con el título y la traza del capítulo
        assertThat(units[1].lessons).containsExactly(ProposedLesson("Configurar", "acme/manuales@$sha:cap.adoc#configurar"))
    }

    @Test
    fun `a document with only its title is one unit, and sections before any chapter hang from the title`() {
        val titleOnly = DraftProposal.of(root, analysis(section(0, "Manual", id = null, path = "manual.adoc")))
        val lesson = titleOnly.single().lessons.single()
        assertThat(lesson.trace).isEqualTo("acme/manuales@$sha:manual.adoc")

        val early = DraftProposal.of(root, analysis(section(0, "Manual", id = null, path = "manual.adoc"), section(2, "Suelta")))
        assertThat(early.single().title).isEqualTo("Manual")
        assertThat(early.single().lessons.map { it.title }).containsExactly("Suelta")
        assertThat(DraftProposal.of(root, analysis())).isEmpty()
    }

    @Test
    fun `long titles are cut`() {
        val units = DraftProposal.of(root, analysis(section(1, "x".repeat(500))))

        assertThat(units.single().title).hasSize(DraftProposal.MAX_TITLE)
    }
}
