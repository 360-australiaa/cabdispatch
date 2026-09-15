package au.com.threesixty.cabdispatch.domain.location.inertial

import au.com.threesixty.cabdispatch.domain.LocationFix
import kotlinx.coroutines.flow.StateFlow

/**
 * The money-relevant surface of [InertialSpeedSource] — split out as its own interface (rather
 * than [au.com.threesixty.cabdispatch.domain.FareEngineImpl] depending on the concrete
 * [InertialSpeedSource] class directly) for the same reason [au.com.threesixty.cabdispatch.domain
 * .SpeedSource]/`TollRegistryProvider`/`KnownCorridorDistanceLookup` are each their own seam in
 * this codebase: a money-critical class stays plain-JVM-testable against a fake, without needing
 * a `Context`/`SensorManager`/Robolectric just to construct one. `InertialSpeedSource` is the one
 * production implementation; `FareEngineImpl`'s own test suite constructs a small in-memory fake
 * against this interface instead.
 */
interface InertialBillingSource {
    /** See [InertialEstimate]'s own doc. `null` before the estimator has ever run. */
    val estimate: StateFlow<InertialEstimate?>

    /** Task 4's "Seed at blackout start" — see [InertialSpeedEstimator.seed]'s doc for what each
     * argument means; [entryLocationFix] is carried through only for [InertialSpeedSource]'s own
     * display-position purposes (never read for billing). */
    fun onBlackoutEntered(entrySpeedKmh: Double, entryHeadingDeg: Double?, entryLocationFix: LocationFix?)

    /** Reacquisition — resumes shadow-mode reseeding. */
    fun onBlackoutExited()

    /**
     * The dead-reckoned polyline (lat, lng) walked since [onBlackoutEntered] -- the same
     * heading-integrated positions the meter map already shows during a blackout, downsampled
     * to ~20m steps. Read ONCE by [au.com.threesixty.cabdispatch.domain.FareEngineImpl] at
     * reacquisition to sweep toll gantries along a tunnel no single registry road brackets
     * (2026-09-14, T5453: Rozelle Interchange -> M4-M8 Link -> M4 tunnels is three roads, so the
     * corridor match found none of them). Display-grade geometry, never itself billed as
     * distance. Empty when no blackout is in progress or the implementation keeps no path.
     */
    val blackoutPath: List<Pair<Double, Double>> get() = emptyList()

    /** True when [blackoutPath] is the real road geometry of a locked tunnel corridor rather than
     * free-run dead reckoning -- the engine then walks it as-is instead of closure-correcting it
     * to the exit fix (2026-09-15 tunnel lock, see
     * [au.com.threesixty.cabdispatch.domain.location.tunnel.TunnelCorridor]). */
    val blackoutPathIsRoadLocked: Boolean get() = false

    /** The locked corridor's toll-registry road id, when a tunnel lock is in force. */
    val lockedRoadId: String? get() = null

    /** Every toll road the locked chain runs along (Rozelle Interchange + M4 for the Anzac Bridge
     * -> M4 East drive); empty when not locked. */
    val lockedRoadIds: Set<String> get() = emptySet()

    /** The locked corridor's display name ("Lane Cove Tunnel (Westbound)"), when locked. */
    val lockedCorridorName: String? get() = null

    /** Every bore in the locked chain, by name -- see [au.com.threesixty.cabdispatch.domain
     * .TripModels.ActiveBlackout.lockedCorridorNames]'s own doc for why [lockedCorridorName] alone
     * (the entry bore's name) is not enough to suppress an advisory once inside a LATER bore of a
     * chained lock. */
    val lockedCorridorNames: Set<String> get() = emptySet()

    /** Road distance along the locked corridor from the blackout entry to the reacquisition fix,
     * km -- the billing reference that replaces the chord (null when not locked or the fix is
     * not on the corridor). */
    fun roadLockedPathKm(exitLat: Double, exitLng: Double): Double? = null
}

/**
 * Shared value types for the inertial dead-reckoning package (W2, GPS-blackout optimisation
 * program, 2026-09-12 — `docs/plans/2026-09-12-android-meter-optimisation-and-gps-blackout-plan.md`
 * §1.3/W2). Split out of the individual component files because [ImuSample], [InertialEstimate]
 * and [VehicleFrameCalibration] are each produced by one component and consumed by at least one
 * other — putting them where the first consumer happens to live would make the real dependency
 * direction ([ImuSampler] -> [au.com.threesixty.cabdispatch.domain.location.inertial.VehicleFrameCalibrator]
 * -> [InertialSpeedEstimator] -> [InertialSpeedSource]) harder to read, not easier.
 *
 * **The one thing every type here is careful never to be:** a free-space position. Per the owner's
 * decision (§1.3), this package estimates exactly one scalar — speed along whatever road the
 * vehicle is already known to be on — never an unconstrained (lat, lng) drift from double-
 * integrating acceleration. [ImuSample] carries raw sensor vectors; [InertialEstimate] carries a
 * speed, a heading and a confidence; neither carries a position. Positioning a synthetic
 * [au.com.threesixty.cabdispatch.domain.LocationFix] for display (never for billing) is
 * [InertialSpeedSource]'s job, and even there it is display-only — see that file's own doc.
 */

/**
 * One combined reading of the tablet's motion sensors, downsampled to the ~10 Hz
 * [ImuSampler] publishes at (raw sensor events arrive faster, at `SENSOR_DELAY_GAME`, but the
 * estimator only needs to integrate at 10 Hz — see [ImuSampler]'s own doc for why averaging the
 * intermediate raw events into each published sample, rather than simply dropping them, matters
 * for noise).
 *
 * All vectors are in the TABLET's own reference frame (whatever orientation it happens to be
 * mounted in), in the units `android.hardware.SensorEvent` itself uses (m/s² for acceleration,
 * rad/s for angular velocity, and the gravity sensor's own m/s² "which way is down" vector) —
 * [au.com.threesixty.cabdispatch.domain.location.inertial.VehicleFrameCalibrator] is what turns
 * "tablet frame" into "vehicle frame" (forward/lateral/vertical), nothing upstream of it need know
 * how the tablet is mounted.
 *
 * @property timestampNanos The sensor's own `SensorEvent.timestamp` (monotonic, `SystemClock
 *   .elapsedRealtimeNanos()`-equivalent, NOT wall-clock) — same reasoning
 *   [au.com.threesixty.cabdispatch.domain.LocationFix.receivedAtNanos] gives for using a clock
 *   that cannot jump: this value is differenced against itself, tick to tick, to get `dt` for
 *   integration, and an NTP correction or a driver changing the date mid-shift must never be able
 *   to make that `dt` negative or absurd.
 * @property linearAccelerationMps2 `TYPE_LINEAR_ACCELERATION` — gravity already subtracted by the
 *   platform's own sensor fusion, tablet frame, x/y/z.
 * @property gravityMps2 `TYPE_GRAVITY` — "which way is down" in tablet frame; this is what lets
 *   [VehicleFrameCalibrator] find the horizontal plane without needing the tablet held flat.
 * @property gyroscopeRadPerS `TYPE_GYROSCOPE` — angular velocity, tablet frame, rad/s.
 * @property rotationVector `TYPE_ROTATION_VECTOR` (first three components; the fourth, scalar,
 *   component is reconstructed if present but not needed by anything in this package) — used only
 *   to detect the tablet being lifted out of its mount (a large, sustained rotation relative to
 *   gravity), never for heading directly (the gyro integral is used for that, so the correction is
 *   internally consistent with [InertialSpeedEstimator]'s own heading integration).
 */
data class ImuSample(
    val timestampNanos: Long,
    val linearAccelerationMps2: FloatArray,
    val gravityMps2: FloatArray,
    val gyroscopeRadPerS: FloatArray,
    val rotationVector: FloatArray,
) {
    // A data class with FloatArray properties gets Kotlin's reference-equality/hashCode footgun
    // for free unless these are hand-written — this class is compared in tests (a synthetic sample
    // built by a test fixture must equal an identical one), so the footgun is real here, not
    // theoretical.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImuSample) return false
        return timestampNanos == other.timestampNanos &&
            linearAccelerationMps2.contentEquals(other.linearAccelerationMps2) &&
            gravityMps2.contentEquals(other.gravityMps2) &&
            gyroscopeRadPerS.contentEquals(other.gyroscopeRadPerS) &&
            rotationVector.contentEquals(other.rotationVector)
    }

    override fun hashCode(): Int {
        var result = timestampNanos.hashCode()
        result = 31 * result + linearAccelerationMps2.contentHashCode()
        result = 31 * result + gravityMps2.contentHashCode()
        result = 31 * result + gyroscopeRadPerS.contentHashCode()
        result = 31 * result + rotationVector.contentHashCode()
        return result
    }
}

/**
 * How much [InertialSpeedEstimator] trusts its own current [InertialEstimate.speedKmh] — task 4's
 * "error budget" collapsed to the three tiers the billing decision and the dial actually need,
 * rather than exposing the raw `sigmaV` figure (still available via
 * [InertialEstimate.sigmaVMetresPerSecond] for diagnostics) to every consumer.
 */
enum class InertialConfidence { HIGH, MEDIUM, LOW }

/**
 * One tick's worth of [InertialSpeedEstimator] output — the estimator's complete, honest answer to
 * "how fast is this vehicle going, and how sure are you", including the two things that decide
 * whether [au.com.threesixty.cabdispatch.domain.FareEngineImpl.tick] is allowed to bill against it
 * at all: [calibrationGood] and [confidence].
 *
 * @property speedKmh The estimated forward speed, always `>= 0` (task 4's bounds rule) — this
 *   package never estimates a vehicle reversing; a taxi backing up during a blackout is the one
 *   scenario this estimator deliberately under-bills rather than risk a signed-heading error
 *   doubling a distance.
 * @property headingDegOrNull Estimated compass heading, degrees, or `null` before the estimator has
 *   ever been seeded (task 4's "seed at blackout start") — never fabricated.
 * @property sigmaVMetresPerSecond The raw error-budget figure task 4 defines; [confidence] is
 *   derived from it via [InertialSpeedEstimator]'s own thresholds, kept here too for the
 *   diagnostics panel (task 8) to show a number that actually moves, not just a three-value enum.
 * @property zuptCount How many zero-velocity updates have fired since the estimator was last
 *   [InertialSpeedEstimator.seed]ed — task 4's "drift killer" evidence, surfaced to the audit trail
 *   (a blackout with zero ZUPTs over several minutes at speed is itself worth a human's attention).
 * @property calibrationGood Mirrors [VehicleFrameCalibration.quality] `== GOOD` at the moment this
 *   estimate was produced — carried on the estimate itself (rather than making every consumer also
 *   separately read the calibrator) so a consumer that only ever sees [InertialEstimate] still has
 *   the one fact that gates billing.
 * @property unreliable Task 4's `sigmaV` "`LOW` for more than 120 s" rule — once true, the engine
 *   must fall back to the W1 rule for the REST of the current blackout, even if `sigmaV` happens to
 *   dip back under the `LOW` threshold on a later tick (a `sigmaV` that already spent two minutes
 *   unbounded has, by construction, drifted too far to trust again without a fresh GPS seed).
 */
data class InertialEstimate(
    val speedKmh: Double,
    val headingDegOrNull: Double?,
    val sigmaVMetresPerSecond: Double,
    val confidence: InertialConfidence,
    val zuptCount: Int,
    val calibrationGood: Boolean,
    val unreliable: Boolean,
)

/**
 * How well [VehicleFrameCalibrator] currently knows the tablet's mounted orientation relative to
 * the vehicle — task 3's quality tiers, collapsed for consumption outside that file the same way
 * [InertialConfidence] collapses the estimator's own error budget.
 */
enum class CalibrationQuality {
    /** Fewer than [VehicleFrameCalibrator.MIN_CALIBRATION_EVENTS] confirming GPS-speed-change
     * events observed since the tablet was last (re)mounted — no usable forward axis yet. */
    NONE,

    /** Enough events observed, but their angular spread exceeds task 3's 15° bound — the tablet's
     * mount may be loose, or genuinely conflicting events (e.g. a U-turn misread as a speed-change
     * event) are polluting the estimate. Not used for billing; shown to the driver as "learning". */
    LEARNING,

    /**
     * A forward axis derived in one shot from the tablet's own rotation-vector orientation plus
     * the last live GPS bearing ([HeadingSeed]) — no confirming events needed. Ranked BELOW
     * [GOOD]: [InertialSpeedEstimator] integrates against it (so a first-drive tunnel gets a
     * live accelerometer speed instead of a frozen entry speed — the T5453 field finding,
     * 2026-09-14), but it is never persisted and is replaced the moment task 3's learned axis
     * reaches [GOOD]. Carries no learned bias terms (both zero until stationary windows fill
     * them in).
     */
    SEEDED,

    /** Task 3's bar cleared: `>= MIN_CALIBRATION_EVENTS` with angular spread `< 15°`. The
     * learned quality [InertialSpeedSource] prefers over [SEEDED] whenever it exists. */
    GOOD,
}

/**
 * Task 4's shadow-mode evidence: how far [InertialSpeedEstimator]'s own free-running estimate
 * drifted from live GPS over one shadow-mode reseed interval, aggregated over a rolling window —
 * this is the exact figure the plan's acceptance criteria (task 8/W2 acceptance: "median |residual|
 * <= 3 km/h, p95 <= 8 km/h") gates **OWNER G3** (enabling real billing) on. Surfaced to
 * `InertialDiagnosticsPanel.kt` (task 8) so that evidence is visible without an APK rebuild.
 */
data class ResidualStats(
    val medianAbsKmh: Double,
    val p95AbsKmh: Double,
    val sampleCount: Int,
)

/**
 * The learned mapping from tablet frame to vehicle frame — task 3's output. Immutable; a new
 * calibration event produces a new instance rather than mutating one in place, so a consumer
 * holding a reference from one tick never sees it change under it mid-computation.
 *
 * @property forwardTablet Unit vector, tablet frame, pointing in the vehicle's forward direction.
 * @property gyroZBiasRadPerS Learned bias on the vertical-axis gyro reading — see task 3's
 *   "estimates the gyro z-bias ... during stationary windows".
 * @property forwardAccelBiasMps2 Learned bias on the forward-axis linear-acceleration reading,
 *   same stationary-window source as [gyroZBiasRadPerS].
 * @property quality See [CalibrationQuality].
 * @property confirmingEventCount How many qualifying GPS-speed-change events contributed to this
 *   calibration — task 3's "requires >= 8 such events" threshold, kept on the instance itself so a
 *   diagnostics panel can show progress ("6 of 8") without reaching back into the calibrator's
 *   private state.
 */
data class VehicleFrameCalibration(
    val forwardTablet: DoubleArray,
    val gyroZBiasRadPerS: Double,
    val forwardAccelBiasMps2: Double,
    val quality: CalibrationQuality,
    val confirmingEventCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VehicleFrameCalibration) return false
        return forwardTablet.contentEquals(other.forwardTablet) &&
            gyroZBiasRadPerS == other.gyroZBiasRadPerS &&
            forwardAccelBiasMps2 == other.forwardAccelBiasMps2 &&
            quality == other.quality &&
            confirmingEventCount == other.confirmingEventCount
    }

    override fun hashCode(): Int {
        var result = forwardTablet.contentHashCode()
        result = 31 * result + gyroZBiasRadPerS.hashCode()
        result = 31 * result + forwardAccelBiasMps2.hashCode()
        result = 31 * result + quality.hashCode()
        result = 31 * result + confirmingEventCount.hashCode()
        return result
    }
}
