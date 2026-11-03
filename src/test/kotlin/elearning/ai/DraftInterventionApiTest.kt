package elearning.ai

import elearning.jobs.JobRunner
import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import wasichai.core.common.ForbiddenException
import wasichai.core.data.RecordRequest
import java.util.UUID

// RF-IA-03 y RF-IA-08 (E34.3, #288): aceptar (aprobar en la bandeja), editar o rechazar un borrador GENERADO añade una
// intervención a la ejecución LESSON_DRAFT que lo generó, en la transacción del cambio; las intervenciones solo se añaden;
// el estudio y la bandeja dicen su generator; aprobar sigue siendo de otra persona y ningún trabajo de IA aprueba ni publica
class DraftInterventionApiTest : ClassroomApiTestSupport() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var log: AgentExecutionLog

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var store: DraftValidationStore

    private val fixtures by lazy { DraftFixtures(records, log) }

    private fun account(vararg roles: String): String {
        val email = "${uniqueName("int")}@demo.wasichai-learning.test"
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

    private fun draft(
        author: String,
        generated: Boolean = true,
    ) = fixtures.draft(uniqueName("INT-").uppercase(), userId(author), generated = generated)

    // lanza la validación, mueve la cola y espera a que termine
    private fun validated(
        token: String,
        version: String,
    ) {
        val (status, body) = call(token, "/api/lms/ai/validations", mapOf("versionId" to version))
        assertEquals(HttpStatus.ACCEPTED, status, body.toString())
        runBlocking { while (runner.runOnce() > 0) Unit }
        val job = ok(call(token, "/api/lms/ai/validations/jobs/${body!!["id"].asString()}"))
        assertEquals("DONE", job["status"].asString(), job.toString())
    }

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

    // el generator de una versión del estudio o de la bandeja; null si no viene o viene nulo
    private fun JsonNode.generator(): String? = get("generator")?.takeUnless { it.isNull }?.asString()

    private fun interventions(run: UUID): List<Intervention> = runBlocking { log.find(ORG, run)!!.interventions }

    private fun decide(
        token: String,
        version: String,
        decision: String,
        reason: String? = null,
    ) = call(token, "/api/lms/review/versions/$version/decision", mapOf("decision" to decision, "reason" to reason))

    private fun state(version: String): String =
        runBlocking { records.asPlatform(ORG) { records.get("version_curso", UUID.fromString(version)).attributes["estado"].toString() } }

    private fun sql(statement: String) =
        runBlocking {
            db
                .sql(statement)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }

    private fun decisions(version: String): Long =
        runBlocking {
            db
                .sql("SELECT count(*) AS n FROM lms.decision_revision WHERE version_curso_id = :version")
                .bind("version", UUID.fromString(version))
                .map { row, _ -> row.get("n", Number::class.java)!!.toLong() }
                .one()
                .awaitSingle()
        }

    // un borrador generado, validado y enviado a revisión, con la ejecución que lo generó
    private fun inReview(
        author: String,
        reviewer: String,
        generator: Generator = Generator.DETERMINISTIC,
    ): Pair<DraftFixtures.Draft, UUID> {
        val draft = draft(author, generated = false)
        val run = fixtures.seedGenerated(draft, UUID.fromString(userId(author)), generator)
        validated(reviewer, draft.version)
        assertEquals("EN_REVISION", ok(submit(author, draft.version))["status"].asString())
        return draft to run
    }

    @Test
    fun `approving a generated draft in the inbox adds ACCEPTED to its run, in the same transaction as the decision`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val (draft, run) = inReview(author, reviewer)
        assertTrue(interventions(run).isEmpty(), "validar y enviar a revisión no intervienen")

        // si la intervención no se escribe, tampoco la decisión ni el cambio de estado: es la misma transacción
        val failing = "lms.falla_intervencion_${UUID.randomUUID().toString().replace("-", "")}"
        sql("CREATE FUNCTION $failing() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'sin intervenciones'; END; $$")
        try {
            sql("CREATE TRIGGER falla_intervencion BEFORE UPDATE ON lms.ejecucion_agente FOR EACH ROW WHEN (NEW.id = '$run') EXECUTE FUNCTION $failing()")
            val (status, _) = decide(reviewer, draft.version, "APROBAR")
            assertFalse(status.is2xxSuccessful, "$status")
            assertEquals("EN_REVISION", state(draft.version))
            assertEquals(0, decisions(draft.version))
        } finally {
            sql("DROP TRIGGER IF EXISTS falla_intervencion ON lms.ejecucion_agente")
            sql("DROP FUNCTION IF EXISTS $failing()")
        }
        assertTrue(interventions(run).isEmpty())

        assertEquals("APROBADA", ok(decide(reviewer, draft.version, "APROBAR"))["status"].asString())
        val accepted = interventions(run).single()
        assertEquals(InterventionKind.ACCEPTED, accepted.kind)
        assertEquals(UUID.fromString(userId(reviewer)), accepted.userId)
        assertEquals(1, decisions(draft.version))
    }

    @Test
    fun `asking for changes leaves nothing, editing adds EDITED and rejecting adds REJECTED with its reason`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val (draft, run) = inReview(author, reviewer)

        assertEquals("BORRADOR", ok(decide(reviewer, draft.version, "PEDIR_CAMBIOS", "Falta un ejemplo"))["status"].asString())
        assertTrue(interventions(run).isEmpty(), "pedir cambios no es aceptar, editar ni rechazar")

        // el autor corrige la lección y la versión: una intervención por guardado, con el registro y los campos, no el texto
        assertEquals(HttpStatus.OK.value(), patch(author, "leccion", draft.lesson, mapOf("titulo" to "Consultar el padrón paso a paso")))
        assertEquals(HttpStatus.OK.value(), patch(author, "version_curso", draft.version, mapOf("objetivos" to "Usar y consultar el padrón")))
        // guardar lo mismo no cambia nada
        assertEquals(HttpStatus.OK.value(), patch(author, "leccion", draft.lesson, mapOf("titulo" to "Consultar el padrón paso a paso")))
        val edited = interventions(run)
        assertEquals(listOf(InterventionKind.EDITED, InterventionKind.EDITED), edited.map { it.kind })
        assertTrue(edited.all { it.userId == UUID.fromString(userId(author)) })
        assertEquals("leccion ${draft.lesson}: titulo", edited[0].note)
        assertEquals("version_curso ${draft.version}: objetivos", edited[1].note)
        assertFalse(edited.any { it.note.orEmpty().contains("padrón") }, "la nota no copia el contenido")

        validated(reviewer, draft.version)
        assertEquals("EN_REVISION", ok(submit(author, draft.version))["status"].asString())
        assertEquals("RETIRADA", ok(decide(reviewer, draft.version, "RECHAZAR", "No sigue el manual"))["status"].asString())
        val all = interventions(run)
        assertEquals(listOf(InterventionKind.EDITED, InterventionKind.EDITED, InterventionKind.REJECTED), all.map { it.kind })
        assertEquals(UUID.fromString(userId(reviewer)), all.last().userId)
        assertTrue(
            all
                .last()
                .note
                .orEmpty()
                .contains("No sigue el manual"),
            all.last().note,
        )
    }

    @Test
    fun `a draft written by hand has no generator and leaves no intervention anywhere`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val draft = draft(author, generated = false)
        val other = draft(account("CREADOR"))
        val before = runBlocking { log.find(ORG, store.generatingRun(ORG, UUID.fromString(other.version))!!)!!.interventions.size }

        assertEquals(HttpStatus.OK.value(), patch(author, "leccion", draft.lesson, mapOf("titulo" to "A mano")))
        assertNull(ok(call(author, "/api/lms/studio/versions/${draft.version}")).generator())
        assertEquals("EN_REVISION", ok(submit(author, draft.version))["status"].asString())
        val inbox = ok(call(reviewer, "/api/lms/review/versions")).values().single { it["id"].asString() == draft.version }
        assertNull(inbox.generator())
        assertEquals("APROBADA", ok(decide(reviewer, draft.version, "APROBAR"))["status"].asString())
        assertNull(runBlocking { store.generatingRun(ORG, UUID.fromString(draft.version)) })
        assertEquals(before, runBlocking { log.find(ORG, store.generatingRun(ORG, UUID.fromString(other.version))!!)!!.interventions.size })
    }

    @Test
    fun `the studio and the inbox say which generator made the draft`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val (model, _) = inReview(author, reviewer, Generator.MODEL)
        val (double, _) = inReview(author, reviewer, Generator.DETERMINISTIC)

        val mine = ok(call(author, "/api/lms/studio/versions")).values().associateBy { it["id"].asString() }
        assertEquals("MODEL", mine.getValue(model.version).generator())
        assertEquals("DETERMINISTIC", mine.getValue(double.version).generator())
        assertEquals("DETERMINISTIC", ok(call(author, "/api/lms/studio/versions/${double.version}")).generator())

        val inbox = ok(call(reviewer, "/api/lms/review/versions")).values().associateBy { it["id"].asString() }
        assertEquals("MODEL", inbox.getValue(model.version).generator())
        assertEquals("DETERMINISTIC", inbox.getValue(double.version).generator())
        assertEquals("DETERMINISTIC", ok(call(reviewer, "/api/lms/review/versions/${double.version}")).generator())
    }

    @Test
    fun `interventions are only appended: no api changes or deletes them`() {
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val (draft, run) = inReview(author, reviewer)
        assertEquals("APROBADA", ok(decide(reviewer, draft.version, "APROBAR"))["status"].asString())
        val kept = interventions(run)
        assertEquals(1, kept.size)

        for (method in listOf(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            for (path in listOf("/api/lms/ai/runs/$run", "/api/lms/ai/runs/$run/interventions", "/api/objects/ejecucion_agente/records/$run")) {
                val status =
                    client
                        .method(method)
                        .uri(path)
                        .header("Authorization", admin)
                        .bodyValue(mapOf("interventions" to emptyList<Any>(), "attributes" to mapOf("intervenciones" to "[]")))
                        .exchange()
                        .returnResult(String::class.java)
                        .status
                assertTrue(status.is4xxClientError, "$method $path: $status")
            }
        }
        // ni por SQL directo: el disparador de V17
        assertThrows<Exception> { sql("UPDATE lms.ejecucion_agente SET intervenciones = '[]' WHERE id = '$run'") }
        assertThrows<Exception> { sql("DELETE FROM lms.ejecucion_agente WHERE id = '$run'") }
        assertEquals(kept, interventions(run))
    }

    @Test
    fun `approving still needs someone other than the author, and no ai job approves or publishes`() {
        val both = account("CREADOR", "REVISOR")
        val reviewer = account("REVISOR")
        val (draft, run) = inReview(both, reviewer)

        assertEquals(HttpStatus.FORBIDDEN, decide(both, draft.version, "APROBAR").first)
        assertEquals("EN_REVISION", state(draft.version))

        // un trabajo de IA corre como la plataforma, que la guarda editorial deja pasar: AiWorkGuard no le deja aprobar ni
        // publicar, ni crear una versión ya aprobada
        val id = UUID.fromString(draft.version)
        for (attributes in listOf(mapOf("estado" to "APROBADA"), mapOf("estado" to "PUBLICADA"), mapOf("publicada_en" to "2026-11-03T10:00:00Z"))) {
            assertThrows<ForbiddenException>("$attributes") {
                runBlocking { withContext(AiWork()) { records.asPlatform(ORG) { records.patch("version_curso", id, RecordRequest(attributes), null, null) } } }
            }
        }
        assertThrows<ForbiddenException> {
            runBlocking {
                withContext(AiWork()) {
                    records.asPlatform(ORG) {
                        val course = records.get("version_curso", id).attributes["curso"]
                        val courseId = if (course is Map<*, *>) course["id"] else course
                        records.create(
                            "version_curso",
                            RecordRequest(
                                mapOf(
                                    "curso" to courseId,
                                    "numero" to 2,
                                    "estado" to "APROBADA",
                                    "modalidad" to "ASINCRONA",
                                    "semanas" to 1,
                                    "sistema_credito" to "NINGUNO",
                                    "contenido_ilustrativo" to true,
                                    "autor_id" to userId(both),
                                ),
                            ),
                        )
                    }
                }
            }
        }
        assertEquals("EN_REVISION", state(draft.version))
        assertTrue(interventions(run).isEmpty(), "ni un trabajo de IA ni el autor intervienen sobre lo que se generó")

        assertEquals("APROBADA", ok(decide(reviewer, draft.version, "APROBAR"))["status"].asString())
        assertEquals(listOf(InterventionKind.ACCEPTED), interventions(run).map { it.kind })
    }
}
