package elearning.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wasichai.core.data.RecordResponse
import java.math.BigDecimal
import java.time.LocalDate

class CatalogBuilderTest {
    private fun record(
        id: String,
        vararg attributes: Pair<String, Any?>,
    ) = RecordResponse(id = id, createdAt = null, updatedAt = null, attributes = mapOf(*attributes))

    private val category = record("cat", "nombre" to "Gestión municipal", "slug" to "gestion-municipal")
    private val course =
        record(
            "c1",
            "codigo" to "SRTM-ANU-101",
            "slug" to "srtm-anuncios",
            "titulo" to "Anuncios en SRTM",
            "resumen" to "Resumen",
            "idioma" to "es",
            "nivel" to "INICIAL",
            "categoria" to "cat",
            "conocimientos_previos" to null,
        )

    private fun version(
        id: String,
        number: Int,
        state: String,
        vararg extra: Pair<String, Any?>,
    ) = record(
        id,
        "curso" to "c1",
        "numero" to number,
        "estado" to state,
        "modalidad" to "ASINCRONA",
        "semanas" to 6,
        "horas_teoria_semana" to BigDecimal("12.00"),
        "horas_practica_semana" to BigDecimal("12.00"),
        "horas_supervisadas_semana" to BigDecimal("0.00"),
        "horas_autonomas_semana" to BigDecimal("12.00"),
        "sistema_credito" to "ECTS",
        "objetivos" to "Primero\n\n  Segundo  \n",
        "contenido_ilustrativo" to true,
        *extra,
    )

    private fun records(vararg versions: RecordResponse) =
        CatalogRecords(
            categories = listOf(category),
            courses = listOf(course),
            versions = versions.toList(),
            units =
                listOf(
                    record("u2", "version_curso" to "v1", "orden" to 2, "titulo" to "Segunda"),
                    record("u1", "version_curso" to "v1", "orden" to 1, "titulo" to "Primera", "horas_estimadas" to BigDecimal("6")),
                ),
            lessons =
                listOf(
                    record("l1", "unidad" to "u1", "orden" to 1, "titulo" to "Lección", "tipo" to "LECTURA", "duracion_minutos" to 30),
                ),
            offerings =
                listOf(
                    record(
                        "e1",
                        "version_curso" to "v1",
                        "codigo" to "ED-1",
                        "estado" to "INSCRIPCION_ABIERTA",
                        "fecha_inicio" to LocalDate.parse("2026-11-09"),
                        "fecha_fin" to "2026-12-04",
                        "zona_horaria" to "America/Lima",
                    ),
                    record(
                        "e2",
                        "version_curso" to "v1",
                        "codigo" to "ED-X",
                        "estado" to "CANCELADA",
                        "fecha_inicio" to "2027-01-01",
                        "fecha_fin" to "2027-02-01",
                    ),
                ),
            profiles =
                listOf(
                    record("p1", "nombre_publico" to "Ana Ejemplo", "usuario_id" to "00000000-0000-0000-0000-000000000099", "perfil_publico" to true),
                ),
            assignments = listOf(record("a1", "edicion_curso" to "e1", "perfil_docente" to "p1", "rol_docente" to "TITULAR")),
        )

    @Test
    fun `only courses with a published version enter the catalog`() {
        assertTrue(CatalogBuilder().build(records(version("v1", 1, "BORRADOR"))).isEmpty())
        assertEquals(1, CatalogBuilder().build(records(version("v1", 1, "PUBLICADA"))).size)
    }

    @Test
    fun `the latest published version wins and drafts are ignored`() {
        val built =
            CatalogBuilder().build(records(version("v1", 1, "PUBLICADA"), version("v2", 2, "PUBLICADA"), version("v3", 3, "BORRADOR"))).single()

        assertEquals(2, built.detail.version.number)
    }

    @Test
    fun `the sheet carries workload, a proposed credit and the curriculum in order`() {
        val detail = CatalogBuilder().build(records(version("v1", 1, "PUBLICADA"))).single().detail

        assertEquals(0, BigDecimal("216").compareTo(detail.workload.totalHours))
        assertEquals("ECTS", detail.credits.system)
        assertNull(detail.credits.approved)
        assertEquals(0, BigDecimal("8").compareTo(detail.credits.proposal!!.proposed))
        assertEquals("PROPUESTO", detail.credits.proposal!!.status)
        assertEquals(listOf("Primera", "Segunda"), detail.units.map { it.title })
        assertEquals(
            listOf("Lección"),
            detail.units
                .first()
                .lessons
                .map { it.title },
        )
        assertEquals(listOf("Primero", "Segundo"), detail.objectives)
        assertEquals("gestion-municipal", detail.category?.slug)
        assertTrue(detail.illustrative)
    }

    @Test
    fun `cancelled offerings stay out and teachers show only their public name`() {
        val built = CatalogBuilder().build(records(version("v1", 1, "PUBLICADA"))).single()

        assertEquals(listOf("ED-1"), built.detail.offerings.map { it.code })
        assertEquals(
            "Ana Ejemplo",
            built.detail.offerings
                .single()
                .teachers
                .single()
                .name,
        )
        assertFalse(built.json().contains("00000000-0000-0000-0000-000000000099"), "el usuario_id no es público")
        assertEquals(listOf(LocalDate.parse("2026-11-09")), built.startDates)
    }

    // privado por defecto (#66): sin consentimiento, ni el nombre del docente sale en el catálogo
    @Test
    fun `a teacher who has not agreed to a public profile is left out of the catalog`() {
        val base = records(version("v1", 1, "PUBLICADA"))
        val hidden =
            base.copy(
                profiles = base.profiles + record("p2", "nombre_publico" to "Bruno Reservado", "biografia" to "Privada"),
                assignments = base.assignments + record("a2", "edicion_curso" to "e1", "perfil_docente" to "p2", "rol_docente" to "ASISTENTE"),
            )

        val built = CatalogBuilder().build(hidden).single()

        assertEquals(
            listOf("Ana Ejemplo"),
            built.detail.offerings
                .single()
                .teachers
                .map { it.name },
        )
        assertEquals(listOf("Ana Ejemplo"), built.detail.teachers.map { it.name })
        assertFalse(built.json().contains("Bruno Reservado") || built.json().contains("Privada"))
    }

    @Test
    fun `no credit system means no proposal`() {
        val detail = CatalogBuilder().build(records(version("v1", 1, "PUBLICADA", "sistema_credito" to "NINGUNO"))).single().detail

        assertNull(detail.credits.system)
        assertNull(detail.credits.proposal)
    }

    @Test
    fun `one broken course is left out and the rest still build`() {
        val other = record("c2", "codigo" to "OTRO", "slug" to "otro", "titulo" to "Otro", "nivel" to "INICIAL")
        val broken = version("v9", 1, "PUBLICADA", "curso" to "c2", "semanas" to 0)
        val rejected = mutableListOf<String>()
        val records = records(version("v1", 1, "PUBLICADA"), broken).let { it.copy(courses = it.courses + other) }

        val built = CatalogBuilder().build(records) { course, _ -> rejected += course }

        assertEquals(listOf("srtm-anuncios"), built.map { it.detail.slug })
        assertEquals(listOf("OTRO"), rejected)
    }

    @Test
    fun `a slug the public route would refuse is left out`() {
        val rejected = mutableListOf<String>()
        val records =
            records(version("v1", 1, "PUBLICADA")).let { r ->
                r.copy(
                    courses =
                        r.courses.map {
                            record(
                                "c1",
                                *(
                                    it.attributes +
                                        ("slug" to "Curso_Básico")
                                ).toList().toTypedArray(),
                            )
                        },
                )
            }

        assertTrue(CatalogBuilder().build(records) { course, _ -> rejected += course }.isEmpty())
        assertEquals(listOf("SRTM-ANU-101"), rejected)
    }

    @Test
    fun `the hash does not depend on the order records arrive in`() {
        val base = records(version("v1", 1, "PUBLICADA"))
        val twoTeachers =
            base.copy(
                profiles = base.profiles + record("p2", "nombre_publico" to "Bruno Ejemplo"),
                assignments = base.assignments + record("a2", "edicion_curso" to "e1", "perfil_docente" to "p2", "rol_docente" to "TITULAR"),
            )
        val shuffled =
            twoTeachers.copy(
                units = twoTeachers.units.reversed(),
                assignments = twoTeachers.assignments.reversed(),
                profiles = twoTeachers.profiles.reversed(),
            )

        assertEquals(CatalogBuilder().build(twoTeachers).single().hash, CatalogBuilder().build(shuffled).single().hash)
    }

    @Test
    fun `the hash changes with the content and only with it`() {
        val a = CatalogBuilder().build(records(version("v1", 1, "PUBLICADA"))).single()
        val b = CatalogBuilder().build(records(version("v1", 1, "PUBLICADA"))).single()
        val c = CatalogBuilder().build(records(version("v1", 1, "PUBLICADA", "requisitos" to "Otro"))).single()

        assertEquals(a.hash, b.hash)
        assertTrue(a.hash != c.hash)
    }

    // RF-CAT-09: la ficha dice si una edición está completa, sin publicar cuántos ni quiénes se matricularon
    @Test
    fun `an offering is full when its enrollments reach its capacity, never without a capacity`() {
        fun full(
            capacity: Int?,
            taken: Int?,
        ): Boolean {
            val base = records(version("v1", 1, "PUBLICADA"))
            val offerings = base.offerings.map { if (it.id == "e1") it.copy(attributes = it.attributes + ("capacidad" to capacity)) else it }
            val occupancy = if (taken == null) emptyMap() else mapOf("e1" to taken)
            val built = CatalogBuilder().build(base.copy(offerings = offerings, occupancy = occupancy)).single()
            return built.detail.offerings
                .single()
                .full
        }

        assertTrue(full(capacity = 2, taken = 2))
        assertTrue(full(capacity = 2, taken = 3), "un cupo reducido por debajo de lo ocupado también está completo")
        assertFalse(full(capacity = 2, taken = 1))
        assertFalse(full(capacity = 2, taken = null), "nadie matriculado todavía")
        assertFalse(full(capacity = null, taken = 500), "sin capacidad no hay límite")
    }

    @Test
    fun `the occupancy changes the hash only when the offering fills up`() {
        val base = records(version("v1", 1, "PUBLICADA"))
        val offerings = base.offerings.map { if (it.id == "e1") it.copy(attributes = it.attributes + ("capacidad" to 3)) else it }

        fun hash(taken: Int) = CatalogBuilder().build(base.copy(offerings = offerings, occupancy = mapOf("e1" to taken))).single().hash

        assertEquals(hash(1), hash(2), "una matrícula más que no agota el cupo no reescribe la proyección")
        assertFalse(hash(2) == hash(3))
    }

    // RF-CAT-08 (ADR-0028): las claves que declaró la versión publicada, sin repetir, con el nombre del vocabulario
    @Test
    fun `the sheet carries the prerequisite knowledge keys the published version declares`() {
        val vocabulary =
            listOf(
                record("k1", "clave" to "hoja-de-calculo", "nombre" to "Hoja de cálculo"),
                record("k2", "clave" to "navegador-web", "nombre" to "Navegador web"),
            )
        val declared = version("v1", 1, "PUBLICADA", "conocimientos_previos_claves" to "navegador-web\n\n hoja-de-calculo \nnavegador-web\nsin-nombre")
        val built = CatalogBuilder().build(records(declared).copy(knowledge = vocabulary)).single().detail

        assertEquals(
            listOf(
                KnowledgeTag("navegador-web", "Navegador web"),
                KnowledgeTag("hoja-de-calculo", "Hoja de cálculo"),
                KnowledgeTag("sin-nombre", "sin-nombre"),
            ),
            built.prerequisiteKnowledgeTags,
        )
        assertEquals(emptyList<KnowledgeTag>(), CatalogBuilder().build(records(version("v1", 1, "PUBLICADA"))).single().detail.prerequisiteKnowledgeTags)
    }
}
