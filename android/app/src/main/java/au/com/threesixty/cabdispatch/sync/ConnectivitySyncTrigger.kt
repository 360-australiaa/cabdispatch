package au.com.threesixty.cabdispatch.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.WorkManager

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
 */
class ConnectivitySyncTrigger(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            SyncWorker.enqueueOneTime(WorkManager.getInstance(appContext))
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
}
