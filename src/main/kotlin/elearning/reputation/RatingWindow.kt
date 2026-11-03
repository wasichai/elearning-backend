package elearning.reputation

import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

// la ventana de valoración (ADR-0038 §4): se crea, edita o retira hasta window-after-end días tras fecha_fin, contados en
// la zona horaria de la edición. sin fecha de fin no hay ventana (falla cerrada)
object RatingWindow {
    private val DEFAULT_ZONE: ZoneId = ZoneId.of("America/Lima")

    fun closesOn(
        end: LocalDate,
        window: Duration,
    ): LocalDate = end.plusDays(window.toDays())

    fun today(
        zone: String?,
        clock: Clock,
    ): LocalDate = LocalDate.now(clock.withZone(zoneOf(zone)))

    fun isOpen(
        end: LocalDate?,
        zone: String?,
        window: Duration,
        clock: Clock,
    ): Boolean = end != null && !today(zone, clock).isAfter(closesOn(end, window))

    private fun zoneOf(zone: String?): ZoneId = zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: DEFAULT_ZONE
}
