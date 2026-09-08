package au.com.threesixty.cabdispatch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for zones, plotting and per-zone demand statistics.
 *
 * Split out of [ApiService] verbatim in Phase 0 (P0.4) — same package, same
 * visibility, same declarations, no behaviour change. `DriverEngagementDtos.kt` set
 * the precedent. [ApiService]'s file header still carries the rules these all follow:
 * money and other Pydantic `Decimal`s arrive as JSON *strings* and are kept as [String]
 * (never Float/Double), datetimes are ISO-8601 normalised to UTC, and unknown keys are
 * ignored by the shared [au.com.threesixty.cabdispatch.data.cabDispatchJson] config so
 * additive backend fields never break an older build.
 */

// ---- Zones DTOs — mirror backend/app/schemas/zones.py ZoneRead/Page[ZoneRead]/ZonePlotRead/
// ZoneStats 1:1. [ZoneDto.centerLat]/[centerLng]/[radiusM] are plain JSON numbers server-side
// (Pydantic `float`, not `Decimal`), unlike this file's money-as-string convention — geometry
// isn't money, so `Double` round-trips exactly like every other lat/lng field already in this
// file (e.g. [JobDto.originLat]). ----

/** Mirrors `ZoneRead` (`backend/app/schemas/zones.py`) — one named dispatch zone. [number] is
 * the short driver-facing code (e.g. "17") shown on the Plot screen's zone rows, per
 * [au.com.threesixty.cabdispatch.ui.screens.zones.PlotZoneScreen]. */
@Serializable
data class ZoneDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String,
    val name: String,
    val number: String,
    @SerialName("center_lat") val centerLat: Double,
    @SerialName("center_lng") val centerLng: Double,
    @SerialName("radius_m") val radiusM: Double,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Response for [ApiService.listZones] — mirrors the backend's generic `Page[ZoneRead]`, same
 * items/total/skip/limit shape every other paginated list DTO in this file already uses (e.g.
 * [JobListResponseDto]). */
@Serializable
data class ZoneListResponseDto(
    val items: List<ZoneDto>,
    val total: Int,
    val skip: Int,
    val limit: Int,
)

/** Response for [ApiService.plotIntoZone]/[ApiService.unplotZone] — mirrors `ZonePlotRead`, a
 * thin projection of the calling driver's own current shift's plotting state (not the full
 * [ShiftDto] shape — the caller only needs to know where, if anywhere, they're plotted).
 * [plottedZoneId] null means "not currently plotted into any zone" (either never plotted this
 * shift, or this is the response of an [ApiService.unplotZone] call). */
@Serializable
data class ZonePlotReadDto(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("driver_id") val driverId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("plotted_zone_id") val plottedZoneId: String? = null,
    @SerialName("plotted_at") val plottedAt: String? = null,
)

/** One row of [ApiService.zoneStats] — mirrors `ZoneStats`
 * (`backend/app/schemas/zones.py`; see `app.services.zones.compute_zone_stats` server-side for
 * the exact definition/documented simplifications of every count below) — the Statistics
 * screen's table, per
 * [au.com.threesixty.cabdispatch.ui.screens.zones.ZoneStatisticsScreen]. */
@Serializable
data class ZoneStatsDto(
    @SerialName("zone_id") val zoneId: String,
    @SerialName("zone_name") val zoneName: String,
    @SerialName("zone_number") val zoneNumber: String,
    @SerialName("plotted_vehicles") val plottedVehicles: Int,
    @SerialName("vacant_vehicles") val vacantVehicles: Int,
    @SerialName("busy_vehicles") val busyVehicles: Int,
    @SerialName("jobs_holding") val jobsHolding: Int,
    @SerialName("bookings_last_hour") val bookingsLastHour: Int,
    @SerialName("street_hails_last_hour") val streetHailsLastHour: Int,
)
