package elearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class SessionRecurrenceTest {
    private val eight = LocalTime.of(8, 0)
    private val ny = ZoneId.of("America/New_York")

    @Test
    fun `the local time holds across a daylight saving change`() {
        // en 2026 Nueva York cambia el 1 de noviembre: de UTC-4 a UTC-5
        val all = SessionRecurrence.occurrences("DAILY", emptyList(), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 2), eight, ny)

        assertEquals(listOf(eight, eight, eight), all.map { it.atZone(ny).toLocalTime() })
        assertEquals(listOf(12, 13, 13), all.map { it.atOffset(ZoneOffset.UTC).hour })
    }

    @Test
    fun `each pattern picks its days`() {
        val monday = LocalDate.of(2026, 11, 9)
        val lima = ZoneId.of("America/Lima")

        fun days(
            pattern: String,
            weekdays: List<DayOfWeek> = emptyList(),
        ) = SessionRecurrence.occurrences(pattern, weekdays, monday, monday.plusDays(6), eight, lima).map { it.atZone(lima).dayOfMonth }

        assertEquals(listOf(9), SessionRecurrence.occurrences("ONCE", emptyList(), monday, monday, eight, lima).map { it.atZone(lima).dayOfMonth })
        assertEquals((9..15).toList(), days("DAILY"))
        assertEquals(listOf(9, 11, 13, 15), days("ALTERNATE_DAYS"))
        assertEquals(listOf(9, 11), days("WEEKDAYS", listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)))
    }

    @Test
    fun `nonsense is refused`() {
        val day = LocalDate.of(2026, 11, 9)
        val lima = ZoneId.of("America/Lima")

        assertThrows<IllegalArgumentException> { SessionRecurrence.occurrences("DAILY", emptyList(), day, day.minusDays(1), eight, lima) }
        assertThrows<IllegalArgumentException> { SessionRecurrence.occurrences("WEEKDAYS", emptyList(), day, day, eight, lima) }
        assertThrows<IllegalArgumentException> { SessionRecurrence.occurrences("MOONLY", emptyList(), day, day, eight, lima) }
        assertThrows<IllegalArgumentException> { SessionRecurrence.occurrences("DAILY", emptyList(), day, day.plusYears(2), eight, lima) }
    }
}
