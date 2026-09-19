package au.com.threesixty.cabdispatch.domain

import android.content.Context
import android.util.Log
import au.com.threesixty.cabdispatch.data.BatteryStatsCounters
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.PositionPublishRequestDto
import au.com.threesixty.cabdispatch.domain.location.GeoMath
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
 * open, publish the device's current real fix on an adaptive interval (see "Adaptive cadence"
 * below), with no admin action needed.
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
 * ### Adaptive cadence (N5, master plan P4.4, 2026-09-12 optimisation plan W4 task 1)
 * A flat 5s interval, forever, while on shift was itself the finding: N5 measured 17,280 requests
 * per tablet per day regardless of whether the vehicle was hired, moving, or sitting parked
 * overnight with the driver asleep in the depot lunchroom. [resolveHeartbeatCadence] now answers
 * "how often" from the vehicle's actual state, cheapest first:
 *
 * - **Hired or duress -> [HeartbeatCadence.HIRED_OR_DURESS] (5s).** Unchanged from the old flat
 *   figure -- a fare in progress or a duress event is exactly the case a dispatcher most needs a
 *   moving, current dot for, and this is also the one case this class shares with
 *   [DuressController]'s own independent 5s active-phase GPS relay (`ACTIVE_POLL_INTERVAL_MS`,
 *   untouched by this pass -- see that class's doc). Duress state itself is read via
 *   [duressActive], not by depending on [DuressController] directly (see that parameter's own
 *   doc for why).
 * - **On shift, moving -> [HeartbeatCadence.MOVING] (10s).** "Moving" is [speedSource]'s own
 *   speed reading exceeding [MOVING_SPEED_THRESHOLD_KMH] (5 km/h -- brisk walking pace, the same
 *   order of magnitude [RealLocationProvider]'s own stationary clamp uses for the identical
 *   "is this vehicle actually travelling" question, though that class's 1.4 km/h threshold is
 *   about GPS noise on a stopped vehicle, a stricter question than this one).
 * - **On shift, stationary -> [HeartbeatCadence.STATIONARY] (30s).** A cab sitting at a rank or a
 *   red light does not need a map dot refreshed every 5 seconds; it has not moved.
 * - **Screen off and stationary -> [HeartbeatCadence.SCREEN_OFF_STATIONARY] (120s).** The
 *   deepest-backoff tier: a tablet parked overnight, screen off, engine off, is the exact case N5
 *   measured as pure waste. Screen state is read via [ScreenStateMonitor] (`android.os.PowerManager.isInteractive`
 *   plus a `SCREEN_ON`/`SCREEN_OFF` `BroadcastReceiver` — there is no `StateFlow`/callback API for
 *   this on any supported API level; see that object's own doc for why it is shared with
 *   [DeviceCommandHeartbeat] rather than each registering its own receiver).
 *
 * These four numbers are chosen, not derived from a fleet measurement -- the same "flag a chosen,
 * not decided, figure" convention this class's own history already follows for the original 5s/30s
 * pair (see the git history preceding this pass). Trivial to retune from a real Battery Historian
 * pass (this plan's OWNER G4 gate) if the fleet's own numbers say otherwise.
 *
 * ### Immediate publish on a material change
 * Waiting out the interval above for the FIRST publish after something changes would show a stale
 * dot for up to 120 seconds at exactly the moments a dispatcher most needs a fresh one — the
 * instant a shift starts, a fare opens or closes, a duress event fires, or the vehicle has
 * genuinely travelled somewhere since the last publish. [HeartbeatScheduler.shouldPublish] treats
 * all four as "publish now, cadence be damned":
 *
 * 1. **Shift start** falls out for free: the very first tick of a freshly-started [publishLoop] has
 *    no prior publish recorded, and [HeartbeatScheduler.shouldPublish] always answers `true` when
 *    there is nothing to compare against -- the same "act then delay" shape [publishLoop] already
 *    had.
 * 2. **Trip open/close and duress** are folded into one combined `hiredOrDuress` edge: [publishLoop]
 *    compares this tick's [isHiredOrDuress] against the previous tick's, and any transition either
 *    way is a material change. Folding trip-state and duress into a single edge (rather than two
 *    separate ones) costs nothing here -- both already map onto the identical 5s cadence tier, so
 *    there is no case where distinguishing them would change what this class does.
 * 3. **>= 100m moved** is a straight-line [GeoMath.distanceKm] check between the fix at the last
 *    publish and the current one, evaluated every poll tick alongside the cadence -- a taxi that
 *    covers 100m during its `STATIONARY`/`SCREEN_OFF_STATIONARY` window (unlikely, but the
 *    `MOVING` cadence's own speed check can itself be marginal right at the 5 km/h line) still
 *    gets a fresh dot rather than waiting out a 30s/120s window while visibly displaced on the map.
 *
 * All of the state this reacts to (hired/duress, speed, distance) is polled once per
 * [POLL_TICK_MS] rather than pushed reactively -- see [publishLoop]'s own doc for why a tight poll
 * is the right shape here, the same reasoning `RealLocationProvider.supervisePermission` already
 * gives for its own permission poll.
 *
 * ### Failure handling
 * Best-effort and silent *to the driver*, matching every other background publish in this app
 * ([DuressController.runActivePhase]'s GPS relay, [SettingsViewModel.respondToLocateRequest]) — a
 * single failed publish (offline, server error) is not surfaced in any UI and does not stop the
 * loop. Deliberately no outbox/retry queuing (unlike trips/shifts, B7's offline-sync guarantees) —
 * a missed heartbeat while offline is an acceptable, temporary gap in the dispatcher's ambient
 * view, not user-facing/money-adjacent data that must eventually land.
 *
 * Three things about that posture changed on 2026-09-19, because taken together they meant a
 * tablet could fail every heartbeat of an entire shift and be indistinguishable from a healthy one:
 *
 * 1. **A `403` is no longer "transport".** See [classifyPublishError]'s "The 403 hole".
 *    [PositionPublishOutcome.NOT_MY_VEHICLE] is logged (logging is the only signal this class
 *    has) and triggers a re-resolve of the vehicle uuid, throttled by [RebindThrottle].
 * 2. **A failed publish no longer counts as a publish.** [HeartbeatScheduler.recordAttempt] now
 *    resets the cadence timer and the 100m distance baseline ONLY on
 *    [PositionPublishOutcome.PUBLISHED]. Before, a `NO_FIX` or `TRANSPORT_FAILURE` tick reset the
 *    cadence — so a failing tablet waited out its whole tier (up to 120s) before retrying — and a
 *    `NO_FIX` tick additionally nulled the distance baseline, disabling the 100m trigger entirely.
 * 3. **...which required a failure backoff**, or fixing (2) would turn the 1s [POLL_TICK_MS] into
 *    a request storm on a failing tablet. See [HeartbeatScheduler.shouldPublish]'s own doc.
 *
 * ### Battery / network (2026-08-28 pass)
 * The blueprint's line also names "status, battery" alongside GPS. [PositionPublishRequestDto]
 * now carries both `battery`/`network` as optional fields on this same call (no new endpoint) —
 * read fresh on every [publishOnce] tick via [DeviceTelemetry] (shared with
 * [DeviceCommandHeartbeat], see that object's doc for why this used to be duplicated here), same
 * "best-effort, silent-on-failure" posture as the rest of this class: either reads `null` rather
 * than throwing, so a device with a flaky battery/connectivity service still gets its GPS
 * heartbeat through with those two fields simply omitted.
 *
 * `status` is still published as the same honest fixed placeholder
 * [SettingsViewModel.respondToLocateRequest] already uses — this app has no other real-time
 * on-trip/available/break signal it can read from here yet.
 */
// LongParameterList: eight constructor parameters, all but the first four DEFAULTED test/production
// seams (estimatedPositionSource, vehicleUuidResolver, duressActive) -- same accepted reasoning
// FareEngineImpl's own constructor documents for its seams.
@Suppress("LongParameterList")
class LivePositionHeartbeat(
    private val apiService: ApiService,
    private val speedSource: SpeedSource,
    private val scope: CoroutineScope,
    private val appContext: Context,
    /**
     * Dead-reckoned position source consulted ONLY when [speedSource]'s newest fix is older than
     * [LocationFix.MAX_FIX_AGE_MS] -- i.e. the same "GPS is lost" test the fare engine bills on.
     * In production this is [au.com.threesixty.cabdispatch.data.AppContainer.inertialSpeedSource],
     * whose display fix advances along the last heading at the estimated speed for as long as a
     * hiring's blackout lasts. Published with `estimated = true` so the dashboard keeps a moving,
     * honestly-labelled marker through a tunnel instead of a frozen dot and "signal lost" (field
     * finding, T5453, 2026-09-14). `null` (tests, and any caller without an estimator) keeps the
     * old behaviour exactly.
     */
    private val estimatedPositionSource: SpeedSource? = null,
    /** How a rego becomes a fleet-vehicle UUID again when the persisted one stops working -- see
     * `VehicleBinding.kt`'s file doc for the defect this closes. Injectable so a test can drive the
     * recovery without a Retrofit stack; defaults to the real roster lookup. */
    private val vehicleUuidResolver: VehicleUuidResolver = ApiVehicleUuidResolver(apiService),
    /** True while a duress event is in its `Triggered`/`Active` phase — see "Adaptive cadence"
     * above. A plain function rather than a direct [DuressController] dependency: that class is
     * an [au.com.threesixty.cabdispatch.data.AppContainer]-scoped singleton with its own
     * constructor dependencies (a repository, optional recorder/camera captures), and threading a
     * whole extra constructor parameter through just to read one derived boolean would couple two
     * independent process-lifetime singletons together for no benefit — the same reasoning
     * [MeterController.instance] (a static publication point, read via [isHiredOrDuress] below
     * rather than an injected [MeterController]) already gives for the identical "avoid a
     * construction-order dependency between independent singletons" problem. Defaults to `false`
     * so every pre-existing test/preview construction of this class keeps compiling/behaving
     * unchanged; [au.com.threesixty.cabdispatch.data.AppContainer.livePositionHeartbeat] wires the
     * real check against [DuressController.state].
     */
    private val duressActive: () -> Boolean = { false },
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
    // Keyed off "is there an open shift", and nothing more.
    //
    // It used to be keyed off [DriverSession.vehicleUuid] being non-null, on the reasoning that a
    // session with no resolved UUID has nothing honest to publish. True as far as it went, but the
    // consequence was that one failed roster lookup at bind time (a tablet binding a vehicle with
    // no signal in a basement car park, which is exactly where they bind) cost the depot sight of
    // that car for the entire shift, with nothing anywhere that would ever try again. The loop now
    // starts regardless and resolves the binding itself — see [publishLoop].
    fun start() {
        ScreenStateMonitor.start(appContext)
        scope.launch {
            SessionHolder.session.collect { session ->
                publishJob?.cancel()
                val onShift = session?.takeIf { it.shiftId != null }
                publishJob = onShift?.let { scope.launch { publishLoop(it) } }
            }
        }
    }

    /** `true` while there is a live meter currently accruing a fare (see
     * [MeterController.activeClientUuid]'s own doc — read via the static [MeterController.instance]
     * publication point rather than an injected [MeterController], for the same
     * construction-order reason [duressActive] is a function rather than a [DuressController]
     * dependency) OR a duress event is in its `Triggered`/`Active` phase. Both map onto the
     * identical [HeartbeatCadence.HIRED_OR_DURESS] tier — see "Adaptive cadence" above. */
    private fun isHiredOrDuress(): Boolean =
        MeterController.instance?.activeClientUuid != null || duressActive()

    /**
     * Polls state every [POLL_TICK_MS] and publishes whenever [HeartbeatScheduler.shouldPublish]
     * says to — see "Adaptive cadence" and "Immediate publish on a material change" above for the
     * full design, and [HeartbeatScheduler]'s own doc for why this is a poll loop rather than a
     * reactive one built from combined [kotlinx.coroutines.flow.Flow]s: [isHiredOrDuress] and
     * [ScreenStateMonitor.isScreenOn] are plain synchronous reads with no natural `Flow` of their
     * own (a static field and a broadcast-receiver-updated `var`, respectively), so polling both alongside
     * [speedSource]'s own `StateFlow` at one shared cadence is simpler and easier to reason about
     * than three different reactive-vs-polled subscription shapes feeding one decision. The same
     * "intentionally short enough to feel immediate, long enough not to be busy-work" trade-off
     * `RealLocationProvider.supervisePermission`'s own doc already makes for its permission poll.
     *
     * ### Keeping the binding alive
     * The loop owns the vehicle UUID rather than being handed one, because the persisted one can be
     * absent (the bind happened offline) or wrong (the vehicle was deleted and re-seeded
     * server-side under the same rego, which is what a fleet wipe does). Both cases used to be
     * permanent for the life of the session. Now:
     *
     * - No UUID: look the rego up every [REBIND_INTERVAL_MS] until it resolves.
     * - `404 Vehicle not found` / `403 not your vehicle`: look it up once immediately; if it
     *   resolves to a different car, that is the fleet-wipe case and the session is rebound. If it
     *   resolves to the same id, or not at all, back off to [REBIND_INTERVAL_MS] rather than
     *   re-fetching the whole roster on every poll tick for the rest of the shift. That backoff is
     *   [RebindThrottle] — it used to be only a claim in this comment, held up in practice by the
     *   accident that a failed publish reset the cadence timer; see that class's own doc.
     *
     * Rebinding writes through [SessionHolder.set], which persists it and — because [start]'s
     * collector is watching the same flow — cancels this coroutine and starts a fresh loop on the
     * new UUID. So the `return` after a rebind is documentation, not control flow: the cancellation
     * usually gets there first.
     */
    private suspend fun publishLoop(session: DriverSession) {
        var vehicleUuid = session.vehicleUuid
        val scheduler = HeartbeatScheduler()
        val rebindThrottle = RebindThrottle()
        var previousHiredOrDuress = false
        while (scope.isActive) {
            val resolvedUuid = vehicleUuid ?: resolveAndPersist(session)
            if (resolvedUuid == null) {
                delay(REBIND_INTERVAL_MS)
                continue
            }
            vehicleUuid = resolvedUuid

            val hiredOrDuress = isHiredOrDuress()
            val materialChange = hiredOrDuress != previousHiredOrDuress
            previousHiredOrDuress = hiredOrDuress
            val tick = HeartbeatTick(
                cadence = resolveHeartbeatCadence(
                    hiredOrDuress = hiredOrDuress,
                    speedKmh = speedSource.speedKmh.value,
                    screenOn = ScreenStateMonitor.isScreenOn(),
                ),
                materialChange = materialChange,
                // The SAME selection [publishOnce] will make a moment later, not a bare
                // `speedSource.locationFix.value`. During a GPS blackout those two are different
                // points -- the real fix is frozen where the signal died while the inertial
                // estimate keeps advancing -- and evaluating the 100m trigger against one stream
                // while publishing from the other compares distances between coordinates that
                // never belonged to the same series (2026-09-19). The two reads still happen at
                // different instants, so the baseline recorded afterwards is [PublishAttempt.fix],
                // the point that was genuinely sent; see [publishIfDue].
                fix = currentPublishFix()?.fix,
            )
            vehicleUuid = publishIfDue(session, scheduler, rebindThrottle, resolvedUuid, tick) ?: return
            delay(POLL_TICK_MS)
        }
    }

    /**
     * Publishes [uuid]'s position if [HeartbeatScheduler.shouldPublish] says this [tick] is due,
     * and handles the two outcomes that say the vehicle binding itself is wrong
     * ([PositionPublishOutcome.UNKNOWN_VEHICLE], [PositionPublishOutcome.NOT_MY_VEHICLE]) — split
     * out of [publishLoop] purely to keep that loop's own nesting flat; this carries no state of
     * its own beyond what its parameters give it.
     *
     * @return the vehicle UUID [publishLoop] should keep using next tick, or `null` if a rebind
     * already happened and [publishLoop] should stop (see [publishLoop]'s own "the `return` after
     * a rebind is documentation, not control flow" doc).
     */
    private suspend fun publishIfDue(
        session: DriverSession,
        scheduler: HeartbeatScheduler,
        rebindThrottle: RebindThrottle,
        uuid: String,
        tick: HeartbeatTick,
    ): String? {
        val outcome = publishTickAndRecord(scheduler, tick) { publishOnce(uuid) } ?: return uuid
        return handlePublishOutcome(session, rebindThrottle, uuid, outcome)
    }

    /**
     * What to do about a publish that has already happened and already been recorded.
     *
     * Both binding-implicating outcomes take the same recovery — re-resolve the rego against the
     * roster — for the same reason [publishLoop] folds trip-state and duress into one edge: the
     * action is identical, and only the log line differs. They are kept as separate enum entries
     * anyway because that log line is the entire point of [PositionPublishOutcome.NOT_MY_VEHICLE]
     * existing (see [classifyPublishError]'s "The 403 hole"), and "another driver holds this car"
     * is a very different thing for an operator to read at 3am than "this car no longer exists".
     *
     * `403` is logged at WARN and `404` is not, deliberately: a `404` already has a working,
     * silent, well-understood recovery path that predates this pass and fires on an ordinary fleet
     * re-seed, whereas a `403` means two humans believe they are driving the same car and no amount
     * of re-resolving will fix that if the rego is right — it needs a person. Logging is the only
     * channel this class has to reach one.
     */
    private suspend fun handlePublishOutcome(
        session: DriverSession,
        rebindThrottle: RebindThrottle,
        uuid: String,
        outcome: PositionPublishOutcome,
    ): String? = when (outcome) {
        PositionPublishOutcome.UNKNOWN_VEHICLE -> rebindThrottled(session, rebindThrottle, uuid)
        PositionPublishOutcome.NOT_MY_VEHICLE -> {
            Log.w(
                TAG,
                "Position publish refused (403) for vehicle $uuid on rego ${session.vehicleId}: " +
                    "another driver most likely holds an open shift on this vehicle. Re-resolving " +
                    "the binding; if the rego is correct this needs an operator to close the other shift.",
            )
            rebindThrottled(session, rebindThrottle, uuid)
        }
        else -> uuid
    }

    /** [resolveAndPersist], but no more often than [REBIND_INTERVAL_MS] — see [RebindThrottle]. */
    private suspend fun rebindThrottled(
        session: DriverSession,
        rebindThrottle: RebindThrottle,
        uuid: String,
    ): String? {
        if (!rebindThrottle.shouldAttempt()) return uuid
        rebindThrottle.recordAttempt()
        val resolved = resolveAndPersist(session)
        return if (resolved != null && resolved != uuid) null else uuid
    }

    /**
     * Re-resolves this session's rego and, if that names a different vehicle than the session is
     * carrying, writes it back so the fix outlives this loop, this screen and this process.
     *
     * Reads [SessionHolder.session] fresh rather than copying the [session] it was passed: a shift
     * can have been submitted, or a trip's id written into the session, in the seconds this lookup
     * was in flight, and rebinding must not resurrect a stale snapshot of everything else.
     */
    private suspend fun resolveAndPersist(session: DriverSession): String? {
        val resolved = vehicleUuidResolver.resolve(session.vehicleId) ?: return null
        val current = SessionHolder.session.value
        if (current != null && current.vehicleUuid != resolved) {
            SessionHolder.set(current.copy(vehicleUuid = resolved))
        }
        return resolved
    }

    /**
     * The fix this tablet would publish right now: the newest real one, or — only once that has
     * gone stale by the fare engine's own [LocationFix.MAX_FIX_AGE_MS] definition of "GPS is lost"
     * — the dead-reckoned estimate. See [estimatedPositionSource]'s own doc; the estimate is never
     * preferred over a live fix.
     *
     * Extracted 2026-09-19 so [publishLoop] and [publishOnce] cannot disagree about which stream a
     * tick is reasoning over. `null` means "nothing honest to publish yet".
     */
    private fun currentPublishFix(): PublishFix? {
        val real = speedSource.locationFix.value
        val realStale = real == null ||
            System.nanoTime() - real.receivedAtNanos > LocationFix.MAX_FIX_AGE_MS * NANOS_PER_MILLI
        val estimate = if (realStale) estimatedPositionSource?.locationFix?.value else null
        // `estimated` is carried alongside rather than re-derived by the caller: comparing the
        // chosen fix back against `speedSource.locationFix.value` a second time would re-read a
        // StateFlow that can have advanced in between, and the `estimated = true` flag on the wire
        // is what the dashboard uses to label the marker honestly through a tunnel (T5453).
        return estimate?.let { PublishFix(it, estimated = true) }
            ?: real?.let { PublishFix(it, estimated = false) }
    }

    /**
     * Skips silently (not an error) when there is no fix yet — same "nothing honest to publish
     * yet" reasoning as [SettingsViewModel.respondToLocateRequest]: no permission granted, cold
     * start, no signal.
     *
     * Returns the fix it actually used alongside the outcome, and that pairing is the point
     * (2026-09-19). [publishLoop] reads a candidate fix of its own to evaluate the cadence, and
     * that read used to be what got recorded as the "where were we last time we published"
     * baseline for the 100m [MATERIAL_MOVE_METRES] trigger — even though this function
     * independently re-reads the position and, during a GPS blackout, sends a DIFFERENT point (the
     * inertial estimate, which advances between the two reads). The trigger was therefore anchored
     * to a coordinate that had never been transmitted: the dashboard's last known position drifted
     * away from the tablet's own idea of it for the whole blackout, and the force-publish that
     * exists to correct exactly that measured its 100m from the wrong end. Returning the fix means
     * the baseline is, by construction, the point that went on the wire.
     */
    private suspend fun publishOnce(vehicleUuid: String): PublishAttempt {
        val selected = currentPublishFix() ?: return PublishAttempt(PositionPublishOutcome.NO_FIX, null)
        val fix = selected.fix
        BatteryStatsCounters.recordHeartbeat()
        val liveFare = currentLiveFare()
        val outcome = runCatching {
            apiService.publishPosition(
                PositionPublishRequestDto(
                    vehicleId = vehicleUuid,
                    lat = fix.lat,
                    lng = fix.lng,
                    status = if (liveFare != null) ON_TRIP_STATUS else HEARTBEAT_STATUS,
                    battery = DeviceTelemetry.readBatteryPercent(appContext),
                    network = DeviceTelemetry.readNetworkType(appContext),
                    // Straight passthrough of the same fix's own speed/heading — see
                    // LocationFix.speedKmh/.heading for provenance (fused-location speed, real
                    // Location.getBearing() or honest null). Lets the dispatcher Live Map show a
                    // moving, oriented marker instead of just a bare dot.
                    speedKmh = fix.speedKmh,
                    heading = fix.heading,
                    estimated = selected.estimated,
                    fareTotal = liveFare?.fareTotal,
                    distanceKm = liveFare?.distanceKm,
                    tollsTotal = liveFare?.tollsTotal,
                ),
            )
        }.fold(
            onSuccess = { PositionPublishOutcome.PUBLISHED },
            // A 404 or a 403 mean something other than "try again shortly" -- see
            // `classifyPublishError`. Still silent to the driver, as every background publish in
            // this app is; the difference is that the loop now acts on both, and logs the 403.
            onFailure = ::classifyPublishError,
        )
        return PublishAttempt(outcome, fix)
    }

    /**
     * The live meter's current fare/distance/tolls, as decimal strings ready for the wire, or
     * `null` when no fare is currently running (2026-09-16, live trip monitoring pass — see
     * [PositionPublishRequestDto.fareTotal]'s own doc). Read via the static [MeterController.instance]
     * publication point rather than an injected [MeterController], same construction-order
     * reasoning [duressActive] and [isHiredOrDuress] already give for this class.
     *
     * Deliberately keyed on [MeterController.activeClientUuid] specifically, not the broader
     * [isHiredOrDuress] (which also covers a duress event with no trip open at all) -- there is
     * nothing honest to report as a fare total outside an actual open trip.
     */
    private fun currentLiveFare(): LiveFareSnapshot? {
        val controller = MeterController.instance?.takeIf { it.activeClientUuid != null } ?: return null
        val state = controller.state.value
        return LiveFareSnapshot(
            fareTotal = state.breakdown.total.toPlainString(),
            distanceKm = state.distanceKm.toPlainString(),
            tollsTotal = state.breakdown.tolls.toPlainString(),
        )
    }

    private data class LiveFareSnapshot(val fareTotal: String, val distanceKm: String, val tollsTotal: String)

    private companion object {
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Logcat tag, same `"<ClassName>"` convention [ShiftRepository] and [SecurePrefs] use. */
        const val TAG = "LivePositionHeartbeat"

        /**
         * How often [publishLoop] re-evaluates state and decides whether to publish — NOT the
         * publish interval itself (see [HeartbeatCadence] for that); this is the granularity at
         * which a material change is noticed. See [publishLoop]'s own doc for why a poll rather
         * than a reactive combine, and this figure's "immediate enough without being busy-work"
         * framing there.
         */
        const val POLL_TICK_MS = 1_000L

        /** See this class's own doc ("Why 'shift open'...") for why this is a fixed placeholder
         * rather than a real availability status — nothing here reads the driver's own
         * available/break toggle. [currentLiveFare] IS a real signal this class can read cheaply
         * (the same [MeterController.instance] publication point [isHiredOrDuress] already reads),
         * so [publishOnce] reports [ON_TRIP_STATUS] instead of this placeholder whenever one is
         * available — see that function's own doc. */
        const val HEARTBEAT_STATUS = "unknown"

        /** Reported in place of [HEARTBEAT_STATUS] whenever [currentLiveFare] finds a trip
         * actually running (2026-09-16, live trip monitoring pass) — a real, meaningful value the
         * backend's `_compose_vehicle_live` priority order (`app/services/live_ops.py`) trusts
         * verbatim, same as any other non-placeholder status this class could report. */
        const val ON_TRIP_STATUS = "on_trip"
    }
}

/** A position ready to publish, plus whether it came from the dead-reckoned estimator rather than
 * real GPS — see [LivePositionHeartbeat.currentPublishFix]. */
internal data class PublishFix(val fix: LocationFix, val estimated: Boolean)

/**
 * One `POST /v1/fleet/positions` attempt: how it ended, and — critically — WHICH fix it sent.
 *
 * [fix] is `null` exactly when [outcome] is [PositionPublishOutcome.NO_FIX] (nothing was sent, so
 * there is nothing to report). For every other outcome it is the point that was put on the wire,
 * whether the server accepted it or not. See [LivePositionHeartbeat.publishOnce]'s own doc for the
 * defect that made returning it necessary (2026-09-19).
 */
internal data class PublishAttempt(val outcome: PositionPublishOutcome, val fix: LocationFix?)

/** Everything one poll tick of [LivePositionHeartbeat.publishLoop] decided before asking whether to
 * publish. Bundled into one value purely so the functions that consume it stay under detekt's
 * parameter-count threshold; it carries no behaviour. */
internal data class HeartbeatTick(
    val cadence: HeartbeatCadence,
    val materialChange: Boolean,
    val fix: LocationFix?,
)

/**
 * The whole of one tick's publish decision and bookkeeping, with the network call itself left as a
 * [publish] lambda.
 *
 * Shaped this way so the part with the real edge cases is pure and directly unit-testable — the
 * same split `VehicleBinding.kt`'s file doc already describes for [matchVehicleUuid] and
 * [classifyPublishError], and the reason `LivePositionHeartbeatSchedulerTest` can pin the two
 * ordering defects fixed on 2026-09-19 without constructing a [LivePositionHeartbeat], a
 * [android.content.Context], or a fake of a 76-method Retrofit interface:
 *
 * - The baseline recorded is [PublishAttempt.fix] — what [publish] actually sent — never
 *   [HeartbeatTick.fix], the candidate the loop read a moment earlier.
 * - [HeartbeatScheduler.recordAttempt], not `recordPublish`, decides what a given outcome does to
 *   the cadence timer. A failure no longer counts as a publish.
 *
 * @return the outcome, or `null` for "this tick was not due" — in which case [publish] was never
 * called and no state changed.
 */
internal suspend fun publishTickAndRecord(
    scheduler: HeartbeatScheduler,
    tick: HeartbeatTick,
    publish: suspend () -> PublishAttempt,
): PositionPublishOutcome? {
    if (!scheduler.shouldPublish(tick.cadence, tick.materialChange, tick.fix?.lat, tick.fix?.lng)) return null
    val attempt = publish()
    scheduler.recordAttempt(attempt.outcome, attempt.fix)
    return attempt.outcome
}

/**
 * The four publish cadences [LivePositionHeartbeat] chooses between — see that class's "Adaptive
 * cadence" doc for the full rationale behind each tier and figure. Ordered cheapest (most
 * frequent) first only for readability; [resolveHeartbeatCadence] does not rely on declaration
 * order.
 */
// Each millisecond literal below IS the named constant -- the enum entry's own name
// (HIRED_OR_DURESS, MOVING, ...) already says what it means, so a second, separately-named
// constant per value would be pure indirection, not more information.
@Suppress("MagicNumber")
internal enum class HeartbeatCadence(val intervalMs: Long) {
    HIRED_OR_DURESS(5_000L),
    MOVING(10_000L),
    STATIONARY(30_000L),
    SCREEN_OFF_STATIONARY(120_000L),
}

/** Ground speed above which a vehicle counts as "moving" for cadence purposes — see
 * [LivePositionHeartbeat]'s "Adaptive cadence" doc for why this is a coarser, presence-only
 * threshold than [au.com.threesixty.cabdispatch.domain.location.RealLocationProvider]'s own
 * 1.4 km/h GPS-noise clamp. */
internal const val MOVING_SPEED_THRESHOLD_KMH = 5.0

/** How far (metres) the vehicle must have moved since the last publish for that alone to force an
 * immediate one, regardless of the current [HeartbeatCadence] — see [LivePositionHeartbeat]'s
 * "Immediate publish on a material change" doc, point 3. */
internal const val MATERIAL_MOVE_METRES = 100.0

/**
 * Pure decision of which [HeartbeatCadence] tier applies right now — no Android, no coroutines,
 * no I/O, so it is directly unit-testable (see `LivePositionHeartbeatSchedulerTest`) against every
 * one of the plan's four named states without constructing a [LivePositionHeartbeat] at all.
 *
 * Checked in priority order exactly as the plan text lists them: hired/duress always wins (a fare
 * or a duress event never backs off, no matter how the tablet's screen/speed looks), then
 * screen-off-and-stationary (the deepest backoff), then moving, then plain on-shift-stationary as
 * the fallback.
 */
internal fun resolveHeartbeatCadence(hiredOrDuress: Boolean, speedKmh: Double, screenOn: Boolean): HeartbeatCadence {
    val moving = speedKmh > MOVING_SPEED_THRESHOLD_KMH
    return when {
        hiredOrDuress -> HeartbeatCadence.HIRED_OR_DURESS
        !screenOn && !moving -> HeartbeatCadence.SCREEN_OFF_STATIONARY
        moving -> HeartbeatCadence.MOVING
        else -> HeartbeatCadence.STATIONARY
    }
}

/**
 * Pure, testable "should [LivePositionHeartbeat.publishLoop] publish on this tick" decision plus
 * the small bit of state (when/where the last publish happened) it needs to answer that — see
 * [LivePositionHeartbeat]'s "Immediate publish on a material change" doc for the full rationale.
 * No Android, no coroutines: [nowMs] is an injected clock so a test can drive elapsed time
 * explicitly rather than sleeping wall-clock seconds (`LivePositionHeartbeatSchedulerTest`'s "fake/
 * virtual clock" coverage).
 */
internal class HeartbeatScheduler(private val nowMs: () -> Long = System::currentTimeMillis) {

    private var lastPublishAtMs: Long? = null
    private var lastPublishLat: Double? = null
    private var lastPublishLng: Double? = null

    /** How many publishes in a row have failed, and when the last one did. `0`/`null` whenever the
     * most recent attempt succeeded — see [recordAttempt]. */
    private var consecutiveFailures = 0
    private var lastFailureAtMs: Long? = null

    private companion object {
        const val METRES_PER_KM = 1000.0

        /**
         * First retry delay after a publish fails, then doubling — 5s, 10s, 20s, 40s, 80s, then
         * [FAILURE_BACKOFF_CEILING_MS] forever.
         *
         * 5s is deliberately the same figure as [HeartbeatCadence.HIRED_OR_DURESS], the fastest
         * cadence this class ever runs at: the FIRST retry after a failure must not be slower than
         * the tablet would have published anyway had nothing gone wrong, or a single dropped packet
         * during a fare would cost the dispatcher a gap it did not have before this pass.
         */
        const val FAILURE_BACKOFF_BASE_MS = 5_000L

        /** The backoff never grows past the deepest ordinary cadence tier
         * ([HeartbeatCadence.SCREEN_OFF_STATIONARY], 120s). A tablet that has been failing for an
         * hour is usually one in a basement car park or on a dead SIM, and an hour of silence
         * followed by an instant recovery matters more than the handful of requests a 120s retry
         * costs — 30 requests an hour is a rounding error against the 17,280/day N5 measured. */
        const val FAILURE_BACKOFF_CEILING_MS = 120_000L

        /** `1 shl 5` is 32 — enough to reach [FAILURE_BACKOFF_CEILING_MS] from
         * [FAILURE_BACKOFF_BASE_MS] and clamped there so a shift's worth of consecutive failures
         * can never shift a `Long` into nonsense. */
        const val FAILURE_BACKOFF_MAX_DOUBLINGS = 5
    }

    /**
     * `true` when: this is the very first tick ([lastPublishAtMs] is `null` — covers "shift
     * start"), OR [materialChange] is set (the hired/duress edge — covers "trip open/close" and
     * "duress"), OR the vehicle has moved >= [MATERIAL_MOVE_METRES] since the last publish, OR
     * [cadence]'s own interval has simply elapsed.
     *
     * ### ...unless the last attempt failed (2026-09-19)
     * A failing tablet is answered from [failureBackoffMs] and nothing else — cadence, material
     * change and the 100m trigger are all suppressed while a backoff is in flight. That is not a
     * nicety; it is what makes the rest of this pass safe. `recordPublish` used to be called for
     * every outcome, so a failure reset the cadence timer and the next attempt was 5–120s away by
     * accident. Recording only real publishes (see [recordAttempt]) removes that accident and
     * leaves [LivePositionHeartbeat.POLL_TICK_MS] — one second — as the retry interval, i.e. 3,600
     * failed requests an hour per tablet, on a fleet, against a backend that is already refusing
     * them. The backoff is the deliberate replacement for the accident.
     *
     * Suppressing `materialChange` too is the uncomfortable part and is chosen on purpose: a
     * material change is an edge computed by [LivePositionHeartbeat.publishLoop] once, on the tick
     * it happens, so one suppressed here is genuinely lost rather than deferred. It is suppressed
     * anyway because the alternative is that a duress event — which flips `hiredOrDuress` and can
     * flip it back — becomes a way to bypass the backoff entirely, and because a request the server
     * is currently refusing conveys the trip-open edge no better than no request at all. Duress
     * itself does not depend on this path: [DuressController] runs its own independent 5s GPS relay
     * (`ACTIVE_POLL_INTERVAL_MS`) that this class neither shares nor throttles.
     */
    fun shouldPublish(cadence: HeartbeatCadence, materialChange: Boolean, lat: Double?, lng: Double?): Boolean {
        val failedAt = lastFailureAtMs
        if (failedAt != null) return (nowMs() - failedAt) >= failureBackoffMs()
        val last = lastPublishAtMs
        return last == null || materialChange || movedFar(lat, lng) || (nowMs() - last) >= cadence.intervalMs
    }

    /** The current retry delay: [FAILURE_BACKOFF_BASE_MS] doubled once per consecutive failure
     * after the first, capped at [FAILURE_BACKOFF_CEILING_MS]. `0` while nothing is failing. */
    fun failureBackoffMs(): Long {
        if (consecutiveFailures <= 0) return 0L
        val doublings = (consecutiveFailures - 1).coerceAtMost(FAILURE_BACKOFF_MAX_DOUBLINGS)
        return (FAILURE_BACKOFF_BASE_MS shl doublings).coerceAtMost(FAILURE_BACKOFF_CEILING_MS)
    }

    private fun movedFar(lat: Double?, lng: Double?): Boolean {
        val fromLat = lastPublishLat
        val fromLng = lastPublishLng
        // Split across two conditions (rather than one four-term `== null ||` chain) purely to
        // stay under detekt's ComplexCondition threshold -- functionally this is still just "do
        // we have all four points". Kotlin smart-casts lat/lng to non-null for the distanceKm call
        // below from the `lat != null && lng != null` conjunct in the same expression.
        if (fromLat == null || fromLng == null) return false
        return lat != null && lng != null &&
            GeoMath.distanceKm(fromLat, fromLng, lat, lng) * METRES_PER_KM >= MATERIAL_MOVE_METRES
    }

    /**
     * Books one finished publish attempt in, by outcome. This is the whole of defect (c)'s fix.
     *
     * Until 2026-09-19 [LivePositionHeartbeat.publishIfDue] called [recordPublish] for EVERY
     * outcome except [PositionPublishOutcome.UNKNOWN_VEHICLE], with two consequences that both
     * pointed the wrong way:
     *
     * - A [PositionPublishOutcome.TRANSPORT_FAILURE] — the common one, a tablet in a tunnel or on
     *   a dropped SIM — reset the cadence timer as if the position had landed. The tablet then sat
     *   out its entire tier, up to 120s on a parked screen-off tablet, before trying again, on the
     *   strength of a request that never arrived anywhere.
     * - A [PositionPublishOutcome.NO_FIX] tick additionally wrote `null`/`null` over
     *   [lastPublishLat]/[lastPublishLng], silently disarming the 100m [MATERIAL_MOVE_METRES]
     *   force-publish for the rest of the loop or until the next successful publish — the driver
     *   crosses a suburb and no early publish fires, because the baseline was erased by a tick that
     *   never sent anything.
     *
     * Now: only [PositionPublishOutcome.PUBLISHED] touches the publish baselines.
     * [PositionPublishOutcome.NO_FIX] touches nothing at all — no request was made (see
     * [LivePositionHeartbeat.publishOnce]: it returns before the network call), so there is neither
     * anything to record nor anything to back off from, and the very next tick that finds a fix
     * publishes immediately. Everything else is a failure and arms the backoff.
     */
    fun recordAttempt(outcome: PositionPublishOutcome, fix: LocationFix?) {
        when (outcome) {
            PositionPublishOutcome.PUBLISHED -> recordPublish(fix?.lat, fix?.lng)
            PositionPublishOutcome.NO_FIX -> Unit
            PositionPublishOutcome.TRANSPORT_FAILURE,
            PositionPublishOutcome.UNKNOWN_VEHICLE,
            PositionPublishOutcome.NOT_MY_VEHICLE,
            -> recordFailure()
        }
    }

    /** Records that a publish just landed, for the next call's elapsed-time/distance-moved checks,
     * and clears any failure backoff. [lat]/[lng] are `null` only if a fix-less position somehow
     * reached the server; in that case there is nothing to compare the next fix against, so the
     * distance check simply never fires until a real fix is recorded. */
    fun recordPublish(lat: Double?, lng: Double?) {
        lastPublishAtMs = nowMs()
        lastPublishLat = lat
        lastPublishLng = lng
        consecutiveFailures = 0
        lastFailureAtMs = null
    }

    /** Arms/deepens the retry backoff. Deliberately does NOT touch [lastPublishLat]/[lastPublishLng]
     * or [lastPublishAtMs]: the last position the dispatcher actually holds is still the last one
     * that actually published, and a failure is not new information about where the vehicle is. */
    fun recordFailure() {
        consecutiveFailures++
        lastFailureAtMs = nowMs()
    }
}
