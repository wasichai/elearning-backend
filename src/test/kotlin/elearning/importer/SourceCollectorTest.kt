package elearning.importer

import elearning.importer.SourceFetchException.Reason
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

// la lectura por rondas de una fuente (ADR-0018 §5) con un SourceFetcher falso y fragmentos propios: nunca red real
class SourceCollectorTest {
    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val root = SourceRef("acme", "manuales", sha, "libro/manual.adoc")
    private val fetched = mutableListOf<String>()

    private fun fetcher(
        files: Map<String, String>,
        failing: Map<String, Reason> = emptyMap(),
    ) = SourceFetcher { ref ->
        fetched += ref.path
        assertThat(ref.repository).isEqualTo(root.repository)
        assertThat(ref.sha).isEqualTo(sha)
        when {
            ref.path in failing -> Mono.error(SourceFetchException(failing.getValue(ref.path), ref.toString()))
            ref.path in files -> Mono.just(files.getValue(ref.path).toByteArray())
            else -> Mono.error(SourceFetchException(Reason.NOT_FOUND, "$ref not found"))
        }
    }

    private fun collect(
        files: Map<String, String>,
        failing: Map<String, Reason> = emptyMap(),
        maxFiles: Int = 50,
    ) = runBlocking { SourceCollector(fetcher(files, failing), maxFiles).collect(root) }

    @Test
    fun `follows includes two levels deep, checks images and reads each file once`() {
        val collected =
            collect(
                mapOf(
                    "libro/manual.adoc" to "﻿= Manual\n\ninclude::cap/uno.adoc[leveloffset=+1]\n",
                    "libro/cap/uno.adoc" to "= Uno\n\ninclude::pasos/a.adoc[leveloffset=+1]\n",
                    "libro/cap/pasos/a.adoc" to "= Paso A\n\nimage::img/falta.png[Falta]\n\nimage::img/esta.png[Está]\n",
                    "libro/img/esta.png" to "png",
                ),
            )

        assertThat(collected.analysis.files).containsExactly("libro/manual.adoc", "libro/cap/uno.adoc", "libro/cap/pasos/a.adoc")
        assertThat(collected.analysis.sections.map { it.title }).containsExactly("Manual", "Uno", "Paso A")
        assertThat(collected.analysis.defects.map { it.kind to it.detail }).containsExactly(DefectKind.MISSING_IMAGE to "libro/img/falta.png")
        assertThat(fetched).doesNotHaveDuplicates()
    }

    @Test
    fun `a cycle and a missing include are reported, and it ends`() {
        val collected =
            collect(
                mapOf(
                    "libro/manual.adoc" to "= Manual\n\ninclude::a.adoc[]\ninclude::falta.adoc[]\n",
                    "libro/a.adoc" to "== A\n\ninclude::manual.adoc[]\n",
                ),
            )

        assertThat(collected.analysis.defects.map { it.kind }).containsExactlyInAnyOrder(DefectKind.INCLUDE_CYCLE, DefectKind.MISSING_INCLUDE)
    }

    @Test
    fun `a path outside the repository is never fetched`() {
        val collected = collect(mapOf("libro/manual.adoc" to "= Manual\n\ninclude::../../fuera.adoc[]\ninclude::con espacio.adoc[]\n"))

        assertThat(collected.analysis.defects.map { it.kind }).containsExactly(DefectKind.INCLUDE_OUTSIDE_ROOT, DefectKind.MISSING_INCLUDE)
        assertThat(fetched).containsExactly("libro/manual.adoc")
    }

    @Test
    fun `a file too large is left out and listed`() {
        val collected =
            collect(mapOf("libro/manual.adoc" to "= Manual\n\ninclude::grande.adoc[]\n"), failing = mapOf("libro/grande.adoc" to Reason.TOO_LARGE))

        assertThat(collected.oversized).containsExactly("libro/grande.adoc")
    }

    @Test
    fun `an unavailable source fails so the queue retries, and too many files stop it`() {
        assertThatThrownBy { collect(mapOf("libro/manual.adoc" to "include::a.adoc[]\n"), failing = mapOf("libro/a.adoc" to Reason.UNAVAILABLE)) }
            .isInstanceOf(SourceFetchException::class.java)
        val many = (1..5).joinToString("\n") { "include::f$it.adoc[]" }
        assertThatThrownBy { collect(mapOf("libro/manual.adoc" to many), maxFiles = 3) }.isInstanceOf(ImportFailure::class.java)
    }

    @Test
    fun `a missing root reads no file`() {
        val collected = collect(emptyMap())

        assertThat(collected.analysis.files).isEmpty()
    }
}
