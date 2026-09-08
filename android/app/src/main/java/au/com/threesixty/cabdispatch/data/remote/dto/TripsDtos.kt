package au.com.threesixty.cabdispatch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for trips, the meter tick/close cycle, offline sync, vouchers and receipts.
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
data class TripCreateDto(
    @SerialName("client_uuid") val clientUuid: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("driver_id") val driverId: String,
    @SerialName("shift_id") val shiftId: String? = null,
    @SerialName("tariff_id") val tariffId: String,
    val type: String, // rank_hail | booked | airport_fixed | multi_hire
    @SerialName("start_at") val startAt: String? = null,
    @SerialName("start_lat") val startLat: Double,
    @SerialName("start_lng") val startLng: Double,
    @SerialName("payment_method") val paymentMethod: String = "cash", // cash | card | voucher | account | split_fare
    /** Required (non-empty) when [paymentMethod] == "voucher" — backend 422s otherwise. */
    @SerialName("voucher_code") val voucherCode: String? = null,
    /** Required (non-empty) when [paymentMethod] == "account" — backend 422s otherwise. Note:
     * `split_payments` deliberately has no field here — the backend's `TripCreate` schema doesn't
     * accept it either (a trip's total isn't known until close; split-fare is a close-time-only
     * payment method, see [TripCloseRequestDto.splitPayments]). */
    @SerialName("account_reference") val accountReference: String? = null,
    /**
     * Negotiated/fixed-fare total (2026-08-10 meter-polish pass, "Set Price" entry point) —
     * mirrors the backend's `TripCreate.negotiated_total` exactly (`Decimal | None`, validated
     * `[1.00, 500.00]` server-side via `app.services.fare_engine.validate_negotiated_total`).
     * `null` = normal metered trip (the default, unchanged for every existing call site). Settable
     * only at trip creation, same as the backend contract — there is deliberately no equivalent
     * field on [TripCloseRequestDto].
     */
    @SerialName("negotiated_total") val negotiatedTotal: String? = null,
    @SerialName("time_class") val timeClass: String = "day", // day | night | holiday
    @SerialName("is_peak") val isPeak: Boolean = false,
    val maxi: Boolean = false,
    /** See [TripEntity][au.com.threesixty.cabdispatch.data.local.entity.TripEntity.passengerCount]'s
     * doc (Point to Point Transport (Fares) Order 2026 compliance pass). Nullable-defaulted
     * (rather than required) per this file's own convention for a field added after this DTO
     * already had live callers. */
    @SerialName("passenger_count") val passengerCount: Int? = null,
    /** See [TripEntity][au.com.threesixty.cabdispatch.data.local.entity.TripEntity.wheelchairHiring]'s doc. */
    @SerialName("wheelchair_hiring") val wheelchairHiring: Boolean? = null,
    /** See [TripEntity][au.com.threesixty.cabdispatch.data.local.entity.TripEntity.airportRankRequestedMaxi]'s
     * doc (maxi-at-airport-rank fare-integrity fix, 2026-09-05). Nullable-defaulted per this file's
     * own convention for a field added after this DTO already had live callers. */
    @SerialName("airport_rank_requested_maxi") val airportRankRequestedMaxi: Boolean? = null,
    val tolls: String = "0",
    val extras: String = "0",
    @SerialName("gps_trace_ref") val gpsTraceRef: String? = null,
)

@Serializable
data class TripDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("client_uuid") val clientUuid: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("driver_id") val driverId: String,
    @SerialName("shift_id") val shiftId: String?,
    @SerialName("tariff_id") val tariffId: String,
    val type: String,
    val status: String,
    @SerialName("time_class") val timeClass: String,
    @SerialName("is_peak") val isPeak: Boolean,
    val maxi: Boolean,
    /** See [TripCreateDto.passengerCount]'s doc. Nullable-defaulted per this file's convention for
     * a field added after this DTO already had live callers. */
    @SerialName("passenger_count") val passengerCount: Int? = null,
    /** See [TripCreateDto.wheelchairHiring]'s doc. */
    @SerialName("wheelchair_hiring") val wheelchairHiring: Boolean? = null,
    /** See [TripCreateDto.airportRankRequestedMaxi]'s doc. */
    @SerialName("airport_rank_requested_maxi") val airportRankRequestedMaxi: Boolean? = null,
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String?,
    @SerialName("start_lat") val startLat: Double,
    @SerialName("start_lng") val startLng: Double,
    @SerialName("end_lat") val endLat: Double?,
    @SerialName("end_lng") val endLng: Double?,
    @SerialName("distance_m") val distanceM: Int,
    @SerialName("moving_s") val movingS: Int,
    @SerialName("waiting_s") val waitingS: Int,
    @SerialName("flag_fall") val flagFall: String,
    @SerialName("dist_amount") val distAmount: String,
    @SerialName("wait_amount") val waitAmount: String,
    @SerialName("peak_amount") val peakAmount: String,
    val tolls: String,
    val psl: String,
    val extras: String,
    val subtotal: String,
    val surcharge: String,
    val total: String,
    @SerialName("gst_component") val gstComponent: String,
    @SerialName("payment_method") val paymentMethod: String,
    @SerialName("gps_trace_ref") val gpsTraceRef: String?,
    @SerialName("max_fare_check_passed") val maxFareCheckPassed: Boolean,
    @SerialName("variance_pct") val variancePct: String?,
    @SerialName("receipt_ref") val receiptRef: String?,
    /** Blueprint 5.2.5 "Dispute" fields — mirrors backend `TripRead.flagged_for_review`/`review_notes`.
     * Defaulted so this DTO still decodes fine against any older cached/mocked payload that predates
     * them (`ignoreUnknownKeys`/nullable-default convention, see this file's header). */
    @SerialName("flagged_for_review") val flaggedForReview: Boolean = false,
    @SerialName("review_notes") val reviewNotes: String? = null,
    @SerialName("voucher_code") val voucherCode: String? = null,
    @SerialName("account_reference") val accountReference: String? = null,
    @SerialName("split_payments") val splitPayments: List<SplitPaymentEntryDto>? = null,
    /** See [TripCreateDto.negotiatedTotal]'s doc. Nullable-defaulted (not required) per this
     * file's own convention for a field added after this DTO already had live callers — a cached/
     * mocked payload from before this pass still decodes fine. Not independently verified against
     * a real `TripRead` response body from a running backend; the backend agent's own contract
     * notes only explicitly list `negotiated_total` on `Trip`/`TripCreate`/`TripSyncItem`, not
     * `TripRead` by name — assumed present here since every other model field this project's
     * `TripRead` schemas expose so far has been 1:1 with the model, but flagged as the one
     * unverified assumption in this DTO. */
    @SerialName("negotiated_total") val negotiatedTotal: String? = null,
    /**
     * Driver tip (Close & Pay "tips" pass) — mirrors the backend's `Trip.tip_amount`. Nullable-
     * defaulted (not required) per this file's own convention for a field added after this DTO
     * already had live callers. Deliberately NOT folded into [total]/[gstComponent] — see the
     * backend `Trip.tip_amount` doc comment (deviation #6): a tip is a voluntary, non-fare
     * amount, never part of the regulated fare/GST figures this DTO otherwise mirrors 1:1.
     */
    @SerialName("tip_amount") val tipAmount: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class TripListResponseDto(
    val items: List<TripDto>,
    val total: Int,
    val skip: Int,
    val limit: Int,
)

/** `GET /v1/trips/earnings/today` response (backend contract Part 4.3, 2026-08-29). Money fields
 * are decimal-as-string per this file's header convention. [pctChange] `null` means the backend
 * had no yesterday baseline to compare against — render "—", never a fabricated 0%. */
@Serializable
data class DriverEarningsTodayReadDto(
    @SerialName("driver_id") val driverId: String,
    val date: String,
    @SerialName("today_total") val todayTotal: String,
    @SerialName("yesterday_total") val yesterdayTotal: String,
    @SerialName("pct_change") val pctChange: Double? = null,
    @SerialName("trips_completed_today") val tripsCompletedToday: Int,
)

/** A single raw GPS/speed fix, as recorded by the in-vehicle meter. */
@Serializable
data class TelemetryPointDto(
    val lat: Double,
    val lng: Double,
    @SerialName("speed_kmh") val speedKmh: Double,
    val ts: String,
)

/**
 * [destLat]/[destLng] (`dest_lat`/`dest_lng` on the wire — exact backend field names, per the
 * sibling backend-track agent's addition to `TripTickRequest`) are the driver's *picked*
 * destination, sourced from the same [au.com.threesixty.cabdispatch.data.local.entity.TripEntity]
 * columns [au.com.threesixty.cabdispatch.data.repository.TripRepository.updateDropoff] writes
 * (`endLat`/`endLng`) — see that method's own doc for why those columns hold "where we're
 * heading" while a trip is open. `Double?`, default `null`: honest "not known yet" for every tick
 * before [au.com.threesixty.cabdispatch.ui.screens.hired.MeterNavViewModel.selectDestination] has
 * run, exactly the same "real null, never a placeholder" convention this file's header and
 * [PositionPublishRequestDto]'s `battery`/`network` already follow — never fabricate a coordinate
 * to fill these in early.
 *
 * HONEST GAP (found during this pass, not introduced by it): as of 2026-09-05, [ApiService.tickTrip]
 * itself has **no call site anywhere in this app** — verified by grepping every production source
 * file, not assumed. [au.com.threesixty.cabdispatch.data.repository.TripRepository] holds no
 * [ApiService] reference at all, and
 * [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel]'s periodic `doPersistTick` only
 * calls [au.com.threesixty.cabdispatch.data.repository.TripRepository.tick] (Room-only, per that
 * method's own doc — "this method never touches the network either way"). This app's trip flow is
 * deliberately offline-first end to end: [ApiService.createTrip] is likewise never called, an open
 * trip has no server-side id at all, and the only network trip write that exists today is the bulk
 * `POST /v1/trips/sync` [SyncOutboxEntity]-driven replay [au.com.threesixty.cabdispatch.sync
 * .OutboxDrainer] fires once a trip closes. So these two fields are added here to match the
 * backend's wire contract byte-for-byte and are ready the moment a live per-tick call exists, but
 * as of this pass nothing constructs or sends a [TripTickRequestDto] in production code — wiring
 * an actual "give an open trip a server id, then PATCH it periodically" mechanism is a materially
 * larger architecture change than threading two fields through an existing call, and is out of
 * scope for this pass; flagging it explicitly here rather than quietly leaving these fields to look
 * load-bearing when they are not yet wired to anything.
 */
@Serializable
data class TripTickRequestDto(
    val points: List<TelemetryPointDto>,
    @SerialName("dest_lat") val destLat: Double? = null,
    @SerialName("dest_lng") val destLng: Double? = null,
)

/** One leg of a split-fare payment — mirrors the backend's `SplitPaymentItem`
 * (`backend/app/schemas/trips.py`) exactly: [method] is one of `cash|card|voucher|account`
 * (deliberately excludes `split_fare` itself — no nesting, matches the backend's `SubPaymentMethod`
 * Literal), [amount] is decimal-as-string per this file's header rule. A trip's `split_payments`
 * list must sum, to the cent, to its final total — enforced server-side at close time (backend's
 * `SplitPaymentMismatchError` -> 422); this app additionally checks it client-side before enabling
 * "Confirm & close trip" for Split Fare (see
 * [au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayUiState.ReadyToClose.canConfirm]) so
 * a driver isn't sent to the server just to be told the split doesn't add up. */
@Serializable
data class SplitPaymentEntryDto(
    val method: String,
    val amount: String,
)

@Serializable
data class TripCloseRequestDto(
    @SerialName("end_at") val endAt: String? = null,
    @SerialName("end_lat") val endLat: Double? = null,
    @SerialName("end_lng") val endLng: Double? = null,
    @SerialName("payment_method") val paymentMethod: String? = null, // cash | card | voucher | account | split_fare
    @SerialName("voucher_code") val voucherCode: String? = null,
    @SerialName("account_reference") val accountReference: String? = null,
    @SerialName("split_payments") val splitPayments: List<SplitPaymentEntryDto>? = null,
    @SerialName("surcharge_pct") val surchargePct: String? = null,
    @SerialName("cleaning_fee") val cleaningFee: String = "0",
    @SerialName("include_psl") val includePsl: Boolean = false,
    @SerialName("receipt_ref") val receiptRef: String? = null,
    /** See [TripDto.tipAmount]'s doc. `null` = no tip recorded for this close. */
    @SerialName("tip_amount") val tipAmount: String? = null,
)

/**
 * A complete, self-contained trip payload uploaded after a period offline.
 * Carries its own `client_uuid` (idempotency key) and the raw `gps_trace`
 * recorded on-device so the server can independently recompute the fare and
 * check it against `device_total` (±1% variance tolerance, see spec B6).
 *
 * **Fixed (2026-08-03, reconciliation pass):** [voucherCode]/[accountReference]/[splitPayments]
 * now round-trip all the way through — the backend's `TripSyncItem` Pydantic schema
 * (`backend/app/schemas/trips.py`) was extended to declare these three fields and validate/persist
 * them in `app.api.v1.trips.sync_trips` (same voucher-redemption / account-reference / split-sum
 * checks `close_trip` already applied to the online close path), closing what had been a real gap
 * where these fields were silently dropped on `POST /v1/trips/sync` — the ONLY network call this
 * app's offline-first close flow actually makes (see
 * [au.com.threesixty.cabdispatch.sync.SyncWorker]). Verified server-side via
 * `backend/tests/test_trips.py::test_sync_voucher_payment_persists_voucher_code` and
 * `::test_sync_split_fare_matching_sum_persists_split_payments`.
 */
@Serializable
data class TripSyncItemDto(
    /** True when this trip ran on fabricated GPS (see TripEntity.simulated). Sent so the
     * server can keep a test trip out of real revenue and compliance reporting -- without it
     * a simulated fare is indistinguishable from a real one. */
    val simulated: Boolean = false,
    @SerialName("client_uuid") val clientUuid: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("driver_id") val driverId: String,
    @SerialName("shift_id") val shiftId: String? = null,
    @SerialName("tariff_id") val tariffId: String,
    val type: String,
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    @SerialName("start_lat") val startLat: Double,
    @SerialName("start_lng") val startLng: Double,
    @SerialName("end_lat") val endLat: Double? = null,
    @SerialName("end_lng") val endLng: Double? = null,
    @SerialName("payment_method") val paymentMethod: String = "cash",
    @SerialName("voucher_code") val voucherCode: String? = null,
    @SerialName("account_reference") val accountReference: String? = null,
    @SerialName("split_payments") val splitPayments: List<SplitPaymentEntryDto>? = null,
    /**
     * See [TripCreateDto.negotiatedTotal]'s doc. Unlike [voucherCode]/[accountReference]/
     * [splitPayments] above (whose own doc comment on this same class documents a real gap where
     * the backend's `TripSyncItem` schema didn't carry them until the 2026-08-03 reconciliation
     * pass fixed it), `negotiated_total` was declared on `TripSyncItem` from the start by the
     * backend agent that added it this same session (2026-08-10) — per that agent's own contract
     * notes, `TripSyncItem.negotiated_total` shares the exact same validation as `TripCreate`'s.
     * No known gap here, but genuinely unverified against a real running backend either way (see
     * this file's/HANDOFF.md's standing "never run through kotlinc" caveat).
     */
    @SerialName("negotiated_total") val negotiatedTotal: String? = null,
    @SerialName("time_class") val timeClass: String = "day",
    @SerialName("is_peak") val isPeak: Boolean = false,
    val maxi: Boolean = false,
    /** See [TripCreateDto.passengerCount]'s doc. */
    @SerialName("passenger_count") val passengerCount: Int? = null,
    /** See [TripCreateDto.wheelchairHiring]'s doc. */
    @SerialName("wheelchair_hiring") val wheelchairHiring: Boolean? = null,
    /** See [TripCreateDto.airportRankRequestedMaxi]'s doc. This is the field that actually matters
     * for the flag to reach the server: `POST /v1/trips/sync` is the ONLY network call this app's
     * offline-first close flow makes (see this class's own doc above) — without this, a maxi-at-
     * airport-rank trip's [deviceTotal] (which correctly includes the surcharge on-device) could
     * diverge from the server's independent recompute (which has no way to know the flag was set)
     * and get rejected for exceeding the sync variance tolerance. */
    @SerialName("airport_rank_requested_maxi") val airportRankRequestedMaxi: Boolean? = null,
    val tolls: String = "0",
    val extras: String = "0",
    @SerialName("cleaning_fee") val cleaningFee: String = "0",
    @SerialName("surcharge_pct") val surchargePct: String? = null,
    @SerialName("include_psl") val includePsl: Boolean = false,
    @SerialName("gps_trace") val gpsTrace: List<TelemetryPointDto> = emptyList(),
    @SerialName("gps_trace_ref") val gpsTraceRef: String? = null,
    @SerialName("receipt_ref") val receiptRef: String? = null,
    /** The total the offline device computed on-vehicle. */
    @SerialName("device_total") val deviceTotal: String,
    /**
     * See [TripDto.tipAmount]'s doc. This is the field that actually matters for tips to reach
     * the server: `POST /v1/trips/sync` is the ONLY network call this app's offline-first close
     * flow makes (see this class's own doc above) — a tip entered on-device round-trips here, not
     * through [ApiService.closeTrip]/[TripCloseRequestDto], which has no real call site.
     */
    @SerialName("tip_amount") val tipAmount: String? = null,
)

/** Body for [ApiService.flagTrip] (`PATCH /v1/trips/{id}/flag`, backend's `TripFlagRequest`) — the
 * "Dispute" button (Trip Detail screen). `flagged=true` (the default) requires a non-blank [reason]
 * (backend 422s `DisputeReasonRequiredError` without one); this app never sends `flagged=false` —
 * only a staff role may clear a flag server-side (`backend/app/api/v1/trips.py::flag_trip`), and
 * this is a driver app. */
@Serializable
data class TripFlagRequestDto(
    val flagged: Boolean = true,
    val reason: String? = null,
)

@Serializable
data class TripSyncResultItemDto(
    @SerialName("client_uuid") val clientUuid: String,
    val duplicate: Boolean,
    val trip: TripDto,
)

@Serializable
data class TripSyncResponseDto(val results: List<TripSyncResultItemDto>)

/** Mirrors the backend's `VoucherRead` (`backend/app/api/v1/vouchers.py`) — money/dates as
 * decimal-as-string/ISO strings per this file's header convention. [redeemedByTripId] added for
 * the Vouchers pane's USED tab (Phase G, `squishy-herding-iverson.md`) — the Close & Pay payment
 * grid that first declared this DTO never needed it, only the redeemed/expiry fields. */
@Serializable
data class VoucherDto(
    val id: String,
    val code: String,
    @SerialName("value_aud") val valueAud: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("redeemed_at") val redeemedAt: String? = null,
    @SerialName("redeemed_by_trip_id") val redeemedByTripId: String? = null,
)

@Serializable
data class VoucherPageDto(
    val items: List<VoucherDto>,
    val total: Int,
    val skip: Int,
    val limit: Int,
)

/** Mirrors the backend's `CorporateAccountRead` (`backend/app/api/v1/corporate_accounts.py`). */
@Serializable
data class CorporateAccountDto(
    val id: String,
    val reference: String,
    @SerialName("company_name") val companyName: String,
    val active: Boolean,
)

@Serializable
data class CorporateAccountPageDto(
    val items: List<CorporateAccountDto>,
    val total: Int,
    val skip: Int,
    val limit: Int,
)

/** Mirrors `ReceiptEmailRequest`/`ReceiptSmsRequest`. */
@Serializable
data class ReceiptEmailRequestDto(@SerialName("to_email") val toEmail: String)

@Serializable
data class ReceiptSmsRequestDto(@SerialName("to_phone") val toPhone: String)

/** Mock-aware responses (see `ReceiptEmailResponse`'s own doc server-side): `mock=true` +
 * `would_send_to` when no provider key is configured; real-send fields otherwise. */
@Serializable
data class ReceiptEmailResponseDto(
    val mock: Boolean,
    @SerialName("would_send_to") val wouldSendTo: String? = null,
    @SerialName("to_email") val toEmail: String? = null,
    @SerialName("receipt_ref") val receiptRef: String? = null,
    @SerialName("pdf_relative_path") val pdfRelativePath: String,
)

@Serializable
data class ReceiptSmsResponseDto(
    val mock: Boolean,
    @SerialName("would_send_to") val wouldSendTo: String? = null,
    @SerialName("to_phone") val toPhone: String? = null,
    @SerialName("receipt_ref") val receiptRef: String? = null,
    @SerialName("pdf_relative_path") val pdfRelativePath: String,
)
