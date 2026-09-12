package au.com.threesixty.cabdispatch.domain.location

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tiny great-circle distance helper, deliberately a byte-for-byte mirror of the backend's own
 * haversine (`backend/app/services/trips.py::haversine_km` / `EARTH_RADIUS_KM`, and the metre-
 * scale twin `backend/app/services/geofence.py::haversine_m` / `_EARTH_RADIUS_M` used for toll
 * geofence containment) rather than a fresh implementation — same formula, same Earth-radius
 * constant, so a distance computed here and the equivalent backend computation never drift for
 * an unrelated reason (e.g. a different mean-radius approximation).
 *
 * Deliberately NOT [android.location.Location.distanceBetween] (which
 * `domain/location/RealLocationProvider.kt` uses internally for its own jump-filtering, and would
 * be marginally more accurate — WGS84 ellipsoid vs. this sphere approximation): that's a real
 * Android framework method, which means anything calling it can only run on-device or under
 * Robolectric, never a plain JVM unit test. [RegionResolver] (the one real consumer of this today)
 * is money-adjacent (it picks the tariff *region*), and this codebase's convention for
 * money-adjacent logic is a plain-Kotlin, JVM-testable port (see `domain/fare/FareEngine.kt`'s own
 * doc on why it's a "line-for-line port... proven against golden vectors") — keeping this file
 * Android-free preserves that option for whoever adds a `RegionResolverTest` later.
 */
object GeoMath {

    /** Mean Earth radius, kilometres — same constant backend's `trips.py::_EARTH_RADIUS_KM` uses. */
    private const val EARTH_RADIUS_KM = 6371.0088

    /** [destination]'s own longitude-normalisation constants — see that function's comment. */
    private const val FULL_TURN_DEG = 360.0
    private const val HALF_TURN_DEG = 180.0
    private const val HALF_TURNS_DEG = HALF_TURN_DEG * 3 // 540 -- three half-turns, always positive

    /** Great-circle distance between two WGS84 lat/lng points, in kilometres. */
    fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lng2 - lng1)
        val a = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
        val c = 2 * asin(sqrt(a))
        return EARTH_RADIUS_KM * c
    }

    /**
     * The point [distanceKm] along great-circle bearing [bearingDeg] (0 = north, clockwise) from
     * (`lat`, `lng`) — the forward geodesic problem, [distanceKm] (above)'s inverse. Added for
     * `domain/location/inertial/InertialSpeedSource.kt`'s DISPLAY-ONLY synthetic position while a
     * GPS blackout is bridged by dead reckoning (never used to compute a billed distance — see
     * that file's own doc for why the fare engine's billed km never comes from this function).
     * Standard spherical-Earth destination formula, same [EARTH_RADIUS_KM] as [distanceKm] so the
     * two are mutually consistent (a round trip through both returns arbitrarily close to the
     * original point, modulo the sphere-vs-ellipsoid approximation this whole object already
     * accepts — see the class doc).
     */
    fun destination(lat: Double, lng: Double, bearingDeg: Double, distanceKm: Double): Pair<Double, Double> {
        val angularDistance = distanceKm / EARTH_RADIUS_KM
        val bearingRad = Math.toRadians(bearingDeg)
        val phi1 = Math.toRadians(lat)
        val lambda1 = Math.toRadians(lng)

        val phi2 = asin(
            sin(phi1) * cos(angularDistance) + cos(phi1) * sin(angularDistance) * cos(bearingRad),
        )
        val lambda2 = lambda1 + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(phi1),
            cos(angularDistance) - sin(phi1) * sin(phi2),
        )
        // Normalises longitude to (-180, 180] -- the standard "+540 mod 360 -180" trick (adding a
        // full 360 + 180 before the mod keeps the operand positive regardless of how far lambda2
        // wrapped, then the mod-360 and -180 fold it back into the conventional signed range).
        val normalisedLng = (Math.toDegrees(lambda2) + HALF_TURNS_DEG).mod(FULL_TURN_DEG) - HALF_TURN_DEG
        return Math.toDegrees(phi2) to normalisedLng
    }
}
