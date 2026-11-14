package elearning.catalog

import elearning.LmsApiTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// la portada pública (ADR-0046): la institución destaca cursos con curso_destacado y el catálogo anónimo los ofrece con
// featured=true, en su orden. un borrador destacado no sale, y ningún rol del LMS puede destacar
class CatalogFeaturedApiTest : LmsApiTest() {
    @Autowired
    private lateinit var projection: CatalogProjection

    private val admin by lazy { bearer() }

    private fun list(query: String): JsonNode =
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

    private fun courseId(code: String): String = get("/api/objects/curso/records?codigo=$code", admin)["content"][0]["id"].asString()

    private fun mark(
        token: String,
        course: String,
        order: Int,
    ): Pair<HttpStatus, JsonNode?> {
        val result =
            client
                .post()
                .uri("/api/objects/curso_destacado/records")
                .header("Authorization", token)
                .bodyValue(mapOf("attributes" to mapOf("curso" to course, "orden" to order)))
                .exchange()
                .returnResult(String::class.java)
        val body =
            result.responseBody
                .collectList()
                .block()
                ?.joinToString("")
        return HttpStatus.valueOf(result.status.value()) to body?.takeIf { it.isNotBlank() }?.let { json.readTree(it) }
    }

    private fun unmark(id: String) {
        client
            .delete()
            .uri("/api/objects/curso_destacado/records/$id")
            .header("Authorization", admin)
            .exchange()
            .expectStatus()
            .is2xxSuccessful
    }

    private fun marks(): Int = get("/api/objects/curso_destacado/records?size=200", admin)["totalElements"].asInt()

    private fun JsonNode.codes(): List<String> = this["content"].values().map { it["code"].asString() }

    @Test
    fun `the featured courses come first in their order, and only the published ones`() {
        val (s1, ia) = mark(admin, courseId("IA-AGT-201"), 2)
        val (s2, srtm) = mark(admin, courseId("SRTM-ANU-101"), 1)
        // SRTM-ATC-001 solo tiene borrador: destacarlo no lo publica
        val (s3, draft) = mark(admin, courseId("SRTM-ATC-001"), 0)
        assertEquals(listOf(HttpStatus.CREATED, HttpStatus.CREATED, HttpStatus.CREATED), listOf(s1, s2, s3))
        try {
            runBlocking { projection.refreshNow() }

            val featured = list("?featured=true")
            assertEquals(listOf("SRTM-ANU-101", "IA-AGT-201"), featured.codes())
            assertTrue(featured["content"].values().all { it["featured"].asBoolean() })
            assertFalse(list("?featured=false").codes().any { it in setOf("SRTM-ANU-101", "IA-AGT-201", "SRTM-ATC-001") })
            assertTrue(list("?q=srtm")["content"].values().single()["featured"].asBoolean(), "el listado completo dice cuál es destacado")
            val detail = list("?featured=true&size=1")
            assertEquals(listOf("SRTM-ANU-101"), detail.codes())
        } finally {
            listOf(ia, srtm, draft).forEach { unmark(it!!["id"].asString()) }
            runBlocking { projection.refreshNow() }
        }
        assertTrue(list("?featured=true").codes().none { it in setOf("SRTM-ANU-101", "IA-AGT-201") }, "quitar la marca la saca del banner")
    }

    @Test
    fun `no LMS role features a course, and nothing changes`() {
        val before = marks()
        val course = courseId("SGSPE-101")

        for (role in listOf("creador", "revisor", "docente", "estudiante")) {
            val (status, _) = mark(demoUser(role), course, 0)
            assertEquals(HttpStatus.FORBIDDEN, status, role)
        }
        assertEquals(before, marks())
    }

    @Test
    fun `an anonymous visitor cannot feature a course`() {
        client
            .post()
            .uri("/api/objects/curso_destacado/records")
            .bodyValue(mapOf("attributes" to mapOf("curso" to courseId("SGSPE-101"), "orden" to 0)))
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `featured must be true or false`() {
        client
            .get()
            .uri("/api/lms/public/catalog/courses?featured=si")
            .exchange()
            .expectStatus()
            .isBadRequest
    }
}
