package elearning.reputation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// la ventana de ADR-0038 §4 en la zona de la edición, y la muestra mínima con su piso (§9)
class RatingWindowTest {
    private val window = Duration.ofDays(30)

    // 2026-03-31 02:00 UTC: en Lima todavía es 30 de marzo
    private val clock = Clock.fixed(Instant.parse("2026-03-31T02:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `the window closes window-after-end days after the end of the offering, in its time zone`() {
        val end = LocalDate.parse("2026-02-28")
        assertEquals(LocalDate.parse("2026-03-30"), RatingWindow.closesOn(end, window))
        assertTrue(RatingWindow.isOpen(end, "America/Lima", window, clock), "último día en Lima")
        assertFalse(RatingWindow.isOpen(end, "Europe/Madrid", window, clock), "en Madrid ya es el 31")
        assertTrue(RatingWindow.isOpen(LocalDate.parse("2026-04-30"), "America/Lima", window, clock), "antes del fin también")
    }

    @Test
    fun `without an end date or with an unknown zone the window fails closed or uses the default zone`() {
        assertFalse(RatingWindow.isOpen(null, "America/Lima", window, clock))
        assertTrue(RatingWindow.isOpen(LocalDate.parse("2026-02-28"), "Marte/Olympus", window, clock))
    }

    @Test
    fun `a minimum sample below the floor counts as the floor`() {
        assertEquals(3, ReputationProperties(minSample = 2).sample)
        assertEquals(5, ReputationProperties().sample)
        assertEquals(7, ReputationProperties(minSample = 7).sample)
    }

    @Test
    fun `nonsense limits do not start`() {
        assertThrows(IllegalArgumentException::class.java) { ReputationProperties(minProgress = 101) }
        assertThrows(IllegalArgumentException::class.java) { ReputationProperties(maxEditsPerDay = 0) }
        assertThrows(IllegalArgumentException::class.java) { ReputationProperties(releaseAfterEnd = Duration.ofDays(1)) }
    }
}
