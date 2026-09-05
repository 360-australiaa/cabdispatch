package au.com.threesixty.cabdispatch.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.PositionPublishRequestDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Ambient "vehicle.heartbeat" position publish — the Taxi Meter SaaS Complete Blueprint's own
 * WebSocket spec (§6.2.2) calls for this literally: "vehicle.heartbeat -> Every 30 seconds: GPS,
 * status, battery" while a vehicle is on shift. Today `POST /v1/fleet/positions`
 * ([ApiService.publishPosition]) is only ever called *reactively* — in response to an admin's MDM
 * "locate" request, see
 * [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel.respondToLocateRequest] —
 * so a dispatcher watching the fleet dashboard's Live Map sees no moving dot for a driver just
 * driving around normally between locate requests. This class closes that gap: while a shift is
 * open, publish the device's current real fix on a fixed interval, with no admin action needed.
 *
 * Registered as a process-lifetime [au.com.threesixty.cabdispatch.data.AppContainer] singleton —
 * same reasoning as [DuressController] (a background loop that must keep running across screen
 * navigation, not tied to any one screen's ViewModel scope) — but *self-supervising* rather than
 * externally trigger()/cancel()-driven: it observes [SessionHolder.session] itself and starts/stops
 * its own publish loop accordingly, the same self-supervising shape
 * `domain/location/RealLocationProvider.kt`'s `supervisePermission` uses (there: gated on
 * location-permission grant; here: gated on a shift being open). This means no screen/ViewModel
 * needs to remember to call anything when a shift starts or ends — [start] just needs to be called
 * once, from [au.com.threesixty.cabdispatch.data.AppContainer.init], and this class reacts to
 * [SessionHolder] on its own for the rest of the process lifetime.
 *
 * ### Why "shift open", not "marked available for offers"
 * The blueprint's own wording is "while a vehicle is on shift" — an ambient presence signal, not
 * "while marked available for offers". A driver who is on shift but temporarily unavailable (on a
 * break, mid-trip, whatever the still-unwired "For Hire" toggle broadcast eventually becomes — see
 * HANDOFF.md's "Availability broadcast not wired") should still show *somewhere* on the Live Map;
 * gating this on availability instead of shift state would make a busy driver disappear from the
 * dispatcher's view entirely, which is a worse outcome than an ambient dot with an honest
 * [HEARTBEAT_STATUS] placeholder (same reasoning [SettingsViewModel.respondToLocateRequest] already
 * documents for its own placeholder `status`). [SessionHolder.session]'s `shiftId` being non-null
 * is this app's one existing, reliable "on shift" signal: set exactly once a real shift opens
 * ([au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindViewModel.startShift]) and
 * cleared exactly once a shift is submitted
 * ([au.com.threesixty.cabdispatch.ui.screens.shiftsubmitted.ShiftSubmittedScreen]'s DONE button
 * calls [SessionHolder.clear]) — see [DriverSession]'s own doc for that lifecycle. Checked via
 * `session?.shiftId != null` rather than a bare `session != null` null-check, matching the same
 * defensive style [au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftReportViewModel]
 * already uses for the identical "is there really an active shift" question — even though, as of
 * this pass, [SessionHolder.set] is only ever called with a non-null `shiftId` in practice, so the
 * two checks are equivalent today.
 *
 * ### Interval
 * [HEARTBEAT_INTERVAL_MS] was originally 30s — the blueprint's own literal figure (§6.2.2) — but
 * that made the dispatcher Live Map marker visibly *jump* between fixes rather than read as a
 * vehicle actually moving, once this class started carrying real speed/heading per publish (see
 * this file's `speedKmh`/`heading` passthrough in [publishOnce]) instead of just a bare lat/lng
 * dot. Now 5s, matching this app's own existing precedent for "how often is fast enough to feel
 * live without being a precision feed": [DuressController]'s active-phase GPS relay
 * (`ACTIVE_POLL_INTERVAL_MS`, also 5000L) already polls/publishes position at exactly this
 * cadence while a duress event is open, for the same "dispatcher/responder needs to see the dot
 * actually move" reason — see also `docs/DURESS_DEVICE_INTEGRATION.md` for that stream's own
 * write-up. 5s is still far coarser than the fare engine's 1Hz GPS tick rate
 * ([au.com.threesixty.cabdispatch.domain.location.RealLocationProvider]) — this remains an
 * ambient presence signal for a dispatcher's map, not a precision feed, so publishing every
 * second (matching the fare engine tick 1:1) would still be pointless battery/data burn for a
 * dot that does not need frame-by-frame precision, just visible motion.
 *
 * As with the original 30s figure, 5s is a default this pass chose to make the map feel
 * real-time, not a re-derivation of the blueprint's §6.2.2 spec text (which still literally says
 * 30s) or a decided business/battery-budget policy — flagged here the same way this codebase's
 * own convention elsewhere insists a chosen-not-derived number be flagged as such rather than
 * silently presented as settled (see `docs/DURESS_DEVICE_INTEGRATION.md` sec 8's "exact number...
 * is a business decision, not an engineering one" framing for its retention-window figure).
 * Trivial to retune if a real battery-life measurement or a dispatcher-side complaint says
 * otherwise.
 *
 * ### Failure handling
 * Best-effort and silent-on-failure, matching every other background publish in this app
 * ([DuressController.runActivePhase]'s GPS relay, [SettingsViewModel.respondToLocateRequest]) — a
 * single failed publish (offline, server error) is not surfaced anywhere and does not stop the
 * loop; the next tick simply tries again. Deliberately no outbox/retry queuing (unlike trips/
 * shifts, B7's offline-sync guarantees) — a missed heartbeat while offline is an acceptable,
 * temporary gap in the dispatcher's ambient view, not user-facing/money-adjacent data that must
 * eventually land.
 *
 * ### Battery / network (2026-08-28 pass)
 * The blueprint's line also names "status, battery" alongside GPS. [PositionPublishRequestDto]
 * now carries both `battery`/`network` as optional fields on this same call (no new endpoint) —
 * read fresh on every [publishOnce] tick via [BatteryManager.BATTERY_PROPERTY_CAPACITY] and
 * [ConnectivityManager]'s active-network [NetworkCapabilities], same "best-effort, silent-on-
 * failure" posture as the rest of this class: either reads `null` rather than throwing (see
 * [readBatteryPercent]/[readNetworkType]), so a device with a flaky battery/connectivity service
 * still gets its GPS heartbeat through with those two fields simply omitted.
 *
 * `status` is still published as the same honest fixed placeholder
 * [SettingsViewModel.respondToLocateRequest] already uses — this app has no other real-time
 * on-trip/available/break signal it can read from here yet.
 */
class LivePositionHeartbeat(
    private val apiService: ApiService,
    private val speedSource: SpeedSource,
    private val scope: CoroutineScope,
    private val appContext: Context,
) {

    /** The currently-running publish loop, or `null` while off-shift. Only ever touched from the
     * single supervising coroutine [start] launches, so no extra synchronisation is needed — same
     * reasoning as [au.com.threesixty.cabdispatch.domain.location.RealLocationProvider.updatesJob]. */
    private var publishJob: Job? = null

    /**
     * Begins supervising [SessionHolder.session] for the process lifetime. Call exactly once, from
     * [au.com.threesixty.cabdispatch.data.AppContainer.init] — see this class's own doc for why
     * nothing else needs to call [start]/stop anything explicitly around shift start/end.
     *
     * [kotlinx.coroutines.flow.StateFlow.collect] never completes on its own, so this coroutine
     * simply runs for as long as [scope] does (a process-lifetime scope, per
     * [au.com.threesixty.cabdispatch.data.AppContainer.livePositionHeartbeat]'s construction) —
     * every time [SessionHolder.session] changes, the previous publish loop (if any) is cancelled
     * and a new one started only if the new session is actually on shift. [StateFlow] already
     * dedupes structurally-equal consecutive values on its own, so this does not re-launch on a
     * no-op re-emission of the same [DriverSession].
     */
    // Keyed off [DriverSession.vehicleUuid], not [DriverSession.vehicleId] — found live that
    // `POST /v1/fleet/positions` 404s "Vehicle not found" on the driver-entered rego string
    // ([DriverSession.vehicleId]) and only ever accepts the real fleet-vehicle UUID. A session
    // whose rego->UUID lookup hasn't resolved yet (still in flight, offline, or no server-side
    // match) has no publish loop running at all — same "nothing honest to send" posture as
    // [publishOnce]'s own missing-GPS-fix skip, just one level up: it degrades to *no heartbeat
    // this shift* rather than a guaranteed-404 spammed every 30s.
    fun start() {
        scope.launch {
            SessionHolder.session.collect { session ->
                publishJob?.cancel()
                val onShiftVehicleUuid = session?.takeIf { it.shiftId != null }?.vehicleUuid
                publishJob = onShiftVehicleUuid?.let { vehicleUuid -> scope.launch { publishLoop(vehicleUuid) } }
            }
        }
    }

    /** Publishes immediately (so a dispatcher sees a fresh dot the moment a shift starts, not up
     * to [HEARTBEAT_INTERVAL_MS] later) and then every [HEARTBEAT_INTERVAL_MS] after that — same
     * "act then delay" shape as [DuressController.runActivePhase]'s own poll loop. */
    private suspend fun publishLoop(vehicleUuid: String) {
        while (scope.isActive) {
            publishOnce(vehicleUuid)
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    /** Skips silently (not an error) when there is no fix yet — same "nothing honest to publish
     * yet" reasoning as [SettingsViewModel.respondToLocateRequest]: no permission granted, cold
     * start, no signal. */
    private suspend fun publishOnce(vehicleUuid: String) {
        val fix = speedSource.locationFix.value ?: return
        runCatching {
            apiService.publishPosition(
                PositionPublishRequestDto(
                    vehicleId = vehicleUuid,
                    lat = fix.lat,
                    lng = fix.lng,
                    status = HEARTBEAT_STATUS,
                    battery = readBatteryPercent(),
                    network = readNetworkType(),
                    // Straight passthrough of the same fix's own speed/heading — see
                    // LocationFix.speedKmh/.heading for provenance (fused-location speed, real
                    // Location.getBearing() or honest null). Lets the dispatcher Live Map show a
                    // moving, oriented marker instead of just a bare dot.
                    speedKmh = fix.speedKmh,
                    heading = fix.heading,
                ),
            )
        }
    }

    /** [BatteryManager.BATTERY_PROPERTY_CAPACITY] on the system [Context.BATTERY_SERVICE] —
     * returns `null` (never throws) if the service is unavailable or reports an invalid
     * percentage, matching this class's existing "skip silently, try again next tick" posture. */
    private fun readBatteryPercent(): Int? = runCatching {
        val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return null
        val pct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        pct.takeIf { it in 0..100 }
    }.getOrNull()

    /** Maps the active network's [NetworkCapabilities] transport to the categories the backend
     * expects — `"wifi"` / `"4g"` (any cellular transport; this app has no way to distinguish
     * 3G/4G/5G from [NetworkCapabilities] alone, and the blueprint's own example string is `"4g"`,
     * not a generic `"cellular"`) / `"offline"` (no active network, or one with neither transport
     * — e.g. VPN-only). Returns `null` (not `"offline"`) only if [ConnectivityManager] itself is
     * unavailable, so a real "no network" reading is never confused with "couldn't check". */
    private fun readNetworkType(): String? = runCatching {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "4g"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
            else -> "offline"
        }
    }.getOrNull()

    private companion object {
        // See this class's own "Interval" doc above for why this is 5s, not the blueprint's
        // literal 30s figure.
        const val HEARTBEAT_INTERVAL_MS = 5_000L

        /** See this class's own doc ("Why 'shift open'...") for why this is a fixed placeholder
         * rather than a real availability/on-trip status — no such signal exists to read from a
         * process-lifetime singleton like this one yet. */
        const val HEARTBEAT_STATUS = "unknown"
    }
}
