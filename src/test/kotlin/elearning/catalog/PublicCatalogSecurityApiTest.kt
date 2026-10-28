package elearning.catalog

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import wasichai.test.WasichaiIntegrationTest

// la cadena pública (ADR-0006) deja pasar sin sesión solo GET bajo /api/lms/public/**, y no le quita nada a la de
// Wasichai: si la nuestra la hiciera retroceder (@ConditionalOnMissingBean), toda la plataforma quedaría abierta
class PublicCatalogSecurityApiTest : WasichaiIntegrationTest() {
    @Test
    fun `an anonymous GET under the public path passes security`() {
        // ninguna ruta atiende aquí todavía: 404 y no 401 prueba que la seguridad dejó pasar
        client
            .get()
            .uri("/api/lms/public/no-existe")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `HEAD is a read like GET`() {
        client
            .head()
            .uri("/api/lms/public/catalog/courses")
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `the public path never takes writes, not even with a token`() {
        client
            .post()
            .uri("/api/lms/public/catalog/courses")
            .bodyValue("{}")
            .exchange()
            .expectStatus()
            .isUnauthorized
        for (method in listOf(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            client
                .method(method)
                .uri("/api/lms/public/catalog/courses/x")
                .exchange()
                .expectStatus()
                .isUnauthorized
        }
        client
            .post()
            .uri("/api/lms/public/catalog/courses")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .bodyValue("{}")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    // un 401 con WWW-Authenticate: Basic hace que el navegador abra su diálogo de usuario y contraseña: aquí no hay
    // usuario ni contraseña que pedir (hallazgo de las pruebas e2e del frontend)
    @Test
    fun `a refused write never asks the browser for basic credentials`() {
        client
            .post()
            .uri("/api/lms/public/catalog/courses")
            .bodyValue("{}")
            .exchange()
            .expectStatus()
            .isUnauthorized
            .expectHeader()
            .doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
    }

    @Test
    fun `the rest of the lms api still needs a session`() {
        client
            .get()
            .uri("/api/lms/privado")
            .exchange()
            .expectStatus()
            .isUnauthorized
        client
            .get()
            .uri("/api/lms/publicx/catalog")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `wasichai keeps guarding its own api and its sign in`() {
        client
            .get()
            .uri("/api/objects")
            .exchange()
            .expectStatus()
            .isUnauthorized
        client
            .get()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `a browser on an allowed origin may call the public path`() {
        client
            .options()
            .uri("/api/lms/public/catalog/courses")
            .header(HttpHeaders.ORIGIN, "http://localhost:5180")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5180")
    }
}
