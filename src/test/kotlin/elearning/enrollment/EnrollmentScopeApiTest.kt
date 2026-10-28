package elearning.enrollment

import elearning.LmsApiTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import java.util.UUID

// alcance de lectura de las matrículas (ADR-0012): cada estudiante ve solo las suyas; quien tiene VER_TODAS, todas.
// las matrículas se crean como la plataforma (la API genérica no puede: matricula es apiOnly)
class EnrollmentScopeApiTest : LmsApiTest() {
    @Autowired
    private lateinit var records: RecordService

    private lateinit var mine: String
    private lateinit var theirs: String

    private fun userId(token: String): String = get("/api/auth/me", token)["userId"].asString()

    private fun newStudent(): String {
        val email = "${uniqueName("est")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", bearer())
            .bodyValue(mapOf("email" to email, "displayName" to "Otra estudiante", "password" to DEMO_PASSWORD, "roles" to listOf("ESTUDIANTE")))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    private fun enroll(
        student: String,
        offering: String,
        version: String,
    ): String =
        runBlocking {
            records
                .asPlatform(DEMO_ORGANIZATION) {
                    records.create(
                        "matricula",
                        RecordRequest(mapOf("edicion" to offering, "version_curso" to version, "estudiante_id" to student, "estado" to "ACTIVA")),
                    )
                }.id
        }

    private lateinit var student: String
    private lateinit var other: String

    @BeforeEach
    fun enrollments() {
        // dos estudiantes nuevos por prueba: la restricción única (edición, estudiante) no admite repetir la matrícula
        student = newStudent()
        other = newStudent()
        val offering = get("/api/objects/edicion_curso/records?size=1", bearer())["content"][0]
        val offeringId = offering["id"].asString()
        val version = offering["attributes"]["version_curso"].asString()
        mine = enroll(userId(student), offeringId, version)
        theirs = enroll(userId(other), offeringId, version)
    }

    private fun status(
        path: String,
        token: String,
    ): HttpStatus =
        HttpStatus.valueOf(
            client
                .get()
                .uri(path)
                .header("Authorization", token)
                .exchange()
                .returnResult(String::class.java)
                .status
                .value(),
        )

    @Test
    fun `a student lists only their own enrollments`() {
        val ids = get("/api/objects/matricula/records?size=200", student)["content"].strings { it["id"] }

        assertTrue(mine in ids, ids.toString())
        assertTrue(theirs !in ids, ids.toString())
        assertTrue(
            get("/api/objects/matricula/records?size=200", student)["content"].values().all {
                it["attributes"]["estudiante_id"].asString() ==
                    userId(student)
            },
        )
    }

    @Test
    fun `someone else's enrollment reads as missing, even by its id`() {
        assertEquals(HttpStatus.OK, status("/api/objects/matricula/records/$mine", student))
        assertEquals(HttpStatus.NOT_FOUND, status("/api/objects/matricula/records/$theirs", student))
        assertEquals(HttpStatus.NOT_FOUND, status("/api/objects/matricula/records/${UUID.randomUUID()}", student))
    }

    @Test
    fun `the administrator sees them all, a teacher without assignments none`() {
        val all = get("/api/objects/matricula/records?size=200", bearer())["content"].strings { it["id"] }
        assertTrue(mine in all && theirs in all, all.toString())

        // un docente sin ediciones asignadas no ve matrículas ni progreso de nadie: ningún rol tiene VER_TODAS sobre ellos
        // (ADR-0014) y su alcance sale de sus asignaciones (ADR-0016)
        val email = "${uniqueName("doc")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", bearer())
            .bodyValue(mapOf("email" to email, "displayName" to "Docente sin edición", "password" to DEMO_PASSWORD, "roles" to listOf("DOCENTE")))
            .exchange()
            .expectStatus()
            .isCreated
        val teacher = bearer(email, DEMO_PASSWORD)
        for (obj in listOf("matricula", "progreso_leccion")) {
            assertEquals(0, get("/api/objects/$obj/records?size=200", teacher)["totalElements"].asInt(), obj)
        }
    }

    @Test
    fun `nobody creates an enrollment through the generic record api`() {
        val offering = get("/api/objects/edicion_curso/records?size=1", bearer())["content"][0]
        for (token in listOf(student, bearer())) {
            client
                .post()
                .uri("/api/objects/matricula/records")
                .header("Authorization", token)
                .bodyValue(mapOf("attributes" to mapOf("edicion" to offering["id"].asString(), "estudiante_id" to userId(student))))
                .exchange()
                .expectStatus()
                .isForbidden
        }
    }

    companion object {
        // la organización de la semilla de desarrollo de Wasichai
        val DEMO_ORGANIZATION: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
