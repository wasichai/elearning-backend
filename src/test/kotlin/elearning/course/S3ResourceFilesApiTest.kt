package elearning.course

import elearning.LmsApiTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.web.reactive.function.BodyInserters
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import tools.jackson.databind.JsonNode
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

private const val PREFIX = "recursos"

// RF-CON-02: con wasichai.files.store=s3 el archivo de un recurso va a un almacén S3 compatible (MinIO, el mismo servidor
// que compose.yaml) y nunca a PostgreSQL; wasichai-files registra su sha256 al subirlo. desde Wasichai 1.8.0 (ADR-074 de
// Wasichai) cada descarga lleva ese hash en Repr-Digest y el servidor la comprueba mientras la transmite: lo alterado en
// el bucket nunca se sirve entero y queda en el rastro admin:stored-file (L6 de #216)
@TestPropertySource(properties = ["wasichai.files.store=s3", "wasichai.files.s3.prefix=$PREFIX"])
class S3ResourceFilesApiTest : LmsApiTest() {
    private val creator by lazy { demoUser("creador") }
    private val admin by lazy { bearer() }

    @Test
    fun `a resource file lands in the s3 bucket with its sha256 and the download carries that hash in Repr-Digest`() {
        val bytes = reading()
        val version = draftVersion()

        upload(version, bytes)

        val descriptor = get("/api/objects/version_recurso/records/$version", creator)["attributes"]["archivo"]
        assertEquals(sha256(bytes), descriptor["sha256"].asString())
        assertEquals(bytes.size.toLong(), descriptor["size"].asLong())
        // los bytes están en el bucket, bajo el prefijo configurado, con la clave <organización>/<archivo>
        assertArrayEquals(bytes, objectBytes(keyOf(descriptor)))
        val download = fetch(version)
        assertEquals(HttpStatus.OK.value(), download.status.value())
        assertArrayEquals(bytes, download.responseBody)
        // RFC 9530: el sha256 registrado, en base64 entre dos puntos
        assertEquals("sha-256=:${base64Sha256(bytes)}:", download.responseHeaders.getFirst("Repr-Digest"))
    }

    // L6 de #216: un archivo pequeño (un solo bloque) alterado en el bucket es un 500 antes de enviar nada, queda en el
    // rastro de administración y, devuelto a sus bytes originales, se sirve otra vez
    @Test
    fun `a small file altered in the bucket fails its download and lands on the admin trail`() {
        val bytes = note()
        val version = draftVersion()
        upload(version, bytes)
        val descriptor = get("/api/objects/version_recurso/records/$version", creator)["attributes"]["archivo"]
        val key = keyOf(descriptor)

        // misma longitud, un byte cambiado: solo el hash lo nota
        putObject(key, altered(bytes))

        val refused = fetch(version)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), refused.status.value())
        assertFalse(altered(bytes).contentEquals(refused.responseBody))
        val trail = get("/api/audit?objectName=admin:stored-file&recordId=${descriptor["id"].asString()}", admin).values().toList()
        assertTrue(trail.isNotEmpty())
        assertTrue(trail.all { it["operation"].asString() == "UPDATE" })

        putObject(key, bytes)

        val served = fetch(version)
        assertEquals(HttpStatus.OK.value(), served.status.value())
        assertArrayEquals(bytes, served.responseBody)
    }

    // L6 de #216: uno grande (varios bloques) ya envió cabeceras y bytes cuando se nota; la respuesta se corta antes de
    // su Content-Length, así que el cliente nunca recibe el archivo alterado entero
    @Test
    fun `a large file altered in the bucket is never served whole`() {
        val bytes = reading()
        val version = draftVersion()
        upload(version, bytes)
        val descriptor = get("/api/objects/version_recurso/records/$version", creator)["attributes"]["archivo"]
        val altered = altered(bytes)

        putObject(keyOf(descriptor), altered)

        val received = runCatching { fetch(version).responseBody }
        assertTrue(received.isFailure || !altered.contentEquals(received.getOrNull()), "se sirvió entero un archivo alterado")
    }

    private fun altered(bytes: ByteArray): ByteArray = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }

    // una nota HTML de unos cientos de bytes: cabe en un solo bloque de la lectura del almacén
    private fun note(): ByteArray = "<!doctype html><html lang=\"es\"><body><h1>${uniqueName("nota")}</h1><p>Nota breve.</p></body></html>".toByteArray()

    // una lectura HTML de unos 200 KB, distinta en cada prueba
    private fun reading(): ByteArray {
        val html =
            buildString {
                append("<!doctype html><html lang=\"es\"><body><h1>${uniqueName("lectura")}</h1>")
                repeat(2_000) { append("<p>Párrafo $it de la lectura guardada en el almacén S3.</p>") }
                append("</body></html>")
            }
        return html.toByteArray()
    }

    private fun draftVersion(): String {
        val resource = create("recurso", mapOf("titulo" to "Lectura en S3", "tipo" to "HTML", "idioma" to "es"))
        val author = get("/api/auth/me", creator)["userId"].asString()
        return create("version_recurso", mapOf("recurso" to resource, "numero" to 1, "autor_id" to author))
    }

    private fun create(
        obj: String,
        attributes: Map<String, Any?>,
    ): String {
        val body =
            client
                .post()
                .uri("/api/objects/$obj/records")
                .header("Authorization", creator)
                .bodyValue(mapOf("attributes" to attributes))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
        return json.readTree(body)["id"].asString()
    }

    private fun upload(
        version: String,
        bytes: ByteArray,
    ) {
        val parts = MultipartBodyBuilder()
        parts
            .part(
                "file",
                object : ByteArrayResource(bytes) {
                    override fun getFilename() = "lectura.html"
                },
            ).contentType(MediaType.TEXT_HTML)
        client
            .post()
            .uri("/api/objects/version_recurso/records/$version/files/archivo")
            .header("Authorization", creator)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun fetch(version: String): EntityExchangeResult<ByteArray> =
        client
            .get()
            .uri("/api/objects/version_recurso/records/$version/files/archivo")
            .header("Authorization", creator)
            .exchange()
            .expectBody(ByteArray::class.java)
            .returnResult()

    // la clave real del objeto, buscada en el bucket por el id del archivo: ningún id de organización escrito a mano
    private fun keyOf(descriptor: JsonNode): String {
        val fileId = descriptor["id"].asString()
        val request =
            ListObjectsV2Request
                .builder()
                .bucket(BUCKET)
                .prefix("$PREFIX/")
                .build()
        val keys =
            s3
                .listObjectsV2(request)
                .join()
                .contents()
                .map { it.key() }
        return keys.single { it.endsWith("/$fileId") }
    }

    private fun objectBytes(key: String): ByteArray {
        val request =
            GetObjectRequest
                .builder()
                .bucket(BUCKET)
                .key(key)
                .build()
        return s3.getObject(request, AsyncResponseTransformer.toBytes()).join().asByteArray()
    }

    private fun putObject(
        key: String,
        bytes: ByteArray,
    ) {
        val request =
            PutObjectRequest
                .builder()
                .bucket(BUCKET)
                .key(key)
                .contentType(MediaType.TEXT_HTML_VALUE)
                .build()
        s3.putObject(request, AsyncRequestBody.fromBytes(bytes)).join()
    }

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun base64Sha256(bytes: ByteArray): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    companion object {
        private const val BUCKET = "elearning-archivos-it"

        // credenciales solo de esta prueba, en un contenedor efímero
        private const val ACCESS_KEY = "elearning"
        private const val SECRET_KEY = "elearning-integration"

        // el mismo servidor que compose.yaml; puerto aleatorio de Testcontainers
        private val minio: GenericContainer<*> by lazy {
            GenericContainer("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
                .withCommand("server", "/data")
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000))
                .also { it.start() }
        }

        private val endpoint: String by lazy { "http://${minio.host}:${minio.getMappedPort(9000)}" }

        private val s3: S3AsyncClient by lazy {
            S3AsyncClient
                .builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .build()
                .also { it.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build()).join() }
        }

        @JvmStatic
        @DynamicPropertySource
        fun s3Properties(registry: DynamicPropertyRegistry) {
            // s3 primero: el bucket existe antes de que arranque la aplicación
            registry.add("wasichai.files.s3.endpoint") { s3.let { endpoint } }
            registry.add("wasichai.files.s3.bucket") { BUCKET }
            registry.add("wasichai.files.s3.access-key") { ACCESS_KEY }
            registry.add("wasichai.files.s3.secret-key") { SECRET_KEY }
            registry.add("wasichai.files.s3.path-style-access") { "true" }
        }
    }
}
