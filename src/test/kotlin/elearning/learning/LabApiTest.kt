package elearning.learning

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordRequest

// el laboratorio (RF-LAB-01, RF-LAB-03, ADR-0027): sus campos en la lección, sus entornos externos con enlace https público
// validado con SafeLink para toda escritura, congelados con la versión y leídos solo por los matriculados
class LabApiTest : ClassroomApiTestSupport() {
    private val creator by lazy { demoUser("creador") }

    private fun write(
        method: String,
        token: String,
        path: String,
        attributes: Map<String, Any?>? = null,
    ): Pair<HttpStatus, JsonNode?> {
        val body = attributes?.let { mapOf("attributes" to it) }
        val spec =
            when (method) {
                "PATCH" -> client.patch().uri(path).bodyValue(body!!)
                "DELETE" -> client.delete().uri(path)
                else -> client.post().uri(path).bodyValue(body!!)
            }
        val r = spec.header("Authorization", token).exchange().expectBody(String::class.java).returnResult()
        return HttpStatus.valueOf(r.status.value()) to r.responseBody?.let { runCatching { json.readTree(it) }.getOrNull() }
    }

    private fun created(result: Pair<HttpStatus, JsonNode?>): String {
        assertEquals(HttpStatus.CREATED, result.first, result.second.toString())
        return result.second!!["id"].asString()
    }

    // un curso del creador con una versión en borrador y una lección LABORATORIO: devuelve curso, versión y lección
    private fun draftLab(): Triple<String, String, String> {
        val slug = uniqueName("lab-")
        val course = created(write("POST", creator, "/api/objects/curso/records", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Laboratorio")))
        val version =
            created(
                write(
                    "POST",
                    creator,
                    "/api/objects/version_curso/records",
                    mapOf("curso" to course, "numero" to 1, "modalidad" to "ASINCRONA", "semanas" to 1, "autor_id" to userId(creator)),
                ),
            )
        val unit = created(write("POST", creator, "/api/objects/unidad/records", mapOf("version_curso" to version, "orden" to 1, "titulo" to "Unidad")))
        val lesson =
            created(
                write(
                    "POST",
                    creator,
                    "/api/objects/leccion/records",
                    mapOf("unidad" to unit, "orden" to 1, "titulo" to "Práctica", "tipo" to "LABORATORIO") + LAB_FIELDS,
                ),
            )
        return Triple(course, version, lesson)
    }

    private fun environment(
        token: String,
        lesson: String,
        link: String,
        order: Int = 1,
        title: String = "Entorno",
    ) = write("POST", token, "/api/objects/entorno_laboratorio/records", mapOf("leccion" to lesson, "orden" to order, "titulo" to title, "enlace" to link))

    @Test
    fun `the classroom shows a lab with its objective, its lists and its environments`() {
        val course = course(lab = true)
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        val lessons = ok(call(student, "/api/lms/classroom/enrollments/$enrollment"))["units"][0]["lessons"]
        val lab = lessons.single { it["id"].asString() == course.assessment }["lab"]

        assertEquals("Consultar el padrón a una fecha pasada", lab["objective"].asString())
        assertEquals(listOf("Cuenta en el entorno", "Navegador actual"), lab["requirements"].strings())
        // una línea vacía no es un hito
        assertEquals(listOf("Abrir el entorno", "Consultar el padrón", "Redactar el informe"), lab["milestones"].strings())
        assertEquals(listOf("Cada estado justificado"), lab["acceptanceCriteria"].strings())
        assertEquals(listOf("Informe en PDF"), lab["deliverables"].strings())
        val environment = lab["environments"].single()
        assertEquals(course.environment, environment["id"].asString())
        assertEquals("Entorno de práctica", environment["title"].asString())
        assertEquals(LAB_LINK, environment["url"].asString())
        // una lección que no es laboratorio no lleva bloque de laboratorio
        val reading = lessons.single { it["id"].asString() == course.reading }
        assertTrue(reading.has("lab") && reading["lab"].isNull, reading.toString())
    }

    @Test
    fun `only the enrolled read the environments of a lab`() {
        val course = course(lab = true)
        val enrolled = newStudent()
        enroll(enrolled, course)
        val outsider = newStudent()
        val path = "/api/objects/entorno_laboratorio/records?leccion=${course.assessment}"

        assertEquals(listOf(course.environment), ok(call(enrolled, path))["content"].strings { it["id"] })
        assertEquals(0, ok(call(outsider, path))["content"].size())
        assertEquals(HttpStatus.NOT_FOUND, call(outsider, "/api/objects/entorno_laboratorio/records/${course.environment}").first)
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, path).first)
    }

    @Test
    fun `an environment link is a public https link`() {
        val (_, _, lesson) = draftLab()
        val accepted = created(environment(creator, lesson, "https://lab.example.org/sala"))
        val refused =
            listOf(
                "http://lab.example.org/sala",
                "file:///etc/passwd",
                "javascript:alert(1)",
                "https://localhost/",
                "https://10.0.0.1/",
                "https://192.168.1.10/",
                "https://169.254.169.254/latest/meta-data/",
                "https://2130706433/",
                "https://lab.internal/",
                "lab.example.org",
            )
        refused.forEachIndexed { i, link ->
            val (status, body) = environment(creator, lesson, link, order = i + 2)
            assertEquals(HttpStatus.BAD_REQUEST, status, "$link → $body")
        }
        assertEquals(HttpStatus.BAD_REQUEST, write("PATCH", creator, "/api/objects/entorno_laboratorio/records/$accepted", mapOf("enlace" to "http://x.org/")).first)
        assertEquals(HttpStatus.OK, write("PATCH", creator, "/api/objects/entorno_laboratorio/records/$accepted", mapOf("titulo" to "Otro")).first)
        // es un dato inválido, no un permiso: tampoco la plataforma lo escribe
        assertThrows<ValidationException> {
            runBlocking {
                records.asPlatform(ORG) {
                    records.create(
                        "entorno_laboratorio",
                        RecordRequest(mapOf("leccion" to lesson, "orden" to 99, "titulo" to "Interno", "enlace" to "https://127.0.0.1/")),
                    )
                }
            }
        }
    }

    @Test
    fun `students, teachers and reviewers do not write environments`() {
        val (_, _, lesson) = draftLab()
        val accepted = created(environment(creator, lesson, "https://lab.example.org/sala"))

        for (token in listOf(newStudent(), demoUser("estudiante"), demoUser("docente"), demoUser("revisor"))) {
            assertEquals(HttpStatus.FORBIDDEN, environment(token, lesson, "https://lab.example.org/otra", order = 2).first)
            assertEquals(HttpStatus.FORBIDDEN, write("PATCH", token, "/api/objects/entorno_laboratorio/records/$accepted", mapOf("titulo" to "Mío")).first)
            assertEquals(HttpStatus.FORBIDDEN, write("DELETE", token, "/api/objects/entorno_laboratorio/records/$accepted").first)
        }
    }

    @Test
    fun `a published lab does not change, neither its fields nor its environments`() {
        val course = course(lab = true)
        val admin = admin
        val environmentPath = "/api/objects/entorno_laboratorio/records/${course.environment}"

        assertEquals(HttpStatus.CONFLICT, write("PATCH", admin, "/api/objects/leccion/records/${course.assessment}", mapOf("hitos" to "Otro hito")).first)
        assertEquals(HttpStatus.CONFLICT, environment(admin, course.assessment, "https://lab.example.org/nuevo", order = 2).first)
        assertEquals(HttpStatus.CONFLICT, write("PATCH", admin, environmentPath, mapOf("enlace" to "https://lab.example.org/otro")).first)
        assertEquals(HttpStatus.CONFLICT, write("DELETE", admin, environmentPath).first)
        assertEquals(LAB_LINK, ok(call(admin, environmentPath))["attributes"]["enlace"].asString())
    }

    @Test
    fun `a new version copies the lab with its environments`() {
        val (course, version, lesson) = draftLab()
        val title = uniqueName("entorno-")
        created(environment(creator, lesson, "https://lab.example.org/sala", title = title))

        val copy = ok(call(creator, "/api/lms/studio/courses/$course/versions", mapOf("fromVersionId" to version)))

        val environments = ok(call(creator, "/api/objects/entorno_laboratorio/records?titulo=$title"))["content"].values().toList()
        assertEquals(2, environments.size, environments.toString())
        val copied = environments.single { it["attributes"]["leccion"].let { l -> (if (l.isObject) l["id"] else l).asString() } != lesson }
        assertEquals("https://lab.example.org/sala", copied["attributes"]["enlace"].asString())
        assertTrue(copy.has("id"), copy.toString())
    }
}
