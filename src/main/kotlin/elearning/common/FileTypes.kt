package elearning.common

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordWrite
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// RNF-SEG-07 (ADR-0018 §8): los tipos de archivo que admite cada sitio, sobre lo que wasichai-files DETECTA por los bytes,
// nunca sobre la extensión ni el tipo que declara el cliente. wasichai-files 1.6.0 solo reconoce PNG, JPEG, GIF, WebP y
// PDF por número mágico, los ofimáticos zip y el texto (text/plain, text/csv) si coinciden con lo declarado, y zip; todo
// lo demás (HTML, vídeo, SVG, ejecutables) es application/octet-stream. Por eso HTML y VIDEO admiten octet-stream: no
// hay otra forma de subirlos hasta que Wasichai los detecte. Lo que se descarga va siempre como adjunto y con nosniff
object FileTypes {
    const val OCTET_STREAM = "application/octet-stream"
    const val PDF = "application/pdf"
    const val ZIP = "application/zip"
    const val TEXT = "text/plain"
    const val CSV = "text/csv"
    private val IMAGES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    private val OFFICE =
        setOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
        )
    private val SLIDES =
        setOf(
            PDF,
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.presentation",
        )

    // tipo_recurso → tipos detectados que admite su archivo. un tipo de recurso sin entrada no admite archivo
    val RESOURCE: Map<String, Set<String>> =
        mapOf(
            "HTML" to setOf(OCTET_STREAM),
            "PDF" to setOf(PDF),
            "VIDEO" to setOf(OCTET_STREAM),
            "PRESENTACION" to SLIDES,
            "LABORATORIO" to setOf(ZIP, PDF, TEXT, CSV),
            "CUESTIONARIO" to setOf(PDF, TEXT, CSV),
            "IMAGEN" to IMAGES,
            // SVG llega como octet-stream y puede llevar script: un diagrama se sube como imagen o PDF
            "DIAGRAMA" to IMAGES + PDF,
        )

    // lo que entrega un estudiante: documentos, imágenes, texto y zip; nunca octet-stream (lo que no se reconoce)
    val SUBMISSION: Set<String> = setOf(PDF, ZIP, TEXT, CSV) + IMAGES + OFFICE

    fun allowedForResource(type: String?): Set<String> = RESOURCE[type].orEmpty()

    // 400 en el campo, como el de wasichai-files cuando el campo tiene su propia lista
    fun require(
        contentType: String,
        allowed: Set<String>,
        field: String,
        what: String,
    ) {
        if (contentType.lowercase() in allowed) return
        val expected = if (allowed.isEmpty()) "no file" else allowed.sorted().joinToString(", ")
        throw ValidationException("File type $contentType is not allowed in $what", field, "must be one of: $expected")
    }
}

// el tipo que wasichai-files detectó al guardar un archivo, leído por su función de lectura stored_file_descriptor
// (ADR-061 de Wasichai), la misma con la que un registro muestra su archivo. Wasichai 1.6.0 no pasa el descriptor a las
// guardas: la guarda ve el id del archivo nuevo
@Component
class DetectedFileTypes(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
) {
    // el tipo del archivo que la escritura pone en field, si pone uno nuevo; null si no lo toca, lo quita o deja el mismo.
    // un id que no es de la organización da null: lo rechaza StoredFileGuard de wasichai-files
    suspend fun incoming(
        change: RecordWrite,
        field: String,
    ): String? {
        val attributes = change.attributes ?: return null
        if (!attributes.containsKey(field)) return null
        val next = attributes.ref(field)?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() } ?: return null
        if (next.toString() == change.before?.ref(field)) return null
        return of(change.organizationId, next)
    }

    suspend fun of(
        organizationId: UUID,
        fileId: UUID,
    ): String? =
        db
            .sql(
                """
                SELECT d ->> 'contentType' AS content_type
                FROM (SELECT CAST(${schemas.metadata}.stored_file_descriptor(:file, :org) AS jsonb) AS d) AS f
                WHERE d IS NOT NULL
                """.trimIndent(),
            ).bind("file", fileId)
            .bind("org", organizationId)
            .map { row, _ -> row.get("content_type", String::class.java) ?: FileTypes.OCTET_STREAM }
            .one()
            .awaitSingleOrNull()
}

// el tipo de un archivo tal como lo da un registro leído: su descriptor {id, name, contentType, size, sha256}
fun fileContentType(value: Any?): String? = (value as? Map<*, *>)?.get("contentType")?.toString()
