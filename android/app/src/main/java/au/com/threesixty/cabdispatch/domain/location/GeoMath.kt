package au.com.threesixty.cabdispatch.domain.location

import kotlin.math.asin
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
}
