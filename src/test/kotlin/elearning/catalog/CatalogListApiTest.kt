package elearning.catalog

import elearning.LmsApiTest
import elearning.common.LmsProperties
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordService
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.TenantDirectory
import java.time.LocalDate
import java.time.ZoneId

// el listado público lee la proyección (ADR-0011). los datos son los de model/demo, aplicados por LmsApiTest;
// la organización pública es la semilla «demo» de Wasichai
class CatalogListApiTest : LmsApiTest() {
    @Autowired
    private lateinit var projection: CatalogProjection

    @Autowired private lateinit var db: DatabaseClient

    @Autowired private lateinit var tenants: TenantDirectory

    @Autowired private lateinit var records: RecordService

    @Autowired private lateinit var source: CatalogSource

    @Autowired private lateinit var repository: CatalogRepository

    @Autowired private lateinit var clusterLock: ClusterLock

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

        assertEquals(setOf("SRTM-ANU-101", "SGSPE-101", "IA-AGT-201", "CORTO-101"), page.codes().toSet())
        assertEquals(4, page["totalElements"].asInt())
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
        assertEquals(setOf("IA-AGT-201", "CORTO-101"), list("?modality=HIBRIDA").codes().toSet())
        assertEquals(listOf("SRTM-ANU-101"), list("?q=srtm").codes(), "el borrador SRTM-ATC-001 no aparece aunque coincida")
        assertTrue(list("?q=SRTM").codes().isNotEmpty())
        // la demo fecha sus ediciones respecto del día en que se aplica: IA empieza en hoy+159, la segunda de SRTM en hoy+124
        assertEquals(listOf("IA-AGT-201"), list("?startsFrom=${LocalDate.now(ZoneId.of("America/Lima")).plusDays(138)}").codes())
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
        assertEquals("SRTM-ANU-101", upcoming.first(), "la edición abierta de SRTM (hoy+12) es la más próxima")

        val second = list("?sort=title&size=2&page=1")
        assertEquals(2, second["content"].size())
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
    fun `an ongoing offering counts as the next one when sorting by upcoming`() {
        // el curso de IA pasa a tener una edición que empezó en enero y sigue en curso: debe ir primero
        runBlocking {
            db
                .sql("UPDATE lms.catalogo_publicado SET inicios = '{2026-01-05}', fines = '{2099-01-01}', hash = 'x' WHERE codigo = 'IA-AGT-201'")
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        try {
            assertEquals("IA-AGT-201", list("?sort=upcoming").codes().first())
        } finally {
            runBlocking { projection.refreshNow() }
        }
    }

    @Test
    fun `when the public organization cannot be resolved the catalog is emptied, not left stale`() {
        val lost =
            CatalogProjection(LmsProperties(public = LmsProperties.Public("organizacion-que-no-existe")), tenants, records, source, repository, clusterLock)
        try {
            runBlocking { lost.refreshNow() }
            assertEquals(0, list()["totalElements"].asInt())
        } finally {
            runBlocking { projection.refreshNow() }
        }
        assertEquals(4, list()["totalElements"].asInt())
    }

    // un NUL llegaba a PostgreSQL y salía como 500 anónimo (revisión de seguridad). uri(String) volvería a codificar
    // el %, así que el carácter real se pasa por el UriBuilder
    @Test
    fun `control characters in the search are a 400, not a 500`() {
        for (q in listOf("\u0000", "a\nb", "\u001b[31m")) {
            client
                .get()
                .uri { it.path("/api/lms/public/catalog/courses").queryParam("q", q).build() }
                .exchange()
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.errors[0].field")
                .isEqualTo("q")
        }
    }

    // la proyección ya va hasta lms.catalog.refresh por detrás: un proxy o el navegador pueden guardar la respuesta ese
    // tiempo (#68). un error no se guarda como público
    @Test
    fun `the public listing and course sheets can be cached as long as the projection lags`() {
        client
            .get()
            .uri("/api/lms/public/catalog/courses")
            .exchange()
            .expectHeader()
            .valueEquals("Cache-Control", "max-age=30, public")
        val slug = list()["content"][0]["slug"].asString()
        client
            .get()
            .uri("/api/lms/public/catalog/courses/$slug")
            .exchange()
            .expectHeader()
            .valueEquals("Cache-Control", "max-age=30, public")
        client
            .get()
            .uri("/api/lms/public/catalog/courses?size=0")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectHeader()
            .value("Cache-Control") { assertTrue("public" !in it, it) }
    }

    @Test
    fun `a refresh without changes writes nothing`() {
        val again = runBlocking { projection.refreshNow() }

        assertEquals(0, again.written)
        assertEquals(0, again.deleted)
        assertEquals(4, again.published)
    }
}
