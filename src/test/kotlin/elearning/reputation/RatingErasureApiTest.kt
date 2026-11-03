package elearning.reputation

import elearning.catalog.CatalogProjection
import elearning.common.listAll
import elearning.jobs.JobRunner
import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordRequest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

// las valoraciones en la supresión de datos personales (ADR-0037, ADR-0038 §21) y el agregado en la ficha pública del
// docente (§12): se van las valoraciones de quien se suprime, sus denuncias abiertas quedan sin él y las resueltas se
// borran, lo congelado se conserva y lo de recurso se recalcula; en el catálogo, solo con consentimiento y n ≥ k
class RatingErasureApiTest : ClassroomApiTestSupport() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var aggregation: RatingAggregation

    @Autowired
    private lateinit var projection: CatalogProjection

    private inner class Scene(
        raters: Int,
        val author: String = newCreator(),
    ) {
        val course = course(resourceAuthor = userId(author))
        val teacher = acceptTerms(newTeacher()).also { assign(it, course) }
        private val offering: String = get("/api/objects/edicion_curso/records?codigo=${course.offering}", admin)["content"][0]["id"].asString()
        val students = mutableListOf<String>()
        val ratings =
            (1..raters).map { i ->
                val student = newStudent().also { students += it }
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
                            "scores" to mapOf("docente_claridad" to 4),
                            "comment" to "Comentario $i",
                        ),
                    )
                assertEquals(HttpStatus.CREATED, rated.first, rated.second.toString())
                assertEquals(
                    HttpStatus.CREATED,
                    call(
                        student,
                        path,
                        mapOf(
                            "type" to "RECURSO",
                            "targetId" to course.resource,
                            "scores" to mapOf("claridad" to 3),
                        ),
                    ).first,
                )
                rated.second!!["id"].asString()
            }

        fun release() {
            val today = LocalDate.now(ZoneId.of("America/Lima"))
            platform {
                records.patch(
                    "edicion_curso",
                    UUID.fromString(offering),
                    RecordRequest(mapOf("fecha_inicio" to today.minusDays(91).toString(), "fecha_fin" to today.minusDays(61).toString())),
                )
            }
        }

        fun summary(): JsonNode = ok(call(teacher, "/api/lms/teaching/offerings/${course.offering}/ratings"))

        fun resourceClarity(): JsonNode =
            ok(call(author, "/api/lms/ratings/resources"))
                .values()
                .single { it["resourceVersionId"].asString() == course.resource }["dimensions"]
                .values()
                .single { it["key"].asString() == "claridad" }

        fun ratingsOf(student: String): Int = platform { records.listAll("valoracion", mapOf("estudiante_id" to userId(student))).size }

        fun scoresOf(student: String): Int = platform { records.listAll("puntaje_valoracion", mapOf("estudiante_id" to userId(student))).size }

        // la ficha del docente en el catálogo público, tras refrescar la proyección
        fun publicTeacher(): JsonNode {
            runBlocking { projection.refreshNow() }
            return ok(call(null, "/api/lms/public/catalog/courses/${course.offering.lowercase()}"))["teachers"].values().single()
        }

        fun consent(ratings: Boolean) {
            val account = userId(teacher)
            val profile = get("/api/objects/perfil_docente/records?usuario_id=$account", admin)["content"][0]["id"].asString()
            platform {
                records.patch("perfil_docente", UUID.fromString(profile), RecordRequest(mapOf("perfil_publico" to true, "valoraciones_publicas" to ratings)))
            }
        }
    }

    private fun <T> platform(block: suspend () -> T): T = runBlocking { records.asPlatform(ORG) { block() } }

    private fun drain() = runBlocking { while (runner.runOnce() > 0) Unit }

    private fun withdraw(token: String) {
        val status =
            client
                .delete()
                .uri("/api/lms/terms/acceptance")
                .header("Authorization", token)
                .exchange()
                .returnResult(String::class.java)
                .status
                .value()
        assertEquals(HttpStatus.ACCEPTED.value(), status)
        drain()
    }

    private fun reports(rating: String): List<Map<String, Any?>> =
        platform {
            records.listAll("denuncia_valoracion", mapOf("valoracion_id" to rating)).map { it.attributes }
        }

    @Test
    fun `erasing a student removes their ratings, keeps what is frozen and recomputes the resource`() {
        val s = Scene(raters = 5)
        s.release()
        assertEquals(
            5,
            s
                .summary()["dimensions"]
                .values()
                .single { it["key"].asString() == "docente_claridad" }["n"]
                .asInt(),
        )
        runBlocking { aggregation.aggregate(ORG) }
        assertEquals(5, s.resourceClarity()["n"].asInt())

        val gone = s.students[0]
        withdraw(gone)

        assertEquals(0, s.ratingsOf(gone))
        assertEquals(0, s.scoresOf(gone))
        assertEquals(2, s.ratingsOf(s.students[1]), "lo de los demás se queda: su docente y su recurso")
        val frozen = s.summary()["dimensions"].values().single { it["key"].asString() == "docente_claridad" }
        assertEquals(5, frozen["n"].asInt(), "lo congelado no tiene a nadie dentro y se conserva")
        val resource = s.resourceClarity()
        assertFalse(resource["sufficient"].asBoolean(), "el de recurso se recalcula y bajo k deja de mostrarse")
        assertTrue(resource["n"].isNull)
    }

    @Test
    fun `erasing the rated teacher dissociates open reports and deletes resolved ones`() {
        val s = Scene(raters = 5)
        val mod = moderator()
        ok(call(mod, "/api/lms/moderation/ratings/${s.ratings[1]}/decision", mapOf("decision" to "PUBLISH")))
        s.release()
        val path = "/api/lms/ratings/comments/${s.summary()["comments"][0]["token"].asString()}/reports"
        assertEquals(HttpStatus.CREATED, call(s.teacher, path, mapOf("reason" to "Primera")).first)
        ok(call(mod, "/api/lms/moderation/ratings/${s.ratings[1]}/decision", mapOf("decision" to "PUBLISH")))
        assertEquals(HttpStatus.CREATED, call(s.teacher, path, mapOf("reason" to "Segunda")).first)
        assertEquals(listOf("ABIERTA", "RESUELTA"), reports(s.ratings[1]).map { it["estado"].toString() }.sorted())

        withdraw(s.teacher)

        val left = reports(s.ratings[1]).single()
        assertEquals("ABIERTA", left["estado"])
        assertNull(left["denunciante_id"])
        assertEquals(2, s.ratingsOf(s.students[1]), "las valoraciones de otra persona se quedan")
    }

    @Test
    fun `the public teacher profile shows the ratings only with consent and enough sample`() {
        val s = Scene(raters = 5)
        s.release()
        s.summary()
        s.consent(ratings = false)
        assertTrue(s.publicTeacher()["ratings"].isNull, "sin valoraciones_publicas")

        s.consent(ratings = true)
        val ratings = s.publicTeacher()["ratings"]
        assertEquals("/api/lms/public/ratings/criterion", ratings["criterion"].asString())
        val clarity = ratings["dimensions"].values().single()
        assertEquals("docente_claridad", clarity["key"].asString())
        assertEquals(5, clarity["n"].asInt())
        assertEquals(4.0, clarity["mean"].asDouble())

        val few = Scene(raters = 4)
        few.release()
        few.summary()
        few.consent(ratings = true)
        assertTrue(few.publicTeacher()["ratings"].isNull, "n < k")
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

    private fun moderator(): String {
        val role = uniqueName("MODERACION_").uppercase().replace('-', '_')
        client
            .post()
            .uri("/api/roles")
            .header("Authorization", admin)
            .bodyValue(mapOf("name" to role, "label" to "Moderación de valoraciones"))
            .exchange()
            .expectStatus()
            .isCreated
        val entries =
            listOf("READ", "UPDATE", "MODERAR").map { mapOf("objectName" to "valoracion", "action" to it) } +
                listOf("READ", "UPDATE").map { mapOf("objectName" to "denuncia_valoracion", "action" to it) }
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header("Authorization", admin)
            .bodyValue(mapOf("permissions" to entries))
            .exchange()
            .expectStatus()
            .isOk
        val email = "${uniqueName("mod-").lowercase()}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Moderación", "password" to DEMO_PASSWORD, "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }
}
