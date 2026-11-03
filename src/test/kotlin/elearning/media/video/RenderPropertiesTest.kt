package elearning.media.video

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// ADR-0040 §7: apagado por defecto; encendido, no arranca sin URL y secreto, y el secreto no sale en ningún registro
class RenderPropertiesTest {
    private val token = "t".repeat(RenderProperties.MIN_TOKEN)

    @Test
    fun `rendering is off by default and needs nothing then`() {
        assertFalse(RenderProperties().enabled)
    }

    @Test
    fun `enabled, it needs the worker url and a long enough shared secret`() {
        assertThrows<IllegalArgumentException> { RenderProperties(enabled = true, workerToken = token) }
        assertThrows<IllegalArgumentException> { RenderProperties(enabled = true, workerUrl = "http://render-worker:8095", workerToken = "short") }
        assertThrows<IllegalArgumentException> { RenderProperties(enabled = true, workerUrl = "render-worker:8095", workerToken = token) }
        RenderProperties(enabled = true, workerUrl = "http://render-worker:8095", workerToken = token)
    }

    @Test
    fun `the shared secret never shows`() {
        val properties = RenderProperties(enabled = true, workerUrl = "http://render-worker:8095", workerToken = token)
        assertFalse(properties.toString().contains(token))
        assertTrue(properties.toString().contains("workerToken=***"))
    }
}
