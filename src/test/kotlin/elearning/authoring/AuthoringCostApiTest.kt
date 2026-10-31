package elearning.authoring

import elearning.StatementCount
import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordRequest

// el coste del estudio y de la bandeja (L4 de #216): las sentencias SQL no deben crecer con el temario ni con la bandeja.
// antes, el detalle hacía dos listas por lección, las diferencias leían el temario de dos versiones lección a lección, la
// bandeja leía curso y comentarios por versión y la copia leía cada registro por su padre
@Import(StatementCount::class)
class AuthoringCostApiTest : ClassroomApiTestSupport() {
    private fun account(role: String): String {
        val email = "${uniqueName("cost")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Persona", "password" to DEMO_PASSWORD, "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    private inner class Scene {
        val course = course()
        val author = account("CREADOR")
        val reviewer = account("REVISOR")
        val courseId: String = get("/api/objects/curso/records?codigo=${course.offering}", admin)["content"][0]["id"].asString()

        // un borrador nuevo del autor, copia de la versión indicada (o de la última)
        fun draft(from: String? = null): String {
            val (status, body) = copy(from)
            assertEquals(HttpStatus.CREATED, status, body.toString())
            return body!!["id"].asString()
        }

        fun copy(from: String?): Pair<HttpStatus, JsonNode?> = call(author, "/api/lms/studio/courses/$courseId/versions", mapOf("fromVersionId" to from))

        // unidades de dos lecciones, cada una con su recurso, puestas por la plataforma mientras la versión es borrador
        fun units(
            version: String,
            count: Int,
        ) = runBlocking {
            records.asPlatform(ORG) {
                repeat(count) { i ->
                    val unit = records.create("unidad", RecordRequest(mapOf("version_curso" to version, "orden" to 100 + i, "titulo" to "Unidad extra $i")))
                    for (n in 1..2) {
                        val lesson =
                            records.create(
                                "leccion",
                                RecordRequest(mapOf("unidad" to unit.id, "orden" to n, "titulo" to "Lección extra $i.$n", "tipo" to "LECTURA")),
                            )
                        records.create("recurso_leccion", RecordRequest(mapOf("leccion" to lesson.id, "version_recurso" to course.resource, "orden" to 1)))
                    }
                }
            }
        }

        // el mínimo de varias lecturas: el catálogo se proyecta en segundo plano y puede sumar sentencias a una de ellas
        fun cost(
            token: String,
            path: String,
        ): Int =
            (1..3).minOf {
                StatementCount.statements.set(0)
                ok(call(token, path))
                StatementCount.statements.get()
            }
    }

    // las cifras van en el nombre de la prueba dinámica: el CI solo muestra el nombre de lo que falla, no el mensaje
    private fun bounded(
        what: String,
        small: Int,
        big: Int,
        added: Int,
        per: String,
        max: Double,
    ): List<DynamicTest> {
        val each = (big - small).toDouble() / added
        val figures = "sentencias de $what: $small, y $big con $added ${per}s más (≈$each por $per)"
        return listOf(dynamicTest(figures) { assertTrue(each <= max, figures) })
    }

    @TestFactory
    fun `the studio detail costs about the same however long the curriculum`(): List<DynamicTest> {
        val s = Scene()
        val draft = s.draft()
        val small = s.cost(s.author, "/api/lms/studio/versions/$draft")
        s.units(draft, MORE)
        val big = s.cost(s.author, "/api/lms/studio/versions/$draft")
        return bounded("el detalle del estudio", small, big, MORE, "unidad", PER_UNIT)
    }

    @TestFactory
    fun `the diff costs about the same however long the curriculum`(): List<DynamicTest> {
        val s = Scene()
        val draft = s.draft()
        val small = s.cost(s.author, "/api/lms/studio/versions/$draft/diff")
        s.units(draft, MORE)
        val big = s.cost(s.author, "/api/lms/studio/versions/$draft/diff")
        return bounded("las diferencias", small, big, MORE, "unidad", PER_UNIT)
    }

    @TestFactory
    fun `the inbox costs about the same however many versions it holds`(): List<DynamicTest> {
        val s = Scene()

        fun inReview() = ok(call(s.author, "/api/lms/studio/versions/${s.draft()}/submit", emptyMap()))
        inReview()
        val small = s.cost(s.reviewer, "/api/lms/review/versions")
        repeat(MORE) { inReview() }
        val big = s.cost(s.reviewer, "/api/lms/review/versions")
        return bounded("la bandeja", small, big, MORE, "versión", PER_VERSION)
    }

    // la copia escribe cada registro, con su guarda: crece con el temario, pero sin leer cada registro por su padre
    @TestFactory
    fun `a copy reads the curriculum in batches`(): List<DynamicTest> {
        val s = Scene()
        val source = s.draft()

        fun cost(): Int {
            StatementCount.statements.set(0)
            val (status, body) = s.copy(source)
            assertEquals(HttpStatus.CREATED, status, body.toString())
            return StatementCount.statements.get()
        }
        val small = cost()
        s.units(source, MORE)
        val big = cost()
        return bounded("la copia", small, big, MORE, "unidad", PER_COPIED_UNIT)
    }

    companion object {
        const val MORE = 6

        // leer por lotes no depende del tamaño; el margen cubre la proyección del catálogo en segundo plano
        const val PER_UNIT = 0.5
        const val PER_VERSION = 0.5

        // una unidad de dos lecciones con su recurso son cinco registros que se escriben y se guardan
        const val PER_COPIED_UNIT = 0.5
    }
}
