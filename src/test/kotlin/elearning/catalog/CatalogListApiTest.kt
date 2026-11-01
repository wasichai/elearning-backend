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

        assertEquals(setOf("SRTM-ANU-101", "SGSPE-101", "IA-AGT-201", "CORTO-101", "TRAMITE-101"), page.codes().toSet())
        assertEquals(5, page["totalElements"].asInt())
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

    // RF-CAT-08. la demo: SRTM 3 semanas (6 h de teoría, 6 de práctica, 6 autónomas), SGSPE 2 (6, 6, 4), IA 6 (72, 72, 72) y
    // CORTO y TRAMITE 1 (1, 1, 1). otras clases crean cursos de prueba en la misma base: se mira solo lo que es de la demo
    private fun demo(query: String): Set<String> = list(query).codes().filter { it in DEMO }.toSet()

    @Test
    fun `duration filters by weeks, inclusive on both ends`() {
        assertEquals(setOf("SRTM-ANU-101", "IA-AGT-201"), demo("?minWeeks=3"))
        assertEquals(setOf("CORTO-101", "SGSPE-101", "TRAMITE-101"), demo("?maxWeeks=2"))
        assertEquals(setOf("SGSPE-101", "SRTM-ANU-101"), demo("?minWeeks=2&maxWeeks=3"))
        assertEquals(emptySet<String>(), demo("?minWeeks=12"), "ningún curso de la demo es semestral")
    }

    @Test
    fun `enrollment open keeps the courses with an offering open today, and false the rest`() {
        // SRTM, CORTO y TRAMITE tienen una edición INSCRIPCION_ABIERTA cuya ventana incluye hoy; SGSPE e IA solo programadas
        assertEquals(setOf("SRTM-ANU-101", "CORTO-101", "TRAMITE-101"), demo("?enrollmentOpen=true"))
        assertEquals(setOf("SGSPE-101", "IA-AGT-201"), demo("?enrollmentOpen=false"))
    }

    @Test
    fun `an offering whose enrollment window is over does not count as open`() {
        // la ventana de SRTM cerró ayer: el estado sigue INSCRIPCION_ABIERTA, pero la ficha diría enrollmentOpen=false
        runBlocking {
            db
                .sql(
                    """
                    UPDATE lms.catalogo_publicado
                    SET ficha = jsonb_set(ficha, '{offerings}', (
                        SELECT jsonb_agg(jsonb_set(o, '{enrollmentUntil}', to_jsonb((CURRENT_DATE - 3)::text))) FROM jsonb_array_elements(ficha->'offerings') o
                    )), hash = 'x'
                    WHERE codigo = 'SRTM-ANU-101'
                    """.trimIndent(),
                ).fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        try {
            assertEquals(setOf("CORTO-101", "TRAMITE-101"), demo("?enrollmentOpen=true"))
        } finally {
            runBlocking { projection.refreshNow() }
        }
    }

    @Test
    fun `hours by type filter by the total hours of each type`() {
        assertEquals(setOf("SRTM-ANU-101", "SGSPE-101", "IA-AGT-201"), demo("?minTheoryHours=6"))
        assertEquals(setOf("IA-AGT-201"), demo("?minPracticeHours=6.5"))
        assertEquals(setOf("SRTM-ANU-101", "IA-AGT-201"), demo("?minAutonomousHours=5"))
        assertEquals(setOf("IA-AGT-201"), demo("?minTheoryHours=6&minAutonomousHours=72"))
    }

    @Test
    fun `the new filters combine with the others and narrow the facets`() {
        assertEquals(setOf("CORTO-101"), demo("?modality=HIBRIDA&enrollmentOpen=true&maxWeeks=4"))
        // con 10 h de práctica o más solo queda IA: su categoría es la única del filtro de categorías
        val ia = list()["content"].values().first { it["code"].asString() == "IA-AGT-201" }["category"]["slug"].asString()
        assertEquals(setOf(ia), list("?minPracticeHours=10")["facets"]["categories"].values().map { it["slug"].asString() }.toSet())
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
        assertEquals(3, second["totalPages"].asInt())
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
            "?minWeeks=0" to "minWeeks",
            "?maxWeeks=27" to "maxWeeks",
            "?maxWeeks=dos" to "maxWeeks",
            "?minWeeks=4&maxWeeks=2" to "maxWeeks",
            "?enrollmentOpen=yes" to "enrollmentOpen",
            "?minTheoryHours=-1" to "minTheoryHours",
            "?minPracticeHours=1e3" to "minPracticeHours",
            "?minAutonomousHours=10001" to "minAutonomousHours",
            "?minAutonomousHours=1.255" to "minAutonomousHours",
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
        assertEquals(5, list()["totalElements"].asInt())
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
        assertEquals(5, again.published)
    }

    companion object {
        private val DEMO = setOf("SRTM-ANU-101", "SGSPE-101", "IA-AGT-201", "CORTO-101", "TRAMITE-101")
    }
}
