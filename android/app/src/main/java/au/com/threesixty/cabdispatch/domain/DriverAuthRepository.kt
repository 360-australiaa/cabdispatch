package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.DriverLoginRequestDto
import au.com.threesixty.cabdispatch.data.remote.MfaLoginRequestDto
import au.com.threesixty.cabdispatch.data.remote.UserDto

/**
 * Driver ID + PIN login. Owner decision, 2026-09-16: every sign-in must be a genuine,
 * server-verified login — there is no offline fallback any more.
 *
 * This class used to cache a salted-PBKDF2 PIN hash on-device (spec B5 S1: "local validation
 * against a cached driver record, falls back to a stored hash so login works fully offline once a
 * driver has logged in once"), with its own real security hardening (finding X1: only a genuine
 * `IOException` — no signal at all — fell back to the cache, never a server-issued 401/403, which
 * would have let a revoked driver keep logging in offline forever; finding X2: the cached hash was
 * salted PBKDF2, not a bare digest). The owner asked for that whole fallback removed outright: a
 * driver with no connectivity now simply cannot start a shift, full stop — an explicit trade-off
 * the operator made, not a gap. See git history on this file (before 2026-09-16) for the removed
 * implementation and its own X1/X2 tests if that path is ever revisited.
 *
 * Calls the real `POST /v1/auth/driver-login` endpoint ([ApiService.driverLogin],
 * `driverId` -> `driver_code`).
 *
 * [login] returns a [DriverLoginResult], not `Result<UserDto>`, because the backend endpoint can
 * answer three ways, not two: a normal success, an MFA-challenge (driver account has TOTP enabled
 * — see shared/API_SUMMARY.md "Admin MFA (TOTP)"; the driver-login endpoint shares the exact same
 * two-step contract staff login would use), or a failure. A plain `Result` only has two branches,
 * so it can't carry the MFA case without overloading `onFailure` for something that isn't actually
 * a failure (the request succeeded; the driver just isn't logged in yet). [completeMfaLogin] is
 * step two, only reachable from [DriverLoginResult.MfaRequired].
 */
interface DriverAuthRepository {
    suspend fun login(driverId: String, pin: String): DriverLoginResult

    /** Step two of the MFA challenge from [login]. */
    suspend fun completeMfaLogin(mfaToken: String, code: String): Result<UserDto>
}

/** Outcome of [DriverAuthRepository.login]. See that method's doc for why this isn't `Result`. */
sealed interface DriverLoginResult {
    data class Success(val user: UserDto) : DriverLoginResult

    /** Driver account has MFA enabled; call [DriverAuthRepository.completeMfaLogin] with
     * [mfaToken] and a 6-digit TOTP code to actually finish logging in. */
    data class MfaRequired(val mfaToken: String) : DriverLoginResult
    data class Failure(val error: Throwable) : DriverLoginResult
}

/** The real, online-only driver-login flow — see [DriverAuthRepository]'s own doc. */
class OnlineDriverAuthRepository(
    private val apiService: ApiService,
    /**
     * The tenant slug to send with a driver login, read lazily so tests can supply one without
     * standing up [AppContainer]. Injected rather than reached for: the login path is the one
     * place in this class that must work identically in a unit test and on a tablet.
     */
    private val tenantSlug: () -> String? = { AppContainer.devicePairingStore.getTenantSlug() },
) : DriverAuthRepository {

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
                // The NSW toll registry is fetched at boot, but a never-logged-in tablet gets a
                // 401 there and nothing retried it -- so on a fresh device the simulator listed
                // no toll routes and auto-toll detection had no gantries (second tablet,
                // 2026-09-08). The first authenticated moment is the right one to fetch it.
                AppContainer.refreshTollRegistry()
                // The airport-fee zones are tenant-scoped, so the first authenticated moment is
                // the FIRST moment they can be fetched at all — see AirportZoneCache's doc.
                AppContainer.refreshAirportZones()
                // Same "never-logged-in tablet gets a 401 at boot" gap as the toll registry above
                // applies to the live traffic cameras/hazards too (live-map redesign, 2026-09-09) —
                // authenticated but not tenant-scoped, so login is the first moment this succeeds,
                // not the first moment it's relevant.
                AppContainer.refreshTrafficData()
                // Real gap closed 2026-09-06: this field existed on the response the whole time
                // and was simply never read — see AppContainer.refreshToken's doc for the 401s
                // that went unrecovered without it.
                AppContainer.refreshToken = response.refreshToken
                return DriverLoginResult.Success(user)
            }
            // The server answered 2xx with a body matching neither the success nor the MFA shape
            // -- we reached the authority and could not read its reply, which is a bug or a
            // version skew, not evidence that this driver is authorised.
            return DriverLoginResult.Failure(
                IllegalStateException(
                    "Driver login returned neither tokens nor an MFA challenge",
                ),
            )
        }

        return DriverLoginResult.Failure(
            onlineResult.exceptionOrNull() ?: IllegalStateException("Driver login failed without an exception"),
        )
    }

    override suspend fun completeMfaLogin(mfaToken: String, code: String): Result<UserDto> = runCatching {
        val token = apiService.mfaLogin(MfaLoginRequestDto(mfaToken = mfaToken, code = code))
        AppContainer.accessToken = token.accessToken
        AppContainer.refreshToken = token.refreshToken
        token.user
    }
}
