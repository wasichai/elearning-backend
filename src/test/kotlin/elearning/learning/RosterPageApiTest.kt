package elearning.learning

import elearning.StatementCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.springframework.context.annotation.Import

// el roster por páginas (M4 de #183): sin page ni size devuelve la edición entera, como antes; con ellos, una página en el
// orden de siempre, y lo que lee (progreso y asistencia) es el de los estudiantes de esa página, no el de toda la edición
@Import(StatementCount::class)
class RosterPageApiTest : ClassroomApiTestSupport() {
    private inner class Scene {
        val course = course()
        val teacher = newTeacher().also { assign(it, course) }
        val path = "/api/lms/teaching/offerings/${course.offering}/students"

        fun students(count: Int) = repeat(count) { enroll(newStudent(), course) }

        fun ids(query: String = ""): List<String> {
            val rows = ok(call(teacher, path + query))
            return (0 until rows.size()).map { rows[it]["enrollmentId"].asString() }
        }
    }

    @Test
    fun `the roster pages in its usual order and without params returns it whole`() {
        val s = Scene()
        s.students(5)
        val whole = s.ids()
        assertEquals(5, whole.size)

        val pages = (0..3).map { s.ids("?page=$it&size=2") }
        assertEquals(listOf(2, 2, 1, 0), pages.map { it.size })
        assertEquals(whole, pages.flatten())
        // size solo: la primera página; como en la API de registros, un size fuera de 1..200 se acota
        assertEquals(whole.take(3), s.ids("?size=3"))
        assertEquals(whole, s.ids("?page=0&size=1000"))
    }

    // las cifras van en el nombre de la prueba dinámica: el CI solo muestra el nombre de lo que falla, no el mensaje
    @TestFactory
    fun `a roster page costs the same however many students the offering has`(): List<DynamicTest> {
        val s = Scene()
        s.students(PAGE)
        val small = cost(s)
        s.students(MORE)
        val big = cost(s)

        val figures =
            "página de $PAGE del roster: sentencias ${small.first} → ${big.first}, arreglo ligado más largo ${small.second} → " +
                "${big.second} con $MORE estudiantes más"
        return listOf(dynamicTest(figures) { assertTrue(big.first <= small.first && big.second <= small.second, figures) })
    }

    // el mínimo de varias lecturas: el catálogo se proyecta en segundo plano y puede sumar sentencias a una de ellas
    private fun cost(s: Scene): Pair<Int, Int> {
        val runs =
            (1..3).map {
                StatementCount.reset()
                assertEquals(PAGE, ok(call(s.teacher, "${s.path}?page=0&size=$PAGE")).size())
                StatementCount.statements.get() to StatementCount.largestArray.get()
            }
        return runs.minOf { it.first } to runs.minOf { it.second }
    }

    companion object {
        const val PAGE = 2
        const val MORE = 6
    }
}
