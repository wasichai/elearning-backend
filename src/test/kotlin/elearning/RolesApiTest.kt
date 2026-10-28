package elearning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

// los roles del LMS sobre la administración de usuarios de Wasichai: varios a la vez (RF-ROL-03)
class RolesApiTest : LmsApiTest() {
    private val admin by lazy { bearer() }

    // una cuenta nueva con esos roles, creada por el administrador con la API de usuarios de Wasichai
    private fun account(vararg roles: String): String {
        val email = "${uniqueName("roles-").lowercase()}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Cuenta de prueba de roles", "password" to DEMO_PASSWORD, "roles" to roles.toList()))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, DEMO_PASSWORD)
    }

    // objeto → acciones que la cuenta tiene sobre él, según Wasichai
    private fun permissions(token: String): Map<String, Set<String>> {
        val objects: JsonNode = get("/api/auth/me/permissions", token)["objects"]
        return objects.propertyNames().associateWith { objects[it].strings().toSet() }
    }

    @Test
    fun `a student who is also a creator holds the union of both roles`() {
        val both = permissions(account("ESTUDIANTE", "CREADOR"))
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
        val both = account("ESTUDIANTE", "CREADOR")
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
}
