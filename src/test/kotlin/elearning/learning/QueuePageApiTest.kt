package elearning.learning

import elearning.StatementCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.springframework.context.annotation.Import

// la cola de corrección por páginas (M4 de #183): sin page ni size devuelve la cola entera, como antes; con ellos, una
// página en el orden de siempre (el de envío), y lo que lee (intentos y correos) es el de esa página, no el de la edición
@Import(StatementCount::class)
class QueuePageApiTest : ClassroomApiTestSupport() {
    private inner class Scene {
        val course = course(withEvaluation = true)
        val teacher = newTeacher().also { assign(it, course) }
        val path = "/api/lms/teaching/offerings/${course.offering}/attempts"
        val open = course.questions.getValue("OPEN")

        // intentos enviados: con la abierta respondida quedan SUBMITTED; sin responder, la abierta vale 0 sola y quedan GRADED
        fun attempts(
            count: Int,
            answered: Boolean = false,
        ) = repeat(count) {
            val student = newStudent()
            val enrollment = enroll(student, course)["id"].asString()
            val attempt =
                call(student, "/api/lms/classroom/enrollments/$enrollment/lessons/${course.assessment}/attempts", emptyMap())
                    .second!!["id"]
                    .asString()
            if (answered) {
                ok(
                    put(
                        student,
                        "/api/lms/classroom/attempts/$attempt/answers",
                        mapOf("answers" to listOf(mapOf("questionId" to open, "optionIds" to emptyList<String>(), "text" to "Por su fecha"))),
                    ),
                )
            }
            ok(call(student, "/api/lms/classroom/attempts/$attempt/submit", emptyMap()))
        }

        fun ids(query: String = ""): List<String> {
            val rows = ok(call(teacher, path + query))
            return (0 until rows.size()).map { rows[it]["attemptId"].asString() }
        }
    }

    @Test
    fun `the queue pages in its usual order and without params returns it whole`() {
        val s = Scene()
        s.attempts(5)
        val whole = s.ids("?status=GRADED")
        assertEquals(5, whole.size)

        val pages = (0..3).map { s.ids("?status=GRADED&page=$it&size=2") }
        assertEquals(listOf(2, 2, 1, 0), pages.map { it.size })
        assertEquals(whole, pages.flatten())
        // size solo: la primera página; como en la API de registros, un size fuera de 1..200 se acota
        assertEquals(whole.take(3), s.ids("?status=GRADED&size=3"))
        assertEquals(whole, s.ids("?status=GRADED&page=0&size=1000"))
    }

    @Test
    fun `the default queue of submitted attempts pages too`() {
        val s = Scene()
        s.attempts(3, answered = true)
        s.attempts(2)
        val whole = s.ids()
        assertEquals(3, whole.size)

        assertEquals(whole.take(2), s.ids("?page=0&size=2"))
        assertEquals(whole.drop(2), s.ids("?page=1&size=2"))
        val row = ok(call(s.teacher, "${s.path}?page=1&size=2")).single()
        assertTrue(row["email"].asString().isNotEmpty())
        assertEquals(1, row["pendingQuestions"].asInt())
    }

    // las cifras van en el nombre de la prueba dinámica: el CI solo muestra el nombre de lo que falla, no el mensaje
    @TestFactory
    fun `a queue page costs the same however many attempts the offering has`(): List<DynamicTest> {
        val s = Scene()
        s.attempts(PAGE)
        val small = cost(s)
        s.attempts(MORE)
        val big = cost(s)

        val figures =
            "página de $PAGE de la cola: filas ${small.rows} → ${big.rows}, sentencias ${small.statements} → ${big.statements}, " +
                "arreglo ligado más largo ${small.array} → ${big.array} con $MORE intentos más"
        return listOf(
            dynamicTest(figures) {
                assertEquals(PAGE, big.rows, figures)
                assertTrue(big.statements <= small.statements && big.array <= small.array, figures)
            },
        )
    }

    private data class Cost(
        val rows: Int,
        val statements: Int,
        val array: Int,
    )

    // el mínimo de varias lecturas: el catálogo se proyecta en segundo plano y puede sumar sentencias a una de ellas
    private fun cost(s: Scene): Cost {
        val runs =
            (1..3).map {
                StatementCount.reset()
                val rows = ok(call(s.teacher, "${s.path}?status=GRADED&page=0&size=$PAGE")).size()
                Cost(rows, StatementCount.statements.get(), StatementCount.largestArray.get())
            }
        return Cost(runs.maxOf { it.rows }, runs.minOf { it.statements }, runs.minOf { it.array })
    }

    companion object {
        const val PAGE = 2
        const val MORE = 6
    }
}
