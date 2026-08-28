package au.com.threesixty.cabdispatch.domain

import android.content.Context

/**
 * Persists the server-assigned [au.com.threesixty.cabdispatch.data.remote.DeviceDto.id] across
 * process death (2026-08-28, real device-pairing pass — see
 * [au.com.threesixty.cabdispatch.ui.screens.pairing.PairMeterViewModel]). [SessionHolder.deviceId]
 * was a bare in-memory `var` with a standing TODO flagging exactly this gap: nothing ever set it,
 * and even once something does, an in-memory field means every app restart forgets the pairing and
 * heartbeat silently goes back to a no-op. Mirrors [SharedPreferencesDriverAuthRepository]'s own
 * `getSharedPreferences(..., MODE_PRIVATE)` pattern — the existing precedent for small durable
 * session state in this app, not a new persistence mechanism.
 */
class DevicePairingStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("device_pairing", Context.MODE_PRIVATE)

    fun getDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)

    fun saveDeviceId(deviceId: String) {
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
    }
}
