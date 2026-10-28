package elearning.learning

import elearning.LmsApiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

// la demo del aula contra el servidor real, tal como la deja model/apply.py --demo: el estudiante demo matriculado por el
// comando en la edición abierta de SRTM, con una lectura HTML, una guía PDF y un vídeo, cada uno con su archivo
class DemoClassroomApiTest : LmsApiTest() {
    @Test
    fun `the demo student studies the open SRTM offering with a reading, a pdf and a video`() {
        val student = demoUser("estudiante")
        val enrollment = get("/api/lms/classroom/enrollments", student).values().single { it["offering"]["code"].asString() == "SRTM-ANU-101-A" }
        assertEquals("INSCRIPCION_ABIERTA", enrollment["offering"]["status"].asString())

        val classroom = get("/api/lms/classroom/enrollments/${enrollment["id"].asString()}", student)
        val lessons = classroom["units"].values().flatMap { it["lessons"].values() }
        val resources = lessons.flatMap { lesson -> lesson["resources"].values().map { lesson to it } }

        assertEquals(setOf("HTML", "PDF", "VIDEO"), resources.map { it.second["type"].asString() }.toSet())
        val video = resources.single { it.second["type"].asString() == "VIDEO" }.first
        assertEquals("VIDEO", video["type"].asString())
        assertEquals("VIDEO_END", video["completion"].asString())
        for ((_, resource) in resources) {
            val file = resource["file"]
            val local = Path.of("model/demo/archivos", file["name"].asString())
            val bytes =
                client
                    .get()
                    .uri(file["url"].asString())
                    .header("Authorization", student)
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody(ByteArray::class.java)
                    .returnResult()
                    .responseBody!!
            assertTrue(bytes.contentEquals(Files.readAllBytes(local)), "${file["name"]}: el archivo baja tal como se subió")
        }
    }
}
