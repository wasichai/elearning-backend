package elearning.enrollment

import elearning.LmsApiTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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

// RF-ACA-08: una versión declara los cursos que exige haber aprobado (cursos_previos) y el comando de matrícula los
// comprueba: solo cuenta una matrícula propia FINALIZADA y APROBADO. los cursos los pone la plataforma, como la carga de
// la demo, y se retiran al terminar para no aparecer en el catálogo de otras pruebas
class EnrollmentPrerequisiteApiTest : LmsApiTest() {
    @Autowired
    private lateinit var records: RecordService

    private val admin by lazy { bearer() }
    private val published = mutableListOf<String>()

    private class Course(
        val code: String,
        val version: String,
        val offering: String,
        val offeringId: String,
    )

    @AfterEach
    fun retireCourses() =
        runBlocking {
            records.asPlatform(ORG) {
                published.forEach { records.patch("version_curso", UUID.fromString(it), RecordRequest(mapOf("estado" to "RETIRADA")), null, null) }
            }
            published.clear()
        }

    // un curso con su v1 publicada, que exige requires, y una edición con la inscripción abierta
    private fun course(
        requires: String? = null,
        title: String = "Curso previo",
    ): Course =
        runBlocking {
            records.asPlatform(ORG) {
                val code = uniqueName("PRE-").uppercase()
                val course =
                    records.create(
                        "curso",
                        RecordRequest(mapOf("codigo" to code, "slug" to code.lowercase(), "titulo" to title, "idioma" to "es", "nivel" to "INICIAL")),
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
                                "cursos_previos" to requires,
                            ),
                        ),
                    )
                records.patch("version_curso", UUID.fromString(version.id), RecordRequest(mapOf("estado" to "PUBLICADA")), null, null)
                published += version.id
                val today = LocalDate.now(ZoneId.of("America/Lima"))
                val offering =
                    records.create(
                        "edicion_curso",
                        RecordRequest(
                            mapOf(
                                "codigo" to code,
                                "version_curso" to version.id,
                                "estado" to "INSCRIPCION_ABIERTA",
                                "fecha_inicio" to today.plusDays(10).toString(),
                                "fecha_fin" to today.plusDays(40).toString(),
                                "zona_horaria" to "America/Lima",
                                "inscripcion_desde" to today.minusDays(1).toString(),
                                "inscripcion_hasta" to today.plusDays(5).toString(),
                            ),
                        ),
                    )
                Course(code, version.id, code, offering.id)
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

    private fun userId(token: String): String = get("/api/auth/me", token)["userId"].asString()

    // una matrícula ya cerrada en el curso previo, como la dejaría el certificado: la plataforma, no la API genérica
    private fun pastEnrollment(
        student: String,
        course: Course,
        state: String,
        result: String,
    ): String {
        val id = userId(student)
        return runBlocking {
            records
                .asPlatform(ORG) {
                    records.create(
                        "matricula",
                        RecordRequest(
                            mapOf(
                                "edicion" to course.offeringId,
                                "version_curso" to course.version,
                                "estudiante_id" to id,
                                "estado" to state,
                                "resultado" to result,
                            ),
                        ),
                    )
                }.id
        }
    }

    private fun enroll(
        token: String,
        code: String,
    ): Pair<HttpStatus, JsonNode?> {
        val result =
            client
                .post()
                .uri("/api/lms/enrollments")
                .header("Authorization", token)
                .bodyValue(mapOf("offering" to code))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(result.status.value()) to result.responseBody?.let { json.readTree(it) }
    }

    @Test
    fun `without the required course passed, the enrollment is refused saying which one is missing`() {
        val previous = course(title = "Fundamentos")
        val next = course(requires = previous.code)

        val (status, body) = enroll(newStudent(), next.offering)

        assertEquals(HttpStatus.CONFLICT, status, body.toString())
        val detail = body!!["detail"].asString()
        assertTrue(previous.code in detail && "Fundamentos" in detail, detail)
    }

    @Test
    fun `a finished and passed enrollment in the required course lets the student in`() {
        val previous = course()
        val next = course(requires = previous.code)
        val student = newStudent()
        pastEnrollment(student, previous, "FINALIZADA", "APROBADO")

        val (status, body) = enroll(student, next.offering)

        assertEquals(HttpStatus.CREATED, status, body.toString())
    }

    @Test
    fun `an active, failed or withdrawn enrollment does not meet the prerequisite`() {
        val previous = course()
        val next = course(requires = previous.code)
        for ((state, result) in listOf("ACTIVA" to "SIN_RESULTADO", "FINALIZADA" to "NO_APROBADO", "RETIRADA" to "SIN_RESULTADO")) {
            val student = newStudent()
            pastEnrollment(student, previous, state, result)

            assertEquals(HttpStatus.CONFLICT, enroll(student, next.offering).first, "$state/$result")
        }
    }

    @Test
    fun `every required course counts, and an unknown code is never met`() {
        val first = course()
        val second = course()
        val next = course(requires = "${first.code}\n\n  ${second.code}  \nNO-EXISTE-999\n")
        val student = newStudent()
        pastEnrollment(student, first, "FINALIZADA", "APROBADO")

        val (status, body) = enroll(student, next.offering)

        assertEquals(HttpStatus.CONFLICT, status, body.toString())
        val detail = body!!["detail"].asString()
        assertTrue(first.code !in detail && second.code in detail && "NO-EXISTE-999" in detail, detail)
    }

    // autorización negativa: el aprobado de otra persona no cuenta, y nadie se lo da a sí mismo ni quita el prerrequisito
    @Test
    fun `someone else's pass does not count and a student can neither fake a pass nor drop the prerequisite`() {
        val previous = course()
        val next = course(requires = previous.code)
        val other = newStudent()
        pastEnrollment(other, previous, "FINALIZADA", "APROBADO")
        val student = newStudent()
        val own = pastEnrollment(student, previous, "ACTIVA", "SIN_RESULTADO")

        assertEquals(HttpStatus.CONFLICT, enroll(student, next.offering).first)
        // matricula es apiOnly: la API genérica no la cambia, tampoco la propia
        client
            .patch()
            .uri("/api/objects/matricula/records/$own")
            .header("Authorization", student)
            .bodyValue(mapOf("attributes" to mapOf("estado" to "FINALIZADA", "resultado" to "APROBADO")))
            .exchange()
            .expectStatus()
            .isForbidden
        // el estudiante solo lee las versiones
        client
            .patch()
            .uri("/api/objects/version_curso/records/${next.version}")
            .header("Authorization", student)
            .bodyValue(mapOf("attributes" to mapOf("cursos_previos" to "")))
            .exchange()
            .expectStatus()
            .isForbidden
        assertEquals(HttpStatus.CONFLICT, enroll(student, next.offering).first)
    }

    @Test
    fun `a published version keeps its prerequisites`() {
        val previous = course()
        val next = course(requires = previous.code)

        client
            .patch()
            .uri("/api/objects/version_curso/records/${next.version}")
            .header("Authorization", admin)
            .bodyValue(mapOf("attributes" to mapOf("cursos_previos" to "")))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CONFLICT)
    }

    companion object {
        // la organización de la semilla de desarrollo de Wasichai
        val ORG: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
