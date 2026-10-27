package elearning

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.test.WasichaiIntegrationTest

// la aplicación entera arranca como en producción (ElearningApplication es la @SpringBootConfiguration que encuentra
// @SpringBootTest desde este paquete) contra el PostgreSQL 18 de Testcontainers de wasichai-test, con su secreto JWT
// fijo y la semilla dev. WasichaiIntegrationTest ya lleva la etiqueta; se repite para que se lea aquí
@Tag("integration")
class ApplicationStartupTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var database: DatabaseClient

    @Test
    fun `the context is the elearning application on postgresql 18`() {
        assertThat(context.getBeanNamesForType(ElearningApplication::class.java)).hasSize(1)
        // bloquear está bien en una prueba: aquí no hay hilo de WebFlux que proteger
        val version =
            database
                .sql("SHOW server_version_num")
                .map { row -> row.get(0, String::class.java)!! }
                .one()
                .block()
        assertThat(version).startsWith("18")
    }

    @Test
    fun `health answers 200 without a token`() {
        client
            .get()
            .uri("/api/health")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo("UP")
            .jsonPath("$.application")
            .isEqualTo("elearning-backend")
    }

    @Test
    fun `the generic api refuses a request without a token`() {
        client
            .get()
            .uri("/api/objects")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `the dev seed administrator logs in and the token opens the generic api`() {
        client
            .post()
            .uri("/api/auth/login")
            .bodyValue(mapOf("email" to ADMIN_EMAIL, "password" to ADMIN_PASSWORD))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.token")
            .isNotEmpty

        // bearer() repite ese login y devuelve el token; con él, la misma ruta que sin token da 401
        client
            .get()
            .uri("/api/objects")
            .header("Authorization", bearer())
            .exchange()
            .expectStatus()
            .isOk
    }
}
