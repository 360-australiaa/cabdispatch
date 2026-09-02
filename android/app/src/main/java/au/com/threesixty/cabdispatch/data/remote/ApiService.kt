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
    suspend fun refresh(@Body body: RefreshRequestDto): TokenResponseDto

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
    @POST("/v1/fleet/devices/{deviceId}/heartbeat")
    suspend fun deviceHeartbeat(
        @Path("deviceId") deviceId: String,
        @Body body: DeviceHeartbeatRequestDto,
        @Header("X-Device-Secret") deviceSecret: String? = null,
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
     * rego filter is assumed/used here; the caller fetches the page and matches client-side. */
    @GET("/v1/fleet/vehicles")
    suspend fun listVehicles(
        @Query("skip") skip: Int = 0,
        // Backend caps this at 100 (checked live: 200 -> real 422 "Input should be less than or
        // equal to 100"). A tenant with a fleet bigger than one page is a real, silently-degrading
        // gap here — this call has no pagination loop — but matches this app's existing
        // best-effort posture elsewhere rather than adding one for a fleet-roster lookup this pass
        // wasn't scoped to build out fully.
        @Query("limit") limit: Int = 100,
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
}

// ============================================================================
// DTOs — mirror shared/openapi.json component schemas 1:1 (field names/nullability
// kept identical so the JSON round-trips without custom (de)serializers). Kept in
// this file for the skeleton pass; split into data/remote/dto/*.kt if/when this
// file grows unwieldy.
// ============================================================================

@Serializable
data class LoginRequestDto(val email: String, val password: String)

/** Body for [ApiService.driverLogin]. `driverCode` is the meter's "Driver ID" field — globally
 * unique, auto-generated per driver user, distinct from `email`/staff login. */
@Serializable
data class DriverLoginRequestDto(
    @SerialName("driver_code") val driverCode: String,
    val pin: String,
)

/**
 * Mirrors the backend's `TokenResponse | MfaRequiredResponse` union response_model for
 * `POST /v1/auth/driver-login` (shared/API_SUMMARY.md "POST /v1/auth/driver-login" +
 * "Admin MFA (TOTP)"). kotlinx.serialization has no first-class support for an ad hoc union
 * response like this, so both shapes' fields are folded into one DTO, all nullable —
 * `ignoreUnknownKeys = true` (data/JsonConfig.kt) makes it safe for either shape's JSON to land
 * here without the other shape's fields present.
 *
 * Callers MUST check [mfaRequired] first:
 * - `true` -> only [mfaToken] is populated; exchange it (+ a 6-digit TOTP code) via
 *   [ApiService.mfaLogin] for a real [TokenResponseDto]. [accessToken]/[user] are absent.
 * - `null`/`false` -> a normal token response; [accessToken]/[refreshToken]/[user] are populated
 *   exactly as [TokenResponseDto] would be.
 */
@Serializable
data class DriverLoginResponseDto(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val user: UserDto? = null,
    @SerialName("mfa_required") val mfaRequired: Boolean? = null,
    @SerialName("mfa_token") val mfaToken: String? = null,
)

/** Body for [ApiService.mfaLogin] — exchanges the short-lived `mfa_token` from a
 * [DriverLoginResponseDto] (or the staff-login equivalent) plus a 6-digit TOTP `code`. */
@Serializable
data class MfaLoginRequestDto(
    @SerialName("mfa_token") val mfaToken: String,
    val code: String,
)

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
    /** Relative on-disk path, per the backend's own doc — not a directly-loadable absolute
     * URL. `null` when no photo has ever been uploaded. Not used to build the image request
     * (`ApiService.getUserPhoto` is called by user id, not by this path) — kept only so a
     * `photo_url != null` check can drive "has a photo" UI state without an extra network round
     * trip. See `ui/screens/profile/ProfileViewModel.kt`. */
    @SerialName("photo_url") val photoUrl: String? = null,
    /**
     * The real backing field for a "VERIFIED" badge (2026-08-29, backend contract Part 2.1/10:
     * `"suitability_status == \"clear\""` is the concept a driver-verification badge should map
     * to — not a field literally named "verified"). Values beyond `"clear"` (e.g. pending/
     * flagged) are real but this app has no other UI for them yet — see
     * [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s header, which shows
     * VERIFIED only on an exact `"clear"` match and shows nothing (not a false claim) otherwise.
     * `null` is treated the same as "not clear" — never assumed verified by omission.
     */
    @SerialName("suitability_status") val suitabilityStatus: String? = null,
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
 * facing banner (this app has no self-update path — see that class's doc), and [locateRequested] is
 * answered by publishing a fresh position (see [PositionPublishRequestDto] /
 * [ApiService.publishPosition]). [rebootRequested] is deliberately left unconsumed here — see the
 * backend's own HONESTY NOTE on `POST /v1/fleet/devices/{id}/reboot`: actually rebooting the OS
 * needs device-owner-level Android permissions this app does not hold, so this stays a
 * backend-only command queue an admin can see pending, not something this DTO's presence should
 * be mistaken for "implemented" — see HANDOFF.md.
 */
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
)

/**
 * Body for [ApiService.publishPosition] (`POST /v1/fleet/positions`, backend's
 * `PositionPublishRequest`) — a device/tick handler's position report for one vehicle. Three call
 * sites, all best-effort/fire-and-forget: the MDM "locate" response
 * ([DeviceCommandHeartbeat][au.com.threesixty.cabdispatch.domain.DeviceCommandHeartbeat]),
 * the ambient 30s while-on-shift heartbeat
 * ([LivePositionHeartbeat][au.com.threesixty.cabdispatch.domain.LivePositionHeartbeat], Taxi Meter
 * SaaS Complete Blueprint §6.2.2 "vehicle.heartbeat"), and (separately, still unwired — see
 * HANDOFF.md "Availability broadcast not wired") the Idle screen's "For Hire" toggle. [status] has
 * no server-side enum constraint (backend: a plain `str`, `min_length=1, max_length=20`), just
 * documented examples ("available"/"on_trip"/"offline"/"break") — any short non-empty string
 * round-trips fine.
 */
/** One row of `GET /v1/fleet/vehicles` — only the fields [ApiService.listVehicles]'s one caller
 * actually needs (`rego` to match against, `id` to resolve to); the real response carries more
 * (vin/vehicle_class/status/...) that this app has no use for yet, left off rather than guessed. */
@Serializable
data class VehicleDto(
    val id: String,
    val rego: String,
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

/**
 * Mirrors `TariffRead` — money/rate fields are decimal-as-string, see file header.
 *
 * [signature] is only ever populated on [ApiService.activeTariff]'s response (backend's
 * `SignedTariffRead`, `TariffRead` + a `signature` field) — every other endpoint that returns
 * this shape (`currentFaresOrder`, plain tariff CRUD) serves the unsigned `TariffRead` and leaves
 * it absent, which `ignoreUnknownKeys`/a nullable default (see data/JsonConfig.kt) makes safe to
 * share one DTO for. See `au.com.threesixty.cabdispatch.security.canonicalTariffPayload` (the
 * Kotlin port of `backend/app/services/tariff_signing.canonical_tariff_payload`, the exact
 * byte-format this signs) and [au.com.threesixty.cabdispatch.sync.TariffCache.refresh] (where the
 * signature is actually checked, then run through [au.com.threesixty.cabdispatch.domain.fare.validateAgainstFaresOrder]
 * — Point to Point Transport (Fares) Order 2026, effective 1 June 2026 — before this DTO is
 * trusted/cached).
 *
 * None of this DTO's own field defaults below hardcode a stale rate figure from the superseded
 * Fares Order 2025 (no.2) — every actual rate field (`flag_fall`/`dist_rate_1`/`dist_rate_2`/
 * `night_rate_1`/`night_rate_2`/`waiting_rate_per_min`) is mandatory on the wire, with no
 * client-side default to go stale; only the non-rate structural defaults below (thresholds,
 * multipliers, the PSL flat amount) have literal defaults, and none of those changed in the 2026
 * Order.
 */
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
    // Point to Point Transport (Fares) Order 2026 cl 2(f): up to $124.14 — added server-side
    // alongside the 2026 rate-card pass. Defaults to that same figure so a tariff signed by an
    // older backend build (pre-field) still deserializes to the correct current cap rather than
    // "0".
    @SerialName("cleaning_fee_cap") val cleaningFeeCap: String = "124.14",
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val signature: String? = null,
)

/** Body for [ApiService.verifyAdminPin] — same PIN shape as the backend's
 * `VerifyAdminPinRequest`/`AdminPinSetRequest` (4-8 digits). */
@Serializable
data class VerifyAdminPinRequestDto(val pin: String)

/** Response for [ApiService.verifyAdminPin]. [configured] `false` means the tenant has never set
 * an admin PIN at all — kept distinct from [valid] `false` (a PIN is set but this one is wrong)
 * so a caller can tell "nothing set up yet" from "wrong PIN" rather than treating both the same
 * (i.e. rather than silently allowing a destructive action just because nothing's configured
 * yet). Callers MUST check [configured] explicitly, per shared/API_SUMMARY.md's "Admin PIN" note. */
@Serializable
data class VerifyAdminPinResponseDto(val valid: Boolean, val configured: Boolean)

/** Response for [ApiService.tariffSigningPublicKey] — the backend's `TariffSigningPublicKeyRead`.
 * [publicKey] is X.509 SubjectPublicKeyInfo DER, base64-encoded, matching
 * `au.com.threesixty.cabdispatch.security.RsaTariffSignatureVerifier`'s existing key-encoding
 * convention (see that class's doc) even though the actual algorithm here is Ed25519. */
@Serializable
data class TariffSigningPublicKeyDto(
    @SerialName("public_key") val publicKey: String,
    val algorithm: String = "Ed25519",
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

@Serializable
data class TripTickRequestDto(val points: List<TelemetryPointDto>)

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
    /** Zone-plotting fields (backend's `ShiftRead`, `app/models/shift.py`) — managed
     * exclusively via [ApiService.plotIntoZone]/[ApiService.unplotZone], never settable via
     * [ShiftStartDto]/[ShiftEndDto] above. Defaulted null so this DTO still decodes fine against
     * any older cached/mocked payload that predates them, same `ignoreUnknownKeys`/nullable-
     * default convention this file's header documents. */
    @SerialName("plotted_zone_id") val plottedZoneId: String? = null,
    @SerialName("plotted_at") val plottedAt: String? = null,
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
    /**
     * `job_type`/`distance_km`/`eta_min` (2026-08-29, backend contract Part 4.1/7) — server-computed
     * at job creation (straight-line haversine + a flat 30km/h heuristic per the backend's own
     * doc, NOT routed/live-traffic; the backend's own field comment flags this as an
     * approximation). `null` on a job created before this migration landed — callers must degrade
     * (e.g. [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s dispatch card
     * falls back to a live-GPS straight-line distance and omits ETA entirely when this is null,
     * rather than showing a stale/wrong number). `jobType` defaults `"booked"` server-side
     * (migration `9a9364f2c706`'s `server_default`).
     */
    @SerialName("job_type") val jobType: String? = null, // "booked" | "rank_hail"
    @SerialName("distance_km") val distanceKm: String? = null,
    @SerialName("eta_min") val etaMin: Int? = null,
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

/** One entry of `GET /v1/messages/templates` — mirrors `app.schemas.messages.MessageTemplateRead`
 * 1:1. [code] is the stable identifier passed to `POST /v1/messages/templates/{code}`; [label] is
 * the human-readable button text; [senderType] is `driver` or `dispatch` — this app should only
 * ever render the `driver`-typed entries as quick-tap buttons (a driver-role caller can't use a
 * dispatch-side code, see [ApiService.sendTemplateMessage]'s doc / the 400 it maps to). */
@Serializable
data class MessageTemplateDto(
    val code: String,
    val label: String,
    @SerialName("sender_type") val senderType: String, // dispatch | driver
)

/** Body for `POST /v1/messages/templates/{code}`. Same [MessageCreateDto.driverId] rule as a
 * free-text send. [note] is an optional free-text suffix — the UI should only surface an input
 * for it on the "other" template (see [ApiService.sendTemplateMessage]'s doc), though the backend
 * accepts it on any code. */
@Serializable
data class TemplateMessageCreateDto(
    @SerialName("driver_id") val driverId: String? = null,
    val note: String? = null,
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


// ---- Command Deck v2 additions (2026-08-27 redesign port) ----------------------------------

/** Mirrors `TariffPresetRead` (`backend/app/schemas/tariffs.py`). Only the fields the Tariff
 * Select screen renders are declared — `ignoreUnknownKeys` drops the rest safely. */
@Serializable
data class TariffPresetDto(
    val key: String,
    val label: String,
    val description: String,
    val defaults: TariffPresetDefaultsDto,
)

@Serializable
data class TariffPresetDefaultsDto(
    val region: String,
    val booked: Boolean,
    @SerialName("flag_fall") val flagFall: String,
    @SerialName("dist_rate_1") val distRate1: String,
    @SerialName("dist_rate_2") val distRate2: String,
    @SerialName("night_rate_1") val nightRate1: String,
    @SerialName("night_rate_2") val nightRate2: String,
    @SerialName("waiting_rate_per_min") val waitingRatePerMin: String,
)

/** Mirrors `TariffSuggestionRead`. */
@Serializable
data class TariffSuggestionDto(
    @SerialName("tariff_id") val tariffId: String,
    @SerialName("tariff_name") val tariffName: String,
    @SerialName("time_class") val timeClass: String,
    val reason: String,
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
