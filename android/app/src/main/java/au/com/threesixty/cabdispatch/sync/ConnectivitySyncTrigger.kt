package au.com.threesixty.cabdispatch.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.WorkManager
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
 * second `ConnectivityManager` detector. It is the exact same check
 * [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel.pollNetwork] and
 * [au.com.threesixty.cabdispatch.ui.screens.offlinesync.OfflineSyncViewModel.pollNetwork] already
 * poll for (`activeNetwork` + [NetworkCapabilities.NET_CAPABILITY_INTERNET]) — used here to seed
 * the initial value and on every [android.net.ConnectivityManager.NetworkCallback.onLost] (a lost
 * network doesn't necessarily mean *no* network: another internet-capable one may still be up),
 * layered under the SAME [networkCallback]/[NetworkRequest] registration this class already
 * performs for the sync trigger above, not a parallel registration. [count] tracks how many
 * currently-registered networks satisfy the request so a second concurrent connection (e.g. Wi-Fi
 * up while cellular is still dropping out) doesn't flip [isOnline] false on the first one's
 * [onLost].
 */
class ConnectivitySyncTrigger(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var satisfyingNetworkCount = 0

    private val _isOnline = MutableStateFlow(readCurrentConnectivitySnapshot())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            satisfyingNetworkCount++
            _isOnline.value = true
            SyncWorker.enqueueOneTime(WorkManager.getInstance(appContext))
        }

        override fun onLost(network: Network) {
            satisfyingNetworkCount = (satisfyingNetworkCount - 1).coerceAtLeast(0)
            // Don't just assume "offline" the instant one matching network drops — a second one
            // (see class doc) may still be up. Re-derive from the platform's own current view
            // rather than trusting the local counter alone, since callback ordering across two
            // networks flapping at once isn't guaranteed.
            _isOnline.value = satisfyingNetworkCount > 0 || readCurrentConnectivitySnapshot()
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

    /** Same one-shot snapshot check [SettingsViewModel.pollNetwork]/[OfflineSyncViewModel.pollNetwork]
     * already poll with — used only to seed [isOnline] before the first callback fires and as an
     * [onLost] tie-breaker (see [networkCallback]'s doc), never on a hot path. */
    private fun readCurrentConnectivitySnapshot(): Boolean {
        val caps = connectivityManager.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
