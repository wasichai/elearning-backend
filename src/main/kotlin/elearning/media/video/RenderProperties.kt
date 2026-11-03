package elearning.media.video

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

// lms.render.* (ADR-0040 §7): el render de vídeo es opcional y está apagado por defecto. Apagado, pedir un render es 409 y
// no existe ni el manejador que llama al trabajador ni su cliente HTTP. Encendido, no arranca sin la URL y el secreto
// compartido del trabajador (render-worker/)
@ConfigurationProperties("lms.render")
data class RenderProperties(
    val enabled: Boolean = false,
    // el trabajador en la red interna, p. ej. http://render-worker:8095
    val workerUrl: String = "",
    // el secreto compartido (Authorization: Bearer); nunca va a registros ni a lms.trabajo
    val workerToken: String = "",
    // lo que puede tardar un render, de la petición al informe; lms.jobs.lease tiene que ser mayor
    val timeout: Duration = Duration.ofMinutes(30),
    // renders en curso a la vez, contando todas las réplicas: los demás esperan en la cola
    val maxConcurrent: Int = 1,
    // duración máxima de un vídeo: la suma de las secciones del guion
    val maxSeconds: Int = 900,
    // tamaño máximo del MP4; wasichai.files.max-bytes no puede ser menor
    val maxBytes: DataSize = DataSize.ofMegabytes(256),
) {
    init {
        require(timeout > Duration.ZERO) { "lms.render.timeout must be positive" }
        require(maxConcurrent > 0) { "lms.render.max-concurrent must be positive" }
        require(maxSeconds > 0) { "lms.render.max-seconds must be positive" }
        require(maxBytes.toBytes() > 0) { "lms.render.max-bytes must be positive" }
        if (enabled) {
            require(workerUrl.startsWith("http://") || workerUrl.startsWith("https://")) {
                "lms.render.worker-url (LMS_RENDER_WORKER_URL) must be the http(s) URL of the render worker when lms.render.enabled is true"
            }
            require(workerToken.length >= MIN_TOKEN) {
                "lms.render.worker-token (LMS_RENDER_WORKER_TOKEN) must have at least $MIN_TOKEN characters when lms.render.enabled is true"
            }
        }
    }

    // el secreto no sale en ningún registro
    override fun toString(): String =
        "RenderProperties(enabled=$enabled, workerUrl=$workerUrl, workerToken=***, timeout=$timeout, maxConcurrent=$maxConcurrent, " +
            "maxSeconds=$maxSeconds, maxBytes=$maxBytes)"

    companion object {
        // el mismo mínimo que exige el trabajador
        const val MIN_TOKEN = 32
    }
}
