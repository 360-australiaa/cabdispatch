package au.com.threesixty.cabdispatch.domain.location.roadpath

import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.fare.tollBearingDegrees
import au.com.threesixty.cabdispatch.domain.location.GeoMath

/**
 * One road `LineString` read off the meter's Mapbox style's road source-layer -- see
 * `ui/screens/hired/MeterBackdropMap.kt`'s `queryRoadSourceFeatures` for how these are produced.
 * [name]/[roadClass] mirror the vector tile's own `name`/`class` feature properties (the shape
 * Mapbox Streets v8's `road` layer uses) -- both nullable because a real road feature can
 * genuinely lack either (an unnamed access road; a `class` value this style's tile schema simply
 * does not set for some feature types), never fabricated.
 */
data class StyleRoadFeature(
    val name: String?,
    val roadClass: String?,
    /** Lat/lng pairs, in the feature's own vertex order -- NOT necessarily "the direction of
     * travel"; a road's digitised direction in a vector tile is arbitrary, which is exactly why
     * [StyleRoadPath]'s own bearing check treats a feature and its reverse as equally valid. */
    val points: List<Pair<Double, Double>>,
)

/**
 * Task 4's spike, W3 (2026-09-12 plan) -- offline map road geometry as a [RoadPathSource], for
 * tunnels and structures that are neither a mapped toll corridor nor on the driver's active nav
 * route. [CompositeRoadPath] tries this LAST: it is the least certain of the three sources (see
 * below).
 *
 * **Spike result -- UNVERIFIED ON DEVICE. Read this before relying on this class for anything.**
 * This workstream has no tablet/emulator access (an explicit operating constraint) and no live
 * Mapbox credential in this environment to fetch this app's custom Studio style
 * (`mapbox://styles/benfarid/cmtbnyhe4000e01pcgx2t51za`,
 * `ui/screens/hired/MeterBackdropMap.kt`'s `BACKDROP_MAP_STYLE_URI`) and inspect its own layer
 * list. What IS confirmed from static inspection of this repo:
 * `data/remote/MapboxOfflineRegion.kt`'s `TilesetDescriptorOptions.Builder()
 * .styleURI(BACKDROP_MAP_STYLE_URI)` builds the offline tileset descriptor DIRECTLY from that same
 * custom style, so whatever source layers the style itself declares are exactly what an offline
 * region download caches -- there is no separate, independently-configured "which layers to cache
 * offline" step that could disagree with the style. What is NOT confirmed: whether that custom
 * style's own layer list actually includes a queryable road source-layer at all (e.g. built on
 * `mapbox-streets-v8` with its `road` layer, per this task's own suggestion) -- a Mapbox Studio
 * custom style can just as easily omit road geometry entirely if it was authored purely for this
 * app's own dark background/POI look, and nothing in this repository settles that question.
 *
 * **Per the plan's own "stop and report" instruction for this exact spike:** if a human confirms
 * (Mapbox Studio's own style editor, or a live `querySourceFeatures` call run on the tablet) that
 * [DEFAULT_SOURCE_ID]/[DEFAULT_SOURCE_LAYER_ID] below do not match this style's real layer list,
 * the fix is an OWNER action in Mapbox Studio -- add the standard `mapbox-streets-v8` `road` layer
 * to the style (or correct the ids to whatever this style actually calls its own road layer) --
 * NOT a code change here. Until then, this class is written to the plan's own spec but degrades
 * safely: an absent/misnamed source-layer simply means [featureProvider] always returns an empty
 * list (see `MeterBackdropMap.kt`'s `queryRoadSourceFeatures` doc for why a query against a
 * source-layer the loaded style does not have answers empty rather than erroring), so [pathAt]
 * returns `null` and [CompositeRoadPath] falls through to the next source or the chord bounds --
 * it never crashes, never fabricates a road.
 *
 * **Why this is a cached-snapshot function, not a live `MapView` call.** Every other
 * [RoadPathSource] is pure over already-cached data with no Android/Mapbox dependency at all --
 * [RoadPathSource]'s own contract requires it. [RoadPathSource.pathAt] must stay synchronous and
 * callable from [au.com.threesixty.cabdispatch.domain.FareEngineImpl] (a plain-JVM-testable class
 * with no `MapView` reference of its own), but the real Mapbox SDK's `querySourceFeatures` call is
 * callback-based (see `MeterBackdropMap.kt`'s own doc). [featureProvider] is the seam: the UI
 * layer that DOES hold a live `MapView` is expected to call `queryRoadSourceFeatures` when a
 * blackout begins and publish its result somewhere this lambda can read synchronously -- exactly
 * how [CorridorRoadPath]'s own `registryProvider` reads an already-loaded
 * [au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot] rather than querying Room
 * itself. Wiring that publish step end-to-end (which composable/controller calls
 * `queryRoadSourceFeatures` and where the cache lives) is unverified/unimplemented by this
 * workstream, for the same reason: it cannot be exercised without a device. `AppContainer.kt`
 * wires this class with a provider that always returns an empty list (see that file's own doc) --
 * a safe, honest default until that gap is closed.
 */
class StyleRoadPath(
    /** Already-queried road features near the last blackout entry, or an empty list if none have
     * ever been queried -- see this class's own doc for why this is a cache read, never a live
     * query, from [pathAt] itself. */
    private val featureProvider: () -> List<StyleRoadFeature>,
) : RoadPathSource {

    // ReturnCount: three guard-clause early returns (each answering "is there enough here to even
    // attempt a match") plus the one real result -- same established pattern
    // [au.com.threesixty.cabdispatch.domain.location.roadpath.CorridorRoadPath.pathAt]'s own
    // `@Suppress("ReturnCount")` comment cites.
    @Suppress("ReturnCount")
    override fun pathAt(entryFix: LocationFix): RoadPath? {
        val features = featureProvider()
        if (features.isEmpty()) return null

        val nearby = features.filter { feature ->
            feature.points.any { (lat, lng) ->
                GeoMath.distanceKm(entryFix.lat, entryFix.lng, lat, lng) * METRES_PER_KM <= ENTRY_MATCH_RADIUS_M
            }
        }
        if (nearby.isEmpty()) return null

        val bearing = entryFix.heading
        val start = nearby
            .filter { bearing == null || matchesBearing(it, bearing) }
            .minByOrNull { feature ->
                feature.points.minOf { (lat, lng) -> GeoMath.distanceKm(entryFix.lat, entryFix.lng, lat, lng) }
            }
            ?: return null

        val walked = followConnectedSegments(start, nearby, bearing)
        val points = listOf(entryFix.lat to entryFix.lng) + walked
        return PolylinePath(points)
    }

    /**
     * Task 4's "follow connected segments (matching name/class, bearing within 20 deg) up to
     * 12 km" -- starting from [start], greedily append whichever unused candidate shares its
     * [StyleRoadFeature.name]/[StyleRoadFeature.roadClass], has an endpoint within
     * [SEGMENT_CONNECT_RADIUS_M] of the chain's own current end, and (when [bearing] is known)
     * runs within [BEARING_MATCH_DEG] of it -- stopping at [MAX_FOLLOW_DISTANCE_M] or once nothing
     * more connects.
     */
    private fun followConnectedSegments(
        start: StyleRoadFeature,
        candidates: List<StyleRoadFeature>,
        bearing: Double?,
    ): List<Pair<Double, Double>> {
        val remaining = candidates.filter { it !== start }.toMutableList()
        val chain = mutableListOf<Pair<Double, Double>>().apply { addAll(start.points) }
        var totalM = pathLengthM(start.points)
        // LoopWithTooManyJumpStatements: the "not connected closely enough" rejection is folded
        // into the same filter chain as the name/class/bearing gates (rather than a second,
        // separate `if (...) break` after `minByOrNull`) specifically so this loop has exactly ONE
        // jump statement to reason about -- a candidate either clears every gate and connects, or
        // it is filtered out before `minByOrNull` ever sees it, same effect either way.
        while (totalM < MAX_FOLLOW_DISTANCE_M) {
            val currentEnd = chain.last()
            val next = remaining
                .filter { candidate -> candidate.name == start.name && candidate.roadClass == start.roadClass }
                .filter { candidate -> bearing == null || matchesBearing(candidate, bearing) }
                .filter { candidate -> nearestEndpointGapM(currentEnd, candidate) <= SEGMENT_CONNECT_RADIUS_M }
                .minByOrNull { candidate -> nearestEndpointGapM(currentEnd, candidate) }
                ?: break

            val gapToFirstM = GeoMath.distanceKm(
                currentEnd.first, currentEnd.second, next.points.first().first, next.points.first().second,
            ) * METRES_PER_KM
            val gapToLastM = GeoMath.distanceKm(
                currentEnd.first, currentEnd.second, next.points.last().first, next.points.last().second,
            ) * METRES_PER_KM
            val orderedPoints = if (gapToFirstM <= gapToLastM) next.points else next.points.reversed()

            chain.addAll(orderedPoints)
            totalM += pathLengthM(orderedPoints)
            remaining.remove(next)
        }
        return chain
    }

    private fun nearestEndpointGapM(from: Pair<Double, Double>, candidate: StyleRoadFeature): Double {
        val first = candidate.points.first()
        val last = candidate.points.last()
        return minOf(
            GeoMath.distanceKm(from.first, from.second, first.first, first.second) * METRES_PER_KM,
            GeoMath.distanceKm(from.first, from.second, last.first, last.second) * METRES_PER_KM,
        )
    }

    /** A feature's own end-to-end bearing is within [BEARING_MATCH_DEG] of [lastBearingDeg] in
     * EITHER travel direction -- a vector-tile `LineString`'s digitised direction is arbitrary
     * (see [StyleRoadFeature.points]'s own doc), so a road running exactly opposite the vehicle's
     * last known heading is just as valid a match as one running exactly along it. A feature with
     * fewer than two points has no bearing to measure; treated as a pass, not a rejection -- an
     * absent bearing is not evidence of a mismatch. */
    private fun matchesBearing(feature: StyleRoadFeature, lastBearingDeg: Double): Boolean {
        if (feature.points.size < 2) return true
        val (lat1, lng1) = feature.points.first()
        val (lat2, lng2) = feature.points.last()
        val featureBearing = tollBearingDegrees(lat1, lng1, lat2, lng2)
        val delta = angularDifferenceDeg(featureBearing, lastBearingDeg)
        return delta <= BEARING_MATCH_DEG || delta >= (HALF_TURN_DEG - BEARING_MATCH_DEG)
    }

    private fun pathLengthM(points: List<Pair<Double, Double>>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += GeoMath.distanceKm(
                points[i].first, points[i].second, points[i + 1].first, points[i + 1].second,
            ) * METRES_PER_KM
        }
        return total
    }

    private fun angularDifferenceDeg(a: Double, b: Double): Double {
        val diff = kotlin.math.abs(a - b) % FULL_TURN_DEG
        return if (diff > HALF_TURN_DEG) FULL_TURN_DEG - diff else diff
    }

    companion object {
        private const val METRES_PER_KM = 1000.0
        private const val FULL_TURN_DEG = 360.0
        private const val HALF_TURN_DEG = 180.0

        /** Task 4's own "within 300 m of the entry fix". */
        const val ENTRY_MATCH_RADIUS_M = 300.0

        /** How close two candidate segments' endpoints must be to count as "connected" -- tighter
         * than [ENTRY_MATCH_RADIUS_M] since two genuinely connected road segments in a vector tile
         * should share (near enough) the same vertex, not merely be in the same neighbourhood. */
        const val SEGMENT_CONNECT_RADIUS_M = 30.0

        /** Task 4's own "bearing within 20 deg". */
        const val BEARING_MATCH_DEG = 20.0

        /** Task 4's own "follow connected segments ... up to 12 km". */
        const val MAX_FOLLOW_DISTANCE_M = 12_000.0

        /** Real Mapbox Streets v8 identifiers -- the ones `queryRoadSourceFeatures` defaults to.
         * See this class's own doc for why they are UNVERIFIED against this project's actual
         * custom style. */
        const val DEFAULT_SOURCE_ID = "composite"
        const val DEFAULT_SOURCE_LAYER_ID = "road"
    }
}
