package au.com.threesixty.cabdispatch.domain.location.tunnel

import au.com.threesixty.cabdispatch.domain.location.GeoMath
import kotlin.math.abs

/**
 * One direction of one Sydney road tunnel as a polyline (entry portal first) -- owner
 * instruction, 2026-09-15, after T5453's M4 East run: "when we enter the tunnel road, and you
 * know that GPS will lost out, we have to lock to that tunnel ... don't just forget the route."
 * Straight-line dead reckoning drifted 50 degrees off the M4 East, missed every gantry, and
 * charged no toll. With the position LOCKED to the tunnel geometry the track follows the road,
 * the gantries inside it are crossed, and the billed distance has a real road reference.
 *
 * Geometry: Transport for NSW's own tunnel average-speed enforcement paths (22 corridors: M4
 * East, M5 East, Cross City, Eastern Distributor, Lane Cove, Harbour Tunnel, NorthConnex, M8,
 * M4-M8 Link, Rozelle Interchange), bundled as `assets/nsw_tunnels.json`. Pure Kotlin, no
 * Android dependency, so every rule here is unit-tested on the JVM.
 */
class TunnelCorridor(
    val id: String,
    val name: String,
    /** The toll-registry road this corridor belongs to (e.g. "LCT"), or null when unknown. */
    val roadId: String?,
    val points: List<Pair<Double, Double>>,
) {
    init {
        require(points.size >= 2) { "a corridor needs at least two points" }
    }

    /** Cumulative distance from the entry portal at each vertex, km. */
    val cumulativeKm: DoubleArray = DoubleArray(points.size).also { acc ->
        for (i in 1 until points.size) {
            val (aLat, aLng) = points[i - 1]
            val (bLat, bLng) = points[i]
            acc[i] = acc[i - 1] + GeoMath.distanceKm(aLat, aLng, bLat, bLng)
        }
    }

    val lengthKm: Double get() = cumulativeKm.last()

    /** Nearest vertex to ([lat], [lng]): its index, how far away it is (m) and how far along the
     * corridor it sits (km). Vertices are 20-100 m apart, so vertex-nearest is within a few tens
     * of metres of the true perpendicular projection -- plenty for a portal match. */
    fun nearestVertex(lat: Double, lng: Double): Projection {
        var bestIndex = 0
        var bestM = Double.MAX_VALUE
        points.forEachIndexed { i, (pLat, pLng) ->
            val m = GeoMath.distanceKm(lat, lng, pLat, pLng) * METRES_PER_KM
            if (m < bestM) { bestM = m; bestIndex = i }
        }
        return Projection(index = bestIndex, distanceM = bestM, alongKm = cumulativeKm[bestIndex])
    }

    /** Position [alongKm] from the entry portal, interpolated between vertices; clamped to the
     * corridor (a vehicle that "runs off the end" without GPS holds at the exit portal). */
    fun pointAt(alongKm: Double): Pair<Double, Double> {
        val target = alongKm.coerceIn(0.0, lengthKm)
        val i = segmentIndexAt(target)
        val segStart = cumulativeKm[i]
        val segLen = cumulativeKm[i + 1] - segStart
        val f = if (segLen <= 0.0) 0.0 else ((target - segStart) / segLen).coerceIn(0.0, 1.0)
        val (aLat, aLng) = points[i]
        val (bLat, bLng) = points[i + 1]
        return (aLat + (bLat - aLat) * f) to (aLng + (bLng - aLng) * f)
    }

    /** Bearing of the corridor at [alongKm], degrees true. */
    fun bearingAt(alongKm: Double): Double {
        val i = segmentIndexAt(alongKm.coerceIn(0.0, lengthKm))
        val (aLat, aLng) = points[i]
        val (bLat, bLng) = points[i + 1]
        return GeoMath.bearingDeg(aLat, aLng, bLat, bLng)
    }

    /** The vertices strictly between [fromKm] and [toKm], for a toll sweep along the road. */
    fun pathBetween(fromKm: Double, toKm: Double): List<Pair<Double, Double>> {
        val lo = minOf(fromKm, toKm)
        val hi = maxOf(fromKm, toKm)
        return points.indices
            .filter { cumulativeKm[it] in lo..hi }
            .map { points[it] }
    }

    private fun segmentIndexAt(alongKm: Double): Int {
        var i = 0
        while (i < cumulativeKm.size - 2 && cumulativeKm[i + 1] < alongKm) i++
        return i
    }

    data class Projection(val index: Int, val distanceM: Double, val alongKm: Double)

    companion object {
        const val METRES_PER_KM = 1000.0
    }
}

/**
 * A blackout in progress, locked to [corridor] from [entryAlongKm]. The caller feeds it the
 * distance the vehicle has travelled since GPS dropped (the same sensor-integrated figure the
 * meter bills); the lock answers where on the road that puts the vehicle.
 */
class TunnelLock(val corridor: TunnelCorridor, val entryAlongKm: Double) {

    fun alongKm(traveledKm: Double): Double =
        (entryAlongKm + traveledKm.coerceAtLeast(0.0)).coerceAtMost(corridor.lengthKm)

    fun positionAt(traveledKm: Double): Pair<Double, Double> = corridor.pointAt(alongKm(traveledKm))

    fun headingAt(traveledKm: Double): Double = corridor.bearingAt(alongKm(traveledKm))

    /** True once the integrated distance has reached the exit portal -- the display holds there. */
    fun atExit(traveledKm: Double): Boolean = alongKm(traveledKm) >= corridor.lengthKm

    /** The road vertices from the entry point to [traveledKm] along -- the blackout path. */
    fun pathTraveled(traveledKm: Double): List<Pair<Double, Double>> {
        val end = alongKm(traveledKm)
        return listOf(corridor.pointAt(entryAlongKm)) +
            corridor.pathBetween(entryAlongKm, end) +
            corridor.pointAt(end)
    }

    /**
     * Road distance from the entry point to the reacquisition fix, km -- the billing reference
     * that replaces the entry->exit chord. Null when the fix is not on this corridor (more than
     * [EXIT_MATCH_RADIUS_M] from its nearest vertex): the vehicle left the tunnel some other
     * way, and the chord bound is the honest fallback. A fix past the exit portal projects to
     * the portal, plus the straight run from the portal to the fix.
     */
    fun roadKmTo(exitLat: Double, exitLng: Double): Double? {
        val p = corridor.nearestVertex(exitLat, exitLng)
        val portalLat = corridor.points.last().first
        val portalLng = corridor.points.last().second
        val beyondPortalKm = GeoMath.distanceKm(portalLat, portalLng, exitLat, exitLng)
        return when {
            p.distanceM <= EXIT_MATCH_RADIUS_M && p.alongKm >= entryAlongKm -> p.alongKm - entryAlongKm
            beyondPortalKm * TunnelCorridor.METRES_PER_KM <= BEYOND_PORTAL_MATCH_M ->
                (corridor.lengthKm - entryAlongKm) + beyondPortalKm
            else -> null
        }
    }

    companion object {
        const val EXIT_MATCH_RADIUS_M = 300.0
        const val BEYOND_PORTAL_MATCH_M = 1500.0
    }
}

/** The bundled corridors and the one rule for locking onto one. */
class TunnelRegistry(val corridors: List<TunnelCorridor>) {

    /**
     * The corridor to lock to when GPS drops at ([lat], [lng]) heading [headingDeg]: the nearest
     * corridor vertex within [ENTRY_RADIUS_M] whose local bearing is within
     * [BEARING_TOLERANCE_DEG] of the vehicle's heading (a westbound car never locks to the
     * eastbound bore), and not within [MIN_REMAINING_KM] of that corridor's exit portal (a
     * blackout that starts at the far end is the car LEAVING the tunnel, not entering it). Null
     * heading -> no lock: direction is what tells the two bores apart.
     */
    fun match(lat: Double, lng: Double, headingDeg: Double?): TunnelLock? {
        val best = if (headingDeg == null) {
            null
        } else {
            corridors
                .mapNotNull { corridor -> candidateLock(corridor, lat, lng, headingDeg) }
                .minByOrNull { it.second }
                ?.first
        }
        // Lock to the whole chain the entry bore leads into (Anzac Bridge -> Rozelle Interchange
        // westbound -> M4 East, 2026-09-15), so a blackout that spans two connected tunnels keeps
        // following the road instead of holding at the first bore's exit portal.
        return best?.let { TunnelLock(chainFrom(it.corridor), it.entryAlongKm) }
    }

    /**
     * [start] followed by every corridor that continues it: repeatedly the corridor whose first
     * vertex lies within [CHAIN_GAP_M] of the current last vertex AND whose initial bearing is
     * within [BEARING_TOLERANCE_DEG] of the current final bearing (the opposite bore also starts
     * at our exit portal, but points the other way, so it never chains). The gap between two
     * enforcement paths is bridged by a straight leg. Returns [start] itself when nothing
     * continues it. Capped at [MAX_CHAIN_HOPS] hops.
     */
    fun chainFrom(start: TunnelCorridor): TunnelCorridor {
        val used = mutableListOf(start)
        var points = start.points
        var current = start
        repeat(MAX_CHAIN_HOPS) {
            val (lastLat, lastLng) = current.points.last()
            val endBearing = current.bearingAt(current.lengthKm)
            val next = corridors
                .filter { it !in used }
                .mapNotNull { c ->
                    val (fLat, fLng) = c.points.first()
                    val gapM = GeoMath.distanceKm(lastLat, lastLng, fLat, fLng) * TunnelCorridor.METRES_PER_KM
                    // "Continues" = the next bore heads the way we are going, judged against
                    // BOTH our final leg and the straight gap leg to it -- interchange tunnels
                    // bend right at the join (Rozelle westbound ends on 297, the M4 East begins
                    // on 246), so either reference may be the fair one.
                    val gapBearing = GeoMath.bearingDeg(lastLat, lastLng, fLat, fLng)
                    val startBearing = c.bearingAt(0.0)
                    val diff = minOf(bearingDiff(startBearing, endBearing), bearingDiff(startBearing, gapBearing))
                    if (gapM <= CHAIN_GAP_M && diff <= CHAIN_BEARING_TOLERANCE_DEG) c to gapM else null
                }
                .minByOrNull { it.second }
                ?.first ?: return@repeat
            used += next
            points = points + next.points
            current = next
        }
        if (used.size == 1) return start
        return TunnelCorridor(
            id = used.joinToString("+") { it.id },
            name = start.name,
            roadId = start.roadId,
            points = points,
        )
    }

    /** ([TunnelLock], distance from the vehicle in metres) if [corridor] is lockable from here. */
    private fun candidateLock(
        corridor: TunnelCorridor,
        lat: Double,
        lng: Double,
        headingDeg: Double,
    ): Pair<TunnelLock, Double>? {
        val p = corridor.nearestVertex(lat, lng)
        val tooFar = p.distanceM > ENTRY_RADIUS_M
        val leaving = corridor.lengthKm - p.alongKm < MIN_REMAINING_KM
        val wrongWay = bearingDiff(corridor.bearingAt(p.alongKm), headingDeg) > BEARING_TOLERANCE_DEG
        return if (tooFar || leaving || wrongWay) null else TunnelLock(corridor, p.alongKm) to p.distanceM
    }

    private fun bearingDiff(a: Double, b: Double): Double =
        abs(((a - b + HALF_TURN_DEG + FULL_TURN_DEG) % FULL_TURN_DEG) - HALF_TURN_DEG)

    companion object {
        /** The last good fix before a tunnel can be several hundred metres before the portal (the
         * receiver degrades on the approach ramp: T5453's was 273 m out); heading is what keeps
         * 400 m safe. */
        const val ENTRY_RADIUS_M = 400.0

        /** How far apart two enforcement paths may be and still be one continuous tunnel drive
         * (Rozelle Interchange westbound ends ~1.4 km before the M4 East path begins; the road
         * between them is still underground). */
        const val CHAIN_GAP_M = 2000.0
        const val CHAIN_BEARING_TOLERANCE_DEG = 75.0
        const val MAX_CHAIN_HOPS = 4
        const val BEARING_TOLERANCE_DEG = 50.0
        const val MIN_REMAINING_KM = 0.3
        private const val FULL_TURN_DEG = 360.0
        private const val HALF_TURN_DEG = 180.0
    }
}
