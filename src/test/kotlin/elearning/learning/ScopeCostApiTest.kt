package elearning.learning

import elearning.StatementCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.context.annotation.Import

// el alcance de lectura con subconsultas (M4 de #183, M1 de #104): antes, ContentReadScope y EnrollmentReadScope listaban
// versiones, unidades, lecciones o matrículas y las ligaban como un arreglo de ids (ANY(?)). el arreglo crecía con el
// catálogo publicado (las unidades de todas las versiones visibles) o con los estudiantes de las ediciones del docente.
// se mide el arreglo ligado más largo de un GET: no debe crecer con lo que la pantalla no muestra
@Import(StatementCount::class)
class ScopeCostApiTest : ClassroomApiTestSupport() {
    private class Cost(
        val statements: Int,
        val largestArray: Int,
    )

    // el mínimo de varias lecturas: el catálogo se proyecta en segundo plano y puede sumar sentencias a una de ellas
    private fun cost(
        token: String,
        path: String,
        check: (Int) -> Unit = {},
    ): Cost {
        val runs =
            (1..3).map {
                StatementCount.reset()
                val body = ok(call(token, path))
                val cost = Cost(StatementCount.statements.get(), StatementCount.largestArray.get())
                check(if (body.isArray) body.size() else 0)
                cost
            }
        return Cost(runs.minOf { it.statements }, runs.minOf { it.largestArray })
    }

    // las cifras van en el nombre de la prueba dinámica: el CI solo muestra el nombre de lo que falla, no el mensaje
    @TestFactory
    fun `the classroom binds no list that grows with the published catalog`(): List<DynamicTest> {
        val course = course()
        val student = newStudent()
        val path = "/api/lms/classroom/enrollments/${enroll(student, course)["id"].asString()}"
        val small = cost(student, path)
        repeat(OTHER_COURSES) { course() }
        val big = cost(student, path)

        val figures =
            "aula: arreglo ligado más largo ${small.largestArray} → ${big.largestArray} con $OTHER_COURSES cursos publicados más " +
                "(sentencias ${small.statements} → ${big.statements})"
        return listOf(dynamicTest(figures) { assertTrue(big.largestArray <= small.largestArray, figures) })
    }

    @TestFactory
    fun `the roster binds no list that grows with its students`(): List<DynamicTest> {
        val course = course()
        val teacher = newTeacher().also { assign(it, course) }
        val path = "/api/lms/teaching/offerings/${course.offering}/students"
        repeat(FEW) { enroll(newStudent(), course) }
        val small = cost(teacher, path) { assertEquals(FEW, it) }
        repeat(MORE) { enroll(newStudent(), course) }
        val big = cost(teacher, path) { assertEquals(FEW + MORE, it) }

        val figures =
            "roster: arreglo ligado más largo ${small.largestArray} con $FEW estudiantes, ${big.largestArray} con ${FEW + MORE} " +
                "(sentencias ${small.statements} → ${big.statements})"
        return listOf(dynamicTest(figures) { assertTrue(big.largestArray <= small.largestArray, figures) })
    }

    companion object {
        const val OTHER_COURSES = 3
        const val FEW = 2
        const val MORE = 6
    }
}
