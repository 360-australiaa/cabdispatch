package au.com.threesixty.cabdispatch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for devices, vehicles, position publishing, compliance and fatigue.
 *
 * Split out of [ApiService] verbatim in Phase 0 (P0.4) — same package, same
 * visibility, same declarations, no behaviour change. `DriverEngagementDtos.kt` set
 * the precedent. [ApiService]'s file header still carries the rules these all follow:
 * money and other Pydantic `Decimal`s arrive as JSON *strings* and are kept as [String]
 * (never Float/Double), datetimes are ISO-8601 normalised to UTC, and unknown keys are
 * ignored by the shared [au.com.threesixty.cabdispatch.data.cabDispatchJson] config so
 * additive backend fields never break an older build.
 */

@Serializable
data class DeviceRegisterRequestDto(
    @SerialName("android_id") val androidId: String,
    @SerialName("pairing_code") val pairingCode: String,
    val model: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
)

/** Body of [ApiService.deviceLocateResponse]. [accuracyM] is null when the fix carries no accuracy
 * — never a guessed number, so the dashboard shows a position without a precision claim it cannot
 * support. */
@Serializable
data class DeviceLocateResponseDto(
    val lat: Double,
    val lng: Double,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
)

/** Body of [ApiService.deviceCommandAck]. `restart` is the only command the server accepts. */
@Serializable
data class DeviceCommandAckDto(val command: String)

@Serializable
data class DeviceHeartbeatRequestDto(
    val battery: Int? = null,
    val network: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
)

/**
 * [kioskLocked]/[forceUpdatePending]/[locateRequested]/[rebootRequested] mirror the backend's
 * MDM-lite command flags (`backend/app/schemas/fleet.py::DeviceRead`, see
 * `POST /v1/fleet/devices/{id}/kiosk-lock`/`/force-update`/`/locate`/`/reboot`) — an admin sets one
 * via the dashboard, the device reads it back on its next [ApiService.deviceHeartbeat] call. Those
 * endpoints are pure flag-set columns with no push channel behind them, so this response body is
 * the *only* way any of them ever reaches the tablet; since 2026-08-29
 * [au.com.threesixty.cabdispatch.domain.DeviceCommandHeartbeat] polls this endpoint for the
 * process lifetime and acts on the first three: [kioskLocked] drives app-wide screen pinning in
 * [au.com.threesixty.cabdispatch.MainActivity], [forceUpdatePending] drives a persistent driver-
 * facing banner, and [locateRequested] is answered on [ApiService.deviceLocateResponse] — the
 * DEVICE route, which needs no vehicle and no driver session, so a parked tablet can still say
 * where it is. It used to be answered by publishing a vehicle position; see that method's doc for
 * the two field failures that caused.
 *
 * [rebootRequested] is now consumed too, as a RESTART OF THIS APP, not an OS reboot. The backend's
 * HONESTY NOTE still holds for the OS — that needs device-owner permissions this app does not hold
 * — but restarting the meter's own process is both possible and what the button is actually
 * reached for, and the app acknowledges on [ApiService.deviceCommandAck] so the flag clears
 * instead of reading "Pending" for the life of the row.
 */
@Serializable
data class DeviceDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String,
    // The tenant's SLUG, not its uuid. Set by the backend only on the two routes a tablet can
    // reach with no bearer token -- POST /devices/register and GET /devices/me -- because those
    // are the only points at which a device that cannot yet log anybody in needs to know its
    // operator. POST /v1/auth/driver-login requires it, and without it every driver login failed
    // with 422 (test tablet, 2026-09-08). Nullable: absent on every other DeviceRead response,
    // and on an older backend that does not send it at all.
    @SerialName("tenant_slug") val tenantSlug: String? = null,
    @SerialName("android_id") val androidId: String,
    val model: String?,
    @SerialName("app_version") val appVersion: String?,
    @SerialName("vehicle_id") val vehicleId: String?,
    @SerialName("kiosk_locked") val kioskLocked: Boolean,
    @SerialName("force_update_pending") val forceUpdatePending: Boolean,
    @SerialName("locate_requested") val locateRequested: Boolean = false,
    @SerialName("reboot_requested") val rebootRequested: Boolean = false,
    @SerialName("last_seen_at") val lastSeenAt: String?,
    val battery: Int?,
    val network: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    /**
     * The device-scoped heartbeat credential (backend, 2026-08-29) — present ONLY on a
     * [ApiService.registerDevice] response, ONE TIME, right after a (re-)pair; every other
     * response that returns a [DeviceDto] (heartbeat, locate, etc.) omits it, and the backend never
     * returns it again after this call. [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel.submitPairingCode]
     * must persist it via [au.com.threesixty.cabdispatch.domain.DevicePairingStore.saveDeviceSecret]
     * in the same breath as the device id — miss this one response and there is no way to fetch it
     * again short of re-pairing. Re-pairing rotates it: a fresh secret is issued and the previous
     * one stops authenticating immediately, mirrored client-side by simply overwriting the stored
     * value. `null` on a device paired before this field existed — that device keeps authenticating
     * on the driver-bearer path unmodified until it next re-pairs.
     */
    @SerialName("device_secret") val deviceSecret: String? = null,
    /**
     * Real OTA self-update hint (2026-09-06 — see [au.com.threesixty.cabdispatch.domain.AppUpdateChecker]
     * and `docs/OTA_UPDATE_ROLLOUT.md`), stamped by the backend from its own current
     * `GET /v1/app-releases/latest` answer on every heartbeat response (`app/api/v1/fleet.py`'s
     * `device_heartbeat`) — a low-cost hint riding this existing 60s poll. `null` means either "no
     * active release has ever been published" or simply that this response didn't carry the hint
     * (older backend) — [DeviceCommandHeartbeat] treats both the same: not "you are up to date",
     * just "no hint this tick". [au.com.threesixty.cabdispatch.domain.AppUpdateChecker.checkForUpdate]'s
     * own dedicated `GET /v1/app-releases/latest` call remains the source of truth used to actually
     * drive the update flow — this field only lets [DeviceCommandState] show *that* an update is
     * known to be pending without a second network round trip.
     */
    @SerialName("latest_version_code") val latestVersionCode: Int? = null,
    /**
     * This device row's own [vehicleId]'s rego, joined in server-side (backend `DeviceRead.
     * vehicle_rego`, `device_heartbeat`) — `null` whenever [vehicleId] is unset, the vehicle has
     * since been deleted, or the backend predates this field. See
     * [au.com.threesixty.cabdispatch.domain.decideVehicleRebind]'s doc ("The fourth case") for why
     * this exists: it is what lets that self-heal tell a genuine fleet-wipe/reseed (same rego, new
     * uuid — heal it) apart from a driver deliberately bound to a different vehicle than this
     * tablet's admin-configured pairing (leave it alone).
     */
    @SerialName("vehicle_rego") val vehicleRego: String? = null,
)

/**
 * Body for [ApiService.publishPosition] (`POST /v1/fleet/positions`, backend's
 * `PositionPublishRequest`) — a device/tick handler's position report for one vehicle. Three call
 * sites, all best-effort/fire-and-forget: the MDM "locate" response
 * ([DeviceCommandHeartbeat][au.com.threesixty.cabdispatch.domain.DeviceCommandHeartbeat]),
 * the ambient while-on-shift heartbeat
 * ([LivePositionHeartbeat][au.com.threesixty.cabdispatch.domain.LivePositionHeartbeat] — originally
 * the Taxi Meter SaaS Complete Blueprint's literal §6.2.2 "vehicle.heartbeat" 30s figure, now 5s;
 * see that class's own "Interval" doc for why), and (separately, still unwired — see
 * HANDOFF.md "Availability broadcast not wired") the Idle screen's "For Hire" toggle. [status] has
 * no server-side enum constraint (backend: a plain `str`, `min_length=1, max_length=20`), just
 * documented examples ("available"/"on_trip"/"offline"/"break") — any short non-empty string
 * round-trips fine.
 */
/** One row of `GET /v1/fleet/vehicles`. Originally only carried `id`/`rego` (the one field
 * [ApiService.listVehicles]'s login-time caller needed) — [make]/[model]/[registrationExpiry]/
 * [insuranceExpiry] were added for `ui/screens/profile/ProfileScreen.kt`'s Identity card
 * ("GHP-1 · Toyota Camry Hybrid" instead of just the rego) and its Documents tab's real
 * Registration/Insurance expiry-status rows (Phase H, 2026-09-03) — see `backend/app/schemas/fleet.py`'s
 * `VehicleBase`, which already carried all four fields on the wire; this DTO just wasn't reading
 * them yet. The rest of the real response (vin/vehicle_class/status/...) still has no on-device
 * use, left off rather than guessed. All four new fields are nullable with a `null` default —
 * `null` means genuinely unset on this vehicle's row (a real, honest "—" case, not a decode
 * failure) and, per `data/JsonConfig.kt`'s `ignoreUnknownKeys`, also keeps this DTO safe against
 * any older cached/mocked payload that predates them. */
@Serializable
data class VehicleDto(
    val id: String,
    val rego: String,
    val make: String? = null,
    val model: String? = null,
    @SerialName("registration_expiry") val registrationExpiry: String? = null,
    @SerialName("insurance_expiry") val insuranceExpiry: String? = null,
)

@Serializable
data class VehiclePageDto(
    val items: List<VehicleDto>,
    val total: Int,
)

@Serializable
data class PositionPublishRequestDto(
    @SerialName("vehicle_id") val vehicleId: String,
    val lat: Double,
    val lng: Double,
    val status: String,
    /** 0-100, or `null` if unreadable (see [au.com.threesixty.cabdispatch.domain.LivePositionHeartbeat]'s
     * read site). Optional/additive — same `POST /v1/fleet/positions` call, no new endpoint. */
    val battery: Int? = null,
    /** `"wifi"` / `"4g"` / `"offline"` (or similar transport-derived categories) — see this
     * field's read site for the exact mapping. Optional/additive, same reasoning as [battery]. */
    val network: String? = null,
    /** Ground speed, km/h, from [au.com.threesixty.cabdispatch.domain.LocationFix.speedKmh] at
     * the same instant as [lat]/[lng] — `null` only if
     * [au.com.threesixty.cabdispatch.domain.LivePositionHeartbeat.publishOnce] had no fix at all
     * (it would have skipped the call entirely in that case; kept nullable here rather than
     * non-null purely so this DTO matches the wire shape of a field the backend also treats as
     * optional). Wire name `speed_kmh` — must byte-for-byte match the backend's
     * `PositionPublishRequest.speed_kmh` (`app/schemas/live_ops.py`). */
    @SerialName("speed_kmh") val speedKmh: Double? = null,
    /** Compass bearing in degrees (0=north), from
     * [au.com.threesixty.cabdispatch.domain.LocationFix.heading] — `null` whenever that field is
     * (device stationary, or the platform reported no bearing for this fix); never fabricated,
     * same honest-null posture as [battery]/[network] above. Wire name `heading` — must
     * byte-for-byte match the backend's `PositionPublishRequest.heading` (`app/schemas/live_ops.py`). */
    val heading: Double? = null,
)

/** Response for [ApiService.publishPosition] — mirrors the backend's `PositionPublishResponse`
 * (a `PositionRead` plus [subscriberCount]). Not currently read by either call site (both are
 * fire-and-forget), kept typed rather than discarded so a future caller that wants to confirm the
 * publish landed doesn't have to add it later. */
@Serializable
data class PositionPublishResponseDto(
    @SerialName("vehicle_id") val vehicleId: String,
    val lat: Double,
    val lng: Double,
    val status: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("subscriber_count") val subscriberCount: Int,
)

// ---- Compliance Vault DTOs — mirror shared/openapi.json ComplianceDossierRead/
// ChecklistItemRead 1:1, minus the nested per-document list (see [ChecklistItemDto] doc). ----

/** One cl.14-checklist line item (e.g. "Calibration record", "Duress alarm register"). Server's
 * `ChecklistItemRead` also carries a full `documents: List<ComplianceDocumentRead>` — omitted
 * here since Profile > Compliance only needs the satisfied/not-satisfied summary, not a
 * per-document browser/downloader (ignoreUnknownKeys=true makes leaving it off safe). */
@Serializable
data class ChecklistItemDto(
    val key: String,
    val label: String,
    @SerialName("doc_types") val docTypes: List<String>,
    val satisfied: Boolean,
    @SerialName("document_count") val documentCount: Int,
)

@Serializable
data class ComplianceDossierDto(
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("generated_at") val generatedAt: String,
    val items: List<ChecklistItemDto>,
    @SerialName("overall_compliant") val overallCompliant: Boolean,
    @SerialName("missing_items") val missingItems: List<String>,
)

/** One row of `GET /v1/fleet/compliance-expiry` — `ComplianceExpiryItem`. */
@Serializable
data class ComplianceExpiryItemDto(
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val label: String,
    val field: String,
    @SerialName("expiry_date") val expiryDate: String,
    val status: String,
    @SerialName("days_remaining") val daysRemaining: Int,
)

@Serializable
data class ComplianceExpiryPageDto(
    val items: List<ComplianceExpiryItemDto>,
    val total: Int,
    val skip: Int,
    val limit: Int,
)

/** Mirrors `FatigueAlertRead` (subset — the fields the Shift screen renders). */
@Serializable
data class FatigueAlertDto(
    val id: String,
    @SerialName("driver_id") val driverId: String? = null,
    @SerialName("vehicle_id") val vehicleId: String? = null,
    @SerialName("shift_id") val shiftId: String? = null,
    val kind: String,
    @SerialName("triggered_at") val triggeredAt: String,
)

@Serializable
data class FatigueAlertPageDto(
    val items: List<FatigueAlertDto>,
    val total: Int,
    val skip: Int,
    val limit: Int,
)

/** Mirrors the backend's `LatestAppReleaseRead` (`GET /v1/app-releases/latest`,
 * `app/schemas/app_releases.py`) — the real OTA self-update contract, see
 * [au.com.threesixty.cabdispatch.domain.AppUpdateChecker]. [downloadUrl] is a relative path
 * (`/v1/app-releases/{id}/download`) resolved against `BuildConfig.API_BASE_URL` by
 * [au.com.threesixty.cabdispatch.domain.AppUpdateChecker.downloadAndVerify], not an absolute URL —
 * same relative-path convention as [UserDto.photoUrl]. [sha256] is always server-computed at
 * publish time; [AppUpdateChecker] verifies the downloaded bytes against it before this app will
 * ever consider installing them. */
@Serializable
data class LatestAppReleaseDto(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    @SerialName("release_notes") val releaseNotes: String? = null,
    @SerialName("download_url") val downloadUrl: String,
    val sha256: String,
)
