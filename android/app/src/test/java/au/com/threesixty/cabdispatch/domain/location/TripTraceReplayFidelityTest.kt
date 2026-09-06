package au.com.threesixty.cabdispatch.domain.location

import au.com.threesixty.cabdispatch.domain.fare.FareEngine
import au.com.threesixty.cabdispatch.domain.fare.FareState
import au.com.threesixty.cabdispatch.domain.fare.URBAN_TARIFF
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Confirms the actual point-thresholding decision this pass made
 * ([TracePointRecorder]/`HiredViewModel.nextTracePoint`: record one real point per fare-engine
 * tick, not a sparse distance/time-thresholded sample) by replaying the SAME two algorithms real
 * code runs:
 *
 * - **"Device"** — [au.com.threesixty.cabdispatch.domain.FareEngineImpl.tick]'s own formula:
 *   `distanceDeltaKm = speedKmh / 3600.0` per 1-second tick (a synthetic distance derived from
 *   speed, never from position — see that class's own doc).
 * - **"Server replay"** — `backend/app/services/trips.py::recompute_from_trace`'s own formula
 *   (verified against that file directly, not assumed): `distance_delta_km = haversine(prev, cur)`
 *   between consecutive trace points, `elapsed_seconds = cur.ts - prev.ts`, mode picked per point
 *   from that point's own `speed_kmh`. Both arms call the SAME [FareEngine.tick] — the pure Kotlin
 *   port `FareEngineTest.kt` already pins against the Python original — so any divergence here is
 *   purely from what the two callers feed it, exactly the question this pass's trace-recording
 *   design needed to answer.
 *
 * This is a plain-JVM Android-free test (this file, [GeoMath], and [FareEngine] all are) — it does
 * NOT run the real Python backend, so it is not literal proof `recompute_from_trace` agrees; it is
 * proof the identical, tested port of that same algorithm agrees, which is the strongest check
 * available without a running backend instance.
 */
class TripTraceReplayFidelityTest {

    private val engine = FareEngine()

    // Same mean-Earth-radius constant GeoMath.kt itself uses (see that class's own doc — it
    // mirrors the backend's `_EARTH_RADIUS_KM`) — used here only to construct a synthetic route
    // whose real geometry is known exactly, not to duplicate GeoMath's own distance calculation.
    private val earthRadiusKm = 6371.0088

    /** Moves (lat, lng) exactly [distanceKm] due north. For a pure north-south displacement
     * (Δlng = 0) the haversine great-circle formula reduces exactly to `R * Δφ` (no small-angle
     * approximation involved — `asin(sin(x)) == x` for the tiny angles a real trip covers), so
     * [GeoMath.distanceKm] measuring back the distance between the two points this produces
     * recovers [distanceKm] to floating-point precision, not just approximately. */
    private fun moveNorth(lat: Double, lng: Double, distanceKm: Double): Pair<Double, Double> {
        val deltaLatDeg = Math.toDegrees(distanceKm / earthRadiusKm)
        return (lat + deltaLatDeg) to lng
    }

    private fun deviceDistanceDeltaKm(speedKmh: Double): BigDecimal = BigDecimal.valueOf(speedKmh / 3600.0)

    @Test
    fun `recording one point per tick keeps the server replay within 1 percent of the device total`() {
        // A realistic short fare: wait at the rank, drive, crawl in traffic, drive again.
        val speedProfile: List<Double> =
            List(15) { 0.0 } + // 15s waiting at the rank
                List(40) { 45.0 } + // 40s driving at 45 km/h
                List(20) { 5.0 } + // 20s crawling in traffic (below the 26 km/h threshold)
                List(30) { 60.0 } // 30s driving at 60 km/h

        // --- "device": one tick per second, distance from speed, exactly FareEngineImpl.tick's own formula ---
        val deviceState = FareState(tariff = URBAN_TARIFF)
        for (speed in speedProfile) {
            engine.tick(deviceState, speedKmh = speed, distanceDeltaKm = deviceDistanceDeltaKm(speed), elapsedSeconds = 1)
        }
        val deviceTotal = engine.close(deviceState).grandTotal

        // --- synthetic real GPS trace: one point per second, position advanced by exactly the
        // same speed*1s distance the device arm used, so haversine recovers it exactly ---
        var lat = -33.8688
        var lng = 151.2093
        data class Point(val lat: Double, val lng: Double, val speedKmh: Double)
        val trace = speedProfile.map { speed ->
            val (nextLat, nextLng) = moveNorth(lat, lng, speed / 3600.0)
            lat = nextLat
            lng = nextLng
            Point(lat, lng, speed)
        }

        // --- "server replay": recompute_from_trace's own formula — haversine distance + real
        // elapsed-time gap between consecutive points, mode picked from each point's own speed ---
        val serverState = FareState(tariff = URBAN_TARIFF)
        var prevLat = -33.8688
        var prevLng = 151.2093
        for (point in trace) {
            val distanceDeltaKm = BigDecimal.valueOf(GeoMath.distanceKm(prevLat, prevLng, point.lat, point.lng))
            engine.tick(serverState, speedKmh = point.speedKmh, distanceDeltaKm = distanceDeltaKm, elapsedSeconds = 1)
            prevLat = point.lat
            prevLng = point.lng
        }
        val serverTotal = engine.close(serverState).grandTotal

        val variancePct = (deviceTotal - serverTotal).abs()
            .divide(deviceTotal, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))

        assertTrue(
            "device=$deviceTotal server=$serverTotal variance=$variancePct% (must be <= 1%, " +
                "compute_variance_pct's own tolerance)",
            variancePct <= BigDecimal("1.00"),
        )
    }

    @Test
    fun `coalescing a long wait into one sparse end-of-gap point loses the waiting charge`() {
        // The exact failure mode TracePointRecorder's own doc warns a sparse/thresholded sample
        // would create: 60s genuinely stationary at the rank, then 5s pulling away at 50 km/h,
        // recorded as ONE trace point at the very end of that 65s gap (a plausible "record every
        // ~50m or ~60s" sparse threshold would do exactly this: nothing to record while stationary,
        // then one point once the vehicle has moved far enough).
        val waitSeconds = 60
        val moveSeconds = 5
        val moveSpeedKmh = 50.0

        // --- device (per-second ticks — the real, correct accrual) ---
        val deviceState = FareState(tariff = URBAN_TARIFF)
        repeat(waitSeconds) { engine.tick(deviceState, speedKmh = 0.0, distanceDeltaKm = BigDecimal.ZERO, elapsedSeconds = 1) }
        repeat(moveSeconds) {
            engine.tick(deviceState, speedKmh = moveSpeedKmh, distanceDeltaKm = deviceDistanceDeltaKm(moveSpeedKmh), elapsedSeconds = 1)
        }
        val deviceTotal = engine.close(deviceState).grandTotal

        // --- sparse replay: ONE point for the whole 65s gap, speed = the final (moving) reading,
        // distance = the real total distance covered (all of it during the last 5s) ---
        val totalDistanceKm = moveSeconds * moveSpeedKmh / 3600.0
        val sparseState = FareState(tariff = URBAN_TARIFF)
        engine.tick(
            sparseState,
            speedKmh = moveSpeedKmh, // >= the 26 km/h threshold -> the WHOLE gap reads as "distance" mode
            distanceDeltaKm = BigDecimal.valueOf(totalDistanceKm),
            elapsedSeconds = waitSeconds + moveSeconds,
        )
        val sparseTotal = engine.close(sparseState).grandTotal

        val variancePct = (deviceTotal - sparseTotal).abs()
            .divide(deviceTotal, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))

        // The real 60s of waiting charge is entirely gone from the sparse total (distance-mode
        // ticks charge purely by distance, never by elapsed time) — a large, easily-flagged
        // variance, not a rounding-sized one. This is the counter-example proving a sparse
        // threshold does NOT keep the server replay within its 1% tolerance, which is exactly why
        // this pass records one point per tick instead.
        assertTrue(
            "expected the sparse sample to diverge by MORE than 1% (device=$deviceTotal sparse=$sparseTotal " +
                "variance=$variancePct%) — if this fails, the failure mode this test documents no longer reproduces",
            variancePct > BigDecimal("1.00"),
        )
    }
}
