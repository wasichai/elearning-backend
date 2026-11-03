package elearning.reputation

import elearning.common.listAll
import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordRequest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

// lo que ve el valorado (RF-REP-05, ADR-0038 §8–§11): resúmenes con muestra mínima (k = 5 por omisión), liberados tras las
// notas y congelados, sin nadie dentro. sujetos de un solo rol, nunca ADMIN
class RatingSummaryApiTest : ClassroomApiTestSupport() {
    @Autowired
    private lateinit var aggregation: RatingAggregation

    private inner class Scene(
        raters: Int,
        resourceAuthor: String? = null,
    ) {
        val course = course(resourceAuthor = resourceAuthor?.let { userId(it) })
        val teacher = newTeacher().also { assign(it, course) }
        val offering: String = get("/api/objects/edicion_curso/records?codigo=${course.offering}", admin)["content"][0]["id"].asString()
        val students =
            (1..raters).map { i ->
                val student = newStudent()
                val enrollment = enroll(student, course)["id"].asString()
                ok(event(student, enrollment, course.reading, "MARKED_COMPLETE"))
                val path = "/api/lms/classroom/enrollments/$enrollment/ratings"
                val assignment = ok(call(student, path))["teachers"][0]["id"].asString()
                val rated =
                    call(
                        student,
                        path,
                        mapOf(
                            "type" to "DOCENCIA",
                            "targetId" to assignment,
                            "scores" to mapOf("docente_claridad" to 3 + i % 2),
                            "comment" to "Comentario $i",
                        ),
                    )
                assertEquals(HttpStatus.CREATED, rated.first, rated.second.toString())
                val resource = call(student, path, mapOf("type" to "RECURSO", "targetId" to course.resource, "scores" to mapOf("claridad" to 4)))
                assertEquals(HttpStatus.CREATED, resource.first, resource.second.toString())
                rated.second!!["id"].asString()
            }

        fun summary(token: String = teacher) = call(token, "/api/lms/teaching/offerings/${course.offering}/ratings")

        fun endedDaysAgo(days: Long) {
            val today = LocalDate.now(ZoneId.of("America/Lima"))
            platform {
                records.patch(
                    "edicion_curso",
                    UUID.fromString(offering),
                    RecordRequest(mapOf("fecha_inicio" to today.minusDays(days + 30).toString(), "fecha_fin" to today.minusDays(days).toString())),
                )
            }
        }

        fun allGraded() =
            platform {
                records.listAll("matricula", mapOf("edicion" to offering)).forEach {
                    records.patch("matricula", UUID.fromString(it.id), RecordRequest(mapOf("resultado" to "APROBADO")))
                }
            }

        fun publish(rating: String) =
            platform { records.patch("valoracion", UUID.fromString(rating), RecordRequest(mapOf("estado_moderacion" to "PUBLICADA"))) }
    }

    private fun <T> platform(block: suspend () -> T): T = runBlocking { records.asPlatform(ORG) { block() } }

    private fun JsonNode.dimension(key: String): JsonNode = this["dimensions"].values().single { it["key"].asString() == key }

    @Test
    fun `the teacher sees nothing until the window closed and every enrollment has its result`() {
        val s = Scene(raters = 5)
        val open = ok(s.summary())
        assertFalse(open["released"].asBoolean())
        assertEquals(0, open["dimensions"].size())
        assertEquals(0, open["comments"].size())

        s.endedDaysAgo(40)
        assertFalse(ok(s.summary())["released"].asBoolean(), "faltan resultados")
        s.allGraded()
        val released = ok(s.summary())
        assertTrue(released["released"].asBoolean())
        val clarity = released.dimension("docente_claridad")
        assertTrue(clarity["sufficient"].asBoolean())
        assertEquals(5, clarity["n"].asInt())
        assertEquals(3.6, clarity["mean"].asDouble())
        assertEquals("/api/lms/public/ratings/criterion", released["criterion"].asString())
        // ni quién, ni cuándo, ni ids
        val text = released.toString()
        s.students.forEach { assertFalse(it in text) }
        assertFalse(s.course.offering in text || "createdAt" in text || "enrollment" in text, text)
    }

    @Test
    fun `below the minimum sample there is no mean nor n, and comments need it too`() {
        val s = Scene(raters = 4)
        s.publish(s.students[0])
        s.endedDaysAgo(61)
        val released = ok(s.summary())
        assertTrue(released["released"].asBoolean(), "pasado release-after-end aunque falten resultados")
        val clarity = released.dimension("docente_claridad")
        assertFalse(clarity["sufficient"].asBoolean())
        assertTrue(clarity["mean"].isNull && clarity["n"].isNull)
        assertEquals(0, released["comments"].size())
    }

    @Test
    fun `only published comments reach the teacher, and the frozen figures do not move`() {
        val s = Scene(raters = 5)
        s.publish(s.students[0])
        s.endedDaysAgo(61)
        val first = ok(s.summary())
        assertEquals(listOf("Comentario 1"), first["comments"].strings { it["text"] })

        // un cambio posterior (la plataforma, fuera de la ventana) no altera lo congelado
        platform {
            records.listAll("puntaje_valoracion").filter { it.attributes["valoracion"]?.toString()?.contains(s.students[1]) == true }.forEach {
                records.patch("puntaje_valoracion", UUID.fromString(it.id), RecordRequest(mapOf("valor" to 1)))
            }
        }
        runBlocking { aggregation.aggregate(ORG) }
        assertEquals(first.dimension("docente_claridad"), ok(s.summary()).dimension("docente_claridad"))
    }

    @Test
    fun `only the assigned teacher with the summary action reads it`() {
        val s = Scene(raters = 1)
        assertEquals(HttpStatus.NOT_FOUND, s.summary(newTeacher()).first, "docente de otra edición")
        assertEquals(HttpStatus.FORBIDDEN, s.summary(newStudent()).first)
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, "/api/lms/teaching/offerings/${s.course.offering}/ratings").first)
        assertEquals(HttpStatus.FORBIDDEN, call(newStudent(), "/api/lms/ratings/resources").first)
    }

    @Test
    fun `the author sees the daily figures of their resource versions`() {
        val author = newCreator()
        val s = Scene(raters = 5, resourceAuthor = author)
        assertTrue(ok(call(author, "/api/lms/ratings/resources")).values().all { r -> r["dimensions"].values().none { it["sufficient"].asBoolean() } })

        runBlocking { aggregation.aggregate(ORG) }
        val mine = ok(call(author, "/api/lms/ratings/resources")).values().single { it["resourceVersionId"].asString() == s.course.resource }
        val clarity = mine.dimension("claridad")
        assertTrue(clarity["sufficient"].asBoolean())
        assertEquals(5, clarity["n"].asInt())
        assertEquals(4.0, clarity["mean"].asDouble())
        assertFalse(mine.dimension("utilidad")["sufficient"].asBoolean())
    }

    @Test
    fun `the criterion is public and names the minimum sample`() {
        val criterion = ok(call(null, "/api/lms/public/ratings/criterion"))
        assertEquals("media-simple@1", criterion["version"].asString())
        assertEquals(5, criterion["minSample"].asInt())
    }

    private fun newCreator(): String {
        val email = "${uniqueName("cre")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Creadora", "password" to DEMO_PASSWORD, "roles" to listOf("CREADOR")))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }
}
