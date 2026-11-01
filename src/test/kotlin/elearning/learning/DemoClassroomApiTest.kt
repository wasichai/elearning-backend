package elearning.learning

import elearning.LmsApiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

// la demo del aula contra el servidor real, tal como la deja model/apply.py --demo: el estudiante demo matriculado por el
// comando en la edición abierta de SRTM, con una lectura HTML, una guía PDF, un vídeo y una
// presentación, cada uno con su archivo, y en la del curso corto, con su intento calificado
class DemoClassroomApiTest : LmsApiTest() {
    @Test
    fun `the demo student studies the open SRTM offering with a reading, a pdf, a video and a presentation`() {
        val student = demoUser("estudiante")
        val enrollment = get("/api/lms/classroom/enrollments", student).values().single { it["offering"]["code"].asString() == "SRTM-ANU-101-A" }
        assertEquals("INSCRIPCION_ABIERTA", enrollment["offering"]["status"].asString())

        val classroom = get("/api/lms/classroom/enrollments/${enrollment["id"].asString()}", student)
        val lessons = classroom["units"].values().flatMap { it["lessons"].values() }
        val resources = lessons.flatMap { lesson -> lesson["resources"].values().map { lesson to it } }

        assertEquals(setOf("HTML", "PDF", "VIDEO", "PRESENTACION"), resources.map { it.second["type"].asString() }.toSet())
        val video = resources.single { it.second["type"].asString() == "VIDEO" }.first
        assertEquals("VIDEO", video["type"].asString())
        assertEquals("VIDEO_END", video["completion"].asString())
        val presentation = resources.single { it.second["type"].asString() == "PRESENTACION" }.first
        assertEquals("Consultar el padrón con filtros", presentation["title"].asString())
        assertEquals("PRESENTACION", presentation["type"].asString())
        assertEquals("EXPLICIT", presentation["completion"].asString())
        for ((_, resource) in resources) {
            val file = resource["file"]
            val local = Path.of("model/demo/archivos", file["name"].asString())
            val bytes =
                client
                    .get()
                    .uri(file["url"].asString())
                    .header("Authorization", student)
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody(ByteArray::class.java)
                    .returnResult()
                    .responseBody!!
            assertTrue(bytes.contentEquals(Files.readAllBytes(local)), "${file["name"]}: el archivo baja tal como se subió")
        }
    }

    // RNF-OSS-06: la semilla trae un intento enviado y calificado. lo hizo el estudiante demo por el aula y lo corrigió el
    // docente demo asignado, por los mismos comandos que una persona
    @Test
    fun `the demo student has a graded attempt in the short course, graded by the demo teacher`() {
        val student = demoUser("estudiante")
        val teacher = demoUser("docente")
        val enrollment = get("/api/lms/classroom/enrollments", student).values().single { it["offering"]["code"].asString() == "CORTO-101-A" }
        val lessons =
            get("/api/lms/classroom/enrollments/${enrollment["id"].asString()}", student)["units"]
                .values()
                .flatMap { it["lessons"].values() }
        val assessment = lessons.single { it["completion"].asString() == "ASSESSMENT" }["assessment"]
        assertEquals(1, assessment["attemptsUsed"].asInt())
        assertEquals(80, assessment["best"]["percent"].asInt())
        assertTrue(assessment["best"]["passed"].asBoolean())

        val row =
            get("/api/lms/teaching/offerings/CORTO-101-A/attempts?status=GRADED", teacher)
                .values()
                .single { it["email"].asString() == "estudiante@demo.wasichai-learning.test" }
        val grade = get("/api/lms/teaching/attempts/${row["attemptId"].asString()}", teacher)["history"].single()
        assertEquals(get("/api/auth/me", teacher)["userId"].asString(), grade["authorId"].asString())
        assertEquals(4.0, grade["points"].asDouble())
    }

    @Test
    fun `the short course goes from enrolling to its certificate with its assigned teacher`() {
        val student = bearerOfNewStudent()
        val teacher = demoUser("docente")
        val enrolled =
            client
                .post()
                .uri(
                    "/api/lms/enrollments",
                ).header("Authorization", student)
                .bodyValue(mapOf("offering" to "CORTO-101-A"))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(201, enrolled.status.value(), enrolled.responseBody)
        val enrollment = json.readTree(enrolled.responseBody)["id"].asString()
        val classroom = get("/api/lms/classroom/enrollments/$enrollment", student)
        val lessons = classroom["units"].values().flatMap { it["lessons"].values() }
        assertEquals(listOf("EXPLICIT", "ASSESSMENT", "ATTENDANCE"), lessons.map { it["completion"].asString() })
        assertEquals(1, lessons[0]["resources"].size())
        assertEquals(
            listOf(
                "LESSONS",
                "ASSESSMENTS",
                "ATTENDANCE",
            ),
            get("/api/lms/classroom/enrollments/$enrollment/completion", student)["rules"].values().map {
                it["rule"].asString()
            },
        )

        fun post(
            token: String,
            path: String,
            body: Map<String, Any?>,
        ) = client.post().uri(path).header("Authorization", token).bodyValue(body).exchange().expectBody(String::class.java).returnResult().let { r ->
            assertTrue(r.status.is2xxSuccessful, "$path: ${r.responseBody}")
            json.readTree(r.responseBody)
        }
        post(
            student,
            "/api/lms/classroom/enrollments/$enrollment/lessons/${lessons[0]["id"].asString()}/events",
            mapOf(
                "type" to "MARKED_COMPLETE",
                "clientEventId" to
                    java.util.UUID
                        .randomUUID()
                        .toString(),
            ),
        )
        val attempt = post(student, "/api/lms/classroom/enrollments/$enrollment/lessons/${lessons[1]["id"].asString()}/attempts", emptyMap())
        val id = attempt["id"].asString()
        val questions = attempt["questions"].values().toList()

        fun option(
            index: Int,
            text: String,
        ) = questions[index]["options"].values().single { it["text"].asString().startsWith(text) }["id"].asString()
        val answers =
            listOf(
                mapOf("questionId" to questions[0]["id"].asString(), "optionIds" to listOf(option(0, "Qué pasó"))),
                mapOf("questionId" to questions[1]["id"].asString(), "optionIds" to listOf(option(1, "Falso"))),
                mapOf("questionId" to questions[2]["id"].asString(), "text" to "No pudimos guardar: falta la fecha. Complétala e inténtalo de nuevo."),
            )
        client
            .put()
            .uri(
                "/api/lms/classroom/attempts/$id/answers",
            ).header("Authorization", student)
            .bodyValue(mapOf("answers" to answers))
            .exchange()
            .expectStatus()
            .isOk
        assertTrue(post(student, "/api/lms/classroom/attempts/$id/submit", emptyMap())["result"]["pending"].asBoolean())

        // el docente demo está asignado: corrige y pasa asistencia
        post(teacher, "/api/lms/teaching/attempts/$id/grades", mapOf("questionId" to questions[2]["id"].asString(), "points" to 6, "comment" to "Claro y útil"))
        val session = get("/api/lms/teaching/offerings/CORTO-101-A/sessions", teacher).values().single()["id"].asString()
        client
            .put()
            .uri("/api/lms/teaching/sessions/$session/attendance")
            .header("Authorization", teacher)
            .bodyValue(
                mapOf("records" to listOf(mapOf("enrollmentId" to enrollment, "status" to "PRESENTE"))),
            ).exchange()
            .expectStatus()
            .isOk

        assertTrue(get("/api/lms/classroom/enrollments/$enrollment/completion", student)["met"].asBoolean())
        val certificate = post(student, "/api/lms/classroom/enrollments/$enrollment/certificate", mapOf("holderName" to "Estudiante de prueba"))
        assertEquals(3, certificate["totalHours"].asInt())
        assertEquals("CORTO-101", get("/api/lms/public/certificates/${certificate["code"].asString()}", student)["course"]["code"].asString())
    }

    private fun bearerOfNewStudent(): String {
        val email = "${uniqueName("est")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", bearer())
            .bodyValue(mapOf("email" to email, "displayName" to "Estudiante", "password" to DEMO_PASSWORD, "roles" to listOf("ESTUDIANTE")))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }
}
