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

    // una revisión de curso se decide en la bandeja (ADR-0021)
    private fun decide(
        token: String,
        version: String,
        decision: String,
        reason: String? = null,
    ): Pair<HttpStatus, JsonNode?> = call("POST", "/api/lms/review/versions/$version/decision", token, mapOf("decision" to decision, "reason" to reason))

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
        assertEquals(HttpStatus.OK, decide(reviewer, version, "APROBAR").first)
        assertEquals(HttpStatus.OK, patch(reviewer, "version_curso", version, mapOf("creditos_aprobados" to 2)).first)
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
        assertEquals(HttpStatus.OK, decide(reviewer, version, "APROBAR").first)
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
    fun `a review is decided only in the inbox, which leaves the decision, never through the record API`() {
        // revisión de la Fase 4 (M1 de #216): por la API de registros quedaba la transición sin decisión ni motivo
        val (_, version) = draft()
        assertEquals(HttpStatus.OK, patch(creator, "version_curso", version, mapOf("estado" to "EN_REVISION")).first)

        // quien sí puede decidir tampoco decide por aquí: ni aprobar, ni pedir cambios, ni rechazar
        for (state in listOf("APROBADA", "BORRADOR", "RETIRADA")) {
            assertEquals(HttpStatus.FORBIDDEN, patch(reviewer, "version_curso", version, mapOf("estado" to state)).first, state)
        }
        val stored = get("/api/objects/version_curso/records/$version", admin)["attributes"]
        assertEquals("EN_REVISION", stored["estado"].asString())
        assertEquals(0, get("/api/lms/studio/versions/$version", creator)["decisions"].size())

        // el autor sí la retira a borrador: no es una decisión
        assertEquals(HttpStatus.OK, patch(creator, "version_curso", version, mapOf("estado" to "BORRADOR")).first)
        assertEquals(HttpStatus.OK, patch(creator, "version_curso", version, mapOf("estado" to "EN_REVISION")).first)

        assertEquals(HttpStatus.OK, decide(reviewer, version, "PEDIR_CAMBIOS", "Falta la bibliografía").first)
        val decisions = get("/api/lms/studio/versions/$version", creator)["decisions"]
        assertEquals("PEDIR_CAMBIOS", decisions.single()["decision"].asString())
        assertEquals("Falta la bibliografía", decisions.single()["reason"].asString())
    }

    @Test
    fun `a version is not born approved, which would skip the review`() {
        val slug = uniqueName("curso-")
        val course = create(creator, "curso", mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Nace aprobada"))
        val attributes = mapOf("curso" to course, "numero" to 1, "modalidad" to "ASINCRONA", "semanas" to 2, "estado" to "APROBADA")

        // ni quien crea y revisa, a su nombre (autoaprobación), ni quien publica, a nombre de otro
        val both = newUser("CREADOR", "REVISOR")
        val (selfApproved, _) = call("POST", "/api/objects/version_curso/records", both, mapOf("attributes" to attributes + ("autor_id" to userId(both))))
        assertEquals(HttpStatus.FORBIDDEN, selfApproved)
        val (asAdmin, _) = call("POST", "/api/objects/version_curso/records", admin, mapOf("attributes" to attributes + ("autor_id" to userId(creator))))
        assertEquals(HttpStatus.FORBIDDEN, asAdmin)
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
    fun `another creator does not touch the curriculum of someone else's draft, and editorial direction does`() {
        // revisión de la Fase 2 (L8): la versión solo la editaba su autor, pero su temario lo tocaba cualquier CREADOR
        val (_, version) = draft()
        val unit = create(creator, "unidad", mapOf("version_curso" to version, "orden" to 1, "titulo" to "Unidad"))
        val lesson = create(creator, "leccion", mapOf("unidad" to unit, "orden" to 1, "titulo" to "Lección", "tipo" to "LECTURA"))
        val other = newUser("CREADOR")

        val newUnit = mapOf("attributes" to mapOf("version_curso" to version, "orden" to 2, "titulo" to "Ajena"))
        assertEquals(HttpStatus.FORBIDDEN, call("POST", "/api/objects/unidad/records", other, newUnit).first)
        assertEquals(HttpStatus.FORBIDDEN, patch(other, "unidad", unit, mapOf("titulo" to "Reescrita")).first)
        val newLesson = mapOf("attributes" to mapOf("unidad" to unit, "orden" to 2, "titulo" to "Ajena", "tipo" to "LECTURA"))
        assertEquals(HttpStatus.FORBIDDEN, call("POST", "/api/objects/leccion/records", other, newLesson).first)
        assertEquals(HttpStatus.FORBIDDEN, call("DELETE", "/api/objects/leccion/records/$lesson", other).first)
        assertEquals(HttpStatus.OK, patch(admin, "unidad", unit, mapOf("titulo" to "Corregida por la dirección")).first)
    }

    private fun newUser(vararg roles: String): String {
        val email = "${uniqueName("creador")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Otro creador", "password" to DEMO_PASSWORD, "roles" to roles.toList()))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    @Test
    fun `a teacher does not edit offerings and a student does not read unpublished versions`() {
        val offering = get("/api/objects/edicion_curso/records?size=1", admin)["content"][0]["id"].asString()

        assertEquals(HttpStatus.FORBIDDEN, patch(demoUser("docente"), "edicion_curso", offering, mapOf("capacidad" to 999)).first)
        // desde ADR-0012 el estudiante lee versiones, pero solo las de sus matrículas activas: ninguna en borrador
        val (listed, versions) = call("GET", "/api/objects/version_curso/records?size=200", demoUser("estudiante"))
        assertEquals(HttpStatus.OK, listed)
        assertTrue(versions!!["content"].values().none { it["attributes"]["estado"].asString() == "BORRADOR" })
        assertTrue(call("GET", "/api/objects/curso/records", demoUser("estudiante")).first.is2xxSuccessful)
    }
}
