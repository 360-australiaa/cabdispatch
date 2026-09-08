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
import au.com.threesixty.cabdispatch.data.remote.DeviceHeartbeatRequestDto
import au.com.threesixty.cabdispatch.data.remote.MapboxOfflineRegion
import au.com.threesixty.cabdispatch.data.remote.PositionPublishRequestDto
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.data.remote.VerifyAdminPinRequestDto
import au.com.threesixty.cabdispatch.domain.GpsQuality
import au.com.threesixty.cabdispatch.domain.GpsQualityClassifier
import au.com.threesixty.cabdispatch.domain.DevicePairingRepository
import au.com.threesixty.cabdispatch.domain.LocateOutcome
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.ThemeMode
import au.com.threesixty.cabdispatch.domain.location.RegionResolver
import au.com.threesixty.cabdispatch.hardware.printing.PrinterDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import retrofit2.HttpException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// GpsQuality moved to au.com.threesixty.cabdispatch.domain.GpsQuality (2026-09-02, Home-dashboard
// redesign pass) so au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardViewModel can
// share the exact same accuracy tiers instead of duplicating them — see that file's own doc.
enum class NetworkStatus { OFFLINE, CELLULAR, WIFI, OTHER }
/**
 * [UNREGISTERED] is deliberately distinct from [UNKNOWN_OFFLINE]. Found on the pilot tablet,
 * 2026-09-07: the heartbeat had been failing with a flat "offline or server unreachable" while
 * the server was demonstrably healthy. It was returning **404** -- the device record this tablet
 * still holds an id for had been removed by a fleet wipe, so there was nothing on the server to
 * beat against. Those two states need opposite responses from whoever reads the screen ("wait for
 * signal" versus "re-pair this tablet"), and collapsing them into one message sent the reader
 * looking for a network fault that did not exist.
 */
enum class ForceUpdateStatus { UNKNOWN_NO_DEVICE, UNKNOWN_OFFLINE, UNREGISTERED, UP_TO_DATE, REQUIRED }

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
    data class Failed(val message: String) : LocateResponseState
}

/**
 * Projects the process-wide [LocateOutcome] onto this screen's own type.
 *
 * There used to be TWO locate implementations. [DeviceCommandHeartbeat] answered correctly, and
 * this ViewModel kept a private copy that published a VEHICLE position against
 * `SessionHolder.session.vehicleUuid` -- and the copy was the one the About tab actually rendered.
 * Its own doc admitted it was "an un-migrated duplicate left behind".
 *
 * That duplicate is what a technician saw as "Location request failed to send - HTTP 404 not
 * found": the tablet held a vehicle UUID for a car that had since been deleted, so
 * `POST /v1/fleet/positions` answered 404 and the tile printed the HTTP status. The tablet was
 * fine; the identity it was publishing against was gone. The heartbeat now reports on the DEVICE
 * route, which needs no vehicle at all, and this screen reads that one result instead of running
 * its own.
 *
 * [LocateOutcome.NoVehicleBound] has no counterpart here on purpose -- it cannot happen any more,
 * because answering a locate no longer involves a vehicle.
 */
private fun LocateOutcome.toScreenState(): LocateResponseState = when (this) {
    LocateOutcome.None -> LocateResponseState.Idle
    LocateOutcome.Sent -> LocateResponseState.Sent
    LocateOutcome.NoFixYet -> LocateResponseState.NoFixYet
    LocateOutcome.NoVehicleBound -> LocateResponseState.Idle
    is LocateOutcome.Failed -> LocateResponseState.Failed(message)
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

    /** True once the admin PIN has been verified for the GPS simulator this session — see
     * [SettingsViewModel.attemptUnlockSimulator]. Deliberately NOT persisted: the unlock lasts
     * for the life of this ViewModel, so a technician who walks away doesn't leave the simulator
     * open on a tablet that goes back into service. */
    val simulatorUnlocked: Boolean = false,
    val simulatorPinError: String? = null,
    val simulatorPinVerifying: Boolean = false,
    val offlineMapDownload: OfflineMapDownloadState = OfflineMapDownloadState.NotStarted,
    val locateResponse: LocateResponseState = LocateResponseState.Idle,
    val pairMeter: PairMeterState = PairMeterState.Idle,
    /** Driver's local self-declaration that the bound vehicle has 5+ seats — see
     * [au.com.threesixty.cabdispatch.domain.MaxiVehicleStore]'s doc. Loaded from that store in
     * [SettingsViewModel.init], updated via [SettingsViewModel.setMaxiVehicle]. */
    val isMaxiVehicle: Boolean = false,
    /** Settings two-pane pass (2026-09-03) — the three real preference rows, backed by
     * [au.com.threesixty.cabdispatch.domain.SettingsPreferencesStore]. Mirrored into this state the
     * same way [isMaxiVehicle] is (loaded once in [SettingsViewModel.init], each setter writes
     * through to the store and updates this copy) even though the store's own `StateFlow`s are
     * also read directly by other screens — see that store's class doc for why. */
    val autoAcceptJobs: Boolean = false,
    val showMapInBackground: Boolean = true,
    val allowCash: Boolean = true,
    /** Real Light/Dark/System display theme (2026-09-04 day-mode pass) — see [ThemeMode]'s own
     * doc and [au.com.threesixty.cabdispatch.domain.SettingsPreferencesStore.themeMode]. Mirrored
     * into this state the same way the three flags above are. */
    val themeMode: ThemeMode = ThemeMode.DARK,
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
        // Mirror the ONE real locate result rather than running a second attempt of our own -- see
        // LocateOutcome.toScreenState for the duplicate this replaced and the 404 it produced.
        viewModelScope.launch {
            AppContainer.deviceCommandHeartbeat.state.collect { command ->
                _uiState.update { it.copy(locateResponse = command.locate.toScreenState()) }
            }
        }
        loadDeviceStatus()
        loadFareSchedule()
        _uiState.update {
            it.copy(
                pairedPrinter = AppContainer.receiptPrinterGateway.pairedDevice,
                isMaxiVehicle = AppContainer.maxiVehicleStore.isMaxiVehicle(),
                autoAcceptJobs = AppContainer.settingsPreferencesStore.autoAcceptJobs.value,
                showMapInBackground = AppContainer.settingsPreferencesStore.showMapInBackground.value,
                allowCash = AppContainer.settingsPreferencesStore.allowCash.value,
                themeMode = AppContainer.settingsPreferencesStore.themeMode.value,
            )
        }
    }

    /** See [SettingsUiState.autoAcceptJobs]'s doc. */
    fun setAutoAcceptJobs(value: Boolean) {
        AppContainer.settingsPreferencesStore.setAutoAcceptJobs(value)
        _uiState.update { it.copy(autoAcceptJobs = value) }
    }

    /** See [SettingsUiState.showMapInBackground]'s doc. */
    fun setShowMapInBackground(value: Boolean) {
        AppContainer.settingsPreferencesStore.setShowMapInBackground(value)
        _uiState.update { it.copy(showMapInBackground = value) }
    }

    /** See [SettingsUiState.allowCash]'s doc. */
    fun setAllowCash(value: Boolean) {
        AppContainer.settingsPreferencesStore.setAllowCash(value)
        _uiState.update { it.copy(allowCash = value) }
    }

    /** See [SettingsUiState.themeMode]'s doc. [au.com.threesixty.cabdispatch.ui.theme.CabDispatchTheme]
     * (composed once at the app root, not here) is what actually reacts to the store's `StateFlow`
     * and repaints the whole app — this just writes through, same as every other real toggle above. */
    fun setThemeMode(value: ThemeMode) {
        AppContainer.settingsPreferencesStore.setThemeMode(value)
        _uiState.update { it.copy(themeMode = value) }
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
                }
            }.onFailure { error ->
                // 404 means the server has no such device -- not that it is unreachable. See
                // ForceUpdateStatus.UNREGISTERED.
                val unregistered = (error as? HttpException)?.code() == 404
                _uiState.update {
                    it.copy(
                        forceUpdateStatus = if (unregistered) {
                            ForceUpdateStatus.UNREGISTERED
                        } else {
                            ForceUpdateStatus.UNKNOWN_OFFLINE
                        },
                    )
                }
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

    fun pairPrinter(device: PrinterDevice) {
        viewModelScope.launch {
            AppContainer.receiptPrinterGateway.pair(device)
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
                            AppContainer.refreshToken = null
                            SessionHolder.clear()
                            // A factory reset must also UNPAIR. This wiped Room, the tokens and the
                            // session but left the device id and secret sitting in
                            // DevicePairingStore, so a "factory reset" tablet came back up still
                            // paired -- DevicePairingStore.clear() had no caller at all, and both
                            // DeviceCommandHeartbeat's and Session.kt's docs asserted the opposite
                            // of what actually happened.
                            //
                            // Cosmetic until 2026-09-08; not any more. Registration is now the gate
                            // in front of the login screen, so a tablet that had been reset and
                            // handed to another depot would sail straight past a check that exists
                            // precisely to stop it. SessionHolder.clear() deliberately does not
                            // touch deviceId (logging off must not unpair), so this is the one
                            // place that has to say so explicitly.
                            SessionHolder.deviceId = null
                            AppContainer.devicePairingStore.clear()
                            // ...and back to first-install state, so the next person to switch it
                            // on gets the technician's commissioning checklist rather than a login
                            // screen on a tablet nobody has set up.
                            AppContainer.commissioningStore.clear()
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

    // --- Admin-PIN-gated GPS simulator ---
    //
    // The simulator can drive the meter from a scripted route: it feeds synthetic speed and
    // position into the same SpeedSource the fare engine bills from. It shipped as an always-open
    // panel on the Settings ▸ About tab, gated on nothing at all — the reasoning being that it was
    // a debug-build affordance. That reasoning does not hold on this project: the field tablet runs
    // a DEBUG build against the production server, so in practice every driver had a one-tap way to
    // make the meter bill a trip that never happened. It is now behind the same server-verified
    // admin PIN as the factory reset, which is the control the depot already uses for
    // "technicians only".
    //
    // Deliberately a separate unlock from attemptFactoryReset rather than a shared flag: the two
    // are different actions with different consequences, and a technician who unlocked the
    // simulator must not thereby be halfway into a data wipe.
    fun attemptUnlockSimulator(pin: String) {
        val deviceId = SessionHolder.deviceId
        if (deviceId == null) {
            _uiState.update {
                it.copy(
                    simulatorPinError = "This device isn't registered yet — cannot verify the admin " +
                        "PIN. Pair it via Fleet & Vehicles first.",
                )
            }
            return
        }
        _uiState.update { it.copy(simulatorPinError = null, simulatorPinVerifying = true) }
        viewModelScope.launch {
            runCatching {
                AppContainer.apiService.verifyAdminPin(deviceId, VerifyAdminPinRequestDto(pin = pin))
            }.fold(
                onSuccess = { response ->
                    when {
                        // Same explicit `configured` check attemptFactoryReset makes, for the same
                        // reason (see VerifyAdminPinResponseDto's doc): an unconfigured tenant PIN
                        // must block, never be read as "any PIN works".
                        !response.configured -> _uiState.update {
                            it.copy(
                                simulatorPinVerifying = false,
                                simulatorPinError = "No admin PIN has been set up for this tenant yet " +
                                    "— an owner must set one (Fleet & Vehicles) first.",
                            )
                        }
                        !response.valid -> _uiState.update {
                            it.copy(simulatorPinVerifying = false, simulatorPinError = "Incorrect admin PIN")
                        }
                        else -> _uiState.update {
                            it.copy(simulatorPinVerifying = false, simulatorUnlocked = true)
                        }
                    }
                },
                onFailure = {
                    // Fail closed, exactly like the factory-reset gate: no offline fallback to a
                    // local check, because there isn't one that means anything. A tablet that
                    // cannot reach the server cannot unlock the simulator.
                    _uiState.update {
                        it.copy(
                            simulatorPinVerifying = false,
                            simulatorPinError = "Couldn't verify the admin PIN (offline or server " +
                                "error) — try again once connected.",
                        )
                    }
                },
            )
        }
    }

    fun clearSimulatorPinError() = _uiState.update { it.copy(simulatorPinError = null) }

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
     * finally has something real to fire against.
     *
     * The call itself lives in [DevicePairingRepository]: the readiness gate in front of the login
     * screen pairs too, and the two must behave identically — same normalisation, same persistence
     * (including the device secret, which only that repository has ever stored), same error text.
     * A second copy here would be a second thing to keep in step. */
    fun submitPairingCode(pairingCode: String) {
        if (pairingCode.isBlank()) return
        _uiState.update { it.copy(pairMeter = PairMeterState.Submitting) }
        viewModelScope.launch {
            when (val result = DevicePairingRepository.pair(getApplication(), pairingCode)) {
                is DevicePairingRepository.PairResult.Success -> {
                    _uiState.update { it.copy(pairMeter = PairMeterState.Success(result.vehicleId)) }
                    loadDeviceStatus() // re-fires heartbeat now that deviceId is real
                }
                is DevicePairingRepository.PairResult.Failure ->
                    _uiState.update { it.copy(pairMeter = PairMeterState.Error(result.message)) }
            }
        }
    }

    companion object {
        private const val GPS_NETWORK_POLL_INTERVAL_MS = 4000L

        /** See [respondToLocateRequest]'s doc on why this is a fixed placeholder rather than a
         * real availability status. */
        private const val LOCATE_RESPONSE_STATUS = "unknown"
    }
}
