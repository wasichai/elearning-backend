package elearning.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

// los límites de CLAUDE.md (reglas 1, 2, 4 y 7), de docs/architecture/overview.md §2.3 y de RNF-ARQ-08 sobre
// src/main/kotlin. Que cada regla de verdad detecta lo que dice lo prueba SourceScannerTest, con el mismo escáner y la
// misma tabla
class ArchitectureTest {
    private val root = File("src/main/kotlin")
    private val violations by lazy { SourceScanner(ALLOWED_DEPENDENCIES).scan(readSources(root)) }

    private fun assertNoViolations(rule: Rule) {
        assertThat(violations.filter { it.rule == rule }.map { it.toString() })
            .describedAs(rule.description)
            .isEmpty()
    }

    @Test
    fun `the scan finds the sources`() {
        assertThat(readSources(root).map { File(it.path).invariantSeparatorsPath })
            .contains("src/main/kotlin/elearning/ElearningApplication.kt", "src/main/kotlin/elearning/workload/Workload.kt")
    }

    @Test
    fun `nothing lives under the wasichai package`() = assertNoViolations(Rule.WASICHAI_PACKAGE)

    @Test
    fun `no jpa, hibernate, spring data repositories nor blocking jdbc`() = assertNoViolations(Rule.BLOCKING_PERSISTENCE)

    @Test
    fun `no internal classes of wasichai`() = assertNoViolations(Rule.WASICHAI_INTERNALS)

    @Test
    fun `no role names in code`() = assertNoViolations(Rule.ROLE_NAME_LITERAL)

    @Test
    fun `workload is pure domain`() = assertNoViolations(Rule.PURE_WORKLOAD)

    @Test
    fun `no concrete ai, storage, videoconference or source provider outside its adapter`() = assertNoViolations(Rule.CONCRETE_PROVIDER)

    @Test
    fun `no external process outside the renderer adapter`() = assertNoViolations(Rule.EXTERNAL_PROCESS)

    @Test
    fun `every capability sits in the dag and uses only what it may`() = assertNoViolations(Rule.PACKAGE_DAG)

    // la tabla también se vigila: una línea nueva no puede colar un ciclo, dar dependencias a workload ni hacer que
    // alguien use catalog (el catálogo solo lee; lo junta la aplicación)
    @Test
    fun `the table of allowed dependencies is a dag where workload uses nothing and nobody uses catalog`() {
        assertThat(ALLOWED_DEPENDENCIES.getValue("workload")).isEmpty()
        assertThat(ALLOWED_DEPENDENCIES.filterValues { "catalog" in it }.keys).isEmpty()
        assertThat(ALLOWED_DEPENDENCIES.values.flatten()).allSatisfy { assertThat(ALLOWED_DEPENDENCIES).containsKey(it) }
        assertThat(cycleIn(ALLOWED_DEPENDENCIES)).isNull()
    }

    companion object {
        // capacidad → capacidades que puede usar: las flechas de docs/architecture/overview.md §2.3, más common para
        // todas salvo workload. Una capacidad nueva entra aquí (y en el diagrama) o ArchitectureTest falla
        val ALLOWED_DEPENDENCIES: Map<String, Set<String>> =
            mapOf(
                "common" to emptySet(),
                "workload" to emptySet(),
                // la cola persistente: no sabe de ninguna capacidad; quien encola y quien maneja la usa a ella
                "jobs" to setOf("common"),
                "course" to setOf("common", "workload"),
                "catalog" to setOf("common", "course", "workload"),
                "enrollment" to setOf("common", "course"),
                "learning" to setOf("common", "enrollment", "course", "workload"),
                "assessment" to setOf("common", "learning", "enrollment"),
                "authoring" to setOf("common", "course"),
                "importer" to setOf("common", "jobs"),
                "ai" to setOf("common", "authoring"),
                // la generación encola y maneja sus trabajos en la cola persistente (ADR-0018 §7)
                "media" to setOf("common", "authoring", "jobs"),
                "reputation" to setOf("common", "enrollment"),
            )

        // un ciclo como lista de nodos, o null si no hay
        fun cycleIn(graph: Map<String, Set<String>>): List<String>? {
            val done = mutableSetOf<String>()

            fun visit(
                node: String,
                path: List<String>,
            ): List<String>? {
                if (node in path) return path.dropWhile { it != node } + node
                if (!done.add(node)) return null
                return graph[node].orEmpty().firstNotNullOfOrNull { visit(it, path + node) }
            }
            return graph.keys.firstNotNullOfOrNull { visit(it, emptyList()) }
        }
    }
}
