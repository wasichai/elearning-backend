package elearning.ai

import elearning.jobs.JobRunner
import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordRequest
import java.util.UUID

// ningún dato personal de estudiantes entra en el contexto del modelo (revisión de seguridad de la F5, #302; docs/security
// §4.5 I, docs/ai §6). En un curso publicado con su edición, dos estudiantes con marcadores únicos en el nombre y el correo se
// matriculan, responden su evaluación (con otro marcador en la respuesta abierta) y valoran el recurso de la lectura (otro en
// el comentario). Sobre ese mismo curso corren, con Embabel de verdad contra el doble HTTP de cada proveedor, todos los pasos
// de IA que hay: análisis de fuentes, diseño, guion de vídeo y, al validar un borrador nuevo del curso, la opinión de
// consistencia. Ninguna petición que recibe el doble (URL, cabeceras ni cuerpo) ni ninguna ejecución AgentRun del curso
// contiene un marcador, ni el id de una persona, de su matrícula o de su intento; y la prueba no es vacía: lo sembrado está
// guardado y el contenido del curso sí llega al modelo. El borrador de lección (#283) entra aquí cuando esté en main
//
// Extiende el soporte del aula para sembrar como lo hace el aula; las claves ficticias y la URL del doble son las de
// ProviderDoubleApiTest, que no puede ser su base
@Import(SourceAnalysisFixtures.FakeSource::class)
@TestPropertySource(
    properties = [
        "lms.jobs.scheduling=false",
        "lms.jobs.backoff=0s",
        "lms.importer.allowed-sources=acme/manuales",
        "ANTHROPIC_API_KEY=${ProviderDouble.ANTHROPIC_KEY}",
        "OPENAI_API_KEY=${ProviderDouble.OPENAI_KEY}",
        "embabel.agent.platform.models.anthropic.api-key=",
        "embabel.agent.platform.models.openai.api-key=",
    ],
)
abstract class StudentDataProviderApiTest(
    private val replies: (String) -> Unit,
    private val prefix: String,
) : ClassroomApiTestSupport() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var log: AgentExecutionLog

    private val creator by lazy { demoUser("creador") }

    // lo que otra clase dejó pendiente cuenta para la concurrencia de la organización y el tope por persona
    @BeforeEach
    fun cancelLeftovers() {
        ProviderDouble.reset()
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

    @AfterEach
    fun noUnmatchedProviderRequests() = ProviderDouble.assertNoUnmatchedRequests()

    // lo personal de un estudiante sembrado: cada texto aparece solo en sus datos
    private class Student(
        val token: String,
        val attempt: String,
        val answer: String,
        val comment: String,
        val markers: List<String>,
    )

    private fun account(
        email: String,
        name: String,
        role: String,
    ): String {
        val (status, body) = call(admin, "/api/users", mapOf("email" to email, "displayName" to name, "password" to DEMO_PASSWORD, "roles" to listOf(role)))
        assertThat(status).describedAs(body.toString()).isEqualTo(HttpStatus.CREATED)
        return bearer(email, DEMO_PASSWORD)
    }

    private fun success(result: Pair<HttpStatus, JsonNode?>): JsonNode {
        assertThat(result.first.is2xxSuccessful).describedAs("${result.first} ${result.second}").isTrue()
        return result.second!!
    }

    // un estudiante con marcadores en su nombre y su correo, matriculado, con su intento enviado y su valoración del recurso
    private fun student(course: Course): Student {
        val tag =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(TAG)
        val given = "Nombre$tag"
        val family = "Apellido$tag"
        val mailbox = "correo$tag"
        val answer = "Respuesta$tag"
        val comment = "Comentario$tag"
        val token = acceptTerms(account("$mailbox@demo.wasichai-learning.test", "$given $family", "ESTUDIANTE"))
        val enrollment = enroll(token, course)["id"].asString()

        val attempt = success(call(token, "/api/lms/classroom/enrollments/$enrollment/lessons/${course.assessment}/attempts", emptyMap()))
        val attemptId = attempt["id"].asString()
        success(put(token, "/api/lms/classroom/attempts/$attemptId/answers", mapOf("answers" to answers(attempt, course, answer))))
        success(call(token, "/api/lms/classroom/attempts/$attemptId/submit", emptyMap()))

        ok(event(token, enrollment, course.reading, "MARKED_COMPLETE"))
        val rating =
            call(
                token,
                "/api/lms/classroom/enrollments/$enrollment/ratings",
                mapOf("type" to "RECURSO", "targetId" to course.resource, "scores" to mapOf("claridad" to 5, "utilidad" to 4), "comment" to comment),
            )
        assertThat(rating.first).describedAs(rating.second.toString()).isEqualTo(HttpStatus.CREATED)
        return Student(token, attemptId, answer, comment, listOf(given, family, mailbox, answer, comment, userId(token), enrollment, attemptId))
    }

    // las respuestas de la evaluación de ClassroomApiTestSupport, con el marcador en la abierta y en la corta
    private fun answers(
        attempt: JsonNode,
        course: Course,
        text: String,
    ): List<Map<String, Any?>> {
        fun option(
            question: String,
            label: String,
        ) = attempt["questions"]
            .values()
            .single { it["id"].asString() == question }["options"]
            .values()
            .single { it["text"].asString() == label }["id"]
            .asString()

        fun answer(
            question: String,
            options: List<String> = emptyList(),
            value: String? = null,
        ) = mapOf("questionId" to question, "optionIds" to options, "text" to value, "submissionId" to null)
        val q = course.questions
        return listOf(
            answer(q.getValue("SINGLE"), listOf(option(q.getValue("SINGLE"), "Correcta"))),
            answer(q.getValue("MULTIPLE"), listOf(option(q.getValue("MULTIPLE"), "Sí A"))),
            answer(q.getValue("TRUE_FALSE"), listOf(option(q.getValue("TRUE_FALSE"), "Falso"))),
            answer(q.getValue("SHORT"), value = text),
            answer(q.getValue("OPEN"), value = "$text explica el estado"),
        )
    }

    private fun created(
        objectName: String,
        attributes: Map<String, Any?>,
    ): String {
        val (status, body) = call(creator, "/api/objects/$objectName/records", mapOf("attributes" to attributes))
        assertThat(status).describedAs(body.toString()).isEqualTo(HttpStatus.CREATED)
        return body!!["id"].asString()
    }

    // encola el paso con la respuesta del doble, vacía la cola y devuelve el trabajo, que terminó bien
    private fun step(
        token: String,
        path: String,
        body: Map<String, Any?>,
        reply: String,
    ): JsonNode {
        replies("$prefix-$reply")
        val (status, job) = call(token, path, body)
        assertThat(status).describedAs(job.toString()).isEqualTo(HttpStatus.ACCEPTED)
        runBlocking { while (runner.runOnce() > 0) Unit }
        return job!!
    }

    private fun done(job: JsonNode): String {
        val read = ok(call(creator, "/api/lms/ai/jobs/${job["id"].asString()}"))
        assertThat(read["status"].asString()).describedAs(read.toString()).isEqualTo("DONE")
        return read["executionId"].asString()
    }

    // un borrador nuevo del mismo curso, como el limpio de DraftFixtures, de la creadora; devuelve la versión
    private fun draft(course: String): String {
        val author = userId(creator)
        val draft =
            runBlocking {
                records.asPlatform(ORG) {
                    val version =
                        records.create(
                            "version_curso",
                            RecordRequest(
                                mapOf(
                                    "curso" to course,
                                    "numero" to 2,
                                    "estado" to "BORRADOR",
                                    "modalidad" to "ASINCRONA",
                                    "semanas" to 1,
                                    "sistema_credito" to "NINGUNO",
                                    "contenido_ilustrativo" to true,
                                    "autor_id" to author,
                                    "objetivos" to "Usar el padrón",
                                    "resultados_aprendizaje" to "Consulta el padrón",
                                    "sistema_evaluacion" to "Una evaluación",
                                ),
                            ),
                        )
                    val unit =
                        records.create(
                            "unidad",
                            RecordRequest(mapOf("version_curso" to version.id, "orden" to 1, "titulo" to "Unidad 1", "objetivos" to "Explicar el padrón")),
                        )
                    val lesson =
                        records.create(
                            "leccion",
                            RecordRequest(
                                mapOf("unidad" to unit.id, "orden" to 1, "titulo" to "Lección 1", "tipo" to "EVALUACION", "duracion_minutos" to 30),
                            ),
                        )
                    val evaluation =
                        records.create(
                            "evaluacion",
                            RecordRequest(
                                mapOf(
                                    "leccion" to lesson.id,
                                    "titulo" to "Evaluación",
                                    "intentos_max" to 2,
                                    "nota_minima" to 60,
                                    "mostrar_soluciones" to "AL_ENVIAR",
                                ),
                            ),
                        )
                    val question =
                        records.create(
                            "pregunta",
                            RecordRequest(mapOf("evaluacion" to evaluation.id, "orden" to 1, "tipo" to "UNICA", "enunciado" to STATEMENT, "puntos" to 2)),
                        )
                    records.create("opcion", RecordRequest(mapOf("pregunta" to question.id, "orden" to 1, "texto" to "Un registro", "correcta" to true)))
                    records.create("opcion", RecordRequest(mapOf("pregunta" to question.id, "orden" to 2, "texto" to "Otra cosa", "correcta" to false)))
                    DraftFixtures.Draft(course, version.id, lesson.id)
                }
            }
        DraftFixtures(records, log).seedGenerated(draft, UUID.fromString(author))
        return draft.version
    }

    @Test
    fun `no personal data of students reaches the provider in any ai step`() {
        val course = course(withEvaluation = true)
        val courseId = get("/api/objects/curso/records?codigo=${course.offering}", admin)["content"][0]["id"].asString()
        val students = (1..2).map { student(course) }
        val markers = students.flatMap { it.markers }

        // lo sembrado está guardado: la respuesta en el intento y el comentario en la valoración de cada uno
        for (student in students) {
            assertThat(ok(call(student.token, "/api/lms/classroom/attempts/${student.attempt}")).toString()).contains(student.answer)
            assertThat(ok(call(student.token, "/api/lms/ratings/mine")).toString()).contains(student.comment)
        }

        // todos los pasos de IA sobre el mismo curso
        val brief = created("brief", SourceAnalysisFixtures.brief(courseId))
        val analysis = done(step(creator, "/api/lms/ai/source-analysis", mapOf("briefId" to brief), "source-analysis.json"))
        done(step(creator, "/api/lms/ai/course-design", mapOf("executionId" to analysis), "course-design.json"))
        val resource = created("recurso", mapOf("titulo" to "Vídeo de anuncios", "tipo" to "VIDEO", "idioma" to "es"))
        val video = created("version_recurso", mapOf("recurso" to resource, "numero" to 1, "autor_id" to userId(creator)))
        done(
            step(
                creator,
                "/api/lms/ai/scripts",
                mapOf("briefId" to brief, "versionId" to video, "targetSeconds" to VideoScriptProviderApiTest.TARGET),
                "video-script.json",
            ),
        )
        val version = draft(courseId)
        val reviewer = account("${uniqueName("revisor").lowercase()}@demo.wasichai-learning.test", "Revisora", "REVISOR")
        step(reviewer, "/api/lms/ai/validations", mapOf("versionId" to version), "consistency-approved.json")
        val opinion = ok(call(reviewer, "/api/lms/ai/validations?versionId=$version"))["opinion"]
        assertThat(opinion["status"].asString()).describedAs(opinion.toString()).isEqualTo("DONE")

        // una petición por paso, y el contenido del curso sí llega: la fuente al análisis y el borrador a la opinión
        val sent = ProviderDouble.servedRequests()
        assertThat(sent).hasSize(STEPS)
        assertThat(sent.joinToString("\n")).contains("Abrir el panel de anuncios", STATEMENT)
        for ((i, request) in sent.withIndex()) {
            for (marker in markers) assertThat(request).describedAs("petición $i al proveedor").doesNotContain(marker)
        }

        // ni lo que guarda el registro AgentRun del curso
        val runs = ok(call(creator, "/api/lms/ai/runs?courseId=$courseId")).values().map { it["id"].asString() }
        assertThat(runs).hasSizeGreaterThanOrEqualTo(STEPS)
        for (run in runs) {
            val text = ok(call(creator, "/api/lms/ai/runs/$run")).toString()
            for (marker in markers) assertThat(text).describedAs("ejecución $run").doesNotContain(marker)
        }
    }

    companion object {
        private const val TAG = 16

        // análisis, diseño, guion y opinión de consistencia: una llamada cada uno
        private const val STEPS = 4
        private const val STATEMENT = "¿Qué es el padrón?"

        @JvmStatic
        @DynamicPropertySource
        fun providerDouble(registry: DynamicPropertyRegistry) = ProviderDouble.register(registry)
    }
}

// con las dos claves y sin lms.ai.provider, la canalización usa Anthropic
class StudentDataAnthropicApiTest : StudentDataProviderApiTest(ProviderDouble::anthropicReplies, "messages")

@TestPropertySource(properties = ["lms.ai.provider=openai"])
class StudentDataOpenAiApiTest : StudentDataProviderApiTest(ProviderDouble::openAiReplies, "responses")
