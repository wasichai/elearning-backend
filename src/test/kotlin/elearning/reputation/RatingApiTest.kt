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

// valorar a docentes y recursos (RF-REP-01/02, ADR-0038): quién, cuándo, una vez, sin conflicto de interés, con límite
// diario y sin que el valorado lea nada. sujetos de un solo rol salvo donde la regla es justamente tener dos; nunca ADMIN.
// cada negativa relee lo guardado (como la plataforma) para comprobar que nada cambió
class RatingApiTest : ClassroomApiTestSupport() {
    private inner class Scene(
        resourceAuthor: String? = null,
        val student: String = newStudent(),
    ) {
        val course = course(resourceAuthor = resourceAuthor?.let { userId(it) })
        val teacher = newTeacher().also { assign(it, course) }
        val enrollment = enroll(student, course)["id"].asString()

        fun eligibility(token: String = student) = ok(call(token, "/api/lms/classroom/enrollments/$enrollment/ratings"))

        fun teacherTarget(): String = eligibility()["teachers"][0]["id"].asString()

        fun completeReading() = ok(event(student, enrollment, course.reading, "MARKED_COMPLETE"))

        fun rate(
            token: String? = student,
            type: String = "DOCENCIA",
            target: String = if (type == "DOCENCIA") teacherTarget() else course.resource,
            scores: Map<String, Any> = if (type == "DOCENCIA") mapOf("docente_claridad" to 4) else mapOf("claridad" to 5, "utilidad" to 4),
            comment: String? = null,
        ) = call(
            token,
            "/api/lms/classroom/enrollments/$enrollment/ratings",
            mapOf(
                "type" to type,
                "targetId" to target,
                "scores" to scores,
                "comment" to comment,
            ),
        )

        fun stored(): List<Map<String, Any?>> =
            runBlocking { records.asPlatform(ORG) { records.listAll("valoracion", mapOf("matricula" to enrollment)).map { it.attributes } } }

        val studentId: String by lazy { userId(student) }

        fun storedScores(): Int = scoreValues().size

        // los valores de sus puntajes guardados, como la plataforma
        fun scoreValues(): List<String> =
            runBlocking {
                records.asPlatform(ORG) {
                    records.listAll("puntaje_valoracion", mapOf("estudiante_id" to studentId)).map { it.attributes["valor"].toString() }
                }
            }

        // la edición terminó hace 40 días: la ventana de 30 días cerró
        fun closeWindow() {
            val offering = get("/api/objects/edicion_curso/records?codigo=${course.offering}", admin)["content"][0]["id"].asString()
            val today = LocalDate.now(ZoneId.of("America/Lima"))
            runBlocking {
                records.asPlatform(ORG) {
                    records.patch(
                        "edicion_curso",
                        UUID.fromString(offering),
                        RecordRequest(mapOf("fecha_inicio" to today.minusDays(70).toString(), "fecha_fin" to today.minusDays(40).toString())),
                    )
                }
            }
        }
    }

    private fun created(result: Pair<HttpStatus, JsonNode?>): JsonNode {
        assertEquals(HttpStatus.CREATED, result.first, result.second.toString())
        return result.second!!
    }

    private fun send(
        method: String,
        token: String,
        path: String,
        body: Any? = null,
        idempotencyKey: String? = null,
    ): Pair<HttpStatus, JsonNode?> {
        val spec =
            when (method) {
                "PUT" -> client.put().uri(path).bodyValue(body!!)
                "DELETE" -> client.delete().uri(path)
                else -> client.post().uri(path).bodyValue(body!!)
            }
        val r =
            spec
                .header("Authorization", token)
                .apply { if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey) }
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(r.status.value()) to r.responseBody?.let { runCatching { json.readTree(it) }.getOrNull() }
    }

    @Test
    fun `only an enrolled student with enough progress rates a teacher, and only once`() {
        val s = Scene()
        val target = s.teacherTarget()

        assertEquals(HttpStatus.UNAUTHORIZED, s.rate(token = null, target = target).first)
        assertEquals(HttpStatus.FORBIDDEN, s.rate(token = s.teacher, target = target).first, "el docente no valora")
        val outsider = Scene()
        val (status, _) =
            call(
                outsider.student,
                "/api/lms/classroom/enrollments/${outsider.enrollment}/ratings",
                mapOf("type" to "DOCENCIA", "targetId" to target, "scores" to mapOf("docente_claridad" to 2)),
            )
        assertEquals(HttpStatus.FORBIDDEN, status, "no está matriculado en esa edición")
        assertEquals(HttpStatus.NOT_FOUND, call(outsider.student, "/api/lms/classroom/enrollments/${s.enrollment}/ratings").first, "matrícula ajena")

        val before = s.eligibility()["teachers"][0]
        assertFalse(before["eligible"].asBoolean())
        assertEquals("PROGRESS", before["reason"].asString())
        assertEquals(HttpStatus.FORBIDDEN, s.rate(target = target).first, "progreso bajo el mínimo")
        assertTrue(s.stored().isEmpty())

        s.completeReading()
        val rating = created(s.rate(target = target, comment = "Explica muy bien"))
        assertEquals("DOCENCIA", rating["type"].asString())
        assertEquals(target, rating["targetId"].asString())
        assertEquals(4, rating["scores"]["docente_claridad"].asInt())
        assertEquals("PENDIENTE", rating["moderation"].asString())
        assertEquals(rating["id"].asString(), s.eligibility()["teachers"][0]["ratingId"].asString())

        assertEquals(HttpStatus.CONFLICT, s.rate(target = target).first, "una por par")
        assertEquals(1, s.stored().size)
        assertEquals(1, s.storedScores())
        assertEquals(listOf(rating["id"].asString()), ok(call(s.student, "/api/lms/ratings/mine")).strings { it["id"] })
        assertEquals(0, ok(call(outsider.student, "/api/lms/ratings/mine")).size())
        assertEquals(
            HttpStatus.NOT_FOUND,
            send(
                "PUT",
                outsider.student,
                "/api/lms/ratings/${rating["id"].asString()}",
                mapOf("scores" to mapOf("docente_claridad" to 1)),
            ).first,
        )
        assertEquals(HttpStatus.NOT_FOUND, send("DELETE", outsider.student, "/api/lms/ratings/${rating["id"].asString()}").first)
        assertEquals("4", s.scoreValues().joinToString())
    }

    @Test
    fun `a resource is rated once a lesson that uses it is completed`() {
        val s = Scene()
        val resource = s.eligibility()["resources"].single()
        assertEquals(s.course.resource, resource["id"].asString())
        assertEquals("LESSON", resource["reason"].asString())
        assertEquals(HttpStatus.FORBIDDEN, s.rate(type = "RECURSO").first)
        assertTrue(s.stored().isEmpty())

        s.completeReading()
        val rating = created(s.rate(type = "RECURSO"))
        assertEquals(mapOf("claridad" to 5, "utilidad" to 4), rating["scores"].properties().associate { it.key to it.value.asInt() })
        assertEquals(HttpStatus.CONFLICT, s.rate(type = "RECURSO").first)
        assertEquals(1, s.stored().size)
    }

    @Test
    fun `nobody rates what is their own`() {
        // quien enseña la edición y además está matriculado en ella: dos roles, que es justamente el caso
        val email = "${uniqueName("doc-est")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(
                mapOf("email" to email, "displayName" to "Docente y estudiante", "password" to DEMO_PASSWORD, "roles" to listOf("ESTUDIANTE", "DOCENTE")),
            ).exchange()
            .expectStatus()
            .isCreated
        val both = acceptTerms(bearer(email, DEMO_PASSWORD))
        val s = Scene(student = both)
        assign(both, s.course)
        s.completeReading()
        assertTrue(s.eligibility()["teachers"].values().all { it["reason"].asString() == "OWN" })
        for (teacher in s.eligibility()["teachers"].values()) {
            assertEquals(HttpStatus.FORBIDDEN, s.rate(target = teacher["id"].asString()).first, "colega o sí mismo")
        }
        assertTrue(s.stored().isEmpty())

        val author = newStudent()
        val authored = Scene(resourceAuthor = author, student = author)
        authored.completeReading()
        assertEquals("OWN", authored.eligibility()["resources"][0]["reason"].asString())
        assertEquals(HttpStatus.FORBIDDEN, authored.rate(type = "RECURSO").first, "el autor de su recurso")
        assertTrue(authored.stored().isEmpty())
    }

    @Test
    fun `outside the window nothing is created, edited or withdrawn`() {
        val s = Scene()
        s.completeReading()
        val id = created(s.rate(comment = "Antes del cierre"))["id"].asString()
        s.closeWindow()

        assertFalse(s.eligibility()["open"].asBoolean())
        assertEquals(HttpStatus.CONFLICT, s.rate(type = "RECURSO").first)
        assertEquals(
            HttpStatus.CONFLICT,
            send(
                "PUT",
                s.student,
                "/api/lms/ratings/$id",
                mapOf(
                    "scores" to mapOf("docente_claridad" to 1),
                    "comment" to "Después",
                ),
            ).first,
        )
        assertEquals(HttpStatus.CONFLICT, send("DELETE", s.student, "/api/lms/ratings/$id").first)

        val kept = s.stored().single()
        assertEquals("Antes del cierre", kept["comentario"])
        assertEquals("4", s.scoreValues().joinToString())
    }

    @Test
    fun `editing the comment sends it back to moderation and keeps its history for its author`() {
        val s = Scene()
        s.completeReading()
        val id = created(s.rate(comment = "Primera versión"))["id"].asString()
        runBlocking { records.asPlatform(ORG) { records.patch("valoracion", UUID.fromString(id), RecordRequest(mapOf("estado_moderacion" to "PUBLICADA"))) } }

        val scoresOnly = ok(send("PUT", s.student, "/api/lms/ratings/$id", mapOf("scores" to mapOf("docente_claridad" to 2), "comment" to "Primera versión")))
        assertEquals("PUBLICADA", scoresOnly["moderation"].asString(), "cambiar solo puntajes no la devuelve a moderación")
        val edited =
            ok(
                send(
                    "PUT",
                    s.student,
                    "/api/lms/ratings/$id",
                    mapOf(
                        "scores" to mapOf("docente_claridad" to 3, "docente_disponibilidad" to 5),
                        "comment" to "Segunda",
                    ),
                ),
            )

        assertEquals("PENDIENTE", edited["moderation"].asString())
        assertEquals("Segunda", edited["comment"].asString())
        assertEquals(mapOf("docente_claridad" to 3, "docente_disponibilidad" to 5), edited["scores"].properties().associate { it.key to it.value.asInt() })
        assertTrue(ok(call(s.student, "/api/objects/valoracion/records/$id/history")).size() >= 3)

        val withdrawn = ok(send("PUT", s.student, "/api/lms/ratings/$id", mapOf("scores" to mapOf("docente_claridad" to 3), "comment" to "")))
        assertTrue(withdrawn["comment"].isNull && withdrawn["moderation"].isNull)
        assertEquals("3", s.scoreValues().joinToString())

        assertEquals(HttpStatus.NO_CONTENT, send("DELETE", s.student, "/api/lms/ratings/$id").first)
        assertTrue(s.stored().isEmpty())
        assertEquals(0, s.storedScores())
    }

    @Test
    fun `the rated teacher reads nothing of the ratings, not even their history or audit`() {
        val s = Scene()
        s.completeReading()
        val id = created(s.rate(comment = "Con nombre propio"))["id"].asString()

        assertEquals(HttpStatus.FORBIDDEN, call(s.teacher, "/api/objects/valoracion/records").first)
        assertEquals(HttpStatus.FORBIDDEN, call(s.teacher, "/api/objects/valoracion/records/$id").first)
        assertEquals(HttpStatus.FORBIDDEN, call(s.teacher, "/api/objects/puntaje_valoracion/records").first)
        assertEquals(HttpStatus.FORBIDDEN, call(s.teacher, "/api/objects/valoracion/records/$id/history").first)
        assertTrue(call(s.teacher, "/api/audit?objectName=valoracion").first in setOf(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND))
        assertEquals(HttpStatus.FORBIDDEN, call(s.teacher, "/api/lms/ratings/mine").first)
        assertEquals(HttpStatus.FORBIDDEN, call(demoUser("creador"), "/api/objects/valoracion/records/$id").first)
    }

    @Test
    fun `ratings are written only through the command`() {
        val s = Scene()
        s.completeReading()
        val attributes =
            mapOf(
                "estudiante_id" to userId(s.student),
                "matricula" to s.enrollment,
                "destino" to "DOCENCIA",
                "docente_edicion" to s.teacherTarget(),
                "estado_moderacion" to "PUBLICADA",
            )
        assertEquals(HttpStatus.FORBIDDEN, call(s.student, "/api/objects/valoracion/records", mapOf("attributes" to attributes)).first)
        assertTrue(s.stored().isEmpty())
    }

    @Test
    fun `scores, dimensions and comments are checked`() {
        val s = Scene()
        s.completeReading()
        assertEquals(HttpStatus.BAD_REQUEST, s.rate(scores = mapOf("docente_claridad" to 6)).first)
        assertEquals(HttpStatus.BAD_REQUEST, s.rate(scores = mapOf("docente_claridad" to 0)).first)
        assertEquals(HttpStatus.BAD_REQUEST, s.rate(scores = mapOf("claridad" to 3)).first, "dimensión de recursos")
        assertEquals(HttpStatus.BAD_REQUEST, s.rate(scores = emptyMap()).first)
        assertEquals(HttpStatus.BAD_REQUEST, s.rate(comment = "x".repeat(2001)).first)
        assertTrue(s.stored().isEmpty())
        assertEquals(0, s.storedScores())
    }

    @Test
    fun `the sixth change of the day is 429 and changes nothing`() {
        val s = Scene()
        s.completeReading()
        val id = created(s.rate())["id"].asString()
        for (value in 1..4) ok(send("PUT", s.student, "/api/lms/ratings/$id", mapOf("scores" to mapOf("docente_claridad" to value))))

        val (status, problem) = send("PUT", s.student, "/api/lms/ratings/$id", mapOf("scores" to mapOf("docente_claridad" to 5)))
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, status, problem.toString())
        assertEquals(429, problem!!["status"].asInt())
        assertEquals("4", s.scoreValues().joinToString())
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, send("DELETE", s.student, "/api/lms/ratings/$id").first)
        assertEquals(1, s.stored().size)
    }

    @Test
    fun `the same idempotency key rates once and answers the same`() {
        val s = Scene()
        s.completeReading()
        val body = mapOf("type" to "DOCENCIA", "targetId" to s.teacherTarget(), "scores" to mapOf("docente_claridad" to 4))
        val path = "/api/lms/classroom/enrollments/${s.enrollment}/ratings"
        val first = send("POST", s.student, path, body, idempotencyKey = "valorar-1")
        val again = send("POST", s.student, path, body, idempotencyKey = "valorar-1")

        assertEquals(HttpStatus.CREATED, first.first, first.second.toString())
        assertEquals(HttpStatus.CREATED, again.first)
        assertEquals(first.second!!["id"].asString(), again.second!!["id"].asString())
        assertEquals(1, s.stored().size)
    }
}
