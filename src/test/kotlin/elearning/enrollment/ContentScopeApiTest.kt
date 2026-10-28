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
        val offering = offeringOf("IA-AGT-201-A")
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
    fun `a student reads published versions and their units and lessons, never a draft's`() {
        val versions = get("/api/objects/version_curso/records?size=200", student)["content"].values()
        assertTrue(enrolledVersion in versions.map { it["id"].asString() })
        assertTrue(versions.all { it["attributes"]["estado"].asString() == "PUBLICADA" })

        val units = ids("/api/objects/unidad/records?size=200", student)
        assertTrue(ids("/api/objects/unidad/records?size=200&version_curso=$enrolledVersion", admin).all { it in units })
        val visible = versions.map { it["id"].asString() }.toSet()
        assertTrue(get("/api/objects/unidad/records?size=200", student)["content"].values().all { it["attributes"]["version_curso"].asString() in visible })
        val lessons = get("/api/objects/leccion/records?size=200", student)["content"].values()
        assertTrue(lessons.isNotEmpty() && lessons.all { it["attributes"]["unidad"].asString() in units })
    }

    @Test
    fun `drafts read as missing, even by their id`() {
        val draft = ids("/api/objects/version_curso/records?estado=BORRADOR&size=1", admin).single()
        val draftUnits = ids("/api/objects/unidad/records?version_curso=$draft&size=1", admin)

        assertEquals(HttpStatus.OK, status("/api/objects/version_curso/records/$enrolledVersion", student))
        assertEquals(HttpStatus.NOT_FOUND, status("/api/objects/version_curso/records/$draft", student))
        for (unit in draftUnits) assertEquals(HttpStatus.NOT_FOUND, status("/api/objects/unidad/records/$unit", student))
        assertEquals(HttpStatus.NOT_FOUND, status("/api/objects/version_curso/records/${UUID.randomUUID()}", student))
    }

    // ADR-0013: el temario y las ediciones de lo publicado se ven como en el catálogo; lo que no, es el contenido
    @Test
    fun `the structure of other published courses is visible, as in the public catalog`() {
        val otherCourse = offeringOf("SGSPE-101-A")["attributes"]["version_curso"].asString()

        assertEquals(HttpStatus.OK, status("/api/objects/version_curso/records/$otherCourse", student))
        assertTrue(ids("/api/objects/edicion_curso/records?size=200", student).isNotEmpty())
    }

    @Test
    fun `a student without enrollments sees published structure and never a draft`() {
        val nobody = newStudent()

        val states = get("/api/objects/version_curso/records?size=200", nobody)["content"].strings { it["attributes"]["estado"] }
        assertTrue(states.isNotEmpty() && states.all { it == "PUBLICADA" }, states.toString())
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
