package elearning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// los roles del LMS sobre la administración de usuarios de Wasichai: varios a la vez (RF-ROL-03) y nadie se los da a
// sí mismo (RF-ROL-05).
//
// RF-ROL-05 lo cumple Wasichai 1.6.0, verificado en su código (AdminService): crear usuarios, cambiar sus roles y los
// permisos de un rol exigen MANAGE_ORGANIZATION, un permiso global que ningún rol LMS puede tener (model/apply.py solo
// admite permisos sobre objetos del modelo). Wasichai no distingue «a sí mismo»: quien tiene MANAGE_ORGANIZATION (solo
// ADMIN) cambia los roles de cualquiera, también los suyos, pero ya lo tiene todo. Por eso no hay regla propia
class RolesApiTest : LmsApiTest() {
    private val admin by lazy { bearer() }

    // una cuenta nueva con esos roles, creada por el administrador con la API de usuarios de Wasichai
    private fun account(vararg roles: String): Account {
        val email = "${uniqueName("roles-").lowercase()}@demo.wasichai-learning.test"
        val created =
            client
                .post()
                .uri("/api/users")
                .header("Authorization", admin)
                .bodyValue(mapOf("email" to email, "displayName" to "Cuenta de prueba de roles", "password" to DEMO_PASSWORD, "roles" to roles.toList()))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
        return Account(json.readTree(created)["id"].asString(), bearer(email, DEMO_PASSWORD))
    }

    // objeto → acciones que la cuenta tiene sobre él, según Wasichai
    private fun permissions(token: String): Map<String, Set<String>> {
        val objects: JsonNode = get("/api/auth/me/permissions", token)["objects"]
        return objects.propertyNames().associateWith { objects[it].strings().toSet() }
    }

    private fun send(
        method: String,
        path: String,
        token: String,
        body: Any,
    ): HttpStatus {
        val spec = if (method == "POST") client.post().uri(path) else client.put().uri(path)
        return HttpStatus.valueOf(
            spec
                .header("Authorization", token)
                .bodyValue(body)
                .exchange()
                .returnResult(String::class.java)
                .status
                .value(),
        )
    }

    @Test
    fun `a student who is also a creator holds the union of both roles`() {
        val both = permissions(account("ESTUDIANTE", "CREADOR").token)
        val student = permissions(demoUser("estudiante"))
        val creator = permissions(demoUser("creador"))

        // exactamente la unión: nada se pierde al sumar un rol y nada aparece que no dé ninguno de los dos
        val union = (student.keys + creator.keys).associateWith { student[it].orEmpty() + creator[it].orEmpty() }
        assertEquals(union, both)
        // lo que solo da ESTUDIANTE
        assertTrue("CREATE" in both.getValue("matricula") && "CREATE" in both.getValue("progreso_leccion"), both.toString())
        // lo que solo da CREADOR
        assertTrue("CREATE" in both.getValue("curso") && "ENVIAR_A_REVISION" in both.getValue("version_curso"), both.toString())
        // lo que no da ninguno de los dos
        assertTrue(both.getValue("version_curso").none { it in setOf("APROBAR", "PUBLICAR", "RETIRAR") }, both.toString())
    }

    @Test
    fun `the creator side of a student who is also a creator really writes`() {
        val both = account("ESTUDIANTE", "CREADOR").token
        val slug = uniqueName("multirrol-").lowercase()

        // ESTUDIANTE solo no crea cursos (ModelProvisioningApiTest); sumado a CREADOR, sí
        client
            .post()
            .uri("/api/objects/curso/records")
            .header("Authorization", both)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to slug.uppercase(), "slug" to slug, "titulo" to "Curso de una cuenta multirrol")))
            .exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `no LMS role grants itself privileges through the Wasichai administration`() {
        for (role in listOf("ESTUDIANTE", "DOCENTE", "CREADOR", "REVISOR")) {
            val me = account(role)
            val before = permissions(me.token)

            // darse ADMIN, ampliar su propio rol o crearse otra cuenta con ADMIN
            assertEquals(HttpStatus.FORBIDDEN, send("PUT", "/api/users/${me.id}/roles", me.token, mapOf("roles" to listOf(role, "ADMIN"))), role)
            val grant = mapOf("permissions" to listOf(mapOf("objectName" to "version_curso", "action" to "PUBLICAR")))
            assertEquals(HttpStatus.FORBIDDEN, send("PUT", "/api/roles/$role/permissions", me.token, grant), role)
            val other =
                mapOf(
                    "email" to "${uniqueName("intruso-").lowercase()}@demo.wasichai-learning.test",
                    "displayName" to "Otra cuenta",
                    "password" to DEMO_PASSWORD,
                    "roles" to listOf("ADMIN"),
                )
            assertEquals(HttpStatus.FORBIDDEN, send("POST", "/api/users", me.token, other), role)

            // y nada cambió: ni sus permisos ni los de su rol
            assertEquals(before, permissions(me.token), role)
        }
    }

    @Test
    fun `role changes made by the administrator are audited`() {
        val target = account("ESTUDIANTE")

        assertEquals(HttpStatus.OK, send("PUT", "/api/users/${target.id}/roles", admin, mapOf("roles" to listOf("ESTUDIANTE", "CREADOR"))))
        val entries = get("/api/audit?object=admin:user&recordId=${target.id}", admin).values()

        assertTrue(
            entries.any {
                it["recordId"].asString() == target.id && it["operation"].asString() == "UPDATE" && it["userEmail"].asString() == ADMIN_EMAIL
            },
            entries.toString(),
        )
    }
}

// una cuenta de prueba: su id y su sesión
private class Account(
    val id: String,
    val token: String,
)
