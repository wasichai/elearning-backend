package elearning.ai

import elearning.LmsApiTest
import elearning.jobs.PermanentJobFailure
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import wasichai.core.common.ForbiddenException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.util.UUID

// RF-IA-07 (E33.3, ADR-0033 §10): generar y validar son acciones declaradas sobre version_curso, distintas de aprobar y
// publicar. Aún no hay pasos que encolar, así que se llama a AiPermissions como cada persona de la demo, con su token
// real: ForbiddenException es el 403 de la API. Al ejecutar, se vuelve a mirar con los roles guardados (ADR-0022)
class AiPermissionsApiTest : LmsApiTest() {
    @Autowired
    private lateinit var permissions: AiPermissions

    @Autowired
    private lateinit var currentUser: CurrentUser

    @Autowired
    private lateinit var metadata: MetadataService

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private val admin by lazy { bearer() }

    // lo que hace un controlador: llamar como la persona del token
    private fun <T : Any> asPerson(
        token: String,
        block: suspend () -> T,
    ): T =
        runBlocking {
            val jwt = decoder.decode(token.removePrefix("Bearer ")).awaitSingle()
            mono { block() }
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(JwtAuthenticationToken(jwt)))
                .awaitSingle()
        }

    private fun allowed(
        token: String,
        action: AiAction,
    ): Boolean =
        try {
            asPerson(token) { permissions.requireToEnqueue(action) }
            true
        } catch (_: ForbiddenException) {
            false
        }

    @Test
    fun `the creator generates and is refused validating, approving and publishing`() {
        val creator = demoUser("creador")
        assertTrue(allowed(creator, AiAction.GENERAR))
        assertThrows(ForbiddenException::class.java) { asPerson(creator) { permissions.requireToEnqueue(AiAction.VALIDAR) } }
        for (action in listOf("APROBAR", "PUBLICAR")) {
            assertThrows(ForbiddenException::class.java, { asPerson(creator) { requireOnVersion(action) } }, action)
        }
    }

    @Test
    fun `the reviewer validates and does not generate`() {
        val reviewer = demoUser("revisor")
        assertTrue(allowed(reviewer, AiAction.VALIDAR))
        assertTrue(!allowed(reviewer, AiAction.GENERAR))
    }

    @Test
    fun `without GENERAR nobody enqueues a step`() {
        for (role in listOf("estudiante", "docente", "autoridad")) {
            val token = demoUser(role)
            for (action in AiAction.entries) assertTrue(!allowed(token, action), "$role $action")
        }
    }

    @Test
    fun `when it runs, the step checks again the stored roles of whoever asked for it`() {
        val email = "${uniqueName("ia")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Persona", "password" to DEMO_PASSWORD, "roles" to listOf("CREADOR")))
            .exchange()
            .expectStatus()
            .isCreated
        val token = bearer(email, DEMO_PASSWORD)
        val person = asPerson(token) { permissions.requireToEnqueue(AiAction.GENERAR) }
        runBlocking { permissions.requireStillAllowed(person.organizationId, person.userId, AiAction.GENERAR) }

        val changed =
            client
                .put()
                .uri("/api/users/${person.userId}/roles")
                .header("Authorization", admin)
                .bodyValue(mapOf("roles" to listOf("ESTUDIANTE")))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(HttpStatus.OK.value(), changed.status.value(), changed.responseBody)

        // el token con el que encoló dice todavía CREADOR; lo que cuenta son los roles guardados
        val failure =
            assertThrows(PermanentJobFailure::class.java) {
                runBlocking { permissions.requireStillAllowed(person.organizationId, person.userId, AiAction.GENERAR) }
            }
        assertTrue(failure.message!!.contains("GENERAR"), failure.message)
        assertThrows(PermanentJobFailure::class.java) {
            runBlocking { permissions.requireStillAllowed(person.organizationId, UUID.randomUUID(), AiAction.GENERAR) }
        }
    }

    private suspend fun requireOnVersion(action: String): AuthenticatedUser {
        val user = currentUser.require()
        currentUser.requirePermission(user, action, metadata.definitionOf("version_curso").obj.id)
        return user
    }
}
