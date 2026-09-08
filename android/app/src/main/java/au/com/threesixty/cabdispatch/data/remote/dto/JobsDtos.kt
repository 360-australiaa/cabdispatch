package au.com.threesixty.cabdispatch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for dispatch jobs, offers and driver availability.
 *
 * Split out of [ApiService] verbatim in Phase 0 (P0.4) — same package, same
 * visibility, same declarations, no behaviour change. `DriverEngagementDtos.kt` set
 * the precedent. [ApiService]'s file header still carries the rules these all follow:
 * money and other Pydantic `Decimal`s arrive as JSON *strings* and are kept as [String]
 * (never Float/Double), datetimes are ISO-8601 normalised to UTC, and unknown keys are
 * ignored by the shared [au.com.threesixty.cabdispatch.data.cabDispatchJson] config so
 * additive backend fields never break an older build.
 */

// ---- Jobs DTOs — mirror shared/openapi.json JobCreate/JobRead/JobOfferRead/
// DriverAvailability{Update,Read} schemas 1:1. Money fields (fare estimates) kept as String per
// this file's header rule even though the backend schema accepts number|string on the way in. ----

/** Body for `POST /v1/jobs` — a new ride request. `status`/`requested_at`/`created_by_user_id`
 * are all server-assigned, never client-supplied. */
@Serializable
data class JobCreateDto(
    @SerialName("origin_lat") val originLat: Double,
    @SerialName("origin_lng") val originLng: Double,
    @SerialName("origin_address") val originAddress: String,
    @SerialName("dest_lat") val destLat: Double,
    @SerialName("dest_lng") val destLng: Double,
    @SerialName("dest_address") val destAddress: String,
    @SerialName("fare_estimate_low") val fareEstimateLow: String,
    @SerialName("fare_estimate_high") val fareEstimateHigh: String,
)

@Serializable
data class JobDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("origin_lat") val originLat: Double,
    @SerialName("origin_lng") val originLng: Double,
    @SerialName("origin_address") val originAddress: String,
    @SerialName("dest_lat") val destLat: Double,
    @SerialName("dest_lng") val destLng: Double,
    @SerialName("dest_address") val destAddress: String,
    val status: String, // queued | offered | accepted | expired | cancelled
    @SerialName("fare_estimate_low") val fareEstimateLow: String,
    @SerialName("fare_estimate_high") val fareEstimateHigh: String,
    @SerialName("requested_at") val requestedAt: String,
    @SerialName("created_by_user_id") val createdByUserId: String?,
    @SerialName("accepted_by_driver_id") val acceptedByDriverId: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    /**
     * 2026-09-05 API-audit pass, correcting this doc comment's own prior claims (there was never
     * a migration `9a9364f2c706`, and the backend never had a `job_type` column or an ETA
     * service — that earlier text described a backend contract that didn't actually exist):
     *
     * - [distanceKm] is now real: `app.schemas.jobs.JobRead` computes it server-side from this
     *   same job's own origin/dest lat-lng via the exact `haversine_km` helper this codebase
     *   already uses elsewhere (toll-geofence detection) — a straight-line, NOT routed/live-traffic,
     *   distance, always present (never null) on any `JobRead` response.
     * - [jobType] and [etaMin] are deliberately NOT backed by anything server-side and stay
     *   permanently `null`: every row in the backend's `jobs` table is, by that domain's own
     *   construction, a dispatch/broadcast job (a rank/hail job never creates one), so there is no
     *   real per-record classification to expose as `job_type` — hardcoding a constant would add
     *   no information. A real `eta_min` needs a routing/live-traffic service this codebase does
     *   not have; a flat-speed guess would be a fabricated arrival-time estimate. Callers must keep
     *   degrading on `null` (e.g. [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s
     *   dispatch card falls back to a live-GPS straight-line distance and omits ETA entirely).
     */
    @SerialName("job_type") val jobType: String? = null, // "booked" | "rank_hail" -- always null, see above
    @SerialName("distance_km") val distanceKm: String? = null,
    @SerialName("eta_min") val etaMin: Int? = null, // always null, see above
)

@Serializable
data class JobListResponseDto(
    val items: List<JobDto>,
    val total: Int,
    val skip: Int,
    val limit: Int,
)

@Serializable
data class JobOfferDto(
    val id: String,
    @SerialName("job_id") val jobId: String,
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("driver_id") val driverId: String,
    val status: String, // pending | accepted | declined | expired
    @SerialName("offered_at") val offeredAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("responded_at") val respondedAt: String?,
)

/** Body for `POST /v1/jobs/availability` — a driver's own self-toggle. */
@Serializable
data class DriverAvailabilityUpdateDto(@SerialName("is_available") val isAvailable: Boolean)

@Serializable
data class DriverAvailabilityDto(
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("driver_id") val driverId: String,
    @SerialName("is_available") val isAvailable: Boolean,
    @SerialName("updated_at") val updatedAt: String,
)
