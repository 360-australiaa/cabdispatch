package au.com.threesixty.cabdispatch.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.math.cos

/**
 * A deterministic [SpeedSource] that behaves the way the real one does: **speed and position always
 * arrive together, on the same clock.**
 *
 * The fakes this replaces did not. One published a speed and left [locationFix] permanently `null`;
 * the other published a fixed position and never moved it. Both combinations are impossible in
 * production — [au.com.threesixty.cabdispatch.domain.location.RealLocationProvider] sets
 * `_speedKmh` and `_locationFix` from the same accepted fix, in the same statement — and both
 * quietly hid the exact bugs the F1/F2/F3 work exists to fix. A fake reporting 80 km/h from a
 * position that never changes is a fake that cannot tell the difference between a meter integrating
 * speed (the bug) and one measuring ground covered (the fix), because under it the two give
 * different answers and only the buggy one is non-zero.
 *
 * So this fake drives a vehicle. [emitFixAt] advances it along a due-east track at whatever
 * [setSpeed] last said, and stamps the resulting fix with the **virtual** clock, so a test running
 * on `kotlinx.coroutines.test`'s scheduler gets fix ages and tick deltas that agree with each other
 * and with the coroutine machinery. Pair it with
 * `FareEngineImpl(nanoTimeSource = { testScheduler.currentTime * 1_000_000L })`.
 *
 * Due east along a parallel is a deliberate simplification, not an approximation of a real route:
 * the distance a test needs is "did the vehicle cover the ground its speed claims", and a straight
 * track makes that checkable by hand.
 */
class FakeMeterGps(
    initialSpeedKmh: Double = 0.0,
    private val startLat: Double = -33.87,
    startLng: Double = 151.21,
    /** Reported horizontal accuracy. Above
     * [au.com.threesixty.cabdispatch.domain.location.RealLocationProvider]'s own 50m floor this
     * fix would never have been accepted in the first place, so the default is comfortably inside
     * it. */
    private val accuracyM: Float = 10f,
) : SpeedSource {

    private val _speedKmh = MutableStateFlow(initialSpeedKmh)
    override val speedKmh: StateFlow<Double> = _speedKmh

    private val _locationFix = MutableStateFlow<LocationFix?>(null)
    override val locationFix: StateFlow<LocationFix?> = _locationFix

    private var currentLng: Double = startLng
    private var lastEmitMillis: Long? = null

    /** Total ground distance this fake has actually driven, km — what a correct meter should have
     * charged for, available to a test as the independent figure to check the engine against. */
    var travelledKm: Double = 0.0
        private set

    fun setSpeed(kmh: Double) {
        _speedKmh.value = kmh
    }

    /**
     * Moves the vehicle forward by however much virtual time has passed since the last emission, at
     * the current speed, and publishes the resulting fix stamped at [virtualMillis].
     *
     * The very first call publishes a starting position without moving (there is no previous
     * emission to have travelled from), which mirrors the real first fix of a trip.
     */
    fun emitFixAt(virtualMillis: Long) {
        val previous = lastEmitMillis
        if (previous != null) {
            val elapsedSeconds = (virtualMillis - previous) / 1000.0
            val km = _speedKmh.value * elapsedSeconds / 3600.0
            travelledKm += km
            currentLng += kmToLngDegrees(km, startLat)
        }
        lastEmitMillis = virtualMillis
        _locationFix.value = LocationFix(
            lat = startLat,
            lng = currentLng,
            speedKmh = _speedKmh.value,
            accuracyM = accuracyM,
            timestampMillis = virtualMillis,
            receivedAtNanos = virtualMillis * NANOS_PER_MILLI,
        )
    }

    /**
     * Publishes a fix at an explicit position — for tests that need the vehicle at a particular
     * gantry rather than somewhere along the default track. Does not disturb [travelledKm]'s
     * accounting of the synthetic track.
     */
    fun emitFixAt(virtualMillis: Long, lat: Double, lng: Double) {
        lastEmitMillis = virtualMillis
        _locationFix.value = LocationFix(
            lat = lat,
            lng = lng,
            speedKmh = _speedKmh.value,
            accuracyM = accuracyM,
            timestampMillis = virtualMillis,
            receivedAtNanos = virtualMillis * NANOS_PER_MILLI,
        )
    }

    /**
     * Stops publishing fixes without clearing the last one — a tunnel, a multi-storey car park, an
     * urban canyon. The already-published fix simply grows old, which is precisely the condition
     * F3's staleness cut-off exists to notice; nothing about the [SpeedSource] contract changes,
     * which is the honest simulation, because in the real blackout nothing about it changes either.
     * That was the whole bug.
     */
    fun goDark() {
        lastEmitMillis = null
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** Metres per degree of longitude at [lat] — the standard spherical approximation. */
        fun kmToLngDegrees(km: Double, lat: Double): Double =
            km / (111.31949079327357 * cos(Math.toRadians(lat)))
    }
}

/** Virtual-clock nanoTime source to hand [FareEngineImpl] so its tick deltas and fix ages are
 * measured on the same clock `advanceTimeBy` moves. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.virtualNanoTimeSource(): () -> Long = { testScheduler.currentTime * 1_000_000L }

/**
 * Advances the meter by exactly one real tick, with GPS behaving normally.
 *
 * A fix is published *before* the clock moves, so that when the engine's own `delay(1000)` fires a
 * second later it sees a one-second-old fix — the ordinary, healthy case — and measures the ground
 * covered between this fix and the previous tick's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun TestScope.advanceOneTick(gps: FakeMeterGps) {
    gps.emitFixAt(testScheduler.currentTime)
    advanceTimeBy(1000)
    runCurrent()
}

/**
 * Advances one tick with **no** new fix published, leaving the last one to age.
 *
 * Two quite different tests want this. One is a vehicle holding a position the test placed it at
 * explicitly (a gantry), where publishing a fresh track fix would move it back off. The other is a
 * blackout: call this repeatedly and the last fix ages past
 * [FareEngineImpl.MAX_FIX_AGE_MS], which is what puts the engine into [FareState.gpsLost].
 *
 * A *single* call is still comfortably inside the staleness window — one second old — so it does
 * not by itself simulate lost GPS.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun TestScope.advanceOneTickWithNoNewFix() {
    advanceTimeBy(1000)
    runCurrent()
}
