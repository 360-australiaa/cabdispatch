package au.com.threesixty.cabdispatch.domain

import android.content.Context
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
class LivePositionHeartbeat(
    private val apiService: ApiService,
    private val speedSource: SpeedSource,
    private val scope: CoroutineScope,
    private val appContext: Context,
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
     * - `404 Vehicle not found`: look it up once immediately; if it resolves to a different car,
     *   that is the fleet-wipe case and the session is rebound. If it resolves to the same id, or
     *   not at all, back off to [REBIND_INTERVAL_MS] rather than 404-ing every five seconds for the
     *   rest of the shift.
     *
     * Rebinding writes through [SessionHolder.set], which persists it and — because [start]'s
     * collector is watching the same flow — cancels this coroutine and starts a fresh loop on the
     * new UUID. So the `return` after a rebind is documentation, not control flow: the cancellation
     * usually gets there first.
     */
    private suspend fun publishLoop(session: DriverSession) {
        var vehicleUuid = session.vehicleUuid
        val scheduler = HeartbeatScheduler()
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
            val cadence = resolveHeartbeatCadence(
                hiredOrDuress = hiredOrDuress,
                speedKmh = speedSource.speedKmh.value,
                screenOn = ScreenStateMonitor.isScreenOn(),
            )
            val fix = speedSource.locationFix.value
            val due = scheduler.shouldPublish(cadence, materialChange, fix?.lat, fix?.lng)
            vehicleUuid = publishIfDue(session, scheduler, resolvedUuid, due, fix) ?: return
            delay(POLL_TICK_MS)
        }
    }

    /**
     * Publishes [uuid]'s position if [HeartbeatScheduler.shouldPublish] says this tick is due, and
     * handles the one outcome that means the vehicle binding itself is wrong
     * ([PositionPublishOutcome.UNKNOWN_VEHICLE]) — split out of [publishLoop] purely to keep that
     * loop's own nesting flat; this carries no state of its own beyond what its parameters give it.
     *
     * @return the vehicle UUID [publishLoop] should keep using next tick, or `null` if a rebind
     * already happened and [publishLoop] should stop (see [publishLoop]'s own "the `return` after
     * a rebind is documentation, not control flow" doc).
     */
    private suspend fun publishIfDue(
        session: DriverSession,
        scheduler: HeartbeatScheduler,
        uuid: String,
        due: Boolean,
        fix: LocationFix?,
    ): String? {
        if (!due) return uuid
        return when (publishOnce(uuid)) {
            PositionPublishOutcome.UNKNOWN_VEHICLE -> {
                val resolved = resolveAndPersist(session)
                if (resolved != null && resolved != uuid) null else uuid
            }
            else -> {
                scheduler.recordPublish(fix?.lat, fix?.lng)
                uuid
            }
        }
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

    /** Skips silently (not an error) when there is no fix yet — same "nothing honest to publish
     * yet" reasoning as [SettingsViewModel.respondToLocateRequest]: no permission granted, cold
     * start, no signal. */
    private suspend fun publishOnce(vehicleUuid: String): PositionPublishOutcome {
        val fix = speedSource.locationFix.value ?: return PositionPublishOutcome.NO_FIX
        BatteryStatsCounters.recordHeartbeat()
        return runCatching {
            apiService.publishPosition(
                PositionPublishRequestDto(
                    vehicleId = vehicleUuid,
                    lat = fix.lat,
                    lng = fix.lng,
                    status = HEARTBEAT_STATUS,
                    battery = DeviceTelemetry.readBatteryPercent(appContext),
                    network = DeviceTelemetry.readNetworkType(appContext),
                    // Straight passthrough of the same fix's own speed/heading — see
                    // LocationFix.speedKmh/.heading for provenance (fused-location speed, real
                    // Location.getBearing() or honest null). Lets the dispatcher Live Map show a
                    // moving, oriented marker instead of just a bare dot.
                    speedKmh = fix.speedKmh,
                    heading = fix.heading,
                ),
            )
        }.fold(
            onSuccess = { PositionPublishOutcome.PUBLISHED },
            // A 404 here is the only failure that means anything other than "try again shortly" --
            // see `classifyPublishError`. Still silent to the driver, as every background publish in
            // this app is; the difference is that the loop now acts on it.
            onFailure = ::classifyPublishError,
        )
    }

    private companion object {
        /**
         * How often [publishLoop] re-evaluates state and decides whether to publish — NOT the
         * publish interval itself (see [HeartbeatCadence] for that); this is the granularity at
         * which a material change is noticed. See [publishLoop]'s own doc for why a poll rather
         * than a reactive combine, and this figure's "immediate enough without being busy-work"
         * framing there.
         */
        const val POLL_TICK_MS = 1_000L

        /**
         * How often to re-attempt a rego to UUID lookup while the binding is missing or rejected.
         *
         * Deliberately far slower than any [HeartbeatCadence] tier: this is a whole fleet-roster
         * fetch, and the conditions it recovers from (no signal at bind time, a fleet re-seeded
         * mid-shift) resolve on the scale of minutes, not seconds. 60s means a wiped-and-restored
         * fleet is back on the dispatcher's map within a minute, without a roster fetch every 5
         * seconds all shift.
         */
        const val REBIND_INTERVAL_MS = 60_000L

        /** See this class's own doc ("Why 'shift open'...") for why this is a fixed placeholder
         * rather than a real availability/on-trip status — no such signal exists to read from a
         * process-lifetime singleton like this one yet. */
        const val HEARTBEAT_STATUS = "unknown"
    }
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

    private companion object {
        const val METRES_PER_KM = 1000.0
    }

    /**
     * `true` when: this is the very first tick ([lastPublishAtMs] is `null` — covers "shift
     * start"), OR [materialChange] is set (the hired/duress edge — covers "trip open/close" and
     * "duress"), OR the vehicle has moved >= [MATERIAL_MOVE_METRES] since the last publish, OR
     * [cadence]'s own interval has simply elapsed.
     */
    fun shouldPublish(cadence: HeartbeatCadence, materialChange: Boolean, lat: Double?, lng: Double?): Boolean {
        val last = lastPublishAtMs
        return last == null || materialChange || movedFar(lat, lng) || (nowMs() - last) >= cadence.intervalMs
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

    /** Records that a publish just happened, for the next call's elapsed-time/distance-moved
     * checks. [lat]/[lng] are `null` exactly when [LivePositionHeartbeat.publishOnce] itself
     * would have skipped for lack of a fix — in that case there is nothing to compare the next
     * fix against, so the distance check simply never fires until a real fix is recorded. */
    fun recordPublish(lat: Double?, lng: Double?) {
        lastPublishAtMs = nowMs()
        lastPublishLat = lat
        lastPublishLng = lng
    }
}
