package elearning.ai

import elearning.LmsApiTest
import elearning.ai.SourceAnalysisFixtures.CHAPTER
import elearning.ai.SourceAnalysisFixtures.ROOT
import elearning.jobs.JobRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import wasichai.core.data.RecordService

// la unidad de demostración de extremo a extremo sin claves (E38.1, #300): toda la canalización con el doble determinista,
// sin red ni proveedor, hasta el aula de un estudiante matriculado (DemoUnitFlow). Es lo que corre CI, que no tiene claves.
// Las anotaciones son las de LessonDraftApiTest a propósito: las dos clases comparten contexto de Spring
@Import(SourceAnalysisFixtures.FakeSource::class, LessonDraftFixtures.FakeRenderer::class)
@TestPropertySource(
    properties = [
        "ANTHROPIC_API_KEY=",
        "OPENAI_API_KEY=",
        "lms.jobs.scheduling=false",
        "lms.jobs.backoff=0s",
        "lms.importer.allowed-sources=acme/manuales",
    ],
)
class DemoUnitApiTest : LmsApiTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var status: AiStatus

    private val admin by lazy { bearer() }

    private val flow by lazy {
        DemoUnitFlow(
            client = client,
            json = json,
            runner = runner,
            records = records,
            db = db,
            admin = admin,
            account = { roles -> account(roles) },
            student = { student() },
            uniqueName = { uniqueName(it) },
            reply = {},
        )
    }

    private fun account(roles: List<String>): String {
        val email = "${uniqueName("demo")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Persona", "password" to DEMO_PASSWORD, "roles" to roles))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    private fun student(): String = acceptTerms(account(listOf("ESTUDIANTE")))

    @BeforeEach
    fun cancelLeftovers() {
        flow.cancelLeftovers()
    }

    @Test
    fun `without keys the double takes a brief to a published unit that an enrolled student reads in the classroom`() {
        // ni proveedor ni modelo: todo lo genera el doble determinista
        assertEquals(false, status.available)

        val result = flow.run(generator = "DETERMINISTIC", opinion = "SKIPPED")

        for (run in result.runs.values) {
            assertTrue(run["provider"].let { it == null || it.isNull }, run.toString())
            assertTrue(run["model"].let { it == null || it.isNull }, run.toString())
        }
        // las fuentes del brief, con su procedencia, en el análisis y otra vez en el borrador, en el mismo commit
        for (step in listOf("SOURCE_ANALYSIS", "LESSON_DRAFT")) {
            assertEquals(
                listOf(ROOT, CHAPTER),
                result.runs
                    .getValue(step)["sources"]
                    .values()
                    .map { it["ref"].asString() },
            )
        }
        // sin proveedor, la opinión de consistencia se omite y el informe lo dice
        assertTrue(result.reports.all { it["opinion"]["reason"].asString() == "NO_PROVIDER" })
    }
}
