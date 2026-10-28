package elearning.learning

import elearning.LmsApiTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

// reglas del progreso (ADR-0012): solo avanza, cada tipo de lección se completa con su evidencia y el tiempo con la
// página abierta no cuenta. cada prueba usa un curso propio con lectura, vídeo y evaluación, puesto por la plataforma
// como lo haría la carga de la demo (que no tiene vídeos), y retirado al terminar para no aparecer en el catálogo de otras
// pruebas
class LessonProgressApiTest : LmsApiTest() {
    @Autowired
    private lateinit var records: RecordService

    private val admin by lazy { bearer() }
    private val published = mutableListOf<String>()

    @AfterEach
    fun retireCourses() =
        runBlocking {
            records.asPlatform(ORG) {
                published.forEach { records.patch("version_curso", UUID.fromString(it), RecordRequest(mapOf("estado" to "RETIRADA")), null, null) }
            }
            published.clear()
        }

    private class Course(
        val offering: String,
        val reading: String,
        val video: String,
        val assessment: String,
    )

    private fun course(): Course =
        runBlocking {
            records.asPlatform(ORG) {
                val code = uniqueName("PRG-").uppercase()
                val course =
                    records.create(
                        "curso",
                        RecordRequest(
                            mapOf(
                                "codigo" to code,
                                "slug" to code.lowercase(),
                                "titulo" to "Curso de progreso",
                                "idioma" to "es",
                                "nivel" to "INICIAL",
                            ),
                        ),
                    )
                val version =
                    records.create(
                        "version_curso",
                        RecordRequest(
                            mapOf(
                                "curso" to course.id,
                                "numero" to 1,
                                "estado" to "BORRADOR",
                                "modalidad" to "ASINCRONA",
                                "semanas" to 1,
                                "sistema_credito" to "NINGUNO",
                                "contenido_ilustrativo" to true,
                            ),
                        ),
                    )
                val unit = records.create("unidad", RecordRequest(mapOf("version_curso" to version.id, "orden" to 1, "titulo" to "Unidad 1")))

                suspend fun lesson(
                    order: Int,
                    type: String,
                ) = records.create("leccion", RecordRequest(mapOf("unidad" to unit.id, "orden" to order, "titulo" to "Lección $order", "tipo" to type)))
                val reading = lesson(1, "LECTURA")
                val video = lesson(2, "VIDEO")
                val assessment = lesson(3, "EVALUACION")
                records.patch("version_curso", UUID.fromString(version.id), RecordRequest(mapOf("estado" to "PUBLICADA")), null, null)
                published += version.id
                val today = LocalDate.now(ZoneId.of("America/Lima"))
                records.create(
                    "edicion_curso",
                    RecordRequest(
                        mapOf(
                            "codigo" to code,
                            "version_curso" to version.id,
                            "estado" to "INSCRIPCION_ABIERTA",
                            "fecha_inicio" to today.toString(),
                            "fecha_fin" to today.plusDays(30).toString(),
                            "zona_horaria" to "America/Lima",
                            "inscripcion_desde" to today.minusDays(1).toString(),
                            "inscripcion_hasta" to today.plusDays(5).toString(),
                        ),
                    ),
                )
                Course(code, reading.id, video.id, assessment.id)
            }
        }

    private fun newStudent(): String {
        val email = "${uniqueName("est")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Estudiante", "password" to DEMO_PASSWORD, "roles" to listOf("ESTUDIANTE")))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    private fun post(
        token: String?,
        path: String,
        body: Map<String, Any?>,
    ): Pair<HttpStatus, JsonNode?> {
        val spec = client.post().uri(path).bodyValue(body)
        val r = (if (token == null) spec else spec.header("Authorization", token)).exchange().expectBody(String::class.java).returnResult()
        return HttpStatus.valueOf(r.status.value()) to r.responseBody?.let { runCatching { json.readTree(it) }.getOrNull() }
    }

    private fun enroll(
        token: String,
        course: Course,
    ): JsonNode {
        val (status, body) = post(token, "/api/lms/enrollments", mapOf("offering" to course.offering))
        assertTrue(status.is2xxSuccessful, body.toString())
        return body!!
    }

    private fun event(
        token: String?,
        enrollment: String,
        lesson: String,
        type: String,
        clientEventId: String = UUID.randomUUID().toString(),
    ) = post(token, "/api/lms/classroom/enrollments/$enrollment/lessons/$lesson/events", mapOf("type" to type, "clientEventId" to clientEventId))

    @Test
    fun `a reading is started and then completed by the student, and never goes back`() {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        val (started, s) = event(student, enrollment, course.reading, "STARTED")
        assertEquals(HttpStatus.OK, started, s.toString())
        assertEquals("INICIADA", s!!["status"].asString())
        assertTrue(s["completedAt"].isNull)

        val (completed, c) = event(student, enrollment, course.reading, "MARKED_COMPLETE")
        assertEquals(HttpStatus.OK, completed, c.toString())
        assertEquals("COMPLETADA", c!!["status"].asString())
        assertEquals("FINALIZACION_EXPLICITA", c["evidence"].asString())
        assertEquals(s["startedAt"].asString(), c["startedAt"].asString())

        val (_, again) = event(student, enrollment, course.reading, "STARTED")
        assertEquals("COMPLETADA", again!!["status"].asString())
        assertEquals(c["completedAt"].asString(), again["completedAt"].asString())

        // la matrícula cuenta la lectura y apunta a la siguiente; la evaluación (F3) no cuenta
        val view = enroll(student, course)
        assertEquals(1, view["progress"]["completedLessons"].asInt())
        assertEquals(2, view["progress"]["totalLessons"].asInt())
        assertEquals(50, view["progress"]["percent"].asInt())
        assertEquals(course.video, view["nextLesson"]["id"].asString())
    }

    @Test
    fun `completing straight away also records when it started`() {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        val (_, c) = event(student, enrollment, course.reading, "MARKED_COMPLETE")

        assertEquals("COMPLETADA", c!!["status"].asString())
        assertFalse(c["startedAt"].isNull)
    }

    @Test
    fun `each lesson type is completed only with its own evidence`() {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        assertEquals(HttpStatus.CONFLICT, event(student, enrollment, course.video, "MARKED_COMPLETE").first)
        assertEquals(HttpStatus.CONFLICT, event(student, enrollment, course.reading, "VIDEO_ENDED").first)
        assertEquals(HttpStatus.CONFLICT, event(student, enrollment, course.assessment, "MARKED_COMPLETE").first)
        assertEquals(HttpStatus.CONFLICT, event(student, enrollment, course.assessment, "VIDEO_ENDED").first)

        val (_, started) = event(student, enrollment, course.assessment, "STARTED")
        assertEquals("INICIADA", started!!["status"].asString())
        val (ended, v) = event(student, enrollment, course.video, "VIDEO_ENDED")
        assertEquals(HttpStatus.OK, ended, v.toString())
        assertEquals("COMPLETADA", v!!["status"].asString())
        assertEquals("FIN_DE_VIDEO", v["evidence"].asString())
    }

    @Test
    fun `the same event sent twice counts once`() {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val id = UUID.randomUUID().toString()

        val (first, a) = event(student, enrollment, course.reading, "STARTED", id)
        val (second, b) = event(student, enrollment, course.reading, "STARTED", id)

        assertEquals(HttpStatus.OK, first)
        assertEquals(HttpStatus.OK, second)
        assertEquals(a, b)
        assertEquals(1, get("/api/objects/progreso_leccion/records?matricula=$enrollment", student)["totalElements"].asInt())
    }

    @Test
    fun `simultaneous events on a lesson leave one progress record and no errors`() {
        val course = course()
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val pool = Executors.newFixedThreadPool(6)
        val statuses =
            try {
                pool
                    .invokeAll(
                        List(6) { i -> Callable { event(student, enrollment, course.reading, if (i % 2 == 0) "STARTED" else "MARKED_COMPLETE").first } },
                    ).map { it.get() }
            } finally {
                pool.shutdown()
            }

        assertTrue(statuses.all { it == HttpStatus.OK }, statuses.toString())
        val progress = get("/api/objects/progreso_leccion/records?matricula=$enrollment", student)
        assertEquals(1, progress["totalElements"].asInt())
        assertEquals("COMPLETADA", progress["content"][0]["attributes"]["estado"].asString())
    }

    @Test
    fun `progress is only written through the classroom, and only on your own enrollment`() {
        val course = course()
        val student = newStudent()
        val other = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val otherEnrollment = enroll(other, course())["id"].asString()
        event(student, enrollment, course.reading, "STARTED")

        // ajena, inexistente, o una lección de otra versión: lo mismo, no existe
        assertEquals(HttpStatus.NOT_FOUND, event(other, enrollment, course.reading, "STARTED").first)
        assertEquals(HttpStatus.NOT_FOUND, event(student, UUID.randomUUID().toString(), course.reading, "STARTED").first)
        assertEquals(HttpStatus.NOT_FOUND, event(other, otherEnrollment, course.reading, "STARTED").first)
        assertEquals(HttpStatus.BAD_REQUEST, event(student, enrollment, course.reading, "OPENED_FOR_AN_HOUR").first)
        assertEquals(
            HttpStatus.BAD_REQUEST,
            post(
                student,
                "/api/lms/classroom/enrollments/$enrollment/lessons/${course.reading}/events",
                mapOf("type" to "STARTED"),
            ).first,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, event(null, enrollment, course.reading, "STARTED").first)

        // el progreso no se escribe por la API genérica, y nadie lee el de otro
        val forged = mapOf("attributes" to mapOf("matricula" to enrollment, "leccion" to course.video, "estudiante_id" to "x", "estado" to "COMPLETADA"))
        assertTrue(post(student, "/api/objects/progreso_leccion/records", forged).first.is4xxClientError)
        assertEquals(0, get("/api/objects/progreso_leccion/records?matricula=$enrollment", other)["totalElements"].asInt())
    }

    companion object {
        val ORG: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
