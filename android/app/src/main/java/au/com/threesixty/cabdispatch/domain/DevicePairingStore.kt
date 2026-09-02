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

    /**
     * The device-scoped heartbeat credential (backend, 2026-08-29 — see
     * [au.com.threesixty.cabdispatch.data.remote.DeviceDto.deviceSecret]'s own doc for why it must
     * be captured off the register response the one time it is offered). Persisted for the exact
     * same reason [getDeviceId] is: [DeviceCommandHeartbeat] needs it on a cold start with nobody
     * logged in, which is the entire point of this credential existing. Read by
     * [DeviceCommandHeartbeat.pollOnce] and sent as `X-Device-Secret`
     * ([au.com.threesixty.cabdispatch.data.remote.ApiService.deviceHeartbeat]) instead of relying
     * on a live driver session for that one call. `null` for a device paired before this field
     * existed, or after [clear] — both cases fall back to the unmodified driver-bearer path.
     */
    fun getDeviceSecret(): String? = prefs.getString(KEY_DEVICE_SECRET, null)

    /** Overwrites any previously stored secret — correct on both a first pair and a re-pair,
     * since the backend rotates the secret on every successful
     * [au.com.threesixty.cabdispatch.data.remote.ApiService.registerDevice] call and invalidates
     * the old one immediately (see
     * [au.com.threesixty.cabdispatch.data.remote.DeviceDto.deviceSecret]'s doc). There is no
     * migration step needed for the rotation case: the old value is simply never read again once
     * this call lands. */
    fun saveDeviceSecret(deviceSecret: String) {
        prefs.edit().putString(KEY_DEVICE_SECRET, deviceSecret).apply()
    }

    /**
     * Last `kiosk_locked` / `force_update_pending` this device actually read back off a *successful*
     * heartbeat, persisted so a reboot or process kill comes up in the last commanded state instead
     * of the `false` defaults (2026-08-29 review pass).
     *
     * [DeviceCommandState] is in-memory only, and [DeviceCommandHeartbeat.pollOnce] deliberately
     * holds `kioskLocked` at its last known-good value when a poll fails so an offline tablet cannot
     * silently un-pin itself. Without this store that guarantee lasted exactly one process lifetime:
     * pull the SIM *and reboot* and the app came back with `kioskLocked = false`, which
     * [au.com.threesixty.cabdispatch.MainActivity]'s resume path then actively drives towards
     * unpinned. Persisting the flags closes that one-step-longer escape.
     *
     * Written only on a poll that succeeded — never on a failure, and never speculatively — so this
     * mirrors the server's last actual answer rather than a guess. Cleared by [clear] at factory
     * reset along with the pairing itself.
     */
    fun getKioskLocked(): Boolean = prefs.getBoolean(KEY_KIOSK_LOCKED, false)

    /** See [getKioskLocked] — same persistence, same lifecycle. */
    fun getForceUpdatePending(): Boolean = prefs.getBoolean(KEY_FORCE_UPDATE_PENDING, false)

    /** Persists the flags from one successful heartbeat — see [getKioskLocked]. */
    fun saveCommandFlags(kioskLocked: Boolean, forceUpdatePending: Boolean) {
        prefs.edit()
            .putBoolean(KEY_KIOSK_LOCKED, kioskLocked)
            .putBoolean(KEY_FORCE_UPDATE_PENDING, forceUpdatePending)
            .apply()
    }

    /**
     * Unpairs this tablet durably — the on-disk half of a factory reset.
     *
     * Added 2026-08-29 because there was no such method: S6's factory reset nulled
     * [SessionHolder.deviceId] in memory only, so the reset *looked* like it unpaired while the id
     * sat untouched in these prefs and was restored by
     * [au.com.threesixty.cabdispatch.data.AppContainer.init] on the very next cold start — the
     * device silently re-paired itself to a tenant the depot believed it had wiped. Also drops the
     * persisted command flags above, so a reset device cannot come back up pinned by a command
     * issued to its previous registration.
     */
    fun clear() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_SECRET)
            .remove(KEY_KIOSK_LOCKED)
            .remove(KEY_FORCE_UPDATE_PENDING)
            .apply()
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_SECRET = "device_secret"
        const val KEY_KIOSK_LOCKED = "kiosk_locked"
        const val KEY_FORCE_UPDATE_PENDING = "force_update_pending"
    }
}
