package au.com.threesixty.cabdispatch.domain

import android.content.Context
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.LoginRequestDto
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
 * NOTE(integration agent): [ApiService.login] takes [LoginRequestDto]
 * (email/password — the fleet-dashboard admin login contract). The spec
 * calls for Driver ID + PIN on the meter. This implementation maps
 * `driverId -> email` and `pin -> password` as a placeholder so S1 has a
 * real call to make; reconcile with the backend team on whether the meter
 * gets its own `/v1/auth/driver-login` endpoint before ship.
 */
interface DriverAuthRepository {
    suspend fun login(driverId: String, pin: String): Result<UserDto>
}

class SharedPreferencesDriverAuthRepository(
    context: Context,
    private val apiService: ApiService,
) : DriverAuthRepository {

    private val prefs = context.applicationContext
        .getSharedPreferences("driver_auth_cache", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun login(driverId: String, pin: String): Result<UserDto> {
        val onlineResult = runCatching {
            apiService.login(LoginRequestDto(email = driverId, password = pin))
        }
        onlineResult.onSuccess { token ->
            AppContainer.accessToken = token.accessToken
            cacheDriver(driverId, pin, token.user)
            return Result.success(token.user)
        }

        // Offline (or backend rejected/unreachable) — fall back to the
        // cached hash from this driver's last successful login on this
        // device. TODO(security review): SHA-256(driverId:pin) with no salt
        // is adequate only because it's compared against a value stored in
        // this app's private SharedPreferences, never transmitted; revisit
        // if the cache is ever moved to a shared/exported store.
        val cachedHash = prefs.getString(hashKey(driverId), null)
        val cachedUserJson = prefs.getString(userKey(driverId), null)
        if (cachedHash != null && cachedUserJson != null && cachedHash == sha256(driverId, pin)) {
            return runCatching { json.decodeFromString<UserDto>(cachedUserJson) }
        }

        return Result.failure(
            onlineResult.exceptionOrNull()
                ?: IllegalStateException("No cached credentials for offline login"),
        )
    }

    private fun cacheDriver(driverId: String, pin: String, user: UserDto) {
        prefs.edit()
            .putString(hashKey(driverId), sha256(driverId, pin))
            .putString(userKey(driverId), json.encodeToString(user))
            .apply()
    }

    private fun hashKey(driverId: String) = "hash_$driverId"
    private fun userKey(driverId: String) = "user_$driverId"

    private fun sha256(driverId: String, pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$driverId:$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
