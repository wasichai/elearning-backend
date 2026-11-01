package elearning.media

import elearning.common.listAll
import elearning.common.ref
import elearning.jobs.Job
import elearning.jobs.JobHandler
import elearning.jobs.PermanentJobFailure
import elearning.jobs.Requesters
import elearning.jobs.RequiredPermission
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import reactor.core.publisher.Flux
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.Actions
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.files.FileDescriptor
import wasichai.files.FileDownload
import wasichai.files.FileService
import wasichai.files.FileUpload
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

// el trabajo de generación (ADR-0018 §7, RF-CON-03/07): de una fuente_recurso, los artefactos HTML y PDF y una
// version_recurso nueva en BORRADOR con el artefacto del tipo de su recurso como archivo. Corre en la cola, nunca en el hilo
// de WebFlux, como la plataforma (asPlatform): por eso los permisos se comprueban al encolar (RenderService) y otra vez al
// ejecutar (ADR-0022), y la versión lleva como autor a quien lo pidió. Publicar no sobrescribe: cada generación es una versión nueva, que sigue el flujo
// editorial como cualquier otra.
//
// - la fuente se lee de wasichai-files y su hash se comprueba contra el que guardó al subirla
// - cada artefacto guarda su fuente, el hash de lo generado y de la fuente, el generador y su versión, y el trabajo
// - versión y artefactos se crean en una transacción; los archivos se suben antes, y lo que quede sin adjuntar lo borra
//   la limpieza de wasichai-files
// - repetir el trabajo (la réplica murió tras escribir) devuelve lo ya escrito: (trabajo_id, formato) es único
@Component
class RenderJobHandler(
    private val records: RecordService,
    private val files: FileService,
    private val renderer: DocumentRenderer,
    private val transactions: TransactionalOperator,
    private val requesters: Requesters,
) : JobHandler {
    override val type: String = TYPE

    override suspend fun handle(job: Job): String {
        val payload = MAPPER.readTree(job.payload)
        val source = UUID.fromString(payload.required(SOURCE_ID).asString())
        val requester = UUID.fromString(payload.required(REQUESTER_ID).asString()).toString()
        return try {
            records.asPlatform(job.organizationId, AUDIT_SOURCE) {
                existing(job.id) ?: run {
                    // quien la pidió puede todavía lo que se le exigió al encolar (ADR-0022)
                    requesters.requireStillAllowed(job.organizationId, UUID.fromString(requester), REQUIRED)
                    generate(job, source, requester)
                }
            }
        } catch (e: RenderException) {
            // lo que no cambia por reintentar termina el trabajo; lo pasajero vuelve a la cola (ADR-0022)
            if (e.permanent) throw PermanentJobFailure(e.message ?: "The render failed", e)
            throw e
        }
    }

    private suspend fun generate(
        job: Job,
        sourceId: UUID,
        requester: String,
    ): String {
        val source = records.get(SOURCE, sourceId)
        val resourceId = source.attributes.ref(RESOURCE) ?: throw RenderException("The source $sourceId has no resource")
        val resource = records.get(RESOURCE, UUID.fromString(resourceId))
        val resourceType = resource.attributes[TYPE_FIELD]?.toString()
        val primary = primaryFormat(resourceType) ?: throw RenderException("A $resourceType resource is not generated: only HTML or PDF")
        val stored = (source.attributes[FILE] as? Map<*, *>)?.get(SHA256)?.toString() ?: throw RenderException("The source $sourceId has no file")
        val bytes = read(files.open(SOURCE, sourceId, FILE))
        val sourceHash = sha256(bytes)
        if (!sourceHash.equals(stored, ignoreCase = true)) throw RenderException("The source $sourceId does not match the hash stored with it")

        val rendered = ArtifactFormat.entries.map { renderer.render(bytes, it) }
        val baseName = "${resource.attributes[CODE]?.toString()?.takeIf { it.isNotBlank() } ?: "recurso"}-fuente-${source.attributes[NUMBER]}"
        val versionFile = stage(VERSION, "$baseName.${primary.extension}", rendered.first { it.format == primary })
        val artifactFiles = rendered.map { it to stage(ARTIFACT, "$baseName.${it.format.extension}", it) }

        return transactions.executeAndAwait {
            val number = records.listAll(VERSION, mapOf(RESOURCE to resourceId)).maxOfOrNull { numberOf(it.attributes[NUMBER]) }?.plus(1) ?: 1
            val version =
                records.create(
                    VERSION,
                    RecordRequest(
                        mapOf(RESOURCE to resourceId, NUMBER to number, AUTHOR to requester, STATE to DRAFT, FILE to versionFile.id.toString()),
                    ),
                )
            val artifacts =
                artifactFiles.map { (document, file) ->
                    val created =
                        records.create(
                            ARTIFACT,
                            RecordRequest(
                                mapOf(
                                    VERSION to version.id,
                                    SOURCE to sourceId.toString(),
                                    FORMAT to document.format.name,
                                    FILE to file.id.toString(),
                                    SHA256 to file.sha256,
                                    SOURCE_HASH to sourceHash,
                                    GENERATOR to document.generator,
                                    GENERATOR_VERSION to document.generatorVersion,
                                    JOB to job.id.toString(),
                                ),
                            ),
                        )
                    GeneratedArtifact(created.id, document.format.name, file.sha256)
                }
            result(version.id, number, artifacts)
        }
    }

    // lo que ya escribió este trabajo en un intento anterior que no llegó a cerrarse
    private suspend fun existing(job: UUID): String? {
        val artifacts = records.listAll(ARTIFACT, mapOf(JOB to job.toString()))
        if (artifacts.isEmpty()) return null
        val versionId = artifacts.first().attributes.ref(VERSION) ?: return null
        val version = records.get(VERSION, UUID.fromString(versionId))
        return result(
            versionId,
            numberOf(version.attributes[NUMBER]),
            artifacts.map { GeneratedArtifact(it.id, it.attributes[FORMAT].toString(), it.attributes[SHA256].toString()) }.sortedBy { it.format },
        )
    }

    // dentro de asPlatform: solo la escritura de este mismo bloque puede adjuntarlo (ADR-068 de Wasichai)
    private suspend fun stage(
        objectName: String,
        name: String,
        document: RenderedDocument,
    ): FileDescriptor {
        val content: Flux<DataBuffer> = Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(document.bytes))
        val staged = files.stage(objectName, FILE, FileUpload(name, document.format.contentType, content))
        if (!staged.sha256.equals(sha256(document.bytes), ignoreCase = true)) {
            throw RenderException("The stored ${document.format} does not match its hash", permanent = false)
        }
        return staged
    }

    private suspend fun read(download: FileDownload): ByteArray {
        val joined =
            try {
                DataBufferUtils.join(download.content, MAX_SOURCE_BYTES).awaitSingle()
            } catch (e: DataBufferLimitException) {
                throw RenderException("The source is larger than $MAX_SOURCE_BYTES bytes", e)
            }
        try {
            return ByteArray(joined.readableByteCount()).also { joined.read(it) }
        } finally {
            DataBufferUtils.release(joined)
        }
    }

    private fun result(
        versionId: String,
        number: Int,
        artifacts: List<GeneratedArtifact>,
    ): String = MAPPER.writeValueAsString(mapOf("versionId" to versionId, "versionNumber" to number, "artifacts" to artifacts))

    companion object {
        const val TYPE = "media.render"
        const val SOURCE_ID = "sourceId"
        const val REQUESTER_ID = "requesterId"
        private const val AUDIT_SOURCE = "job:render"

        // la fuente tiene tope de 1 MB en su campo: esto solo protege la memoria si alguien lo sube
        private const val MAX_SOURCE_BYTES = 2 * 1024 * 1024

        internal const val SOURCE = "fuente_recurso"
        internal const val ARTIFACT = "artefacto_generado"
        internal const val RESOURCE = "recurso"
        internal const val VERSION = "version_recurso"
        internal const val TYPE_FIELD = "tipo"
        private const val CODE = "codigo"
        private const val NUMBER = "numero"
        private const val AUTHOR = "autor_id"
        private const val STATE = "estado"
        private const val DRAFT = "BORRADOR"
        private const val FILE = "archivo"
        private const val FORMAT = "formato"
        private const val SHA256 = "sha256"
        private const val SOURCE_HASH = "hash_fuente"
        private const val GENERATOR = "generador"
        private const val GENERATOR_VERSION = "version_generador"
        private const val JOB = "trabajo_id"

        // lo que RenderService exige al encolar: crear la versión y leer la fuente y su recurso
        private val REQUIRED =
            listOf(
                RequiredPermission(Actions.CREATE, VERSION),
                RequiredPermission(Actions.READ, SOURCE),
                RequiredPermission(Actions.READ, RESOURCE),
            )

        internal val MAPPER: JsonMapper = JsonMapper.builder().findAndAddModules().build()

        // el artefacto que hace de archivo de la versión: el que admite el tipo de su recurso (FileTypes.RESOURCE)
        fun primaryFormat(resourceType: String?): ArtifactFormat? = ArtifactFormat.entries.firstOrNull { it.name == resourceType }

        private fun numberOf(value: Any?): Int = (value as? Number)?.toInt() ?: value?.toString()?.toBigDecimalOrNull()?.toInt() ?: 0

        private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

// un artefacto tal como lo cuenta el resultado del trabajo
data class GeneratedArtifact(
    val id: String,
    val format: String,
    val sha256: String,
)
