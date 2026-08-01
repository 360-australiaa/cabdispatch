package au.com.threesixty.cabdispatch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
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
