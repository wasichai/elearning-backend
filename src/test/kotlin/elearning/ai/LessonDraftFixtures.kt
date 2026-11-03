package elearning.ai

import elearning.media.ArtifactFormat
import elearning.media.DocumentRenderer
import elearning.media.RenderedDocument
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

// el generador de HTML de las pruebas de API del borrador (E33.6): sin Asciidoctor, un HTML con el AsciiDoc dentro tal cual y
// lo que el saneado de ADR-0022 debe quitar (un script en la cabecera y en el cuerpo, un manejador de eventos, un iframe y un
// enlace javascript:). Así se ve que lo que se guarda es el HTML saneado, sea cual sea el generador
object LessonDraftFixtures {
    const val GENERATOR = "fake-lesson-renderer"
    const val MARK = "contenido-de-la-leccion"

    @TestConfiguration(proxyBeanMethods = false)
    class FakeRenderer {
        @Bean
        @Primary
        fun fakeLessonRenderer(): DocumentRenderer =
            object : DocumentRenderer {
                override fun render(
                    source: ByteArray,
                    format: ArtifactFormat,
                ): RenderedDocument {
                    val html =
                        "<html><head><title>Lección</title><script>steal()</script></head><body onload=\"steal()\">" +
                            "<div class=\"$MARK\"><pre>${String(source)}</pre></div>" +
                            "<iframe src=\"https://evil.example.com\"></iframe><a href=\"javascript:steal()\">x</a></body></html>"
                    return RenderedDocument(format, html.toByteArray(), GENERATOR, "$GENERATOR 1.0")
                }
            }
    }
}
