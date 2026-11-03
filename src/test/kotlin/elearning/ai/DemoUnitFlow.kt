package elearning.ai

import elearning.jobs.JobRunner
import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

// la unidad de demostración de extremo a extremo (E38.1, #300), el cierre de la Fase 5: brief → análisis → diseño → borrador →
// validación → corrección → revisión → aprobación por una persona distinta del autor → publicación, y la unidad en el aula de
// un estudiante matriculado. Todo por la API y con las cuentas de cada papel, como lo haría la interfaz; la cola se mueve a mano.
// La recorren DemoUnitApiTest (sin claves, con el doble determinista) y DemoUnitProviderApiTest (Embabel contra el doble HTTP
// de Anthropic y de OpenAI); cada una comprueba aparte lo propio de su generador.
//
// Lo que deja ver de la canalización real: el borrador trae las lecturas y las evaluaciones del diseño (#367), así que la
// primera validación ya no bloquea; la corrección de la autora (paso 8) es una edición menor que obliga a validar otra vez,
// porque el informe deja de corresponder al borrador. Y las versiones de recurso de las lecturas nacen en BORRADOR y pasan
// su propio flujo (enviar, aprobar, publicar) antes de enviar la versión del curso a revisión (ADR-0041), para que lo revisado
// y lo publicado no cambien después
internal class DemoUnitFlow(
    private val client: WebTestClient,
    private val json: JsonMapper,
    private val runner: JobRunner,
    private val records: RecordService,
    private val db: DatabaseClient,
    private val admin: String,
    // una cuenta nueva de la demo con esos roles y su token (POST /api/users como administrador)
    private val account: (List<String>) -> String,
    // un estudiante nuevo con los términos de uso aceptados
    private val student: () -> String,
    private val uniqueName: (String) -> String,
    // antes de cada llamada al modelo, la respuesta del doble HTTP para ese paso (el sufijo de su archivo); sin proveedor, nada
    private val reply: (String) -> Unit,
) {
    class Result(
        val course: String,
        val version: String,
        // la ejecución de cada paso de IA, por paso: SOURCE_ANALYSIS, COURSE_DESIGN, LESSON_DRAFT
        val runs: Map<String, JsonNode>,
        // los dos informes de validación: antes y después de la corrección menor de la autora
        val reports: List<JsonNode>,
    )

    // los pasos de IA y las validaciones que otra clase dejó pendientes cuentan para la concurrencia y el tope por persona
    fun cancelLeftovers() =
        runBlocking {
            db
                .sql(
                    "UPDATE lms.trabajo SET estado = 'CANCELLED', reclamado_en = NULL, terminado_en = now() " +
                        "WHERE (tipo LIKE 'ai.%' OR tipo = '${DraftValidations.JOB_TYPE}') AND estado IN ('QUEUED', 'RUNNING')",
                ).fetch()
                .rowsUpdated()
                .awaitSingle()
        }

    // generator: el que debe quedar en las ejecuciones de los pasos que generan (MODEL o DETERMINISTIC); opinion: el estado de
    // la opinión de consistencia en el informe (DONE con proveedor, SKIPPED sin él)
    fun run(
        generator: String,
        opinion: String,
    ): Result {
        val author = account(listOf("CREADOR", "REVISOR"))
        val reviewer = account(listOf("REVISOR"))
        val authorId = userId(author)
        val reviewerId = userId(reviewer)

        // 1. brief: el curso y su brief LISTO, de la autora
        val slug = uniqueName("demo-").lowercase()
        val course = created(author, "curso", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Administración de anuncios"))
        val brief = created(author, "brief", SourceAnalysisFixtures.brief(course))

        // 2-4. análisis, diseño y borrador: cada paso parte de la ejecución SUCCEEDED del anterior
        val analysis = step(author, "/api/lms/ai/source-analysis", mapOf("briefId" to brief), "source-analysis.json", "SOURCE_ANALYSIS", generator)
        val design =
            step(author, "/api/lms/ai/course-design", mapOf("executionId" to analysis["id"].asString()), "course-design.json", "COURSE_DESIGN", generator)
        val draft = step(author, "/api/lms/ai/lesson-draft", mapOf("executionId" to design["id"].asString()), "lesson-draft.json", "LESSON_DRAFT", generator)
        val runs = mapOf("SOURCE_ANALYSIS" to analysis, "COURSE_DESIGN" to design, "LESSON_DRAFT" to draft)
        val version = draft["output"]["versionId"].asString()
        try {
            val drafted = attributes(author, "version_curso", version)
            assertEquals("BORRADOR", drafted["estado"].asString())
            assertEquals(authorId, drafted["autor_id"].asString())
            assertEquals(LessonTemplate.VERSION, drafted["plantilla_ia"].asString())
            val units = list(author, "unidad", "version_curso", version).sortedBy { it["attributes"]["orden"].asInt() }.map { it["id"].asString() }
            assertTrue(units.isNotEmpty())
            val lessons = units.associateWith { unit -> list(author, "leccion", "unidad", unit).sortedBy { it["attributes"]["orden"].asInt() } }
            val all = lessons.values.flatten()
            val readings = all.filter { it["attributes"]["tipo"].asString() == "LECTURA" }.map { it["id"].asString() }
            assertTrue(readings.isNotEmpty())
            // el borrador trae también las evaluaciones del diseño (#367): lecciones EVALUACION con su evaluación y sus preguntas
            val evaluationLessons = all.filter { it["attributes"]["tipo"].asString() == "EVALUACION" }.map { it["id"].asString() }
            assertTrue(evaluationLessons.isNotEmpty(), all.toString())
            assertEquals(all.size, readings.size + evaluationLessons.size, all.toString())
            val evaluations = evaluationLessons.map { lesson -> list(author, "evaluacion", "leccion", lesson).single()["id"].asString() }
            for (evaluation in evaluations) assertTrue(list(author, "pregunta", "evaluacion", evaluation).isNotEmpty())
            // es generada (docs/ai §13): sin informe no va a revisión
            assertEquals(HttpStatus.CONFLICT, submit(author, version).first)

            // 6. validación: la lanza el revisor; la autora no valida lo suyo aunque tenga VALIDAR (separación de deberes). Con
            // las evaluaciones del borrador, ya no bloquea con NO_ASSESSMENT
            assertEquals(HttpStatus.FORBIDDEN, call(HttpMethod.POST, author, "/api/lms/ai/validations", mapOf("versionId" to version)).first)
            val first = validated(reviewer, version, opinion)
            assertFalse(first["report"]["blocking"].asBoolean(), first.toString())
            assertEquals(emptySet<String>(), blocking(first), first.toString())
            assertFalse(first["report"]["problems"].values().any { it["kind"].asString() == "NO_ASSESSMENT" }, first.toString())
            assertEquals(evaluations.size, first["report"]["evaluations"].asInt(), first.toString())

            // 8. corrección: la autora retoca las instrucciones de una evaluación; el informe deja de corresponder al borrador
            // (otro hash) y no entra en revisión hasta validarlo otra vez
            val edit = mapOf("instrucciones" to "Responde sin consultar el manual ni tus notas")
            assertEquals(HttpStatus.OK, patch(author, "evaluacion", evaluations.first(), edit))
            assertEquals(HttpStatus.CONFLICT, submit(author, version).first)
            val second = validated(reviewer, version, opinion)
            assertFalse(second["report"]["blocking"].asBoolean(), second.toString())
            assertTrue(second["current"].asBoolean())
            assertEquals(evaluations.size, second["report"]["evaluations"].asInt(), second.toString())
            // las lecturas generadas pasan antes su propio flujo (ADR-0041): con alguna sin publicar, el curso no va a revisión
            assertEquals(HttpStatus.CONFLICT, submit(author, version).first)
            // la autora las envía, el revisor las aprueba y quien publica las publica
            val resources = readings.map { lesson -> list(author, "recurso_leccion", "leccion", lesson).single()["attributes"]["version_recurso"].asString() }
            for (resource in resources) {
                assertEquals("BORRADOR", attributes(author, "version_recurso", resource)["estado"].asString())
                assertEquals(HttpStatus.OK, patch(author, "version_recurso", resource, mapOf("estado" to "EN_REVISION")))
                assertEquals(HttpStatus.FORBIDDEN, patch(author, "version_recurso", resource, mapOf("estado" to "APROBADA")))
                assertEquals(HttpStatus.OK, patch(reviewer, "version_recurso", resource, mapOf("estado" to "APROBADA")))
                assertEquals(HttpStatus.OK, patch(admin, "version_recurso", resource, mapOf("estado" to "PUBLICADA")))
            }
            assertEquals("EN_REVISION", ok(submit(author, version))["status"].asString())

            // 7 y 9. revisión y aprobación: nunca la autora (403 aunque tenga APROBAR), sí el revisor, en la bandeja
            assertEquals(HttpStatus.FORBIDDEN, decide(author, version).first)
            val inbox = ok(call(HttpMethod.GET, reviewer, "/api/lms/review/versions")).values().single { it["id"].asString() == version }
            assertEquals(generator, inbox["generator"].asString(), inbox.toString())
            assertEquals("APROBADA", ok(decide(reviewer, version))["status"].asString())
            // aceptar el borrador queda en la ejecución que lo generó (RF-IA-03), a nombre del revisor
            val accepted = ok(call(HttpMethod.GET, author, "/api/lms/ai/runs/${draft["id"].asString()}"))["interventions"].values()
            assertTrue(accepted.any { it["kind"].asString() == "ACCEPTED" && it["userId"].asString() == reviewerId }, accepted.toString())

            // 10. publicación: solo quien tiene PUBLICAR; ni la autora ni el revisor
            for (token in listOf(author, reviewer)) assertEquals(HttpStatus.FORBIDDEN, publish(token, version).first)
            assertEquals("PUBLICADA", ok(publish(admin, version))["status"].asString())
            val published = attributes(admin, "version_curso", version)
            assertEquals("PUBLICADA", published["estado"].asString())
            assertTrue(published["publicada_en"]?.let { !it.isNull } == true, published.toString())

            // la unidad en el aula: una edición abierta, un estudiante matriculado que la lee entera y descarga su lectura
            val offering = offering(version)
            val learner = student()
            val (enrolled, enrollment) = call(HttpMethod.POST, learner, "/api/lms/enrollments", mapOf("offering" to offering))
            assertTrue(enrolled.is2xxSuccessful, enrollment.toString())
            val classroom = ok(call(HttpMethod.GET, learner, "/api/lms/classroom/enrollments/${enrollment!!["id"].asString()}"))
            val seen = classroom["units"].values().toList()
            assertEquals(units, seen.map { it["id"].asString() })
            val expected = lessons.mapValues { (_, l) -> l.map { it["id"].asString() } }
            assertEquals(expected, seen.associate { u -> u["id"].asString() to u["lessons"].values().map { it["id"].asString() } })
            val shown = seen.flatMap { it["lessons"].values() }.associateBy { it["id"].asString() }
            for (lesson in evaluationLessons) assertNotNull(shown.getValue(lesson)["assessment"]?.takeUnless { it.isNull }, shown.getValue(lesson).toString())
            for (lesson in readings) {
                val resource = shown.getValue(lesson)["resources"].values().single()
                assertTrue(resource["id"].asString() in resources, resource.toString())
                val (status, html) = download(learner, resource["file"]["url"].asString())
                assertEquals(HttpStatus.OK, status)
                assertTrue(html.contains(LessonDraftFixtures.MARK), html)
                assertFalse(html.contains("<script"), html)
            }
            return Result(course, version, runs, listOf(first, second))
        } finally {
            // fuera del catálogo de las demás pruebas
            runBlocking {
                records.asPlatform(ClassroomApiTestSupport.ORG) {
                    val state = records.get("version_curso", UUID.fromString(version)).attributes["estado"]?.toString()
                    if (state == "PUBLICADA") records.patch("version_curso", UUID.fromString(version), RecordRequest(mapOf("estado" to "RETIRADA")), null, null)
                }
            }
        }
    }

    // encola un paso, mueve la cola y devuelve su ejecución SUCCEEDED, con el generador que toca
    private fun step(
        token: String,
        path: String,
        body: Map<String, Any?>,
        replyFile: String,
        name: String,
        generator: String,
    ): JsonNode {
        reply(replyFile)
        val (status, job) = call(HttpMethod.POST, token, path, body)
        assertEquals(HttpStatus.ACCEPTED, status, job.toString())
        drain()
        val done = ok(call(HttpMethod.GET, token, "/api/lms/ai/jobs/${job!!["id"].asString()}"))
        assertEquals("DONE", done["status"].asString(), done.toString())
        val run = ok(call(HttpMethod.GET, token, "/api/lms/ai/runs/${done["executionId"].asString()}"))
        assertEquals(name, run["step"].asString())
        assertEquals("SUCCEEDED", run["status"].asString(), run.toString())
        assertEquals(generator, run["generator"].asString())
        return run
    }

    // lanza la validación, mueve la cola (la validación y, con proveedor, la opinión) y devuelve el informe
    private fun validated(
        reviewer: String,
        version: String,
        opinion: String,
    ): JsonNode {
        reply("consistency-approved.json")
        val (status, job) = call(HttpMethod.POST, reviewer, "/api/lms/ai/validations", mapOf("versionId" to version))
        assertEquals(HttpStatus.ACCEPTED, status, job.toString())
        drain()
        val done = ok(call(HttpMethod.GET, reviewer, "/api/lms/ai/validations/jobs/${job!!["id"].asString()}"))
        assertEquals("DONE", done["status"].asString(), done.toString())
        val view = ok(call(HttpMethod.GET, reviewer, "/api/lms/ai/validations?versionId=$version"))
        // la opinión es solo eso: nunca bloquea ni aprueba
        assertEquals("OPINION", view["opinion"]["kind"].asString(), view.toString())
        assertFalse(view["opinion"]["blocking"].asBoolean())
        assertEquals(opinion, view["opinion"]["status"].asString(), view["opinion"].toString())
        return view
    }

    private fun blocking(view: JsonNode): Set<String> =
        view["report"]["problems"]
            .values()
            .filter { it["severity"].asString() == "BLOCKING" }
            .map { it["kind"].asString() }
            .toSet()

    // una edición con la inscripción abierta, de la versión publicada; la crea quien publica
    private fun offering(version: String): String {
        val code = uniqueName("DEMO-").uppercase()
        val today = LocalDate.now(ZoneId.of("America/Lima"))
        created(
            admin,
            "edicion_curso",
            mapOf(
                "codigo" to code,
                "version_curso" to version,
                "estado" to "INSCRIPCION_ABIERTA",
                "fecha_inicio" to today.toString(),
                "fecha_fin" to today.plusDays(30).toString(),
                "zona_horaria" to "America/Lima",
                "inscripcion_desde" to today.minusDays(1).toString(),
                "inscripcion_hasta" to today.plusDays(5).toString(),
            ),
        )
        return code
    }

    private fun drain() = runBlocking { while (runner.runOnce() > 0) Unit }

    private fun submit(
        token: String,
        version: String,
    ) = call(HttpMethod.POST, token, "/api/lms/studio/versions/$version/submit", emptyMap())

    private fun decide(
        token: String,
        version: String,
    ) = call(HttpMethod.POST, token, "/api/lms/review/versions/$version/decision", mapOf("decision" to "APROBAR"))

    private fun publish(
        token: String,
        version: String,
    ) = call(HttpMethod.POST, token, "/api/lms/review/versions/$version/publish", emptyMap())

    private fun userId(token: String): String = ok(call(HttpMethod.GET, token, "/api/auth/me"))["userId"].asString()

    private fun created(
        token: String,
        objectName: String,
        attributes: Map<String, Any?>,
    ): String {
        val (status, body) = call(HttpMethod.POST, token, "/api/objects/$objectName/records", mapOf("attributes" to attributes))
        assertEquals(HttpStatus.CREATED, status, "$objectName: $body")
        return body!!["id"].asString()
    }

    private fun attributes(
        token: String,
        objectName: String,
        id: String,
    ): JsonNode = ok(call(HttpMethod.GET, token, "/api/objects/$objectName/records/$id"))["attributes"]

    private fun list(
        token: String,
        objectName: String,
        field: String,
        value: String,
    ): List<JsonNode> = ok(call(HttpMethod.GET, token, "/api/objects/$objectName/records?$field=$value&size=200"))["content"].values().toList()

    private fun patch(
        token: String,
        objectName: String,
        id: String,
        attributes: Map<String, Any?>,
    ): HttpStatus = call(HttpMethod.PATCH, token, "/api/objects/$objectName/records/$id", mapOf("attributes" to attributes)).first

    private fun call(
        method: HttpMethod,
        token: String,
        path: String,
        body: Map<String, Any?>? = null,
    ): Pair<HttpStatus, JsonNode?> {
        val spec = client.method(method).uri(path).header("Authorization", token)
        val r = (if (body == null) spec else spec.bodyValue(body)).exchange().expectBody(String::class.java).returnResult()
        return HttpStatus.valueOf(r.status.value()) to r.responseBody?.let { runCatching { json.readTree(it) }.getOrNull() }
    }

    private fun download(
        token: String,
        path: String,
    ): Pair<HttpStatus, String> {
        val r =
            client
                .get()
                .uri(path)
                .header("Authorization", token)
                .exchange()
                .expectBody(ByteArray::class.java)
                .returnResult()
        return HttpStatus.valueOf(r.status.value()) to String(r.responseBody ?: ByteArray(0))
    }

    private fun ok(result: Pair<HttpStatus, JsonNode?>): JsonNode {
        assertEquals(HttpStatus.OK, result.first, result.second.toString())
        return result.second!!
    }
}
