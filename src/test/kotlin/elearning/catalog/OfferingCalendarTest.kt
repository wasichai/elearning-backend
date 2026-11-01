package elearning.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class OfferingCalendarTest {
    private fun at(instant: String) = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)

    private fun offering(
        code: String,
        start: String,
        end: String,
        status: String = "INSCRIPCION_ABIERTA",
        zone: String = "America/Lima",
        from: String? = null,
        until: String? = null,
        full: Boolean = false,
    ) = OfferingView(
        code = code,
        status = status,
        startDate = LocalDate.parse(start),
        endDate = LocalDate.parse(end),
        timeZone = zone,
        enrollmentFrom = from?.let(LocalDate::parse),
        enrollmentUntil = until?.let(LocalDate::parse),
        enrollmentOpen = false,
        capacity = null,
        schedule = null,
        teachers = emptyList(),
        full = full,
    )

    @Test
    fun `finished offerings drop out and the rest come in start order`() {
        val list =
            listOf(
                offering("B", "2026-12-01", "2026-12-20"),
                offering("old", "2026-01-01", "2026-02-01"),
                offering("A", "2026-11-01", "2026-11-30"),
            )

        assertEquals(listOf("A", "B"), OfferingCalendar.current(list, at("2026-10-28T12:00:00Z")).map { it.code })
    }

    @Test
    fun `an offering ending today is still current`() {
        val list = listOf(offering("A", "2026-10-01", "2026-10-28"))

        assertEquals(1, OfferingCalendar.current(list, at("2026-10-28T23:00:00Z")).size, "en Lima aún es 28")
    }

    @Test
    fun `enrollment is open only in its state and within its window, counted in the offering zone`() {
        val window = offering("A", "2026-11-09", "2026-11-27", from = "2026-10-15", until = "2026-11-06")

        assertTrue(OfferingCalendar.current(listOf(window), at("2026-10-28T12:00:00Z")).single().enrollmentOpen)
        // 7 de noviembre 03:00 UTC = 6 de noviembre 22:00 en Lima: todavía abierta
        assertTrue(OfferingCalendar.current(listOf(window), at("2026-11-07T03:00:00Z")).single().enrollmentOpen)
        // 7 de noviembre 06:00 UTC = 7 de noviembre 01:00 en Lima: cerrada
        assertFalse(OfferingCalendar.current(listOf(window), at("2026-11-07T06:00:00Z")).single().enrollmentOpen)
        assertFalse(OfferingCalendar.current(listOf(window), at("2026-10-14T12:00:00Z")).single().enrollmentOpen)
        assertFalse(OfferingCalendar.current(listOf(window.copy(status = "PROGRAMADA")), at("2026-10-28T12:00:00Z")).single().enrollmentOpen)
    }

    @Test
    fun `a zone with daylight saving time uses its own wall clock`() {
        // Nueva York: 1 de noviembre de 2026 vuelve a EST; a las 04:30 UTC allí aún es 31 de octubre
        val closing = offering("NY", "2026-11-10", "2026-11-20", zone = "America/New_York", until = "2026-10-31")

        assertTrue(OfferingCalendar.current(listOf(closing), at("2026-11-01T03:30:00Z")).single().enrollmentOpen)
        // 04:30 UTC: aún rige EDT (UTC-4) hasta las 06:00 UTC, así que allí ya es 1 de noviembre 00:30 y está cerrada.
        // con un desfase fijo de UTC-5 serían las 23:30 del 31 y saldría abierta: este caso distingue el horario de verano
        assertFalse(OfferingCalendar.current(listOf(closing), at("2026-11-01T04:30:00Z")).single().enrollmentOpen)
    }

    // RF-CAT-09: abierta, cerrada o completa. completa es una abierta por estado y ventana a la que no le quedan plazas:
    // no admite matrículas, así que enrollmentOpen dice false
    @Test
    fun `an offering is open, closed or full`() {
        val now = at("2026-10-28T12:00:00Z")
        val open = offering("A", "2026-11-09", "2026-11-27", from = "2026-10-15", until = "2026-11-06")

        fun of(o: OfferingView) = OfferingCalendar.current(listOf(o), now).single()

        assertEquals(EnrollmentStatus.OPEN, of(open).enrollmentStatus)
        assertTrue(of(open).enrollmentOpen)
        assertEquals(EnrollmentStatus.FULL, of(open.copy(full = true)).enrollmentStatus)
        assertFalse(of(open.copy(full = true)).enrollmentOpen)
        assertEquals(EnrollmentStatus.CLOSED, of(open.copy(status = "PROGRAMADA")).enrollmentStatus)
        assertEquals(EnrollmentStatus.CLOSED, of(open.copy(status = "EN_CURSO", full = true)).enrollmentStatus, "sin inscripción, cerrada aunque esté llena")
        assertEquals(EnrollmentStatus.CLOSED, of(open.copy(enrollmentUntil = LocalDate.parse("2026-10-27"), full = true)).enrollmentStatus)
    }
}
