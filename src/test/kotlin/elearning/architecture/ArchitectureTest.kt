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
    fun `no embabel outside elearning ai`() = assertNoViolations(Rule.EMBABEL)

    @Test
    fun `every capability sits in the dag and uses only what it may`() = assertNoViolations(Rule.PACKAGE_DAG)

    // RF-MED-04 (#294): lo publicado funciona sin el proveedor. Nada de lo que sirve al estudiante (el aula, sus
    // evaluaciones, la matrícula, el catálogo y las valoraciones) usa elearning.ai, ni directamente ni a través de otra
    // capacidad: la tabla no deja llegar y el código no la nombra. Que tampoco llegue por un bean (una interfaz de common
    // que implementara ai) lo prueba ClassroomWithoutAiApiTest con el grafo de beans de la aplicación sin IA
    @Test
    fun `nothing that serves the student depends on elearning ai, not even through another capability`() {
        assertThat(ALLOWED_DEPENDENCIES.keys).containsAll(CLASSROOM)
        assertThat(CLASSROOM.associateWith { reachableFrom(ALLOWED_DEPENDENCIES, it) }.filterValues { AI in it }.keys)
            .describedAs("capacidades del aula que alcanzan elearning.$AI en la tabla")
            .isEmpty()
        val reference = Regex("""\belearning\.$AI\b""")
        val offenders =
            readSources(root)
                .filter { source -> CLASSROOM.any { File(source.path).invariantSeparatorsPath.contains("/elearning/$it/") } }
                .filter { reference.containsMatchIn(withoutComments(it.text)) }
                .map { it.path }
        assertThat(offenders).describedAs("fuentes del aula que nombran elearning.$AI").isEmpty()
    }

    // RF-IA-06 (#284): ningún paso da herramientas al modelo ni conecta un servidor MCP. Cada llamada es una createObject sin
    // herramientas (ModelCalls); que la petición no las lleve y que el contexto no tenga ninguna lo prueba
    // PromptInjectionProviderApiTest
    @Test
    fun `no step hands tools to the model nor connects an mcp server`() {
        val tools =
            Regex("""\b(withToolGroups?|withTools?|withToolObjects?|withToolCallbacks?|ToolCallback\w*|McpSyncClient|McpAsyncClient)\b|@(LlmTool|Tool)\b""")
        val offenders =
            readSources(root)
                .filter { tools.containsMatchIn(withoutComments(it.text)) }
                .map { "${it.path}: ${tools.findAll(withoutComments(it.text)).map { m -> m.value }.distinct().toList()}" }
        assertThat(offenders).describedAs("fuentes que dan herramientas al modelo").isEmpty()
    }

    // la tabla también se vigila: una línea nueva no puede colar un ciclo, dar dependencias a workload ni hacer que
    // alguien use catalog (el catálogo solo lee; lo junta la aplicación) o reputation (las valoraciones no tocan la
    // matrícula ni las notas, ADR-0038)
    @Test
    fun `the table of allowed dependencies is a dag where workload uses nothing and nobody uses catalog nor reputation`() {
        assertThat(ALLOWED_DEPENDENCIES.getValue("workload")).isEmpty()
        assertThat(ALLOWED_DEPENDENCIES.filterValues { "catalog" in it }.keys).isEmpty()
        assertThat(ALLOWED_DEPENDENCIES.filterValues { "reputation" in it }.keys).isEmpty()
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
                // los términos de uso y la supresión de datos personales (ADR-0037): borra y disocia por SQL y por la API
                // de registros, sin usar ninguna capacidad; la matrícula y el progreso le preguntan si hay aceptación
                "privacy" to setOf("common", "jobs"),
                "course" to setOf("common", "workload"),
                "catalog" to setOf("common", "course", "workload"),
                "enrollment" to setOf("common", "course", "privacy"),
                // el aviso de publicación de contenido sale de la cola persistente (RF-AUL-09)
                "learning" to setOf("common", "enrollment", "course", "workload", "jobs", "privacy"),
                // el barrido de intentos vencidos corre en la cola persistente (L4 de #183)
                "assessment" to setOf("common", "learning", "enrollment", "jobs"),
                "authoring" to setOf("common", "course"),
                // la aprobación de créditos lee las decisiones de la bandeja para la separación de funciones (ADR-0035)
                "credits" to setOf("common", "course", "workload", "authoring"),
                "importer" to setOf("common", "jobs"),
                // el brief (E33.1) se escribe con las guardas de course y sus fuentes son las permitidas del importador; los
                // pasos son trabajos de la cola persistente y vuelven a mirar los permisos de quien los pidió (ADR-0033 §5); las
                // horas y los créditos del diseño pedagógico los calcula workload, no el modelo (E33.5)
                // E33.6: el borrador guarda el HTML de cada lección con el generador y el saneado de media (ADR-0022)
                "ai" to setOf("common", "authoring", "course", "importer", "jobs", "media", "workload"),
                // la generación encola y maneja sus trabajos en la cola persistente (ADR-0018 §7)
                "media" to setOf("common", "authoring", "jobs"),
                // las valoraciones (ADR-0038): leen la matrícula y el progreso del aula y sus agregados corren en la cola. nadie
                // las usa: ni la matrícula ni las notas dependen de ellas (regla 8)
                "reputation" to setOf("common", "enrollment", "learning", "jobs"),
            )

        // lo que sirve al estudiante (RF-MED-04): no debe alcanzar AI
        val CLASSROOM = setOf("learning", "assessment", "enrollment", "catalog", "reputation")
        const val AI = "ai"

        // las capacidades que node usa, directa o indirectamente
        fun reachableFrom(
            graph: Map<String, Set<String>>,
            node: String,
        ): Set<String> {
            val seen = mutableSetOf<String>()
            val pending = ArrayDeque(graph[node].orEmpty())
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                if (seen.add(next)) pending.addAll(graph[next].orEmpty())
            }
            return seen
        }

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
