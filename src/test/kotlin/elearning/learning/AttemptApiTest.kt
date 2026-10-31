package elearning.learning

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import java.util.UUID

// los intentos (ADR-0016 §2, RF-EVA-02/03/04): sobre una instantánea, con intentos y plazo impuestos por el servidor y sin
// soluciones mientras están abiertos
class AttemptApiTest : ClassroomApiTestSupport() {
    @Autowired
    private lateinit var db: DatabaseClient

    private fun start(
        token: String,
        enrollment: String,
        lesson: String,
    ) = call(token, "/api/lms/classroom/enrollments/$enrollment/lessons/$lesson/attempts", emptyMap())

    private fun started(
        token: String,
        enrollment: String,
        course: Course,
    ) = started(token, enrollment, course.assessment)

    private fun started(
        token: String,
        enrollment: String,
        lesson: String,
    ): JsonNode {
        val (status, body) = start(token, enrollment, lesson)
        assertTrue(status.is2xxSuccessful, body.toString())
        return body!!
    }

    private fun answer(
        question: String,
        options: List<String> = emptyList(),
        text: String? = null,
    ) = mapOf("questionId" to question, "optionIds" to options, "text" to text, "submissionId" to null)

    private fun question(
        attempt: JsonNode,
        id: String,
    ) = attempt["questions"].values().single { it["id"].asString() == id }

    private fun option(
        attempt: JsonNode,
        question: String,
        text: String,
    ) = question(attempt, question)["options"].values().single { it["text"].asString() == text }["id"].asString()

    // todo bien salvo la abierta, que queda sin responder o con el texto dado
    private fun rightAnswers(
        attempt: JsonNode,
        course: Course,
        open: String? = null,
    ): List<Map<String, Any?>> {
        val q = course.questions
        return listOf(
            answer(q.getValue("SINGLE"), listOf(option(attempt, q.getValue("SINGLE"), "Correcta"))),
            answer(q.getValue("MULTIPLE"), listOf(option(attempt, q.getValue("MULTIPLE"), "Sí A"), option(attempt, q.getValue("MULTIPLE"), "Sí B"))),
            answer(q.getValue("TRUE_FALSE"), listOf(option(attempt, q.getValue("TRUE_FALSE"), "Verdadero"))),
            answer(q.getValue("SHORT"), text = " PADRÓN "),
            answer(q.getValue("OPEN"), text = open),
        )
    }

    private fun save(
        token: String,
        attempt: String,
        answers: List<Map<String, Any?>>,
    ) = put(token, "/api/lms/classroom/attempts/$attempt/answers", mapOf("answers" to answers))

    private fun submit(
        token: String,
        attempt: String,
    ) = call(token, "/api/lms/classroom/attempts/$attempt/submit", emptyMap())

    @Test
    fun `an open attempt carries the questions and never the solutions`() {
        val course = course(withEvaluation = true)
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        val (status, attempt) = start(student, enrollment, course.assessment)

        assertEquals(HttpStatus.CREATED, status, attempt.toString())
        assertEquals(1, attempt!!["number"].asInt())
        assertEquals("OPEN", attempt["status"].asString())
        assertEquals(5, attempt["questions"].size())
        assertTrue(attempt["result"].isNull)
        assertEquals("SINGLE", question(attempt, course.questions.getValue("SINGLE"))["type"].asString())
        assertEquals(2, question(attempt, course.questions.getValue("OPEN"))["rubric"].size())
        val raw = attempt.toString().lowercase()
        for (leak in listOf("\"correct\"", "padr", "acceptedanswers", "\"solution\"")) {
            assertFalse(leak in raw, "$leak en $raw")
        }
        // retomar da el mismo intento
        val (again, same) = start(student, enrollment, course.assessment)
        assertEquals(HttpStatus.OK, again)
        assertEquals(attempt["id"].asString(), same!!["id"].asString())
    }

    @Test
    fun `submitting grades the closed questions and leaves the open one pending`() {
        val course = course(withEvaluation = true)
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val attempt = started(student, enrollment, course.assessment)
        val id = attempt["id"].asString()

        assertEquals(HttpStatus.OK, save(student, id, rightAnswers(attempt, course, open = "Por su fecha")).first)
        assertEquals(
            "Por su fecha",
            ok(call(student, "/api/lms/classroom/attempts/$id"))["answers"]
                .values()
                .single {
                    it["questionId"].asString() ==
                        course.questions.getValue("OPEN")
                }["text"]
                .asString(),
        )

        val done = ok(submit(student, id))

        assertEquals("SUBMITTED", done["status"].asString())
        assertEquals(7.0, done["result"]["score"].asDouble())
        assertEquals(10.0, done["result"]["maxScore"].asDouble())
        assertTrue(done["result"]["pending"].asBoolean())
        assertTrue(done["result"]["percent"].isNull)
        val single = done["result"]["questions"].values().single { it["questionId"].asString() == course.questions.getValue("SINGLE") }
        assertTrue(single["correct"].asBoolean())
        // le queda otro intento y aún no aprobó: las soluciones de este le servirían para aprobar el siguiente
        assertTrue(single["solution"].isNull, single.toString())
        // enviado, ya no cambia
        assertEquals(HttpStatus.CONFLICT, save(student, id, rightAnswers(attempt, course)).first)
        assertEquals(HttpStatus.CONFLICT, submit(student, id).first)
    }

    @Test
    fun `leaving the open question blank grades the attempt at once`() {
        val course = course(withEvaluation = true)
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val attempt = started(student, enrollment, course.assessment)
        save(student, attempt["id"].asString(), rightAnswers(attempt, course))

        val done = ok(submit(student, attempt["id"].asString()))

        assertEquals("GRADED", done["status"].asString())
        assertEquals(70, done["result"]["percent"].asInt())
        assertTrue(done["result"]["passed"].asBoolean())
    }

    @Test
    fun `the solutions show once the student passed or ran out of attempts`() {
        val course = course(withEvaluation = true)
        val failing = newStudent()
        val failingEnrollment = enroll(failing, course)["id"].asString()
        val first = started(failing, failingEnrollment, course)
        val singleId = course.questions.getValue("SINGLE")

        fun solutionOf(attempt: JsonNode) = attempt["result"]["questions"].values().single { it["questionId"].asString() == singleId }["solution"]

        assertTrue(solutionOf(ok(submit(failing, first["id"].asString()))).isNull, "falló y le queda un intento")
        val second = started(failing, failingEnrollment, course)
        assertFalse(solutionOf(ok(submit(failing, second["id"].asString()))).isNull, "agotó sus intentos")
        assertFalse(solutionOf(ok(call(failing, "/api/lms/classroom/attempts/${first["id"].asString()}"))).isNull, "también en el anterior")

        val passing = newStudent()
        val attempt = started(passing, enroll(passing, course)["id"].asString(), course)
        save(passing, attempt["id"].asString(), rightAnswers(attempt, course))
        assertFalse(solutionOf(ok(submit(passing, attempt["id"].asString()))).isNull, "aprobó")
    }

    @Test
    fun `attempts run out`() {
        val course = course(withEvaluation = true)
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        repeat(2) { i ->
            val attempt = started(student, enrollment, course.assessment)
            assertEquals(i + 1, attempt["number"].asInt())
            ok(submit(student, attempt["id"].asString()))
        }

        assertEquals(HttpStatus.CONFLICT, start(student, enrollment, course.assessment).first)
    }

    @Test
    fun `starting after the last attempt expired closes it and explains there are none left`() {
        val course = course(withEvaluation = true)
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        ok(submit(student, started(student, enrollment, course)["id"].asString()))
        val last = started(student, enrollment, course)["id"].asString()
        runBlocking {
            db
                .sql(
                    "UPDATE lms.intento SET vence_en = now() - interval '1 minute' WHERE id = :id",
                ).bind("id", UUID.fromString(last))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }

        assertEquals(HttpStatus.CONFLICT, start(student, enrollment, course.assessment).first)

        val status =
            runBlocking {
                db
                    .sql("SELECT estado FROM lms.intento WHERE id = :id")
                    .bind("id", UUID.fromString(last))
                    .map {
                        row,
                        _,
                        ->
                        row.get("estado", String::class.java)!!
                    }.one()
                    .awaitSingle()
            }
        assertEquals("GRADED", status, "el cierre no se pierde con el 409")
    }

    @Test
    fun `simultaneous starts open a single attempt`() {
        val course = course(withEvaluation = true)
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val pool =
            java.util.concurrent.Executors
                .newFixedThreadPool(5)
        val ids =
            try {
                pool
                    .invokeAll(
                        List(5) { java.util.concurrent.Callable { start(student, enrollment, course.assessment).second!!["id"].asString() } },
                    ).map { it.get() }
            } finally {
                pool.shutdown()
            }

        assertEquals(1, ids.toSet().size, ids.toString())
    }

    @Test
    fun `past its deadline the server refuses the attempt and keeps what was saved`() {
        val course = course(withEvaluation = true)
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val attempt = started(student, enrollment, course.assessment)
        val id = attempt["id"].asString()
        save(student, id, rightAnswers(attempt, course))
        runBlocking {
            db
                .sql("UPDATE lms.intento SET vence_en = now() - interval '1 minute' WHERE id = :id")
                .bind("id", UUID.fromString(id))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }

        assertEquals(HttpStatus.CONFLICT, save(student, id, emptyList()).first)
        assertEquals(HttpStatus.CONFLICT, submit(student, id).first)

        val closed = ok(call(student, "/api/lms/classroom/attempts/$id"))
        assertEquals("GRADED", closed["status"].asString())
        assertEquals(70, closed["result"]["percent"].asInt())
    }

    @Test
    fun `an answer must fit its question`() {
        val course = course(withEvaluation = true)
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val attempt = started(student, enrollment, course.assessment)
        val id = attempt["id"].asString()
        val q = course.questions

        val invalid =
            listOf(
                answer(UUID.randomUUID().toString()),
                answer(q.getValue("SINGLE"), listOf(UUID.randomUUID().toString())),
                answer(q.getValue("SINGLE"), question(attempt, q.getValue("SINGLE"))["options"].values().map { it["id"].asString() }),
                answer(q.getValue("OPEN"), text = "x".repeat(20_001)),
            )
        for (one in invalid) {
            assertEquals(HttpStatus.BAD_REQUEST, save(student, id, listOf(one)).first, one.toString())
        }
    }

    @Test
    fun `nobody touches someone else's attempt, and a lesson without evaluation has none`() {
        val course = course(withEvaluation = true)
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val other = newStudent()
        val otherEnrollment = enroll(other, course)["id"].asString()
        val id = started(student, enrollment, course.assessment)["id"].asString()

        assertEquals(HttpStatus.NOT_FOUND, call(other, "/api/lms/classroom/attempts/$id").first)
        assertEquals(HttpStatus.NOT_FOUND, save(other, id, emptyList()).first)
        assertEquals(HttpStatus.NOT_FOUND, submit(other, id).first)
        assertEquals(HttpStatus.NOT_FOUND, start(other, enrollment, course.assessment).first)
        assertEquals(HttpStatus.NOT_FOUND, call(admin, "/api/lms/classroom/attempts/$id").first)
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, "/api/lms/classroom/attempts/$id").first)
        assertEquals(HttpStatus.NOT_FOUND, start(other, otherEnrollment, course().assessment).first, "lección de otra versión")
        assertEquals(HttpStatus.CONFLICT, start(other, otherEnrollment, course.reading).first, "lección sin evaluación")
    }

    @Test
    fun `an attempt stays on its snapshot whatever happens later to the evaluation`() {
        val course = course(withEvaluation = true)
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val attempt = started(student, enrollment, course.assessment)
        val id = attempt["id"].asString()
        // lo publicado no se puede editar: se simula un cambio directo en la copia para comprobar que el intento no la mira
        runBlocking {
            db
                .sql(
                    "UPDATE lms.contenido_evaluacion SET datos = jsonb_set(datos, '{enunciado}', '\"Cambiada\"') WHERE objeto = 'pregunta' AND registro_id = :id",
                ).bind("id", UUID.fromString(course.questions.getValue("SINGLE")))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }

        val read = ok(call(student, "/api/lms/classroom/attempts/$id"))

        assertEquals("Pregunta 1", question(read, course.questions.getValue("SINGLE"))["prompt"].asString())
    }
}
