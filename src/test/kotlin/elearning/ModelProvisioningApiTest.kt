package elearning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// el modelo de model/ aplicado de verdad: lo que E3 declara es lo que Wasichai hace cumplir
class ModelProvisioningApiTest : LmsApiTest() {
    @Test
    fun `a course is created and read back through the record api`() {
        val admin = bearer()
        val created =
            json.readTree(
                client
                    .post()
                    .uri("/api/objects/curso/records")
                    .header("Authorization", admin)
                    .bodyValue(mapOf("attributes" to mapOf("codigo" to uniqueName("C"), "slug" to uniqueName("c"), "titulo" to "Curso de prueba")))
                    .exchange()
                    .expectStatus()
                    .isCreated
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody,
            )
        val read = get("/api/objects/curso/records/${created["id"].asString()}", admin)

        assertEquals("Curso de prueba", read["attributes"]["titulo"].asString())
        // valores por defecto del modelo: idioma y nivel
        assertEquals("es", read["attributes"]["idioma"].asString())
        assertEquals("INICIAL", read["attributes"]["nivel"].asString())
    }

    @Test
    fun `applying the model again changes nothing`() {
        val run = applyModel()

        assertEquals(0, run.exit, run.output)
        assertTrue("Sin cambios" in run.output, run.output)
    }

    @Test
    fun `the demo data is there, drafts included`() {
        val admin = bearer()
        val courses = get("/api/objects/curso/records?size=200", admin)["content"].strings { it["attributes"]["codigo"] }

        assertTrue(courses.containsAll(listOf("SRTM-ANU-101", "SGSPE-101", "IA-AGT-201", "SRTM-ATC-001")), courses.toString())
        val states = get("/api/objects/version_curso/records?size=200", admin)["content"].strings { it["attributes"]["estado"] }
        assertTrue("BORRADOR" in states && "PUBLICADA" in states, states.toString())
    }

    @Test
    fun `a student reads the catalog objects but cannot write them`() {
        val student = demoUser("estudiante")

        client
            .get()
            .uri("/api/objects/curso/records")
            .header("Authorization", student)
            .exchange()
            .expectStatus()
            .isOk
        client
            .post()
            .uri("/api/objects/curso/records")
            .header("Authorization", student)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to uniqueName("C"), "slug" to uniqueName("c"), "titulo" to "No")))
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `nobody but the administrator may publish, and the creator may not approve`() {
        for (role in listOf("estudiante", "docente", "creador", "revisor", "docente.revisor")) {
            // un rol que ni siquiera ve version_curso no la publica
            val actions = get("/api/auth/me/permissions", demoUser(role))["objects"]["version_curso"]?.strings().orEmpty()
            assertTrue("PUBLICAR" !in actions && "RETIRAR" !in actions, "$role: $actions")
        }
        val creator = get("/api/auth/me/permissions", demoUser("creador"))["objects"]["version_curso"].strings()
        assertTrue("ENVIAR_A_REVISION" in creator && "APROBAR" !in creator, creator.toString())
    }

    @Test
    fun `one account holds several roles at once`() {
        val permissions = get("/api/auth/me/permissions", demoUser("docente.revisor"))["objects"]

        // DOCENTE: lee las ediciones; REVISOR: aprueba versiones. la misma cuenta, sin cambiar de sesión
        assertTrue("READ" in permissions["edicion_curso"].strings(), permissions.toString())
        assertTrue("APROBAR" in permissions["version_curso"].strings(), permissions.toString())
    }
}
