package au.com.threesixty.cabdispatch.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.WorkManager
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.domain.NetworkStatus
import au.com.threesixty.cabdispatch.domain.classifyNetworkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Registers a `ConnectivityManager.NetworkCallback` that eagerly enqueues a
 * one-time [SyncWorker] run the instant connectivity returns, rather than
 * waiting for the ~15 min periodic backstop ([SyncWorker.enqueuePeriodic]).
 * This is the "silently sync the moment connectivity returns" half of the
 * offline-sync contract; the periodic request is the "and don't rely on that
 * callback alone" half.
 *
 * Held for the process lifetime by
 * [au.com.threesixty.cabdispatch.data.AppContainer] (never unregistered) —
 * there's exactly one of these per process, same as the rest of AppContainer.
 *
 * ### [isOnline] (offline-indicator pass, 2026-09-05)
 * Also the single live "is there real internet connectivity right now" signal for the whole app —
 * [au.com.threesixty.cabdispatch.ui.overlays.OfflineBanner] reads it, rather than standing up a
 * second `ConnectivityManager` detector. Before the W7 connectivity consolidation (2026-09-13),
 * [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel.pollNetwork] and
 * [au.com.threesixty.cabdispatch.ui.screens.offlinesync.OfflineSyncViewModel.pollNetwork] each ran
 * this exact same `activeNetwork` + [NetworkCapabilities.NET_CAPABILITY_INTERNET] check
 * independently; both now collect [networkStatus] below instead. Used here to seed the initial
 * value and on every [android.net.ConnectivityManager.NetworkCallback.onLost] (a lost
 * network doesn't necessarily mean *no* network: another internet-capable one may still be up),
 * layered under the SAME [networkCallback]/[NetworkRequest] registration this class already
 * performs for the sync trigger above, not a parallel registration. [count] tracks how many
 * currently-registered networks satisfy the request so a second concurrent connection (e.g. Wi-Fi
 * up while cellular is still dropping out) doesn't flip [isOnline] false on the first one's
 * [onLost].
 *
 * ### [networkStatus] (W7 connectivity consolidation, 2026-09-13)
 * The richer transport-classified sibling of [isOnline] — see [au.com.threesixty.cabdispatch.domain.NetworkStatus]'s
 * own doc. Maintained on the exact same [networkCallback] events as [isOnline], so both ViewModels
 * that used to poll `ConnectivityManager` for this ([au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel],
 * [au.com.threesixty.cabdispatch.ui.screens.offlinesync.OfflineSyncViewModel]) now just collect it.
 */
class ConnectivitySyncTrigger(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var satisfyingNetworkCount = 0

    /** Process-lifetime scope for the best-effort tariff refresh below. `NetworkCallback` methods
     * run on a binder thread with no scope of their own, and this object is held for the life of
     * the process by [au.com.threesixty.cabdispatch.data.AppContainer], so a [SupervisorJob] here
     * matches the lifetime of the class exactly — same shape as `AppContainer.startupScope`. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isOnline = MutableStateFlow(readCurrentConnectivitySnapshot())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _networkStatus = MutableStateFlow(classifyNetworkStatus(currentCapabilities()))
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            satisfyingNetworkCount++
            _isOnline.value = true
            _networkStatus.value = classifyNetworkStatus(currentCapabilities())
            SyncWorker.enqueueOneTime(WorkManager.getInstance(appContext))
            // T2 (architecture audit 2026-09-08, §2.3): the toll registry used to be refreshed
            // exactly once, fire-and-forget, at process start — so a tablet that booted with no
            // signal (a driver starting a shift in a basement car park is the ordinary case) ran
            // the entire shift against an empty cache and auto-detected no tolls at all, silently.
            // Regaining connectivity is the obvious moment to fix that, and it is the same event
            // this callback already treats as "we can reach the server again".
            AppContainer.refreshTollRegistry()
            // Same moment, same reasoning, for the airport-fee zones the pickup fee is decided
            // against — a tablet that booted offline otherwise runs the shift on the compiled
            // precinct-circle fallback (see AirportZoneCache's doc) instead of the real ranks.
            AppContainer.refreshAirportZones()
            // Same moment again for the live traffic cameras/hazards the meter map draws
            // (live-map redesign, 2026-09-09) — informational only, so this one is lower-stakes
            // than the two above, but reconnecting is still the right moment to stop showing a
            // driver whatever was cached (possibly nothing) before the tablet last had signal.
            AppContainer.refreshTrafficData()
            // S5: reconnecting is also the single best moment to refresh the tariff — it is exactly
            // when a tablet that has been offline (a shift in a dead-spot, a tablet left parked) is
            // most likely to be holding a stale one. Best-effort and fire-and-forget; see
            // [TariffRefresh]. Independent of the toll refresh above: either can fail without
            // affecting the other, and neither may fail this callback.
            scope.launch { TariffRefresh.refreshBestEffort() }
        }

        override fun onLost(network: Network) {
            satisfyingNetworkCount = (satisfyingNetworkCount - 1).coerceAtLeast(0)
            // Don't just assume "offline" the instant one matching network drops — a second one
            // (see class doc) may still be up. Re-derive from the platform's own current view
            // rather than trusting the local counter alone, since callback ordering across two
            // networks flapping at once isn't guaranteed.
            _isOnline.value = satisfyingNetworkCount > 0 || readCurrentConnectivitySnapshot()
            _networkStatus.value = classifyNetworkStatus(currentCapabilities())
        }
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    /** The platform's current view of the active network's capabilities, or `null` if there is
     * none — the one raw `ConnectivityManager` read this whole class is built on. Used to seed
     * both [isOnline]/[networkStatus] before the first callback fires and as [onLost]'s
     * tie-breaker (see [networkCallback]'s doc), never on a hot path. */
    private fun currentCapabilities(): NetworkCapabilities? =
        connectivityManager.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

    private fun readCurrentConnectivitySnapshot(): Boolean =
        currentCapabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
