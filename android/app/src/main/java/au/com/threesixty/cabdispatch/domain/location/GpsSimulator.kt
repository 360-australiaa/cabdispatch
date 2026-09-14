package au.com.threesixty.cabdispatch.domain.location

import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.SpeedSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fabricated GPS for testing the meter without driving — position, speed and direction of travel,
 * fed into the app through the same [SpeedSource] interface the real fused-location provider
 * implements, so everything downstream (the fare engine, toll detection, the map, the trip's
 * gps_trace) behaves exactly as it does on a real road.
 *
 * ### This forges the primary evidence of a fare-regulated meter. Read this before using it.
 *
 * A trip driven under simulation produces a gps_trace that is internally consistent, replays
 * cleanly through the server's own `reconstruct_fare`, and is otherwise **indistinguishable from
 * a real fare**. Two things keep that from being a problem, and both are load-bearing:
 *
 * 1. **Every trip started while [active] is true is flagged `simulated` on the trip record**, and
 *    that flag travels all the way to the server and the dashboard (see `TripEntity.simulated`).
 *    Without it a test trip would sit in the operator's ledger as real revenue and real
 *    compliance evidence. The flag is set from this object rather than from the UI, so it cannot
 *    be forgotten by a caller.
 * 2. **The meter shows a permanent, unmissable banner while simulating.** A driver must never be
 *    able to look at the screen and not know the position is fake.
 *
 * Note what is deliberately NOT relied on here: `BuildConfig.DEBUG`. The tablet in the field runs
 * a debug build pointed at the production backend (the release build type still points at a
 * placeholder URL — see app/build.gradle.kts), so a debug-only gate would not have kept a single
 * simulated trip out of the real ledger. The flag on the trip is what actually does that job.
 *
 * ### Timebase
 * Fixes are emitted at [TICK_INTERVAL_MS], matching [RealLocationProvider.TICK_INTERVAL_MS] and
 * the fare engine's own 1 Hz tick. Position is computed from the elapsed simulated time rather
 * than by stepping a cursor, so a delayed or dropped tick lands the vehicle where it should
 * genuinely be by then instead of accumulating drift.
 */
class GpsSimulator(private val scope: CoroutineScope) {

    private val _fix = MutableStateFlow<LocationFix?>(null)
    private val _speedKmh = MutableStateFlow(0.0)
    private val _route = MutableStateFlow<SimulatedRoute?>(null)
    private val _finished = MutableStateFlow(false)
    private var finishedAtMillis: Long? = null

    /**
     * The route's TRUE motion this tick -- published on every tick, INCLUDING blackout ticks that
     * withhold the GPS fix -- for [au.com.threesixty.cabdispatch.domain.location.inertial
     * .SimulatedImu] to synthesise accelerometer/gyro samples from. Bench testing only: this is
     * what lets a tablet sitting on a desk in Karachi exercise the tunnel dead-reckoning path
     * (seeded axis -> integration -> estimated position) end to end. `null` whenever no route is
     * running, so the IMU falls straight back to the real sensors.
     */
    private val _kinematics = MutableStateFlow<SimKinematics?>(null)
    val kinematics: StateFlow<SimKinematics?> = _kinematics.asStateFlow()
    private var lastKinematicsSpeedKmh: Double? = null
    private var lastKinematicsBearingDeg: Double? = null
    private var lastKinematicsElapsedS: Double? = null

    /** The route being driven, or null when the simulator is off. */
    val route: StateFlow<SimulatedRoute?> = _route.asStateFlow()

    /** True while fabricated fixes are being produced. */
    val active: StateFlow<Boolean>
        get() = _activeState.asStateFlow()

    private val _activeState = MutableStateFlow(false)

    /** True once the route has been driven to its end (the vehicle then sits still). */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private var job: Job? = null

    /**
     * The [SpeedSource] view of this simulator. Handed to [SwitchableSpeedSource] rather than
     * wired directly, so nothing downstream has to know whether it is reading real or fake GPS.
     */
    val speedSource: SpeedSource = object : SpeedSource {
        override val speedKmh: StateFlow<Double> = _speedKmh.asStateFlow()
        override val locationFix: StateFlow<LocationFix?> = _fix.asStateFlow()
    }

    /** Starts (or restarts) driving [route] from its first waypoint. */
    fun start(route: SimulatedRoute) {
        stop()
        _route.value = route
        _finished.value = false
        _activeState.value = true
        simulating = true

        job = scope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                val elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000.0
                val position = route.positionAt(elapsedSeconds)
                publishKinematics(route, position, elapsedSeconds)

                // GPS blackout (2026-09-12, W1): inside one of the route's own blackoutWindows,
                // publish NOTHING -- not a frozen copy of the last fix, an actual skip -- so
                // `_fix`/`_speedKmh` are left exactly as they were on the tick before the window
                // opened, and their age (LocationFix.receivedAtNanos) ticks forward toward
                // FareEngineImpl's real MAX_FIX_AGE_MS staleness cutoff the same way a real
                // fused-location provider going quiet in a tunnel does. See SimulatedRoute
                // .isBlackoutAt's own doc.
                if (route.isBlackoutAt(elapsedSeconds)) {
                    delay(TICK_INTERVAL_MS)
                    continue
                }

                // A finished route parks the vehicle: still emitting fixes (a real device does
                // not stop reporting when you arrive) but at 0 km/h, so the meter correctly
                // switches to waiting mode instead of the trip appearing to vanish.
                val speed = if (position.finished) 0.0 else route.speedAt(elapsedSeconds)
                _speedKmh.value = speed
                _fix.value = LocationFix(
                    lat = position.point.lat,
                    lng = position.point.lng,
                    speedKmh = speed,
                    // A plausible good-signal accuracy rather than 0.0: several places treat
                    // accuracy as real (the fix filter, the "GPS" status pill), and a perfect
                    // 0m fix is not something any real receiver ever reports.
                    accuracyM = SIMULATED_ACCURACY_M,
                    timestampMillis = System.currentTimeMillis(),
                    heading = position.bearingDegrees,
                )
                if (position.finished) {
                    if (!_finished.value) {
                        _finished.value = true
                        finishedAtMillis = System.currentTimeMillis()
                    } else if (
                        System.currentTimeMillis() - (finishedAtMillis ?: 0L) >= FINISHED_GRACE_MS &&
                        SessionHolder.liveTripClientUuid.value == null
                    ) {
                        // RELEASE THE METER (tablet, 2026-09-08). A finished route used to park the
                        // vehicle forever: still "active", still the source the meter reads, still
                        // 0 km/h -- with real GPS ignored and the red banner up -- until a human
                        // found STOP in Settings. The next fare started on that tablet read 0 km/h
                        // and 0.0 km for its whole life. Parking briefly is right (the calm-motion
                        // check needs a still ring at the end); parking indefinitely is a trap.
                        // After the grace the simulator stops itself and the switch hands the
                        // meter back to the real provider.
                        //
                        // Bug found in live on-device testing (2026-09-13): the grace timer alone
                        // does not know whether the CURRENT trip -- the one that opened under this
                        // very simulation and is correctly flagged `simulated` -- is still running.
                        // The ordinary case is exactly that: nobody taps End Fare within 20 seconds
                        // of the simulated route's last waypoint. That driver's live position was
                        // silently swapped to whatever the real fused-location provider reports --
                        // on a tablet with no real satellite fix and only network-based location,
                        // that can be a fix thousands of kilometres from the simulated route,
                        // self-reported to the backend and fed straight into the still-running
                        // fare/toll pipeline as though it were the next genuine sample. The
                        // `simulated` flag on the trip survives (it is stamped once, at open), but
                        // the live distance/toll numbers produced after the swap do not deserve it.
                        // Gating the release on SessionHolder.liveTripClientUuid keeps the parked
                        // fix held (re-checked every tick, so release still follows within one tick
                        // of End Fare) for as long as a trip this process opened is still live --
                        // the same "park, do not vanish" reasoning above, just extended to cover the
                        // trip's whole lifetime instead of stopping at one fixed grace window.
                        stop()
                        return@launch
                    }
                }

                delay(TICK_INTERVAL_MS)
            }
        }
    }

    /**
     * Stops simulating and goes quiet.
     *
     * Leaves [_fix] as the last simulated position rather than nulling it: the switch back to
     * real GPS is what restores real position (see [SwitchableSpeedSource]), and blanking the
     * fix here would briefly show "no GPS" on a device that has perfectly good GPS.
     */
    private fun publishKinematics(route: SimulatedRoute, position: RoutePosition, elapsedSeconds: Double) {
        val speedKmh = if (position.finished) 0.0 else route.speedAt(elapsedSeconds)
        val bearing = position.bearingDegrees
        val prevSpeed = lastKinematicsSpeedKmh
        val prevBearing = lastKinematicsBearingDeg
        val prevElapsed = lastKinematicsElapsedS
        val dt = if (prevElapsed != null) elapsedSeconds - prevElapsed else 0.0
        val accel = if (prevSpeed != null && dt > 0.0) (speedKmh - prevSpeed) / KMH_PER_MPS / dt else 0.0
        val yawRate = if (prevBearing != null && dt > 0.0) {
            var delta = bearing - prevBearing
            while (delta > HALF_TURN_DEG) delta -= FULL_TURN_DEG
            while (delta < -HALF_TURN_DEG) delta += FULL_TURN_DEG
            Math.toRadians(delta) / dt
        } else {
            0.0
        }
        lastKinematicsSpeedKmh = speedKmh
        lastKinematicsBearingDeg = bearing
        lastKinematicsElapsedS = elapsedSeconds
        _kinematics.value = SimKinematics(
            speedKmh = speedKmh,
            bearingDeg = bearing,
            forwardAccelMps2 = accel,
            yawRateRadPerS = yawRate,
            blackout = route.isBlackoutAt(elapsedSeconds),
        )
    }

    fun stop() {
        job?.cancel()
        job = null
        finishedAtMillis = null
        _kinematics.value = null
        lastKinematicsSpeedKmh = null
        lastKinematicsBearingDeg = null
        lastKinematicsElapsedS = null
        _speedKmh.value = 0.0
        _activeState.value = false
        _route.value = null
        simulating = false
    }

    companion object {
        /**
         * Process-wide "is fabricated GPS live right now", readable without holding a reference
         * to the simulator instance.
         *
         * Exists for exactly one caller: [TripRepository][au.com.threesixty.cabdispatch.data.repository.TripRepository].openTrip,
         * which stamps `TripEntity.simulated`. Threading it through as a parameter instead would
         * put the decision at every call site that opens a trip, and the one thing that must not
         * happen is a screen opening a trip on fake GPS and forgetting to say so. Same
         * process-scoped-mutable-state pattern as
         * [SessionHolder][au.com.threesixty.cabdispatch.domain.SessionHolder], and `@Volatile`
         * for the same reason: written on whichever thread drives the simulator UI, read on the
         * coroutine that opens the trip.
         */
        @Volatile
        private var simulating: Boolean = false

        fun isSimulating(): Boolean = simulating

        const val TICK_INTERVAL_MS = 1_000L
        private const val KMH_PER_MPS = 3.6
        private const val HALF_TURN_DEG = 180.0
        private const val FULL_TURN_DEG = 360.0
        /** How long a finished route stays parked before the simulator releases the meter. */
        const val FINISHED_GRACE_MS = 20_000L

        /** Metres. Mid-range for a real in-vehicle fix (LocationFix.accuracyM documents 5-30m). */
        const val SIMULATED_ACCURACY_M = 8.0f
    }
}

/**
 * A [SpeedSource] that reads from the real GPS provider normally, and from [simulator] while it
 * is running.
 *
 * Exists so the swap happens in ONE place. The alternative -- letting each consumer decide which
 * source to read -- would mean the fare engine and the map could disagree about where the vehicle
 * is, which is exactly the kind of split-brain bug that is invisible until a fare is wrong.
 */
class SwitchableSpeedSource(
    private val real: SpeedSource,
    private val simulator: GpsSimulator,
    scope: CoroutineScope,
) : SpeedSource {

    private val _speedKmh = MutableStateFlow(0.0)
    private val _fix = MutableStateFlow<LocationFix?>(null)

    override val speedKmh: StateFlow<Double> = _speedKmh.asStateFlow()
    override val locationFix: StateFlow<LocationFix?> = _fix.asStateFlow()

    init {
        // Both upstreams are collected unconditionally and filtered on write, rather than
        // switching subscriptions: `real` is a hot provider whose own start/stop is driven by a
        // permission poll loop (see RealLocationProvider), and unsubscribing from it here would
        // interfere with that lifecycle for the sake of an optimisation that saves nothing.
        scope.launch {
            real.speedKmh.collect { if (!simulator.active.value) _speedKmh.value = it }
        }
        scope.launch {
            real.locationFix.collect { if (!simulator.active.value) _fix.value = it }
        }
        scope.launch {
            simulator.speedSource.speedKmh.collect { if (simulator.active.value) _speedKmh.value = it }
        }
        scope.launch {
            simulator.speedSource.locationFix.collect { if (simulator.active.value) _fix.value = it }
        }
        // On stopping the simulator, immediately republish the real provider's current values so
        // the meter does not sit on the last fabricated position until the next real fix lands.
        scope.launch {
            simulator.active.collect { simulating ->
                if (!simulating) {
                    _speedKmh.value = real.speedKmh.value
                    _fix.value = real.locationFix.value
                }
            }
        }
    }
}

/** One tick of a simulated route's true motion -- see [GpsSimulator.kinematics]. */
data class SimKinematics(
    val speedKmh: Double,
    val bearingDeg: Double,
    val forwardAccelMps2: Double,
    val yawRateRadPerS: Double,
    val blackout: Boolean,
)
