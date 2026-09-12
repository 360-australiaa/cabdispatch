package au.com.threesixty.cabdispatch.domain.location.roadpath

import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.location.GeoMath

/**
 * W3 (road-geometry constraint sources, 2026-09-12 plan). Where W2's `domain/location/inertial/`
 * package (see that package's own `ImuTypes.kt` doc) estimates exactly one scalar -- speed along a
 * road already known to be there -- this package is what answers "which road, and where does it
 * go": a real, ordered polyline the vehicle is assumed to be following through a GPS blackout, from
 * whichever already-cached source can name one.
 *
 * Three sources exist ([CorridorRoadPath], [NavRouteRoadPath], [StyleRoadPath]), highest confidence
 * first, composed by [CompositeRoadPath] into the one seam
 * [au.com.threesixty.cabdispatch.domain.FareEngineImpl] actually depends on -- the same decoupling
 * discipline [au.com.threesixty.cabdispatch.domain.location.inertial.InertialBillingSource]'s own
 * doc describes for why a money-adjacent class depends on an interface, never a concrete sensor (or
 * here, map/registry) class: `FareEngineImpl`'s own test suite constructs small in-memory fakes
 * against [RoadPathSource]/[RoadPath] instead of needing a Room database, a live `MapView`, or a
 * real Mapbox Directions response just to exercise the reconciliation path.
 *
 * **Every implementation must be PURE over already-cached data -- no network call in this path,
 * ever.** A blackout is, by definition, a moment GPS (and often cellular data alongside it, inside
 * a tunnel) is unavailable; a road-path source that reached for the network would simply hang or
 * fail exactly when it is needed, which is worse than returning `null` honestly.
 */
interface RoadPathSource {
    /**
     * The road this vehicle is assumed to be on, based on [entryFix] (the last accepted fix before
     * the blackout began) -- or `null` if this source has nothing to offer for it (no active route,
     * no nearby known corridor, no matching style feature; see each implementation's own doc for
     * its own match radius). Called once, at blackout entry -- never re-queried mid-blackout, since
     * none of these sources learn anything new until a real fix (the exit fix) arrives again.
     */
    fun pathAt(entryFix: LocationFix): RoadPath?
}

/**
 * A real, ordered polyline a [RoadPathSource] has matched the vehicle onto -- see that interface's
 * own doc. Two independent uses, matching the plan's own two mid-blackout needs:
 * - [advance] -- a live, DISPLAY-ONLY position while still inside the blackout (never itself a
 *   billed distance -- exactly the same "display-only" convention
 *   [au.com.threesixty.cabdispatch.domain.location.GeoMath.destination]'s own doc describes for
 *   its own straight-line fallback).
 * - [distanceTo] -- the real, along-road reference distance once the exit fix is known, which
 *   [au.com.threesixty.cabdispatch.domain.location.inertial.BlackoutReconciler] trusts completely
 *   over the tick-by-tick sensor estimate (see that object's own doc: "a road-path match is always
 *   trusted completely over the estimate").
 */
interface RoadPath {
    /**
     * The point [metres] along this path from its own start (the entry fix). Clamped to the path's
     * own length at either end -- never extrapolates past a known road's own geometry. A path with
     * fewer than two points (nothing walkable yet -- see [PolylinePath]'s own doc) simply holds at
     * its one known point regardless of [metres].
     */
    fun advance(metres: Double): Pair<Double, Double>

    /**
     * The along-path distance, in METRES, from this path's own start to wherever [exitFix] best
     * matches it -- or `null` if [exitFix] is not within [PolylinePath.EXIT_MATCH_RADIUS_M] of any
     * point on the path. A `null` here means this source is treated as having NO match at all for
     * this blackout (never a zero/degenerate distance), falling through to the next
     * [RoadPathSource] in [CompositeRoadPath]'s own order, or ultimately the straight-line chord
     * bounds -- exactly [au.com.threesixty.cabdispatch.domain.location.inertial.BlackoutReconciler]'s
     * own `CHORD_BOUNDED` fallback.
     */
    fun distanceTo(exitFix: LocationFix): Double?
}

/**
 * Shared polyline-walking primitive behind every [RoadPathSource]'s [RoadPath] -- [CorridorRoadPath],
 * [NavRouteRoadPath] and [StyleRoadPath] each ultimately resolve to nothing more than "a real,
 * ordered sequence of lat/lng points this vehicle is assumed to be following"; this class is the
 * one place that turns such a sequence into [RoadPath]'s two operations, so distance-along-path and
 * nearest-vertex projection are computed identically regardless of which source produced the
 * polyline -- the same "one function, several callers, never divergent copies" discipline
 * `domain/fare/KnownCorridor.kt`'s own `greedyChainPoints`/`gantryChainPath` split was made for.
 *
 * **Honest simplifications, both display/reconciliation-only, never billing math on their own:**
 * - [advance] linearly interpolates lat/lng between the two bracketing vertices -- a small,
 *   deliberate approximation (a true great-circle interpolation would barely differ at the vertex
 *   spacings this data actually has: toll gantries tens-to-low-hundreds of metres apart, Directions
 *   API route points sampled every few tens of metres) matching this codebase's existing tolerance
 *   for a spherical-Earth approximation ([GeoMath]'s own class doc).
 * - [distanceTo] matches the NEAREST VERTEX to the given fix, not a full point-to-segment
 *   projection -- for the vertex densities the two real polyline producers use, this is within the
 *   same few-metre error band [EXIT_MATCH_RADIUS_M] already tolerates, and avoids a materially more
 *   complex point-to-segment-projection implementation for a reconciliation figure that is, in the
 *   ROAD_PATH case, rounded to cents at a distance rate regardless.
 *
 * A single-point (or empty) [points] list is a valid, honest "not enough geometry yet" state (e.g.
 * [CorridorRoadPath]'s own [RoadPath] before its real match resolves -- see that class's own doc):
 * [advance] holds at the one known point (or the origin, for an empty list — defensive only, no
 * real producer ever constructs one), and [distanceTo] can never match anything against it (nothing
 * to project onto), so it honestly returns `null` rather than a fabricated zero.
 */
internal class PolylinePath(private val points: List<Pair<Double, Double>>) : RoadPath {

    /** `cumulativeM[i]` = the along-path distance, metres, from `points[0]` to `points[i]`. */
    private val cumulativeM: DoubleArray = DoubleArray(points.size).also { cumulative ->
        for (i in 1 until points.size) {
            val (lat1, lng1) = points[i - 1]
            val (lat2, lng2) = points[i]
            cumulative[i] = cumulative[i - 1] + GeoMath.distanceKm(lat1, lng1, lat2, lng2) * METRES_PER_KM
        }
    }

    /** Total path length, metres -- `0.0` for a single-point (or empty) polyline. */
    val totalLengthM: Double get() = cumulativeM.lastOrNull() ?: 0.0

    // ReturnCount: three guard-clause early returns (each an honest degenerate/boundary case) plus
    // the one real interpolated result -- same established pattern
    // [au.com.threesixty.cabdispatch.domain.location.roadpath.CorridorRoadPath.pathAt]'s own
    // `@Suppress("ReturnCount")` comment cites.
    @Suppress("ReturnCount")
    override fun advance(metres: Double): Pair<Double, Double> {
        if (points.isEmpty()) return ORIGIN
        if (points.size == 1) return points[0]
        val target = metres.coerceIn(0.0, totalLengthM)
        // target == 0.0 sits exactly at points[0] itself -- handled directly rather than folded
        // into the search below, since `indexOfFirst { it >= 0.0 }` correctly returns index 0
        // (cumulativeM[0] is always 0.0), which is NOT the "not found" sentinel (-1) and must not
        // be treated as one -- doing so here (an earlier version of this code did, via a `<= 0`
        // guard) silently walked into the LAST segment instead, extrapolating from the wrong end
        // of the path entirely.
        if (target <= 0.0) return points.first()
        // First vertex whose cumulative distance reaches the target -- the segment this distance
        // falls inside runs from the vertex just before it to this one. Guaranteed >= 1 here
        // (target is strictly positive and cumulativeM[0] == 0.0 can never satisfy `>= target`).
        // `indexOfFirst` returning -1 only happens if target exceeds the path's own total length,
        // already ruled out by the coerceIn above -- the `< 0` guard is defensive, not an expected
        // path.
        val segmentEndIndex = cumulativeM.indexOfFirst { it >= target }.let { found ->
            if (found < 0) points.size - 1 else found
        }
        val segmentStartIndex = segmentEndIndex - 1
        val segmentLengthM = cumulativeM[segmentEndIndex] - cumulativeM[segmentStartIndex]
        val fraction = if (segmentLengthM > 0.0) {
            (target - cumulativeM[segmentStartIndex]) / segmentLengthM
        } else {
            0.0
        }
        val (lat1, lng1) = points[segmentStartIndex]
        val (lat2, lng2) = points[segmentEndIndex]
        return (lat1 + (lat2 - lat1) * fraction) to (lng1 + (lng2 - lng1) * fraction)
    }

    // ReturnCount: two guard-clause early returns plus the one real result -- same established
    // pattern [advance]'s own `@Suppress("ReturnCount")` comment cites.
    @Suppress("ReturnCount")
    override fun distanceTo(exitFix: LocationFix): Double? {
        if (points.isEmpty()) return null
        var bestIndex = 0
        var bestGapM = Double.MAX_VALUE
        for (index in points.indices) {
            val (lat, lng) = points[index]
            val gapM = GeoMath.distanceKm(lat, lng, exitFix.lat, exitFix.lng) * METRES_PER_KM
            if (gapM < bestGapM) {
                bestGapM = gapM
                bestIndex = index
            }
        }
        if (bestGapM > EXIT_MATCH_RADIUS_M) return null
        return cumulativeM[bestIndex]
    }

    companion object {
        private const val METRES_PER_KM = 1000.0
        private val ORIGIN = 0.0 to 0.0

        /** The contract's own "within 60m of the path" exit-match radius -- see [RoadPath
         * .distanceTo]'s own doc. One constant so every [RoadPathSource] implementation applies
         * exactly the same tolerance, never a source-specific variant that could quietly make one
         * source easier to (mis)match than another. */
        const val EXIT_MATCH_RADIUS_M = 60.0
    }
}
