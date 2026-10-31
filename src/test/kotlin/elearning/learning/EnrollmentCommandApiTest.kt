package elearning.learning

import elearning.LmsApiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

// el comando de matrícula (ADR-0012/0013): ventana en la zona de la edición, cupo contado bajo cerrojo en la misma
// transacción, idempotente. cada prueba crea su edición con fechas relativas a hoy: las de la demo envejecen (#96)
class EnrollmentCommandApiTest : LmsApiTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var transactions: TransactionalOperator

    private val admin by lazy { bearer() }
    private val zone = ZoneId.of("America/Lima")
    private val today get() = LocalDate.now(zone)

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

    private fun publishedVersion(code: String = "SRTM-ANU-101"): String {
        val course = get("/api/objects/curso/records?codigo=$code", admin)["content"][0]["id"].asString()
        return get("/api/objects/version_curso/records?curso=$course&estado=PUBLICADA", admin)["content"][0]["id"].asString()
    }

    private fun offering(
        state: String = "INSCRIPCION_ABIERTA",
        capacity: Int? = 30,
        from: LocalDate = today.minusDays(1),
        until: LocalDate = today.plusDays(5),
        version: String = publishedVersion(),
    ): String {
        val code = uniqueName("ED-").uppercase()
        client
            .post()
            .uri("/api/objects/edicion_curso/records")
            .header("Authorization", admin)
            .bodyValue(
                mapOf(
                    "attributes" to
                        mapOf(
                            "codigo" to code,
                            "version_curso" to version,
                            "estado" to state,
                            "fecha_inicio" to today.plusDays(10).toString(),
                            "fecha_fin" to today.plusDays(40).toString(),
                            "zona_horaria" to "America/Lima",
                            "inscripcion_desde" to from.toString(),
                            "inscripcion_hasta" to until.toString(),
                            "capacidad" to capacity,
                        ),
                ),
            ).exchange()
            .expectStatus()
            .isCreated
        return code
    }

    private fun enroll(
        token: String?,
        code: String,
    ): Pair<HttpStatus, JsonNode?> {
        val spec = client.post().uri("/api/lms/enrollments").bodyValue(mapOf("offering" to code))
        val result = (if (token == null) spec else spec.header("Authorization", token)).exchange().expectBody(String::class.java).returnResult()
        return HttpStatus.valueOf(result.status.value()) to result.responseBody?.let { json.readTree(it) }
    }

    @Test
    fun `a student enrolls in an open offering and gets their enrollment`() {
        val code = offering()
        val (status, body) = enroll(newStudent(), code)

        assertEquals(HttpStatus.CREATED, status, body.toString())
        assertEquals("ACTIVA", body!!["status"].asString())
        assertEquals("SRTM-ANU-101", body["course"]["code"].asString())
        assertEquals(code, body["offering"]["code"].asString())
        assertEquals("America/Lima", body["offering"]["timeZone"].asString())
        assertEquals(1, body["version"].asInt())
        assertEquals(0, body["progress"]["completedLessons"].asInt())
        assertTrue(body["progress"]["totalLessons"].asInt() > 0)
        assertEquals(1, body["nextLesson"]["unitOrder"].asInt())
        assertEquals(1, body["nextLesson"]["lessonOrder"].asInt())
    }

    @Test
    fun `enrolling again returns the same enrollment`() {
        val code = offering()
        val student = newStudent()
        val (first, a) = enroll(student, code)
        val (second, b) = enroll(student, code)

        assertEquals(HttpStatus.CREATED, first)
        assertEquals(HttpStatus.OK, second)
        assertEquals(a!!["id"].asString(), b!!["id"].asString())
    }

    @Test
    fun `outside its window or state an offering takes no enrollments`() {
        val student = newStudent()
        for (code in listOf(
            offering(state = "PROGRAMADA"),
            offering(from = today.plusDays(1), until = today.plusDays(5)),
            offering(from = today.minusDays(10), until = today.minusDays(1)),
        )) {
            val (status, body) = enroll(student, code)
            assertEquals(HttpStatus.CONFLICT, status, "$code: $body")
        }
    }

    @Test
    fun `an unknown offering, or one of an unpublished version, is not found`() {
        val draft = get("/api/objects/version_curso/records?estado=BORRADOR&size=1", admin)["content"][0]["id"].asString()

        assertEquals(HttpStatus.NOT_FOUND, enroll(newStudent(), "NO-EXISTE").first)
        assertEquals(HttpStatus.NOT_FOUND, enroll(newStudent(), offering(version = draft)).first)
    }

    @Test
    fun `a full offering takes no more students`() {
        val code = offering(capacity = 1)

        assertEquals(HttpStatus.CREATED, enroll(newStudent(), code).first)
        assertEquals(HttpStatus.CONFLICT, enroll(newStudent(), code).first)
    }

    @Test
    fun `simultaneous enrollments never exceed the capacity`() {
        val code = offering(capacity = 1)
        val students = (1..5).map { newStudent() }
        val pool = Executors.newFixedThreadPool(students.size)
        val statuses = pool.invokeAll(students.map { s -> Callable { enroll(s, code).first } }).map { it.get() }
        pool.shutdown()

        assertEquals(1, statuses.count { it == HttpStatus.CREATED }, statuses.toString())
        assertEquals(students.size - 1, statuses.count { it == HttpStatus.CONFLICT }, statuses.toString())
    }

    // la carrera, sin depender de la suerte: la prueba retiene la fila del cupo mientras dos altas llegan al punto
    // crítico. con FOR UPDATE las dos esperan y la segunda ve el cupo ocupado; sin él, las dos leen 0 y entran
    @Test
    fun `two enrollments racing for the last place cannot both get in`() {
        val code = offering(capacity = 1)
        val offeringId = UUID.fromString(get("/api/objects/edicion_curso/records?codigo=$code", admin)["content"][0]["id"].asString())
        val org = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val students = listOf(newStudent(), newStudent())
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val statuses =
            runBlocking(Dispatchers.IO) {
                val holder = async { holdOccupancy(org, offeringId, held, release) }
                held.await()
                val racing = students.map { s -> async { enroll(s, code).first } }
                // tiempo de sobra para que las dos lleguen a leer el cupo mientras la fila sigue retenida
                Thread.sleep(1500)
                release.complete(Unit)
                holder.await()
                racing.map { it.await() }
            }

        assertEquals(listOf(HttpStatus.CREATED, HttpStatus.CONFLICT), statuses.sortedBy { it.value() }, statuses.toString())
    }

    // revisión de la Fase 2 (L10): estado, ventana y cupo se leían antes del cerrojo, así que un cierre que llegaba
    // mientras el alta esperaba no contaba. la prueba cierra la edición con la fila del cupo retenida
    @Test
    fun `an offering closed while an enrollment waits for the lock takes no enrollment`() {
        val code = offering(capacity = 5)
        val offeringId = get("/api/objects/edicion_curso/records?codigo=$code", admin)["content"][0]["id"].asString()
        val org = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val student = newStudent()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val status =
            runBlocking(Dispatchers.IO) {
                val holder = async { holdOccupancy(org, UUID.fromString(offeringId), held, release) }
                held.await()
                val waiting = async { enroll(student, code).first }
                // tiempo de sobra para que el alta llegue al cerrojo antes del cierre
                Thread.sleep(1500)
                client
                    .patch()
                    .uri("/api/objects/edicion_curso/records/$offeringId")
                    .header("Authorization", admin)
                    .bodyValue(mapOf("attributes" to mapOf("estado" to "CANCELADA")))
                    .exchange()
                    .expectStatus()
                    .isOk
                release.complete(Unit)
                holder.await()
                waiting.await()
            }

        assertEquals(HttpStatus.CONFLICT, status)
    }

    // retiene la fila del cupo de una edición, como lo haría otra alta en curso, hasta que la prueba la suelte
    private suspend fun holdOccupancy(
        org: UUID,
        offeringId: UUID,
        held: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ) {
        transactions.executeAndAwait {
            db
                .sql("INSERT INTO lms.ocupacion_edicion (organizacion_id, edicion_id) VALUES (:o, :e) ON CONFLICT DO NOTHING")
                .bind("o", org)
                .bind("e", offeringId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            db
                .sql("SELECT matriculados FROM lms.ocupacion_edicion WHERE organizacion_id = :o AND edicion_id = :e FOR UPDATE")
                .bind("o", org)
                .bind("e", offeringId)
                .fetch()
                .one()
                .awaitSingle()
            held.complete(Unit)
            release.await()
        }
    }

    @Test
    fun `anonymous callers cannot enroll`() {
        assertEquals(HttpStatus.UNAUTHORIZED, enroll(null, offering()).first)
    }
}
