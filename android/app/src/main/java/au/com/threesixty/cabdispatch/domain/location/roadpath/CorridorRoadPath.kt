package au.com.threesixty.cabdispatch.domain.location.roadpath

import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.fare.CORRIDOR_PORTAL_MATCH_RADIUS_M
import au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot
import au.com.threesixty.cabdispatch.domain.fare.gantryChainPath
import au.com.threesixty.cabdispatch.domain.fare.tollHaversineM

/**
 * [RoadPathSource] over the existing toll-registry gantry chain ([gantryChainPath]) -- real,
 * surveyed toll-road geometry, not an inference, which is why [CompositeRoadPath] tries this
 * ahead of [StyleRoadPath] (though behind [NavRouteRoadPath]'s own even-more-specific "the actual
 * road the driver is being guided along right now").
 *
 * **Why [pathAt] and [RoadPath.distanceTo] resolve at genuinely different times.**
 * [gantryChainPath] -- like the
 * [au.com.threesixty.cabdispatch.domain.FareEngineImpl.lookupKnownCorridorKm] lookup it now
 * shares its own walk with -- needs BOTH the entry and the exit fix to decide which of possibly
 * several nearby roads a blackout actually crossed (a single fix cannot disambiguate a road's two
 * portals, or two different roads that both happen to pass near the same point). But
 * [RoadPathSource.pathAt] is only ever called with the ENTRY fix -- the exit fix does not exist
 * yet, the vehicle is still inside the blackout. So the [RoadPath] this class returns defers the
 * real, two-ended match to [RoadPath.distanceTo] (called once reacquisition actually supplies an
 * exit fix); [RoadPath.advance] -- meant for a live, mid-blackout DISPLAY position, which this
 * workstream does not itself wire anywhere (see `domain/location/inertial/InertialSpeedSource.kt`,
 * which this workstream does not touch) -- honestly holds at the entry fix until that real match
 * resolves, rather than guess which of possibly several roads near the entry portal the vehicle is
 * actually on.
 */
class CorridorRoadPath(
    /**
     * [TollRegistrySnapshot] provider -- production wires
     * `au.com.threesixty.cabdispatch.sync.TollRegistryCache.snapshot`, the exact same
     * already-loaded registry [au.com.threesixty.cabdispatch.domain.fare.onFix] and
     * [gantryChainPath]'s other callers already read; a `null` snapshot (registry never
     * loaded/refreshed yet) is treated exactly like an empty one -- no match, never a crash.
     */
    private val registryProvider: () -> TollRegistrySnapshot?,
) : RoadPathSource {

    // ReturnCount: three guard-clause early returns (each answering "is there enough here to even
    // attempt a match") plus the one real result -- matching this codebase's own established
    // pattern for this exact shape (see `domain/FareEngine.kt$resolveBlackout`'s own
    // `@Suppress("ReturnCount")` doc) rather than a nested-if pyramid that would trade this finding
    // for NestedBlockDepth instead.
    @Suppress("ReturnCount")
    override fun pathAt(entryFix: LocationFix): RoadPath? {
        val registry = registryProvider() ?: return null
        val nearestGapM = registry.gantries.minOfOrNull { gantry ->
            tollHaversineM(entryFix.lat, entryFix.lng, gantry.latitude, gantry.longitude)
        } ?: return null
        if (nearestGapM > CORRIDOR_PORTAL_MATCH_RADIUS_M) return null
        return CorridorRoadPathHandle(registry, entryFix)
    }
}

/** See [CorridorRoadPath]'s own doc for why [distanceTo] -- not [advance] -- is where the real,
 * two-ended [gantryChainPath] match happens. */
private class CorridorRoadPathHandle(
    private val registry: TollRegistrySnapshot,
    private val entryFix: LocationFix,
) : RoadPath {

    /** Set the first time [distanceTo] resolves a real match -- see [advance]'s own doc. */
    private var resolved: PolylinePath? = null

    override fun distanceTo(exitFix: LocationFix): Double? {
        val points = gantryChainPath(
            registry,
            entryLat = entryFix.lat,
            entryLng = entryFix.lng,
            exitLat = exitFix.lat,
            exitLng = exitFix.lng,
        ) ?: return null
        val path = PolylinePath(points)
        resolved = path
        return path.totalLengthM
    }

    /** Holds at the entry fix until [distanceTo] has resolved the real polyline -- see
     * [CorridorRoadPath]'s own class doc. Once resolved, walks the SAME points [distanceTo]
     * matched the exit fix against, never a second, independently-derived path. */
    override fun advance(metres: Double): Pair<Double, Double> =
        (resolved ?: PolylinePath(listOf(entryFix.lat to entryFix.lng))).advance(metres)
}
