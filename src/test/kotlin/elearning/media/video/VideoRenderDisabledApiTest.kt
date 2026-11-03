package elearning.media.video

import elearning.LmsApiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus

// con la configuración por defecto el render está apagado (ADR-0039 §3, ADR-0040 §7, criterio de #267): el backend arranca
// y funciona sin trabajador ni Remotion (las demás pruebas de integración corren así), no existe el manejador que llama al
// trabajador ni su cliente, y pedir un render es 409. Sin manejador, ninguna réplica reclama un VIDEO_RENDER
class VideoRenderDisabledApiTest : LmsApiTest() {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `asking for a render is a conflict that says rendering is off`() {
        val creator = demoUser("creador")
        val resource =
            json
                .readTree(
                    client
                        .post()
                        .uri("/api/objects/recurso/records")
                        .header("Authorization", creator)
                        .bodyValue(mapOf("attributes" to mapOf("titulo" to "Vídeo sin render", "tipo" to "VIDEO", "idioma" to "es")))
                        .exchange()
                        .expectStatus()
                        .isCreated
                        .expectBody(String::class.java)
                        .returnResult()
                        .responseBody,
                )["id"]
                .asString()
        val result =
            client
                .post()
                .uri("/api/lms/studio/resources/$resource/video-renders")
                .header("Authorization", creator)
                .bodyValue(mapOf("guion" to mapOf("version" to 1)))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()

        assertEquals(HttpStatus.CONFLICT.value(), result.status.value())
        assertTrue(json.readTree(result.responseBody)["detail"].asString().startsWith("Video rendering is not enabled"), result.responseBody)
        assertTrue(context.getBeansOfType(VideoRenderJobHandler::class.java).isEmpty(), "sin render no hay cliente del trabajador")
    }
}
