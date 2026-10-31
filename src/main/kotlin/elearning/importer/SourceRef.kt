package elearning.importer

// un archivo de un repositorio en un commit exacto, `owner/repo@sha:ruta` (ADR-0018 §5). nunca una URL: el adaptador
// arma la petición con estas piezas, así que cada una se limita a lo que GitHub admite y la ruta no sale del repositorio
data class SourceRef(
    val owner: String,
    val repo: String,
    val sha: String,
    val path: String,
) {
    init {
        require(OWNER.matches(owner)) { "owner must be a github account name" }
        require(REPO.matches(repo) && repo != "." && repo != "..") { "repo must be a github repository name" }
        require(SHA.matches(sha)) { "sha must be a full 40-character lower-case commit id" }
        require(isRelativePath(path)) { "path must be relative, inside the repository, with plain segments" }
    }

    val repository: String get() = "$owner/$repo"

    override fun toString() = "$owner/$repo@$sha:$path"

    companion object {
        // cuentas de GitHub: letras, cifras y guiones, sin guion inicial, hasta 39
        internal val OWNER = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,38}$")
        internal val REPO = Regex("^[A-Za-z0-9._-]{1,100}$")
        private val SHA = Regex("^[0-9a-f]{40}$")

        // segmentos sin «%», «?», «\» ni espacios: van tal cual a la ruta de la petición, sin codificar nada
        private val SEGMENT = Regex("^[A-Za-z0-9._-]+$")
        private val FORM = Regex("^([^/@:]+)/([^/@:]+)@([^:]+):(.+)$")

        fun parse(text: String): SourceRef {
            val match = requireNotNull(FORM.matchEntire(text.trim())) { "expected owner/repo@sha:path" }
            val (owner, repo, sha, path) = match.destructured
            return SourceRef(owner, repo, sha.lowercase(), path)
        }

        private fun isRelativePath(path: String) =
            path.length <= MAX_PATH &&
                path.split('/').all { SEGMENT.matches(it) && it != "." && it != ".." }

        private const val MAX_PATH = 1024
    }
}
