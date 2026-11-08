package elearning.learning

import elearning.media.ReadingDocument
import elearning.media.ReadingDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.stereotype.Service
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
        val download = files.open(ClassroomReader.RESOURCE_VERSION, resourceVersionId, ClassroomReader.FILE)
        // el campo admite hasta MAX_BYTES; el tope solo protege la memoria
        val joined = DataBufferUtils.join(download.content, MAX_BYTES).awaitSingle()
        val bytes =
            try {
                ByteArray(joined.readableByteCount()).also { joined.read(it) }
            } finally {
                DataBufferUtils.release(joined)
            }
        // jsoup es CPU puro y un archivo puede rondar el tope: fuera del hilo de WebFlux, en el despachador de cálculo
        return withContext(Dispatchers.Default) { ReadingDocuments.read(bytes) }
    }

    private companion object {
        const val HTML = "HTML"

        // el maxBytes de version_recurso.archivo en model/model.json
        const val MAX_BYTES = 25 * 1024 * 1024
    }
}
