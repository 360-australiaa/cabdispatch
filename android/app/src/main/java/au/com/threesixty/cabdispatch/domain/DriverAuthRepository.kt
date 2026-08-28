package au.com.threesixty.cabdispatch.domain

import android.content.Context
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.DriverLoginRequestDto
import au.com.threesixty.cabdispatch.data.remote.MfaLoginRequestDto
import au.com.threesixty.cabdispatch.data.remote.UserDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Driver ID + PIN login with an offline fallback, per spec B5 S1: "local
 * validation against a cached driver record, falls back to a stored hash so
 * login works fully offline once a driver has logged in once on this
 * device."
 *
 * Calls the real `POST /v1/auth/driver-login` endpoint ([ApiService.driverLogin],
 * `driverId` -> `driver_code`) — this used to map `driverId -> email` and
 * `pin -> password` onto the staff login endpoint as a placeholder (see git
 * history on this file if you need that); the backend team has since shipped
 * the dedicated driver-PIN endpoint the old NOTE(integration agent) comment
 * asked to reconcile on, so that mapping is gone.
 *
 * [login] returns a [DriverLoginResult], not `Result<UserDto>`, because the
 * backend endpoint can answer three ways, not two: a normal success, an
 * MFA-challenge (driver account has TOTP enabled — see
 * shared/API_SUMMARY.md "Admin MFA (TOTP)"; the driver-login endpoint shares
 * the exact same two-step contract staff login would use), or a failure. A
 * plain `Result` only has two branches, so it can't carry the MFA case
 * without overloading `onFailure` for something that isn't actually a
 * failure (the request succeeded; the driver just isn't logged in yet).
 * [completeMfaLogin] is step two, only reachable from [DriverLoginResult.MfaRequired].
 */
interface DriverAuthRepository {
    suspend fun login(driverId: String, pin: String): DriverLoginResult

    /**
     * Step two of the MFA challenge from [login]. [driverId]/[pin] are passed back in (not just
     * [mfaToken]/[code]) purely so the offline-cache hash can be written on success — same cache
     * key scheme [login] uses for a non-MFA driver, so a subsequent offline login for this driver
     * works identically whether or not their account has MFA enabled.
     */
    suspend fun completeMfaLogin(driverId: String, pin: String, mfaToken: String, code: String): Result<UserDto>
}

/** Outcome of [DriverAuthRepository.login]. See that method's doc for why this isn't `Result`. */
sealed interface DriverLoginResult {
    data class Success(val user: UserDto) : DriverLoginResult

    /** Driver account has MFA enabled; call [DriverAuthRepository.completeMfaLogin] with
     * [mfaToken] and a 6-digit TOTP code to actually finish logging in. */
    data class MfaRequired(val mfaToken: String) : DriverLoginResult
    data class Failure(val error: Throwable) : DriverLoginResult
}

class SharedPreferencesDriverAuthRepository(
    context: Context,
    private val apiService: ApiService,
) : DriverAuthRepository {

    private val prefs = context.applicationContext
        .getSharedPreferences("driver_auth_cache", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun login(driverId: String, pin: String): DriverLoginResult {
        val onlineResult = runCatching {
            apiService.driverLogin(DriverLoginRequestDto(driverCode = driverId, pin = pin))
        }
        onlineResult.onSuccess { response ->
            if (response.mfaRequired == true) {
                return response.mfaToken?.let { DriverLoginResult.MfaRequired(it) }
                    ?: DriverLoginResult.Failure(
                        IllegalStateException("Backend reported mfa_required but returned no mfa_token"),
                    )
            }
            val accessToken = response.accessToken
            val user = response.user
            if (accessToken != null && user != null) {
                AppContainer.accessToken = accessToken
                cacheDriver(driverId, pin, user)
                return DriverLoginResult.Success(user)
            }
            // Neither shape matched (unexpected server response) — fall through to the offline
            // fallback below exactly as a network failure would.
        }

        // Offline (or backend rejected/unreachable/malformed) — fall back to the cached hash
        // from this driver's last successful login on this device. TODO(security review):
        // SHA-256(driverId:pin) with no salt is adequate only because it's compared against a
        // value stored in this app's private SharedPreferences, never transmitted; revisit if the
        // cache is ever moved to a shared/exported store.
        val cachedHash = prefs.getString(hashKey(driverId), null)
        val cachedUserJson = prefs.getString(userKey(driverId), null)
        if (cachedHash != null && cachedUserJson != null && cachedHash == sha256(driverId, pin)) {
            return runCatching { json.decodeFromString<UserDto>(cachedUserJson) }
                .fold(
                    onSuccess = { DriverLoginResult.Success(it) },
                    onFailure = { DriverLoginResult.Failure(it) },
                )
        }

        return DriverLoginResult.Failure(
            onlineResult.exceptionOrNull()
                ?: IllegalStateException("No cached credentials for offline login"),
        )
    }

    override suspend fun completeMfaLogin(
        driverId: String,
        pin: String,
        mfaToken: String,
        code: String,
    ): Result<UserDto> = runCatching {
        val token = apiService.mfaLogin(MfaLoginRequestDto(mfaToken = mfaToken, code = code))
        AppContainer.accessToken = token.accessToken
        cacheDriver(driverId, pin, token.user)
        token.user
    }

    private fun cacheDriver(driverId: String, pin: String, user: UserDto) {
        prefs.edit()
            .putString(hashKey(driverId), sha256(driverId, pin))
            .putString(userKey(driverId), json.encodeToString(user))
            .apply()
    }

    /**
     * TEMPORARY debug-only helper: pre-populates this device's offline-login cache (the same
     * cache [login]'s network-failure fallback already reads from above) with a fabricated demo
     * driver record, so [login] can succeed fully offline on a device that has never reached a
     * real backend — e.g. testing the app's screens/navigation with no backend deployed yet.
     * Only called from [au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindViewModel.quickLoginDemoDriver]'s
     * debug-gated button. Remove once a reachable backend is the normal dev/test setup — this is
     * a stopgap, not a real auth path.
     */
    fun seedOfflineDemoDriver(driverId: String, pin: String) {
        cacheDriver(
            driverId,
            pin,
            UserDto(
                id = "demo-driver-offline",
                tenantId = null,
                role = "driver",
                name = "Demo Driver",
                email = driverId,
                status = "active",
                photoUrl = null,
            ),
        )
    }

    private fun hashKey(driverId: String) = "hash_$driverId"
    private fun userKey(driverId: String) = "user_$driverId"

    private fun sha256(driverId: String, pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$driverId:$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
