package au.com.threesixty.cabdispatch.domain.location

import au.com.threesixty.cabdispatch.domain.fare.TollGantryRef
import au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A route the GPS simulator drives — an ordered list of real-world waypoints, plus the speed to
 * drive them at.
 *
 * Pure geometry with no Android dependency, so the interpolation below (the part that decides
 * exactly which coordinates the meter sees, and therefore which tolls fire) is unit-testable on a
 * plain JVM, the same discipline the fare engine itself is held to.
 *
 * **Why routes are BUILT from the real toll registry, not hardcoded.** Testing automatic toll
 * detection means driving through real gantry coordinates within
 * [TOLL_GANTRY_DETECTION_RADIUS_M][au.com.threesixty.cabdispatch.domain.fare.TOLL_GANTRY_DETECTION_RADIUS_M].
 * Hand-typing a polyline for each toll road would be inventing coordinates that only approximate
 * the ones detection actually uses, so a passing test would prove nothing about the real data --
 * and it would silently rot the moment the registry is re-seeded. [throughTollRoad] instead reads
 * the device's own cached registry and drives that road's actual gantries in order, so a
 * simulated crossing is a crossing of the same coordinate the meter matches against.
 */
/**
 * A speed that changes over time, for routes that need to ramp rather than hold one figure.
 *
 * Every existing route is constant-speed, which is fine for exercising toll detection but cannot
 * demonstrate anything that depends on CHANGING speed — the meter dial's animation bands, for
 * instance, whose whole point is what happens at 26 and 60 km/h. Testing those by switching routes
 * shows the three states but never a transition between them.
 *
 * Segments are linear ramps, so distance over a segment is the trapezoid area and has a closed
 * form. Pure Kotlin and unit-tested, because a wrong integral would put the simulated taxi in the
 * wrong place rather than merely at the wrong speed.
 */
data class SpeedProfile(val segments: List<Segment>) {
    /** One ramp: [durationS] seconds moving linearly from [fromKmh] to [toKmh]. A hold is a
     * segment whose two speeds are equal. */
    data class Segment(val durationS: Double, val fromKmh: Double, val toKmh: Double) {
        init {
            require(durationS > 0) { "a segment needs a positive duration, got $durationS" }
            require(fromKmh >= 0 && toKmh >= 0) { "speeds cannot be negative: $fromKmh -> $toKmh" }
        }

        /** Metres covered over the whole segment: the trapezoid under the speed ramp. */
        val distanceM: Double get() = (fromKmh + toKmh) / 2.0 / 3.6 * durationS
    }

    init {
        require(segments.isNotEmpty()) { "a speed profile needs at least one segment" }
    }

    val totalDurationS: Double get() = segments.sumOf { it.durationS }

    /** Speed at [elapsedS]. Past the end this holds the final speed rather than wrapping — a route
     * that has finished leaves the vehicle where it stopped, as the constant-speed path does. */
    fun speedAt(elapsedS: Double): Double {
        var t = elapsedS.coerceAtLeast(0.0)
        for (seg in segments) {
            if (t <= seg.durationS) {
                val fraction = if (seg.durationS == 0.0) 0.0 else t / seg.durationS
                return seg.fromKmh + (seg.toKmh - seg.fromKmh) * fraction
            }
            t -= seg.durationS
        }
        return segments.last().toKmh
    }

    /** Metres covered by [elapsedS] — the integral of [speedAt], segment by segment. */
    fun distanceM(elapsedS: Double): Double {
        var t = elapsedS.coerceAtLeast(0.0)
        var total = 0.0
        for (seg in segments) {
            if (t <= seg.durationS) {
                val fraction = if (seg.durationS == 0.0) 0.0 else t / seg.durationS
                val speedHere = seg.fromKmh + (seg.toKmh - seg.fromKmh) * fraction
                total += (seg.fromKmh + speedHere) / 2.0 / 3.6 * t
                return total
            }
            total += seg.distanceM
            t -= seg.durationS
        }
        // Past the end: keep going at the final speed, matching speedAt.
        return total + segments.last().toKmh / 3.6 * t
    }
}

data class SimulatedRoute(
    val id: String,
    val name: String,
    /** What this route is for, shown in the picker -- e.g. which tolls it should trigger. */
    val description: String,
    /** At least two points. Consecutive points are driven in a straight line at [speedKmh]. */
    val waypoints: List<LatLng>,
    /** The route's speed, or its nominal/peak speed when [speedProfile] is set. */
    val speedKmh: Double,
    /** When set, the speed varies over time and [speedKmh] is only the headline figure shown in
     * the picker. Null — every existing route — keeps the constant-speed path exactly as it was. */
    val speedProfile: SpeedProfile? = null,
) {
    init {
        require(waypoints.size >= 2) { "a route needs at least two waypoints, got ${waypoints.size}" }
        require(speedKmh > 0) { "speedKmh must be positive, got $speedKmh" }
    }

    /** Speed at [elapsedSeconds] — the profile's, or the constant. */
    fun speedAt(elapsedSeconds: Double): Double =
        speedProfile?.speedAt(elapsedSeconds) ?: speedKmh

    /** Total ground distance, metres. */
    val lengthM: Double
        get() = waypoints.zipWithNext().sumOf { (a, b) -> haversineM(a, b) }

    /** How long driving the whole route takes at [speedKmh], seconds. */
    val durationSeconds: Double
        get() = lengthM / (speedKmh / 3.6)

    /**
     * The position [elapsedSeconds] into the drive, and the compass bearing being travelled.
     *
     * Past the end of the route this clamps to the final waypoint rather than wrapping or
     * extrapolating -- a route that has finished should leave the vehicle stationary at its
     * destination, which is what a real trip does, not teleport back to the start.
     */
    fun positionAt(elapsedSeconds: Double): RoutePosition {
        var remaining = speedProfile?.distanceM(elapsedSeconds)
            ?: (elapsedSeconds * (speedKmh / 3.6))

        for ((from, to) in waypoints.zipWithNext()) {
            val legLength = haversineM(from, to)
            if (legLength <= 0.0) continue // duplicate points: nothing to drive
            if (remaining <= legLength) {
                val fraction = remaining / legLength
                return RoutePosition(
                    point = interpolate(from, to, fraction),
                    bearingDegrees = bearingDegrees(from, to),
                    finished = false,
                )
            }
            remaining -= legLength
        }

        val last = waypoints.last()
        val secondLast = waypoints[waypoints.size - 2]
        return RoutePosition(point = last, bearingDegrees = bearingDegrees(secondLast, last), finished = true)
    }
}

data class LatLng(val lat: Double, val lng: Double)

data class RoutePosition(
    val point: LatLng,
    val bearingDegrees: Double,
    /** True once the vehicle has reached the last waypoint. */
    val finished: Boolean,
)

private const val EARTH_RADIUS_M = 6_371_008.8

internal fun haversineM(a: LatLng, b: LatLng): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

internal fun bearingDegrees(from: LatLng, to: LatLng): Double {
    val lat1 = Math.toRadians(from.lat)
    val lat2 = Math.toRadians(to.lat)
    val dLng = Math.toRadians(to.lng - from.lng)
    val y = sin(dLng) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/**
 * Linear interpolation in lat/lng space.
 *
 * Not a great-circle interpolation, and deliberately so: legs here are at most a few kilometres
 * of Sydney motorway, where the difference between the two is well under a metre -- far inside
 * the 150m gantry detection radius this exists to exercise. A rhumb/great-circle interpolation
 * would add trigonometry for no behavioural difference at this scale.
 */
internal fun interpolate(from: LatLng, to: LatLng, fraction: Double): LatLng = LatLng(
    lat = from.lat + (to.lat - from.lat) * fraction,
    lng = from.lng + (to.lng - from.lng) * fraction,
)

/**
 * Route builders. Toll routes come from the device's own cached registry (see [SimulatedRoute]'s
 * doc for why they are not hardcoded); the plain routes are synthetic lines for exercising
 * flagfall/distance/waiting without involving tolls at all.
 */
object SimulatedRoutes {

    /**
     * Drives every gantry of one real toll road, in order, with a short lead-in before the first
     * one.
     *
     * The lead-in matters: toll detection needs a PREVIOUS fix to compute a travel bearing from,
     * and a directional road (northbound_only, one_way) refuses to charge until it has one --
     * see `directionAllowsCharge`. Starting the route exactly on top of the first gantry would
     * mean the very first fix has no bearing, so a directional road would decline to charge and
     * the route would silently test nothing. Starting ~400m back guarantees a real, correctly
     * oriented bearing by the time the first gantry is reached.
     *
     * Returns null when the road is unknown to the cached registry or has fewer than one gantry
     * -- an honest "cannot build this route" rather than a route through invented coordinates.
     */
    fun throughTollRoad(
        registry: TollRegistrySnapshot,
        roadId: String,
        speedKmh: Double = 80.0,
    ): SimulatedRoute? {
        val road = registry.roadsById[roadId] ?: return null
        val gantries = registry.gantries.filter { it.tollRoadId == roadId }
        if (gantries.isEmpty()) return null

        val ordered = orderAlongCorridor(gantries)
        val points = ordered.map { LatLng(it.latitude, it.longitude) }
        val leadIn = leadInBefore(points)

        return SimulatedRoute(
            id = "toll:$roadId",
            name = road.name,
            description = "Drives all ${gantries.size} real gantries of ${road.name} in order " +
                "(${road.pricingModel}, ${road.chargingPolicy}).",
            waypoints = listOf(leadIn) + points,
            speedKmh = speedKmh,
        )
    }

    /**
     * Greedy nearest-neighbour ordering from the westernmost/southernmost gantry.
     *
     * The registry stores gantries as an unordered set with no sequence along the corridor, so
     * driving them in registry order would zig-zag across the city and produce absurd implied
     * speeds. Nearest-neighbour is not an optimal path, but a real motorway's gantries lie
     * roughly along a line, which is exactly the shape where greedy nearest-neighbour is a good
     * approximation -- and the only property this needs is "passes within 150m of each one,
     * roughly in corridor order", not "shortest possible tour".
     */
    internal fun orderAlongCorridor(gantries: List<TollGantryRef>): List<TollGantryRef> {
        val remaining = gantries.toMutableList()
        // Deterministic start point so the same registry always produces the same route --
        // a test route that varies run to run is not a test route.
        val start = remaining.minWith(compareBy({ it.longitude }, { it.latitude }))
        remaining.remove(start)

        val ordered = mutableListOf(start)
        while (remaining.isNotEmpty()) {
            val current = LatLng(ordered.last().latitude, ordered.last().longitude)
            val next = remaining.minBy { haversineM(current, LatLng(it.latitude, it.longitude)) }
            remaining.remove(next)
            ordered.add(next)
        }
        return ordered
    }

    /** A point ~400m before [points]'s first point, on the same heading as the first leg. */
    private fun leadInBefore(points: List<LatLng>): LatLng {
        val first = points.first()
        val second = points.getOrNull(1) ?: return LatLng(first.lat - 0.0036, first.lng)
        val legLength = haversineM(first, second)
        if (legLength <= 0.0) return LatLng(first.lat - 0.0036, first.lng)
        // Extend backwards along the first leg by extrapolating with a negative fraction.
        val fraction = -(LEAD_IN_M / legLength)
        return interpolate(first, second, fraction)
    }

    private const val LEAD_IN_M = 400.0

    /**
     * A plain city drive with no toll roads anywhere near it -- for exercising flagfall, the
     * distance rate and the tariff's time-of-day handling without a toll confusing the total.
     *
     * These coordinates are a synthetic line through open water east of Sydney, chosen precisely
     * BECAUSE nothing is there: any real street route risks passing within 150m of a gantry and
     * quietly adding a toll to what is supposed to be a toll-free baseline.
     */
    fun plainDrive(speedKmh: Double = 50.0): SimulatedRoute = SimulatedRoute(
        id = "plain",
        name = "Plain drive (no tolls)",
        description = "A straight ${"%.0f".format(speedKmh)} km/h run clear of every gantry — " +
            "flagfall, distance and time-of-day only.",
        waypoints = listOf(
            LatLng(-33.8900, 151.3200),
            LatLng(-33.9200, 151.3600),
            LatLng(-33.9500, 151.4000),
            LatLng(-33.9800, 151.4400),
        ),
        speedKmh = speedKmh,
    )

    /**
     * Ramps up through both animation bands and back down again.
     *
     * Every other route holds one speed, which shows the meter dial's three characters but never a
     * TRANSITION between them -- and the transitions are the thing worth watching: the crossfade at
     * the tariff's 26 km/h line, and the hysteresis that stops the dial strobing when the speed
     * hovers there.
     *
     * The profile deliberately crosses 26 and 60 twice each, and dwells at 55-60 on the way down so
     * FAST can be seen holding through its exit margin rather than dropping the instant the speed
     * does. It ends stopped, which is the state the whole calm-motion design is judged on: the ring
     * should be completely still.
     *
     * About 2.5 minutes end to end.
     */
    fun bandSweep(): SimulatedRoute = SimulatedRoute(
        id = "bands",
        name = "Band sweep (0 -> 90 -> 0)",
        description = "Ramps through waiting -> distance -> fast and back. The dial should change " +
            "character at 26 and 60 km/h, hold FAST down to 55, and go completely still at the end.",
        // The same open-water line as plainDrive, extended: this profile covers roughly 2.4 km, and
        // a route that ran out of waypoints would clamp to its last one and stop early.
        waypoints = listOf(
            LatLng(-33.8900, 151.3200),
            LatLng(-33.9200, 151.3600),
            LatLng(-33.9500, 151.4000),
            LatLng(-33.9800, 151.4400),
            LatLng(-34.0100, 151.4800),
        ),
        speedKmh = 90.0,
        speedProfile = SpeedProfile(
            listOf(
                SpeedProfile.Segment(15.0, 3.0, 3.0),    // crawling: WAITING
                SpeedProfile.Segment(25.0, 3.0, 45.0),   // through 26: WAITING -> DISTANCE
                SpeedProfile.Segment(15.0, 45.0, 45.0),  // hold DISTANCE
                SpeedProfile.Segment(20.0, 45.0, 90.0),  // through 60: DISTANCE -> FAST
                SpeedProfile.Segment(20.0, 90.0, 90.0),  // hold FAST
                SpeedProfile.Segment(15.0, 90.0, 57.0),  // down to 57: still FAST (exit is 55)
                SpeedProfile.Segment(10.0, 57.0, 57.0),  // dwell there -- the hysteresis proof
                SpeedProfile.Segment(15.0, 57.0, 0.0),   // all the way down
                SpeedProfile.Segment(15.0, 0.0, 0.0),    // stopped: the ring must be static
            ),
        ),
    )

    /**
     * Barely moving -- below the tariff's waiting-mode threshold, so the meter should switch to
     * charging by time rather than distance. Deliberately not zero: a stationary vehicle produces
     * no bearing at all, and this route is meant to test the waiting rate, not the no-bearing path.
     */
    fun stopAndGo(): SimulatedRoute = SimulatedRoute(
        id = "waiting",
        name = "Stopped in traffic",
        description = "3 km/h crawl — the meter should be in WAITING mode, charging by time.",
        waypoints = listOf(
            LatLng(-33.8900, 151.3200),
            LatLng(-33.8910, 151.3210),
        ),
        speedKmh = 3.0,
    )
}
