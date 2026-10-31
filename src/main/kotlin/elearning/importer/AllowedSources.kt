package elearning.importer

// la lista configurada de orígenes (lms.importer.allowed-sources): `owner/repo` admite cualquier commit del repositorio y
// `owner/repo@sha`, solo ese. GitHub no distingue mayúsculas en los nombres; una entrada mal escrita impide arrancar
class AllowedSources(
    entries: List<String>,
) {
    private val origins: List<Origin> = entries.map { it.trim() }.filter { it.isNotEmpty() }.map(::origin)

    fun permits(ref: SourceRef): Boolean = origins.any { it.repository.equals(ref.repository, ignoreCase = true) && (it.sha == null || it.sha == ref.sha) }

    private data class Origin(
        val repository: String,
        val sha: String?,
    )

    private companion object {
        val ENTRY = Regex("^([^/@:]+)/([^/@:]+)(?:@([0-9a-fA-F]{40}))?$")

        fun origin(entry: String): Origin {
            val match = requireNotNull(ENTRY.matchEntire(entry)) { "allowed source '$entry' must be owner/repo or owner/repo@sha" }
            val (owner, repo, sha) = match.destructured
            require(SourceRef.OWNER.matches(owner) && SourceRef.REPO.matches(repo) && repo != "." && repo != "..") {
                "allowed source '$entry' is not a github repository"
            }
            return Origin("$owner/$repo", sha.lowercase().ifEmpty { null })
        }
    }
}
