package au.com.threesixty.cabdispatch.ui.screens.hired

import au.com.threesixty.cabdispatch.data.remote.DirectionsRoute
import au.com.threesixty.cabdispatch.data.remote.RoutePoint
import au.com.threesixty.cabdispatch.data.remote.RouteStep
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The navigator's arithmetic, with no Android or coroutine dependencies so every rule is unit
 * testable on the JVM (`NavProgressTest`). [MeterNavViewModel] is the only production caller; it
 * feeds real GPS fixes and a real [DirectionsRoute] in and copies the answers into its UI state.
 *
 * Everything here is deliberately *simple* geometry — this is Directions-API guidance, not the
 * Mapbox Navigation SDK (see `MapboxDirections.kt`'s doc for why the SDK is unavailable). No
 * map-matching, no bearing checks: distances are great-circle ([haversineM]) between the fix and
 * the route's own vertices/maneuver points. Good enough for a taxi meter's "next turn + ETA"
 * pane; never claims more.
 *
 * ### Step semantics (Mapbox Directions)
 * `steps[i].lat/lng` is the point at which the driver *performs* instruction `i`, and
 * `steps[i].distanceM` is the road length from that maneuver to the next one (`steps[i+1]`). The
 * last step is the "arrive" step with distance 0. "Current step" here means the maneuver the
 * vehicle is driving towards: while `currentStepIndex == i`, the pane shows/speaks
 * `steps[i].instruction` and the vehicle is somewhere between maneuver `i-1` and maneuver `i`.
 */
object NavProgress {

    /** Within this many metres of the current maneuver point, the step counts as reached. */
    const val STEP_ARRIVE_M = 30.0

    /** Farther than this from every route vertex is "off the line" for one fix. */
    const val OFF_ROUTE_M = 80.0

    /** This many *consecutive* off-the-line fixes flip [OffRouteTracker] to off-route. */
    const val OFF_ROUTE_CONSECUTIVE = 3

    /** Minimum gap between two automatic reroute requests, so a flaky fix can't spam the API. */
    const val REROUTE_MIN_INTERVAL_MS = 20_000L

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in metres. */
    fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Distance from the fix to the nearest vertex of the route polyline, or
     * [Double.POSITIVE_INFINITY] for an empty polyline (so an empty route reads as "off route",
     * never "on route"). Vertex-only rather than true point-to-segment distance: with
     * `overview=full` Mapbox emits a vertex every few metres on curves and every few tens of
     * metres on straights, well inside [OFF_ROUTE_M], so the extra projection math buys nothing.
     */
    fun distanceToNearestVertexM(lat: Double, lng: Double, points: List<RoutePoint>): Double {
        var best = Double.POSITIVE_INFINITY
        for (p in points) {
            val d = haversineM(lat, lng, p.lat, p.lng)
            if (d < best) best = d
        }
        return best
    }

    /** True when the fix is within [thresholdM] of [step]'s maneuver point. */
    fun hasArrivedAtStep(lat: Double, lng: Double, step: RouteStep, thresholdM: Double = STEP_ARRIVE_M): Boolean =
        haversineM(lat, lng, step.lat, step.lng) <= thresholdM

    /**
     * The step index the navigator should be on after this fix. Advances past every step whose
     * maneuver point the fix has reached (several at once if maneuvers are clustered, e.g. a
     * slip road immediately followed by a merge), never backwards, and never past the last
     * ("arrive") step. Returns [currentIndex] unchanged when nothing was reached.
     *
     * One-step look-ahead: a fix that reaches the maneuver *after* the current one also counts,
     * so a maneuver whose 30 m window fell inside a GPS gap (tunnel, a dropped fix at speed) costs
     * at most one stale instruction rather than freezing the navigator on it for the rest of the
     * trip — it would otherwise stay "on route" (the polyline is still under the wheels) and so
     * never trigger the off-route reroute either. Deliberately only one step: the wider the
     * look-ahead, the likelier a self-crossing route (a block loop to turn around) fools it into
     * jumping ahead at the first pass.
     */
    fun advanceStepIndex(
        lat: Double,
        lng: Double,
        steps: List<RouteStep>,
        currentIndex: Int,
        thresholdM: Double = STEP_ARRIVE_M,
    ): Int {
        if (steps.isEmpty()) return currentIndex
        var index = currentIndex.coerceIn(0, steps.lastIndex)
        while (index < steps.lastIndex) {
            val reached = when {
                hasArrivedAtStep(lat, lng, steps[index], thresholdM) -> index
                index + 1 <= steps.lastIndex && hasArrivedAtStep(lat, lng, steps[index + 1], thresholdM) -> index + 1
                else -> -1
            }
            if (reached < 0) break
            index = minOf(reached + 1, steps.lastIndex)
        }
        return index
    }

    /**
     * Metres left to the destination, approximated as
     * `straight-line(fix -> current maneuver) + sum of distanceM over steps i, i+1, ..., last`.
     *
     * The straight-line leg is the only approximation: between two maneuvers the road may bend,
     * so this can read slightly short until the next maneuver is reached, at which point the
     * sum is exact again. It is monotonically non-increasing as the vehicle moves along the
     * route: the straight-line leg shrinks with every fix, and on a step advance the dropped
     * the dropped road length of step i is at least the straight line it replaced.
     */
    fun remainingDistanceM(lat: Double, lng: Double, steps: List<RouteStep>, currentIndex: Int): Double {
        if (steps.isEmpty()) return 0.0
        val index = currentIndex.coerceIn(0, steps.lastIndex)
        val current = steps[index]
        var remaining = haversineM(lat, lng, current.lat, current.lng)
        for (i in index until steps.size) remaining += steps[i].distanceM
        return remaining
    }

    /**
     * Seconds left, scaling the route's own average speed (`distanceM / durationS`, which bakes
     * in Mapbox's per-road-class speed model and traffic-free assumptions) over the remaining
     * distance. Approximation: assumes the remaining stretch averages the same speed as the whole
     * route — a route that is mostly motorway with a slow CBD tail will read optimistic near the
     * end. A route with zero distance or duration yields 0.
     */
    fun remainingDurationS(remainingDistanceM: Double, route: DirectionsRoute): Double {
        if (route.distanceM <= 0.0 || route.durationS <= 0.0) return 0.0
        val averageSpeedMps = route.distanceM / route.durationS
        return (remainingDistanceM / averageSpeedMps).coerceAtLeast(0.0)
    }

    fun etaEpochMillis(nowEpochMillis: Long, remainingDurationS: Double): Long =
        nowEpochMillis + (remainingDurationS * 1000).toLong()

    /**
     * Counts consecutive "far from the line" fixes so a single GPS blip (a tunnel exit, a tall
     * building bounce) can't trigger a reroute on its own. [onFix] returns true exactly when the
     * count first reaches [consecutiveRequired] and on every far fix after that until a near fix
     * or [reset] clears it.
     */
    class OffRouteTracker(
        private val thresholdM: Double = OFF_ROUTE_M,
        private val consecutiveRequired: Int = OFF_ROUTE_CONSECUTIVE,
    ) {
        var consecutiveFar: Int = 0
            private set

        val isOffRoute: Boolean get() = consecutiveFar >= consecutiveRequired

        fun onFix(distanceToRouteM: Double): Boolean {
            consecutiveFar = if (distanceToRouteM > thresholdM) consecutiveFar + 1 else 0
            return isOffRoute
        }

        fun reset() {
            consecutiveFar = 0
        }
    }
}
