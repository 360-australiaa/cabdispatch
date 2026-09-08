package au.com.threesixty.cabdispatch.domain

import android.content.Context
import android.content.SharedPreferences
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.DriverLoginRequestDto
import au.com.threesixty.cabdispatch.data.remote.MfaLoginRequestDto
import au.com.threesixty.cabdispatch.data.remote.UserDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

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

/**
 * ### The offline fallback is a connectivity fallback, not a failure fallback (security finding X1)
 *
 * This class used to fall back to the cached PIN hash on **any** failure of the online call, and
 * said so in its own comment: *"Offline (or backend rejected/unreachable/malformed) — fall back to
 * the cached hash."* That is an authorisation bypass. The server's 401/403 is precisely how a depot
 * revokes a driver — deactivating the account, suspending them, or pulling their authority — and
 * treating that answer as interchangeable with "the tablet has no signal" meant a revoked driver
 * kept logging in successfully, forever, on any tablet they had ever used. On a regulated meter the
 * person operating it must be someone the operator still authorises.
 *
 * So [login] now branches on *why* the call failed:
 *
 * | Outcome | Meaning | Behaviour |
 * |---|---|---|
 * | [IOException] (incl. `SocketTimeoutException`) | we never got an answer | offline fallback runs |
 * | [HttpException] 401/403 | the server answered "no" | cache **cleared**, login fails |
 * | any other [HttpException] (5xx, 422, …) | the server answered something else | login fails, cache untouched |
 * | 2xx with an unrecognised body | the server answered, malformed | login fails, cache untouched |
 *
 * Only the first row is a genuine "we cannot reach the authority" case, which is the only case the
 * offline cache was ever meant to cover. The 401/403 row actively **clears** the cached hash: a
 * driver whose authority was revoked while the tablet had signal must not keep an offline credential
 * on that tablet afterwards, or airplane mode becomes the bypass.
 *
 * Note the deliberate asymmetry on the "some other error" rows — a 500 does not clear the cache
 * (a server outage must not lock out a legitimate driver) but does not grant a login either
 * (an outage is not evidence of authority). Failing closed without destroying state is the right
 * answer for an answer we cannot interpret.
 */
class SharedPreferencesDriverAuthRepository internal constructor(
    private val prefs: SharedPreferences,
    private val apiService: ApiService,
    /**
     * The tenant slug to send with a driver login, read lazily so tests can supply one without
     * standing up [AppContainer]. Injected rather than reached for: the login path is the one
     * place in this class that must work identically in a unit test and on a tablet.
     */
    private val tenantSlug: () -> String? = { AppContainer.devicePairingStore.getTenantSlug() },
) : DriverAuthRepository {

    constructor(context: Context, apiService: ApiService) : this(
        AuthCachePrefs.open(context),
        apiService,
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun login(driverId: String, pin: String): DriverLoginResult {
        // The backend requires `tenant_slug`: driver codes are unique per tenant, not
        // platform-wide. The tablet learns it when it pairs. If it is missing the request would
        // fail with a 422 whose message names a field no driver has ever heard of, so fail here
        // instead and say the actual thing that is wrong -- the tablet is not paired.
        val slug = tenantSlug()
        if (slug.isNullOrBlank()) {
            return DriverLoginResult.Failure(
                IllegalStateException(
                    "This tablet is not paired to an operator yet. Pair it in Settings before signing in.",
                ),
            )
        }
        val onlineResult = runCatching {
            apiService.driverLogin(
                DriverLoginRequestDto(driverCode = driverId, pin = pin, tenantSlug = slug),
            )
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
                // Real gap closed 2026-09-06: this field existed on the response the whole time
                // and was simply never read — see AppContainer.refreshToken's doc for the 401s
                // that went unrecovered without it.
                AppContainer.refreshToken = response.refreshToken
                cacheDriver(driverId, pin, user)
                return DriverLoginResult.Success(user)
            }
            // The server answered 2xx with a body matching neither the success nor the MFA shape.
            // Pre-X1 this fell through to the offline cache "exactly as a network failure would";
            // it is not a network failure — we reached the authority and could not read its reply,
            // which is a bug or a version skew, not evidence that this driver is authorised.
            return DriverLoginResult.Failure(
                IllegalStateException(
                    "Driver login returned neither tokens nor an MFA challenge",
                ),
            )
        }

        val error = onlineResult.exceptionOrNull()
            ?: IllegalStateException("Driver login failed without an exception")

        if (error is HttpException && error.code() in REVOCATION_CODES) {
            // The depot has said no. Drop this driver's offline credential so the answer sticks
            // even once the tablet goes offline again — see the class doc.
            clearCachedDriver(driverId)
            return DriverLoginResult.Failure(error)
        }

        if (error !is IOException) {
            // Some other real answer from the server (5xx, 422, a decode failure on a non-2xx
            // body...). Fail closed, but leave the cache alone — see the class doc's note on why
            // an outage must neither authorise nor lock out.
            return DriverLoginResult.Failure(error)
        }

        // Genuinely could not reach the server. This — and only this — is what the offline cache
        // is for: fall back to the hash written by this driver's last successful login here.
        val cachedRecord = prefs.getString(hashKey(driverId), null)
        val cachedUserJson = prefs.getString(userKey(driverId), null)
        if (cachedRecord != null && cachedUserJson != null &&
            OfflinePinHasher.verify(driverId, pin, cachedRecord)
        ) {
            return runCatching { json.decodeFromString<UserDto>(cachedUserJson) }
                .fold(
                    onSuccess = { DriverLoginResult.Success(it) },
                    onFailure = { DriverLoginResult.Failure(it) },
                )
        }

        return DriverLoginResult.Failure(error)
    }

    override suspend fun completeMfaLogin(
        driverId: String,
        pin: String,
        mfaToken: String,
        code: String,
    ): Result<UserDto> = runCatching {
        val token = apiService.mfaLogin(MfaLoginRequestDto(mfaToken = mfaToken, code = code))
        AppContainer.accessToken = token.accessToken
        AppContainer.refreshToken = token.refreshToken
        cacheDriver(driverId, pin, token.user)
        token.user
    }

    /**
     * Writes (or rewrites) this driver's offline credential.
     *
     * Called only from the two **online** success paths ([login]'s 2xx branch and
     * [completeMfaLogin]), which is what makes the X2 legacy migration lazy and automatic: any
     * pre-X2 unsalted SHA-256 entry is overwritten with a PBKDF2 record the next time its owner
     * logs in with a working network, without a migration pass, a version flag, or anything for the
     * driver to do. See [OfflinePinHasher]'s "Legacy records" note for why there is deliberately no
     * offline migration path.
     */
    private fun cacheDriver(driverId: String, pin: String, user: UserDto) {
        prefs.edit()
            .putString(hashKey(driverId), OfflinePinHasher.hash(driverId, pin, deviceSalt()))
            .putString(userKey(driverId), json.encodeToString(user))
            .apply()
    }

    /** Drops one driver's offline credential — see the class doc's 401/403 row. Both keys go: a
     * cached user record with no hash is unusable, and leaving the driver's name and id sitting on
     * a tablet they are no longer authorised to operate serves no purpose. */
    private fun clearCachedDriver(driverId: String) {
        prefs.edit()
            .remove(hashKey(driverId))
            .remove(userKey(driverId))
            .apply()
    }

    /**
     * This tablet's PBKDF2 salt, generated once on first use and persisted alongside the hashes.
     *
     * Per-device rather than per-entry: the threat X2 is about is someone who has read this prefs
     * file off one lost or rooted tablet, and a salt they can also read does not stop *that*
     * attacker — what it stops is the fleet-wide attack, where one precomputed table of a million
     * PINs cracks every tablet in the depot at once. A per-device salt is enough for that, and it
     * is what the workstream brief specifies. (The salt is still written into every record by
     * [OfflinePinHasher.hash], so records stay self-describing and a future move to per-entry salts
     * needs no migration.)
     */
    private fun deviceSalt(): ByteArray {
        prefs.getString(KEY_SALT, null)
            ?.let { OfflinePinHasher.decodeSalt(it) }
            ?.let { return it }

        val salt = OfflinePinHasher.newSalt()
        prefs.edit().putString(KEY_SALT, OfflinePinHasher.encodeSalt(salt)).apply()
        return salt
    }

    private fun hashKey(driverId: String) = "hash_$driverId"
    private fun userKey(driverId: String) = "user_$driverId"

    private companion object {
        /** 401 Unauthorized / 403 Forbidden — the two ways `POST /v1/auth/driver-login` says
         * "this driver may not log in", as opposed to failing to answer at all. */
        val REVOCATION_CODES = setOf(401, 403)

        const val KEY_SALT = "pin_salt"
    }
}
