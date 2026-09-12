package au.com.threesixty.cabdispatch.domain.location.roadpath

import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.location.GeoMath

/**
 * [RoadPathSource] over the driver's own active navigation route -- the highest-confidence source
 * [CompositeRoadPath] tries, because when a route exists it IS the actual road the vehicle is
 * being guided along, at full fidelity: `data/remote/MapboxDirections.kt`'s
 * `geometries=polyline&overview=full` request already decodes the complete, real road geometry
 * (see [decodePolyline][au.com.threesixty.cabdispatch.data.remote.decodePolyline]'s own doc), not
 * a simplified sketch.
 *
 * **Decoupled from `MeterNavViewModel` on purpose** -- same reasoning
 * [au.com.threesixty.cabdispatch.domain.location.inertial.InertialBillingSource]'s own doc gives
 * for why [au.com.threesixty.cabdispatch.domain.FareEngineImpl] depends on an interface rather
 * than a concrete sensor class: this is a plain-JVM-testable domain class, and `MeterNavViewModel`
 * is a screen-scoped `AndroidViewModel`. [activeRoutePoints] is the seam -- production wires
 * `MeterNavViewModel.activeRoutePoints`, a plain property read (see that class's own doc for why a
 * property rather than a `StateFlow` is enough here: [pathAt] only ever reads it once, at the
 * instant a blackout begins, never observes it continuously).
 */
class NavRouteRoadPath(
    /** The active route's own polyline, lat/lng pairs in route order, or `null`/empty when no
     * destination is currently set (no active navigation to constrain against). */
    private val activeRoutePoints: () -> List<Pair<Double, Double>>?,
) : RoadPathSource {

    // ReturnCount: two guard-clause early returns plus the one real result -- same established
    // pattern [CorridorRoadPath.pathAt]'s own `@Suppress("ReturnCount")` comment cites.
    @Suppress("ReturnCount")
    override fun pathAt(entryFix: LocationFix): RoadPath? {
        val points = activeRoutePoints()?.takeIf { it.size >= 2 } ?: return null
        val nearestGapM = points.minOf { (lat, lng) ->
            GeoMath.distanceKm(entryFix.lat, entryFix.lng, lat, lng) * METRES_PER_KM
        }
        if (nearestGapM > ENTRY_MATCH_RADIUS_M) return null
        return PolylinePath(points)
    }

    companion object {
        private const val METRES_PER_KM = 1000.0

        /** Task 3's own "must be within 40 m" -- tighter than [CorridorRoadPath]'s 250 m portal
         * radius, because a route polyline is dense turn-by-turn geometry (the vehicle should sit
         * almost exactly on it while actually following the route), not a sparse gantry chain. */
        const val ENTRY_MATCH_RADIUS_M = 40.0
    }
}
