package au.com.threesixty.cabdispatch.domain.location.inertial

import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.SpeedSource
import au.com.threesixty.cabdispatch.domain.location.GeoMath
import au.com.threesixty.cabdispatch.domain.location.tunnel.TunnelLock
import au.com.threesixty.cabdispatch.domain.location.tunnel.TunnelRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Task 5 of W2: wires [ImuSampler] -> [VehicleFrameCalibrator] -> [InertialSpeedEstimator]
 * together into the one object [au.com.threesixty.cabdispatch.data.AppContainer] constructs and
 * `MeterController`/[au.com.threesixty.cabdispatch.domain.FareEngineImpl] consume.
 *
 * ### Deliberately NOT spliced into [au.com.threesixty.cabdispatch.domain.FareEngineImpl]'s own
 * `speedSource`
 * [FareEngineImpl.tick]'s entire F3 GPS-blackout detection works by finding [SpeedSource
 * .locationFix]'s age stale — if this class's fabricated, always-fresh fixes fed into THAT SAME
 * `speedSource` while a blackout is in progress, `gpsLost` would never observe the blackout at
 * all, silently defeating every G1-G8 rule W1 already built (and this class's own billing
 * integration along with it). So this class implements [SpeedSource] only for OTHER, display-only
 * consumers (the map puck, diagnostics) that want a position to show during a blackout; the
 * MONEY-relevant channel is [estimate] — the richer [InertialEstimate] with the confidence/
 * calibration fields billing needs — which [FareEngineImpl] reads directly, as a distinct
 * constructor dependency, never through the generic `SpeedSource` contract. See that class's
 * `inertialSpeedSource` parameter doc.
 *
 * ### Shadow mode (task 4)
 * While no blackout is in progress, this class re-seeds [InertialSpeedEstimator] from the live GPS
 * feed on every sample — the estimator is always running, always compared against ground truth,
 * and never allowed to accumulate drift while GPS is available. The moment [onBlackoutEntered] is
 * called, reseeding stops and the estimator free-runs on IMU alone until [onBlackoutExited].
 *
 * ### Road constraint
 * W3 (parallel workstream, not yet landed as of this pass) will let [displayFix] follow an actual
 * road polyline instead of a straight bearing ray. Until then, the DISPLAY-only position advances
 * along the last known heading from the blackout's entry fix ([GeoMath.destination]) — exactly
 * task 5's documented fallback ("advanced along heading from the entry fix — display only, never
 * itself billed as distance"). [FareEngineImpl]'s billing math never reads [locationFix] at all
 * during a blackout, so this approximation's inaccuracy cannot leak into a fare.
 */
class InertialSpeedSource(
    private val imuSampler: ImuSampler,
    private val calibrator: VehicleFrameCalibrator,
    private val real: SpeedSource,
    scope: CoroutineScope,
    private val estimator: InertialSpeedEstimator = InertialSpeedEstimator(),
    /** The bundled tunnel corridors, or null while still loading / on a build without the
     * asset -- see [au.com.threesixty.cabdispatch.domain.location.tunnel.TunnelRegistry]. */
    private val tunnelRegistry: () -> TunnelRegistry? = { null },
) : SpeedSource, InertialBillingSource {

    /** The corridor this blackout is locked to, if GPS dropped at a known tunnel portal. */
    @Volatile private var tunnelLock: TunnelLock? = null

    override val blackoutPathIsRoadLocked: Boolean get() = tunnelLock != null
    override val lockedRoadId: String? get() = tunnelLock?.corridor?.roadId
    override val lockedCorridorName: String? get() = tunnelLock?.corridor?.name

    override fun roadLockedPathKm(exitLat: Double, exitLng: Double): Double? =
        tunnelLock?.roadKmTo(exitLat, exitLng)

    private val _speedKmh = MutableStateFlow(0.0)
    private val _fix = MutableStateFlow<LocationFix?>(null)
    override val speedKmh: StateFlow<Double> = _speedKmh.asStateFlow()
    override val locationFix: StateFlow<LocationFix?> = _fix.asStateFlow()

    private val _estimate = MutableStateFlow<InertialEstimate?>(null)

    /** The rich, billing-relevant estimate — see class doc for why [FareEngineImpl] reads this,
     * never [speedKmh]/[locationFix]. */
    override val estimate: StateFlow<InertialEstimate?> = _estimate.asStateFlow()

    // --- task 4/8's shadow-mode residual evidence -----------------------------------------------
    private val residualBufferAbsKmh = ArrayDeque<Double>()
    private val _residualStats = MutableStateFlow<ResidualStats?>(null)

    /** Task 8's diagnostics figure -- see [ResidualStats]'s own doc for what gates on it. */
    val residualStats: StateFlow<ResidualStats?> = _residualStats.asStateFlow()

    @Volatile private var blackoutActive = false
    /** The newest [ImuSample] seen by the collect loop below — what [onBlackoutEntered] seeds the
     * forward axis from at the exact instant GPS drops (see [maybeReseedFromHeading]). */
    @Volatile private var latestSample: ImuSample? = null
    private var lastHeadingSeedNanos: Long = 0L
    private var entryFix: LocationFix? = null
    private var traveledAlongHeadingKm = 0.0
    /** See [InertialBillingSource.blackoutPath]. Guarded by itself: appended on the sampler thread,
     * snapshotted from the fare engine's tick. */
    private val pathPoints = mutableListOf<Pair<Double, Double>>()

    override val blackoutPath: List<Pair<Double, Double>>
        get() = synchronized(pathPoints) { pathPoints.toList() }
    private var lastSampleNanosForDisplay: Long? = null

    init {
        scope.launch {
            imuSampler.samples.collect { sample ->
                if (sample == null) return@collect
                latestSample = sample
                calibrator.onImuSample(sample)
                if (!blackoutActive) maybeReseedFromHeading(sample)
                val calib = calibrator.calibration.value
                val est = estimator.step(sample, calib)
                _estimate.value = est
                if (blackoutActive) {
                    _speedKmh.value = est.speedKmh
                    _fix.value = displayFix(est, sample.timestampNanos)
                } else {
                    // Task 4's "logs the residual vEst - vGps ... into a ring buffer" -- taken
                    // BEFORE reseeding, so it measures exactly one sample interval's worth of
                    // free-running drift since the LAST reseed, the real evidence [ResidualStats]
                    // exists to collect (see that type's own doc for what it gates).
                    recordResidual(est.speedKmh - real.speedKmh.value)
                    // Shadow mode (task 4): keep the estimator seeded from live ground truth so it
                    // never drifts while GPS is available, and so a blackout that starts on the very
                    // next sample begins from a trustworthy seed, not a stale one.
                    estimator.seed(real.speedKmh.value, real.locationFix.value?.heading)
                }
            }
        }
        scope.launch {
            real.speedKmh.collect { speedKmhValue -> calibrator.onGpsSpeedSample(speedKmhValue, System.nanoTime()) }
        }
    }

    /**
     * Called by [au.com.threesixty.cabdispatch.domain.FareEngineImpl] the instant a blackout is
     * declared (task 4's "Seed at blackout start"). [entrySpeedKmh]/[entryHeadingDeg] are the same
     * `lastKnownSpeedKmh`/last GPS bearing the W1 corridor catch-up already captures — one seed,
     * shared meaning, not a second independent notion of "speed at loss".
     */
    override fun onBlackoutEntered(entrySpeedKmh: Double, entryHeadingDeg: Double?, entryLocationFix: LocationFix?) {
        // Last chance to seed the forward axis from a REAL bearing before the free-run starts
        // (2026-09-14, T5453 field finding) -- the shadow-mode reseed above already keeps this
        // fresh while moving, this just guarantees the very last live heading is the one used.
        val sample = latestSample
        if (sample != null && entryHeadingDeg != null) calibrator.seedFromHeading(sample, entryHeadingDeg)
        blackoutActive = true
        entryFix = entryLocationFix
        traveledAlongHeadingKm = 0.0
        // Owner instruction (2026-09-15): at a known tunnel portal, lock to the tunnel and follow
        // its road, never a straight line off into the suburbs.
        tunnelLock = entryLocationFix?.let { fix -> tunnelRegistry()?.match(fix.lat, fix.lng, entryHeadingDeg) }
        synchronized(pathPoints) {
            pathPoints.clear()
            entryLocationFix?.let { pathPoints += it.lat to it.lng }
        }
        lastSampleNanosForDisplay = null
        estimator.seed(entrySpeedKmh, entryHeadingDeg)
    }

    /** Called on reacquisition — resumes shadow-mode reseeding immediately (rather than waiting for
     * the next tick) so the estimator does not sit on a frozen, now-stale free-run estimate. */
    override fun onBlackoutExited() {
        blackoutActive = false
        entryFix = null
        tunnelLock = null
        estimator.seed(real.speedKmh.value, real.locationFix.value?.heading)
    }

    /**
     * While GPS is live and the vehicle is genuinely moving (a real bearing needs real motion),
     * keeps the heading seed fresh at most every [HEADING_SEED_INTERVAL_NANOS] until the learned
     * calibration reaches GOOD -- see [VehicleFrameCalibrator.seedFromHeading]. Cheap (one 3x3
     * rotation), and it means a blackout that starts on the very next sample already has a
     * forward axis no more than two seconds old.
     */
    // ReturnCount: guard-clause style, same accepted pattern as VehicleFrameCalibrator's own methods.
    @Suppress("ReturnCount")
    private fun maybeReseedFromHeading(sample: ImuSample) {
        // No GOOD early-return here (removed 2026-09-14): the calibrator itself decides what a
        // seed means for a GOOD axis -- it is the ONLY evidence that can retire a persisted axis
        // learned from bogus events (see VehicleFrameCalibrator.seedFromHeading), and skipping the
        // call while GOOD kept that guard from ever firing on the bench.
        if (sample.timestampNanos - lastHeadingSeedNanos < HEADING_SEED_INTERVAL_NANOS) return
        val heading = real.locationFix.value?.heading ?: return
        if (real.speedKmh.value < HEADING_SEED_MIN_SPEED_KMH) return
        lastHeadingSeedNanos = sample.timestampNanos
        calibrator.seedFromHeading(sample, heading)
    }

    /** Rolling-window median/p95 of |residual|, recomputed on every shadow-mode sample once enough
     * history exists to make the figure meaningful -- see [ResidualStats]'s own doc. */
    private fun recordResidual(residualKmh: Double) {
        residualBufferAbsKmh.addLast(kotlin.math.abs(residualKmh))
        while (residualBufferAbsKmh.size > RESIDUAL_BUFFER_SIZE) residualBufferAbsKmh.removeFirst()
        if (residualBufferAbsKmh.size < MIN_RESIDUAL_SAMPLES) return
        val sorted = residualBufferAbsKmh.sorted()
        val p95Index = (sorted.size * P95_FRACTION).toInt().coerceAtMost(sorted.size - 1)
        _residualStats.value = ResidualStats(
            medianAbsKmh = sorted[sorted.size / 2],
            p95AbsKmh = sorted[p95Index],
            sampleCount = sorted.size,
        )
    }

    private fun displayFix(est: InertialEstimate, sampleNanos: Long): LocationFix? {
        val entry = entryFix ?: return null
        val previousNanos = lastSampleNanosForDisplay
        lastSampleNanosForDisplay = sampleNanos
        if (previousNanos != null) {
            val dtSeconds = (sampleNanos - previousNanos) / NANOS_PER_SECOND
            val dtHours = (dtSeconds / SECONDS_PER_HOUR).coerceIn(0.0, MAX_DISPLAY_STEP_HOURS)
            traveledAlongHeadingKm += est.speedKmh * dtHours
        }
        val lock = tunnelLock
        val heading: Double?
        val lat: Double
        val lng: Double
        if (lock != null) {
            // Locked to the tunnel: the integrated distance is walked along the corridor's own
            // geometry, and the heading reported is the road's, not the gyro's.
            val onRoad = lock.positionAt(traveledAlongHeadingKm)
            lat = onRoad.first
            lng = onRoad.second
            heading = lock.headingAt(traveledAlongHeadingKm)
        } else {
            heading = est.headingDegOrNull
            val free = if (heading != null) {
                GeoMath.destination(entry.lat, entry.lng, heading, traveledAlongHeadingKm)
            } else {
                entry.lat to entry.lng
            }
            lat = free.first
            lng = free.second
        }
        synchronized(pathPoints) {
            val last = pathPoints.lastOrNull()
            if (last == null || GeoMath.distanceKm(last.first, last.second, lat, lng) * METRES_PER_KM >= PATH_STEP_M) {
                pathPoints += lat to lng
            }
        }
        return LocationFix(
            lat = lat,
            lng = lng,
            speedKmh = est.speedKmh,
            // Never claim GPS-grade precision for a dead-reckoned display position.
            accuracyM = Float.MAX_VALUE,
            timestampMillis = System.currentTimeMillis(),
            heading = heading,
        )
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val SECONDS_PER_HOUR = 3600.0
        private const val MAX_DISPLAY_STEP_SECONDS = 30.0

        /** Caps one display-position step at 30s of travel even if the sensor thread stalls —
         * cosmetic-only guard, mirrors [InertialSpeedEstimator]'s own `MAX_STEP_SECONDS` reasoning. */
        private const val MAX_DISPLAY_STEP_HOURS = MAX_DISPLAY_STEP_SECONDS / SECONDS_PER_HOUR

        /** How often the heading seed is refreshed while GPS is live -- see [maybeReseedFromHeading]. */
        private const val HEADING_SEED_INTERVAL_NANOS = 2_000_000_000L

        /** Below this a GPS bearing is not trusted to mean "the direction the car is pointing"
         * (a crawling or stationary fix reports noise for a heading). */
        private const val HEADING_SEED_MIN_SPEED_KMH = 15.0

        /** [blackoutPath] vertex spacing -- a third of the toll detector's 60m confirm radius, so
         * a gantry the dead-reckoned path genuinely passes over always gets a vertex inside it. */
        private const val PATH_STEP_M = 20.0
        private const val METRES_PER_KM = 1000.0

        /** ~60s of history at [ImuSampler]'s ~10 Hz publish rate. */
        private const val RESIDUAL_BUFFER_SIZE = 600
        private const val MIN_RESIDUAL_SAMPLES = 10
        private const val P95_FRACTION = 0.95
    }
}
