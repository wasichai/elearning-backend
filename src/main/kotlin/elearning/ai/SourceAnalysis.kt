package elearning.ai

import tools.jackson.databind.JsonNode

// el análisis de fuentes con procedencia (paso 2 de docs/ai §1, E33.4, RF-IA-02): de las secciones de las fuentes del brief,
// los conceptos y procedimientos que enseñan, cada uno ligado a la sección de la que sale (owner/repo@sha:ruta#ancla) y a
// una cita literal de ella. Es el patrón de paso tipado que siguen los demás (docs/ai §5): una interfaz pequeña con dos
// implementaciones (ModelSourceAnalyzer con Embabel y DeterministicSourceAnalyzer, el doble de §7), una comprobación por
// código igual para las dos (SourceAnalysisCheck) y un JobHandler sobre AiStepRunner (SourceAnalysisJobHandler)

// la salida tipada del paso: lo que el modelo devuelve y lo que queda, ya comprobado, como salida de la ejecución. source y
// quote son opcionales en el tipo a propósito: un elemento sin procedencia se descarta por código, no invalida todo
data class SourceAnalysis(
    val concepts: List<SourceConcept>,
    val procedures: List<SourceProcedure>,
)

data class SourceConcept(
    val name: String,
    val summary: String,
    // owner/repo@sha:ruta#ancla de la sección
    val source: String? = null,
    // un fragmento literal de esa sección
    val quote: String? = null,
)

data class SourceProcedure(
    val name: String,
    val steps: List<String>,
    val source: String? = null,
    val quote: String? = null,
)

// una sección con ancla de una fuente leída: su referencia owner/repo@sha:ruta#ancla, su título y su texto (dato no
// confiable: va delimitado en el mensaje de usuario, nunca en las instrucciones)
data class SourceSection(
    val ref: String,
    val title: String,
    val text: String,
)

// lo que el paso lee antes de llamar (SourceMaterialReader): los archivos leídos (owner/repo@sha:ruta) y sus secciones con
// ancla, en orden de documento
data class SourceMaterial(
    val files: Set<String>,
    val sections: List<SourceSection>,
) {
    private val byRef = sections.associateBy { it.ref }

    fun section(ref: String): SourceSection? = byRef[ref]
}

// las entradas del paso, que guarda el trabajo y la ejecución: el brief LISTO del que salen y sus fuentes
data class SourceAnalysisInputs(
    val briefId: String,
    val topic: String,
    val audience: String?,
    val objectives: String?,
    val language: String?,
    val level: String?,
    // owner/repo@sha:ruta, una por fuente del brief
    val sources: List<String>,
) {
    companion object {
        fun of(inputs: JsonNode): SourceAnalysisInputs = AiJobs.MAPPER.treeToValue(inputs, SourceAnalysisInputs::class.java)
    }
}

// una implementación del paso. Bloquea: solo se llama desde su trabajo, en el dispatcher acotado de la cola. Devuelve la
// salida tal cual la produjo, sin comprobar, y el consumo (null con el doble)
interface SourceAnalyzer {
    fun analyze(
        prompt: AiPrompt,
        material: SourceMaterial,
    ): ModelReply<SourceAnalysis>
}

enum class SourceElementKind { CONCEPT, PROCEDURE }

// por qué se descartó un elemento: sin nombre o sin contenido, sin procedencia, de un archivo que el paso no leyó, de un ancla
// que no existe en ese archivo, o con una cita que no está en esa sección (o demasiado corta o larga para ser una cita)
enum class DiscardReason { BLANK, NO_PROVENANCE, SOURCE_NOT_READ, ANCHOR_NOT_FOUND, QUOTE_NOT_IN_SOURCE }

data class DiscardedElement(
    val kind: SourceElementKind,
    val name: String,
    val source: String?,
    val reason: DiscardReason,
)

// el informe de validación de la ejecución: cuántos quedaron y qué se descartó
data class SourceAnalysisReport(
    val concepts: Int,
    val procedures: Int,
    val discarded: List<DiscardedElement>,
)

// la comprobación por código de la salida, igual para el modelo y para el doble (docs/ai §5 y §6): cada elemento debe citar
// una sección que el paso leyó, con un fragmento que esté en su texto (espacios aparte); si no, se descarta y queda en el
// informe. Más elementos de los que caben, o ninguno con procedencia, es AiInvalidOutput: INVALID_OUTPUT y sin borrador
object SourceAnalysisCheck {
    const val MAX_ELEMENTS = 300
    const val MIN_QUOTE = 12
    const val MAX_QUOTE = 500
    const val MAX_NAME = 200
    const val MAX_TEXT = 2000
    const val MAX_STEPS = 100

    fun checked(
        analysis: SourceAnalysis,
        material: SourceMaterial,
        usage: TokenUsage?,
    ): AiReply {
        val total = analysis.concepts.size + analysis.procedures.size
        if (total > MAX_ELEMENTS) throw AiInvalidOutput("the analysis has $total elements, more than $MAX_ELEMENTS", usage)
        val discarded = mutableListOf<DiscardedElement>()
        val concepts =
            analysis.concepts.mapNotNull { concept ->
                val reason =
                    if (concept.name.isBlank() || concept.summary.isBlank()) DiscardReason.BLANK else provenance(concept.source, concept.quote, material)
                if (reason != null) {
                    discarded += DiscardedElement(SourceElementKind.CONCEPT, concept.name.take(MAX_NAME), concept.source?.take(MAX_NAME), reason)
                    null
                } else {
                    SourceConcept(concept.name.trim().take(MAX_NAME), concept.summary.trim().take(MAX_TEXT), concept.source!!.trim(), concept.quote!!.trim())
                }
            }
        val procedures =
            analysis.procedures.mapNotNull { procedure ->
                val steps = procedure.steps.map { it.trim() }.filter { it.isNotEmpty() }
                val reason = if (procedure.name.isBlank() || steps.isEmpty()) DiscardReason.BLANK else provenance(procedure.source, procedure.quote, material)
                if (reason != null) {
                    discarded += DiscardedElement(SourceElementKind.PROCEDURE, procedure.name.take(MAX_NAME), procedure.source?.take(MAX_NAME), reason)
                    null
                } else {
                    SourceProcedure(
                        procedure.name.trim().take(MAX_NAME),
                        steps.take(MAX_STEPS).map { it.take(MAX_TEXT) },
                        procedure.source!!.trim(),
                        procedure.quote!!.trim(),
                    )
                }
            }
        val report = SourceAnalysisReport(concepts.size, procedures.size, discarded)
        if (concepts.isEmpty() && procedures.isEmpty()) {
            throw AiInvalidOutput("no element of the analysis has a provenance that exists in its source", usage, report)
        }
        return AiReply(SourceAnalysis(concepts, procedures), usage, report)
    }

    // null: la procedencia existe en la fuente
    private fun provenance(
        source: String?,
        quote: String?,
        material: SourceMaterial,
    ): DiscardReason? {
        val ref = source?.trim().orEmpty()
        val cited = normalized(quote.orEmpty())
        if (ref.isEmpty() || cited.isEmpty()) return DiscardReason.NO_PROVENANCE
        val section =
            material.section(ref)
                ?: return if (ref.substringBefore('#') in material.files) DiscardReason.ANCHOR_NOT_FOUND else DiscardReason.SOURCE_NOT_READ
        if (cited.length !in MIN_QUOTE..MAX_QUOTE || cited !in normalized(section.text)) return DiscardReason.QUOTE_NOT_IN_SOURCE
        return null
    }

    // los espacios, saltos de línea incluidos, no cambian una cita
    fun normalized(text: String): String = text.replace(SPACES, " ").trim()

    private val SPACES = Regex("""\s+""")
}
