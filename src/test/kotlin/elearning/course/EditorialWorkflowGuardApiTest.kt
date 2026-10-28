package elearning.course

import elearning.LmsApiTest
import elearning.catalog.CatalogProjection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// la separación de deberes del flujo editorial, contra la API genérica de registros de Wasichai: las acciones
// declaradas no protegen nada por sí solas, lo hace EditorialWorkflowGuard (hallazgo alto de la revisión de seguridad)
class EditorialWorkflowGuardApiTest : LmsApiTest() {
    @Autowired
    private lateinit var projection: CatalogProjection

    private val admin by lazy { bearer() }
    private val creator by lazy { demoUser("creador") }
    private val reviewer by lazy { demoUser("revisor") }

    private fun call(
        method: String,
        path: String,
        token: String,
        body: Map<String, Any?>? = null,
    ): Pair<HttpStatus, JsonNode?> {
        val spec =
            when (method) {
                "POST" -> client.post().uri(path).bodyValue(body!!)
                "PATCH" -> client.patch().uri(path).bodyValue(body!!)
                "DELETE" -> client.delete().uri(path)
                else -> client.get().uri(path)
            }
        val result =
            spec
                .header("Authorization", token)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(result.status.value()) to result.responseBody?.let { json.readTree(it) }
    }

    private fun userId(token: String): String = get("/api/auth/me", token)["userId"].asString()

    private fun create(
        token: String,
        objectName: String,
        attributes: Map<String, Any?>,
    ): String {
        val (status, body) = call("POST", "/api/objects/$objectName/records", token, mapOf("attributes" to attributes))
        assertEquals(HttpStatus.CREATED, status, body.toString())
        return body!!["id"].asString()
    }

    private fun patch(
        token: String,
        objectName: String,
        id: String,
        attributes: Map<String, Any?>,
    ): Pair<HttpStatus, JsonNode?> = call("PATCH", "/api/objects/$objectName/records/$id", token, mapOf("attributes" to attributes))

    // un curso nuevo del creador, con una versión en borrador escrita por él
    private fun draft(): Pair<String, String> {
        val slug = uniqueName("curso-")
        val course = create(creator, "curso", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso de prueba del flujo"))
        val version =
            create(
                creator,
                "version_curso",
                mapOf("curso" to course, "numero" to 1, "modalidad" to "ASINCRONA", "semanas" to 2, "autor_id" to userId(creator)),
            )
        return course to version
    }

    @Test
    fun `a creator cannot publish, not by creating nor by editing the state`() {
        val (course, version) = draft()

        val (created, _) =
            call(
                "POST",
                "/api/objects/version_curso/records",
                creator,
                mapOf(
                    "attributes" to
                        mapOf(
                            "curso" to course,
                            "numero" to 2,
                            "modalidad" to "ASINCRONA",
                            "semanas" to 2,
                            "estado" to "PUBLICADA",
                            "autor_id" to userId(creator),
                        ),
                ),
            )
        assertEquals(HttpStatus.FORBIDDEN, created)
        // de borrador a publicada no hay transición: se rechaza antes de mirar permisos
        assertEquals(HttpStatus.CONFLICT, patch(creator, "version_curso", version, mapOf("estado" to "PUBLICADA")).first)
        assertEquals(HttpStatus.FORBIDDEN, patch(creator, "version_curso", version, mapOf("creditos_aprobados" to 8)).first)
    }

    @Test
    fun `nobody writes in someone else's name`() {
        val slug = uniqueName("curso-")
        val course = create(creator, "curso", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Otro"))
        val (status, _) =
            call(
                "POST",
                "/api/objects/version_curso/records",
                creator,
                mapOf("attributes" to mapOf("curso" to course, "numero" to 1, "modalidad" to "ASINCRONA", "semanas" to 2, "autor_id" to userId(reviewer))),
            )
        assertEquals(HttpStatus.BAD_REQUEST, status)
    }

    @Test
    fun `only legal transitions, each with its action, and no self approval`() {
        val (_, version) = draft()

        // saltarse la revisión no es una transición
        assertEquals(HttpStatus.CONFLICT, patch(admin, "version_curso", version, mapOf("estado" to "APROBADA")).first)
        assertEquals(HttpStatus.OK, patch(creator, "version_curso", version, mapOf("estado" to "EN_REVISION")).first)
        // en revisión el contenido no se mueve
        assertEquals(HttpStatus.CONFLICT, patch(creator, "version_curso", version, mapOf("objetivos" to "otro")).first)
        // el autor no aprueba lo suyo, aunque tuviera la acción
        assertEquals(HttpStatus.FORBIDDEN, patch(creator, "version_curso", version, mapOf("estado" to "APROBADA")).first)
        assertEquals(HttpStatus.OK, patch(reviewer, "version_curso", version, mapOf("estado" to "APROBADA", "creditos_aprobados" to 2)).first)
        // el revisor no publica
        assertEquals(HttpStatus.FORBIDDEN, patch(reviewer, "version_curso", version, mapOf("estado" to "PUBLICADA")).first)
    }

    @Test
    fun `the administrator cannot approve a version written by themselves`() {
        val slug = uniqueName("curso-")
        val course = create(admin, "curso", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Del administrador"))
        val version =
            create(admin, "version_curso", mapOf("curso" to course, "numero" to 1, "modalidad" to "ASINCRONA", "semanas" to 2, "autor_id" to userId(admin)))

        assertEquals(HttpStatus.OK, patch(admin, "version_curso", version, mapOf("estado" to "EN_REVISION")).first)
        assertEquals(HttpStatus.FORBIDDEN, patch(admin, "version_curso", version, mapOf("estado" to "APROBADA")).first)
    }

    @Test
    fun `a published version and its curriculum are immutable, and it reaches the catalog only once published`() {
        val (_, version) = draft()
        val unit = create(creator, "unidad", mapOf("version_curso" to version, "orden" to 1, "titulo" to "Unidad"))
        val lesson = create(creator, "leccion", mapOf("unidad" to unit, "orden" to 1, "titulo" to "Lección", "tipo" to "LECTURA"))
        patch(creator, "version_curso", version, mapOf("estado" to "EN_REVISION"))
        patch(reviewer, "version_curso", version, mapOf("estado" to "APROBADA"))
        val slugOf = get("/api/objects/version_curso/records/$version", admin)["attributes"]["curso"].asString()
        try {
            assertEquals(HttpStatus.OK, patch(admin, "version_curso", version, mapOf("estado" to "PUBLICADA", "publicada_en" to "2026-10-28T12:00:00Z")).first)
            runBlocking { projection.refreshNow() }
            val published = get("/api/objects/curso/records/$slugOf", admin)["attributes"]["slug"].asString()
            client
                .get()
                .uri("/api/lms/public/catalog/courses/$published")
                .exchange()
                .expectStatus()
                .isOk

            assertEquals(HttpStatus.CONFLICT, patch(admin, "version_curso", version, mapOf("objetivos" to "cambiado")).first)
            assertEquals(HttpStatus.CONFLICT, patch(creator, "unidad", unit, mapOf("titulo" to "cambiada")).first)
            assertEquals(HttpStatus.CONFLICT, patch(creator, "leccion", lesson, mapOf("titulo" to "cambiada")).first)
            val (added, _) =
                call(
                    "POST",
                    "/api/objects/leccion/records",
                    creator,
                    mapOf("attributes" to mapOf("unidad" to unit, "orden" to 2, "titulo" to "Nueva", "tipo" to "LECTURA")),
                )
            assertEquals(HttpStatus.CONFLICT, added)
            assertEquals(HttpStatus.CONFLICT, call("DELETE", "/api/objects/version_curso/records/$version", admin).first)
        } finally {
            // retirar es la única salida de lo publicado; deja el catálogo como estaba para las demás pruebas
            assertEquals(HttpStatus.OK, patch(admin, "version_curso", version, mapOf("estado" to "RETIRADA")).first)
            runBlocking { projection.refreshNow() }
        }
        assertEquals(HttpStatus.CONFLICT, patch(admin, "version_curso", version, mapOf("estado" to "PUBLICADA")).first)
    }

    @Test
    fun `a reviewer cannot rewrite someone else's draft`() {
        val (_, version) = draft()

        assertEquals(HttpStatus.FORBIDDEN, patch(reviewer, "version_curso", version, mapOf("objetivos" to "reescritos")).first)
    }

    @Test
    fun `a draft and its curriculum stay editable by the creator`() {
        val (_, version) = draft()
        val unit = create(creator, "unidad", mapOf("version_curso" to version, "orden" to 1, "titulo" to "Unidad"))

        assertEquals(HttpStatus.OK, patch(creator, "version_curso", version, mapOf("objetivos" to "Uno\nDos")).first)
        assertEquals(HttpStatus.OK, patch(creator, "unidad", unit, mapOf("titulo" to "Renombrada")).first)
    }

    @Test
    fun `a teacher does not edit offerings and a student does not read unpublished versions`() {
        val offering = get("/api/objects/edicion_curso/records?size=1", admin)["content"][0]["id"].asString()

        assertEquals(HttpStatus.FORBIDDEN, patch(demoUser("docente"), "edicion_curso", offering, mapOf("capacidad" to 999)).first)
        assertEquals(HttpStatus.FORBIDDEN, call("GET", "/api/objects/version_curso/records", demoUser("estudiante")).first)
        assertTrue(call("GET", "/api/objects/curso/records", demoUser("estudiante")).first.is2xxSuccessful)
    }
}
