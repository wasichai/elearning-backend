package elearning.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest

// el contrato OpenAPI del LMS (ADR-0008): público, solo /api/lms/**, y con los tipos que valida el frontend
class OpenApiContractApiTest : WasichaiIntegrationTest() {
    private val spec by lazy {
        JsonMapper.builder().build().readTree(
            client
                .get()
                .uri("/api/lms/public/openapi")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody,
        )
    }

    // sin configurar, ningún origen ajeno: un despliegue con la UI y la API en el mismo dominio no necesita CORS, y
    // antes se heredaba http://localhost:* de Wasichai en cualquier entorno (#67)
    @Test
    fun `without configuration no other origin is allowed, not even localhost`() {
        client
            .options()
            .uri("/api/lms/public/openapi")
            .header("Origin", "http://localhost:5180")
            .header("Access-Control-Request-Method", "GET")
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `the contract is public and describes the catalog`() {
        assertTrue(spec["openapi"].asString().startsWith("3."))
        val paths = spec["paths"].propertyNames().toList()
        assertTrue("/api/lms/public/catalog/courses" in paths, paths.toString())
        assertTrue("/api/lms/public/catalog/courses/{slug}" in paths, paths.toString())
    }

    @Test
    fun `the server url does not come from the Host header`() {
        val spec =
            JsonMapper.builder().build().readTree(
                client
                    .get()
                    .uri("/api/lms/public/openapi")
                    .header("Host", "evil.example")
                    .exchange()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody,
            )

        assertEquals("/", spec["servers"][0]["url"].asString())
    }

    @Test
    fun `only the lms api is in it, not the platform's`() {
        val paths = spec["paths"].propertyNames().toList()

        // solo lo público: una ruta privada futura de /api/lms no debe aparecer en el contrato anónimo
        assertTrue(paths.all { it.startsWith("/api/lms/public/") }, paths.toString())
        assertFalse(paths.any { it.startsWith("/api/objects") || it.startsWith("/api/auth") }, paths.toString())
    }

    @Test
    fun `the response types match the contract the frontend validates`() {
        val schemas = spec["components"]["schemas"]
        val page = schemas["CoursePage"]["properties"].propertyNames().toList()
        val detail = schemas["CourseDetail"]["properties"].propertyNames().toList()

        assertEquals(setOf("content", "page", "size", "totalElements", "totalPages", "facets"), page.toSet())
        assertTrue(detail.containsAll(listOf("slug", "workload", "credits", "units", "offerings", "teachers")), detail.toString())
        val params =
            spec["paths"]["/api/lms/public/catalog/courses"]["get"]["parameters"]
                .values()
                .map { it["name"].asString() }
        assertEquals(
            setOf(
                "q",
                "category",
                "level",
                "modality",
                "startsFrom",
                "minWeeks",
                "maxWeeks",
                "enrollmentOpen",
                "minTheoryHours",
                "minPracticeHours",
                "minAutonomousHours",
                "sort",
                "page",
                "size",
            ),
            params.toSet(),
        )
    }
}
