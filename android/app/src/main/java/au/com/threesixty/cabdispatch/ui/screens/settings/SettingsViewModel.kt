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
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.SessionHolder
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

enum class GpsQuality { NO_FIX, POOR, FAIR, GOOD, PERMISSION_DENIED }
enum class NetworkStatus { OFFLINE, CELLULAR, WIFI, OTHER }
enum class ForceUpdateStatus { UNKNOWN_NO_DEVICE, UNKNOWN_OFFLINE, UP_TO_DATE, REQUIRED }

/** Mirrors [MapboxOfflineRegion.DownloadState] but as a UI-friendly type (no Mapbox SDK types
 * leaking into the state a Composable reads) — see that class's doc for the actual download. */
sealed interface OfflineMapDownloadState {
    data object NotStarted : OfflineMapDownloadState
    data class Downloading(val progressPercent: Int) : OfflineMapDownloadState
    data object Completed : OfflineMapDownloadState
    data class Failed(val message: String) : OfflineMapDownloadState
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
)

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
        _uiState.update { it.copy(pairedPrinter = AppContainer.receiptPrinterGateway.pairedDevice) }
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

        if (fix == null) {
            _uiState.update { it.copy(gpsQuality = GpsQuality.NO_FIX, gpsAccuracyM = null) }
            return
        }
        val quality = when {
            fix.accuracy <= 10f -> GpsQuality.GOOD
            fix.accuracy <= 30f -> GpsQuality.FAIR
            else -> GpsQuality.POOR
        }
        _uiState.update { it.copy(gpsQuality = quality, gpsAccuracyM = fix.accuracy) }
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

    // --- App version / force update ---
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
            }.onFailure {
                _uiState.update { it.copy(forceUpdateStatus = ForceUpdateStatus.UNKNOWN_OFFLINE) }
            }
        }
    }

    // --- Passenger-facing fare schedule (cl.15 display requirement) ---
    private fun loadFareSchedule() {
        viewModelScope.launch {
            // TODO(S2 sibling agent): region is hardcoded "urban" here since
            // S6 has no live GPS-region polygon lookup of its own — S2 is
            // where that detection lives (spec B5). Read the driver's actual
            // current region once that's exposed via SessionHolder or
            // similar, instead of this default.
            val dto = AppContainer.tariffCache.getActiveTariff(region = FARE_SCHEDULE_REGION)
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
    fun attemptFactoryReset(pin: String) {
        if (pin != ADMIN_PIN_PLACEHOLDER) {
            _uiState.update { it.copy(factoryResetError = "Incorrect admin PIN") }
            return
        }
        _uiState.update { it.copy(factoryResetError = null, factoryResetInProgress = true) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AppContainer.database.clearAllTables() }
            AppContainer.accessToken = null
            SessionHolder.clear()
            _uiState.update { it.copy(factoryResetInProgress = false, factoryResetComplete = true) }
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
            MapboxOfflineRegion.downloadSydneyMetroRegion(token).collect { state ->
                val mapped = when (state) {
                    is MapboxOfflineRegion.DownloadState.Started -> OfflineMapDownloadState.Downloading(0)
                    is MapboxOfflineRegion.DownloadState.InProgress ->
                        OfflineMapDownloadState.Downloading(state.progressPercent)
                    is MapboxOfflineRegion.DownloadState.Completed -> OfflineMapDownloadState.Completed
                    is MapboxOfflineRegion.DownloadState.Failed -> OfflineMapDownloadState.Failed(state.message)
                }
                _uiState.update { it.copy(offlineMapDownload = mapped) }
            }
        }
    }

    companion object {
        private const val GPS_NETWORK_POLL_INTERVAL_MS = 4000L
        private const val FARE_SCHEDULE_REGION = "urban"

        /**
         * *** PLACEHOLDER — NOT A REAL SECURITY CONTROL ***
         * spec B6 anti-tamper calls for an "admin-PIN factory reset"; no
         * admin/PIN-management backend exists yet to verify against, so this
         * is a hardcoded demo value. TODO(auth/admin sibling agent): replace
         * with a server-verified admin PIN (or at minimum a per-tenant
         * value pulled from a signed config, not compiled into the APK)
         * before shipping — grep for `ADMIN_PIN_PLACEHOLDER` before release.
         */
        private const val ADMIN_PIN_PLACEHOLDER = "913572"
    }
}
