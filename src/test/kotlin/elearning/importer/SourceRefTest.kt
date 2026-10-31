package elearning.importer

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

// la referencia de una fuente (ADR-0018 §5): repositorio, commit exacto y ruta relativa, nunca una URL
class SourceRefTest {
    private val sha = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun `a reference names the repository, the exact commit and the path`() {
        val ref = SourceRef.parse("wasichai/manual-java@$sha:modulos/01/intro.adoc")

        assertThat(ref).isEqualTo(SourceRef("wasichai", "manual-java", sha, "modulos/01/intro.adoc"))
        assertThat(ref.repository).isEqualTo("wasichai/manual-java")
        assertThat(ref.toString()).isEqualTo("wasichai/manual-java@$sha:modulos/01/intro.adoc")
    }

    @Test
    fun `the commit is kept in lower case`() {
        assertThat(SourceRef.parse("acme/repo@${sha.uppercase()}:a.adoc").sha).isEqualTo(sha)
    }

    @Test
    fun `a branch, a tag or a short sha is not an exact commit`() {
        for (revision in listOf("main", "v1.0", sha.take(7), sha + "0", sha.dropLast(1) + "g")) {
            assertThatThrownBy { SourceRef.parse("acme/repo@$revision:a.adoc") }
                .describedAs(revision)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `a path out of the repository or with odd characters is refused`() {
        val refused =
            listOf(
                "../secreto.adoc",
                "docs/../../x.adoc",
                "./a.adoc",
                "/etc/passwd",
                "docs//a.adoc",
                "docs/",
                "docs\\a.adoc",
                "docs/a.adoc?ref=main",
                "docs/a%2e%2e.adoc",
                "docs/a b.adoc",
                "",
            )
        for (path in refused) {
            assertThatThrownBy { SourceRef("acme", "repo", sha, path) }
                .describedAs(path)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `an owner or repository that is not a github name is refused`() {
        for ((owner, repo) in listOf("" to "repo", "-acme" to "repo", "ac/me" to "repo", "acme" to "..", "acme" to ".", "acme" to "re po", "acme" to "")) {
            assertThatThrownBy { SourceRef(owner, repo, sha, "a.adoc") }
                .describedAs("$owner/$repo")
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `text that is not owner-repo-at-sha-colon-path is refused`() {
        for (text in listOf("acme/repo:a.adoc", "acme/repo@$sha", "acme@$sha:a.adoc", "https://github.com/acme/repo@$sha:a.adoc")) {
            assertThatThrownBy { SourceRef.parse(text) }
                .describedAs(text)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
