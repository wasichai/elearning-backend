package elearning.catalog

import elearning.LmsApiTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode

// el listado público lee la proyección (ADR-0011). los datos son los de model/demo, aplicados por LmsApiTest;
// la organización pública es la semilla «demo» de Wasichai
class CatalogListApiTest : LmsApiTest() {
    @Autowired
    private lateinit var projection: CatalogProjection

    @BeforeEach
    fun refresh() {
        runBlocking { projection.refreshNow() }
    }

    private fun list(query: String = ""): JsonNode =
        json.readTree(
            client
                .get()
                .uri("/api/lms/public/catalog/courses$query")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody,
        )

    private fun JsonNode.codes(): List<String> = this["content"].values().map { it["code"].asString() }

    @Test
    fun `anyone sees the published courses and nothing else`() {
        val page = list()

        assertEquals(setOf("SRTM-ANU-101", "SGSPE-101", "IA-AGT-201"), page.codes().toSet())
        assertEquals(3, page["totalElements"].asInt())
        // SRTM-ATC-001 solo tiene borrador; la v2 en borrador de SRTM-ANU-101 no cambia lo publicado
        assertTrue("SRTM-ATC-001" !in page.codes())
    }

    @Test
    fun `a summary carries workload, next offering and the illustrative mark`() {
        val ia = list()["content"].values().first { it["code"].asString() == "IA-AGT-201" }

        assertEquals(6, ia["weeks"].asInt())
        assertEquals(216, ia["workload"]["totalHours"].asInt())
        assertEquals(144, ia["workload"]["contactHours"].asInt())
        assertNotNull(ia["nextOffering"]["startDate"].asString())
        assertEquals("America/Lima", ia["nextOffering"]["timeZone"].asString())
        assertTrue(ia["illustrative"].asBoolean())
        assertTrue(ia["slug"].asString().isNotBlank())
    }

    @Test
    fun `filters narrow the list`() {
        assertEquals(listOf("IA-AGT-201"), list("?modality=HIBRIDA").codes())
        assertEquals(listOf("SRTM-ANU-101"), list("?q=srtm").codes(), "el borrador SRTM-ATC-001 no aparece aunque coincida")
        assertTrue(list("?q=SRTM").codes().isNotEmpty())
        assertEquals(listOf("IA-AGT-201"), list("?startsFrom=2027-03-15").codes())
        assertEquals(0, list("?q=no-existe-ningun-curso-asi")["totalElements"].asInt())
    }

    @Test
    fun `category facets count the published courses regardless of the category filter`() {
        val all = list()
        val first = all["facets"]["categories"].values().first()
        val filtered = list("?category=${first["slug"].asString()}")

        assertEquals(first["count"].asInt(), filtered["totalElements"].asInt())
        assertEquals(all["facets"]["categories"].size(), filtered["facets"]["categories"].size())
    }

    @Test
    fun `sorting and paging`() {
        val byTitle = list("?sort=title")["content"].values().map { it["title"].asString() }
        assertEquals(byTitle.sortedWith(String.CASE_INSENSITIVE_ORDER), byTitle)

        val byDuration = list("?sort=duration")["content"].values().map { it["weeks"].asInt() }
        assertEquals(byDuration.sorted(), byDuration)

        val upcoming = list("?sort=upcoming").codes()
        assertEquals("SRTM-ANU-101", upcoming.first(), "la edición de noviembre de 2026 es la más próxima")

        val second = list("?sort=title&size=2&page=1")
        assertEquals(1, second["content"].size())
        assertEquals(2, second["totalPages"].asInt())
    }

    @Test
    fun `invalid parameters are a 400 naming the field`() {
        for ((query, field) in listOf(
            "?size=51" to "size",
            "?size=0" to "size",
            "?level=EXPERTO" to "level",
            "?startsFrom=mañana" to "startsFrom",
            "?sort=azar" to "sort",
            "?page=-1" to "page",
        )) {
            client
                .get()
                .uri("/api/lms/public/catalog/courses$query")
                .exchange()
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.errors[0].field")
                .isEqualTo(field)
        }
    }

    @Test
    fun `a refresh without changes writes nothing`() {
        val again = runBlocking { projection.refreshNow() }

        assertEquals(0, again.written)
        assertEquals(0, again.deleted)
        assertEquals(3, again.published)
    }
}
