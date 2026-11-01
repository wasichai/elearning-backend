package elearning.learning

import elearning.assessment.AttemptService
import elearning.assessment.ExpiredAttemptSweep
import elearning.jobs.JobRunner
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

// L4 de #183: un intento vencido se cierra con lo guardado y con su plazo como envío, lo toque o no el estudiante. el
// abandonado lo cierra un barrido periódico en la cola persistente (el @Scheduled está apagado: se da la vuelta a mano)
class ExpiredAttemptSweepApiTest : ClassroomApiTestSupport() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var sweep: ExpiredAttemptSweep

    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var attempts: AttemptService

    private inner class Scene {
        val course = course(withEvaluation = true)
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val attempt: JsonNode =
            call(student, "/api/lms/classroom/enrollments/$enrollment/lessons/${course.assessment}/attempts", emptyMap()).let { (status, body) ->
                assertEquals(HttpStatus.CREATED, status, body.toString())
                body!!
            }
        val id: String = attempt["id"].asString()

        private fun option(
            question: String,
            text: String,
        ) = attempt["questions"]
            .values()
            .single { it["id"].asString() == question }["options"]
            .values()
            .single { it["text"].asString() == text }["id"]
            .asString()

        // todo bien: 7 de 10 sin la abierta, que aprueba (mínimo 60); con la abierta respondida queda pendiente del docente
        fun saveRight(open: String? = null) {
            val q = course.questions

            fun closed(
                key: String,
                vararg texts: String,
            ) = mapOf("questionId" to q.getValue(key), "optionIds" to texts.map { option(q.getValue(key), it) })

            val answers =
                listOf(
                    closed("SINGLE", "Correcta"),
                    closed("MULTIPLE", "Sí A", "Sí B"),
                    closed("TRUE_FALSE", "Verdadero"),
                    mapOf("questionId" to q.getValue("SHORT"), "text" to "padron"),
                    mapOf("questionId" to q.getValue("OPEN"), "text" to open),
                )
            ok(put(student, "/api/lms/classroom/attempts/$id/answers", mapOf("answers" to answers)))
        }

        // el plazo, en la base: el intento lo impone el servidor y no hay forma de pedir uno ya vencido
        fun deadlineAt(deadline: Instant) =
            runBlocking {
                db
                    .sql("UPDATE lms.intento SET vence_en = :deadline WHERE id = :id")
                    .bind("deadline", deadline.atOffset(ZoneOffset.UTC))
                    .bind("id", UUID.fromString(id))
                    .fetch()
                    .rowsUpdated()
                    .awaitSingle()
            }

        fun mine(): JsonNode = ok(call(student, "/api/lms/classroom/attempts/$id"))

        fun queue(status: String? = null): JsonNode =
            ok(call(teacher, "/api/lms/teaching/offerings/${course.offering}/attempts" + (status?.let { "?status=$it" } ?: "")))

        fun roster(): JsonNode =
            ok(call(teacher, "/api/lms/teaching/offerings/${course.offering}/students"))
                .values()
                .single { it["enrollmentId"].asString() == enrollment }["assessments"]
                .single()

        fun lesson(): JsonNode =
            ok(call(student, "/api/lms/classroom/enrollments/$enrollment"))["units"]
                .values()
                .flatMap { it["lessons"].values() }
                .single { it["id"].asString() == course.assessment }
    }

    private fun pastDeadline(): Instant = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS)

    // lo que haría el @Scheduled: encolar el barrido de cada organización con intentos vencidos y dar vueltas a la cola
    private fun sweepNow() =
        runBlocking {
            sweep.enqueueDue()
            while (runner.runOnce() > 0) Unit
        }

    @Test
    fun `an abandoned attempt is closed by the sweep at its deadline and reaches the teacher`() {
        val s = Scene()
        s.saveRight(open = "Por su fecha")
        val deadline = pastDeadline()
        s.deadlineAt(deadline)
        assertEquals(0, s.queue().size(), "abierto no entra en la cola")

        sweepNow()

        val closed = s.mine()
        assertEquals("SUBMITTED", closed["status"].asString())
        assertEquals(deadline, Instant.parse(closed["submittedAt"].asString()), "enviado al vencer, no al barrer")
        assertEquals(7.0, closed["result"]["score"].asDouble(), "con lo guardado")
        val row = s.queue().single { it["attemptId"].asString() == s.id }
        assertEquals(deadline, Instant.parse(row["submittedAt"].asString()))
        assertEquals(1, row["pendingQuestions"].asInt())
        assertTrue(s.roster()["pending"].asBoolean())
    }

    @Test
    fun `an abandoned attempt that passes approves the lesson at its deadline`() {
        val s = Scene()
        s.saveRight()
        val deadline = pastDeadline()
        s.deadlineAt(deadline)

        sweepNow()

        assertEquals("GRADED", s.mine()["status"].asString())
        val lesson = s.lesson()
        assertEquals("APROBADA", lesson["progress"]["status"].asString())
        assertEquals(deadline, Instant.parse(lesson["progress"]["completedAt"].asString()), "passedAt es el plazo")
        assertEquals(1, s.queue("GRADED").size())
        assertEquals(70, s.roster()["bestPercent"].asInt())
        assertTrue(s.roster()["passed"].asBoolean())
    }

    @Test
    fun `touching an expired attempt closes it at its deadline too`() {
        val s = Scene()
        s.saveRight()
        val deadline = pastDeadline()
        s.deadlineAt(deadline)

        assertEquals(HttpStatus.CONFLICT, call(s.student, "/api/lms/classroom/attempts/${s.id}/submit", emptyMap()).first)

        assertEquals(deadline, Instant.parse(s.mine()["submittedAt"].asString()))
        assertEquals(deadline, Instant.parse(s.lesson()["progress"]["completedAt"].asString()))
    }

    @Test
    fun `the sweep leaves alone an attempt still in time`() {
        val s = Scene()
        s.saveRight()
        s.deadlineAt(Instant.now().plus(30, ChronoUnit.MINUTES))

        sweepNow()

        val open = s.mine()
        assertEquals("OPEN", open["status"].asString())
        assertTrue(open["submittedAt"].isNull)
        assertTrue(open["result"].isNull)
        // y el estudiante sigue escribiendo
        assertEquals(HttpStatus.OK, call(s.student, "/api/lms/classroom/attempts/${s.id}/submit", emptyMap()).first)
    }

    @Test
    fun `an attempt the sweep already closed is not closed again`() {
        val s = Scene()
        s.saveRight()
        val deadline = pastDeadline()
        s.deadlineAt(deadline)
        sweepNow()
        val first = s.mine()

        val again = runBlocking { attempts.closeExpired(ORG, 100) }
        sweepNow()

        assertFalse(UUID.fromString(s.id) in again, "ya no está abierto")
        val after = s.mine()
        assertEquals(first["submittedAt"].asString(), after["submittedAt"].asString())
        assertEquals(first["result"].toString(), after["result"].toString())
    }

    @Test
    fun `an expired attempt touched while the sweep runs is closed once`() {
        repeat(3) {
            val s = Scene()
            s.saveRight()
            val deadline = pastDeadline()
            s.deadlineAt(deadline)
            val go = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val (swept, touched) =
                try {
                    val sweeping =
                        pool.submit(
                            Callable {
                                go.await()
                                runBlocking { attempts.closeExpired(ORG, 100) }
                            },
                        )
                    val touching =
                        pool.submit(
                            Callable {
                                go.await()
                                call(s.student, "/api/lms/classroom/attempts/${s.id}/submit", emptyMap())
                            },
                        )
                    go.countDown()
                    sweeping.get() to touching.get()
                } finally {
                    pool.shutdown()
                }

            assertEquals(HttpStatus.CONFLICT, touched.first, touched.second.toString())
            val byStudent = touched.second.toString().contains("plazo")
            val bySweep = UUID.fromString(s.id) in swept
            assertTrue(byStudent != bySweep, "lo cierra uno solo: estudiante=$byStudent, barrido=$bySweep, ${touched.second}")
            val closed = s.mine()
            assertEquals("GRADED", closed["status"].asString())
            assertEquals(deadline, Instant.parse(closed["submittedAt"].asString()))
        }
    }
}
