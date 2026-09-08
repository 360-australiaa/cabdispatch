package au.com.threesixty.cabdispatch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for shift start/end and the end-of-shift report.
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
data class ShiftStartDto(
    @SerialName("driver_id") val driverId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("start_at") val startAt: String? = null,
    @SerialName("inspection_json") val inspectionJson: Map<String, String>? = null,
    /** The calling tablet's `Settings.Secure.ANDROID_ID`, if known — read fresh at shift-start
     * (same call [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel.submitPairingCode]
     * uses to register a device), not persisted anywhere on-device. Used server-side only for a
     * non-blocking cross-check against that device's paired vehicle (`fleet.Device.vehicle_id`) —
     * see [ShiftDto.deviceMismatchWarning]. Never blocks or alters the shift. Defaulted null so
     * this DTO still encodes fine for callers (offline-fallback path in
     * [au.com.threesixty.cabdispatch.domain.RemoteBackedShiftRepository]) that never read one. */
    @SerialName("device_android_id") val deviceAndroidId: String? = null,

    /**
     * Client-minted idempotency key for this shift start (finding S3).
     *
     * `POST /v1/shifts/start` treats this exactly as `POST /v1/trips/sync` treats its own
     * `client_uuid`: unique per tenant, and a repeat of one it has already seen returns the
     * original shift rather than opening a second one. That is what makes a queued, retried
     * shift-start safe — the drainer can resend a row whose response never arrived without any risk
     * of a driver ending up with two overlapping shifts.
     *
     * The field name is fixed by agreement with the backend workstream (B1), which has already
     * landed the server half. Do not rename it.
     *
     * Nullable/defaulted because [au.com.threesixty.cabdispatch.domain.ShiftRepository.getShift]
     * and any future caller that isn't going through the outbox has no need to mint one.
     */
    @SerialName("client_uuid") val clientUuid: String? = null,

    /**
     * Confirms a real shift-changeover: the vehicle already has another driver's shift open on
     * it (`POST /v1/shifts/start` returns 409 with a [ShiftConflictDetail] body when this is
     * false and a conflict exists — see `backend/app/api/v1/shifts.py`,
     * `backend/app/services/shift.py`). Passing `true` in the SAME request ends the conflicting
     * shift and opens this one (`backend/tests/test_shifts.py`). Defaulted false so every
     * existing call site — none of which know about handover — is unaffected; mirrors the
     * dashboard's `ShiftStartInput.force_handover` (`dashboard/src/pages/shifts/types.ts`) field
     * for field-naming so an operator sees identical behaviour from both surfaces.
     */
    @SerialName("force_handover") val forceHandover: Boolean = false,
)

/**
 * Structured `detail` on a 409 from `POST /v1/shifts/start` — the vehicle already has an open
 * shift under a different driver. Mirrors the dashboard's `ShiftConflictDetail`
 * (`dashboard/src/pages/shifts/types.ts`) field-for-field, so both surfaces describe the same
 * conflict identically. See [ShiftStartDto.forceHandover]'s doc for how a driver resolves this.
 */
@Serializable
data class ShiftConflictDetail(
    val message: String,
    @SerialName("conflicting_shift_id") val conflictingShiftId: String,
    @SerialName("conflicting_driver_id") val conflictingDriverId: String,
    @SerialName("conflicting_driver_name") val conflictingDriverName: String,
    @SerialName("conflicting_shift_start_at") val conflictingShiftStartAt: String,
)

@Serializable
data class ShiftEndDto(
    @SerialName("end_at") val endAt: String? = null,
    @SerialName("psl_owed") val pslOwed: String = "0",
    val reconciled: Boolean = true,
)

@Serializable
data class ShiftDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("driver_id") val driverId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String?,
    @SerialName("inspection_json") val inspectionJson: Map<String, String>?,
    @SerialName("trips_count") val tripsCount: Int,
    @SerialName("km_total") val kmTotal: String,
    @SerialName("cash_total") val cashTotal: String,
    @SerialName("card_total") val cardTotal: String,
    @SerialName("psl_owed") val pslOwed: String,
    val reconciled: Boolean,
    /** Zone-plotting fields (backend's `ShiftRead`, `app/models/shift.py`) — managed
     * exclusively via [ApiService.plotIntoZone]/[ApiService.unplotZone], never settable via
     * [ShiftStartDto]/[ShiftEndDto] above. Defaulted null so this DTO still decodes fine against
     * any older cached/mocked payload that predates them, same `ignoreUnknownKeys`/nullable-
     * default convention this file's header documents. */
    @SerialName("plotted_zone_id") val plottedZoneId: String? = null,
    @SerialName("plotted_at") val plottedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    /** Set by the backend (`app.services.shift.start_shift`) only when the request carried
     * [ShiftStartDto.deviceAndroidId] AND that device's paired vehicle disagreed with this
     * shift's [vehicleId] — a purely advisory, non-blocking heads-up (the shift above already
     * opened regardless). Null on every other shift, including one re-read later via
     * [ApiService.getShift], which never re-runs the check. Defaulted null for the same
     * older-payload-compat reason as [plottedZoneId] above. */
    @SerialName("device_mismatch_warning") val deviceMismatchWarning: String? = null,
)

@Serializable
data class ShiftReportDto(
    @SerialName("shift_id") val shiftId: String,
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("driver_id") val driverId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String?,
    @SerialName("duration_minutes") val durationMinutes: Double?,
    @SerialName("trips_count") val tripsCount: Int,
    @SerialName("km_total") val kmTotal: String,
    @SerialName("cash_total") val cashTotal: String,
    @SerialName("card_total") val cardTotal: String,
    @SerialName("total_takings") val totalTakings: String,
    @SerialName("psl_owed") val pslOwed: String,
    val reconciled: Boolean,
    @SerialName("inspection_json") val inspectionJson: Map<String, String>?,
    @SerialName("generated_at") val generatedAt: String,
)
