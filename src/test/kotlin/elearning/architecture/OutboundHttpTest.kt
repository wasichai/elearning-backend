package elearning.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

// SSRF (revisión de seguridad de la F5, #302; docs/security §4.5): la aplicación solo abre conexiones HTTP salientes desde el
// adaptador de GitHub del importador (un host fijo, sin redirecciones, solo la lista permitida) y desde el cliente del
// trabajador de render (la URL de lms.render.worker-url). Ninguna URL sale de un usuario, de una fuente ni de la salida de un
// modelo: los enlaces de un borrador o de un guion se revisan, nunca se visitan. La llamada al proveedor de IA es de Embabel,
// con su URL base de configuración. Un cliente HTTP nuevo en src/main tiene que pasar por esta lista, y por su revisión
class OutboundHttpTest {
    private val root = File("src/main/kotlin")

    @Test
    fun `only the github adapter and the render worker client open outbound http connections`() {
        val clients =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file -> file.readLines().any { line -> CLIENTS.any { it.containsMatchIn(line) } } }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toSet()

        assertThat(clients).containsExactlyInAnyOrderElementsOf(ALLOWED)
    }

    @Test
    fun `the scan sees a client wherever it appears`() {
        for (line in listOf(
            "import org.springframework.web.reactive.function.client.WebClient",
            "import reactor.netty.http.client.HttpClient",
            "import java.net.http.HttpClient",
            "import java.net.URL",
            "import org.springframework.web.client.RestClient",
            "    val stream = URI(link).toURL().openStream()",
            "    val connection = url.openConnection()",
        )) {
            assertThat(CLIENTS.any { it.containsMatchIn(line) }).describedAs(line).isTrue()
        }
        assertThat(CLIENTS.any { it.containsMatchIn("import java.net.URI") }).isFalse()
    }

    private companion object {
        val ALLOWED = setOf("elearning/importer/github/GitHubSourceFetcher.kt", "elearning/media/video/RenderWorkerClient.kt")

        val CLIENTS =
            listOf(
                Regex("""^\s*import\s+org\.springframework\.web\.reactive\.function\.client\."""),
                Regex("""^\s*import\s+org\.springframework\.web\.client\."""),
                Regex("""^\s*import\s+reactor\.netty\.http\.client\."""),
                Regex("""^\s*import\s+java\.net\.http\."""),
                Regex("""^\s*import\s+java\.net\.(URL|HttpURLConnection)\b"""),
                Regex("""^\s*import\s+(okhttp3|org\.apache\.hc|org\.apache\.http)\."""),
                Regex("""\.(openConnection|openStream|toURL)\("""),
            )
    }
}
