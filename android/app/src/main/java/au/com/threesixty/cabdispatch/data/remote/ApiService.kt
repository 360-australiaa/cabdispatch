package au.com.threesixty.cabdispatch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

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

    /**
     * Driver ID (`driver_code`) + PIN login for the meter — the real
     * driver-facing counterpart to [login], see shared/API_SUMMARY.md
     * "POST /v1/auth/driver-login". Return type is [DriverLoginResponseDto],
     * not [TokenResponseDto], because the backend's `response_model` is a
     * union (`TokenResponse | MfaRequiredResponse`) depending on whether the
     * driver account has MFA enabled — see that DTO's doc for how callers
     * disambiguate.
     */
    @POST("/v1/auth/driver-login")
    suspend fun driverLogin(@Body body: DriverLoginRequestDto): DriverLoginResponseDto

    /**
     * Second step of the MFA two-step exchange, shared by staff [login] and
     * [driverLogin] alike (`shared/API_SUMMARY.md` "Admin MFA (TOTP)") — a
     * short-lived `mfa_token` (from a [DriverLoginResponseDto] with
     * [DriverLoginResponseDto.mfaRequired] true) plus a 6-digit TOTP code for
     * a real [TokenResponseDto]. Unlike [login]/[driverLogin] this endpoint
     * has no MFA branch of its own — it always returns [TokenResponseDto].
     */
    @POST("/v1/auth/mfa/login")
    suspend fun mfaLogin(@Body body: MfaLoginRequestDto): TokenResponseDto

    @POST("/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): RefreshResponseDto

    @GET("/v1/auth/me")
    suspend fun me(): UserDto

    @POST("/v1/auth/logout")
    suspend fun logout()

    // ---- Devices (QR vehicle pairing, S1) ----

    @POST("/v1/fleet/devices/register")
    suspend fun registerDevice(@Body body: DeviceRegisterRequestDto): DeviceDto

    /**
     * [deviceSecret], when non-null, is sent as `X-Device-Secret` — the device-scoped credential
     * the backend added 2026-08-29 specifically so this call can authenticate with NO driver
     * session at all (see [au.com.threesixty.cabdispatch.domain.DeviceCommandHeartbeat]'s "real
     * precondition" section for why that mattered: a parked, logged-off, or freshly-rebooted
     * tablet has no [au.com.threesixty.cabdispatch.data.AppContainer.accessToken] in memory, so
     * every poll used to 401 until a driver signed in online). [okhttp3.Interceptor] still adds
     * `Authorization` when a token happens to be in memory too — the backend accepts either, so
     * sending both is harmless; it's what makes the bearer path keep working unchanged for a
     * device paired before this field existed (no secret == this header omitted == old behaviour).
     * `null` -> the header is omitted, not sent empty, via [retrofit2.http.Header]'s null handling.
     */
    /**
     * `POST /v1/fleet/devices/{id}/locate-response` — this DEVICE answering an admin's locate
     * request with its own real fix, which is also what clears `locate_requested` server-side.
     *
     * Replaces answering on [publishPosition]. That is a VEHICLE endpoint: it needs the fleet UUID
     * of the car this tablet is currently bound to, which means a live driver session and a
     * current binding. A parked, logged-off tablet has neither — and that is precisely the tablet
     * an operator reaching for "locate" is trying to find. A tablet holding a binding to a
     * since-deleted vehicle got a 404 from it instead, which surfaced in Settings ▸ About as
     * "Location request failed to send — HTTP 404 not found".
     *
     * Authenticated by [deviceSecret], so it works with nobody signed in.
     */
    @POST("/v1/fleet/devices/{deviceId}/locate-response")
    suspend fun deviceLocateResponse(
        @Path("deviceId") deviceId: String,
        @Body body: DeviceLocateResponseDto,
        @Header("X-Device-Secret") deviceSecret: String? = null,
    ): DeviceDto

    /**
     * `POST /v1/fleet/devices/{id}/command-ack` — this device reporting that it has carried out a
     * queued command, which clears that command's flag. Without it an admin queues a restart and
     * watches it read "Pending" for the life of the row.
     */
    @POST("/v1/fleet/devices/{deviceId}/command-ack")
    suspend fun deviceCommandAck(
        @Path("deviceId") deviceId: String,
        @Body body: DeviceCommandAckDto,
        @Header("X-Device-Secret") deviceSecret: String? = null,
    ): DeviceDto

    @POST("/v1/fleet/devices/{deviceId}/heartbeat")
    suspend fun deviceHeartbeat(
        @Path("deviceId") deviceId: String,
        @Body body: DeviceHeartbeatRequestDto,
        @Header("X-Device-Secret") deviceSecret: String? = null,
    ): DeviceDto

    /**
     * `GET /v1/fleet/devices/me` (B5, 2026-09-08) — this tablet's OWN device row, addressed by
     * nothing but the `X-Device-Secret` it already holds. No `deviceId` in the path and no bearer
     * token, which is the point: it is the one read a parked, logged-off tablet can make to find
     * out who it is and which vehicle the depot has bound it to.
     *
     * Not a duplicate of [deviceHeartbeat] even though both answer with a [DeviceDto]: the
     * heartbeat needs a `deviceId` to address, so it cannot help a tablet that has lost the id but
     * still holds the secret (prefs cleared by an OS storage sweep, a restore-from-backup, or an
     * install that predates [DevicePairingStore] persisting it). [au.com.threesixty.cabdispatch.domain.DeviceCommandHeartbeat.start]
     * calls this exactly once in that case, and never otherwise — see its doc for why this is not
     * folded into the 60 s poll.
     *
     * The secret is an explicit parameter rather than something the auth interceptor attaches,
     * matching every other device-secret call on this interface.
     */
    @GET("/v1/fleet/devices/me")
    suspend fun deviceMe(
        @Header("X-Device-Secret") deviceSecret: String,
    ): DeviceDto

    /**
     * Device-facing check against the tenant's server-side admin PIN (see
     * `POST /v1/tenants/{id}/admin-pin`, owner-only, for the set/overwrite side — not called from
     * this app). The hash is never sent to the device, only the boolean result. Backs
     * [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel.attemptFactoryReset]'s
     * server-verified factory-reset gate. `configured=false` (tenant has never set a PIN) is
     * distinct from `valid=false` (PIN set, wrong value) — callers must check
     * [VerifyAdminPinResponseDto.configured] explicitly, per shared/API_SUMMARY.md.
     */
    @POST("/v1/fleet/devices/{deviceId}/verify-admin-pin")
    suspend fun verifyAdminPin(
        @Path("deviceId") deviceId: String,
        @Body body: VerifyAdminPinRequestDto,
    ): VerifyAdminPinResponseDto

    // ---- Live positions (MDM "locate" response — S6's heartbeat above reads
    // DeviceDto.locateRequested back; when set, SettingsViewModel answers it by publishing the
    // device's current real fix through this same endpoint, which is also what feeds the fleet
    // dashboard's Live Map. Domain note: this endpoint's literal path (`/v1/fleet/positions`, not
    // `/v1/fleet/devices/.../positions`) belongs to the separate Live Ops router, not the Devices
    // one above — see shared/API_SUMMARY.md and backend/app/api/v1/live_ops.py. ----

    @POST("/v1/fleet/positions")
    suspend fun publishPosition(@Body body: PositionPublishRequestDto): PositionPublishResponseDto

    /** `GET /v1/fleet/vehicles` — tenant-scoped vehicle roster (the same list a dispatcher sees
     * on Fleet & Drivers), callable with a driver-role token too (checked live: a real driver JWT
     * gets a real 200, tenant-filtered same as staff). Added so [au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindViewModel.bindVehicle]
     * can resolve a manually-typed rego to the real vehicle UUID [publishPosition] actually
     * requires in [PositionPublishRequestDto.vehicleId] — found live: that endpoint 404s
     * "Vehicle not found" on a rego string, only ever accepting the real `id`. No server-side
     * rego filter is assumed/used here; the caller fetches the page and matches client-side.
     * Second caller since Phase H (2026-09-03): `ui/screens/profile/ProfileViewModel.kt`'s
     * `loadVehicleDetail` fetches this same page and matches on [au.com.threesixty.cabdispatch.domain.DriverSession.vehicleUuid]/
     * `.vehicleId` to read [VehicleDto.make]/`.model`/`.registrationExpiry`/`.insuranceExpiry` for
     * the Profile screen — same "no pagination loop" caveat applies to that caller too. */
    @GET("/v1/fleet/vehicles")
    suspend fun listVehicles(
        @Query("skip") skip: Int = 0,
        // Backend caps this at 100 (checked live: 200 -> real 422 "Input should be less than or
        // equal to 100"). A tenant with a fleet bigger than one page is a real, silently-degrading
        // gap here — this call has no pagination loop — but matches this app's existing
        // best-effort posture elsewhere rather than adding one for a fleet-roster lookup this pass
        // wasn't scoped to build out fully.
        @Query("limit") limit: Int = 100,
        /**
         * `?rego_exact=` (B5, 2026-09-08) — case-insensitive EXACT rego match, 0 or 1 row back.
         * Added for [au.com.threesixty.cabdispatch.domain.ApiVehicleUuidResolver], which is the
         * reason the caveat above was written: resolving one rego no longer depends on that car
         * falling inside the first 100-row window. `null` (the default) leaves every other caller —
         * Profile's vehicle-detail read, which genuinely wants the page — exactly as it was. A
         * backend older than B5 ignores the unknown parameter and answers with the ordinary first
         * page, so the caller still matches client-side rather than trusting the filter blindly.
         */
        @Query("rego_exact") regoExact: String? = null,
    ): VehiclePageDto

    // ---- Tariffs (B6 fare engine reads these; server is the source of truth,
    // cached + signed on-device per B7 offline behaviour) ----

    /** Response includes an Ed25519 `signature` field (see [TariffDto.signature]) — the one
     * tariff response in this domain that's signed, since this is the endpoint devices poll to
     * refresh their cached tariff (see [au.com.threesixty.cabdispatch.sync.TariffCache]). */
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

    /** The Ed25519 public key that verifies [activeTariff]'s [TariffDto.signature] (X.509
     * SubjectPublicKeyInfo DER, base64-encoded). Deliberately no auth requirement server-side —
     * public keys aren't secret — so this is safe to call before/without a bearer token; see
     * [au.com.threesixty.cabdispatch.sync.TariffSigningKeyCache]. */
    @GET("/v1/tariffs/signing-public-key")
    suspend fun tariffSigningPublicKey(): TariffSigningPublicKeyDto

    /** Named tariff presets (MTI parity / blueprint 5.2.3) — powers the v2 Tariff Select screen
     * (`17b`, Command Deck redesign). Mirrors `GET /v1/tariffs/presets` -> `list[TariffPresetRead]`. */
    @GET("/v1/tariffs/presets")
    suspend fun tariffPresets(): List<TariffPresetDto>

    /** Auto-suggest the best-matching tariff for a position (blueprint 9.1) — v2 Tariff Select's
     * "suggested" chip. Mirrors `GET /v1/tariffs/suggest`. */
    @GET("/v1/tariffs/suggest")
    suspend fun suggestTariff(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("vehicle_class") vehicleClass: String? = null,
    ): TariffSuggestionDto

    // ---- Toll roads (real NSW toll registry — `backend/app/api/v1/toll_roads.py`; read-only,
    // platform-wide reference data, same as tariffs above. See
    // [au.com.threesixty.cabdispatch.sync.TollRegistryCache] for how the on-device auto-toll
    // detector caches this for offline use — it never calls these two directly on the fare
    // engine's hot path, only [au.com.threesixty.cabdispatch.sync.TollRegistryCache.refresh]. ----

    /** `GET /v1/toll-roads` — every road's identity + CURRENT price only (no gantries, no price
     * history — see [TollRoadDto.currentPrice]'s doc for why the device only ever caches the
     * in-force revision). [TollRegistryCache][au.com.threesixty.cabdispatch.sync.TollRegistryCache.refresh]
     * follows this with one [tollRoadDetail] call per road id to also fetch gantry coordinates. */
    @GET("/v1/toll-roads")
    suspend fun tollRoads(): List<TollRoadDto>

    /** `GET /v1/toll-roads/{road_id}` — adds this one road's real gantry coordinates
     * ([TollRoadDetailDto.gantries]) on top of everything [tollRoads] already returns. */
    @GET("/v1/toll-roads/{roadId}")
    suspend fun tollRoadDetail(@Path("roadId") roadId: String): TollRoadDetailDto

    // ---- Trips (offline-first: app is source of truth, server validates —
    // B7. Sibling sync-engine agent drives tick/close/sync from the Room queue) ----

    @POST("/v1/trips")
    suspend fun createTrip(@Body body: TripCreateDto): TripDto

    /**
     * `shift_id`/`start_at_from`/`start_at_to` (2026-08-29, Captain Taxis dashboard pass — see
     * backend's own contract doc, Part 4.2) are additive filters on top of the existing params;
     * `null` (the default) omits each from the query exactly as before this pass, so every
     * existing call site is unaffected. Used by [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s
     * shift-scoped "TRIPS — N Completed / M Active" stat: one call with `status = "closed"`, one
     * with `status = "open"`, both scoped to the current shift via `shiftId`, reading only
     * [TripListResponseDto.total] off each.
     */
    @GET("/v1/trips")
    suspend fun listTrips(
        @Query("status") status: String? = null,
        @Query("type") type: String? = null,
        @Query("vehicle_id") vehicleId: String? = null,
        @Query("driver_id") driverId: String? = null,
        @Query("shift_id") shiftId: String? = null,
        @Query("start_at_from") startAtFrom: String? = null,
        @Query("start_at_to") startAtTo: String? = null,
        @Query("skip") skip: Int = 0,
        @Query("limit") limit: Int = 50,
    ): TripListResponseDto

    /**
     * `GET /v1/trips/earnings/today` (new, 2026-08-29 — backend contract Part 4.3). Sydney-local
     * calendar day, not UTC. [DriverEarningsTodayRead.pctChange] is `null` when there is no
     * yesterday baseline to compare against — callers MUST treat `null` as "hide the comparison",
     * never as `0`. Read only for its trend text
     * ([DeckHomeScreen][au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen] keeps
     * showing the existing Room-backed [au.com.threesixty.cabdispatch.domain.TodayStats.earningsTotal]
     * as the primary $ figure — offline-safe, already the established convention — and only adds
     * this call's `pctChange` as an annotation once it loads).
     */
    @GET("/v1/trips/earnings/today")
    suspend fun earningsToday(@Query("driver_id") driverId: String): DriverEarningsTodayReadDto

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

    /** Blueprint 5.2.5's "Dispute" button (Trip Detail screen, [au.com.threesixty.cabdispatch.ui.screens.tripdetail.TripDetailViewModel.submitDispute])
     * — flags a *closed* trip for operator review with a driver-entered reason. Response is the full
     * [TripDto] (backend's `TripRead`). A driver may flag (never clear) a trip where they are its own
     * `driver_id` — see backend's `app/api/v1/trips.py::flag_trip` for the complete role rule: 403 for a
     * non-owning, non-staff caller; 409 if the trip isn't closed yet; 422 without a non-empty `reason`. */
    @PATCH("/v1/trips/{tripId}/flag")
    suspend fun flagTrip(
        @Path("tripId") tripId: String,
        @Body body: TripFlagRequestDto,
    ): TripDto

    /**
     * Post-close passenger rating (new Rate Passenger screen, 2026-09-04) — mirrors
     * `POST /v1/trips/{trip_id}/rating` (`backend/app/api/v1/ratings.py::rate_trip`). Called
     * *after* [closeTrip] has already returned, same "settle payment first, then hand the tablet
     * to the passenger" sequencing the backend route's own doc describes. [tripId] is the trip's
     * real server id ([au.com.threesixty.cabdispatch.data.local.entity.TripEntity.serverId]), not
     * its [au.com.threesixty.cabdispatch.data.local.entity.TripEntity.clientUuid] — same
     * "needs a real server id" gate [flagTrip]'s own callers (`TripDetailViewModel.submitDispute`)
     * already enforce. Response is [TripRatingDto] (backend's `TripRatingRead`), 201 on success;
     * a 409 means either the trip isn't closed yet or (per that route's own docstring) it has
     * *already* been rated — one rating per trip, enforced server-side.
     */
    @POST("/v1/trips/{tripId}/rating")
    suspend fun rateTrip(
        @Path("tripId") tripId: String,
        @Body body: TripRatingCreateDto,
    ): TripRatingDto

    /** Emails the trip's PDF receipt (Command Deck v2 Receipt screen, `22`). Mirrors
     * `POST /v1/trips/{trip_id}/receipt/email` — mock-aware response (`mock=true` when no
     * SendGrid key is configured server-side; still generates/returns the PDF path). */
    @POST("/v1/trips/{tripId}/receipt/email")
    suspend fun emailReceipt(
        @Path("tripId") tripId: String,
        @Body body: ReceiptEmailRequestDto,
    ): ReceiptEmailResponseDto

    /** SMSes the trip's receipt link — `POST /v1/trips/{trip_id}/receipt/sms`, same mock-aware
     * convention as [emailReceipt]. */
    @POST("/v1/trips/{tripId}/receipt/sms")
    suspend fun smsReceipt(
        @Path("tripId") tripId: String,
        @Body body: ReceiptSmsRequestDto,
    ): ReceiptSmsResponseDto

    /** Driver/vehicle accreditation-expiry feed (`GET /v1/fleet/compliance-expiry`) — the v2
     * Profile screen's compliance-warning cards. */
    @GET("/v1/fleet/compliance-expiry")
    suspend fun complianceExpiry(
        @Query("skip") skip: Int = 0,
        @Query("limit") limit: Int = 50,
    ): ComplianceExpiryPageDto

    /** Fatigue alerts (`GET /v1/fatigue-alerts`) — the v2 Shift screen's fatigue strip. */
    @GET("/v1/fatigue-alerts")
    suspend fun fatigueAlerts(
        @Query("skip") skip: Int = 0,
        @Query("limit") limit: Int = 20,
    ): FatigueAlertPageDto

    // ---- App releases (real Android OTA self-update, 2026-09-06 — see
    // domain/AppUpdateChecker.kt and docs/OTA_UPDATE_ROLLOUT.md). Publishing
    // (`POST /v1/platform/app-releases`) is platform-owner-only and has no client
    // call site in this app — only the device-facing read below is used here. ----

    /** `GET /v1/app-releases/latest` — the highest `version_code` among published, `is_active`
     * releases. Any authenticated tenant/device bearer token (not platform-owner-gated — this is a
     * read). Compared against `BuildConfig.VERSION_CODE` by
     * [au.com.threesixty.cabdispatch.domain.AppUpdateChecker.checkForUpdate]; 404s (surfaced as a
     * thrown [retrofit2.HttpException], caught by that function's `runCatching`) when no release
     * has ever been published. */
    @GET("/v1/app-releases/latest")
    suspend fun latestAppRelease(
        /**
         * `X-Device-Secret` (B5, 2026-09-08 — `require_device_or_user` in
         * `backend/app/api/v1/app_releases.py`). Sent when this tablet holds one, so the device the
         * `force_update_pending` flag actually targets — a parked, logged-off tablet with no bearer
         * token anywhere in memory — can check for an update at all. `null` keeps the old
         * bearer-only path byte-for-byte, which is what a device paired before the secret existed
         * still uses. Retrofit omits a null `@Header` entirely, so an older backend never even sees
         * the header; [au.com.threesixty.cabdispatch.domain.AppUpdateChecker] handles the other
         * half of that (a `401` from a backend that has the header but not B5's handling of it).
         */
        @Header("X-Device-Secret") deviceSecret: String? = null,
    ): LatestAppReleaseDto

    // ---- Vouchers / Corporate Accounts (Close & Pay payment-grid pass, real backend endpoints
    // added by the SaaS-platform Phase 3 voucher-ledger workstream, commit 1f93840) ----

    /**
     * Mirrors `GET /v1/vouchers` (`backend/app/api/v1/vouchers.py`); [skip]/[limit] defaults match
     * the backend's own. Two call sites: [au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayViewModel]
     * uses `redeemed = false, limit = 1` for the Close & Pay VOUCHER button's real "N Available"
     * count, and [au.com.threesixty.cabdispatch.ui.screens.vouchers.VouchersPaneContent] (Phase G)
     * calls it unfiltered (`redeemed = null, limit = 200`) for the real Available/Used/Expired
     * browse screen, bucketing client-side since this endpoint has no expiry filter. A failed/
     * loading call must never fabricate a count or a voucher list at either call site.
     */
    @GET("/v1/vouchers")
    suspend fun listVouchers(
        @Query("redeemed") redeemed: Boolean? = null,
        @Query("skip") skip: Int = 0,
        @Query("limit") limit: Int = 200,
    ): VoucherPageDto

    /**
     * Backs the Close & Pay ACCOUNT button's real active-count/balance indicator — mirrors
     * `GET /v1/corporate-accounts` (`backend/app/api/v1/corporate_accounts.py`). Same
     * never-fabricate-on-failure rule as [listVouchers].
     */
    @GET("/v1/corporate-accounts")
    suspend fun listCorporateAccounts(
        @Query("active") active: Boolean? = null,
        @Query("skip") skip: Int = 0,
        @Query("limit") limit: Int = 200,
    ): CorporateAccountPageDto

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

    /** Single-shift read (`backend/app/api/v1/shifts.py::get_shift`, any authenticated tenant
     * user) — added for the Plot Zone screen's "currently plotted in" indicator
     * ([au.com.threesixty.cabdispatch.ui.screens.zones.PlotZoneViewModel]), which needs
     * [ShiftDto.plottedZoneId] for the driver's own current shift on screen load. Not used by
     * any pre-existing call site — [startShift]/[endShift]/[shiftReport] above never needed a
     * plain re-read of one shift by id before this. */
    @GET("/v1/shifts/{shiftId}")
    suspend fun getShift(@Path("shiftId") shiftId: String): ShiftDto

    // ---- Zones (named dispatch zones, "plot into a zone", live per-zone demand stats — matches
    // a real competitor taxi meter's (MTI) zone-based demand screens, backend/app/api/v1/zones.py.
    // Role policy per that router's own docstring: list/stats/plot/unplot are all any
    // authenticated tenant user (a driver acting on their own current shift for plot/unplot,
    // identity-scoped server-side via the bearer token — no driver_id is sent from this app);
    // admin zone CRUD (create/update/delete) is owner/admin-only and deliberately NOT exposed
    // here, a driver's tablet only ever reads the zone list/stats and plots itself. ----

    /** Zone directory for the Plot screen's zone list — name/number per row, per
     * [au.com.threesixty.cabdispatch.ui.screens.zones.PlotZoneScreen]. */
    @GET("/v1/zones")
    suspend fun listZones(
        @Query("skip") skip: Int = 0,
        @Query("limit") limit: Int = 200,
    ): ZoneListResponseDto

    /** Live per-zone demand table for the Statistics screen — see
     * [au.com.threesixty.cabdispatch.ui.screens.zones.ZoneStatisticsViewModel] for the polling
     * loop that calls this every 15-30s while that screen is visible. Registered as `GET
     * /v1/zones/stats` server-side ahead of `GET /v1/zones/{zone_id}` specifically so `"stats"`
     * isn't captured as a zone id path segment — see `app/api/v1/zones.py`'s own comment. */
    @GET("/v1/zones/stats")
    suspend fun zoneStats(): List<ZoneStatsDto>

    /** Plots the calling driver's own currently-open shift into [zoneId] — backend 404s if the
     * zone doesn't exist for this tenant, 409s ([ZonePlotReadDto] never returned in that case) if
     * the caller has no currently-open shift. No request body — identity/shift come from the
     * bearer token server-side, same as [ApiService.logout]'s bodyless `@POST`. */
    @POST("/v1/zones/{zoneId}/plot")
    suspend fun plotIntoZone(@Path("zoneId") zoneId: String): ZonePlotReadDto

    /** Clears the calling driver's own current shift's plot, if any — a no-op (not an error) if
     * they weren't plotted into anything, per `app.services.zones.unplot`'s own contract. */
    @POST("/v1/zones/unplot")
    suspend fun unplotZone(): ZonePlotReadDto

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

    /** Canned quick-tap template menu (driver-side: "No Job"/"Recall"/"Job Query"/"Other";
     * dispatch-side: quick-status templates) — not tenant-specific, just requires an
     * authenticated caller. Fetch once and cache client-side per
     * `app.api.v1.messages.list_templates`'s doc. */
    @GET("/v1/messages/templates")
    suspend fun listMessageTemplates(): List<MessageTemplateDto>

    /** Quick-tap send: resolves [code] to a canned template and creates a real message through
     * the same path as [sendMessage] — shows up in the thread/live socket identically to a
     * free-text send. [TemplateMessageCreateDto.note] is an optional free-text suffix, primarily
     * for the driver-side "other" template. Same [MessageCreateDto.driverId] sender-attribution
     * rule as [sendMessage]: ignored for a `driver`-role caller. */
    @POST("/v1/messages/templates/{code}")
    suspend fun sendTemplateMessage(
        @Path("code") code: String,
        @Body body: TemplateMessageCreateDto,
    ): MessageDto

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

    /** Uploads a captured duress audio recording (multipart, `file` field —
     * `backend/app/api/v1/duress.py#upload_audio`). Driver-device-callable per that router's
     * role policy, same as [triggerDuress]/[cancelDuress]/[postDuressGps]. See
     * [au.com.threesixty.cabdispatch.domain.duress.DuressAudioRecorder] for where the file comes
     * from and [au.com.threesixty.cabdispatch.domain.DuressController.stopAndUploadAudio] for the
     * only call site. Response is the updated [DuressEventDto] (`audio_ref` now set) — not
     * currently read by anything on this device, the call is fire-and-forget from the caller's
     * point of view. */
    @Multipart
    @POST("/v1/duress/{eventId}/audio")
    suspend fun uploadDuressAudio(
        @Path("eventId") eventId: String,
        @Part file: MultipartBody.Part,
    ): DuressEventDto

    /** Cabin-camera still-frame upload (duress snapshot gallery, 2026-08-27 — backend/dashboard
     * already shipped, see `android/HANDOFF.md`). Mirrors
     * `POST /v1/duress/{event_id}/snapshot` — multipart `file` field, optional `captured_at`
     * (ISO 8601) query param; response is `{id, event_id, captured_at, created_at}`, none of
     * which this device needs to act on — the upload firing is what matters, same as
     * [uploadDuressAudio]. */
    @Multipart
    @POST("/v1/duress/{eventId}/snapshot")
    suspend fun uploadDuressSnapshot(
        @Path("eventId") eventId: String,
        @Part file: MultipartBody.Part,
        @Query("captured_at") capturedAt: String? = null,
    ): DuressSnapshotDto

    // ---- Compliance Vault (read-only on-device — Profile > Compliance, spec §8 rows 20-21) ----
    //
    // Full CRUD (upload/edit/delete) is owner/admin/dispatcher-only server-side
    // (backend/app/api/v1/compliance.py `_WRITE_ROLES`) — a driver's tablet has no business
    // uploading calibration certs, it only ever reads its own vehicle's standing. Only the
    // dossier summary is wired here, not the raw per-document list/download endpoints.

    @GET("/v1/compliance/vehicles/{vehicleId}/dossier")
    suspend fun getComplianceDossier(@Path("vehicleId") vehicleId: String): ComplianceDossierDto

    // ---- Driver photo (Profile screen, 2026-08-10 driver-photo pass) ----
    //
    // Backend contract (`app/api/v1/users.py`): POST is multipart (`file` field), **self-or-staff
    // gated** — any authenticated user may upload their OWN photo (matching this app's actual
    // call site: the signed-in user updating their own Profile photo), and staff
    // (owner/admin/dispatcher) may additionally upload on behalf of any user in their tenant.
    // **Fixed during integration verification** — the endpoint originally shipped staff-only
    // gated, which would have 403'd this exact call site for every real driver-role account;
    // caught and fixed server-side (see backend/app/api/v1/users.py's own doc comment on
    // upload_user_photo, and backend/tests/test_users.py::test_driver_can_upload_their_own_photo)
    // rather than left as a standing client-side risk. GET has no gate at all beyond tenant
    // membership (any authenticated tenant user), used here to load whatever photo (if any) is
    // already on file when the Profile screen first opens.

    /** `POST /v1/users/{userId}/photo` — multipart `file` field. Returns the updated
     * [UserDto] (`photo_url` now set). See this section's header comment for the real
     * staff-role-gate risk. */
    @Multipart
    @POST("/v1/users/{userId}/photo")
    suspend fun uploadUserPhoto(
        @Path("userId") userId: String,
        @Part file: MultipartBody.Part,
    ): UserDto

    /** `GET /v1/users/{userId}/photo` — raw image bytes (backend `FileResponse`), not JSON,
     * hence the plain [okhttp3.ResponseBody] return type + [Streaming] (avoids buffering the whole
     * image into memory before [okhttp3.ResponseBody.byteStream] is read) rather than a DTO. 404s
     * when the user has no photo yet or the on-disk file is missing — callers
     * ([au.com.threesixty.cabdispatch.ui.screens.profile.ProfileViewModel]) treat that like every
     * other "nothing there yet" case in this app: fall back to the initials avatar, never crash. */
    @Streaming
    @GET("/v1/users/{userId}/photo")
    suspend fun getUserPhoto(@Path("userId") userId: String): ResponseBody

    // ---- Driver engagement (`backend/app/api/v1/me.py`, commit 58ccfcf) — the dashboard's
    // WALLET BALANCE / RATING / ANNOUNCEMENTS / INCENTIVE PROGRESS tiles. All four are scoped to
    // the calling driver by the bearer token (no driver_id parameter — the backend resolves it),
    // and all four are read-only from this app: a DRIVER cannot post wallet lines (that's the
    // owner/admin-gated `POST /v1/wallet/transactions`), so nothing here ever writes. DTOs in
    // DriverEngagementDtos.kt; called only through
    // [au.com.threesixty.cabdispatch.domain.DriverEngagementRepository]. ----

    /** `GET /v1/me/wallet` — derived balance + the [limit] most recent ledger lines (backend
     * default 20, max 100). */
    @GET("/v1/me/wallet")
    suspend fun myWallet(@Query("limit") limit: Int = 20): WalletDto

    /** `GET /v1/me/rating` — average/count + the [limit] most recent ratings (backend default 10,
     * max 100). `average_stars` is null until the first rating exists. */
    @GET("/v1/me/rating")
    suspend fun myRating(@Query("limit") limit: Int = 10): RatingDto

    /** `GET /v1/me/announcements` — only currently-live announcements, newest first. */
    @GET("/v1/me/announcements")
    suspend fun myAnnouncements(): AnnouncementListDto

    /** `GET /v1/me/incentives` — live incentives with this driver's derived progress. */
    @GET("/v1/me/incentives")
    suspend fun myIncentives(): IncentiveProgressListDto
}

// ============================================================================
// The DTOs these endpoints exchange used to live below this line. Phase 0 (P0.4)
// moved them, verbatim and in the same package, into data/remote/dto/*Dtos.kt,
// grouped by domain: Auth, Fleet, Tariffs, Trips, Shifts, Jobs, Messages, Duress
// and Zones. DriverEngagementDtos.kt (which already sat outside this file) is the
// engagement group and was left where it is.
//
// Same package means no import changed anywhere. The conventions in this file's
// header still govern every one of them: fields mirror shared/openapi.json 1:1 so
// the JSON round-trips without custom (de)serializers, and money stays a String.
// ============================================================================
