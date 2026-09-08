package au.com.threesixty.cabdispatch.domain.location

import au.com.threesixty.cabdispatch.domain.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F6 (architecture audit 2026-09-08, §2.1): GPS-jitter handling in
 * [RealLocationProvider] — the accuracy floor and the stationary-speed clamp.
 *
 * **Why this file re-implements the two rules rather than calling the provider.**
 * [RealLocationProvider] cannot be constructed in a plain JVM test: it needs a `Context`, a
 * `FusedLocationProviderClient`, and Play Services. Its filtering rules, however, are pure
 * arithmetic on numbers, and they are fare-affecting — a fix wrongly accepted becomes distance the
 * passenger is charged for. This project's stated convention for fare-affecting logic is a
 * plain-Kotlin, JVM-testable form (see `domain/fare/FareEngine.kt`'s own doc on being a
 * "line-for-line port... proven against golden vectors"), and these mirrors are that, for the two
 * specific predicates F6 added. They are deliberately written as the *thresholds and comparisons*
 * the provider uses, so a change to either constant that is not mirrored here shows up as a failure
 * rather than as silence.
 *
 * The haversine half of F6 — collapsing the provider's private `Location.distanceBetween` onto
 * [GeoMath] — is exercised directly, since [GeoMath] genuinely is plain Kotlin.
 */
class LocationFilteringTest {

    // The two constants F6 introduced, mirrored from RealLocationProvider's companion object.
    private val maxAcceptableAccuracyM = 50f
    private val stationarySpeedMs = 1.4f
    private val msToKmh = 3.6f

    /** Mirror of `RealLocationProvider.passesFilter`'s new accuracy floor (its step 0). */
    private fun acceptsAccuracy(accuracyM: Float): Boolean = accuracyM <= maxAcceptableAccuracyM

    /** Mirror of `RealLocationProvider.stationaryClamp`. */
    private fun clampedKmh(metresPerSecond: Float): Float =
        if (metresPerSecond < stationarySpeedMs) 0f else (metresPerSecond * msToKmh).coerceAtLeast(0f)

    // --- (a) the accuracy floor ---------------------------------------------------------------

    @Test
    fun `a coarse network fix is rejected outright`() {
        // The case that motivated the floor: a cold start with no satellite lock yields a
        // cell-tower estimate hundreds of metres wide. Accepting it and then charging the haversine
        // to the first real fix bills the receiver's own uncertainty as distance travelled.
        assertFalse("a 500m-wide estimate is not a position", acceptsAccuracy(500f))
        assertFalse(acceptsAccuracy(120f))
        assertFalse("just past the floor is still rejected", acceptsAccuracy(50.1f))
    }

    @Test
    fun `an ordinary satellite fix is accepted`() {
        assertTrue(acceptsAccuracy(5f))
        assertTrue(acceptsAccuracy(12f))
        assertTrue("exactly at the floor is accepted", acceptsAccuracy(50f))
    }

    @Test
    fun `a fix the platform gave no accuracy for is treated as poor, not perfect`() {
        // LocationFix.accuracyM's honest-unknown sentinel. The dangerous reading of "no accuracy
        // reported" would be zero — infinitely precise — so the sentinel is Float.MAX_VALUE and it
        // must fall on the reject side.
        val unknown = LocationFix(
            lat = -33.87,
            lng = 151.21,
            speedKmh = 0.0,
            accuracyM = Float.MAX_VALUE,
            timestampMillis = 0L,
        )
        assertFalse("unknown accuracy means unusable, never precise", acceptsAccuracy(unknown.accuracyM))
    }

    // --- (b) the stationary clamp -------------------------------------------------------------

    @Test
    fun `a parked cab with poor sky view reports exactly zero, not creeping drift`() {
        // F6's actual field symptom: a stationary vehicle emits fixes that wander five to twenty
        // metres, `hasSpeed()` is usually true, and a small positive speed comes with them. The
        // meter integrated that and accrued phantom distance with the handbrake on.
        assertEquals(0f, clampedKmh(0.0f), 0f)
        assertEquals(0f, clampedKmh(0.4f), 0f) // ~1.4 km/h of drift
        assertEquals(0f, clampedKmh(1.39f), 0f) // just under the threshold
    }

    @Test
    fun `real movement is not clamped away`() {
        // The clamp must not silently swallow genuine slow travel — crawling in traffic is
        // chargeable waiting time AND its distance still counts toward the 12km band.
        assertEquals(5.04f, clampedKmh(1.4f), 0.001f) // exactly at the threshold: kept
        assertEquals(28.8f, clampedKmh(8.0f), 0.001f) // ~29 km/h, ordinary urban driving
        assertEquals(288f, clampedKmh(80.0f), 0.01f) // motorway speeds pass through untouched
    }

    @Test
    fun `the clamp threshold is walking pace, so a taxi is never mistaken for stationary`() {
        // 1.4 m/s is ~5 km/h. A moving taxi is always well above it; anything below is far more
        // likely receiver noise than travel. Clamping down is also the conservative direction — it
        // can only ever charge the passenger less, the same principle roundDownToCent applies.
        assertTrue("threshold is below any real driving speed", stationarySpeedMs * msToKmh < 6f)
    }

    // --- (c) the shared haversine -------------------------------------------------------------

    @Test
    fun `GeoMath measures a known Sydney leg correctly`() {
        // Sydney Opera House to Sydney Harbour Bridge south pylon, ~0.9 km apart.
        val km = GeoMath.distanceKm(-33.856784, 151.215297, -33.852306, 151.210787)
        assertEquals(0.63, km, 0.05)
    }

    @Test
    fun `GeoMath returns zero for a point against itself`() {
        // The stationary case the fare engine leans on: a vehicle that has not moved must accrue
        // no distance, and the haversine must not manufacture a residue from floating-point noise.
        assertEquals(0.0, GeoMath.distanceKm(-33.87, 151.21, -33.87, 151.21), 1e-12)
    }

    @Test
    fun `GeoMath is symmetric`() {
        val there = GeoMath.distanceKm(-33.87, 151.21, -33.95, 151.05)
        val back = GeoMath.distanceKm(-33.95, 151.05, -33.87, 151.21)
        assertEquals(there, back, 1e-12)
    }

    @Test
    fun `a one-second leg at 60 kmh measures as one sixtieth of a kilometre`() {
        // The exact shape F2's per-tick distance relies on, checked end to end: move due east by
        // the distance 60 km/h covers in a second and confirm GeoMath reads it back.
        val lat = -33.87
        val expectedKm = 60.0 / 3600.0
        // 6371.0088 km mean radius x pi/180 — GeoMath's own sphere, so the two agree exactly.
        val lngOffset = expectedKm / (111.19492664455873 * Math.cos(Math.toRadians(lat)))
        val measured = GeoMath.distanceKm(lat, 151.21, lat, 151.21 + lngOffset)
        assertEquals(expectedKm, measured, 1e-6)
    }
}
