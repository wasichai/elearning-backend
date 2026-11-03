package elearning.ai

import elearning.jobs.JobRunner
import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordRequest
import java.util.UUID

// RF-IA-05 (E34.1): la validación por código de un borrador. La lanza quien tiene VALIDAR y no es su autor; corre en la
// cola y deja su informe en una ejecución AgentRun (VALIDATION, DETERMINISTIC); un borrador GENERADO no entra en revisión
// sin un informe vigente y sin errores bloqueantes (409). Que el borrador es generado lo dice una ejecución LESSON_DRAFT
// con la versión en salida.versionId, como la dejará #283: aquí se siembra a mano. La cola se mueve con runOnce
class DraftValidationApiTest : ClassroomApiTestSupport() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var log: AgentExecutionLog

    private val sha = "c".repeat(40)
    private val trace = "wasichai/manual@$sha:capitulo.adoc#intro"

    private fun account(vararg roles: String): String {
        val email = "${uniqueName("val")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Persona", "password" to DEMO_PASSWORD, "roles" to roles.toList()))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    private class Draft(
        val course: String,
        val version: String,
        val lesson: String,
    )

    // un borrador de un curso nuevo escrito por author, como lo dejaría la plataforma. defective: un defecto por categoría
    private fun draft(
        author: String,
        defective: Boolean = false,
        generated: Boolean = true,
    ): Draft {
        val authorId = userId(author)
        val draft =
            runBlocking {
                records.asPlatform(ORG) {
                    val code = uniqueName("VAL-").uppercase()
                    val course =
                        records.create(
                            "curso",
                            RecordRequest(
                                mapOf(
                                    "codigo" to code,
                                    "slug" to code.lowercase(),
                                    "titulo" to "Curso generado",
                                    "idioma" to "es",
                                    "nivel" to "INICIAL",
                                ),
                            ),
                        )
                    val outcomes =
                        if (defective) {
                            emptyMap()
                        } else {
                            mapOf("objetivos" to "Usar el padrón", "resultados_aprendizaje" to "Consulta el padrón", "sistema_evaluacion" to "Una evaluación")
                        }
                    val version =
                        records.create(
                            "version_curso",
                            RecordRequest(
                                mapOf(
                                    "curso" to course.id,
                                    "numero" to 1,
                                    "estado" to "BORRADOR",
                                    "modalidad" to "ASINCRONA",
                                    "semanas" to 1,
                                    "sistema_credito" to "NINGUNO",
                                    "contenido_ilustrativo" to true,
                                    "autor_id" to authorId,
                                    "fuentes" to "wasichai/manual@$sha:manual.adoc",
                                ) + outcomes,
                            ),
                        )
                    val unit =
                        records.create(
                            "unidad",
                            RecordRequest(
                                mapOf(
                                    "version_curso" to version.id,
                                    "orden" to 1,
                                    "titulo" to "Unidad 1",
                                    "objetivos" to "Explicar el padrón",
                                    "referencia_fuente" to trace,
                                ) +
                                    (if (defective) emptyMap() else mapOf("horas_estimadas" to 2)),
                            ),
                        )
                    val summary =
                        if (defective) {
                            "= Padrón\n\n=== Detalle\n\nimage::flujo.png[]\n\nVer http://docs.example.org/guia"
                        } else {
                            "== Padrón\n\n=== Detalle\n\nimage::flujo.png[Flujo de la consulta]\n\nVer https://docs.example.org/guia"
                        }
                    val lesson =
                        records.create(
                            "leccion",
                            RecordRequest(
                                mapOf(
                                    "unidad" to unit.id,
                                    "orden" to 1,
                                    "titulo" to "Lección 1",
                                    "tipo" to "EVALUACION",
                                    "duracion_minutos" to 30,
                                    "resumen" to summary,
                                    "referencia_fuente" to trace,
                                ),
                            ),
                        )
                    if (defective) {
                        records.create(
                            "leccion",
                            RecordRequest(
                                mapOf(
                                    "unidad" to unit.id,
                                    "orden" to 2,
                                    "titulo" to "Lección 2",
                                    "tipo" to "LECTURA",
                                    "duracion_minutos" to 30,
                                    "resumen" to summary,
                                    "referencia_fuente" to "no es una traza",
                                ),
                            ),
                        )
                    }
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
                            RecordRequest(
                                mapOf(
                                    "evaluacion" to evaluation.id,
                                    "orden" to 1,
                                    "tipo" to "UNICA",
                                    "enunciado" to "¿Qué es el padrón?",
                                    "puntos" to 2,
                                ),
                            ),
                        )
                    records.create("opcion", RecordRequest(mapOf("pregunta" to question.id, "orden" to 1, "texto" to "Un registro", "correcta" to !defective)))
                    records.create("opcion", RecordRequest(mapOf("pregunta" to question.id, "orden" to 2, "texto" to "Otra cosa", "correcta" to false)))
                    Draft(course.id, version.id, lesson.id)
                }
            }
        if (generated) seedGenerated(draft, UUID.fromString(authorId))
        return draft
    }

    // el vínculo que dejará el paso de borrador (#283): una ejecución LESSON_DRAFT que nombra la versión en su salida
    private fun seedGenerated(
        draft: Draft,
        author: UUID,
    ) = runBlocking {
        val run =
            log.start(
                NewAgentExecution(
                    organizationId = ORG,
                    jobId = UUID.randomUUID(),
                    step = AgentStep.LESSON_DRAFT,
                    requestedBy = author,
                    courseId = UUID.fromString(draft.course),
                    objective = "Redactar el borrador",
                    inputs = mapOf("brief" to "padrón"),
                    generator = Generator.DETERMINISTIC,
                    model = null,
                    promptVersion = "test",
                    templateVersion = "test",
                    idempotencyKey = UUID.randomUUID().toString(),
                    attempt = 1,
                ),
            )
        log.finish(ORG, run, AgentExecutionOutcome(AgentExecutionStatus.SUCCEEDED, output = mapOf("versionId" to draft.version)))
    }

    private fun drain() =
        runBlocking {
            while (runner.runOnce() > 0) Unit
        }

    private fun launch(
        token: String?,
        version: String,
    ) = call(token, "/api/lms/ai/validations", mapOf("versionId" to version))

    // lanza, mueve la cola y devuelve el trabajo terminado
    private fun validated(
        token: String,
        version: String,
    ): JsonNode {
        val (status, body) = launch(token, version)
        assertEquals(HttpStatus.ACCEPTED, status, body.toString())
        drain()
        val job = ok(call(token, "/api/lms/ai/validations/jobs/${body!!["id"].asString()}"))
        assertEquals("DONE", job["status"].asString(), job.toString())
        return job
    }

    private fun report(
        token: String?,
        version: String,
    ) = call(token, "/api/lms/ai/validations?versionId=$version")

    private fun patch(
        token: String,
        objectName: String,
        id: String,
        attributes: Map<String, Any?>,
    ): Int =
        client
            .patch()
            .uri("/api/objects/$objectName/records/$id")
            .header("Authorization", token)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()
            .returnResult(String::class.java)
            .status
            .value()

    private fun submit(
        token: String,
        version: String,
    ) = call(token, "/api/lms/studio/versions/$version/submit", emptyMap())

    @Test
    fun `launching a validation needs VALIDAR, and nobody validates their own draft`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val both = account("CREADOR", "REVISOR")
        val draft = draft(author)
        val own = draft(both)

        assertEquals(HttpStatus.FORBIDDEN, launch(author, draft.version).first)
        assertEquals(HttpStatus.FORBIDDEN, launch(account("CREADOR"), draft.version).first)
        assertEquals(HttpStatus.FORBIDDEN, launch(newStudent(), draft.version).first)
        assertEquals(HttpStatus.UNAUTHORIZED, launch(null, draft.version).first)
        assertEquals(HttpStatus.FORBIDDEN, launch(both, own.version).first)
        assertEquals(HttpStatus.NOT_FOUND, launch(reviewer, UUID.randomUUID().toString()).first)
        assertEquals(HttpStatus.BAD_REQUEST, launch(reviewer, "no-es-un-id").first)

        val (status, job) = launch(reviewer, draft.version)
        assertEquals(HttpStatus.ACCEPTED, status, job.toString())
        // la misma versión mientras el trabajo no termina: el mismo trabajo
        assertEquals(job!!["id"].asString(), launch(reviewer, draft.version).second!!["id"].asString())
        // el trabajo es de quien lo pidió
        assertEquals(HttpStatus.NOT_FOUND, call(account("REVISOR"), "/api/lms/ai/validations/jobs/${job["id"].asString()}").first)
        drain()
    }

    @Test
    fun `a generated draft with a defect per category reports them all and does not go to review until it is fixed`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val draft = draft(author, defective = true)

        // sin informe
        assertEquals(HttpStatus.CONFLICT, submit(author, draft.version).first)
        assertEquals(HttpStatus.NOT_FOUND, report(reviewer, draft.version).first)

        val job = validated(reviewer, draft.version)
        val view = ok(report(reviewer, draft.version))
        assertEquals(job["executionId"].asString(), view["executionId"].asString())
        assertTrue(view["current"].asBoolean())
        val body = view["report"]
        assertTrue(body["blocking"].asBoolean(), body.toString())
        val categories = body["problems"].values().map { it["category"].asString() }.toSet()
        assertEquals(ValidationCategory.entries.map { it.name }.toSet(), categories, body.toString())
        val kinds = body["problems"].values().map { it["kind"].asString() }.toSet()
        for (kind in listOf("QUESTION_WITHOUT_ANSWER", "INVALID_SOURCE_REF", "MISSING_OUTCOMES", "UNSAFE_LINK", "DUPLICATE_CONTENT", "IMAGE_WITHOUT_ALT")) {
            assertTrue(kind in kinds, "$kind in $kinds")
        }
        assertTrue(body["problemsBySeverity"]["BLOCKING"].asInt() > 0)

        // con errores bloqueantes, tampoco por la API de registros
        assertEquals(HttpStatus.CONFLICT, submit(author, draft.version).first)
        assertEquals(HttpStatus.CONFLICT.value(), patch(author, "version_curso", draft.version, mapOf("estado" to "EN_REVISION")))

        // el informe queda en AgentRun: paso VALIDATION, sin modelo, con la versión y sus validaciones
        val run = ok(call(reviewer, "/api/lms/ai/runs/${job["executionId"].asString()}"))
        assertEquals("VALIDATION", run["step"].asString())
        assertEquals("DETERMINISTIC", run["generator"].asString())
        assertEquals("SUCCEEDED", run["status"].asString())
        assertEquals(draft.version, run["versionId"].asString())
        assertEquals(body["contentHash"].asString(), run["validations"]["contentHash"].asString())
    }

    @Test
    fun `a clean generated draft goes to review with a current report, which the assigned reviewer reads`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val assigned = account("REVISOR")
        val draft = draft(author)

        validated(reviewer, draft.version)
        assertFalse(ok(report(reviewer, draft.version))["report"]["blocking"].asBoolean())

        // editado después de validar: el informe ya no es de este contenido
        assertEquals(HttpStatus.OK.value(), patch(author, "leccion", draft.lesson, mapOf("titulo" to "Otra")))
        assertFalse(ok(report(reviewer, draft.version))["current"].asBoolean())
        assertEquals(HttpStatus.CONFLICT, submit(author, draft.version).first)

        validated(reviewer, draft.version)
        assertEquals("EN_REVISION", ok(submit(author, draft.version))["status"].asString())

        assertEquals(HttpStatus.OK.value(), patch(admin, "version_curso", draft.version, mapOf("revisor_id" to userId(assigned))))

        // el revisor asignado y el autor lo leen; otra creadora no, y quien no ve la versión, tampoco existe para él
        val read = ok(report(assigned, draft.version))
        assertTrue(read["current"].asBoolean())
        assertFalse(read["report"]["blocking"].asBoolean())
        assertEquals(HttpStatus.OK, report(author, draft.version).first)
        assertEquals(HttpStatus.FORBIDDEN, report(account("CREADOR"), draft.version).first)
        assertEquals(HttpStatus.NOT_FOUND, report(newStudent(), draft.version).first)
    }

    @Test
    fun `a draft written by hand goes to review without a report`() {
        val author = account("CREADOR")
        val draft = draft(author, defective = true, generated = false)
        assertEquals("EN_REVISION", ok(submit(author, draft.version))["status"].asString())
    }
}
