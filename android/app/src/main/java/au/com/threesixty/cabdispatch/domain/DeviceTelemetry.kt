package au.com.threesixty.cabdispatch.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/**
 * The two device-telemetry reads this app's background loops send to the backend — battery
 * percentage and a coarse network-transport category.
 *
 * Extracted (2026-08-29 fleet-command pass) from [LivePositionHeartbeat], where both lived as
 * `private` methods, because a second caller appeared: [DeviceCommandHeartbeat] must populate the
 * same two fields on `POST /v1/fleet/devices/{id}/heartbeat`
 * ([au.com.threesixty.cabdispatch.data.remote.DeviceHeartbeatRequestDto]) — until that pass the
 * device heartbeat sent only `app_version`, which is exactly why the fleet dashboard's Devices
 * table showed `battery`/`network` as `null` for the real pilot tablet even while it was clearly
 * online. Deliberately a shared helper rather than a copy-paste: the wifi/4g/offline mapping below
 * is a wire contract with the backend, and two divergent copies of it would drift silently.
 *
 * Behaviour is byte-for-byte what [LivePositionHeartbeat] already shipped — only the receiver
 * changed (from an instance field to an explicit [Context] parameter). No new permission is
 * needed: `ACCESS_NETWORK_STATE` is already declared in the manifest, and battery capacity needs
 * none.
 */
internal object DeviceTelemetry {

    /** [BatteryManager.BATTERY_PROPERTY_CAPACITY] on the system [Context.BATTERY_SERVICE] —
     * returns `null` (never throws) if the service is unavailable or reports an invalid
     * percentage, matching both callers' "skip silently, try again next tick" posture. */
    fun readBatteryPercent(appContext: Context): Int? = runCatching {
        val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return null
        val pct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        pct.takeIf { it in 0..100 }
    }.getOrNull()

    /** Maps the active network's [NetworkCapabilities] transport to the categories the backend
     * expects — `"wifi"` / `"4g"` (any cellular transport; this app has no way to distinguish
     * 3G/4G/5G from [NetworkCapabilities] alone, and the blueprint's own example string is `"4g"`,
     * not a generic `"cellular"`) / `"offline"` (no active network, or one with neither transport
     * — e.g. VPN-only). Returns `null` (not `"offline"`) only if [ConnectivityManager] itself is
     * unavailable, so a real "no network" reading is never confused with "couldn't check". */
    fun readNetworkType(appContext: Context): String? = runCatching {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "4g"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
            else -> "offline"
        }
    }.getOrNull()
}
