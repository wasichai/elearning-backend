package elearning.learning

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import wasichai.core.data.RecordRequest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

// las sesiones (ADR-0016 §5, RF-SES-01/02/04): las crea el docente de la edición, las leen él y sus matriculados
class SessionApiTest : ClassroomApiTestSupport() {
    private val today get() = LocalDate.now(ZoneId.of("America/Lima"))

    private fun body(
        pattern: String = "ONCE",
        link: String? = "https://reunion.example.org/sala",
        endOffset: Long = 0,
        extra: Map<String, Any?> = emptyMap(),
    ) = mapOf(
        "title" to "Teoría",
        "type" to "TEORIA",
        "modality" to "VIRTUAL",
        "durationMinutes" to 90,
        "link" to link,
        "location" to null,
        "lessonId" to null,
        "recurrence" to
            mapOf(
                "pattern" to pattern,
                "days" to emptyList<String>(),
                "startDate" to today.plusDays(1).toString(),
                "endDate" to today.plusDays(1 + endOffset).toString(),
                "localTime" to "08:00",
            ),
    ) + extra

    private fun sessionsPath(code: String) = "/api/lms/teaching/offerings/$code/sessions"

    @Test
    fun `a teacher creates a series and an enrolled student reads it in the offering's zone`() {
        val course = course()
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        val (status, created) = call(teacher, sessionsPath(course.offering), body("DAILY", endOffset = 2))
        assertEquals(HttpStatus.CREATED, status, created.toString())
        assertEquals(3, created!!.size())

        val read = ok(call(student, "/api/lms/classroom/enrollments/$enrollment/sessions"))
        assertEquals(3, read.size())
        val first = read[0]
        assertEquals("Teoría", first["title"].asString())
        assertEquals("America/Lima", first["timeZone"].asString())
        assertEquals("PROGRAMADA", first["status"].asString())
        assertEquals("https://reunion.example.org/sala", first["link"].asString())
        // Lima es UTC-5 sin horario de verano: las 08:00 son las 13:00 Z, y dura 90 minutos
        assertEquals("${today.plusDays(1)}T13:00:00Z", first["start"].asString())
        assertEquals("${today.plusDays(1)}T14:30:00Z", first["end"].asString())
        assertTrue(first["attendance"].isNull)
        assertEquals(3, ok(call(teacher, sessionsPath(course.offering))).size())
    }

    @Test
    fun `a link must be https and not internal, and the request must make sense`() {
        val course = course()
        val teacher = newTeacher().also { assign(it, course) }
        val invalid =
            listOf(
                body(link = "http://reunion.example.org"),
                body(link = "file:///etc/passwd"),
                body(link = "https://localhost/sala"),
                body(link = "https://127.0.0.1/sala"),
                body(link = "https://192.168.1.10/sala"),
                body(link = "https://169.254.169.254/latest"),
                body(pattern = "MOONLY"),
                body(extra = mapOf("durationMinutes" to 0)),
                body(extra = mapOf("type" to "FIESTA")),
                body(extra = mapOf("title" to " ")),
                body(extra = mapOf("lessonId" to course().reading)),
            )
        for (one in invalid) {
            assertEquals(HttpStatus.BAD_REQUEST, call(teacher, sessionsPath(course.offering), one).first, one.toString())
        }
        assertEquals(0, ok(call(teacher, sessionsPath(course.offering))).size())
    }

    @Test
    fun `nobody outside the offering reaches its sessions`() {
        val course = course()
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val session = call(teacher, sessionsPath(course.offering), body()).second!![0]["id"].asString()
        val other = newStudent()
        enroll(other, course())
        val outsider = newTeacher().also { assign(it, course()) }

        assertEquals(HttpStatus.NOT_FOUND, call(other, "/api/lms/classroom/enrollments/$enrollment/sessions").first)
        assertEquals(HttpStatus.NOT_FOUND, call(outsider, sessionsPath(course.offering)).first)
        assertEquals(HttpStatus.NOT_FOUND, call(outsider, sessionsPath(course.offering), body()).first)
        assertEquals(HttpStatus.NOT_FOUND, call(student, sessionsPath(course.offering), body()).first)
        assertEquals(HttpStatus.NOT_FOUND, call(other, "/api/objects/sesion/records/$session").first)
        assertEquals(HttpStatus.NOT_FOUND, call(outsider, "/api/objects/sesion/records/$session").first)
        assertEquals(HttpStatus.OK, call(student, "/api/objects/sesion/records/$session").first)
        // ni por la API genérica
        assertTrue(call(teacher, "/api/objects/sesion/records", mapOf("attributes" to mapOf("titulo" to "x"))).first.is4xxClientError)
    }

    private fun agendaPath(
        from: Any?,
        to: Any?,
    ) = "/api/lms/classroom/sessions" +
        listOfNotNull(from?.let { "from=$it" }, to?.let { "to=$it" }).joinToString("&", if (from != null || to != null) "?" else "")

    // mañana a las 08:00 de Lima: 13:00 Z
    private val tomorrow get() = Instant.parse("${today.plusDays(1)}T00:00:00Z")

    private fun withdraw(enrollment: String) =
        runBlocking {
            records.asPlatform(ORG) {
                records.patch("matricula", UUID.fromString(enrollment), RecordRequest(mapOf("estado" to "RETIRADA")), "Baja de prueba", null)
            }
        }

    @Test
    fun `agenda includes sessions of every active enrollment in range`() {
        val first = course()
        val second = course()
        val teacher =
            newTeacher().also {
                assign(it, first)
                assign(it, second)
            }
        val student = newStudent()
        val firstEnrollment = enroll(student, first)["id"].asString()
        val secondEnrollment = enroll(student, second)["id"].asString()
        call(teacher, sessionsPath(first.offering), body("DAILY", endOffset = 1))
        call(teacher, sessionsPath(second.offering), body(extra = mapOf("title" to "Laboratorio")))
        // la tercera serie sigue días más allá del intervalo de la consulta; solo cuentan mañana y pasado mañana
        call(teacher, sessionsPath(first.offering), body("DAILY", endOffset = 5))

        val agenda = ok(call(student, agendaPath(tomorrow, tomorrow.plusSeconds(2 * 86_400))))
        // [from, to) con inicio a las 13:00 Z: mañana y pasado mañana, en cada edición (3 con la serie de endOffset 5 que empieza mañana)
        val starts = agenda.toList().map { it["start"].asString() }
        assertEquals(starts.sorted(), starts)
        assertEquals(setOf(firstEnrollment, secondEnrollment), agenda.toList().map { it["enrollmentId"].asString() }.toSet())
        assertEquals(5, agenda.size(), agenda.toString())
        val lab = agenda.toList().single { it["title"].asString() == "Laboratorio" }
        assertEquals(secondEnrollment, lab["enrollmentId"].asString())
        assertEquals(second.offering, lab["offeringCode"].asString())
        assertTrue(lab["courseTitle"].asString().isNotBlank())
        assertEquals("PROGRAMADA", lab["status"].asString())
        assertTrue(lab["attendance"].isNull)
        // el intervalo es semiabierto: el instante exacto de un inicio entra por from y no por to
        val exact = Instant.parse(lab["start"].asString())
        assertEquals(1, ok(call(student, agendaPath(exact, exact.plusSeconds(1)))).toList().filter { it["id"].asString() == lab["id"].asString() }.size)
        assertEquals(0, ok(call(student, agendaPath(exact.minusSeconds(1), exact))).size())
    }

    @Test
    fun `agenda excludes withdrawn enrollments`() {
        val kept = course()
        val dropped = course()
        val teacher =
            newTeacher().also {
                assign(it, kept)
                assign(it, dropped)
            }
        val student = newStudent()
        enroll(student, kept)
        withdraw(enroll(student, dropped)["id"].asString())
        call(teacher, sessionsPath(kept.offering), body())
        call(teacher, sessionsPath(dropped.offering), body())

        val agenda = ok(call(student, agendaPath(tomorrow, tomorrow.plusSeconds(86_400))))
        assertEquals(listOf(kept.offering), agenda.toList().map { it["offeringCode"].asString() })
    }

    @Test
    fun `agenda excludes other students sessions`() {
        val mine = course()
        val theirs = course()
        val teacher =
            newTeacher().also {
                assign(it, mine)
                assign(it, theirs)
            }
        val student = newStudent()
        enroll(student, mine)
        enroll(newStudent(), theirs)
        call(teacher, sessionsPath(mine.offering), body())
        call(teacher, sessionsPath(theirs.offering), body())

        val agenda = ok(call(student, agendaPath(tomorrow, tomorrow.plusSeconds(86_400))))
        assertEquals(listOf(mine.offering), agenda.toList().map { it["offeringCode"].asString() })
        // quien no cursa nada ve una agenda vacía, no la de otro
        assertEquals(0, ok(call(newStudent(), agendaPath(tomorrow, tomorrow.plusSeconds(86_400)))).size())
    }

    @Test
    fun `agenda rejects inverted or over 62 day range`() {
        val student = newStudent()
        val from = tomorrow
        val invalid =
            listOf(
                agendaPath(null, null),
                agendaPath(from, null),
                agendaPath(null, from.plusSeconds(86_400)),
                agendaPath("ayer", from),
                agendaPath(from, "mañana"),
                agendaPath(from, from),
                agendaPath(from.plusSeconds(86_400), from),
                agendaPath(from, from.plusSeconds(63 * 86_400L)),
            )
        for (path in invalid) assertEquals(HttpStatus.BAD_REQUEST, call(student, path).first, path)
        // 62 días justos sí
        assertEquals(HttpStatus.OK, call(student, agendaPath(from, from.plusSeconds(62 * 86_400L))).first)
    }

    @Test
    fun `agenda keeps each session's own zone and applies the range to instants`() {
        val lima = course()
        val madrid = course(timeZone = "Europe/Madrid")
        val student = newStudent()
        enroll(student, lima)
        enroll(student, madrid)
        for (course in listOf(lima, madrid)) {
            val teacher = newTeacher().also { assign(it, course) }
            assertEquals(HttpStatus.CREATED, call(teacher, sessionsPath(course.offering), body()).first)
        }
        // las 08:00 de cada zona, calculadas con su regla (el horario de verano de Madrid no se fija a mano)
        val day = today.plusDays(1)
        val madridStart = ZonedDateTime.of(day, LocalTime.of(8, 0), ZoneId.of("Europe/Madrid")).toInstant()
        val limaStart = ZonedDateTime.of(day, LocalTime.of(8, 0), ZoneId.of("America/Lima")).toInstant()
        val agenda = ok(call(student, agendaPath(madridStart, limaStart.plusSeconds(1)))).toList()
        assertEquals(
            listOf(madrid.offering to "Europe/Madrid", lima.offering to "America/Lima"),
            agenda.map {
                it["offeringCode"].asString() to
                    it["timeZone"].asString()
            },
        )
        assertEquals(listOf(madridStart.toString(), limaStart.toString()), agenda.map { it["start"].asString() })
        // [from, to): el fin excluye la de Lima; el inicio incluye la de Madrid y un segundo después ya no
        assertEquals(listOf(madrid.offering), ok(call(student, agendaPath(madridStart, limaStart))).toList().map { it["offeringCode"].asString() })
        assertEquals(
            listOf(lima.offering),
            ok(call(student, agendaPath(madridStart.plusSeconds(1), limaStart.plusSeconds(1)))).toList().map {
                it["offeringCode"].asString()
            },
        )
    }

    @Test
    fun `agenda answers forbidden before bad request to someone who cannot read enrollments`() {
        val email = "${uniqueName("cre")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Creadora", "password" to DEMO_PASSWORD, "roles" to listOf("CREADOR")))
            .exchange()
            .expectStatus()
            .isCreated
        val creator = bearer(email, DEMO_PASSWORD)
        val from = tomorrow
        assertEquals(HttpStatus.FORBIDDEN, call(creator, agendaPath(from, from.plusSeconds(86_400))).first)
        for (path in listOf(agendaPath(null, null), agendaPath(from, from), agendaPath("ayer", from))) {
            assertEquals(HttpStatus.FORBIDDEN, call(creator, path).first, path)
        }
    }

    private fun finish(enrollment: String) =
        runBlocking {
            records.asPlatform(ORG) {
                records.patch("matricula", UUID.fromString(enrollment), RecordRequest(mapOf("estado" to "FINALIZADA")), null, null)
            }
        }

    @Test
    fun `agenda keeps the sessions of a finished enrollment with its course title`() {
        val course = course()
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        call(teacher, sessionsPath(course.offering), body())
        finish(enrollment)

        val agenda = ok(call(student, agendaPath(tomorrow, tomorrow.plusSeconds(86_400))))
        assertEquals(1, agenda.size(), agenda.toString())
        assertEquals(enrollment, agenda[0]["enrollmentId"].asString())
        assertTrue(agenda[0]["courseTitle"].asString().isNotBlank())
    }

    @Test
    fun `agenda returns the attendance the teacher took`() {
        val course = course()
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val sessions = call(teacher, sessionsPath(course.offering), body("DAILY", endOffset = 1)).second!!
        val marked = sessions[0]["id"].asString()
        val taken =
            put(
                teacher,
                "/api/lms/teaching/sessions/$marked/attendance",
                mapOf("records" to listOf(mapOf("enrollmentId" to enrollment, "status" to "TARDE"))),
            )
        assertEquals(HttpStatus.OK, taken.first, taken.second.toString())

        val agenda = ok(call(student, agendaPath(tomorrow, tomorrow.plusSeconds(3 * 86_400)))).toList()
        assertEquals("TARDE", agenda.single { it["id"].asString() == marked }["attendance"].asString())
        assertTrue(agenda.single { it["id"].asString() != marked }["attendance"].isNull)
    }

    @Test
    fun `agenda of someone who sees every enrollment is still only their own`() {
        val taught = course()
        val studied = course()
        val teacher = acceptTerms(userWith("DOCENTE", "ESTUDIANTE")).also { assign(it, taught) }
        val other = newTeacher().also { assign(it, studied) }
        enroll(newStudent(), taught)
        val mine = enroll(teacher, studied)["id"].asString()
        call(teacher, sessionsPath(taught.offering), body())
        call(other, sessionsPath(studied.offering), body())

        val agenda = ok(call(teacher, agendaPath(tomorrow, tomorrow.plusSeconds(86_400))))
        assertEquals(listOf(mine), agenda.toList().map { it["enrollmentId"].asString() })
        assertEquals(listOf(studied.offering), agenda.toList().map { it["offeringCode"].asString() })
    }

    // CREADOR no lee matrículas: no tiene aula
    private fun creator(): String = userWith("CREADOR")

    private fun userWith(vararg roles: String): String {
        val email = "${uniqueName("mix")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Persona", "password" to DEMO_PASSWORD, "roles" to roles.toList()))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    @Test
    fun `agenda requires classroom permission`() {
        val range = agendaPath(tomorrow, tomorrow.plusSeconds(86_400))
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, range).first)
        assertEquals(HttpStatus.FORBIDDEN, call(creator(), range).first)
    }

    @Test
    fun `agenda keeps the offering time zone`() {
        val course = course()
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        enroll(student, course)
        call(teacher, sessionsPath(course.offering), body())

        val session = ok(call(student, agendaPath(tomorrow, tomorrow.plusSeconds(86_400))))[0]
        assertEquals("America/Lima", session["timeZone"].asString())
        assertEquals("${today.plusDays(1)}T13:00:00Z", session["start"].asString())
    }
}
