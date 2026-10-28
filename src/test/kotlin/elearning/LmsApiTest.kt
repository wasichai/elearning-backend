package elearning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import java.io.File
import java.util.concurrent.TimeUnit

// base de las pruebas de API del LMS. antes de la primera prueba de la JVM aprovisiona la organización sembrada con el
// script real (model/apply.py --demo), contra esta misma aplicación: el contrato que se prueba es el que usa producción.
// la base de datos la comparten todas las clases de la corrida, así que el aprovisionamiento corre una sola vez
// el catálogo público es el de la organización sembrada por Wasichai («demo»)
@TestPropertySource(properties = ["lms.public.organization=demo", "wasichai.files.local.path=build/test-archivos"])
abstract class LmsApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var environment: Environment

    protected val json: JsonMapper = JsonMapper.builder().build()

    protected val port: Int get() = environment.getRequiredProperty("local.server.port").toInt()

    @BeforeEach
    fun provisionOnce() {
        synchronized(LOCK) {
            if (!provisioned) {
                val run = applyModel()
                assertEquals(0, run.exit, "model/apply.py --demo\n${run.output}")
                provisioned = true
            }
        }
    }

    protected data class ScriptRun(
        val exit: Int,
        val output: String,
    )

    protected fun applyModel(): ScriptRun {
        val process =
            ProcessBuilder("python3", "model/apply.py", "--demo", "--url", "http://127.0.0.1:$port")
                .directory(File(System.getProperty("user.dir")))
                .redirectErrorStream(true)
                .apply {
                    environment()["WASICHAI_EMAIL"] = ADMIN_EMAIL
                    environment()["WASICHAI_PASSWORD"] = ADMIN_PASSWORD
                    environment()["WASICHAI_DEMO_PASSWORD"] = DEMO_PASSWORD
                }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "model/apply.py no terminó en 5 minutos\n$output" }
        return ScriptRun(process.exitValue(), output)
    }

    protected fun get(
        path: String,
        token: String,
    ): JsonNode =
        json.readTree(
            client
                .get()
                .uri(path)
                .header("Authorization", token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody,
        )

    // los elementos de un arreglo JSON como texto; pick elige el nodo de cada uno
    protected fun JsonNode.strings(pick: (JsonNode) -> JsonNode = { it }): List<String> = values().map { pick(it).asString() }

    protected fun demoUser(role: String): String = bearer("$role@demo.wasichai-learning.test", DEMO_PASSWORD)

    companion object {
        const val DEMO_PASSWORD = "Demo-integration-2026"
        private val LOCK = Any()

        @Volatile
        private var provisioned = false
    }
}
