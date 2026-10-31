package elearning.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException

// RNF-SEG-07: cada tipo de recurso y la entrega admiten su lista de tipos detectados por wasichai-files, y nada más
class FileTypesTest {
    private val allTypes = listOf("HTML", "PDF", "VIDEO", "PRESENTACION", "LABORATORIO", "CUESTIONARIO", "IMAGEN", "DIAGRAMA")

    @Test
    fun `every resource type of the model has its list`() {
        assertThat(FileTypes.RESOURCE.keys).containsExactlyInAnyOrderElementsOf(allTypes)
        assertThat(FileTypes.RESOURCE.values).allSatisfy { assertThat(it).isNotEmpty() }
    }

    @Test
    fun `a resource takes what its type shows and nothing else`() {
        assertThat(FileTypes.allowedForResource("PDF")).containsExactly("application/pdf")
        assertThat(FileTypes.allowedForResource("IMAGEN")).contains("image/png").doesNotContain("application/octet-stream", "application/pdf")
        assertThat(FileTypes.allowedForResource("PRESENTACION")).contains("application/pdf").doesNotContain("application/octet-stream")
        // wasichai-files deja HTML y vídeo como octet-stream: es lo único que pueden ser
        assertThat(FileTypes.allowedForResource("HTML")).containsExactly("application/octet-stream")
        assertThat(FileTypes.allowedForResource("VIDEO")).containsExactly("application/octet-stream")
        assertThat(FileTypes.allowedForResource("DIAGRAMA")).doesNotContain("application/octet-stream")
        assertThat(FileTypes.allowedForResource("DESCONOCIDO")).isEmpty()
        assertThat(FileTypes.allowedForResource(null)).isEmpty()
    }

    @Test
    fun `a submission never takes what wasichai-files does not recognize`() {
        assertThat(FileTypes.SUBMISSION).contains("application/pdf", "text/plain", "image/png", "application/zip").doesNotContain("application/octet-stream")
    }

    @Test
    fun `require refuses with a 400 on the field`() {
        FileTypes.require("APPLICATION/PDF", FileTypes.allowedForResource("PDF"), "archivo", "a PDF resource")
        assertThatThrownBy { FileTypes.require("image/png", FileTypes.allowedForResource("PDF"), "archivo", "a PDF resource") }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("image/png")
    }
}
