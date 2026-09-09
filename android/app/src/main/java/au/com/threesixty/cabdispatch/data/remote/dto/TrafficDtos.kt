package au.com.threesixty.cabdispatch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for the live NSW traffic domain (`backend/app/api/v1/traffic.py`) — read-only reference
 * data (cameras + hazards) OUR OWN backend keeps fresh from Transport for NSW's public feeds
 * (`backend/app/services/live_traffic.py`). The device never calls livetraffic.com or any other
 * third party directly; it only ever calls this backend's `/v1/traffic` routes — the same
 * "third-party data always routes through the backend first" rule the NSW toll registry
 * ([TollRoadDto]) and the airport-fee geofences ([GeofenceDto]) already follow.
 *
 * Field names/nullability match the backend's real `TrafficCameraRead`/`TrafficHazardRead`
 * (`backend/app/schemas/traffic.py`) exactly — checked against that file directly rather than
 * assumed, since the router already exists in this worktree. Two backend-only fields
 * (`source_last_published`, `raw_json`) are omitted: nothing on-device needs them, and
 * [au.com.threesixty.cabdispatch.data.cabDispatchJson]'s `ignoreUnknownKeys` makes leaving them
 * off safe (same convention as every other DTO file's header note).
 *
 * [TrafficCameraPageDto]/[TrafficHazardPageDto] mirror the backend's generic `Page[T]` wrapper
 * (`items`/`total`/`skip`/`limit`) — the same shape [GeofenceListResponseDto] already uses.
 */

/** Mirrors `TrafficCameraRead` — one live NSW traffic camera. [imageUrl] is a direct JPEG
 * snapshot URL the server refreshes live server-side; this DTO carries the URL only, never the
 * image bytes. */
@Serializable
data class TrafficCameraDto(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val direction: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val region: String? = null,
)

/** `GET /v1/traffic/cameras` response — the backend's `Page[TrafficCameraRead]`. */
@Serializable
data class TrafficCameraPageDto(
    val items: List<TrafficCameraDto>,
    val total: Int = 0,
    val skip: Int = 0,
    val limit: Int = 0,
)

/** Mirrors `TrafficHazardRead` — one live NSW hazard (incident/roadwork/flood/fire/alpine/
 * majorevent — `app.models.traffic.TRAFFIC_HAZARD_CATEGORIES`). [speedLimit]/[expectedDelayMinutes]
 * are `null` when the source feed didn't provide one (the backend normalizes the source's own
 * "-1 means not provided" sentinel to `null` before this ever reaches the wire) — never a
 * fabricated figure. [ended] is always `false` for a row that exists at all when queried with
 * `active_only=true` (the backend deletes an ended hazard outright rather than flagging it — see
 * that model's own doc) but the column is real and kept here too, honestly. */
@Serializable
data class TrafficHazardDto(
    val id: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val headline: String? = null,
    @SerialName("closure_type") val closureType: String? = null,
    val direction: String? = null,
    @SerialName("speed_limit") val speedLimit: Int? = null,
    @SerialName("expected_delay_minutes") val expectedDelayMinutes: Int? = null,
    val ended: Boolean = false,
)

/** `GET /v1/traffic/hazards` response — the backend's `Page[TrafficHazardRead]`. */
@Serializable
data class TrafficHazardPageDto(
    val items: List<TrafficHazardDto>,
    val total: Int = 0,
    val skip: Int = 0,
    val limit: Int = 0,
)
