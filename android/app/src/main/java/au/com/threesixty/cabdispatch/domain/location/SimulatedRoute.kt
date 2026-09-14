package au.com.threesixty.cabdispatch.domain.location

import au.com.threesixty.cabdispatch.domain.fare.TollGantryRef
import au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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
private const val INVERSE_STEP_S = 0.25
private const val INVERSE_SEARCH_TAIL_S = 3600.0

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
    /** Inverse of [distanceM]: the first elapsed second (to [INVERSE_STEP_S] resolution) at which
     * the profile has covered [targetM]. Small numeric search rather than a closed form -- the
     * profile is piecewise-linear in speed, i.e. piecewise-quadratic in distance, and the routes
     * are a few minutes long, so a 0.25s walk is a few hundred iterations at most. */
    fun elapsedAtDistanceM(targetM: Double): Double {
        var t = 0.0
        val limit = totalDurationS + INVERSE_SEARCH_TAIL_S
        while (t < limit && distanceM(t) < targetM) t += INVERSE_STEP_S
        return t
    }

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
    /**
     * Windows of elapsed-seconds, relative to route start, during which [GpsSimulator] emits NO
     * fixes at all -- a real GPS blackout (tunnel, underground car park), not a stationary or
     * zero-speed fix. Empty for every route that existed before this field (2026-09-12, GPS
     * blackout program W1): a route with no blackout window behaves exactly as it always did.
     *
     * Deliberately absence-of-fixes, not a fabricated "zero fix" or "frozen fix": that is the one
     * thing that actually exercises [au.com.threesixty.cabdispatch.domain.FareEngineImpl.tick]'s
     * real staleness path ([au.com.threesixty.cabdispatch.domain.FareEngineImpl]'s `gpsLost`,
     * keyed on [au.com.threesixty.cabdispatch.domain.LocationFix.receivedAtNanos] aging past
     * `MAX_FIX_AGE_MS`) the same way a real tunnel does -- see [isBlackoutAt]'s own doc for how
     * [GpsSimulator] uses this.
     */
    val blackoutWindows: List<ClosedFloatingPointRange<Double>> = emptyList(),
) {
    init {
        require(waypoints.size >= 2) { "a route needs at least two waypoints, got ${waypoints.size}" }
        require(speedKmh > 0) { "speedKmh must be positive, got $speedKmh" }
        blackoutWindows.forEach {
            require(it.start >= 0.0) { "a blackout window cannot start before route start: $it" }
            require(it.start < it.endInclusive) { "a blackout window must have positive duration: $it" }
        }
    }

    /** Speed at [elapsedSeconds] — the profile's, or the constant. */
    fun speedAt(elapsedSeconds: Double): Double =
        speedProfile?.speedAt(elapsedSeconds) ?: speedKmh

    /**
     * True when [elapsedSeconds] falls inside one of [blackoutWindows] -- [GpsSimulator] reads
     * this every tick and, while true, skips publishing a fix/speed update entirely (see that
     * class's own doc), leaving its `StateFlow`s exactly as a real fused-location provider leaves
     * them mid-tunnel: frozen at the last accepted value, aging toward
     * [au.com.threesixty.cabdispatch.domain.FareEngineImpl]'s `MAX_FIX_AGE_MS` staleness cutoff.
     */
    fun isBlackoutAt(elapsedSeconds: Double): Boolean = blackoutWindows.any { elapsedSeconds in it }

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

// W7 haversine consolidation (2026-09-13): this used to carry its own copy of the same
// great-circle formula [GeoMath.distanceKm] implements, with the same Earth-radius constant
// (6,371,008.8 m == 6371.0088 km) -- two independently-typed copies of one another with nothing
// keeping them in step. [GeoMath] is the one designated home for this (see its own doc for why
// it's a byte-for-byte mirror of the backend's haversine); the only actual haversine this
// consolidation must NOT touch is [au.com.threesixty.cabdispatch.domain.fare.TollDetector.tollHaversineM],
// which stays byte-identical to the backend's toll-geofence formula by explicit requirement.
internal fun haversineM(a: LatLng, b: LatLng): Double = GeoMath.distanceKm(a.lat, a.lng, b.lat, b.lng) * 1000.0

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

    /**
     * An airport pickup: starts ON the T1 International taxi rank, leaves via Airport Drive and
     * Southern Cross Drive, takes the M1 Eastern Distributor northbound (a real toll) and ends in
     * the CBD. About 12 km at 50 km/h, ~14 minutes.
     *
     * The one hardcoded route that deliberately touches real Sydney roads (see this file's doc
     * for why the toll routes are otherwise built from the registry): what it demonstrates is the
     * combination the airport-fee feature is about -- the access fee charged ONCE at trip start,
     * because the start fix is inside the T1 rank zone, plus an ordinary auto-detected toll later
     * on the same trip -- and neither half can be shown by a registry-built route, whose start
     * point is a lead-in 400m before a gantry. The two Eastern Distributor gantries the registry
     * carries (M1 William Street, M1 Woolloomooloo) sit on the polyline itself, heading north,
     * which `SimulatedRouteTest` pins so this route cannot quietly drift off them.
     *
     * Start the meter AFTER the simulator has parked the vehicle on the rank (the first fix), so
     * the trip's start position is the rank, not wherever the real GPS last was.
     */
    fun airportPickupToCbd(speedKmh: Double = 50.0): SimulatedRoute = SimulatedRoute(
        id = "airport_t1_cbd",
        name = "Airport T1 pickup → CBD via Eastern Distributor",
        description = "Starts on the T1 International taxi rank (airport access fee at trip " +
            "start), Airport Dr → Southern Cross Dr → M1 Eastern Distributor northbound (toll) → CBD.",
        waypoints = listOf(
            LatLng(-33.9361, 151.1656), // T1 International taxi rank
            LatLng(-33.9318, 151.1712), // Airport Drive, leaving T1
            LatLng(-33.9292, 151.1792), // Airport Drive / Qantas Drive
            LatLng(-33.9318, 151.1885), // Joyce Drive, north of the domestic terminals
            LatLng(-33.9330, 151.1940), // General Holmes Drive junction, onto Southern Cross Drive
            LatLng(-33.9250, 151.1985), // Southern Cross Drive at Gardeners Road
            LatLng(-33.9150, 151.2040), // Southern Cross Drive, Eastlakes
            LatLng(-33.9040, 151.2110), // Southern Cross Drive, Kensington
            LatLng(-33.8975, 151.2160), // Moore Park -- onto the M1 Eastern Distributor northbound
            LatLng(-33.8880, 151.2185), // Eastern Distributor tunnel, Paddington
            LatLng(-33.87583, 151.217257), // registry gantry ED:m1_william_street
            LatLng(-33.869775, 151.218413), // registry gantry ED:m1_woolloomooloo
            LatLng(-33.8680, 151.2135), // Sir John Young Crescent exit
            LatLng(-33.8640, 151.2118), // Macquarie Street
            LatLng(-33.8612, 151.2108), // Circular Quay
        ),
        speedKmh = speedKmh,
    )

    /**
     * A real GPS blackout through the Lane Cove Tunnel -- built from the device's own cached toll
     * registry, the same "read real data, don't invent coordinates" discipline [throughTollRoad]
     * documents (GPS blackout program, W1, 2026-09-12; see the optimisation plan doc's §1.2 "G1").
     *
     * The registry's own LCT gantries cluster in two groups ~7km apart -- "Falcon St
     * North/South" (the Lane Cove/eastern portal) and "east"/"west" (the M2/western portal,
     * despite the confusing names -- see `nsw_toll_gantries.csv`'s own `location` column, which
     * this reads by substring rather than hardcoding lat/lng, so a registry re-seed with corrected
     * coordinates flows through automatically). That real ~7km, ~90 km/h separation is exactly
     * what a tunnel-length blackout needs to be genuine rather than illustrative: this is not a
     * contrived "blackout for N seconds" timer, it is "the vehicle cannot be seen for as long as
     * it actually takes to drive between these two real, cached points" -- around 4.5 minutes,
     * comfortably exercising [au.com.threesixty.cabdispatch.domain.FareEngineImpl]'s
     * `MAX_TICK_SECONDS` clamp on reacquisition the way a short contrived gap would not.
     *
     * The blackout window opens [BLACKOUT_MARGIN_S] after leaving the east portal (so the fare
     * engine has a moving, GPS-confirmed baseline before signal drops -- see
     * [au.com.threesixty.cabdispatch.domain.fare.knownCorridorDistanceKm]'s own doc for why the
     * corridor match needs a genuine "was moving" entry) and closes the same margin before the
     * west portal, leaving a live approach on both ends for reacquisition to actually reacquire.
     *
     * Returns null when the registry has no LCT gantries at all -- honest "cannot build this
     * route" rather than a fabricated tunnel, matching [throughTollRoad]'s own convention.
     */
    fun laneCoveTunnelBlackout(
        registry: TollRegistrySnapshot,
        speedKmh: Double = 90.0,
    ): SimulatedRoute? {
        val gantries = registry.gantries.filter { it.tollRoadId == "LCT" }
        if (gantries.size < 2) return null

        // The two portal clusters, deduplicated by coordinate (the registry carries literal
        // duplicate points for some gantries -- e.g. two rows at the exact same "Falcon St North"
        // coordinate -- which would otherwise contribute a zero-length leg to the drive).
        fun clusterCentroid(matchesEast: Boolean): LatLng? {
            val cluster = gantries.filter { g ->
                val eastPortal = g.id.contains("falcon_st", ignoreCase = true)
                eastPortal == matchesEast
            }
            if (cluster.isEmpty()) return null
            return LatLng(cluster.map { it.latitude }.average(), cluster.map { it.longitude }.average())
        }

        val eastPortal = clusterCentroid(matchesEast = true) ?: return null
        val westPortal = clusterCentroid(matchesEast = false) ?: return null

        val leadIn = leadInBefore(listOf(eastPortal, westPortal))
        val waypoints = listOf(leadIn, eastPortal, westPortal)
        val mps = speedKmh / 3.6
        val leadInM = haversineM(leadIn, eastPortal)
        val tunnelM = haversineM(eastPortal, westPortal)

        // A real speed change INSIDE the blackout (2026-09-14, bench-testing the accelerometer
        // path): cruise in at [speedKmh], slow to [slowKmh] for a stretch mid-tunnel, back up to
        // [speedKmh] and out. With SimulatedImu feeding the matching acceleration, the dial has to
        // visibly follow this underground -- a meter that merely held the entry speed (the first
        // real Sydney drive's failure) reads wrong for the whole middle of the tunnel.
        val slowKmh = speedKmh * SLOW_FRACTION
        val slowDownM = SpeedProfile.Segment(SPEED_CHANGE_S, speedKmh, slowKmh).distanceM
        val slowM = SpeedProfile.Segment(SLOW_HOLD_S, slowKmh, slowKmh).distanceM
        val speedUpM = SpeedProfile.Segment(SPEED_CHANGE_S, slowKmh, speedKmh).distanceM
        val marginM = BLACKOUT_MARGIN_S * mps
        val entryCruiseM = tunnelM * ENTRY_CRUISE_FRACTION
        val exitCruiseM = tunnelM - entryCruiseM - slowDownM - slowM - speedUpM
        require(exitCruiseM > marginM) { "LCT too short for the mid-tunnel speed change at $speedKmh km/h" }
        val profile = SpeedProfile(
            listOf(
                SpeedProfile.Segment((leadInM + entryCruiseM) / mps, speedKmh, speedKmh),
                SpeedProfile.Segment(SPEED_CHANGE_S, speedKmh, slowKmh),
                SpeedProfile.Segment(SLOW_HOLD_S, slowKmh, slowKmh),
                SpeedProfile.Segment(SPEED_CHANGE_S, slowKmh, speedKmh),
                SpeedProfile.Segment(exitCruiseM / mps, speedKmh, speedKmh),
            ),
        )
        val route = SimulatedRoute(
            id = "blackout:lane_cove_tunnel",
            name = "GPS blackout — Lane Cove Tunnel",
            description = "A real tunnel run through the cached Lane Cove Tunnel gantries: GPS " +
                "drops entirely for the ~7km underground crossing and returns at the far portal, " +
                "with a real slow-down to ${slowKmh.toInt()} km/h mid-tunnel. Confirms the dial " +
                "keeps a live accelerometer speed through the gap and the tunnel is billed on " +
                "reacquisition.",
            waypoints = waypoints,
            speedKmh = speedKmh,
            speedProfile = profile,
        )

        val blackoutStartS = profile.elapsedAtDistanceM(leadInM + marginM)
        val blackoutEndS = profile.elapsedAtDistanceM(leadInM + tunnelM - marginM)
        require(blackoutStartS < blackoutEndS) {
            "LCT portal separation too short for a $BLACKOUT_MARGIN_S s margin on both ends " +
                "at $speedKmh km/h -- widen the margin down or drive it faster"
        }

        return route.copy(blackoutWindows = listOf(blackoutStartS..blackoutEndS))
    }

    /** Seconds of live GPS kept on each side of a blackout window, for a moving baseline before
     * loss and a real reacquisition after -- see [laneCoveTunnelBlackout]'s own doc. */
    private const val BLACKOUT_MARGIN_S = 6.0

    /** Mid-tunnel slow-down for the LCT bench route: to 60% of cruise (54 km/h at 90), over 20s,
     * held 40s, back up over 20s, starting a quarter of the way in. */
    private const val SLOW_FRACTION = 0.6
    private const val SPEED_CHANGE_S = 20.0
    private const val SLOW_HOLD_S = 40.0
    private const val ENTRY_CRUISE_FRACTION = 0.25

    /**
     * A GPS blackout while the vehicle is genuinely stationary -- an underground car park, not a
     * moving tunnel crossing (GPS blackout program, W1, 2026-09-12; see the optimisation plan
     * doc's §1.2 "G1"). Exercises the OTHER half of [au.com.threesixty.cabdispatch.domain
     * .FareEngineImpl.tick]'s F3 rule: a blackout that begins while the vehicle is already below
     * the tariff's speed threshold must keep billing WAITING time throughout the gap, never
     * distance and never nothing -- the mirror image of [laneCoveTunnelBlackout]'s moving case.
     *
     * Synthetic coordinates (a driveway short enough that no real toll gantry is anywhere near
     * it), not registry-built: this route needs no real toll data, only "drive a short distance,
     * stop, lose signal for two minutes while stopped, regain it".
     */
    fun carParkBlackout(): SimulatedRoute = SimulatedRoute(
        id = "blackout:car_park",
        name = "GPS blackout — underground car park",
        description = "Drives into a car park, stops, and GPS drops for 2 minutes while parked. " +
            "Confirms waiting time keeps accruing through the gap and no distance is invented.",
        waypoints = listOf(
            LatLng(-33.8900, 151.3200),
            LatLng(-33.8903, 151.3204),
        ),
        speedKmh = 15.0,
        speedProfile = SpeedProfile(
            listOf(
                SpeedProfile.Segment(20.0, 15.0, 15.0), // drive in
                SpeedProfile.Segment(10.0, 15.0, 0.0),  // slow to a stop
                SpeedProfile.Segment(150.0, 0.0, 0.0),  // parked -- the blackout window sits here
                SpeedProfile.Segment(10.0, 0.0, 15.0),  // drive off
                SpeedProfile.Segment(20.0, 15.0, 15.0),
            ),
        ),
        // Opens 10s into the stationary segment (a confirmed-stopped baseline first) and closes
        // 10s before the drive-off resumes (a live reacquisition before motion, not mid-jump).
        blackoutWindows = listOf(40.0..170.0),
    )
}
