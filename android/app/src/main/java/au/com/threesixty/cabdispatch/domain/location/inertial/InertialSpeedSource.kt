package au.com.threesixty.cabdispatch.domain.location.inertial

import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.SpeedSource
import au.com.threesixty.cabdispatch.domain.location.GeoMath
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
) : SpeedSource, InertialBillingSource {

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
    private var entryFix: LocationFix? = null
    private var traveledAlongHeadingKm = 0.0
    private var lastSampleNanosForDisplay: Long? = null

    init {
        scope.launch {
            imuSampler.samples.collect { sample ->
                if (sample == null) return@collect
                calibrator.onImuSample(sample)
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
        blackoutActive = true
        entryFix = entryLocationFix
        traveledAlongHeadingKm = 0.0
        lastSampleNanosForDisplay = null
        estimator.seed(entrySpeedKmh, entryHeadingDeg)
    }

    /** Called on reacquisition — resumes shadow-mode reseeding immediately (rather than waiting for
     * the next tick) so the estimator does not sit on a frozen, now-stale free-run estimate. */
    override fun onBlackoutExited() {
        blackoutActive = false
        entryFix = null
        estimator.seed(real.speedKmh.value, real.locationFix.value?.heading)
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
        val heading = est.headingDegOrNull
        val (lat, lng) = if (heading != null) {
            GeoMath.destination(entry.lat, entry.lng, heading, traveledAlongHeadingKm)
        } else {
            entry.lat to entry.lng
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

        /** ~60s of history at [ImuSampler]'s ~10 Hz publish rate. */
        private const val RESIDUAL_BUFFER_SIZE = 600
        private const val MIN_RESIDUAL_SAMPLES = 10
        private const val P95_FRACTION = 0.95
    }
}
