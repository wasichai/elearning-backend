package elearning.importer

import elearning.jobs.JobRunner
import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import java.util.UUID

// la importación como borrador con informe (ADR-0018 §5 y §6, RF-IMP-01…05) con un SourceFetcher falso y fragmentos
// AsciiDoc escritos para esta prueba: nunca red real ni los manuales. la cola no corre sola: cada prueba da las vueltas
@Import(ImportApiTest.FakeSource::class)
@TestPropertySource(properties = ["lms.jobs.scheduling=false", "lms.importer.allowed-sources=acme/manuales"])
class ImportApiTest : ClassroomApiTestSupport() {
    @Autowired
    private lateinit var runner: JobRunner

    private val creator by lazy { demoUser("creador") }

    private fun courseId() = get("/api/objects/curso/records?codigo=${course().offering}", admin)["content"][0]["id"].asString()

    private fun start(
        token: String?,
        source: String,
        courseId: String,
    ) = call(token, "/api/lms/studio/import", mapOf("source" to source, "courseId" to courseId))

    private fun drain() = runBlocking { while (runner.runOnce() > 0) Unit }

    private fun imported(
        source: String = "acme/manuales@$SHA:manual.adoc",
        courseId: String = courseId(),
    ): JsonNode {
        val (status, job) = start(creator, source, courseId)
        assertEquals(HttpStatus.ACCEPTED, status, job.toString())
        assertEquals("PENDIENTE", job!!["status"].asString())
        drain()
        return ok(call(creator, "/api/lms/studio/import/${job["id"].asString()}"))
    }

    private fun JsonNode.lacks(name: String) = this[name] == null || this[name].isNull

    private fun records(
        obj: String,
        field: String,
        value: String,
    ): List<JsonNode> = get("/api/objects/$obj/records?$field=$value&size=200", admin)["content"].values().toList()

    @Test
    fun `it proposes a draft of the author with a unit per chapter and a lesson per section, traced and without the body`() {
        val course = courseId()
        val job = imported(courseId = course)

        assertEquals("HECHO", job["status"].asString(), job.toString())
        val versionId = job["versionId"].asString()
        val version = get("/api/objects/version_curso/records/$versionId", admin)["attributes"]
        assertEquals(userId(creator), version["autor_id"].asString())
        assertEquals("BORRADOR", version["estado"].asString())
        assertEquals(2, version["numero"].asInt())
        assertTrue(version.lacks("publicada_en"))

        val detail = ok(call(creator, "/api/lms/studio/versions/$versionId"))
        assertEquals(listOf("Instalación", "Configuración"), detail["units"].values().map { it["title"].asString() })
        val install = detail["units"][0]["lessons"].values().toList()
        assertEquals(listOf("Requisitos previos", "Pasos"), install.map { it["title"].asString() })
        assertEquals("acme/manuales@$SHA:capitulos/uno.adoc#_requisitos_previos", install[0]["sourceRef"].asString())
        assertEquals("acme/manuales@$SHA:capitulos/dos.adoc#ajustes", detail["units"][1]["lessons"][0]["sourceRef"].asString())

        val report = job["report"]
        assertEquals("acme/manuales@$SHA:manual.adoc", report["source"].asString())
        assertEquals(3, report["files"].asInt())
        assertEquals(2, report["units"].asInt())
        assertEquals(3, report["lessons"].asInt())
        val problems = report["problems"].values().map { it["kind"].asString() to it["detail"].asString() }
        assertTrue("MISSING_INCLUDE" to "capitulos/falta.adoc" in problems, problems.toString())
        assertTrue("MISSING_IMAGE" to "captura.png" in problems, problems.toString())

        // el cuerpo del manual no está en el borrador ni en el informe
        val written =
            listOf(version.toString(), job.toString()) +
                records("unidad", "version_curso", versionId).flatMap { unit ->
                    listOf(unit.toString()) + records("leccion", "unidad", unit["id"].asString()).map { it.toString() }
                }
        assertTrue(written.none { BODY in it }, written.toString())
        assertTrue(versionId in ok(call(creator, "/api/lms/studio/versions")).values().map { it["id"].asString() })
    }

    @Test
    fun `a cycle is reported and the import still ends with its draft`() {
        val job = imported(source = "acme/manuales@$SHA:ciclo/raiz.adoc")

        assertEquals("HECHO", job["status"].asString(), job.toString())
        assertNotNull(job["versionId"].asString())
        val kinds = job["report"]["problems"].values().map { it["kind"].asString() }
        assertEquals(listOf("INCLUDE_CYCLE"), kinds)
    }

    @Test
    fun `a master that does not exist fails without a draft`() {
        val course = courseId()
        val before = records("version_curso", "curso", course).size

        val job = imported(source = "acme/manuales@$SHA:no-existe.adoc", courseId = course)

        assertEquals("FALLIDO", job["status"].asString(), job.toString())
        assertTrue(job["error"].asString().contains("no-existe.adoc"), job.toString())
        assertTrue(job.lacks("versionId"))
        assertEquals(before, records("version_curso", "curso", course).size)
    }

    @Test
    fun `only an allowed and well formed source of an existing course is imported`() {
        val course = courseId()

        assertEquals(HttpStatus.FORBIDDEN, start(creator, "otra/manuales@$SHA:manual.adoc", course).first)
        assertEquals(HttpStatus.BAD_REQUEST, start(creator, "acme/manuales:manual.adoc", course).first)
        assertEquals(HttpStatus.BAD_REQUEST, start(creator, "acme/manuales@$SHA:../fuera.adoc", course).first)
        assertEquals(HttpStatus.BAD_REQUEST, start(creator, "acme/manuales@$SHA:manual.adoc", "no-es-un-id").first)
        assertEquals(HttpStatus.NOT_FOUND, start(creator, "acme/manuales@$SHA:manual.adoc", UUID.randomUUID().toString()).first)

        val sources = ok(call(creator, "/api/lms/studio/import/sources"))
        assertEquals(listOf("acme/manuales"), sources.values().map { it["repository"].asString() })
        assertTrue(sources[0]["sha"].isNull)
    }

    @Test
    fun `only who creates versions imports, and nobody else reads the job or its report`() {
        val course = courseId()
        val (_, job) = start(creator, "acme/manuales@$SHA:manual.adoc", course)
        val path = "/api/lms/studio/import/${job!!["id"].asString()}"
        drain()

        assertEquals(HttpStatus.FORBIDDEN, start(newStudent(), "acme/manuales@$SHA:manual.adoc", course).first)
        assertEquals(HttpStatus.FORBIDDEN, start(demoUser("revisor"), "acme/manuales@$SHA:manual.adoc", course).first)
        assertEquals(HttpStatus.FORBIDDEN, call(newStudent(), "/api/lms/studio/import/sources").first)
        assertEquals(HttpStatus.UNAUTHORIZED, start(null, "acme/manuales@$SHA:manual.adoc", course).first)

        assertEquals(HttpStatus.OK, call(creator, path).first)
        for (other in listOf(newStudent(), demoUser("revisor"), admin)) {
            val (status, body) = call(other, path)
            assertEquals(HttpStatus.NOT_FOUND, status, body.toString())
            assertFalse(body.toString().contains("acme/manuales"), body.toString())
        }
        assertEquals(HttpStatus.NOT_FOUND, call(creator, "/api/lms/studio/import/${UUID.randomUUID()}").first)
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, path).first)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FakeSource {
        @Bean
        @Primary
        fun fakeSourceFetcher(): SourceFetcher =
            SourceFetcher { ref ->
                val text = if (ref.repository == "acme/manuales" && ref.sha == SHA) FILES[ref.path] else null
                if (text == null) {
                    Mono.error(SourceFetchException(SourceFetchException.Reason.NOT_FOUND, "$ref not found"))
                } else {
                    Mono.just(text.toByteArray())
                }
            }
    }

    companion object {
        const val SHA = "0123456789abcdef0123456789abcdef01234567"
        const val BODY = "TEXTO-PRIVADO-DEL-MANUAL"

        val FILES =
            mapOf(
                "manual.adoc" to
                    """
                    = Manual de prueba
                    :toc:

                    Introducción con $BODY.

                    include::capitulos/uno.adoc[]

                    include::capitulos/dos.adoc[leveloffset=+1]

                    include::capitulos/falta.adoc[]
                    """.trimIndent(),
                "capitulos/uno.adoc" to
                    """
                    [[instalar]]
                    == Instalación

                    Este párrafo es $BODY y no se copia.

                    === Requisitos previos

                    $BODY otra vez.

                    === Pasos

                    image::captura.png[Captura]
                    """.trimIndent(),
                "capitulos/dos.adoc" to
                    """
                    = Configuración

                    [[ajustes]]
                    == Ajustes

                    Ver <<instalar>>. $BODY.
                    """.trimIndent(),
                "ciclo/raiz.adoc" to "= Ciclo\n\n== Uno\n\ninclude::otro.adoc[]\n",
                "ciclo/otro.adoc" to "== Otro\n\ninclude::raiz.adoc[]\n",
            )
    }
}
