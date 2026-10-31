package elearning.authoring

import elearning.authoring.VersionComparison.ADDED
import elearning.authoring.VersionComparison.CHANGED
import elearning.authoring.VersionComparison.REMOVED
import elearning.authoring.VersionComparison.SAME
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

// la comparación por identidad estable (ADR-0018 §3): orden de unidad y de lección
class VersionComparisonTest {
    private fun lesson(
        order: Int,
        title: String = "Lección $order",
        vararg values: Pair<String, Any?>,
    ) = LessonSnapshot(order, title, mapOf("titulo" to title) + values)

    private fun unit(
        order: Int,
        vararg lessons: LessonSnapshot,
        title: String = "Unidad $order",
    ) = UnitSnapshot(order, title, mapOf("titulo" to title), lessons.toList())

    @Test
    fun `without a previous version everything is added`() {
        val diff = VersionComparison.compare(null, listOf(unit(1, lesson(1), lesson(2))))

        assertEquals(ADDED, diff.single().change)
        assertEquals(listOf(ADDED, ADDED), diff.single().lessons.map { it.change })
        assertNull(diff.single().previousTitle)
    }

    @Test
    fun `the same content is the same, even when empty and absent or 2 and 2_00 meet`() {
        val before = listOf(unit(1, lesson(1, "A", "resumen" to null, "duracion_minutos" to 2)))
        val after = listOf(unit(1, lesson(1, "A", "resumen" to "", "duracion_minutos" to BigDecimal("2.00"))))

        val diff = VersionComparison.compare(before, after).single()

        assertEquals(SAME, diff.change)
        assertEquals(SAME, diff.lessons.single().change)
        assertEquals(emptyList<String>(), diff.lessons.single().fields)
    }

    @Test
    fun `lessons and units are matched by order and report what changed`() {
        val before = listOf(unit(1, lesson(1, "Intro", "resumen" to "viejo"), lesson(2)), unit(3, lesson(1)))
        val after = listOf(unit(1, lesson(1, "Introducción", "resumen" to "nuevo"), lesson(3)), unit(2, lesson(1), title = "Nueva"))

        val diff = VersionComparison.compare(before, after)

        assertEquals(listOf(1 to CHANGED, 2 to ADDED, 3 to REMOVED), diff.map { it.order to it.change })
        val first = diff[0].lessons
        assertEquals(listOf(1 to CHANGED, 2 to REMOVED, 3 to ADDED), first.map { it.order to it.change })
        assertEquals(listOf("resumen", "titulo"), first[0].fields)
        assertEquals("Introducción", first[0].title)
        assertEquals("Intro", first[0].previousTitle)
        assertNull(first[1].title)
        assertEquals("Lección 2", first[1].previousTitle)
        assertEquals("Nueva", diff[1].title)
        assertEquals(listOf(ADDED), diff[1].lessons.map { it.change })
        assertEquals(listOf(REMOVED), diff[2].lessons.map { it.change })
        assertEquals("Unidad 3", diff[2].previousTitle)
    }

    @Test
    fun `a unit whose own fields change is changed even if its lessons are not`() {
        val diff = VersionComparison.compare(listOf(unit(1, lesson(1))), listOf(unit(1, lesson(1), title = "Otra"))).single()

        assertEquals(CHANGED, diff.change)
        assertEquals(listOf("titulo"), diff.fields)
        assertEquals(SAME, diff.lessons.single().change)
    }
}
