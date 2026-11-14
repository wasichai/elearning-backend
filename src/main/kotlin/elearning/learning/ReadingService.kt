package elearning.learning

import elearning.media.ReadingDocument
import elearning.media.ReadingDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import wasichai.core.common.NotFoundException
import wasichai.files.FileService
import java.util.UUID

// la lectura de un recurso HTML del aula como documento de bloques (ADR-0045), calculada en cada petición desde su archivo.
// el recurso se busca como lo muestra el aula (matrícula propia y no retirada, versión publicada y enlazada desde su temario)
// y el archivo se lee de wasichai-files como quien llama, con el mismo alcance que su descarga. otro tipo o sin archivo: 404
@Service
class ReadingService(
    private val reader: ClassroomReader,
    private val files: FileService,
) {
    suspend fun read(
        enrollmentId: UUID,
        resourceVersionId: UUID,
    ): ReadingDocument {
        val resource = reader.resource(enrollmentId, resourceVersionId)
        if (resource.type != HTML || resource.file == null) throw NotFoundException("El recurso no es una lectura HTML")
        val bytes = bytesOf(files.open(ClassroomReader.RESOURCE_VERSION, resourceVersionId, ClassroomReader.FILE).content)
        // jsoup es CPU puro y un archivo puede rondar el tope: fuera del hilo de WebFlux, en el despachador de cálculo
        return withContext(Dispatchers.Default) { ReadingDocuments.read(bytes) }
    }

    internal companion object {
        private const val HTML = "HTML"

        // el maxBytes de version_recurso.archivo en model/model.json
        private const val MAX_BYTES = 25 * 1024 * 1024

        // el archivo entero, con tope: el campo admite hasta MAX_BYTES y el tope solo protege la memoria. uno mayor no
        // tiene lectura en bloques: 404, como lo que no es una lectura HTML
        suspend fun bytesOf(
            content: Flux<DataBuffer>,
            max: Int = MAX_BYTES,
        ): ByteArray {
            val joined =
                try {
                    DataBufferUtils.join(content, max).awaitSingle()
                } catch (_: DataBufferLimitException) {
                    throw NotFoundException("El recurso no es una lectura HTML")
                }
            try {
                return ByteArray(joined.readableByteCount()).also { joined.read(it) }
            } finally {
                DataBufferUtils.release(joined)
            }
        }
    }
}
