package elearning.ai

import elearning.LmsApiTest
import elearning.jobs.JobQueue
import elearning.jobs.JobRunner
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordService
import java.util.UUID

// autorización negativa de cada ruta de /api/lms/ai/** (revisión de seguridad de la F5, #302; docs/security §9), sin claves:
// el doble determinista hace los pasos. Las rutas salen de los controladores de la aplicación, así que una ruta nueva sin su
// caso aquí hace fallar la prueba. Para cada una: sin sesión 401; un estudiante y un docente, que no tienen GENERAR, VALIDAR
// ni UPDATE sobre version_curso, 403 o 404 y nada del curso en el cuerpo. Los trabajos son de quien los pidió: otra creadora y
// el administrador reciben 404 al leerlos o cancelarlos, y un trabajo de otra organización es 404 aunque lo pidiera la misma
// persona. Después, nada cambió. Las mismas anotaciones que SourceAnalysisApiTest: comparten contexto de Spring
@Import(SourceAnalysisFixtures.FakeSource::class)
@TestPropertySource(
    properties = [
        "ANTHROPIC_API_KEY=",
        "OPENAI_API_KEY=",
        "lms.jobs.scheduling=false",
        "lms.jobs.backoff=0s",
        "lms.importer.allowed-sources=acme/manuales",
    ],
)
class AiRoutesAuthorizationApiTest : LmsApiTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var queue: JobQueue

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var log: AgentExecutionLog

    @Autowired
    private lateinit var context: ApplicationContext

    private val admin by lazy { bearer() }
    private val creator by lazy { demoUser("creador") }

    @BeforeEach
    fun cancelLeftovers() {
        runBlocking {
            db
                .sql(
                    "UPDATE lms.trabajo SET estado = 'CANCELLED', reclamado_en = NULL, terminado_en = now() " +
                        "WHERE (tipo LIKE 'ai.%' OR tipo = '${DraftValidations.JOB_TYPE}') AND estado IN ('QUEUED', 'RUNNING')",
                ).fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    private fun exchange(
        method: String,
        path: String,
        token: String?,
        body: Any? = null,
    ): Pair<HttpStatus, String?> {
        val spec = if (method == "POST") client.post().uri(path).bodyValue(body ?: emptyMap<String, Any>()) else client.get().uri(path)
        val r = (if (token == null) spec else spec.header("Authorization", token)).exchange().expectBody(String::class.java).returnResult()
        return HttpStatus.valueOf(r.status.value()) to r.responseBody
    }

    private fun ok(
        method: String,
        path: String,
        token: String,
        body: Any? = null,
        expected: HttpStatus = HttpStatus.OK,
    ): JsonNode {
        val (status, text) = exchange(method, path, token, body)
        assertThat(status).describedAs("$method $path: $text").isEqualTo(expected)
        return json.readTree(text)
    }

    private fun account(vararg roles: String): String {
        val email = "${uniqueName("rutas-ia")}@demo.wasichai-learning.test"
        ok(
            "POST",
            "/api/users",
            admin,
            mapOf("email" to email, "displayName" to "Persona", "password" to DEMO_PASSWORD, "roles" to roles.toList()),
            HttpStatus.CREATED,
        )
        return bearer(email, DEMO_PASSWORD)
    }

    private fun userId(token: String): UUID = UUID.fromString(get("/api/auth/me", token)["userId"].asString())

    private fun drain() = runBlocking { while (runner.runOnce() > 0) Unit }

    // una ruta con la petición concreta que se prueba: su método y su patrón, como los declara el controlador
    private data class Route(
        val method: String,
        val pattern: String,
        val path: String,
        val body: Map<String, Any?>? = null,
    ) {
        val key = "$method $pattern"
    }

    // las rutas de /api/lms/ai/** que declaran los controladores de la aplicación
    private fun declaredRoutes(): Set<String> =
        context
            .getBeansOfType(RequestMappingHandlerMapping::class.java)
            .values
            .flatMap { it.handlerMethods.keys }
            .flatMap { info ->
                val methods =
                    info.methodsCondition.methods
                        .map { it.name }
                        .ifEmpty { listOf("ANY") }
                info.patternsCondition.patterns
                    .map { it.patternString }
                    .filter { it.startsWith(PREFIX) }
                    .flatMap { p -> methods.map { "$it $p" } }
            }.toSet()

    @Test
    fun `every ai route refuses whoever is not allowed, and a job of someone else or of another organization does not exist`() {
        // lo de la creadora: un curso con su brief LISTO, el análisis terminado y un borrador que valida una revisora
        val slug = uniqueName("rutas-ia-").lowercase()
        val attributes = mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso")
        val course = ok("POST", "/api/objects/curso/records", creator, mapOf("attributes" to attributes), HttpStatus.CREATED)["id"].asString()
        val brief =
            ok(
                "POST",
                "/api/objects/brief/records",
                creator,
                mapOf("attributes" to SourceAnalysisFixtures.brief(course)),
                HttpStatus.CREATED,
            )["id"].asString()
        val job = ok("POST", "/api/lms/ai/source-analysis", creator, mapOf("briefId" to brief), HttpStatus.ACCEPTED)["id"].asString()
        drain()
        val execution = ok("GET", "/api/lms/ai/jobs/$job", creator)["executionId"].asString()
        val reviewer = account("REVISOR")
        val draft = DraftFixtures(records, log).draft(uniqueName("RUT-").uppercase(), userId(creator).toString())
        val validation = ok("POST", "/api/lms/ai/validations", reviewer, mapOf("versionId" to draft.version), HttpStatus.ACCEPTED)["id"].asString()
        drain()
        assertThat(ok("GET", "/api/lms/ai/validations/jobs/$validation", reviewer)["status"].asString()).isEqualTo("DONE")

        val routes =
            listOf(
                Route("POST", "$PREFIX/source-analysis", "$PREFIX/source-analysis", mapOf("briefId" to brief)),
                Route("POST", "$PREFIX/course-design", "$PREFIX/course-design", mapOf("executionId" to execution)),
                // el borrador de lección (#283): con él en main, esta fila ya lo cubre
                Route("POST", "$PREFIX/lesson-draft", "$PREFIX/lesson-draft", mapOf("executionId" to execution)),
                Route("POST", "$PREFIX/scripts", "$PREFIX/scripts", mapOf("briefId" to brief, "versionId" to UUID.randomUUID().toString())),
                Route("GET", "$PREFIX/jobs/{id}", "$PREFIX/jobs/$job"),
                Route("POST", "$PREFIX/jobs/{id}/cancel", "$PREFIX/jobs/$job/cancel", emptyMap()),
                Route("GET", "$PREFIX/runs", "$PREFIX/runs?courseId=$course"),
                Route("GET", "$PREFIX/runs/{id}", "$PREFIX/runs/$execution"),
                Route("POST", "$PREFIX/validations", "$PREFIX/validations", mapOf("versionId" to draft.version)),
                Route("GET", "$PREFIX/validations/jobs/{id}", "$PREFIX/validations/jobs/$validation"),
                Route("GET", "$PREFIX/validations", "$PREFIX/validations?versionId=${draft.version}"),
                Route("GET", "$PREFIX/indicators", "$PREFIX/indicators?courseId=$course"),
            )
        val declared = declaredRoutes()
        assertThat(declared).describedAs("rutas de IA sin su prueba de autorización negativa").isSubsetOf(routes.map { it.key })
        val live = routes.filter { it.key in declared }

        val student = account("ESTUDIANTE")
        val teacher = account("DOCENTE")
        for (route in live) {
            assertThat(exchange(route.method, route.path, null, route.body).first).describedAs("sin sesión: ${route.key}").isEqualTo(HttpStatus.UNAUTHORIZED)
            for ((who, token) in listOf("estudiante" to student, "docente" to teacher)) {
                val (status, body) = exchange(route.method, route.path, token, route.body)
                assertThat(status).describedAs("$who: ${route.key} → $body").isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND)
                assertThat(body.orEmpty()).describedAs("$who: ${route.key}").doesNotContain(TOPIC, "¿Qué es el padrón?")
            }
        }

        // los trabajos son de quien los pidió, también frente al administrador
        val other = account("CREADOR")
        for (token in listOf(other, admin)) {
            assertThat(exchange("GET", "$PREFIX/jobs/$job", token).first).isEqualTo(HttpStatus.NOT_FOUND)
            assertThat(exchange("POST", "$PREFIX/jobs/$job/cancel", token, emptyMap<String, Any>()).first).isEqualTo(HttpStatus.NOT_FOUND)
            assertThat(exchange("GET", "$PREFIX/validations/jobs/$validation", token).first).isEqualTo(HttpStatus.NOT_FOUND)
        }
        assertThat(exchange("GET", "$PREFIX/validations/jobs/$validation", account("REVISOR")).first).isEqualTo(HttpStatus.NOT_FOUND)
        // el informe lo leen su autora, su revisor asignado y quien valida: otra creadora no
        assertThat(exchange("GET", "$PREFIX/validations?versionId=${draft.version}", other).first).isEqualTo(HttpStatus.FORBIDDEN)

        // un trabajo de otra organización no existe, aunque lo pidiera la misma persona; se cancela al crearlo para que nadie lo corra
        val elsewhere = UUID.randomUUID()
        val foreignJob = runBlocking { queue.enqueue(elsewhere, AiJobs.typeOf(AgentStep.SOURCE_ANALYSIS), requester = userId(creator)) }
        val foreignValidation = runBlocking { queue.enqueue(elsewhere, DraftValidations.JOB_TYPE, requester = userId(reviewer)) }
        runBlocking { listOf(foreignJob, foreignValidation).forEach { queue.cancel(elsewhere, it) } }
        assertThat(exchange("GET", "$PREFIX/jobs/$foreignJob", creator).first).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(exchange("POST", "$PREFIX/jobs/$foreignJob/cancel", creator, emptyMap<String, Any>()).first).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(exchange("GET", "$PREFIX/validations/jobs/$foreignValidation", reviewer).first).isEqualTo(HttpStatus.NOT_FOUND)

        // nada cambió: el trabajo sigue DONE con su ejecución, y la validación, DONE
        val after = ok("GET", "$PREFIX/jobs/$job", creator)
        assertThat(after["status"].asString()).isEqualTo("DONE")
        assertThat(after["executionId"].asString()).isEqualTo(execution)
        assertThat(ok("GET", "$PREFIX/runs/$execution", creator)["status"].asString()).isEqualTo("SUCCEEDED")
        assertThat(ok("GET", "$PREFIX/validations/jobs/$validation", reviewer)["status"].asString()).isEqualTo("DONE")
    }

    private companion object {
        const val PREFIX = "/api/lms/ai"

        // la temática del brief de SourceAnalysisFixtures
        const val TOPIC = "Administración de anuncios"
    }
}
