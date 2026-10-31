package elearning.importer.github

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import elearning.importer.ImporterProperties
import elearning.importer.SourceFetchException
import elearning.importer.SourceRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.util.unit.DataSize
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

// el adaptador contra un servidor HTTP local que hace de API de GitHub: ninguna prueba sale a la red
class GitHubSourceFetcherTest {
    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val ref = SourceRef("acme", "manual", sha, "modulos/01/intro.adoc")
    private val requests = CopyOnWriteArrayList<HttpExchange>()
    private val executor = Executors.newCachedThreadPool()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = this@GitHubSourceFetcherTest.executor
            start()
        }

    @AfterEach
    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun respond(handler: (HttpExchange) -> Unit) {
        server.createContext("/") { exchange ->
            requests += exchange
            exchange.use(handler)
        }
    }

    private fun send(
        exchange: HttpExchange,
        status: Int,
        body: ByteArray = ByteArray(0),
    ) {
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.write(body)
    }

    private fun fetcher(
        allowed: List<String> = listOf("acme/manual"),
        token: String = "",
        timeout: Duration = Duration.ofSeconds(5),
        maxBytes: DataSize = DataSize.ofKilobytes(64),
    ) = GitHubSourceFetcher(
        ImporterProperties(allowedSources = allowed, githubToken = token, timeout = timeout, maxBytes = maxBytes),
        "http://127.0.0.1:${server.address.port}",
    )

    private fun reasonOf(block: () -> Unit) =
        runCatching(block).exceptionOrNull().let {
            assertThat(it).isInstanceOf(SourceFetchException::class.java)
            (it as SourceFetchException).reason
        }

    @Test
    fun `reads the raw file of the exact commit through the contents api`() {
        respond { send(it, 200, "= Introducción\n".toByteArray()) }

        val content = fetcher().fetch(ref).block()

        assertThat(content).isEqualTo("= Introducción\n".toByteArray())
        val request = requests.single()
        assertThat(request.requestMethod).isEqualTo("GET")
        assertThat(request.requestURI.rawPath).isEqualTo("/repos/acme/manual/contents/modulos/01/intro.adoc")
        assertThat(request.requestURI.rawQuery).isEqualTo("ref=$sha")
        assertThat(request.requestHeaders.getFirst("Accept")).isEqualTo("application/vnd.github.raw")
        assertThat(request.requestHeaders.getFirst("Authorization")).isNull()
    }

    @Test
    fun `sends the configured token`() {
        respond { send(it, 200, "x".toByteArray()) }

        fetcher(token = "secreto").fetch(ref).block()

        assertThat(requests.single().requestHeaders.getFirst("Authorization")).isEqualTo("Bearer secreto")
    }

    @Test
    fun `a repository out of the list is refused without any request`() {
        respond { send(it, 200, "x".toByteArray()) }

        assertThat(reasonOf { fetcher(allowed = listOf("acme/otro")).fetch(ref).block() }).isEqualTo(SourceFetchException.Reason.NOT_ALLOWED)
        assertThat(requests).isEmpty()
    }

    @Test
    fun `a missing file is not found`() {
        respond { send(it, 404, """{"message":"Not Found"}""".toByteArray()) }

        assertThat(reasonOf { fetcher().fetch(ref).block() }).isEqualTo(SourceFetchException.Reason.NOT_FOUND)
    }

    @Test
    fun `a redirect is never followed`() {
        server.createContext("/otra") { exchange ->
            requests += exchange
            exchange.use { send(it, 200, "x".toByteArray()) }
        }
        respond {
            it.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/otra")
            send(it, 302)
        }

        assertThat(reasonOf { fetcher().fetch(ref).block() }).isEqualTo(SourceFetchException.Reason.UNAVAILABLE)
        assertThat(requests.map { it.requestURI.path }).noneMatch { it.startsWith("/otra") }
    }

    @Test
    fun `a file over the maximum size is refused`() {
        respond { send(it, 200, ByteArray(2048) { 'a'.code.toByte() }) }

        assertThat(reasonOf { fetcher(maxBytes = DataSize.ofBytes(1024)).fetch(ref).block() }).isEqualTo(SourceFetchException.Reason.TOO_LARGE)
    }

    @Test
    fun `a server that does not answer in time is unavailable`() {
        respond {
            Thread.sleep(3_000)
            send(it, 200, "x".toByteArray())
        }

        assertThat(reasonOf { fetcher(timeout = Duration.ofMillis(300)).fetch(ref).block() }).isEqualTo(SourceFetchException.Reason.UNAVAILABLE)
    }

    @Test
    fun `a server error is unavailable`() {
        respond { send(it, 503) }

        assertThat(reasonOf { fetcher().fetch(ref).block() }).isEqualTo(SourceFetchException.Reason.UNAVAILABLE)
    }

    @Test
    fun `the production adapter points only at the github api`() {
        assertThat(GitHubSourceFetcher.API).isEqualTo("https://api.github.com")
    }
}
