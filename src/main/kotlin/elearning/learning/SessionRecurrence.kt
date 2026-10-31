package elearning.learning

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

// las ocurrencias de una serie, dominio puro (RF-SES-02). cada una se calcula desde su fecha y la hora local en la zona de
// la edición, así que esa hora se mantiene al cruzar el horario de verano
object SessionRecurrence {
    const val MAX_OCCURRENCES = 200
    private const val MAX_DAYS = 400L

    fun occurrences(
        pattern: String,
        days: List<DayOfWeek>,
        start: LocalDate,
        end: LocalDate,
        time: LocalTime,
        zone: ZoneId,
    ): List<Instant> {
        require(!end.isBefore(start)) { "endDate is before startDate" }
        require(ChronoUnit.DAYS.between(start, end) <= MAX_DAYS) { "the series is too long" }
        val dates =
            generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        val picked =
            when (pattern) {
                "ONCE" -> listOf(start)
                "DAILY" -> dates
                "ALTERNATE_DAYS" -> dates.filterIndexed { i, _ -> i % 2 == 0 }
                "WEEKDAYS" -> {
                    require(days.isNotEmpty()) { "days is required" }
                    dates.filter { it.dayOfWeek in days }
                }
                else -> throw IllegalArgumentException("unknown pattern $pattern")
            }
        require(picked.size <= MAX_OCCURRENCES) { "too many sessions" }
        return picked.map { ZonedDateTime.of(it, time, zone).toInstant() }
    }
}
