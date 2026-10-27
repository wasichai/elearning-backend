package elearning.catalog

import elearning.LmsApiTest
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode

class CatalogDetailApiTest : LmsApiTest() {
    @Autowired
    private lateinit var projection: CatalogProjection

    @Autowired
    private lateinit var db: DatabaseClient

    @BeforeEach
    fun refresh() {
        runBlocking { projection.refreshNow() }
    }

    private fun slugOf(code: String): String =
        json
            .readTree(
                client
                    .get()
                    .uri("/api/lms/public/catalog/courses?size=50")
                    .exchange()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody,
            )["content"]
            .values()
            .first { it["code"].asString() == code }["slug"]
            .asString()

    private fun detail(slug: String): Pair<Int, JsonNode> {
        val result =
            client
                .get()
                .uri("/api/lms/public/catalog/courses/$slug")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return result.status.value() to json.readTree(result.responseBody)
    }

    @Test
    fun `the sheet of a published course has everything the catalog promises`() {
        val (status, ia) = detail(slugOf("IA-AGT-201"))

        assertEquals(200, status)
        assertEquals("IA-AGT-201", ia["code"].asString())
        assertEquals(1, ia["version"]["number"].asInt())
        assertTrue(ia["objectives"].size() > 0 && ia["learningOutcomes"].size() > 0)
        assertEquals(216, ia["workload"]["totalHours"].asInt())
        assertEquals(12, ia["workload"]["weekly"]["theory"].asInt())
        assertEquals("ECTS", ia["credits"]["system"].asString())
        assertTrue(ia["credits"]["approved"].isNull, "nadie los aprobó")
        assertEquals("PROPUESTO", ia["credits"]["proposal"]["status"].asString())
        assertEquals(8, ia["credits"]["proposal"]["proposed"].asInt())
        val orders = ia["units"].values().map { it["order"].asInt() }
        assertEquals(orders.sorted(), orders)
        assertTrue(ia["units"].values().all { it["lessons"].size() > 0 })
        assertEquals("America/Lima", ia["offerings"][0]["timeZone"].asString())
        assertTrue(ia["offerings"][0]["teachers"].size() > 0)
        assertTrue(ia["teachers"].size() > 0)
    }

    @Test
    fun `nothing private reaches the public sheet`() {
        val raw =
            client
                .get()
                .uri("/api/lms/public/catalog/courses/${slugOf("SRTM-ANU-101")}")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!

        assertFalse("usuario_id" in raw || "usuarioId" in raw || "@demo.wasichai-learning.test" in raw, raw)
        assertFalse("BORRADOR" in raw, "la v2 en borrador no se filtra a la ficha publicada")
    }

    @Test
    fun `a course without a credit system shows no credits`() {
        val (_, srtm) = detail(slugOf("SRTM-ANU-101"))

        assertTrue(srtm["credits"]["system"].isNull)
        assertTrue(srtm["credits"]["proposal"].isNull)
    }

    @Test
    fun `a draft, an unknown slug and another organization all get the same 404`() {
        // el curso solo en borrador existe en Wasichai, pero no en la proyección
        val draft =
            json
                .readTree(get("/api/objects/curso/records?codigo=SRTM-ATC-001", bearer()).toString())["content"][0]["attributes"]["slug"]
                .asString()
        // una fila de otra organización, puesta a mano: la ruta pública no la ve aunque el slug exista
        runBlocking {
            db
                .sql(
                    "INSERT INTO lms.catalogo_publicado (organizacion, slug, codigo, titulo, nivel, modalidad, semanas, ficha, hash) " +
                        "VALUES ('otra', 'curso-de-otra-organizacion', 'X', 'X', 'INICIAL', 'ASINCRONA', 1, '{}'::jsonb, 'x') ON CONFLICT DO NOTHING",
                ).fetch()
                .rowsUpdated()
                .awaitSingle()
        }

        for (slug in listOf(draft, "no-existe", "curso-de-otra-organizacion", "MAYUSCULAS")) {
            client
                .get()
                .uri("/api/lms/public/catalog/courses/$slug")
                .exchange()
                .expectStatus()
                .isNotFound
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("Curso no encontrado")
        }
        // un intento de recorrer rutas no llega al controlador: el cortafuegos de Spring Security lo rechaza antes
        client
            .get()
            .uri("/api/lms/public/catalog/courses/..%2F..%2Fetc")
            .exchange()
            .expectStatus()
            .isBadRequest
        // y el refresco la retira: la proyección solo guarda la organización pública
        assertTrue(runBlocking { projection.refreshNow() }.deleted >= 1)
    }
}
