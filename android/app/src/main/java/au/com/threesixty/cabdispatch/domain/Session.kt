package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.TariffDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Identity of "who is signed in on this device right now" — separate from
 * [au.com.threesixty.cabdispatch.data.AppContainer.accessToken], which only
 * carries the auth bearer token. Set once at the end of S1 (Login/Vehicle
 * bind), read by S2 (Idle) and S3 (Hired).
 *
 * TODO(integration agent): fold this into a proper SessionRepository backed
 * by Room/DataStore once the auth/session sibling agent's schema lands, so a
 * process restart doesn't silently drop the driver back to S1 mid-shift.
 */
data class DriverSession(
    val driverId: String,
    val driverName: String,
    val vehicleId: String,
    val shiftId: String?,
)

/**
 * Hand-off payload from S2 -> S3 for the trip about to start. Route constants
 * in [au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes] carry no
 * nav-graph arguments (per the foundation contract's verbatim route
 * strings), so [SessionHolder.pendingTrip] is the hand-off point instead of a
 * nav argument.
 */
data class TripContext(
    val clientUuid: String,
    val tariff: TariffDto,
    val startLat: Double,
    val startLng: Double,
    val driverId: String,
    val vehicleId: String,
    val shiftId: String?,
)

/**
 * Minimal in-memory session/hand-off holder for the S1->S2->S3 flow. Not a
 * substitute for real persisted session state — see [DriverSession] doc.
 */
object SessionHolder {
    private val _session = MutableStateFlow<DriverSession?>(null)
    val session: StateFlow<DriverSession?> = _session.asStateFlow()

    fun set(session: DriverSession) {
        _session.value = session
    }

    private val _pendingTrip = MutableStateFlow<TripContext?>(null)
    val pendingTrip: StateFlow<TripContext?> = _pendingTrip.asStateFlow()

    fun setPendingTrip(context: TripContext) {
        _pendingTrip.value = context
    }

    /**
     * Server-assigned `DeviceDto.id` for this tablet, once paired — read by
     * S6 (Settings/Diagnostics) for its device-heartbeat / force-update
     * lookup. Added by the S4-S6 agent: no sibling screen calls
     * `ApiService.registerDevice` yet (S1's vehicle-bind step is QR/manual
     * vehicle-ID entry only, not a `/v1/fleet/devices/register` call), so
     * this is always null until that pairing flow lands. S6 must treat null
     * as "device not registered" and degrade gracefully, not crash/error —
     * see `ui/screens/settings/SettingsViewModel.kt`.
     *
     * TODO(integration agent): once S1 (or a dedicated device-pairing step)
     * calls `registerDevice`, set this alongside [set] above.
     */
    var deviceId: String? = null

    /**
     * Wipes in-memory session identity — called by S6's admin-PIN-gated
     * factory reset alongside clearing [au.com.threesixty.cabdispatch.data.AppContainer.accessToken]
     * and the Room database, so a reset device comes back up exactly like a
     * fresh install at S1. Added by the S4-S6 agent.
     */
    fun clear() {
        _session.value = null
        _pendingTrip.value = null
        deviceId = null
    }
}
