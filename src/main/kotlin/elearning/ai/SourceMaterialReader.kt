package elearning.ai

import elearning.importer.CollectedSource
import elearning.importer.DraftProposal
import elearning.importer.ImportFailure
import elearning.importer.ImporterProperties
import elearning.importer.SourceCollector
import elearning.importer.SourceFetchException
import elearning.importer.SourceFetcher
import elearning.importer.SourceRef
import elearning.jobs.PermanentJobFailure
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.HexFormat

// lee las fuentes del brief para el análisis, en el trabajo y antes de llamar (AiStepRunner): cada raíz y lo que su grafo
// include:: pide, por el puerto del importador, que solo lee la lista permitida en un commit exacto (docs/ai §6: el modelo no
// lee nada; recibe lo que esto le da). Cada archivo leído queda en la ejecución con el SHA-256 de su texto. Un fallo pasajero
// de GitHub sale tal cual y la cola reintenta; lo demás (fuera de la lista, no existe, demasiado grande) no cambia por
// reintentar y es PermanentJobFailure, sin ejecución: no se llamó a ningún modelo
@Component
class SourceMaterialReader(
    private val fetcher: SourceFetcher,
    private val properties: ImporterProperties,
) {
    suspend fun read(sources: List<String>): AiStepInput<SourceMaterial> {
        if (sources.isEmpty()) throw PermanentJobFailure("The brief has no sources to analyze")
        val digests = linkedMapOf<String, SourceDigest>()
        val sections = mutableListOf<SourceSection>()
        for (line in sources.distinct()) {
            val root =
                try {
                    SourceRef.parse(line)
                } catch (e: IllegalArgumentException) {
                    throw PermanentJobFailure("Invalid brief source '$line': ${e.message}", e)
                }
            val collected = collect(root)
            if (root.path !in collected.texts) throw PermanentJobFailure("$root could not be read")
            for (path in collected.analysis.files) {
                val text = collected.texts[path] ?: continue
                val ref = DraftProposal.origin(root, path)
                digests.putIfAbsent(ref, SourceDigest(ref, sha256(text)))
            }
            sections += sectionsOf(root, collected)
        }
        val unique = sections.distinctBy { it.ref }
        val size = unique.sumOf { it.text.length.toLong() }
        if (size > MAX_CHARS) throw PermanentJobFailure("The sources of the brief have $size characters, more than $MAX_CHARS for one analysis")
        return AiStepInput(SourceMaterial(digests.keys, unique), digests.values.toList())
    }

    private suspend fun collect(root: SourceRef): CollectedSource =
        try {
            SourceCollector(fetcher, properties.maxFiles).collect(root)
        } catch (e: ImportFailure) {
            throw PermanentJobFailure(e.message ?: "$root could not be read", e)
        } catch (e: SourceFetchException) {
            if (e.reason == SourceFetchException.Reason.UNAVAILABLE) throw e
            throw PermanentJobFailure(e.message ?: "$root could not be read", e)
        }

    companion object {
        // el texto que va a una sola llamada; más es un brief con demasiadas fuentes para un paso
        const val MAX_CHARS = 400_000L

        // las secciones con ancla de cada archivo leído, con su texto: de la línea tras su título a la anterior al siguiente
        // título del mismo archivo (o a su final). La referencia es la traza del importador, owner/repo@sha:ruta#ancla
        fun sectionsOf(
            root: SourceRef,
            collected: CollectedSource,
        ): List<SourceSection> =
            collected.analysis.sections
                .groupBy { it.location.path }
                .flatMap { (path, inFile) ->
                    val lines = collected.texts[path]?.split('\n') ?: return@flatMap emptyList()
                    val ordered = inFile.distinctBy { it.location.line }.sortedBy { it.location.line }
                    ordered.mapIndexedNotNull { index, section ->
                        if (section.id == null) return@mapIndexedNotNull null
                        // línea n (desde 1) es lines[n - 1]: el cuerpo va de lines[título] a lines[siguiente - 2]
                        val next = ordered.getOrNull(index + 1)?.location?.line ?: (lines.size + 1)
                        val start = minOf(section.location.line, lines.size)
                        val text = lines.subList(start, (next - 1).coerceIn(start, lines.size))
                        SourceSection(DraftProposal.trace(root, section), section.title.take(DraftProposal.MAX_TITLE), text.joinToString("\n").trim())
                    }
                }

        private fun sha256(text: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
    }
}
