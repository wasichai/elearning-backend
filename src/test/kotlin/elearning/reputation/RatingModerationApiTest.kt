package elearning.reputation

import elearning.common.listAll
import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordRequest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

// moderación, denuncias y apelación (RF-REP-04, ADR-0038 §13–§19). quien modera es un rol delegado creado aquí, con
// MODERAR y sin ver quién escribió (fieldPermissions); nunca ADMIN. cada negativa relee lo guardado como la plataforma
class RatingModerationApiTest : ClassroomApiTestSupport() {
    private inner class Scene(
        raters: Int = 5,
        teacherToken: String? = null,
    ) {
        val course = course()
        val teacher = (teacherToken ?: newTeacher()).also { assign(it, course) }
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
                rated.second!!["id"].asString()
            }

        // la edición terminó hace 61 días: se libera aunque falten resultados
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

        fun summary(token: String = teacher) = ok(call(token, "/api/lms/teaching/offerings/${course.offering}/ratings"))

        fun state(rating: String): Map<String, Any?> = platform { records.get("valoracion", UUID.fromString(rating)).attributes }
    }

    private fun <T> platform(block: suspend () -> T): T = runBlocking { records.asPlatform(ORG) { block() } }

    private fun decide(
        token: String,
        rating: String,
        decision: String,
        reason: String? = null,
    ) = call(token, "/api/lms/moderation/ratings/$rating/decision", mapOf("decision" to decision, "reason" to reason))

    private fun reports(rating: String): List<Map<String, Any?>> =
        platform {
            records.listAll("denuncia_valoracion", mapOf("valoracion_id" to rating)).map { it.attributes }
        }

    // un rol delegado que modera: lee y cambia valoraciones y denuncias sin ver quién escribió; con otros roles si se piden
    private fun moderator(vararg also: String): String {
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
        val hidden = listOf("estudiante_id", "matricula").map { mapOf("objectName" to "valoracion", "fieldName" to it, "read" to false, "write" to false) }
        client
            .put()
            .uri("/api/roles/$role/field-permissions")
            .header("Authorization", admin)
            .bodyValue(mapOf("fields" to hidden))
            .exchange()
            .expectStatus()
            .isOk
        val email = "${uniqueName("mod-").lowercase()}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Moderación", "password" to DEMO_PASSWORD, "roles" to listOf(role) + also))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    private fun JsonNode.texts(): List<String> = values().map { it["text"].asString() }

    @Test
    fun `pending and hidden comments never reach the teacher, and hiding needs a listed reason`() {
        val s = Scene()
        s.release()
        val mod = moderator()
        assertEquals(0, s.summary()["comments"].size(), "todo pendiente")
        val queued = ok(call(mod, "/api/lms/moderation/ratings")).values().map { it["id"].asString() }
        assertTrue(queued.containsAll(s.ratings), queued.toString())

        assertEquals(HttpStatus.BAD_REQUEST, decide(mod, s.ratings[1], "HIDE").first)
        assertEquals(HttpStatus.BAD_REQUEST, decide(mod, s.ratings[1], "HIDE", "OPINION_NEGATIVA").first)
        assertEquals("PENDIENTE", s.state(s.ratings[1])["estado_moderacion"])

        assertEquals("PUBLICADA", ok(decide(mod, s.ratings[0], "PUBLISH"))["moderation"].asString())
        val hidden = ok(decide(mod, s.ratings[1], "HIDE", "SPAM"))
        assertEquals("OCULTA", hidden["moderation"].asString())
        assertEquals("SPAM", hidden["reason"].asString())
        assertEquals(listOf("Comentario 1"), s.summary()["comments"].texts())

        assertEquals(HttpStatus.FORBIDDEN, call(s.teacher, "/api/lms/moderation/ratings").first)
        assertEquals(HttpStatus.FORBIDDEN, decide(s.students[2], s.ratings[2], "PUBLISH").first)
        assertEquals(HttpStatus.FORBIDDEN, decide(s.teacher, s.ratings[2], "PUBLISH").first)
        assertEquals("PENDIENTE", s.state(s.ratings[2])["estado_moderacion"])
    }

    @Test
    fun `the moderator never sees who wrote`() {
        val s = Scene(raters = 1)
        val mod = moderator()
        val item = ok(call(mod, "/api/lms/moderation/ratings")).values().single { it["id"].asString() == s.ratings[0] }
        assertFalse(item.has("estudiante_id") || item.has("enrollmentId") || item.has("studentId"), item.toString())
        val record = ok(call(mod, "/api/objects/valoracion/records/${s.ratings[0]}"))["attributes"]
        assertFalse(record.has("estudiante_id") || record.has("matricula"), record.toString())
        assertEquals("Comentario 1", record["comentario"].asString())
    }

    @Test
    fun `nobody moderates a rating about themselves`() {
        // quien modera y además enseña la edición: dos roles, que es justamente el caso
        val both = moderator("DOCENTE")
        val s = Scene(raters = 1, teacherToken = both)
        assertFalse(ok(call(both, "/api/lms/moderation/ratings")).values().any { it["id"].asString() == s.ratings[0] })
        assertEquals(HttpStatus.FORBIDDEN, decide(both, s.ratings[0], "HIDE", "SPAM").first)
        assertEquals("PENDIENTE", s.state(s.ratings[0])["estado_moderacion"])
    }

    @Test
    fun `an appeal is made once and decided once by another moderator`() {
        val s = Scene(raters = 2)
        val first = moderator()
        val second = moderator()
        ok(decide(first, s.ratings[0], "HIDE", "FUERA_DE_TEMA"))
        ok(decide(first, s.ratings[1], "PUBLISH"))
        val appeal = "/api/lms/ratings/${s.ratings[0]}/appeal"

        assertEquals(HttpStatus.CONFLICT, call(s.students[1], "/api/lms/ratings/${s.ratings[1]}/appeal", mapOf("text" to "No está oculto")).first)
        assertEquals(HttpStatus.NOT_FOUND, call(s.students[1], appeal, mapOf("text" to "Ajena")).first)
        assertTrue(ok(call(s.students[0], appeal, mapOf("text" to "Era pertinente")))["pending"].asBoolean())
        assertEquals(HttpStatus.CONFLICT, call(s.students[0], appeal, mapOf("text" to "Otra vez")).first)
        assertEquals("Era pertinente", s.state(s.ratings[0])["apelacion"])

        val decision = "/api/lms/moderation/ratings/${s.ratings[0]}/appeal-decision"
        assertEquals(HttpStatus.FORBIDDEN, call(first, decision, mapOf("decision" to "REVERSE")).first, "quien ocultó no decide")
        assertEquals("OCULTA", s.state(s.ratings[0])["estado_moderacion"])
        val reversed = ok(call(second, decision, mapOf("decision" to "REVERSE")))
        assertEquals("PUBLICADA", reversed["moderation"].asString())
        assertFalse(reversed["appealPending"].asBoolean())
        assertEquals(HttpStatus.CONFLICT, call(second, decision, mapOf("decision" to "UPHOLD")).first, "es final")
        assertEquals("REVOCADA", s.state(s.ratings[0])["apelacion_resultado"])
    }

    @Test
    fun `the rated teacher reports what they see, and the report hides nothing`() {
        val s = Scene()
        val mod = moderator()
        ok(decide(mod, s.ratings[0], "PUBLISH"))
        s.release()
        val token = s.summary()["comments"][0]["token"].asString()
        val path = "/api/lms/ratings/comments/$token/reports"

        assertEquals(HttpStatus.CREATED, call(s.teacher, path, mapOf("reason" to "Me ataca")).first)
        assertEquals("PUBLICADA", s.state(s.ratings[0])["estado_moderacion"])
        assertEquals(1, reports(s.ratings[0]).size)
        assertEquals(HttpStatus.FORBIDDEN, call(s.students[1], path, mapOf("reason" to "x")).first, "un estudiante")
        val other = Scene(raters = 1)
        assertEquals(HttpStatus.NOT_FOUND, call(other.teacher, path, mapOf("reason" to "x")).first, "ficha de otra edición")
        assertEquals(HttpStatus.NOT_FOUND, call(s.teacher, "/api/lms/ratings/comments/${UUID.randomUUID()}/reports", mapOf("reason" to "x")).first)
        assertEquals(1, reports(s.ratings[0]).size)

        val queued = ok(call(mod, "/api/lms/moderation/ratings")).values().single { it["id"].asString() == s.ratings[0] }
        assertEquals(1, queued["openReports"].asInt())
        ok(decide(mod, s.ratings[0], "PUBLISH"))
        assertEquals("RESUELTA", reports(s.ratings[0]).single()["estado"])
    }

    @Test
    fun `the eleventh report of the day is 429 and is not stored`() {
        val s = Scene()
        ok(decide(moderator(), s.ratings[0], "PUBLISH"))
        s.release()
        val path = "/api/lms/ratings/comments/${s.summary()["comments"][0]["token"].asString()}/reports"
        repeat(10) { assertEquals(HttpStatus.CREATED, call(s.teacher, path, mapOf("reason" to "Denuncia $it")).first) }

        val (status, problem) = call(s.teacher, path, mapOf("reason" to "Una más"))
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, status, problem.toString())
        assertEquals(429, problem!!["status"].asInt())
        assertEquals(10, reports(s.ratings[0]).size)
    }
}
