package au.com.threesixty.cabdispatch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit contract for the Cab Dispatch backend (`backend/app/main.py`, FastAPI).
 *
 * Base URL: see [au.com.threesixty.cabdispatch.data.AppContainer] — sourced from
 * `BuildConfig.API_BASE_URL`. In local dev this points at `http://10.0.2.2:8001`:
 * the Android emulator's virtual router aliases the *host machine's* localhost to
 * 10.0.2.2 (10.0.2.1 is the router itself, 10.0.2.3 is the first DNS server) — the
 * emulator is its own isolated network namespace, so `localhost`/`127.0.0.1` from
 * inside the emulator means the emulator itself, not your dev machine running
 * `uv run uvicorn app.main:app --port 8001`. A physical device on the same LAN
 * instead needs the host's real LAN IP; override `API_BASE_URL` per build type
 * (see app/build.gradle.kts) rather than hardcoding it here.
 *
 * All money fields are transported as JSON strings (server-side `Decimal`,
 * serialized as string — see shared/API_SUMMARY.md "Notes for downstream
 * agents"). DTOs below keep them as `String` and must NOT be parsed as
 * Float/Double — use a fixed-point/BigDecimal type when the fare-engine agent
 * wires real math on top of these.
 *
 * Endpoints are versioned under `/v1`; `Authorization: Bearer <token>` is
 * attached by an OkHttp interceptor configured in AppContainer, not per-call
 * here — see AppContainer.authTokenProvider.
 */
interface ApiService {

    // ---- Auth (shared/API_SUMMARY.md "Authentication") ----

    @POST("/v1/auth/login")
    suspend fun login(@Body body: LoginRequestDto): TokenResponseDto

    @POST("/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): TokenResponseDto

    @GET("/v1/auth/me")
    suspend fun me(): UserDto

    @POST("/v1/auth/logout")
    suspend fun logout()

    // ---- Devices (QR vehicle pairing, S1) ----

    @POST("/v1/fleet/devices/register")
    suspend fun registerDevice(@Body body: DeviceRegisterRequestDto): DeviceDto

    @POST("/v1/fleet/devices/{deviceId}/heartbeat")
    suspend fun deviceHeartbeat(
        @Path("deviceId") deviceId: String,
        @Body body: DeviceHeartbeatRequestDto,
    ): DeviceDto

    // ---- Tariffs (B6 fare engine reads these; server is the source of truth,
    // cached + signed on-device per B7 offline behaviour) ----

    @GET("/v1/tariffs/active")
    suspend fun activeTariff(
        @Query("region") region: String,
        @Query("at") at: String? = null,
    ): TariffDto

    @GET("/v1/fares-order/current")
    suspend fun currentFaresOrder(
        @Query("region") region: String = "urban",
        @Query("at") at: String? = null,
    ): TariffDto

    // ---- Trips (offline-first: app is source of truth, server validates —
    // B7. Sibling sync-engine agent drives tick/close/sync from the Room queue) ----

    @POST("/v1/trips")
    suspend fun createTrip(@Body body: TripCreateDto): TripDto

    @GET("/v1/trips")
    suspend fun listTrips(
        @Query("status") status: String? = null,
        @Query("type") type: String? = null,
        @Query("vehicle_id") vehicleId: String? = null,
        @Query("driver_id") driverId: String? = null,
        @Query("skip") skip: Int = 0,
        @Query("limit") limit: Int = 50,
    ): TripListResponseDto

    @GET("/v1/trips/{tripId}")
    suspend fun getTrip(@Path("tripId") tripId: String): TripDto

    @PATCH("/v1/trips/{tripId}/tick")
    suspend fun tickTrip(
        @Path("tripId") tripId: String,
        @Body body: TripTickRequestDto,
    ): TripDto

    @POST("/v1/trips/{tripId}/close")
    suspend fun closeTrip(
        @Path("tripId") tripId: String,
        @Body body: TripCloseRequestDto,
    ): TripDto

    /** Bulk replay after an offline period; idempotent on each item's `client_uuid`. */
    @POST("/v1/trips/sync")
    suspend fun syncTrips(@Body body: List<TripSyncItemDto>): TripSyncResponseDto

    // ---- Shifts (S1 open, S5 close/report) ----

    @POST("/v1/shifts/start")
    suspend fun startShift(@Body body: ShiftStartDto): ShiftDto

    @POST("/v1/shifts/{shiftId}/end")
    suspend fun endShift(
        @Path("shiftId") shiftId: String,
        @Body body: ShiftEndDto,
    ): ShiftDto

    @GET("/v1/shifts/{shiftId}/report")
    suspend fun shiftReport(@Path("shiftId") shiftId: String): ShiftReportDto

    // ---- Jobs (dispatch/job-offer broadcast+accept — Available Trips, S11/S12.
    // shared/API_SUMMARY.md + shared/openapi.json "/v1/jobs*", added for the wheel redesign's
    // dispatch scope, spec TCT-DRIVER-APP-01.md §9) ----
    //
    // `POST /v1/jobs` fans out a 20s-expiry JobOffer per currently-available driver; first
    // accept wins and expires every sibling offer for that job. Real-time push for new offers is
    // `WS /v1/jobs/live` (see [au.com.threesixty.cabdispatch.data.remote.RealtimeSocket] —
    // Retrofit has no first-class websocket support, so that endpoint is NOT here).

    @POST("/v1/jobs")
    suspend fun createJob(@Body body: JobCreateDto): JobDto

    @GET("/v1/jobs")
    suspend fun listJobs(
        @Query("status") status: String? = null,
        @Query("skip") skip: Int = 0,
        @Query("limit") limit: Int = 20,
    ): JobListResponseDto

    @GET("/v1/jobs/{jobId}")
    suspend fun getJob(@Path("jobId") jobId: String): JobDto

    /** Admin/dispatcher only server-side; soft-cancels (not a row delete) despite the verb. */
    @DELETE("/v1/jobs/{jobId}")
    suspend fun cancelJob(@Path("jobId") jobId: String): JobDto

    @GET("/v1/jobs/{jobId}/offers")
    suspend fun listJobOffers(@Path("jobId") jobId: String): List<JobOfferDto>

    /** First accept across all of a job's offers wins; losers are server-expired automatically. */
    @POST("/v1/jobs/{jobId}/offers/{offerId}/accept")
    suspend fun acceptJobOffer(
        @Path("jobId") jobId: String,
        @Path("offerId") offerId: String,
    ): JobOfferDto

    @POST("/v1/jobs/{jobId}/offers/{offerId}/decline")
    suspend fun declineJobOffer(
        @Path("jobId") jobId: String,
        @Path("offerId") offerId: String,
    ): JobOfferDto

    /** Driver's own "For Hire" self-toggle — the write half of the offer-matching rule (available
     * toggle AND open shift AND not mid-trip). Not gated to driver role server-side per the spec
     * doc, but this is the endpoint the Off-Duty/Available wheel slot should call. */
    @POST("/v1/jobs/availability")
    suspend fun setDriverAvailability(@Body body: DriverAvailabilityUpdateDto): DriverAvailabilityDto

    // ---- Messages (dispatch<->driver threads — S13/S14, spec §9) ----
    //
    // One thread per driver (`thread_id == driver_id`). Real-time push is
    // `WS /v1/messages/live?driver_id=` (see [RealtimeSocket] — not here, same reason as jobs).

    /** A `driver`-role caller always sends as themselves (server ignores [MessageCreateDto.driverId]
     * and substitutes the caller's own id); every other role must supply it. */
    @POST("/v1/messages")
    suspend fun sendMessage(@Body body: MessageCreateDto): MessageDto

    /** A `driver`-role caller may only list their own thread server-side. */
    @GET("/v1/messages")
    suspend fun listMessages(
        @Query("driver_id") driverId: String,
        @Query("skip") skip: Int = 0,
        @Query("limit") limit: Int = 50,
    ): MessageListResponseDto

    @POST("/v1/messages/{messageId}/read")
    suspend fun markMessageRead(@Path("messageId") messageId: String): MessageDto

    // ---- Duress (panic/safety — contextual overlays S28-S30, spec §8 rows 28-30) ----
    //
    // Role policy (see backend/app/api/v1/duress.py header): trigger/cancel/gps are any
    // authenticated user — the actions a driver's own device takes. escalate/close are
    // dispatcher-only (owner/admin/dispatcher) and deliberately NOT exposed here — a driver
    // device never escalates or resolves its own event, only backend/dispatcher does.

    /** Opens a duress event and starts its 10-second server-side cancel window (the deadline
     * itself lives in the untyped `escalation_log_json` blob server-side — deliberately not
     * modeled in [DuressEventDto], see that class's doc — so the window length is mirrored
     * client-side instead as [au.com.threesixty.cabdispatch.domain.DuressController.CANCEL_WINDOW_SECONDS]). */
    @POST("/v1/duress/trigger")
    suspend fun triggerDuress(@Body body: DuressTriggerRequestDto): DuressEventDto

    /** Only valid while the returned [DuressEventDto.status] is still `"open"` and the 10s
     * cancel window hasn't elapsed — a 409 past that point is expected/handled, not a bug. */
    @POST("/v1/duress/{eventId}/cancel")
    suspend fun cancelDuress(
        @Path("eventId") eventId: String,
        @Body body: DuressCancelRequestDto,
    ): DuressEventDto

    @GET("/v1/duress/{eventId}")
    suspend fun getDuressEvent(@Path("eventId") eventId: String): DuressEventDto

    /** Live GPS relay while an event is open/escalating — not persisted server-side, purely a
     * dashboard-live-feed broadcast (see backend's `GPSBroadcaster`). Best-effort: safe to ignore
     * failures, there is nothing to retry/queue against. */
    @POST("/v1/duress/{eventId}/gps")
    suspend fun postDuressGps(
        @Path("eventId") eventId: String,
        @Body body: DuressGpsPointDto,
    )

    // ---- Compliance Vault (read-only on-device — Profile > Compliance, spec §8 rows 20-21) ----
    //
    // Full CRUD (upload/edit/delete) is owner/admin/dispatcher-only server-side
    // (backend/app/api/v1/compliance.py `_WRITE_ROLES`) — a driver's tablet has no business
    // uploading calibration certs, it only ever reads its own vehicle's standing. Only the
    // dossier summary is wired here, not the raw per-document list/download endpoints.

    @GET("/v1/compliance/vehicles/{vehicleId}/dossier")
    suspend fun getComplianceDossier(@Path("vehicleId") vehicleId: String): ComplianceDossierDto
}

// ============================================================================
// DTOs — mirror shared/openapi.json component schemas 1:1 (field names/nullability
// kept identical so the JSON round-trips without custom (de)serializers). Kept in
// this file for the skeleton pass; split into data/remote/dto/*.kt if/when this
// file grows unwieldy.
// ============================================================================

@Serializable
data class LoginRequestDto(val email: String, val password: String)

@Serializable
data class RefreshRequestDto(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    val user: UserDto,
)

@Serializable
data class UserDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String?,
    val role: String,
    val name: String,
    val email: String,
    val status: String,
)

@Serializable
data class DeviceRegisterRequestDto(
    @SerialName("android_id") val androidId: String,
    @SerialName("pairing_code") val pairingCode: String,
    val model: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
)

@Serializable
data class DeviceHeartbeatRequestDto(
    val battery: Int? = null,
    val network: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
)

@Serializable
data class DeviceDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("android_id") val androidId: String,
    val model: String?,
    @SerialName("app_version") val appVersion: String?,
    @SerialName("vehicle_id") val vehicleId: String?,
    @SerialName("kiosk_locked") val kioskLocked: Boolean,
    @SerialName("force_update_pending") val forceUpdatePending: Boolean,
    @SerialName("last_seen_at") val lastSeenAt: String?,
    val battery: Int?,
    val network: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Mirrors `TariffRead` — money/rate fields are decimal-as-string, see file header. */
@Serializable
data class TariffDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String?,
    val name: String,
    val region: String,
    @SerialName("effective_from") val effectiveFrom: String,
    @SerialName("effective_to") val effectiveTo: String? = null,
    val booked: Boolean = false,
    @SerialName("flag_fall") val flagFall: String,
    @SerialName("peak_charge") val peakCharge: String = "0",
    @SerialName("dist_rate_1") val distRate1: String,
    @SerialName("dist_rate_2") val distRate2: String,
    @SerialName("night_rate_1") val nightRate1: String,
    @SerialName("night_rate_2") val nightRate2: String,
    @SerialName("holiday_rate_1") val holidayRate1: String = "0",
    @SerialName("holiday_rate_2") val holidayRate2: String = "0",
    @SerialName("waiting_rate_per_min") val waitingRatePerMin: String,
    @SerialName("dist_km_threshold") val distKmThreshold: String = "12",
    @SerialName("speed_threshold_kmh") val speedThresholdKmh: String = "26",
    @SerialName("maxi_multiplier") val maxiMultiplier: String = "1.5",
    @SerialName("multi_hire_pct") val multiHirePct: String = "0.75",
    @SerialName("psl_amount") val pslAmount: String = "1.32",
    @SerialName("surcharge_pct_cap") val surchargePctCap: String = "5.0",
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

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
    @SerialName("payment_method") val paymentMethod: String = "cash", // cash | card
    @SerialName("time_class") val timeClass: String = "day", // day | night | holiday
    @SerialName("is_peak") val isPeak: Boolean = false,
    val maxi: Boolean = false,
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

/** A single raw GPS/speed fix, as recorded by the in-vehicle meter. */
@Serializable
data class TelemetryPointDto(
    val lat: Double,
    val lng: Double,
    @SerialName("speed_kmh") val speedKmh: Double,
    val ts: String,
)

@Serializable
data class TripTickRequestDto(val points: List<TelemetryPointDto>)

@Serializable
data class TripCloseRequestDto(
    @SerialName("end_at") val endAt: String? = null,
    @SerialName("end_lat") val endLat: Double? = null,
    @SerialName("end_lng") val endLng: Double? = null,
    @SerialName("payment_method") val paymentMethod: String? = null, // cash | card
    @SerialName("surcharge_pct") val surchargePct: String? = null,
    @SerialName("cleaning_fee") val cleaningFee: String = "0",
    @SerialName("include_psl") val includePsl: Boolean = false,
    @SerialName("receipt_ref") val receiptRef: String? = null,
)

/**
 * A complete, self-contained trip payload uploaded after a period offline.
 * Carries its own `client_uuid` (idempotency key) and the raw `gps_trace`
 * recorded on-device so the server can independently recompute the fare and
 * check it against `device_total` (±1% variance tolerance, see spec B6).
 */
@Serializable
data class TripSyncItemDto(
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
    @SerialName("time_class") val timeClass: String = "day",
    @SerialName("is_peak") val isPeak: Boolean = false,
    val maxi: Boolean = false,
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
)

@Serializable
data class TripSyncResultItemDto(
    @SerialName("client_uuid") val clientUuid: String,
    val duplicate: Boolean,
    val trip: TripDto,
)

@Serializable
data class TripSyncResponseDto(val results: List<TripSyncResultItemDto>)

@Serializable
data class ShiftStartDto(
    @SerialName("driver_id") val driverId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("start_at") val startAt: String? = null,
    @SerialName("inspection_json") val inspectionJson: Map<String, String>? = null,
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
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
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

// ---- Messages DTOs — mirror shared/openapi.json MessageCreate/MessageRead 1:1. ----

/** Body for `POST /v1/messages`. [driverId] identifies whose thread the message belongs to —
 * required for dispatch-side senders (owner/admin/dispatcher); ignored (and replaced with the
 * caller's own id) for a `driver`-role sender. */
@Serializable
data class MessageCreateDto(
    @SerialName("driver_id") val driverId: String? = null,
    val body: String,
)

@Serializable
data class MessageDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("thread_id") val threadId: String,
    @SerialName("driver_id") val driverId: String,
    @SerialName("sender_type") val senderType: String, // dispatch | driver
    @SerialName("sender_user_id") val senderUserId: String?,
    val body: String,
    @SerialName("sent_at") val sentAt: String,
    @SerialName("read_at") val readAt: String?,
)

@Serializable
data class MessageListResponseDto(
    val items: List<MessageDto>,
    val total: Int,
    val skip: Int,
    val limit: Int,
)

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
