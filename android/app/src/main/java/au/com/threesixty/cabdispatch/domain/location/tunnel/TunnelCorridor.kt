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
    /** Every toll road a CHAINED corridor runs along, in driving order ([roadId] is the first);
     * the gantry sweep widens its search to exactly these roads and no others. */
    val roadIds: List<String> = listOfNotNull(roadId),
    /** Every bore's own [name] in a CHAINED corridor, in driving order ([name] is the first) --
     * 2026-09-15 field report: an advisory (the speed-camera warning) suppressed by comparing
     * against [name] alone kept firing once inside a LATER bore, because a chain keeps only its
     * entry bore's own name. See [au.com.threesixty.cabdispatch.domain.fare
     * .isSuppressedByLockedCorridor]'s call site for the fix. */
    val names: List<String> = listOf(name),
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
     * Every corridor that continues from [corridor]'s own exit portal, paired with its join gap
     * (m): the corridor whose first vertex lies within [CHAIN_GAP_M] of [corridor]'s last vertex
     * AND whose initial bearing is within [CHAIN_BEARING_TOLERANCE_DEG] of [corridor]'s final
     * bearing (the opposite bore also starts at the same exit portal, but points the other way,
     * so it never qualifies). The RAW candidate set -- [chainFrom] auto-continues through it only
     * when there is exactly one; more than one is a genuine fork (common in this dataset: 7 of 22
     * corridors have one -- Westconnex M4 East eastbound alone forks to BOTH the M8 and the
     * Rozelle Interchange), left for [au.com.threesixty.cabdispatch.domain.location.inertial
     * .InertialSpeedSource] to resolve live, from the vehicle's own measured heading, once it
     * actually reaches that point -- not a static geometric guess made here at blackout entry,
     * before any of that heading data exists (2026-09-16 fix; see [extendAtFork]).
     *
     * Excludes every bore already folded into [corridor] itself (its own "+"-joined id), so a
     * chain can never candidate its own constituent bores back to itself.
     */
    fun candidatesAt(corridor: TunnelCorridor): List<Pair<TunnelCorridor, Double>> {
        val alreadyIn = corridor.id.split("+").toSet()
        val (lastLat, lastLng) = corridor.points.last()
        val endBearing = corridor.bearingAt(corridor.lengthKm)
        return corridors.mapNotNull { c ->
            if (c.id in alreadyIn) return@mapNotNull null
            val (fLat, fLng) = c.points.first()
            val gapM = GeoMath.distanceKm(lastLat, lastLng, fLat, fLng) * TunnelCorridor.METRES_PER_KM
            // "Continues" = the next bore heads the way we are going, judged against BOTH our
            // final leg and the straight gap leg to it -- interchange tunnels bend right at the
            // join (Rozelle westbound ends on 297, the M4 East begins on 246), so either
            // reference may be the fair one.
            val gapBearing = GeoMath.bearingDeg(lastLat, lastLng, fLat, fLng)
            val startBearing = c.bearingAt(0.0)
            val diff = minOf(bearingDiff(startBearing, endBearing), bearingDiff(startBearing, gapBearing))
            if (gapM <= CHAIN_GAP_M && diff <= CHAIN_BEARING_TOLERANCE_DEG) c to gapM else null
        }
    }

    /**
     * [start] followed by every corridor that UNAMBIGUOUSLY continues it -- see [candidatesAt]'s
     * own doc for exactly what "continues" means and why a genuine fork (more than one
     * candidate) stops the chain here rather than guessing one by gap distance alone (that used
     * to be this function's own rule; the field report and fix are in [candidatesAt]'s doc).
     * Returns [start] itself when nothing unambiguously continues it. Capped at [MAX_CHAIN_HOPS]
     * hops -- a real fork always ends the chain sooner than that in this dataset.
     */
    fun chainFrom(start: TunnelCorridor): TunnelCorridor {
        val used = mutableListOf(start)
        var points = start.points
        var current = start
        repeat(MAX_CHAIN_HOPS) {
            val candidates = candidatesAt(current)
            if (candidates.size != 1) return@repeat // none, or a genuine fork -- leave it for runtime
            val next = candidates.single().first
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
            roadIds = used.mapNotNull { it.roadId }.distinct(),
            names = used.map { it.name }.distinct(),
        )
    }

    /**
     * Extends [corridor] by exactly one hop across a fork, using [liveHeadingDeg] -- the
     * vehicle's own dead-reckoned heading right now, integrated from the gyro since GPS was lost
     * -- to pick among the candidates [candidatesAt] finds at [corridor]'s current tail. Called
     * once per sample by [au.com.threesixty.cabdispatch.domain.location.inertial
     * .InertialSpeedSource] as the locked position approaches [corridor]'s own [TunnelCorridor
     * .lengthKm], not just once at blackout entry -- see that class's own doc.
     *
     * Zero candidates (a genuine dead end/no known continuation): returns [corridor] unchanged,
     * same as [chainFrom]. Exactly one: takes it, same as an unambiguous [chainFrom] hop, no
     * heading needed. More than one (a real fork) with [liveHeadingDeg] null (no measured heading
     * yet -- shouldn't happen once inside a blackout with a valid entry heading, but the estimator
     * makes no promises): returns [corridor] unchanged rather than guessing, the same "no data, no
     * guess" rule the rest of this pipeline follows -- [InertialSpeedSource] simply calls this
     * again on the next sample, once a heading exists. More than one WITH a heading: the
     * candidate whose own entry bearing ([TunnelCorridor.bearingAt] at 0.0) is closest to
     * [liveHeadingDeg] -- the vehicle's actual measured direction through the join, not a static
     * guess from the corridor geometry alone.
     */
    // ReturnCount: three guard-clause returns (no candidates, exactly one taken via `when`'s own
    // return, no heading to resolve a real fork with) plus the one real result -- same accepted
    // guard-clause style as chainFrom's own helpers above.
    @Suppress("ReturnCount")
    fun extendAtFork(corridor: TunnelCorridor, liveHeadingDeg: Double?): TunnelCorridor {
        val candidates = candidatesAt(corridor).map { it.first }
        val chosen = when {
            candidates.isEmpty() -> return corridor
            candidates.size == 1 -> candidates.single()
            liveHeadingDeg == null -> return corridor
            else -> candidates.minBy { bearingDiff(it.bearingAt(0.0), liveHeadingDeg) }
        }
        return TunnelCorridor(
            id = "${corridor.id}+${chosen.id}",
            name = corridor.name,
            roadId = corridor.roadId,
            points = corridor.points + chosen.points,
            roadIds = (corridor.roadIds + chosen.roadIds).distinct(),
            names = (corridor.names + chosen.names).distinct(),
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
