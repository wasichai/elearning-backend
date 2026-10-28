package elearning.enrollment

import elearning.LmsApiTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import java.util.UUID

// lo que un estudiante lee del contenido (ADR-0012): solo las versiones de sus matrículas activas, sus unidades y sus
// lecciones; nada de borradores ni de otros cursos, tampoco cambiando el id en la URL
class ContentScopeApiTest : LmsApiTest() {
    @Autowired
    private lateinit var records: RecordService

    private val admin by lazy { bearer() }
    private lateinit var student: String
    private lateinit var enrolledVersion: String

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

    private fun offeringOf(code: String): JsonNode = get("/api/objects/edicion_curso/records?codigo=$code", admin)["content"][0]

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

    private fun ids(
        path: String,
        token: String,
    ): List<String> = get(path, token)["content"].strings { it["id"] }

    @BeforeEach
    fun enrolledInTheAiCourse() {
        student = newStudent()
        val offering = offeringOf("IA-AGT-201-2027-04")
        enrolledVersion = offering["attributes"]["version_curso"].asString()
        val me = get("/api/auth/me", student)["userId"].asString()
        runBlocking {
            records.asPlatform(ORG) {
                records.create(
                    "matricula",
                    RecordRequest(
                        mapOf(
                            "edicion" to offering["id"].asString(),
                            "version_curso" to enrolledVersion,
                            "estudiante_id" to me,
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun `a student reads only the version they are enrolled in, its units and its lessons`() {
        assertEquals(listOf(enrolledVersion), ids("/api/objects/version_curso/records?size=200", student))

        val units = ids("/api/objects/unidad/records?size=200", student)
        assertEquals(ids("/api/objects/unidad/records?size=200&version_curso=$enrolledVersion", admin).toSet(), units.toSet())
        val lessons = get("/api/objects/leccion/records?size=200", student)["content"].values()
        assertTrue(lessons.isNotEmpty() && lessons.all { it["attributes"]["unidad"].asString() in units })
    }

    @Test
    fun `drafts and other courses read as missing, even by their id`() {
        val draft = ids("/api/objects/version_curso/records?estado=BORRADOR&size=1", admin).single()
        val otherCourse = offeringOf("SGSPE-101-2027-01")["attributes"]["version_curso"].asString()
        val otherUnit = ids("/api/objects/unidad/records?version_curso=$otherCourse&size=1", admin).single()
        val otherLesson = ids("/api/objects/leccion/records?unidad=$otherUnit&size=1", admin).single()

        assertEquals(HttpStatus.OK, status("/api/objects/version_curso/records/$enrolledVersion", student))
        for (path in listOf(
            "version_curso/records/$draft",
            "version_curso/records/$otherCourse",
            "unidad/records/$otherUnit",
            "leccion/records/$otherLesson",
        )) {
            assertEquals(HttpStatus.NOT_FOUND, status("/api/objects/$path", student), path)
        }
    }

    @Test
    fun `a student without enrollments reads no content at all`() {
        val nobody = newStudent()

        for (obj in listOf("version_curso", "unidad", "leccion")) {
            assertEquals(0, get("/api/objects/$obj/records?size=200", nobody)["totalElements"].asInt(), obj)
        }
    }

    @Test
    fun `staff still read every version, drafts included`() {
        for (role in listOf("creador", "revisor", "docente")) {
            val states = get("/api/objects/version_curso/records?size=200", demoUser(role))["content"].strings { it["attributes"]["estado"] }
            assertTrue("BORRADOR" in states && "PUBLICADA" in states, "$role: $states")
        }
    }

    companion object {
        val ORG: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
