package elearning.learning

import elearning.StatementCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import java.time.LocalDate
import java.time.ZoneId

// el panel docente (#261): una página de las ediciones asignadas con lo que la interfaz pedía aparte por cada una (su cola
// y sus sesiones), leída por lotes. lo decide la asignación: otra edición no aparece
@Import(StatementCount::class)
class TeachingPanelApiTest : ClassroomApiTestSupport() {
    private val panel = "/api/lms/teaching/panel"

    private fun session(
        teacher: String,
        course: Course,
    ): String {
        val today = LocalDate.now(ZoneId.of("America/Lima"))
        val body =
            mapOf(
                "title" to "Consulta",
                "type" to "CONSULTA",
                "modality" to "VIRTUAL",
                "durationMinutes" to 60,
                "link" to "https://reunion.example.org/consulta",
                "location" to null,
                "lessonId" to null,
                "recurrence" to
                    mapOf(
                        "pattern" to "ONCE",
                        "days" to emptyList<String>(),
                        "startDate" to today.plusDays(2).toString(),
                        "localTime" to "18:00",
                    ),
            )
        val (status, created) = call(teacher, "/api/lms/teaching/offerings/${course.offering}/sessions", body)
        assertEquals(HttpStatus.CREATED, status, created.toString())
        return created!![0]["id"].asString()
    }

    // un estudiante con un intento enviado que espera corrección: la abierta respondida queda pendiente
    private fun submitted(course: Course): String {
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val attempt =
            call(student, "/api/lms/classroom/enrollments/$enrollment/lessons/${course.assessment}/attempts", emptyMap()).second!!["id"].asString()
        val open = course.questions.getValue("OPEN")
        ok(
            put(
                student,
                "/api/lms/classroom/attempts/$attempt/answers",
                mapOf("answers" to listOf(mapOf("questionId" to open, "optionIds" to emptyList<String>(), "text" to "Por su fecha"))),
            ),
        )
        ok(call(student, "/api/lms/classroom/attempts/$attempt/submit", emptyMap()))
        return attempt
    }

    @Test
    fun `each offering comes with its figures, its upcoming sessions and the oldest attempts waiting for grading`() {
        val course = course(withEvaluation = true)
        val teacher = newTeacher().also { assign(it, course) }
        val attempts = (1..QUEUE_PREVIEW + 1).map { submitted(course) }
        enroll(newStudent(), course)
        val upcoming = session(teacher, course)

        val item = ok(call(teacher, panel)).single()
        // lo mismo que GET /offerings, campo a campo
        val listed = ok(call(teacher, "/api/lms/teaching/offerings")).single()
        for (field in listOf("code", "status", "startDate", "endDate", "timeZone", "course", "enrolled", "pendingAttempts")) {
            assertEquals(listed[field], item[field], field)
        }
        assertEquals(QUEUE_PREVIEW + 2, item["enrolled"].asInt())
        assertEquals(QUEUE_PREVIEW + 1, item["pendingAttempts"].asInt())
        assertEquals(listOf(upcoming), item["upcomingSessions"].strings { it["id"] })
        // los más antiguos de la cola, en su orden y con sus campos
        val queue = ok(call(teacher, "/api/lms/teaching/offerings/${course.offering}/attempts"))
        assertEquals(attempts.take(QUEUE_PREVIEW), item["oldestPending"].strings { it["attemptId"] })
        for (n in 0 until QUEUE_PREVIEW) assertEquals(queue[n], item["oldestPending"][n])
    }

    @Test
    fun `the panel pages in the order of my offerings`() {
        val teacher = newTeacher()
        listOf(3L, 1L, 2L).forEach { assign(teacher, course(startsIn = it)) }
        val mine = ok(call(teacher, "/api/lms/teaching/offerings")).strings { it["code"] }

        val pages = (0..2).map { ok(call(teacher, "$panel?page=$it&size=2")).strings { item -> item["code"] } }
        assertEquals(listOf(2, 1, 0), pages.map { it.size })
        assertEquals(mine, pages.flatten())
        assertEquals(mine, ok(call(teacher, panel)).strings { it["code"] })
    }

    @Test
    fun `the panel holds only the offerings assigned to me, never a colleague's`() {
        val mine = course(withEvaluation = true)
        val theirs = course(withEvaluation = true)
        val teacher = newTeacher().also { assign(it, mine) }
        val colleague = newTeacher().also { assign(it, theirs) }
        submitted(theirs)
        session(colleague, theirs)

        assertEquals(listOf(mine.offering), ok(call(teacher, panel)).strings { it["code"] })
        assertEquals(listOf(theirs.offering), ok(call(colleague, panel)).strings { it["code"] })
        assertEquals(0, ok(call(newStudent(), panel)).size())
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, panel).first)
    }

    // las cifras van en el nombre de la prueba dinámica: el CI solo muestra el nombre de lo que falla, no el mensaje
    @TestFactory
    fun `a panel page costs about the same however many offerings it holds`(): List<DynamicTest> {
        val teacher = newTeacher()
        repeat(MORE + 1) {
            val course = course(withEvaluation = true)
            assign(teacher, course)
            submitted(course)
            session(teacher, course)
        }
        val small = cost(teacher, 1)
        val big = cost(teacher, MORE + 1)

        val perOffering = (big - small).toDouble() / MORE
        val figures = "sentencias del panel docente: $small con 1 edición, $big con ${MORE + 1} (≈$perOffering por edición)"
        return listOf(dynamicTest(figures) { assertTrue(perOffering <= PER_ITEM, figures) })
    }

    // el mínimo de varias lecturas: el catálogo se proyecta en segundo plano y puede sumar sentencias a una de ellas
    private fun cost(
        teacher: String,
        size: Int,
    ): Int =
        (1..3).minOf {
            StatementCount.statements.set(0)
            assertEquals(size, ok(call(teacher, "$panel?page=0&size=$size")).size())
            StatementCount.statements.get()
        }

    companion object {
        const val QUEUE_PREVIEW = 5
        const val MORE = 4

        // por lotes, el número de ediciones de la página no suma lecturas; el margen cubre la proyección del catálogo
        const val PER_ITEM = 0.5
    }
}
