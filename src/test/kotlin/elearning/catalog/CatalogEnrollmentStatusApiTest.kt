package elearning.catalog

import elearning.LmsApiTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.ZoneId

// RF-CAT-09: la ficha publica el estado de la matrícula de cada edición (abierta, cerrada o completa) y el filtro
// enrollmentOpen lo respeta. las ediciones se crean con fechas relativas a hoy. la abierta va en SRTM, que ya tiene una
// abierta; las cerradas y la completa, en SGSPE, que no tiene ninguna abierta: así no cambia lo que esperan las demás
// pruebas del catálogo (CatalogListApiTest), y se ve que una completa no cuenta como abierta
class CatalogEnrollmentStatusApiTest : LmsApiTest() {
    @Autowired
    private lateinit var projection: CatalogProjection

    private val admin by lazy { bearer() }
    private val today get() = LocalDate.now(ZoneId.of("America/Lima"))

    private fun publishedVersion(code: String): String {
        val course = get("/api/objects/curso/records?codigo=$code", admin)["content"][0]["id"].asString()
        return get("/api/objects/version_curso/records?curso=$course&estado=PUBLICADA", admin)["content"][0]["id"].asString()
    }

    private fun offering(
        course: String,
        state: String,
        capacity: Int?,
        from: LocalDate = today.minusDays(1),
        until: LocalDate = today.plusDays(5),
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
                            "version_curso" to publishedVersion(course),
                            "estado" to state,
                            // después de la abierta de SRTM (hoy+12) y antes de la de IA (hoy+159): no cambia el orden
                            // ni el filtro por fecha de las demás pruebas
                            "fecha_inicio" to today.plusDays(30).toString(),
                            "fecha_fin" to today.plusDays(60).toString(),
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

    private fun public(path: String): JsonNode =
        json.readTree(
            client
                .get()
                .uri("/api/lms/public/catalog$path")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody,
        )

    private fun sheet(code: String): JsonNode {
        val slug = public("/courses?size=50")["content"].values().first { it["code"].asString() == code }["slug"].asString()
        return public("/courses/$slug")
    }

    private fun JsonNode.offering(code: String): JsonNode = this["offerings"].values().first { it["code"].asString() == code }

    @Test
    fun `the sheet tells open, closed and full offerings apart`() {
        val open = offering("SRTM-ANU-101", "INSCRIPCION_ABIERTA", capacity = 5)
        val scheduled = offering("SGSPE-101", "PROGRAMADA", capacity = 5)
        val windowOver = offering("SGSPE-101", "INSCRIPCION_ABIERTA", capacity = 5, from = today.minusDays(10), until = today.minusDays(1))
        val full = offering("SGSPE-101", "INSCRIPCION_ABIERTA", capacity = 1)
        client
            .post()
            .uri("/api/lms/enrollments")
            .header("Authorization", newStudent())
            .bodyValue(mapOf("offering" to full))
            .exchange()
            .expectStatus()
            .isCreated
        runBlocking { projection.refreshNow() }

        val srtm = sheet("SRTM-ANU-101")
        val sgspe = sheet("SGSPE-101")
        assertEquals("OPEN", srtm.offering(open)["enrollmentStatus"].asString())
        assertTrue(srtm.offering(open)["enrollmentOpen"].asBoolean())
        assertFalse(srtm.offering(open)["full"].asBoolean())
        for (closed in listOf(scheduled, windowOver)) {
            assertEquals("CLOSED", sgspe.offering(closed)["enrollmentStatus"].asString(), closed)
            assertFalse(sgspe.offering(closed)["enrollmentOpen"].asBoolean(), closed)
        }
        assertEquals("FULL", sgspe.offering(full)["enrollmentStatus"].asString())
        assertFalse(sgspe.offering(full)["enrollmentOpen"].asBoolean(), "completa: no admite matrículas")
        assertTrue(sgspe.offering(full)["full"].asBoolean())
        // solo si está completa, nunca cuántos ni quiénes se matricularon
        assertEquals(
            setOf(
                "code",
                "status",
                "startDate",
                "endDate",
                "timeZone",
                "enrollmentFrom",
                "enrollmentUntil",
                "enrollmentOpen",
                "capacity",
                "schedule",
                "teachers",
                "full",
                "enrollmentStatus",
            ),
            sgspe.offering(full).propertyNames().toSet(),
        )

        // el filtro de RF-CAT-08 dice lo mismo que la ficha: una edición completa no es una inscripción abierta
        val openCourses = public("/courses?enrollmentOpen=true&size=50")["content"].values().map { it["code"].asString() }
        val closedCourses = public("/courses?enrollmentOpen=false&size=50")["content"].values().map { it["code"].asString() }
        assertTrue("SRTM-ANU-101" in openCourses, openCourses.toString())
        assertTrue("SGSPE-101" !in openCourses && "SGSPE-101" in closedCourses, openCourses.toString())
    }
}
