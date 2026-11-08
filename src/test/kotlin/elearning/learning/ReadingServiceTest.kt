package elearning.learning

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import reactor.core.publisher.Flux
import wasichai.core.common.NotFoundException

// el archivo de la lectura se lee entero con tope: uno mayor es 404, no un error del servidor
class ReadingServiceTest {
    private fun content(vararg parts: String): Flux<DataBuffer> = Flux.fromArray(parts).map { DefaultDataBufferFactory.sharedInstance.wrap(it.toByteArray()) }

    @Test
    fun `a file within the limit is read whole`() {
        assertThat(String(runBlocking { ReadingService.bytesOf(content("<p>", "hola", "</p>"), max = 11) })).isEqualTo("<p>hola</p>")
    }

    @Test
    fun `a file over the limit is 404`() {
        assertThatThrownBy { runBlocking { ReadingService.bytesOf(content("<p>", "hola", "</p>"), max = 10) } }.isInstanceOf(NotFoundException::class.java)
    }
}
