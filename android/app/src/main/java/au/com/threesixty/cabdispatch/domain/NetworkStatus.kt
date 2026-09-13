package au.com.threesixty.cabdispatch.domain

import android.net.NetworkCapabilities

/**
 * W7 connectivity consolidation (2026-09-13).
 *
 * Before this file existed, three call sites each independently asked
 * `ConnectivityManager` "are we online right now, and how" on their own schedule:
 * [au.com.threesixty.cabdispatch.sync.ConnectivitySyncTrigger] (event-driven, via a registered
 * `NetworkCallback` — the real-time signal [au.com.threesixty.cabdispatch.ui.overlays.OfflineBanner]
 * already reads), [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel.pollNetwork]
 * (a timer poll, alongside GPS), and
 * [au.com.threesixty.cabdispatch.ui.screens.offlinesync.OfflineSyncViewModel.pollNetwork] (a
 * one-shot check on init and after a manual "force sync"). All three ran the identical
 * `activeNetwork` + [NetworkCapabilities] read, and could disagree for as long as it took the two
 * pollers to catch up to a state [ConnectivitySyncTrigger] already knew about instantly.
 *
 * [NetworkStatus] is now the one shared vocabulary, and
 * [au.com.threesixty.cabdispatch.sync.ConnectivitySyncTrigger] is the one place that computes it
 * live (it already owns the single process-lifetime `NetworkCallback` registration everything else
 * should read from, not duplicate) — both ViewModels above now collect
 * [au.com.threesixty.cabdispatch.sync.ConnectivitySyncTrigger.networkStatus] instead of querying
 * `ConnectivityManager` themselves. [classifyNetworkStatus] is the pure classification rule, kept
 * here (not private inside the trigger) so it stays unit-testable without a real `ConnectivityManager`.
 */
enum class NetworkStatus { OFFLINE, CELLULAR, WIFI, OTHER }

/** Pure classification: `null` (no active network, or the platform has no capabilities for it) is
 * [NetworkStatus.OFFLINE]; otherwise Wi-Fi/cellular win by transport, and anything else
 * internet-capable (ethernet, VPN, Bluetooth tethering) is [NetworkStatus.OTHER] — never fabricated
 * as one of the two named transports it isn't. */
fun classifyNetworkStatus(caps: NetworkCapabilities?): NetworkStatus = when {
    caps == null -> NetworkStatus.OFFLINE
    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkStatus.OFFLINE
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkStatus.WIFI
    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkStatus.CELLULAR
    else -> NetworkStatus.OTHER
}
