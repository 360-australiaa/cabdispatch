package au.com.threesixty.cabdispatch.ui.screens.settings

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.remote.DeviceHeartbeatRequestDto
import au.com.threesixty.cabdispatch.data.remote.DeviceRegisterRequestDto
import au.com.threesixty.cabdispatch.data.remote.MapboxOfflineRegion
import au.com.threesixty.cabdispatch.data.remote.PositionPublishRequestDto
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.data.remote.VerifyAdminPinRequestDto
import au.com.threesixty.cabdispatch.domain.GpsQuality
import au.com.threesixty.cabdispatch.domain.GpsQualityClassifier
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.location.RegionResolver
import au.com.threesixty.cabdispatch.hardware.printing.PrinterDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

// GpsQuality moved to au.com.threesixty.cabdispatch.domain.GpsQuality (2026-09-02, Home-dashboard
// redesign pass) so au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardViewModel can
// share the exact same accuracy tiers instead of duplicating them — see that file's own doc.
enum class NetworkStatus { OFFLINE, CELLULAR, WIFI, OTHER }
enum class ForceUpdateStatus { UNKNOWN_NO_DEVICE, UNKNOWN_OFFLINE, UP_TO_DATE, REQUIRED }

/** Mirrors [MapboxOfflineRegion.DownloadState] but as a UI-friendly type (no Mapbox SDK types
 * leaking into the state a Composable reads) — see that class's doc for the actual download. */
sealed interface OfflineMapDownloadState {
    data object NotStarted : OfflineMapDownloadState
    data class Downloading(val progressPercent: Int) : OfflineMapDownloadState
    /** [regionLabel] is a driver-facing name ("Sydney metro" / "Karachi metro") for whichever
     * region [MapboxOfflineRegion.downloadRegionNear] actually picked — was a hardcoded "Sydney
     * metro" string in SettingsScreen regardless of the real region until 2026-08-28. */
    data class Completed(val regionLabel: String) : OfflineMapDownloadState
    data class Failed(val message: String) : OfflineMapDownloadState
}

/**
 * Outcome of the last MDM "locate" response attempt — see
 * [SettingsViewModel.respondToLocateRequest]. [Idle] (no locate request seen since this screen
 * opened) is the normal case and deliberately not surfaced in [SettingsScreen]'s DiagnosticsCard,
 * so a driver's screen isn't showing locate-related text on every ordinary visit — only once an
 * admin has actually asked for this device's location does anything appear here.
 */
sealed interface LocateResponseState {
    data object Idle : LocateResponseState
    data object Sent : LocateResponseState
    data object NoFixYet : LocateResponseState
    data object NoVehicleBound : LocateResponseState
    data class Failed(val message: String) : LocateResponseState
}

data class SettingsUiState(
    val gpsQuality: GpsQuality = GpsQuality.NO_FIX,
    val gpsAccuracyM: Float? = null,
    val networkStatus: NetworkStatus = NetworkStatus.OFFLINE,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val forceUpdateStatus: ForceUpdateStatus = ForceUpdateStatus.UNKNOWN_NO_DEVICE,
    val printerDiscovering: Boolean = false,
    val discoveredPrinters: List<PrinterDevice> = emptyList(),
    val pairedPrinter: PrinterDevice? = null,
    val fareSchedule: TariffDto? = null,
    val fareScheduleLoading: Boolean = true,
    val factoryResetError: String? = null,
    val factoryResetInProgress: Boolean = false,
    val factoryResetComplete: Boolean = false,
    val offlineMapDownload: OfflineMapDownloadState = OfflineMapDownloadState.NotStarted,
    val locateResponse: LocateResponseState = LocateResponseState.Idle,
    val pairMeter: PairMeterState = PairMeterState.Idle,
    /** Driver's local self-declaration that the bound vehicle has 5+ seats — see
     * [au.com.threesixty.cabdispatch.domain.MaxiVehicleStore]'s doc. Loaded from that store in
     * [SettingsViewModel.init], updated via [SettingsViewModel.setMaxiVehicle]. */
    val isMaxiVehicle: Boolean = false,
)

/** Real meter/device pairing (2026-08-28 — backend spec: `POST /v1/fleet/devices/register`,
 * device.id persisted so [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel]'s
 * existing heartbeat call finally has a non-null [au.com.threesixty.cabdispatch.domain.SessionHolder.deviceId]
 * to fire against). See [SettingsViewModel.submitPairingCode]. */
sealed interface PairMeterState {
    data object Idle : PairMeterState
    data object Submitting : PairMeterState
    data class Success(val vehicleId: String?) : PairMeterState
    /** [message] is shown verbatim — for the 409 "vehicle has an open shift" case this is the
     * server's own explanatory text (spec: "show the server's message text directly"). */
    data class Error(val message: String) : PairMeterState
}

/** FastAPI's default error-body shape, e.g. `{"detail":"Invalid driver code or PIN"}` — same
 * convention this backend uses everywhere (see driver-login's 401). */
@Serializable
private data class PairErrorDto(val detail: String? = null)

/**
 * S6 — Settings/Diagnostics (spec B5). [AndroidViewModel] (not a plain
 * [androidx.lifecycle.ViewModel]) because GPS/network diagnostics need a
 * [Context] — the only screen in this batch that does.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                pollGps()
                pollNetwork()
                delay(GPS_NETWORK_POLL_INTERVAL_MS)
            }
        }
        loadDeviceStatus()
        loadFareSchedule()
        _uiState.update {
            it.copy(
                pairedPrinter = AppContainer.receiptPrinterGateway.pairedDevice,
                isMaxiVehicle = AppContainer.maxiVehicleStore.isMaxiVehicle(),
            )
        }
    }

    /**
     * Updates the driver's local maxi-vehicle declaration — see [SettingsUiState.isMaxiVehicle]'s
     * doc and [au.com.threesixty.cabdispatch.domain.MaxiVehicleStore]'s own doc for why this is a
     * per-device self-declaration, not fleet-registry data. New method, no existing call site
     * touched.
     */
    fun setMaxiVehicle(value: Boolean) {
        AppContainer.maxiVehicleStore.setMaxiVehicle(value)
        _uiState.update { it.copy(isMaxiVehicle = value) }
    }

    // --- GPS quality ---
    // TODO(location/fare-engine sibling agent): once a real fused/Kalman-
    // filtered location source lands for the fare engine (see
    // au.com.threesixty.cabdispatch.domain.StubSpeedSource's TODO), consider
    // reading its last fix here too for consistency, instead of a separate
    // raw LocationManager read — for a diagnostics indicator, though, a
    // direct last-known-fix + accuracy read is perfectly adequate on its own.
    private fun pollGps() {
        val context = getApplication<Application>()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            _uiState.update { it.copy(gpsQuality = GpsQuality.PERMISSION_DENIED, gpsAccuracyM = null) }
            return
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val fix = listOfNotNull(
            runCatching { locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
            runCatching { locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
        ).minByOrNull { it.accuracy }

        // Same thresholds as before this pass, now shared with the Home dashboard's status strip
        // via GpsQualityClassifier — see that object's own doc. Behavior-preserving: granted==true
        // here always (the early-return above already handled the false case), fix?.accuracy null
        // maps to NO_FIX exactly as the old inline `if (fix == null)` branch did.
        _uiState.update {
            it.copy(gpsQuality = GpsQualityClassifier.classify(granted, fix?.accuracy), gpsAccuracyM = fix?.accuracy)
        }
    }

    // --- Network status ---
    private fun pollNetwork() {
        val context = getApplication<Application>()
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = connectivityManager?.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val status = when {
            caps == null -> NetworkStatus.OFFLINE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkStatus.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkStatus.CELLULAR
            else -> NetworkStatus.OTHER
        }
        _uiState.update { it.copy(networkStatus = status) }
    }

    // --- App version / force update / MDM "locate" ---
    private fun loadDeviceStatus() {
        val deviceId = SessionHolder.deviceId
        if (deviceId == null) {
            _uiState.update { it.copy(forceUpdateStatus = ForceUpdateStatus.UNKNOWN_NO_DEVICE) }
            return
        }
        viewModelScope.launch {
            val result = runCatching {
                AppContainer.apiService.deviceHeartbeat(
                    deviceId,
                    DeviceHeartbeatRequestDto(appVersion = BuildConfig.VERSION_NAME),
                )
            }
            result.onSuccess { device ->
                _uiState.update {
                    it.copy(
                        forceUpdateStatus = if (device.forceUpdatePending) {
                            ForceUpdateStatus.REQUIRED
                        } else {
                            ForceUpdateStatus.UP_TO_DATE
                        },
                    )
                }
                // HANDOFF.md: "MDM 'locate' command" — previously locate_requested was a
                // backend-only flag nothing on the device acted on. Wired here, the one place this
                // screen already reads it back. device.rebootRequested is deliberately NOT acted
                // on (see DeviceDto's doc / backend HONESTY NOTE) — real OS reboot needs
                // device-owner permissions this app doesn't hold, so it stays a backend-only queue.
                if (device.locateRequested) {
                    respondToLocateRequest()
                }
            }.onFailure {
                _uiState.update { it.copy(forceUpdateStatus = ForceUpdateStatus.UNKNOWN_OFFLINE) }
            }
        }
    }

    /**
     * Answers an admin's MDM "locate" request (`Device.locateRequested`, read back on the
     * heartbeat above) by publishing this device's current real position through the same
     * live-position pipeline the fleet dashboard's Live Map already watches
     * (`POST /v1/fleet/positions` — [ApiService.publishPosition][au.com.threesixty.cabdispatch.data.remote.ApiService.publishPosition],
     * shared/API_SUMMARY.md "Live Ops"), so a dispatcher sees a fresh pin appear/move as evidence
     * the request was answered.
     *
     * No acknowledge/clear step: the only endpoint that flips `locate_requested` back off is
     * `POST /v1/fleet/devices/{id}/locate`, which is admin-only server-side
     * (`backend/app/api/v1/fleet.py::set_device_locate`) — this device's own JWT (driver or staff
     * role) is never an admin, so it structurally cannot call it. Per this pass's brief, a fresh
     * position publish is treated as sufficient evidence on its own — the dispatcher watching Live
     * Map sees the pin, which is the actual thing they're waiting on. The flag simply stays set
     * server-side until an admin clears it from the dashboard, which just means this method
     * re-publishes on every subsequent heartbeat while it's still set — reasonable behaviour for a
     * "tell me where you are" request either way, not a bug.
     *
     * Best-effort and silent-on-failure (beyond [LocateResponseState] diagnostics), matching every
     * other background call in this file: [SessionHolder.session]'s [vehicleId] being unset or
     * [AppContainer.speedSource]'s [locationFix][au.com.threesixty.cabdispatch.domain.SpeedSource.locationFix]
     * not having a fix yet (no permission, cold start, no signal) both mean there's nothing honest
     * to publish yet, so this skips rather than sending a fabricated position.
     *
     * Known limitation, flagged rather than left implicit: [loadDeviceStatus] only runs once, when
     * this ViewModel is created (i.e. whenever the driver opens S6/Settings) — there's no
     * periodic/background heartbeat anywhere in this app yet. A "Locate" request only gets
     * answered the next time S6 happens to be opened, not the instant an admin sets the flag.
     * Closing that gap would mean a periodic background heartbeat (e.g. WorkManager, mirroring
     * [au.com.threesixty.cabdispatch.sync.SyncWorker]'s pattern) — a materially bigger change than
     * "wire the existing heartbeat flow", left as a real, open follow-up rather than silently
     * implied to already work continuously.
     */
    private suspend fun respondToLocateRequest() {
        val vehicleId = SessionHolder.session.value?.vehicleId
        if (vehicleId == null) {
            _uiState.update { it.copy(locateResponse = LocateResponseState.NoVehicleBound) }
            return
        }
        val fix = AppContainer.speedSource.locationFix.value
        if (fix == null) {
            _uiState.update { it.copy(locateResponse = LocateResponseState.NoFixYet) }
            return
        }
        runCatching {
            AppContainer.apiService.publishPosition(
                PositionPublishRequestDto(
                    vehicleId = vehicleId,
                    lat = fix.lat,
                    lng = fix.lng,
                    // Deliberately a fixed placeholder, not a guess: this call site only knows "an
                    // admin asked where this device is", not the driver's real
                    // available/on-trip/offline status — that's the Idle screen's separate,
                    // still-unwired "For Hire" toggle (HANDOFF.md "Availability broadcast not
                    // wired"). No server-side enum constraint on `status` (see
                    // PositionPublishRequestDto's doc), so this is a safe, honest value until that
                    // toggle publishes a real one.
                    status = LOCATE_RESPONSE_STATUS,
                ),
            )
        }.onSuccess {
            _uiState.update { it.copy(locateResponse = LocateResponseState.Sent) }
        }.onFailure { error ->
            _uiState.update {
                it.copy(locateResponse = LocateResponseState.Failed(error.message ?: "Unknown error"))
            }
        }
    }

    // --- Passenger-facing fare schedule (cl.15 display requirement) ---
    private fun loadFareSchedule() {
        viewModelScope.launch {
            // Real GPS-derived region (2026-08-03, location/region-detection pass) — was
            // hardcoded "urban" here since S6 had no live GPS-region lookup of its own; now reads
            // the same [RegionResolver] every other region-hardcoded call site was switched to
            // (see that object's doc for why a distance-from-Sydney-CBD circle, not a real
            // polygon/geofence lookup — neither exists to call yet). A one-shot snapshot of
            // whatever fix [AppContainer.speedSource] has *right now* (S6 is a single load, not a
            // long-lived observed screen, so this deliberately isn't a reactive StateFlow the way
            // the dashboard's region is) — degrades to [RegionResolver.REGION_URBAN] if no fix is
            // available yet, the exact same fallback the old hardcoded constant always produced.
            val region = RegionResolver.resolve(AppContainer.speedSource.locationFix.value)
            val dto = AppContainer.tariffCache.getActiveTariff(region = region)
            _uiState.update { it.copy(fareSchedule = dto, fareScheduleLoading = false) }
        }
    }

    // --- Printer pairing (spec B5 S6) ---
    fun discoverPrinters() {
        _uiState.update { it.copy(printerDiscovering = true) }
        viewModelScope.launch {
            val result = AppContainer.receiptPrinterGateway.discover()
            _uiState.update {
                it.copy(
                    printerDiscovering = false,
                    discoveredPrinters = result.getOrDefault(emptyList()),
                )
            }
        }
    }

    fun pairPrinter(deviceId: String) {
        viewModelScope.launch {
            AppContainer.receiptPrinterGateway.pair(deviceId)
            _uiState.update { it.copy(pairedPrinter = AppContainer.receiptPrinterGateway.pairedDevice) }
        }
    }

    // --- Admin-PIN-gated factory reset ---
    //
    // Server-verified (2026-08-03 pass) — replaces the former ADMIN_PIN_PLACEHOLDER hardcoded
    // constant (explicitly flagged in its own doc comment as "*** NOT A REAL SECURITY CONTROL
    // ***", see HANDOFF.md's "High priority" gaps). Checks the entered PIN against the tenant's
    // real server-side admin_pin_hash via `POST /v1/fleet/devices/{id}/verify-admin-pin`
    // (shared/API_SUMMARY.md "Admin PIN") — the hash itself is never sent to the device, only
    // the boolean result.
    fun attemptFactoryReset(pin: String) {
        val deviceId = SessionHolder.deviceId
        if (deviceId == null) {
            // No device id means this tablet was never paired via `POST /v1/fleet/devices/register`
            // (see SessionHolder.deviceId's own doc — null is the expected, common state until a
            // device-pairing flow lands). There is no tenant to check the PIN against without one,
            // so this MUST block rather than fall back to any local check — see this method's own
            // "don't silently allow it" requirement, same reasoning as the configured=false branch
            // in verifyPinResult below.
            _uiState.update {
                it.copy(
                    factoryResetError = "This device isn't registered yet — cannot verify the admin " +
                        "PIN. Pair it via Fleet & Vehicles before attempting a factory reset.",
                )
            }
            return
        }
        _uiState.update { it.copy(factoryResetError = null, factoryResetInProgress = true) }
        viewModelScope.launch {
            val verifyPinResult = runCatching {
                AppContainer.apiService.verifyAdminPin(deviceId, VerifyAdminPinRequestDto(pin = pin))
            }
            verifyPinResult.fold(
                onSuccess = { response ->
                    when {
                        // Distinct from `valid=false` below — an unconfigured tenant PIN must
                        // block the reset with a clear explanation, not be treated as "any PIN
                        // works" or as an ordinary wrong-PIN error. See
                        // VerifyAdminPinResponseDto's doc / shared/API_SUMMARY.md's "Admin PIN"
                        // note: `configured` must always be checked explicitly.
                        !response.configured -> _uiState.update {
                            it.copy(
                                factoryResetInProgress = false,
                                factoryResetError = "No admin PIN has been set up for this tenant yet " +
                                    "— an owner must set one (Fleet & Vehicles) before a factory " +
                                    "reset can be verified.",
                            )
                        }
                        !response.valid -> _uiState.update {
                            it.copy(factoryResetInProgress = false, factoryResetError = "Incorrect admin PIN")
                        }
                        else -> {
                            withContext(Dispatchers.IO) { AppContainer.database.clearAllTables() }
                            AppContainer.accessToken = null
                            SessionHolder.clear()
                            _uiState.update {
                                it.copy(factoryResetInProgress = false, factoryResetComplete = true)
                            }
                        }
                    }
                },
                onFailure = {
                    // Network/server failure — fail closed (never wipe without a confirmed
                    // server-side check), matching every other best-effort call in this file's
                    // philosophy of "silent-fail" for reads, but this is a destructive WRITE path,
                    // so the failure must be visible/actionable to the driver instead.
                    _uiState.update {
                        it.copy(
                            factoryResetInProgress = false,
                            factoryResetError = "Couldn't verify the admin PIN (offline or server " +
                                "error) — try again once connected.",
                        )
                    }
                },
            )
        }
    }

    fun clearFactoryResetError() = _uiState.update { it.copy(factoryResetError = null) }

    // --- Offline maps (spec: meter must keep working with zero connectivity; the wheel
    // dashboard's map background is part of that — see MapboxOfflineRegion's class doc) ---
    fun downloadOfflineMaps() {
        val token = BuildConfig.MAPBOX_ACCESS_TOKEN
        if (token.isBlank()) {
            _uiState.update {
                it.copy(offlineMapDownload = OfflineMapDownloadState.Failed("No Mapbox token configured on this device"))
            }
            return
        }
        _uiState.update { it.copy(offlineMapDownload = OfflineMapDownloadState.Downloading(0)) }
        viewModelScope.launch {
            // Downloads whichever metro region (Sydney or Karachi) is nearest the driver's
            // current GPS fix (2026-08-28, active Karachi field-test fix) — was always
            // downloadSydneyMetroRegion regardless of device location, which wasted bandwidth
            // downloading Sydney tiles a Karachi-based test device would never actually use
            // offline. See MapboxOfflineRegion.downloadRegionNear's doc.
            val fix = AppContainer.speedSource.locationFix.value
            MapboxOfflineRegion.downloadRegionNear(token, fix).collect { state ->
                val mapped = when (state) {
                    is MapboxOfflineRegion.DownloadState.Started -> OfflineMapDownloadState.Downloading(0)
                    is MapboxOfflineRegion.DownloadState.InProgress ->
                        OfflineMapDownloadState.Downloading(state.progressPercent)
                    is MapboxOfflineRegion.DownloadState.Completed -> OfflineMapDownloadState.Completed(
                        regionLabel = when (state.regionId) {
                            MapboxOfflineRegion.KARACHI_METRO_REGION_ID -> "Karachi metro"
                            else -> "Sydney metro"
                        },
                    )
                    is MapboxOfflineRegion.DownloadState.Failed -> OfflineMapDownloadState.Failed(state.message)
                }
                _uiState.update { it.copy(offlineMapDownload = mapped) }
            }
        }
    }

    // --- Meter/device pairing (2026-08-28, backend spec) ---

    fun clearPairMeterError() = _uiState.update { it.copy(pairMeter = PairMeterState.Idle) }

    fun scanPairingQr(activity: android.app.Activity) {
        viewModelScope.launch {
            val code = AppContainer.qrScanner.scan(activity)
            if (code != null) submitPairingCode(code)
        }
    }

    /** [pairingCode] is the 8-char code an admin generated for a specific vehicle
     * (`POST /v1/fleet/vehicles/{id}/pairing-code` on the dashboard side). Registers this
     * physical tablet as that vehicle's meter and persists the resulting device id so
     * [loadDeviceStatus]'s existing heartbeat call — previously a structural no-op, see
     * [au.com.threesixty.cabdispatch.domain.SessionHolder.deviceId]'s own long-standing TODO —
     * finally has something real to fire against. */
    fun submitPairingCode(pairingCode: String) {
        val normalized = pairingCode.trim().uppercase()
        if (normalized.isBlank()) return
        _uiState.update { it.copy(pairMeter = PairMeterState.Submitting) }
        viewModelScope.launch {
            val context = getApplication<Application>()
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )
            val result = runCatching {
                AppContainer.apiService.registerDevice(
                    DeviceRegisterRequestDto(
                        androidId = androidId,
                        pairingCode = normalized,
                        model = android.os.Build.MODEL,
                        appVersion = BuildConfig.VERSION_NAME,
                    ),
                )
            }
            result.onSuccess { device ->
                SessionHolder.deviceId = device.id
                AppContainer.devicePairingStore.saveDeviceId(device.id)
                _uiState.update { it.copy(pairMeter = PairMeterState.Success(device.vehicleId)) }
                loadDeviceStatus() // re-fires heartbeat now that deviceId is real
            }.onFailure { error ->
                _uiState.update { it.copy(pairMeter = PairMeterState.Error(pairErrorMessage(error))) }
            }
        }
    }

    /** Extracts the backend's own `{"detail": "..."}` message off a Retrofit [retrofit2.HttpException]
     * (matches this API's error shape everywhere else, e.g. driver-login's 401 body) — the 409
     * "vehicle has an open shift" case in particular must show the server's real explanatory text
     * per spec, not a generic fallback. Network/5xx/anything unparseable falls back to a plain
     * message; never throws. */
    private fun pairErrorMessage(error: Throwable): String {
        val http = error as? retrofit2.HttpException ?: return error.message ?: "Could not pair — check your connection and try again"
        val body = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
        val detail = body?.let { runCatching { cabDispatchJson.decodeFromString<PairErrorDto>(it).detail }.getOrNull() }
        return detail ?: when (http.code()) {
            404, 410 -> "Invalid or expired code — check it and try again"
            409 -> "Cannot pair — this vehicle currently has an open shift"
            else -> "Could not pair (server said ${http.code()}) — try again"
        }
    }

    companion object {
        private const val GPS_NETWORK_POLL_INTERVAL_MS = 4000L

        /** See [respondToLocateRequest]'s doc on why this is a fixed placeholder rather than a
         * real availability status. */
        private const val LOCATE_RESPONSE_STATUS = "unknown"
    }
}
