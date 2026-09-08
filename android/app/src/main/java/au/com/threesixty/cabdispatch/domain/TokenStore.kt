package au.com.threesixty.cabdispatch.domain

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the session's bearer/refresh token pair across process death — added 2026-09-06 on
 * direct product instruction ("there should be zero errors in our meter") after tracing exactly
 * this gap: [au.com.threesixty.cabdispatch.data.AppContainer.accessToken]/`refreshToken` were both
 * process-memory-only, so a perfectly ordinary Android process restart (routine on an all-day
 * kiosk tablet backgrounded for hours, and every time a build gets reinstalled) came back with NO
 * token at all — not an expired one `AppContainer`'s `tokenAuthenticator` could recover from, just
 * none. Every authenticated call made before the driver's next full PIN re-login failed with a
 * real, honestly-reported 401 — see
 * [au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelViewModel.refresh]'s one-shot
 * `init` call for the specific, highly visible case (LIVE DISPATCH showing a permanent "HTTP 401
 * Unauthorized" — nothing ever re-polls it after that first failure) and `TripRepository`'s sync
 * outbox for a quieter one (a trip stuck reporting it "hasn't synced").
 *
 * [SessionStore]'s own doc explains why THAT class deliberately excludes credentials — this is the
 * separate class that now actually holds them, on the same
 * `getSharedPreferences(..., MODE_PRIVATE)` precedent [DevicePairingStore]/[SessionStore]/the
 * offline-PIN-hash cache already use for durable state on this device — no new storage mechanism
 * or dependency introduced for this.
 *
 * Both keys are independently settable/clearable (not one combined `save(access, refresh)`) so
 * [au.com.threesixty.cabdispatch.data.AppContainer.accessToken]/`refreshToken`'s own property
 * setters can write straight through on every existing assignment site (both
 * [DriverAuthRepository] login call sites, `tokenAuthenticator`'s refresh success, and every
 * logout/factory-reset clear) without any of those call sites needing to change or coordinate the
 * two together.
 */
class TokenStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun setAccessToken(token: String?) {
        if (token == null) {
            prefs.edit().remove(KEY_ACCESS_TOKEN).apply()
        } else {
            prefs.edit().putString(KEY_ACCESS_TOKEN, token).apply()
        }
    }

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun setRefreshToken(token: String?) {
        if (token == null) {
            prefs.edit().remove(KEY_REFRESH_TOKEN).apply()
        } else {
            prefs.edit().putString(KEY_REFRESH_TOKEN, token).apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "auth_tokens"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}
