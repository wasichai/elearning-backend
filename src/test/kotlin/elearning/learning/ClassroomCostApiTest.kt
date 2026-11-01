package elearning.learning

import elearning.StatementCount
import elearning.common.ref
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.springframework.context.annotation.Import
import wasichai.core.data.RecordRequest
import java.util.UUID

// el coste del aula (M1 de #104): las sentencias SQL de un GET no deben crecer más que lo que se muestra. antes, cada
// lectura de un recurso recalculaba el alcance entero (matrículas → unidades → lecciones → enlaces), y el coste crecía
// con el cuadrado del temario
@Import(StatementCount::class)
class ClassroomCostApiTest : ClassroomApiTestSupport() {
    // el mínimo de varias lecturas: el catálogo se proyecta en segundo plano y puede sumar sentencias a una de ellas
    private fun cost(
        student: String,
        enrollment: String,
    ): Int =
        (1..3).minOf {
            StatementCount.statements.set(0)
            ok(call(student, "/api/lms/classroom/enrollments/$enrollment"))
            StatementCount.statements.get()
        }

    @Test
    fun `the classroom costs about the same per unit, however long the course`() {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val small = cost(student, enrollment)

        runBlocking {
            records.asPlatform(ORG) {
                val version = records.get("unidad", UUID.fromString(course.unit)).attributes.ref("version_curso")!!
                for (order in 2..UNITS + 1) {
                    val unit = records.create("unidad", RecordRequest(mapOf("version_curso" to version, "orden" to order, "titulo" to "Unidad $order")))
                    for (n in 1..2) {
                        val lesson =
                            records.create(
                                "leccion",
                                RecordRequest(mapOf("unidad" to unit.id, "orden" to n, "titulo" to "Lección $order.$n", "tipo" to "LECTURA")),
                            )
                        records.create("recurso_leccion", RecordRequest(mapOf("leccion" to lesson.id, "version_recurso" to course.resource, "orden" to 1)))
                    }
                }
            }
        }
        val big = cost(student, enrollment)

        val perUnit = (big - small) / UNITS
        assertTrue(perUnit <= PER_UNIT, "sentencias del aula: $small con 1 unidad, $big con ${UNITS + 1} (≈$perUnit por unidad de 2 lecciones)")
    }

    // antes, el aula leía cada versión de recurso y cada recurso con un get propio (M1 de #104): dos lecturas por recurso.
    // las cifras van en el nombre de la prueba dinámica: el CI solo muestra el nombre de lo que falla
    @TestFactory
    fun `the classroom reads its resources in a batch, however many it has`(): List<DynamicTest> {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val small = cost(student, enrollment)

        val resources = (1..RESOURCES).map { publishedHtml() }
        runBlocking {
            records.asPlatform(ORG) {
                resources.forEachIndexed { n, resource ->
                    val lesson =
                        records.create(
                            "leccion",
                            RecordRequest(mapOf("unidad" to course.unit, "orden" to 10 + n, "titulo" to "Lectura ${n + 1}", "tipo" to "LECTURA")),
                        )
                    records.create("recurso_leccion", RecordRequest(mapOf("leccion" to lesson.id, "version_recurso" to resource, "orden" to 1)))
                }
            }
        }
        val big = cost(student, enrollment)
        val shown =
            ok(call(student, "/api/lms/classroom/enrollments/$enrollment"))["units"]
                .flatMap { it["lessons"] }
                .flatMap { it["resources"] }
                .map { it["id"].asString() }
                .toSet()
        assertEquals(resources.toSet() + course.resource, shown)

        val perResource = (big - small).toDouble() / RESOURCES
        val figures = "sentencias del aula: $small con 1 recurso, $big con ${RESOURCES + 1} (≈$perResource por recurso)"
        return listOf(dynamicTest(figures) { assertTrue(perResource <= PER_RESOURCE, figures) })
    }

    companion object {
        const val UNITS = 6
        const val PER_UNIT = 15
        const val RESOURCES = 6

        // por lotes, el número de recursos no suma lecturas; el margen cubre la proyección del catálogo en segundo plano
        const val PER_RESOURCE = 0.5
    }
}
