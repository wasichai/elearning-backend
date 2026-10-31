package elearning.importer

import elearning.importer.SourceFetchException.Reason
import kotlinx.coroutines.reactor.awaitSingleOrNull

// lo que una importación leyó: el análisis del grafo y los archivos que no se leyeron por pasar de lms.importer.max-bytes
data class CollectedSource(
    val analysis: IncludeAnalysis,
    val oversized: List<String>,
)

// la importación no termina bien, y reintentar no lo arregla: el trabajo acaba con este motivo en vez de fallar
class ImportFailure(
    message: String,
) : RuntimeException(message)

// lee la raíz y lo que su grafo include:: pide, todo por SourceFetcher y en el commit de la raíz. el analizador es síncrono
// y puro, así que trabaja por rondas: cada una lo corre sobre lo ya leído y trae lo que echó en falta, hasta que no falta
// nada (tantas rondas como profundidad tenga el grafo). nada bloquea y nada sale del repositorio: cada ruta pasa otra vez
// por SourceRef. un fallo pasajero de la fuente (UNAVAILABLE) sale tal cual, para que la cola reintente
class SourceCollector(
    private val fetcher: SourceFetcher,
    private val maxFiles: Int,
) {
    suspend fun collect(root: SourceRef): CollectedSource {
        val texts = mutableMapOf<String, String?>()
        val present = mutableMapOf<String, Boolean>()
        val oversized = linkedSetOf<String>()
        var totalChars = 0L
        repeat(MAX_ROUNDS) {
            val wantedTexts = linkedSetOf<String>()
            val wantedFiles = linkedSetOf<String>()
            val analysis =
                IncludeGraphAnalyzer(
                    read = { path -> if (path in texts) texts[path] else null.also { wantedTexts += path } },
                    // mientras no se sepa, existe: la ronda siguiente lo comprueba
                    exists = { path ->
                        when (path) {
                            in present -> present.getValue(path)
                            in texts -> texts[path] != null
                            else -> true.also { wantedFiles += path }
                        }
                    },
                ).analyze(root.path)
            wantedFiles -= wantedTexts
            if (wantedTexts.isEmpty() && wantedFiles.isEmpty()) return CollectedSource(analysis, oversized.toList())
            if (texts.size + present.size + wantedTexts.size + wantedFiles.size > maxFiles) {
                throw ImportFailure("$root needs more than $maxFiles files")
            }
            for (path in wantedTexts) {
                val text = read(root, path, oversized)
                texts[path] = text
                totalChars += text?.length ?: 0
                if (totalChars > MAX_TOTAL_CHARS) throw ImportFailure("$root has more than $MAX_TOTAL_CHARS characters of text")
            }
            for (path in wantedFiles) present[path] = exists(root, path)
        }
        throw ImportFailure("$root needs more than $MAX_ROUNDS rounds of include::")
    }

    // null: no está, o su ruta no es una que SourceRef admita
    private suspend fun read(
        root: SourceRef,
        path: String,
        oversized: MutableSet<String>,
    ): String? {
        val ref = sibling(root, path) ?: return null
        return try {
            (fetcher.fetch(ref).awaitSingleOrNull() ?: ByteArray(0)).toString(Charsets.UTF_8).removePrefix(BOM)
        } catch (e: SourceFetchException) {
            when (e.reason) {
                Reason.NOT_FOUND -> null
                Reason.TOO_LARGE -> null.also { oversized += path }
                Reason.NOT_ALLOWED, Reason.UNAVAILABLE -> throw e
            }
        }
    }

    // se lee entero y se descarta: el puerto no tiene otra forma de preguntar, y el tamaño ya lo acota
    private suspend fun exists(
        root: SourceRef,
        path: String,
    ): Boolean {
        val ref = sibling(root, path) ?: return false
        return try {
            fetcher.fetch(ref).awaitSingleOrNull()
            true
        } catch (e: SourceFetchException) {
            when (e.reason) {
                Reason.NOT_FOUND -> false
                Reason.TOO_LARGE -> true
                Reason.NOT_ALLOWED, Reason.UNAVAILABLE -> throw e
            }
        }
    }

    private fun sibling(
        root: SourceRef,
        path: String,
    ): SourceRef? =
        try {
            root.copy(path = path)
        } catch (_: IllegalArgumentException) {
            null
        }

    companion object {
        // la profundidad máxima de include:: más la raíz y una ronda de comprobación
        const val MAX_ROUNDS = IncludeGraphAnalyzer.DEFAULT_MAX_DEPTH + 2

        // el texto que una importación tiene en memoria a la vez
        const val MAX_TOTAL_CHARS = 32L * 1024 * 1024
        private const val BOM = "﻿"
    }
}
