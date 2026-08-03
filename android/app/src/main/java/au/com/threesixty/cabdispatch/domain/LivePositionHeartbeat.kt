package au.com.threesixty.cabdispatch.domain

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
 * [HEARTBEAT_INTERVAL_MS] (30s) is the blueprint's own literal figure (§6.2.2). Deliberately much
 * coarser than the fare engine's 1Hz GPS tick rate
 * ([au.com.threesixty.cabdispatch.domain.location.RealLocationProvider]) — this is an ambient
 * presence signal for a dispatcher's map, not a precision feed, so publishing every second would
 * just be pointless battery/data burn for a dot that does not need to move that smoothly.
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
 * ### Not done here, flagged rather than silently omitted
 * The blueprint's line also names "status, battery" alongside GPS —
 * [PositionPublishRequestDto] has no battery field at all (the backend's
 * `PositionPublishRequest` doesn't carry one, see that DTO's own doc), so this class cannot
 * honestly publish battery without a backend/DTO change first; left out rather than silently
 * dropped into some other field. `status` is published as the same honest fixed placeholder
 * [SettingsViewModel.respondToLocateRequest] already uses, for the same reason: this app has no
 * other real-time on-trip/available/break signal it can read from here yet.
 */
class LivePositionHeartbeat(
    private val apiService: ApiService,
    private val speedSource: SpeedSource,
    private val scope: CoroutineScope,
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
    fun start() {
        scope.launch {
            SessionHolder.session.collect { session ->
                publishJob?.cancel()
                val onShiftVehicleId = session?.takeIf { it.shiftId != null }?.vehicleId
                publishJob = onShiftVehicleId?.let { vehicleId -> scope.launch { publishLoop(vehicleId) } }
            }
        }
    }

    /** Publishes immediately (so a dispatcher sees a fresh dot the moment a shift starts, not up
     * to [HEARTBEAT_INTERVAL_MS] later) and then every [HEARTBEAT_INTERVAL_MS] after that — same
     * "act then delay" shape as [DuressController.runActivePhase]'s own poll loop. */
    private suspend fun publishLoop(vehicleId: String) {
        while (scope.isActive) {
            publishOnce(vehicleId)
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    /** Skips silently (not an error) when there is no fix yet — same "nothing honest to publish
     * yet" reasoning as [SettingsViewModel.respondToLocateRequest]: no permission granted, cold
     * start, no signal. */
    private suspend fun publishOnce(vehicleId: String) {
        val fix = speedSource.locationFix.value ?: return
        runCatching {
            apiService.publishPosition(
                PositionPublishRequestDto(
                    vehicleId = vehicleId,
                    lat = fix.lat,
                    lng = fix.lng,
                    status = HEARTBEAT_STATUS,
                ),
            )
        }
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 30_000L

        /** See this class's own doc ("Why 'shift open'...") for why this is a fixed placeholder
         * rather than a real availability/on-trip status — no such signal exists to read from a
         * process-lifetime singleton like this one yet. */
        const val HEARTBEAT_STATUS = "unknown"
    }
}
