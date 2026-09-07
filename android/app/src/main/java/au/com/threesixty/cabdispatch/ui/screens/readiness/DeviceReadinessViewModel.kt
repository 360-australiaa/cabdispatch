package au.com.threesixty.cabdispatch.ui.screens.readiness

import android.app.Application
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.MapboxOfflineRegion
import au.com.threesixty.cabdispatch.domain.AppUpdateState
import au.com.threesixty.cabdispatch.domain.DevicePairingRepository
import au.com.threesixty.cabdispatch.domain.DeviceReadiness
import com.mapbox.common.MapboxOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for [DeviceReadinessScreen] — the gate a tablet must pass before anyone can log into the
 * meter. See [DeviceReadiness] for the policy itself; this only gathers its inputs and runs the
 * fixes.
 */
data class DeviceReadinessUiState(
    val results: List<DeviceReadiness.ReadinessResult> = emptyList(),
    val blocking: List<DeviceReadiness.ReadinessResult> = emptyList(),
    val updateState: AppUpdateState = AppUpdateState.Idle,
    val pairCode: String = "",
    val pairing: Boolean = false,
    val pairError: String? = null,
    val mapDownloadMessage: String? = null,
    /**
     * First-install setup, rather than the ordinary guard gate. A technician sees the whole
     * checklist and finishes it explicitly; a driver only ever sees this screen when something is
     * genuinely stopping them, and it clears itself the moment it is fixed.
     */
    val commissioning: Boolean = false,
) {
    /**
     * True once nothing blocking remains — [DeviceReadinessScreen] navigates onward on this.
     *
     * Never true while commissioning: setup ends when the technician says it does, not the instant
     * the last blocking check goes green. Half the point of the checklist is the items that do not
     * block — offline maps, a real GPS fix — and a screen that vanished mid-setup would take them
     * with it.
     */
    val ready: Boolean get() = !commissioning && results.isNotEmpty() && blocking.isEmpty()

    /** Advisory checks still outstanding, so "Finish setup" can say how many it is signing off. */
    val warnings: List<DeviceReadiness.ReadinessResult>
        get() = results.filter { !it.passed && it.severity == DeviceReadiness.Severity.ADVISORY }

    /** Setup can only be finished once nothing is actually blocking. Warnings are allowed through
     * deliberately — a tablet being commissioned indoors has no GPS fix and no amount of waiting
     * will give it one, so trapping the technician there would just teach them to skip the
     * screen. They are counted on the button instead. */
    val canFinishSetup: Boolean get() = results.isNotEmpty() && blocking.isEmpty()
}

class DeviceReadinessViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DeviceReadinessUiState())
    val uiState: StateFlow<DeviceReadinessUiState> = _uiState.asStateFlow()

    /**
     * The two probes that need disk or a coroutine, cached once per screen rather than re-run on
     * every recomposition. `null` means "not checked yet" and is rendered as exactly that —
     * [DeviceReadiness] never lets an unchecked probe read as a failure.
     */
    private val offlineMapsPresent = MutableStateFlow<Boolean?>(null)
    private val signedTariffCached = MutableStateFlow<Boolean?>(null)
    private val locationPermissionGranted = MutableStateFlow<Boolean?>(null)

    init {
        // Re-evaluate whenever any input moves: the heartbeat landing, a pairing succeeding, the
        // update check answering, or a probe completing. That is what lets the gate clear itself
        // the instant the driver fixes the thing it is complaining about, with no refresh button.
        viewModelScope.launch {
            combine(
                AppContainer.deviceCommandHeartbeat.state,
                AppContainer.appUpdateChecker.state,
                offlineMapsPresent,
                signedTariffCached,
                // A real fix arriving, not merely the permission being held: a tablet can have the
                // permission and still never see a satellite, and that tablet cannot charge a
                // distance rate.
                AppContainer.speedSource.locationFix,
            ) { command, update, maps, tariff, fix ->
                val inputs = DeviceReadiness.Inputs(
                    deviceId = command.deviceId,
                    deviceRejected = command.deviceRejected,
                    forceUpdatePending = command.forceUpdatePending,
                    // Only a CONFIRMED newer build counts. Checking, Failed and UpToDate all mean
                    // "there is nothing here to install", and an unreachable release server must
                    // never be the thing that stops a driver working.
                    updateAvailable = update is AppUpdateState.Available ||
                        update is AppUpdateState.Downloading ||
                        update is AppUpdateState.Verifying ||
                        update is AppUpdateState.ReadyToInstall,
                    heartbeatSucceeding = command.lastPollSucceeded,
                    offlineMapsPresent = maps,
                    signedTariffCached = tariff,
                    locationPermissionGranted = locationPermissionGranted.value,
                    hasLocationFix = fix != null,
                )
                Triple(DeviceReadiness.evaluate(inputs), DeviceReadiness.blockingFailures(inputs), update)
            }.collect { (results, blocking, update) ->
                _uiState.update {
                    it.copy(results = results, blocking = blocking, updateState = update)
                }
            }
        }

        // The auto update check on open that the user asked for, and that did not exist before:
        // AppUpdateChecker was only ever touched by the force-update banner, so a tablet the depot
        // had NOT flagged never checked for a build at all.
        if (AppContainer.appUpdateChecker.state.value is AppUpdateState.Idle) {
            viewModelScope.launch { AppContainer.appUpdateChecker.checkForUpdate() }
        }

        probeOfflineMaps()
        probeSignedTariff()
        refreshLocationPermission()

        _uiState.update { it.copy(commissioning = !AppContainer.commissioningStore.isCommissioned()) }
    }

    /** Re-read after the technician returns from the system permission dialog. */
    fun refreshLocationPermission() {
        locationPermissionGranted.value = ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        // Nudge the combine so the row re-renders even when no other input moved.
        signedTariffCached.value = signedTariffCached.value
    }

    /**
     * Technician signs off first-install setup.
     *
     * Recorded on the tablet rather than the server: it describes what a person did to this
     * physical unit, and it has to be answerable with no network, before the tablet has any server
     * identity at all.
     */
    fun finishSetup(onDone: () -> Unit) {
        if (!_uiState.value.canFinishSetup) return
        AppContainer.commissioningStore.markCommissioned()
        _uiState.update { it.copy(commissioning = false) }
        onDone()
    }

    private fun probeOfflineMaps() {
        viewModelScope.launch {
            offlineMapsPresent.value = runCatching {
                MapboxOfflineRegion.hasAnyRegion(MapboxOptions.accessToken)
            }.getOrNull()
        }
    }

    private fun probeSignedTariff() {
        viewModelScope.launch {
            // Reads the Room cache only -- deliberately does NOT refresh over the network. This
            // screen reports what the tablet is holding right now; fetching one here would make an
            // offline tablet look worse than it is and would slow the gate down for no gain, since
            // this check cannot block anyone anyway.
            signedTariffCached.value = runCatching {
                AppContainer.tariffCache.getActiveTariff("urban") != null
            }.getOrNull()
        }
    }

    /** [code] arrives already uppercased and filtered to the server's pairing alphabet by the
     * field itself, so a character the server would reject never reaches state. */
    fun onPairCodeChange(code: String) = _uiState.update {
        it.copy(pairCode = code, pairError = null)
    }

    fun scanPairingQr(activity: android.app.Activity) {
        viewModelScope.launch {
            val code = AppContainer.qrScanner.scan(activity)
            if (code != null) {
                _uiState.update { it.copy(pairCode = code.take(au.com.threesixty.cabdispatch.ui.theme.PAIR_CODE_LENGTH)) }
                submitPairCode()
            }
        }
    }

    fun submitPairCode() {
        val code = _uiState.value.pairCode
        if (code.isBlank() || _uiState.value.pairing) return
        _uiState.update { it.copy(pairing = true, pairError = null) }
        viewModelScope.launch {
            when (val result = DevicePairingRepository.pair(getApplication(), code)) {
                is DevicePairingRepository.PairResult.Success ->
                    // No success state to render: pairing flips `deviceId`, the combine above
                    // re-evaluates, `blocking` empties, and the screen navigates on. Showing a
                    // "paired!" card here would be a screen nobody sees.
                    _uiState.update { it.copy(pairing = false, pairCode = "") }
                is DevicePairingRepository.PairResult.Failure ->
                    _uiState.update { it.copy(pairing = false, pairError = result.message) }
            }
        }
    }

    fun installUpdate(activity: android.app.Activity) {
        viewModelScope.launch {
            when (val state = AppContainer.appUpdateChecker.state.value) {
                is AppUpdateState.Available -> AppContainer.appUpdateChecker.downloadAndVerify(state.release)
                is AppUpdateState.ReadyToInstall ->
                    AppContainer.appUpdateChecker.promptInstall(activity, state.apkFile)
                else -> AppContainer.appUpdateChecker.checkForUpdate()
            }
        }
    }

    fun downloadOfflineMaps() {
        viewModelScope.launch {
            _uiState.update { it.copy(mapDownloadMessage = "Downloading map tiles…") }
            runCatching {
                MapboxOfflineRegion
                    .downloadRegionNear(MapboxOptions.accessToken, AppContainer.speedSource.locationFix.value)
                    .collect { state ->
                        val message = when (state) {
                            is MapboxOfflineRegion.DownloadState.Started -> "Downloading map tiles…"
                            is MapboxOfflineRegion.DownloadState.InProgress -> "Downloading map tiles… ${state.progressPercent}%"
                            is MapboxOfflineRegion.DownloadState.Completed -> "Map tiles downloaded"
                            is MapboxOfflineRegion.DownloadState.Failed -> "Map download failed: ${state.message}"
                        }
                        _uiState.update { it.copy(mapDownloadMessage = message) }
                    }
            }.onFailure { error ->
                _uiState.update { it.copy(mapDownloadMessage = "Map download failed: ${error.message}") }
            }
            probeOfflineMaps()
        }
    }
}
