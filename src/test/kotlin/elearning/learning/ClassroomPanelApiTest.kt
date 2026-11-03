package elearning.learning

import elearning.StatementCount
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import wasichai.core.data.RecordRequest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

// el panel del estudiante (#261): una página de sus matrículas con lo que la interfaz pedía aparte por cada una (su aula para
// las evaluaciones pendientes y sus sesiones), leída por lotes. solo las propias, también para un docente
@Import(StatementCount::class)
class ClassroomPanelApiTest : ClassroomApiTestSupport() {
    private val panel = "/api/lms/classroom/panel"

    private fun sessions(
        teacher: String,
        course: Course,
        count: Int,
    ): List<String> {
        val today = LocalDate.now(ZoneId.of("America/Lima"))
        val body =
            mapOf(
                "title" to "Teoría",
                "type" to "TEORIA",
                "modality" to "VIRTUAL",
                "durationMinutes" to 90,
                "link" to "https://reunion.example.org/sala",
                "location" to null,
                "lessonId" to null,
                "recurrence" to
                    mapOf(
                        "pattern" to "DAILY",
                        "days" to emptyList<String>(),
                        "startDate" to today.plusDays(1).toString(),
                        "endDate" to today.plusDays(count.toLong()).toString(),
                        "localTime" to "08:00",
                    ),
            )
        val (status, created) = call(teacher, "/api/lms/teaching/offerings/${course.offering}/sessions", body)
        assertEquals(HttpStatus.CREATED, status, created.toString())
        return created!!.strings { it["id"] }
    }

    // una sesión que ya terminó: la escribe la plataforma, la API no crea sesiones en el pasado
    private fun moveToPast(session: String) =
        runBlocking {
            val start = Instant.now().minus(2, ChronoUnit.DAYS)
            records.asPlatform(ORG) {
                records.patch(
                    "sesion",
                    UUID.fromString(session),
                    RecordRequest(mapOf("inicio" to start.toString(), "fin" to start.plus(90, ChronoUnit.MINUTES).toString())),
                    null,
                    null,
                )
            }
        }

    @Test
    fun `each enrollment comes with its progress, its upcoming sessions and its pending assessments`() {
        val course = course(withEvaluation = true)
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        ok(event(student, enrollment, course.reading, "MARKED_COMPLETE"))
        val (cancelled, past, upcoming) = sessions(teacher, course, 3)
        ok(call(teacher, "/api/lms/teaching/sessions/$cancelled/cancel", mapOf("reason" to "Feriado")))
        moveToPast(past)

        val item = ok(call(student, panel)).single()
        // lo mismo que «Mis cursos», campo a campo
        val listed = ok(call(student, "/api/lms/classroom/enrollments")).single()
        for (field in listOf("id", "status", "course", "version", "offering", "progress", "nextLesson", "certificateCode")) {
            assertEquals(listed[field], item[field], field)
        }
        assertEquals(1, item["progress"]["completedLessons"].asInt())
        // sin canceladas ni terminadas, la más próxima primero, como en GET …/sessions
        assertEquals(listOf(upcoming), item["upcomingSessions"].strings { it["id"] })
        val session = item["upcomingSessions"][0]
        assertEquals("America/Lima", session["timeZone"].asString())
        assertEquals("https://reunion.example.org/sala", session["link"].asString())
        // la evaluación aún no hecha, con sus intentos
        val pending = item["pendingAssessments"].single()
        assertEquals(course.assessment, pending["lessonId"].asString())
        assertEquals(course.evaluation, pending["assessment"]["id"].asString())
        assertEquals("Evaluación de la unidad", pending["assessment"]["title"].asString())
        assertEquals(0, pending["assessment"]["attemptsUsed"].asInt())
        assertEquals(2, pending["assessment"]["maxAttempts"].asInt())
    }

    @Test
    fun `an assessment without attempts left nor one open is no longer pending`() {
        val course = course(withEvaluation = true)
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val attempts = "/api/lms/classroom/enrollments/$enrollment/lessons/${course.assessment}/attempts"
        // sin responder: la abierta vale 0 y el intento queda calificado y suspendido
        repeat(2) {
            val attempt = call(student, attempts, emptyMap()).second!!["id"].asString()
            val pending = ok(call(student, panel)).single()["pendingAssessments"].single()["assessment"]
            assertEquals(attempt, pending["openAttemptId"].asString())
            ok(call(student, "/api/lms/classroom/attempts/$attempt/submit", emptyMap()))
        }
        assertEquals(0, ok(call(student, panel)).single()["pendingAssessments"].size())
    }

    @Test
    fun `the panel pages in the order of my courses`() {
        val student = newStudent()
        listOf(3L, 1L, 2L).forEach { enroll(student, course(startsIn = it)) }
        val mine = ok(call(student, "/api/lms/classroom/enrollments")).strings { it["id"] }

        val pages = (0..2).map { ok(call(student, "$panel?page=$it&size=2")).strings { item -> item["id"] } }
        assertEquals(listOf(2, 1, 0), pages.map { it.size })
        assertEquals(mine, pages.flatten())
        // sin page ni size, la primera página de 25
        assertEquals(mine, ok(call(student, panel)).strings { it["id"] })
    }

    @Test
    fun `the panel holds only my own enrollments, also for a teacher who reads those of the offering`() {
        val course = course()
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        enroll(student, course)
        val other = newStudent()
        val theirs = enroll(other, course())["id"].asString()

        assertEquals(listOf(theirs), ok(call(other, panel)).strings { it["id"] })
        assertEquals(0, ok(call(teacher, panel)).size())
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, panel).first)
    }

    // las cifras van en el nombre de la prueba dinámica: el CI solo muestra el nombre de lo que falla, no el mensaje
    @TestFactory
    fun `a panel page costs about the same however many enrollments it holds`(): List<DynamicTest> {
        val student = newStudent()
        repeat(MORE + 1) {
            val course = course(withEvaluation = true)
            val enrollment = enroll(student, course)["id"].asString()
            ok(event(student, enrollment, course.reading, "MARKED_COMPLETE"))
        }
        val small = cost(student, 1)
        val big = cost(student, MORE + 1)

        val perEnrollment = (big - small).toDouble() / MORE
        val figures = "sentencias del panel del estudiante: $small con 1 matrícula, $big con ${MORE + 1} (≈$perEnrollment por matrícula)"
        return listOf(dynamicTest(figures) { assertTrue(perEnrollment <= PER_ITEM, figures) })
    }

    // el mínimo de varias lecturas: el catálogo se proyecta en segundo plano y puede sumar sentencias a una de ellas
    private fun cost(
        student: String,
        size: Int,
    ): Int =
        (1..3).minOf {
            StatementCount.statements.set(0)
            assertEquals(size, ok(call(student, "$panel?page=0&size=$size")).size())
            StatementCount.statements.get()
        }

    companion object {
        const val MORE = 4

        // por lotes, el número de matrículas de la página no suma lecturas; el margen cubre la proyección del catálogo
        const val PER_ITEM = 0.5
    }
}
