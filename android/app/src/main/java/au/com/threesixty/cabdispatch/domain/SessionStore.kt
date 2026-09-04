package au.com.threesixty.cabdispatch.domain

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant

/**
 * Persists [DriverSession] across process death — the fix for the standing gap [DriverSession]'s
 * own doc used to flag: session state lived only in [SessionHolder]'s in-memory
 * `MutableStateFlow`, so a tablet killed by the OS under memory pressure (routine on an all-day
 * meter tablet backgrounded for hours) came back on
 * [au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindScreen] with no memory of the
 * driver having been mid-shift at all — a real, disruptive driver-experience bug, not a
 * hypothetical one.
 *
 * Mirrors [DevicePairingStore]'s own `getSharedPreferences(..., MODE_PRIVATE)` pattern — the
 * existing precedent for small durable state in this app — in a *separate* prefs file
 * ([PREFS_NAME]), not folded into [DevicePairingStore]: that class is *device* identity (survives
 * every driver logging in and out, meaningful with nobody signed in at all); this is *driver-
 * session* identity (wiped on every ordinary log-off — see [SessionHolder.clear]'s doc for exactly
 * why [DevicePairingStore] is deliberately NOT wiped there). Conflating the two would make
 * [DevicePairingStore.clear]'s factory-reset-only semantics and this class's every-shift-end
 * semantics fight over the same file.
 *
 * ### What is deliberately NOT persisted here
 * - [au.com.threesixty.cabdispatch.data.AppContainer.accessToken] (the bearer token): this store
 *   holds no credential at all, on purpose. [DriverSession] itself carries no token/PIN/credential
 *   either — it is who-and-what-shift metadata, not an auth artifact — so restoring it does not
 *   let this app "act as the driver" any more than an in-memory session already could; the actual
 *   authentication boundary
 *   ([SharedPreferencesDriverAuthRepository]'s offline-PIN cache, and the online bearer token) is
 *   completely untouched by this class. A resumed session with no access token simply means calls
 *   that need one 401 until a fresh one is issued — an honest, already-familiar shape in this app
 *   (see e.g. [DeviceCommandHeartbeat]'s own doc on the parked-tablet token gap it had to work
 *   around for a *different* endpoint), not a new failure mode invented here. There is also no
 *   existing wired token-refresh path to hook a silent resume-refresh into:
 *   [au.com.threesixty.cabdispatch.data.remote.ApiService.refresh] is declared on the interface but
 *   is never called anywhere in this app, and the refresh token
 *   [au.com.threesixty.cabdispatch.data.remote.TokenResponseDto] carries back from login is never
 *   even captured by [SharedPreferencesDriverAuthRepository]. Wiring a real silent-refresh-on-resume
 *   path is a bigger, separate change through files outside this pass's scope (`ApiService.kt`,
 *   `DriverAuthRepository.kt`), not something to bolt on silently in here.
 * - [SessionHolder.pendingTrip]/[TripContext]: a same-process S2->S3 hand-off, not session state —
 *   see that property's own doc. Once a trip has actually started, its data already lives durably
 *   in Room ([au.com.threesixty.cabdispatch.data.repository.TripRepository]/`TripEntity`); a driver
 *   bounced back to S2 by a process restart mid-trip finds their way back into it through the same
 *   navigation any other S2->S3 return already uses, not through a replayed [TripContext] — a
 *   deliberate scope-down (see this pass's own notes) rather than a risky forced mid-fare resume.
 * - [SessionHolder.deviceId]/device-secret/command flags: already durable, in [DevicePairingStore],
 *   which is device identity — see above.
 *
 * ### Staleness
 * [restore] re-checks the persisted shift against [ShiftDurationLimit] — the same 12h fatigue-limit
 * estimate the dashboard's own countdown chip already uses — rather than trusting a shift that was
 * merely open before the process died. A tablet parked or killed for hours (or days) must not
 * resume pretending a shift from before that gap is still open: once
 * [ShiftDurationLimit.remaining] says the persisted shift is past its limit, [restore] drops
 * [DriverSession.shiftId]/[DriverSession.shiftStartAt] — but keeps driver identity and vehicle
 * binding — landing the driver on the dashboard able to start a fresh shift rather than silently
 * carrying an overdue one forward. Reusing [ShiftDurationLimit] here, rather than inventing a
 * second, possibly-disagreeing threshold, is deliberate — see that object's own honesty note about
 * its hardcoded 12h mirror of the backend default.
 *
 * No equivalent revalidation exists for the vehicle binding itself (a rego/UUID that may have since
 * been reassigned or edited server-side): this app has never revalidated a bound vehicle mid-session
 * even before this class existed (an in-memory [DriverSession] was trusted for the rest of the
 * process the same way), so restoring it unchecked across a restart continues an existing trust
 * boundary rather than introducing a new one.
 */
class SessionStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    /** Persists every field [DriverSession] carries — see this class's own doc for what's excluded
     * (nothing on [DriverSession] itself is; the exclusions are fields that live elsewhere
     * entirely, e.g. the bearer token). */
    fun save(session: DriverSession) {
        prefs.edit()
            .putString(KEY_DRIVER_ID, session.driverId)
            .putString(KEY_DRIVER_NAME, session.driverName)
            .putString(KEY_VEHICLE_ID, session.vehicleId)
            .putString(KEY_VEHICLE_UUID, session.vehicleUuid)
            .putString(KEY_SHIFT_ID, session.shiftId)
            .putString(KEY_SHIFT_START_AT, session.shiftStartAt)
            .apply()
    }

    /**
     * Rebuilds the persisted [DriverSession], or `null` if nothing has ever been saved (or been
     * [clear]ed since). [now] defaults to the real current instant, overridable purely for
     * tests — the same convention [ShiftDurationLimit.remaining] itself already uses.
     *
     * [KEY_DRIVER_ID]/[KEY_DRIVER_NAME]/[KEY_VEHICLE_ID] are treated as all-or-nothing: every real
     * [save] call writes all three (they're non-null on [DriverSession] itself), so a partial
     * record only happens if the prefs file was corrupted, hand-edited, or written by an
     * incompatible future version — treated the same as "nothing saved" rather than reconstructing
     * a half session.
     *
     * See this class's own "Staleness" section for the [ShiftDurationLimit] check below.
     */
    fun restore(now: Instant = Instant.now()): DriverSession? {
        val driverId = prefs.getString(KEY_DRIVER_ID, null) ?: return null
        val driverName = prefs.getString(KEY_DRIVER_NAME, null) ?: return null
        val vehicleId = prefs.getString(KEY_VEHICLE_ID, null) ?: return null
        val shiftId = prefs.getString(KEY_SHIFT_ID, null)
        val shiftStartAt = prefs.getString(KEY_SHIFT_START_AT, null)

        val shiftExpired = ShiftDurationLimit.remaining(shiftStartAt, now)?.isNegative == true

        return DriverSession(
            driverId = driverId,
            driverName = driverName,
            vehicleId = vehicleId,
            vehicleUuid = prefs.getString(KEY_VEHICLE_UUID, null),
            shiftId = if (shiftExpired) null else shiftId,
            shiftStartAt = if (shiftExpired) null else shiftStartAt,
        )
    }

    /** Wipes the persisted session — see [SessionHolder.clear]'s doc for the two call sites (every
     * ordinary end-of-shift log-off, and factory reset) that both land here. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "driver_session"
        const val KEY_DRIVER_ID = "driver_id"
        const val KEY_DRIVER_NAME = "driver_name"
        const val KEY_VEHICLE_ID = "vehicle_id"
        const val KEY_VEHICLE_UUID = "vehicle_uuid"
        const val KEY_SHIFT_ID = "shift_id"
        const val KEY_SHIFT_START_AT = "shift_start_at"
    }
}
