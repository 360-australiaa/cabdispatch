package au.com.threesixty.cabdispatch.domain.fare

import java.math.BigDecimal

/**
 * "Toll ahead" map advisory — a calm, informational answer to "is there a toll gantry coming up
 * on the road I'm actually driving, and what would it cost?", computed entirely from the SAME
 * on-device toll registry [onFix]/[knownCorridorDistanceKm] already use
 * ([TollRegistrySnapshot], cached by [au.com.threesixty.cabdispatch.sync.TollRegistryCache] from
 * `GET /v1/toll-roads` — no network call from this file, no dependency on the backend's newer
 * `/v1/traffic` cameras/hazards endpoints at all). A pure function, same "plain Kotlin, no
 * Android/Compose dependency" discipline as [onFix] and [knownCorridorDistanceKm] in this same
 * package, so it is unit-testable on the JVM independent of the Compose map that renders its
 * answer ([au.com.threesixty.cabdispatch.ui.screens.hired.MeterBackdropMap]).
 *
 * **What "ahead" means here.** A gantry counts as ahead only if it is BOTH within
 * [TOLL_LOOKAHEAD_M] of the vehicle's current position AND roughly in the direction the vehicle
 * is actually heading — [TOLL_LOOKAHEAD_BEARING_TOLERANCE_DEG] either side of a real, trusted
 * compass bearing ([tollBearingDegrees] from the vehicle to the gantry, compared against
 * [headingDeg]). Both checks matter: distance alone would just as happily warn about a gantry the
 * vehicle just drove past and is now leaving behind, or one on a completely different, unrelated
 * road that happens to be nearby. With no trusted heading at all (`null` — the vehicle is
 * stationary or has moved too little since the last fix for a bearing to mean anything, same
 * [LocationFix.heading] honesty [onFix]'s own `compass` already relies on) this returns `null`
 * rather than guess a direction — a banner reading "toll ahead" while parked, or pointed the wrong
 * way, would be actively misleading, which is worse than saying nothing.
 *
 * **Why 2km.** At NSW's highest posted motorway limit (110 km/h ≈ 30.6 m/s — the same figure
 * [TOLL_GANTRY_DETECTION_RADIUS_M]'s own doc cites), 2000m gives a little over a minute of real
 * notice before the gantry — comparable to the lead time a real turn-by-turn app (Google Maps,
 * Waze) gives for an upcoming toll, long enough for a driver to mentally prepare (a passenger
 * asking about the fare, a lane change if the tag lane matters) without being so far out that the
 * banner is showing for the entire back half of a long highway run. Far short of "the whole
 * state" this feature's own sibling ([au.com.threesixty.cabdispatch.sync.TrafficCache]'s doc)
 * warns against for the camera/hazard markers — this is a single, nearest, ahead-of-the-vehicle
 * gantry, never a list.
 *
 * **Price is never invented.** [UpcomingToll.price] is the real published Class A price when the
 * registry has one for the crossing that would actually apply ([representativeTollPrice]'s own
 * doc for exactly which figure, per pricing model) — `null` when the road/point is `unpriced`,
 * `not_captured`, or genuinely has no usable figure cached yet. The banner still names the road in
 * that case; it just never shows a guessed dollar amount, same "flag, never fabricate" rule every
 * other toll-pricing path in this file follows.
 */
data class UpcomingToll(
    val roadName: String,
    val price: BigDecimal?,
    val distanceAheadM: Double,
)

/** See this file's class doc, "Why 2km" section. */
const val TOLL_LOOKAHEAD_M = 2000.0

/**
 * How far either side of the vehicle's real heading a gantry may sit and still count as "ahead" —
 * wide enough that a gently curving approach road or a slightly noisy heading doesn't make the
 * banner flicker on and off just short of the gantry, tight enough to still exclude a gantry that
 * is clearly behind the vehicle or off on an unrelated crossing road. Deliberately wider than
 * [classifyBearing]'s 45°-either-side cardinal buckets (that function is sorting a bearing into
 * one of four fixed compass quadrants for direction-of-travel road rules; this one is answering
 * the looser, single question "is this roughly in front of me").
 */
const val TOLL_LOOKAHEAD_BEARING_TOLERANCE_DEG = 60.0

/**
 * The nearest real toll gantry ahead of ([lat],[lng]) on heading [headingDeg], within
 * [lookaheadM] — or `null` when there is none (no gantry that close, none roughly ahead, no
 * trusted heading to judge "ahead" by at all, or the crossed gantry's road can't be resolved from
 * [registry]). See this file's class doc for the full reasoning behind both gates.
 */
fun upcomingToll(
    registry: TollRegistrySnapshot,
    lat: Double,
    lng: Double,
    headingDeg: Double?,
    lookaheadM: Double = TOLL_LOOKAHEAD_M,
    bearingToleranceDeg: Double = TOLL_LOOKAHEAD_BEARING_TOLERANCE_DEG,
): UpcomingToll? {
    if (headingDeg == null) return null // no trusted direction of travel -- never guess "ahead"

    var best: TollGantryRef? = null
    var bestDistanceM = Double.MAX_VALUE
    for (gantry in registry.gantries) {
        val distanceM = tollHaversineM(lat, lng, gantry.latitude, gantry.longitude)
        if (distanceM > lookaheadM || distanceM >= bestDistanceM) continue

        val bearingToGantry = tollBearingDegrees(lat, lng, gantry.latitude, gantry.longitude)
        if (angularDifferenceDeg(headingDeg, bearingToGantry) > bearingToleranceDeg) continue

        best = gantry
        bestDistanceM = distanceM
    }

    val gantry = best ?: return null
    val road = registry.roadsById[gantry.tollRoadId] ?: return null
    return UpcomingToll(
        roadName = road.name,
        price = representativeTollPrice(road, gantry),
        distanceAheadM = bestDistanceM,
    )
}

/** Smallest angle between two compass bearings, degrees, always in `[0, 180]`. */
internal fun angularDifferenceDeg(a: Double, b: Double): Double {
    val diff = Math.abs(a - b) % 360.0
    return if (diff > 180.0) 360.0 - diff else diff
}

/**
 * The one dollar figure to show for a gantry the vehicle hasn't crossed yet, per pricing model —
 * never a computed/derived estimate, only a real published figure already on [road]/[gantry]:
 * - `per_point`: the crossed toll POINT's own price ([TollPointRef.priceClassA]) — the road-level
 *   min/max is a descriptive range, not a real per-crossing figure (same reasoning [onFix]'s
 *   `chargePerPointRoad` documents for the actual charge).
 * - `flat`/`time_of_day`/anything else with a road-level max: [TollPriceRef.priceClassAMax] — the
 *   one real number a `flat` road publishes; the best honest single figure to show for
 *   `time_of_day` ahead of time, since which band will be in force AT the gantry depends on when
 *   the vehicle actually gets there, not now.
 * - `distance`/`distance_with_flagfall`: [TollPriceRef.capClassA] — the most this crossing could
 *   possibly cost (what the driver would pay for the road's full corridor), framed honestly as a
 *   cap rather than a specific fare this file has no way to know yet (the vehicle hasn't started
 *   accruing distance on the road).
 * `null` (never a guess) when none of the above is captured for this road/point — the banner
 * still names the road; it just shows no dollar figure.
 */
internal fun representativeTollPrice(road: TollRoadRef, gantry: TollGantryRef): BigDecimal? {
    gantry.tollPointId?.let { pointId ->
        road.tollPoints[pointId]?.priceClassA?.let { return it }
    }
    val price = road.currentPrice ?: return null
    return price.priceClassAMax ?: price.capClassA
}
