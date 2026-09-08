package au.com.threesixty.cabdispatch.ui.screens.zones

import androidx.compose.ui.graphics.Color
import au.com.threesixty.cabdispatch.data.remote.ZoneStatsDto
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette

/**
 * SURGE MULTIPLIER — computed client-side, purely derived from real [ZoneStatsDto] fields.
 *
 * `squishy-herding-iverson.md` Phase F's confirmed design decision: this app has NO server-side
 * surge concept anywhere (no admin UI to "set" a zone's multiplier, no new arbitrary field a human
 * types in — `ZoneDto`/`ZoneStatsDto` and the backend's `Zone`/`compute_zone_stats` carry none).
 * Rather than invent one, the mockup's "1.0x/1.2x/1.6x/2.0x" bands are derived honestly from the
 * live supply/demand numbers [ZoneStatisticsViewModel] already polls every 20s:
 *
 * - **demand** = [ZoneStatsDto.bookingsLastHour] + [ZoneStatsDto.streetHailsLastHour] — real `Trip`
 *   rows that actually started inside this zone in the last hour
 *   (`app.services.zones.compute_zone_stats`).
 * - **supply** = [ZoneStatsDto.vacantVehicles] — real, GPS-confirmed vehicles physically inside
 *   this zone's geofence that are NOT currently on a trip right now. Deliberately NOT
 *   [ZoneStatsDto.busyVehicles] (a vehicle mid-trip cannot pick up new demand) and NOT
 *   [ZoneStatsDto.plottedVehicles] (plotting is a self-reported queue intent stored on the
 *   driver's `Shift`, not a GPS-verified presence — a driver can plot into a zone from anywhere in
 *   the city; folding it into "supply" would both double-count a driver who is plotted AND already
 *   showing up as vacant, and credit supply to a zone the driver isn't actually sitting in).
 *
 * ratio = demand / (supply + 1) — the "+1" floor avoids a divide-by-zero in a zone with zero
 * vacant vehicles, and stops one lone booking in an empty zone from instantly reading as the
 * maximum band, while still climbing sharply once real supply is genuinely thin. A zone with zero
 * demand is always the calm 1.0x band regardless of supply — an empty zone with no bookings/hails
 * isn't "surging", it's just quiet.
 *
 * The ratio bands into the mockup's four display tiers:
 * ```
 * ratio <  0.2  -> 1.0x   (calm — plenty of vacant cars relative to demand)
 * ratio <  0.5  -> 1.2x
 * ratio <  1.0  -> 1.6x
 * ratio >= 1.0  -> 2.0x   (hot — demand at or beyond vacant supply)
 * ```
 *
 * Worked example: a zone reporting 6 bookings+hails in the last hour and 2 vacant vehicles has
 * ratio = 6 / (2 + 1) = 2.0 -> the 2.0x top band; six passengers chasing two free cars is a real,
 * legible surge signal, not decoration.
 */
object SurgeModel {

    /** Raw, unbanded demand/supply ratio — used for the Surge Areas tab's sort order (finer
     * grained than the 4 display tiers) and for verification/debugging. Zero when there is no
     * demand at all, regardless of supply. */
    fun demandSupplyRatio(stats: ZoneStatsDto): Double {
        val demand = stats.bookingsLastHour + stats.streetHailsLastHour
        if (demand <= 0) return 0.0
        val supply = stats.vacantVehicles
        return demand.toDouble() / (supply + 1)
    }

    /** The banded display multiplier — always exactly one of 1.0 / 1.2 / 1.6 / 2.0, matching the
     * mockup's four surge tiers. */
    fun multiplier(stats: ZoneStatsDto): Double {
        val ratio = demandSupplyRatio(stats)
        return when {
            ratio < 0.2 -> 1.0
            ratio < 0.5 -> 1.2
            ratio < 1.0 -> 1.6
            else -> 2.0
        }
    }

    /** "1.0x" / "1.2x" / "1.6x" / "2.0x" — the exact label format the mockup shows. */
    fun label(stats: ZoneStatsDto): String = "${"%.1f".format(multiplier(stats))}x"

    /**
     * Band color — a purple-to-amber "glow" progression consistent with [CaptainPalette] and this
     * app's existing amber-for-hottest convention ([ZoneStatisticsScreen]'s `HotZoneTip`/hot-row
     * highlight already uses [CaptainPalette.warning] for "most demand"), deliberately NOT literal
     * traffic-light red/green.
     */
    fun color(multiplier: Double): Color = when {
        multiplier <= 1.0 -> DIM_VIOLET
        multiplier <= 1.2 -> CaptainPalette.accent
        multiplier <= 1.6 -> MAGENTA_GLOW
        else -> CaptainPalette.warning
    }

    /** Same band color as [color], as a "#RRGGBB" hex string — Mapbox's `CircleAnnotationOptions`
     * color setters take a hex string, not a Compose [Color]. */
    fun colorHex(multiplier: Double): String {
        val c = color(multiplier)
        return "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
    }

    private val DIM_VIOLET = Color(0xFF4C4570)
    private val MAGENTA_GLOW = Color(0xFFD946EF)
}
