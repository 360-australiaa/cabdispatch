package au.com.threesixty.cabdispatch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for the duress alarm lifecycle and its evidence snapshots.
 *
 * Split out of [ApiService] verbatim in Phase 0 (P0.4) — same package, same
 * visibility, same declarations, no behaviour change. `DriverEngagementDtos.kt` set
 * the precedent. [ApiService]'s file header still carries the rules these all follow:
 * money and other Pydantic `Decimal`s arrive as JSON *strings* and are kept as [String]
 * (never Float/Double), datetimes are ISO-8601 normalised to UTC, and unknown keys are
 * ignored by the shared [au.com.threesixty.cabdispatch.data.cabDispatchJson] config so
 * additive backend fields never break an older build.
 */

// ---- Duress DTOs — mirror shared/openapi.json DuressTriggerRequest/DuressCancelRequest/
// DuressEventRead/DuressGpsPoint 1:1. ----

/** Body for `POST /v1/duress/trigger`. [trigger] is one of `button|gesture|voice|auto` — this
 * app only ever sends `"gesture"` (the hidden triple-tap), the others exist for a future
 * physical panic button / voice wake-word / automatic (e.g. crash-detection) trigger. */
@Serializable
data class DuressTriggerRequestDto(
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("driver_id") val driverId: String,
    val trigger: String,
    @SerialName("gps_stream_ref") val gpsStreamRef: String? = null,
    @SerialName("audio_ref") val audioRef: String? = null,
)

/** Body for `POST /v1/duress/{id}/cancel`. No fields required server-side; [note] is reserved
 * for an optional driver-entered reason (not currently surfaced in the UI — the Cancel button
 * sends an empty body). */
@Serializable
data class DuressCancelRequestDto(val note: String? = null)

@Serializable
data class DuressGpsPointDto(
    val lat: Double,
    val lng: Double,
    @SerialName("speed_kmh") val speedKmh: Double? = null,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
)

@Serializable
data class DuressEventDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("driver_id") val driverId: String,
    val trigger: String,
    val status: String, // open | escalating | dispatched | cancelled | resolved
    @SerialName("opened_at") val openedAt: String,
    @SerialName("closed_at") val closedAt: String?,
    @SerialName("gps_stream_ref") val gpsStreamRef: String,
    @SerialName("audio_ref") val audioRef: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    // escalation_log_json deliberately omitted — an untyped free-form dict server-side
    // (app.models.duress.DuressEvent's doc), not needed for the on-device overlay UI, and
    // ignoreUnknownKeys=true (see data/JsonConfig.kt) means leaving it off here is safe.
)

/** Mirrors the backend's duress-snapshot upload response (`app/schemas/duress.py`, or the
 * equivalent inline response model — see `POST /v1/duress/{event_id}/snapshot`'s doc). Not
 * consumed for anything today, same as [DuressEventDto] from [ApiService.uploadDuressAudio]. */
@Serializable
data class DuressSnapshotDto(
    val id: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("captured_at") val capturedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)
