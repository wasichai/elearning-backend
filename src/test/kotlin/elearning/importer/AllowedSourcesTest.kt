package elearning.importer

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

// la lista configurada de orígenes (lms.importer.allowed-sources): un repositorio entero o un repositorio en un commit
class AllowedSourcesTest {
    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val other = "fedcba9876543210fedcba9876543210fedcba98"

    @Test
    fun `an empty list allows nothing`() {
        assertThat(AllowedSources(emptyList()).permits(SourceRef("acme", "repo", sha, "a.adoc"))).isFalse()
    }

    @Test
    fun `a listed repository allows any of its commits, ignoring case as github does`() {
        val allowed = AllowedSources(listOf("Wasichai/Manual-Java"))

        assertThat(allowed.permits(SourceRef("wasichai", "manual-java", sha, "a.adoc"))).isTrue()
        assertThat(allowed.permits(SourceRef("wasichai", "manual-java", other, "b/c.adoc"))).isTrue()
        assertThat(allowed.permits(SourceRef("wasichai", "manual-python", sha, "a.adoc"))).isFalse()
        assertThat(allowed.permits(SourceRef("otro", "manual-java", sha, "a.adoc"))).isFalse()
    }

    @Test
    fun `a listed repository at a commit allows only that commit`() {
        val allowed = AllowedSources(listOf("wasichai/manual-java@$sha"))

        assertThat(allowed.permits(SourceRef("wasichai", "manual-java", sha, "a.adoc"))).isTrue()
        assertThat(allowed.permits(SourceRef("wasichai", "manual-java", other, "a.adoc"))).isFalse()
    }

    @Test
    fun `blank entries are skipped and a malformed one stops the start`() {
        assertThat(AllowedSources(listOf(" ", "acme/repo ")).permits(SourceRef("acme", "repo", sha, "a.adoc"))).isTrue()
        for (entry in listOf("acme", "acme/repo@main", "https://github.com/acme/repo", "acme/repo:a.adoc")) {
            assertThatThrownBy { AllowedSources(listOf(entry)) }
                .describedAs(entry)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
