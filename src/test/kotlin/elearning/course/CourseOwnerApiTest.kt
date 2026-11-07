package elearning.course

import elearning.LmsApiTest
import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import java.util.UUID

// #345: el dueño de un curso (curso.propietario_id) es quien lo crea. Lo pone el servidor con CourseOwner, por la SPI
// RecordValueProvider de Wasichai (ADR-077); nadie lo elige ni lo cambia, ADMIN incluido, y no da ni quita permisos
class CourseOwnerApiTest : LmsApiTest() {
    @Autowired
    private lateinit var records: RecordService

    private val creator by lazy { demoUser("creador") }
    private val creatorId by lazy { idOf(creator) }
    private val admin by lazy { bearer() }

    private fun idOf(token: String): String = get("/api/auth/me", token)["userId"].asString()

    private fun send(
        method: String,
        path: String,
        token: String,
        attributes: Map<String, Any?>,
    ): Pair<HttpStatus, JsonNode?> {
        val spec =
            when (method) {
                "POST" -> client.post().uri(path)
                "PUT" -> client.put().uri(path)
                else -> client.patch().uri(path)
            }
        val result =
            spec
                .header("Authorization", token)
                .bodyValue(mapOf("attributes" to attributes))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(result.status.value()) to result.responseBody?.let { json.readTree(it) }
    }

    private fun courseAttributes(): Map<String, Any?> {
        val slug = uniqueName("dueno-").lowercase()
        return mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso con dueño")
    }

    private fun create(
        token: String,
        extra: Map<String, Any?> = emptyMap(),
    ): Pair<HttpStatus, JsonNode?> = send("POST", "/api/objects/curso/records", token, courseAttributes() + extra)

    private fun created(token: String): JsonNode {
        val (status, body) = create(token)
        assertEquals(HttpStatus.CREATED, status, body.toString())
        return body!!
    }

    private fun owner(id: String): String? = get("/api/objects/curso/records/$id", admin)["attributes"]["propietario_id"]?.takeUnless { it.isNull }?.asString()

    @Test
    fun `the field is hidden and read only in the metadata, so the core form leaves it out`() {
        val field = get("/api/metadata/objects/curso", admin)["fields"].values().single { it["name"].asString() == "propietario_id" }
        assertEquals("UUID", field["type"].asString())
        assertFalse(field["visible"].asBoolean())
        assertFalse(field["editable"].asBoolean())
        assertFalse(field["required"].asBoolean())
    }

    @Test
    fun `whoever creates a course owns it, and the POST answers with it in a single audit entry`() {
        val course = created(creator)
        val id = course["id"].asString()

        assertEquals(creatorId, course["attributes"]["propietario_id"].asString())
        assertEquals(creatorId, owner(id))
        val history = get("/api/objects/curso/records/$id/history", admin)
        assertEquals(listOf("CREATE"), history.values().map { it["operation"].asString() })
    }

    @Test
    fun `nobody creates a course in the name of someone else, ADMIN included`() {
        val someone = UUID.randomUUID().toString()

        assertEquals(HttpStatus.BAD_REQUEST, create(creator, mapOf("propietario_id" to someone)).first)
        assertEquals(HttpStatus.BAD_REQUEST, create(admin, mapOf("propietario_id" to someone)).first)
        assertEquals(HttpStatus.BAD_REQUEST, create(admin, mapOf("propietario_id" to creatorId)).first)
        // el propio id no cambia nada
        val (status, body) = create(creator, mapOf("propietario_id" to creatorId))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        assertEquals(creatorId, body!!["attributes"]["propietario_id"].asString())
        assertEquals(idOf(admin), created(admin)["attributes"]["propietario_id"].asString())
    }

    @Test
    fun `the owner does not change on an update, by PUT or by PATCH`() {
        val course = created(creator)
        val path = "/api/objects/curso/records/${course["id"].asString()}"
        // un PUT reemplaza el registro entero: lleva los obligatorios
        val attributes = listOf("codigo", "slug", "titulo", "idioma", "nivel").associateWith { course["attributes"][it].asString() }
        val someone = UUID.randomUUID().toString()

        assertEquals(HttpStatus.BAD_REQUEST, send("PUT", path, creator, attributes + ("propietario_id" to someone)).first)
        assertEquals(HttpStatus.BAD_REQUEST, send("PUT", path, admin, attributes + ("propietario_id" to someone)).first)
        assertEquals(HttpStatus.BAD_REQUEST, send("PUT", path, admin, attributes + ("propietario_id" to null)).first)
        assertEquals(HttpStatus.FORBIDDEN, send("PATCH", path, admin, mapOf("propietario_id" to someone)).first)
        assertEquals(creatorId, owner(course["id"].asString()))

        // lo que manda un formulario que no lo muestra: un PUT sin el campo no lo borra; el mismo valor, tampoco
        send("PUT", path, creator, attributes + ("titulo" to "Otro título")).let { assertEquals(HttpStatus.OK, it.first, it.second.toString()) }
        assertEquals(creatorId, owner(course["id"].asString()))
        assertEquals(HttpStatus.OK, send("PUT", path, creator, attributes + ("propietario_id" to creatorId)).first)
        assertEquals(HttpStatus.OK, send("PATCH", path, creator, mapOf("titulo" to "Y otro")).first)
        assertEquals(creatorId, owner(course["id"].asString()))
    }

    @Test
    fun `the list filters courses by their owner`() {
        val mine = created(creator)["id"].asString()
        val theirs = created(admin)["id"].asString()

        val found = get("/api/objects/curso/records?propietario_id=$creatorId&size=200", admin)["content"].values().map { it["id"].asString() }

        assertTrue(mine in found, found.toString())
        assertFalse(theirs in found, found.toString())
        assertTrue(
            get("/api/objects/curso/records?propietario_id=$creatorId&size=200", admin)["content"].values().all {
                it["attributes"]["propietario_id"].asString() == creatorId
            },
        )
    }

    @Test
    fun `the demo courses are owned by the demo creator`() {
        for (code in listOf("SRTM-ANU-101", "SGSPE-101")) {
            val course = get("/api/objects/curso/records?codigo=$code", admin)["content"].values().single()
            assertEquals(creatorId, course["attributes"]["propietario_id"].asString(), code)
        }
    }

    @Test
    fun `the platform creates courses without an owner, and cannot name one`() {
        val organization = ClassroomApiTestSupport.ORG
        val course = runBlocking { records.asPlatform(organization) { records.create("curso", RecordRequest(courseAttributes())) } }
        assertEquals(null, course.attributes["propietario_id"])

        val refused =
            runCatching {
                runBlocking {
                    records.asPlatform(organization) {
                        records.create(
                            "curso",
                            RecordRequest(courseAttributes() + ("propietario_id" to creatorId)),
                        )
                    }
                }
            }
        assertTrue(refused.exceptionOrNull() is ValidationException, refused.toString())
    }

    // #270: el primer borrador lo crea cualquiera con CREATE sobre version_curso, también en un curso ajeno
    @Test
    fun `owning a course grants nothing, anyone who creates versions starts the first draft of a course of someone else`() {
        val course = created(admin)["id"].asString()
        val (status, body) =
            send(
                "POST",
                "/api/objects/version_curso/records",
                creator,
                mapOf(
                    "curso" to course,
                    "numero" to 1,
                    "modalidad" to "ASINCRONA",
                    "semanas" to 2,
                    "autor_id" to creatorId,
                ),
            )
        assertEquals(HttpStatus.CREATED, status, body.toString())

        // y quien no tiene CREATE sobre curso no lo crea por ser dueño de nada
        assertEquals(HttpStatus.FORBIDDEN, create(demoUser("docente")).first)
    }
}
