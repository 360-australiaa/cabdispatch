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
 * RESOLVED (process-restart session-persistence pass, 2026-09-04): the standing TODO that used to
 * sit here ("fold this into a proper SessionRepository backed by Room/DataStore ... so a process
 * restart doesn't silently drop the driver back to S1 mid-shift") is closed — see [SessionStore]
 * and [SessionHolder.attachStore]. Every field on this class is now durably persisted (a small
 * dedicated `SharedPreferences` store, [DevicePairingStore]'s own precedent, not Room/DataStore —
 * see [SessionStore]'s doc for why), and restored by
 * [au.com.threesixty.cabdispatch.data.AppContainer.init] before anything reads
 * [SessionHolder.session]. Deliberately NOT part of this: [au.com.threesixty.cabdispatch.data.AppContainer.accessToken]
 * (the bearer token) and [SessionHolder.pendingTrip] — neither is a "who/what shift" fact, and
 * [SessionStore]'s own doc explains why each is excluded on purpose rather than merely forgotten.
 */
data class DriverSession(
    val driverId: String,
    val driverName: String,
    val vehicleId: String,
    /**
     * The real fleet-vehicle UUID for [vehicleId] (a driver-entered/QR'd rego, e.g. `"KHI-01"`),
     * resolved by [au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindViewModel.bindVehicle]
     * via a live `GET /v1/fleet/vehicles` lookup — see that method's own doc. Deliberately a
     * *separate* field rather than replacing [vehicleId]: every existing display
     * ([au.com.threesixty.cabdispatch.ui.screens.shiftstart.ShiftStartScreen]'s VEHICLE row, the
     * dashboard identity chip, the inspection screen's subtitle) and every existing API call that
     * already worked against the rego string (`startShift`, duress trigger) stay exactly as they
     * were; only [au.com.threesixty.cabdispatch.domain.LivePositionHeartbeat], which found live
     * that `POST /v1/fleet/positions` 404s on anything but the real UUID, reads this field —
     * falling back to skipping that tick's publish (not to [vehicleId]) if it's `null`, the same
     * "nothing honest to send yet" posture that class already uses for a missing GPS fix. `null`
     * whenever the lookup fails or finds no match (offline bind, typo'd rego, vehicle not yet
     * seeded server-side) — a real, silently-degrading gap, not a crash.
     */
    val vehicleUuid: String? = null,
    val shiftId: String?,
    /**
     * ISO-8601 shift-start timestamp (`ShiftDto.startAt`, backend-assigned at
     * `POST /v1/shifts` time), set alongside [shiftId] by
     * [au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindViewModel.startShift].
     * Added for the dashboard's shift-duration countdown (2026-08-10 meter-polish pass) — see
     * [au.com.threesixty.cabdispatch.domain.ShiftDurationLimit]. Nullable rather than
     * non-null-with-a-sentinel for the same reason [shiftId] already is: a session can exist with
     * no real shift bound to it in this app's pre-persisted-session model (see this class's own
     * TODO above), and the countdown must degrade to "not shown" rather than a wrong number when
     * that's the case.
     */
    val shiftStartAt: String? = null,
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
    /**
     * Negotiated/fixed fare amount (decimal-as-string, per this project's money-field convention
     * — see `ApiService.kt`'s header note), set only when the driver used the dashboard's "Set
     * Price" entry point instead of a normal metered Start Meter tap (2026-08-10 meter-polish
     * pass). `null` for every ordinary metered trip — the existing/default case, unchanged.
     * Threaded through to [au.com.threesixty.cabdispatch.data.repository.TripRepository.openTrip]
     * -> [au.com.threesixty.cabdispatch.data.local.entity.TripEntity.negotiatedTotal] ->
     * `TripSyncItemDto.negotiatedTotal` (`negotiated_total` on the wire), matching the backend's
     * `TripCreate.negotiated_total` / `TripSyncItem.negotiated_total` contract field-for-field.
     * Does NOT change how the live on-screen meter ([au.com.threesixty.cabdispatch.domain.FareEngineImpl])
     * or the on-device reconstruction (`domain/fare/TripFareReconstruction.kt`) compute anything —
     * see `HANDOFF.md`'s 2026-08-10 entry for why that's a real, honestly-flagged gap and not
     * silently assumed handled.
     */
    val negotiatedTotal: String? = null,
    /**
     * Driver's local self-declaration that the bound vehicle has 5+ seats excluding the driver —
     * see [au.com.threesixty.cabdispatch.domain.MaxiVehicleStore]'s doc for why this is a
     * per-device driver declaration, not real fleet-registry data (`VehicleDto` carries no such
     * field). Captured at Start Meter time from [au.com.threesixty.cabdispatch.domain.MaxiVehicleStore]
     * (as prefilled/edited on the start-meter card or in Settings) so the value is locked in for
     * this specific trip rather than read again mid-trip. `false` (the default) for every existing
     * call site — zero behavior change for a driver who never touches the new maxi-taxi controls.
     */
    val isMaxiVehicle: Boolean = false,
    /**
     * Declared passenger count for this hiring, 1-11 — the primary legal trigger (5+ passengers)
     * for the maxi rate per the Point to Point Transport (Fares) Order 2026. Threaded through to
     * [au.com.threesixty.cabdispatch.domain.FareEngine.startTrip] and
     * [au.com.threesixty.cabdispatch.data.repository.TripRepository.openTrip]. Defaults to 1, the
     * ordinary case, matching every pre-existing call site's implicit behavior.
     */
    val passengerCount: Int = 1,
    /**
     * True when the hiring is for a passenger travelling in a wheelchair — the Fares Order
     * carve-out that means the maxi rate is never charged regardless of [isMaxiVehicle]/
     * [passengerCount]/[airportRankRequestedMaxi]. Defaults false, unchanged for any call site
     * that doesn't set it.
     */
    val wheelchairHiring: Boolean = false,
    /**
     * True only when the hirer specifically requested a maxi taxi at a Sydney Airport rank — the
     * one scenario where the maxi rate applies independent of [passengerCount]. Honestly scoped:
     * this app has no rank-location detection, so this is a driver-declared checkbox meaningful
     * only in that specific circumstance, never a general "airport trip" flag. Defaults false.
     */
    val airportRankRequestedMaxi: Boolean = false,
    /**
     * Human-readable pickup/drop-off address strings for the Meter screen's Trip Details card
     * (Phase A.4, 2026-09-03) — sourced from [au.com.threesixty.cabdispatch.data.remote.JobDto.originAddress]/
     * `.destAddress` when this trip started from a dispatch offer accepted via
     * [au.com.threesixty.cabdispatch.ui.screens.availabletrips.AvailableTripOfferViewModel.beginHiredHandoff].
     * Still `null` for a trip started via the dashboard's own Start Meter/Set Price flow (a street
     * hail or rank job has no dispatch-offer address to carry), and — a real, currently-unclosed
     * gap flagged rather than silently worked around — for one accepted via
     * [au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelViewModel.beginHiredHandoff]
     * (the Dispatch wheel-content pane's own accept path), which was out of this pass's edit scope
     * and still builds [TripContext] without these fields. [HiredScreen]'s Trip Details card must
     * show "—" for a `null` address in every case, never fabricate one.
     */
    val originAddress: String? = null,
    val destAddress: String? = null,
)

/**
 * In-memory session/hand-off holder for the S1->S2->S3 flow. [session] itself is durable — see
 * [DriverSession]'s doc and [attachStore] below — but [pendingTrip] is deliberately not: it is a
 * same-process hand-off point (see that property's own doc), not session state to survive a
 * restart, and [au.com.threesixty.cabdispatch.data.repository.TripRepository]/`TripEntity` is
 * already the durable record of an in-progress trip's own data.
 */
object SessionHolder {
    private val _session = MutableStateFlow<DriverSession?>(null)
    val session: StateFlow<DriverSession?> = _session.asStateFlow()

    /**
     * Durable half of [session] — `null` until
     * [au.com.threesixty.cabdispatch.data.AppContainer.init] calls [attachStore], so this object
     * keeps behaving exactly as it always did (in-memory only) for any test or preview that never
     * goes through [au.com.threesixty.cabdispatch.data.AppContainer] — same reasoning as
     * [DeviceCommandHeartbeat] taking [DevicePairingStore] as a constructor param rather than
     * reaching for a global.
     */
    private var store: SessionStore? = null

    /**
     * Wires up durable persistence for [session]. Call exactly once, from
     * [au.com.threesixty.cabdispatch.data.AppContainer.init], before that method restores any
     * persisted session — [set] below writes straight through to [store] once attached, so
     * attaching late would silently drop whatever [set] calls happened before it.
     */
    fun attachStore(sessionStore: SessionStore) {
        store = sessionStore
    }

    fun set(session: DriverSession) {
        _session.value = session
        // Write-through, not write-behind: this is the ONLY call site of `set` in the app (S1's
        // shift-start success), so persisting here — rather than asking every future call site to
        // remember a second call, the way DevicePairingStore's callers currently have to for
        // SessionHolder.deviceId — means a new call site can never forget it.
        store?.save(session)
    }

    private val _pendingTrip = MutableStateFlow<TripContext?>(null)
    val pendingTrip: StateFlow<TripContext?> = _pendingTrip.asStateFlow()

    fun setPendingTrip(context: TripContext) {
        _pendingTrip.value = context
    }

    /**
     * Withdraws a pending trip WITHOUT touching [session]/[deviceId] — added for the Captain Taxis
     * dashboard's Start Meter transition (2026-08-29): tapping START METER calls
     * [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardViewModel.startMeter]
     * immediately (it already writes [pendingTrip] synchronously) and then shows a brief on-dial
     * "STARTING…" transition before navigating to S3/Hired — a real screen, not a fabricated delay,
     * see that composable's own doc — during which CANCEL is offered. If the driver cancels before
     * that navigation happens, [pendingTrip] must not linger for the next Start Meter tap (or worse,
     * for [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel] to pick up on some later,
     * unrelated navigation to S3) to accidentally consume. The blunt [clear] above is NOT a
     * substitute here — it also wipes [session] and [deviceId], which a driver merely changing their
     * mind about one Start Meter tap must not trigger.
     */
    fun clearPendingTrip() {
        _pendingTrip.value = null
    }

    /**
     * Observable form of [deviceId], added by the 2026-08-29 fleet-command pass so
     * [au.com.threesixty.cabdispatch.domain.DeviceCommandHeartbeat] can *react* to this tablet
     * being paired (or reset) instead of sampling it once at process start. Without this, a tablet
     * paired mid-session would need an app restart before any admin command (kiosk lock, locate,
     * force update) could ever reach it — the same "read once, then never again" shape as the bug
     * that pass was fixing, just one layer down.
     *
     * [deviceId] below is kept as a plain `var` facade over this flow precisely so every existing
     * read/write site — [au.com.threesixty.cabdispatch.data.AppContainer.init]'s cold-start
     * restore, S6's pairing/factory-reset paths, [clear] — compiles and behaves exactly as before.
     */
    private val _deviceId = MutableStateFlow<String?>(null)
    val deviceIdFlow: StateFlow<String?> = _deviceId.asStateFlow()

    /**
     * Server-assigned `DeviceDto.id` for this tablet, once paired — the address every
     * `/v1/fleet/devices/{id}/…` call is made against, including the device heartbeat that is this
     * app's only channel for admin commands (see [DeviceCommandHeartbeat]).
     *
     * Set in exactly two places: S6's Pair Meter flow after a successful
     * `POST /v1/fleet/devices/register`
     * ([au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel.submitPairingCode],
     * landed 2026-08-28), and [au.com.threesixty.cabdispatch.data.AppContainer.init]'s cold-start
     * restore from [DevicePairingStore] — the earlier standing TODO here ("always null until that
     * pairing flow lands") is resolved and has been removed rather than left to mislead. Cleared in
     * exactly one place, S6's admin-PIN-gated factory reset (together with [DevicePairingStore.clear],
     * so the id does not simply come back on the next cold start) — deliberately NOT in [clear],
     * see that method's doc. Still
     * legitimately `null` on a never-paired or factory-reset tablet, and every consumer must treat
     * that as "device not registered" and degrade gracefully rather than crash/error: S6 shows its
     * tap-to-pair affordance, [DeviceCommandHeartbeat] simply does not poll.
     */
    var deviceId: String?
        get() = _deviceId.value
        set(value) {
            _deviceId.value = value
        }

    /**
     * Wipes in-memory *driver-session* identity — the signed-in driver and any pending trip
     * hand-off. Called by S6's admin-PIN-gated factory reset alongside clearing
     * [au.com.threesixty.cabdispatch.data.AppContainer.accessToken] and the Room database, and
     * ALSO by the ordinary end-of-shift "Done — Log Off" button
     * ([au.com.threesixty.cabdispatch.ui.screens.shiftsubmitted.ShiftSubmittedScreen]), which is
     * the common case: every shift ends here.
     *
     * ### Why [deviceId] is deliberately NOT cleared here
     * Device pairing is **device** identity, not **driver-session** identity: it is the tablet's
     * server-assigned registration, it survives process death in [DevicePairingStore], and it is
     * meaningful with nobody logged in at all. It is also the poll gate for
     * [DeviceCommandHeartbeat] (which supervises [deviceIdFlow]), so nulling it here would cancel
     * command delivery on every ordinary log-off: driver ends their shift, the process stays alive
     * on the login screen, an admin toggles `kiosk_locked` on the dashboard, and nothing ever
     * reaches the tablet. That is exactly the production bug the 2026-08-29 fleet-command pass
     * exists to fix, so this method must not reintroduce it one layer down.
     *
     * The one caller that genuinely *should* unpair — the factory reset — does it explicitly and
     * durably next to its other teardown
     * ([au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel.attemptFactoryReset]:
     * `SessionHolder.deviceId = null` plus [DevicePairingStore.clear]), rather than relying on a
     * side effect of a method that also runs at the end of every shift.
     *
     * Also drops the durable copy in [store] (see [attachStore]) — both callers of this method
     * (end-of-shift log-off, factory reset) are cases where a next cold start must NOT resume the
     * driver back into the session being cleared here; the ordinary end-of-shift case in
     * particular is the same "common case: every shift ends here" this method's own opening line
     * already documents, so leaving stale prefs behind after every normal log-off would have
     * defeated the point of persisting in the first place.
     */
    fun clear() {
        _session.value = null
        _pendingTrip.value = null
        store?.clear()
    }
}
