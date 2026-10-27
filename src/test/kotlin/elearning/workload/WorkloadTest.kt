package elearning.workload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class WorkloadTest {
    private fun hours(value: String) = BigDecimal(value)

    // el ejemplo de la especificación (6.3): 6 semanas de 12 h de teoría, 12 de laboratorio y 12 autónomas
    private val example = Workload(weeks = 6, weekly = WeeklyHours(theory = hours("12"), practice = hours("12"), autonomous = hours("12")))

    @Test
    fun `example of the specification adds up to 216 hours`() {
        assertEquals(hours("72"), example.theoryHours)
        assertEquals(hours("72"), example.practiceHours)
        assertEquals(hours("0"), example.supervisedHours)
        assertEquals(hours("72"), example.autonomousHours)
        assertEquals(hours("144"), example.contactHours)
        assertEquals(hours("144"), example.directedHours)
        assertEquals(hours("216"), example.totalHours)
        assertEquals(hours("36"), example.weekly.total)
    }

    @Test
    fun `supervised activities are directed work but not contact hours`() {
        val workload = Workload(weeks = 4, weekly = WeeklyHours(theory = hours("2"), supervised = hours("3"), autonomous = hours("5")))

        assertEquals(hours("8"), workload.contactHours)
        assertEquals(hours("20"), workload.directedHours)
        assertEquals(hours("40"), workload.totalHours)
    }

    @Test
    fun `fractional hours are kept exactly`() {
        val workload = Workload(weeks = 3, weekly = WeeklyHours(theory = hours("1.5"), autonomous = hours("0.25")))

        assertEquals(0, hours("5.25").compareTo(workload.totalHours))
    }

    @Test
    fun `a course without hours has no workload but is valid`() {
        assertEquals(0, BigDecimal.ZERO.compareTo(Workload(weeks = 1, weekly = WeeklyHours()).totalHours))
    }

    @Test
    fun `weeks must be between one and two years`() {
        assertThrows<IllegalArgumentException> { Workload(weeks = 0, weekly = WeeklyHours()) }
        assertThrows<IllegalArgumentException> { Workload(weeks = 105, weekly = WeeklyHours()) }
        Workload(weeks = 104, weekly = WeeklyHours())
    }

    @Test
    fun `hours cannot be negative nor exceed the hours of a week`() {
        assertThrows<IllegalArgumentException> { WeeklyHours(theory = hours("-1")) }
        assertThrows<IllegalArgumentException> { WeeklyHours(theory = hours("100"), autonomous = hours("69")) }
        WeeklyHours(theory = hours("100"), autonomous = hours("68"))
    }
}
