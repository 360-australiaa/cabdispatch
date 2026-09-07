package au.com.threesixty.cabdispatch.domain.fare

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.ZonedDateTime
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device NSW toll-road auto-detection — pure Kotlin port of the backend reference
 * implementation `backend/app/services/tolls.py`, same "no Android framework dependency, plain-JVM
 * unit-testable" discipline as [FareEngine] in this same file's package.
 *
 * **Why this exists on-device at all, not just on the server:** the shipped meter's sync path
 * (`POST /v1/trips/sync`, see [au.com.threesixty.cabdispatch.data.repository.TripRepository]'s own
 * doc) takes the device's `tolls` figure VERBATIM — the server runs no toll detection of its own on
 * that path (`PATCH /v1/trips/{id}/tick`, the endpoint that WOULD run `app.services.tolls.apply_toll_detection`
 * server-side, has no real call site in this app). So the device is the only place a toll can
 * honestly reach a real closed trip's bill unless the driver taps a manual preset
 * ([TollPresets][au.com.threesixty.cabdispatch.domain.TollPresets]) — this file is what makes that
 * automatic instead of manual-only.
 *
 * The one thing every function here exists to get right (identical framing to the Python
 * original): charge a road ONCE PER TRIP no matter how many of its gantries are crossed
 * ([onFix]'s per-road, not per-gantry, loop), respect a one-way/northbound/southbound-only road's
 * actual direction ([directionAllowsCharge]), and never invent a dollar figure the real dataset
 * doesn't support (the `zone_flat` / "unpriced" handling below).
 */

// --- geometry -----------------------------------------------------------------

private const val EARTH_RADIUS_M = 6_371_008.8

/**
 * Gantry-proximity detection radius, metres. NOT sourced from the toll-road dataset (it carries no
 * per-gantry detection radius at all) — a deliberate, documented app-level judgement call, same
 * status as the backend's own [GANTRY_DETECTION_RADIUS_M]-equivalent constant in `tolls.py`
 * (`app.services.tolls.GANTRY_DETECTION_RADIUS_M`, also 150.0) — and independently re-derived here,
 * not copied blindly, because the device faces a different worst case than the backend's own
 * comment describes (that comment reasons about GPS accuracy alone; the device must additionally
 * cover the DISTANCE travelled between two consecutive fixes, since a missed fix at speed can
 * otherwise blow straight past a gantry with no fix landing near it at all):
 *
 * - Fixes are requested at 1 Hz ([au.com.threesixty.cabdispatch.domain.location.RealLocationProvider.TICK_INTERVAL_MS])
 *   but arrive with real jitter, not a clean 1.0s cadence — a live device trace showed gaps up to
 *   ~2.04s (documented in [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel.nextTracePoint]'s
 *   own revision history). At 110 km/h (NSW's highest posted motorway limit; the M7/M2/M4 etc. all
 *   post 100-110), that's ~30.6 m/s * 2.04s ≈ 62m of travel between two fixes in the worst observed
 *   case — a fix could legitimately land that far past the gantry's true coordinate and still be
 *   the very first fix to see it.
 * - [au.com.threesixty.cabdispatch.domain.LocationFix.accuracyM]'s own doc: ordinary in-vehicle GPS
 *   accuracy is "typically 5-30m" — add the top of that range (30m) as independent positional error
 *   on top of the travel-distance figure above: ~62m + 30m ≈ 92m is the minimum radius that reliably
 *   catches a real crossing at highway speed under the worst observed fix cadence.
 * - 150m clears that ~92m floor with a real margin (not a razor's edge), while [onFix] deduplicates
 *   by ROAD, not gantry (point 3 of this file's class doc) — so a radius generous enough to also
 *   catch two or three gantries of the SAME road in one fix (M7 alone has 45) costs nothing extra;
 *   it never double-charges.
 * - The real risk of a generous radius is bleeding into a DIFFERENT road's gantry at a shared
 *   interchange. Checked against the real 141-gantry dataset
 *   (`app/data/nsw_toll_gantries.csv`): the closest cross-road gantry pairs are M4<->M4M5_ROZELLE's
 *   Haberfield interchange (~29-37m apart — both roads are `distance_with_flagfall`/`not_captured`,
 *   i.e. both already always land in "unpriced, add manually" regardless of which one fires, so a
 *   150m radius catching both there costs nothing) and M7<->M12 (~136-149m apart — M12 is
 *   `unpriced`, M7 is real `distance` pricing; the only actual exposure found is that a fix this
 *   close to that interchange could seed M7's distance-accrual ANCHOR point up to ~150m early/late,
 *   worth a few cents at M7's per-km rate, not a materially wrong charge). No cross-road pair in
 *   the real dataset creates a risk of auto-charging the WRONG road's real dollar amount at 150m.
 *   A materially smaller radius (e.g. 50m) would not remove this specific M7/M12 edge case anyway
 *   (their true gap is under 150m either way) while it WOULD start missing real crossings under the
 *   ~92m worst-case fix-gap derived above — a missed toll (silently eating the operator's cost) is
 *   the worse failure mode for a fare-regulated meter than a few cents of boundary fuzziness on
 *   what is already, structurally, the cheapest of the priced pricing models to be a little off on.
 *
 * Kept identical to the backend's own constant so a device and a (currently dead, but not
 * permanently — see this file's class doc) server-side detection pass would agree on what counts
 * as "at the gantry", not because 150m is assumed correct without checking.
 */
const val TOLL_GANTRY_DETECTION_RADIUS_M = 150.0

/** Minimum movement between consecutive GPS fixes before a computed bearing is trusted to mean
 * anything — mirrors `app.services.tolls._MIN_BEARING_DISTANCE_M` exactly (same value, same
 * reasoning: below this, ordinary GPS jitter or a vehicle stopped at lights dominates and the
 * "direction of travel" it implies is noise, not signal). */
private const val MIN_BEARING_DISTANCE_M = 15.0

/** Great-circle distance between two lat/lng points, in metres. Kotlin mirror of
 * `app.services.tolls.haversine_m`. */
fun tollHaversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dPhi = Math.toRadians(lat2 - lat1)
    val dLambda = Math.toRadians(lng2 - lng1)
    val a = sin(dPhi / 2).let { it * it } + cos(phi1) * cos(phi2) * sin(dLambda / 2).let { it * it }
    val c = 2 * asin(sqrt(a))
    return EARTH_RADIUS_M * c
}

/** Initial great-circle bearing from point 1 to point 2, degrees clockwise from true north,
 * normalized to [0, 360). Kotlin mirror of `app.services.tolls.bearing_degrees`. */
fun tollBearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dLambda = Math.toRadians(lng2 - lng1)
    val x = sin(dLambda) * cos(phi2)
    val y = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
    return (Math.toDegrees(atan2(x, y)) + 360) % 360
}

/** Coarse compass quadrant (45-degree buckets centred on the four cardinal directions) for travel
 * from point 1 to point 2, or `null` if the two points are too close together to trust (see
 * [MIN_BEARING_DISTANCE_M]) — e.g. the trip's very first fix, or a vehicle stopped at a light.
 * Kotlin mirror of `app.services.tolls.classify_bearing`, deliberately just as coarse (see that
 * function's own doc for why this is a fair approximation for the handful of NSW roads it gates,
 * not a survey-grade bearing calculation). */
fun classifyBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): String? {
    if (tollHaversineM(lat1, lng1, lat2, lng2) < MIN_BEARING_DISTANCE_M) return null
    val bearing = tollBearingDegrees(lat1, lng1, lat2, lng2)
    return when {
        bearing >= 315 || bearing < 45 -> "north"
        bearing < 135 -> "east"
        bearing < 225 -> "south"
        else -> "west"
    }
}

/**
 * Whether a crossing travelling in [compass] direction may charge a road whose [directional] value
 * is as given. Returns `true` (charge), `false` (genuinely the wrong direction — never charge this
 * crossing), or `null` ("can't tell yet this fix" — the caller must not charge on a `null`, but
 * should retry on a later fix once there's reliable movement; NOT the same as `false`). Kotlin
 * mirror of `app.services.tolls.direction_allows_charge`.
 *
 * `"both"`/`"one_way"`/`null` always allow, `one_way` WITHOUT needing a bearing at all — a one-way
 * road has only one physical carriageway, so any detected crossing is necessarily travelling its
 * only tolled direction regardless of what a coarse compass bucket says.
 */
fun directionAllowsCharge(directional: String?, compass: String?): Boolean? {
    if (directional == "both" || directional == "one_way" || directional == null) return true
    if (compass == null) return null
    return when (directional) {
        "northbound_only" -> compass == "north"
        "southbound_only" -> compass == "south"
        else -> true
    }
}

// --- time-of-day pricing (Sydney Harbour Bridge / Tunnel) --------------------

/**
 * Classifies [ts] into one of SHB_SHT's three real published bands (peak/off_peak/night) — faithful
 * structural mirror of `app.services.tolls._shb_sht_band`'s exact windows (the bands/prices
 * themselves still come from the fetched registry; only the *shape* of the schedule is encoded
 * here, from the same real source text):
 *   peak:     Mon-Fri 06:30-09:30 and 16:00-19:00
 *   off_peak: Mon-Fri 09:30-16:00; Sat-Sun 08:00-20:00
 *   night:    Mon-Fri 19:00-06:30; Sat-Sun 20:00-08:00
 * Deliberately uses [ts]'s own hour/weekday exactly as given — no timezone conversion — same
 * established convention as [au.com.threesixty.cabdispatch.domain.FareEngineImpl.resolveTimeClass].
 */
private fun shbShtBand(ts: ZonedDateTime): String {
    val isWeekend = ts.dayOfWeek == DayOfWeek.SATURDAY || ts.dayOfWeek == DayOfWeek.SUNDAY
    val minuteOfDay = ts.hour * 60 + ts.minute
    fun t(hour: Int, minute: Int = 0) = hour * 60 + minute

    if (!isWeekend) {
        if (minuteOfDay in t(6, 30) until t(9, 30) || minuteOfDay in t(16, 0) until t(19, 0)) return "peak"
        if (minuteOfDay in t(9, 30) until t(16, 0)) return "off_peak"
        return "night"
    }
    return if (minuteOfDay in t(8, 0) until t(20, 0)) "off_peak" else "night"
}

/** One band's Class A price from a [TollRoadRef.currentPrice]'s [TollPriceRef.timeOfDayRatesClassA]
 * list — `price` field, raw JSON `double` (not the usual decimal-as-string convention: see
 * [TimeOfDayRateRef]'s own doc for why), round-tripped through `.toString()` before parsing into a
 * [BigDecimal], same string round-trip the Python original itself uses
 * (`Decimal(str(entry["price"]))`) so this is a faithful mirror, not a new approximation. */
data class TimeOfDayRateRef(val band: String, val price: Double?)

/** Picks the Class A price for whichever band [ts] falls into. `null` if that list doesn't carry
 * the resolved band (shouldn't happen for real registry data, but a malformed/partial cached
 * revision must never crash a tick — see [onFix]'s "unpriced" fallback for this). Kotlin mirror of
 * `app.services.tolls.select_time_of_day_price`. */
fun selectTimeOfDayPrice(ratesClassA: List<TimeOfDayRateRef>, ts: ZonedDateTime): BigDecimal? {
    val band = shbShtBand(ts)
    val entry = ratesClassA.firstOrNull { it.band == band && it.price != null } ?: return null
    return BigDecimal(entry.price.toString())
}

// --- registry reference shapes (device-cached subset of the server's TollRoadRead/TollGantryRead) --

/**
 * Current (in-force) pricing snapshot for one [TollRoadRef] — the device only ever caches the
 * CURRENT revision (see [au.com.threesixty.cabdispatch.sync.TollRegistryCache]'s doc for why full
 * price history isn't needed on-device: unlike the backend's audit/dispute use of
 * `TollRoadPriceRevision`'s history, a live meter only ever needs "the price in force right now").
 *
 * [ratePerKmClassA]/[flagfallClassA] (product correction, 2026-09, against the real published
 * Linkt/NSW Government prices): real per-km rate / real flagfall component for the `distance` and
 * `distance_with_flagfall` pricing models, taken DIRECTLY from the registry — see [onFix]'s own
 * doc on why this file no longer derives a rate from `cap / corridor length` (that derivation was
 * confirmed wrong: Westlink M7's real published rate is $0.5252/km capped at $10.50, not the
 * geometrically-derived ~$0.30/km this file used to compute). `null` for a road/revision that
 * hasn't captured these fields — [onFix] treats that as "unpriced", never guesses a number.
 */
data class TollPriceRef(
    val priceClassAMax: BigDecimal?,
    val capClassA: BigDecimal?,
    val ratePerKmClassA: BigDecimal?,
    val flagfallClassA: BigDecimal?,
    val timeOfDayRatesClassA: List<TimeOfDayRateRef>?,
    val confidence: String,
)

/** Static identity + current pricing for one NSW toll road — device-cached subset of the server's
 * `TollRoadRead` (`backend/app/schemas/toll.py`). [pricingModel]/[directional] are the real
 * `TOLL_PRICING_MODELS`/`TOLL_DIRECTIONS` string values (see `app.models.toll`'s module doc) —
 * never re-interpreted or renamed here. Deliberately carries NO corridor-length field any more —
 * see [TollPriceRef.ratePerKmClassA]'s doc for why that derivation was removed outright rather than
 * kept as a fallback. */
data class TollRoadRef(
    val id: String,
    val name: String,
    val pricingModel: String,
    val directional: String?,
    val currentPrice: TollPriceRef?,
)

/** One physical gantry — device-cached subset of `TollGantryRead`. */
data class TollGantryRef(val id: String, val tollRoadId: String, val latitude: Double, val longitude: Double)

/** Immutable, in-memory snapshot of the cached registry loaded once per trip (or refresh) — never
 * re-read from Room per GPS fix; see [au.com.threesixty.cabdispatch.sync.TollRegistryCache.snapshot]. */
data class TollRegistrySnapshot(
    val roadsById: Map<String, TollRoadRef>,
    val gantries: List<TollGantryRef>,
) {
    companion object {
        val EMPTY = TollRegistrySnapshot(emptyMap(), emptyList())
    }
}

// --- gantry detection -----------------------------------------------------------

/** Every [TollGantryRef] within [radiusM] of (lat, lng). 141 rows total — a plain linear scan, same
 * "no spatial index, just haversine over a small table" trade-off the backend's own
 * `find_nearby_gantries` accepts, cheap enough to run on every GPS fix. */
fun findNearbyGantries(
    registry: TollRegistrySnapshot,
    lat: Double,
    lng: Double,
    radiusM: Double = TOLL_GANTRY_DETECTION_RADIUS_M,
): List<TollGantryRef> = registry.gantries.filter { tollHaversineM(lat, lng, it.latitude, it.longitude) <= radiusM }

// --- per-trip mutable detection state --------------------------------------------

/**
 * One trip's worth of toll-detection state — mirrors the shape of the backend's
 * `Trip.auto_tolled_roads`/`.toll_road_progress`/`.unpriced_toll_road_ids` columns, kept in-memory
 * here rather than Room-backed for the same reason the rest of [FareEngineImpl][au.com.threesixty.cabdispatch.domain.FareEngineImpl]'s
 * live accrual state is (see that class's own TODO doc: this instance's lifetime is tied to the
 * live-ticking engine, not a process-scoped singleton — a real, pre-existing, documented limitation
 * this file does not attempt to solve). [au.com.threesixty.cabdispatch.data.repository.TripRepository]
 * separately persists the CURRENT totals (`chargedRoads`'s values, folded into `TripEntity.tolls`
 * and, for local audit only, `autoTolledRoadsJson`) on every tick, so the money is never lost even
 * though this in-memory dedup bookkeeping is.
 */
class TollDetectionState {
    /** roadId -> current auto-charged amount for this trip. `distance`-model roads REVISE this
     * value in place (see [onFix]) rather than growing a second entry. */
    val chargedRoads: MutableMap<String, BigDecimal> = mutableMapOf()

    /** roadId -> cumulative trip distance (km) at the FIRST gantry crossing for a `distance`-model
     * road — the anchor point [onFix] measures "distance travelled on this road" from. */
    val roadEntryDistanceKm: MutableMap<String, BigDecimal> = mutableMapOf()

    /** Roads crossed that genuinely cannot be auto-priced from the real dataset (`zone_flat`,
     * `unpriced`) — surfaced to the driver to add manually, never guessed. */
    val unpricedRoadIds: MutableSet<String> = mutableSetOf()

    /** Roads the driver has explicitly removed an auto-charge for (see [dismissCharge]) — never
     * re-added for the rest of this trip, even if the vehicle re-crosses the same gantry (e.g.
     * queued traffic near a toll point). A false positive the driver has already corrected must
     * stay corrected. */
    val dismissedRoadIds: MutableSet<String> = mutableSetOf()

    /** The immediately preceding GPS fix, for bearing classification — `null` before the first
     * fix. Advanced on EVERY [onFix] call, hit or not, matching the backend's own
     * `apply_toll_detection`'s `(prev_lat, prev_lng)` contract ("the immediately preceding point
     * ... or the trip's last known position"). */
    var previousFix: Pair<Double, Double>? = null
}

/** What changed on one [onFix] call — [FareEngineImpl][au.com.threesixty.cabdispatch.domain.FareEngineImpl]
 * folds this straight into [au.com.threesixty.cabdispatch.domain.FareBreakdown.tolls] and the
 * driver-facing auto-toll/unpriced lists. Both maps/sets are typically empty (no gantry nearby on
 * an ordinary GPS fix — the overwhelmingly common case). */
data class TollDetectionResult(
    /** roadId -> new charged amount, for every road whose charge is NEW or REVISED this call
     * (`distance`-model roads can appear more than once across a trip, each time replacing the
     * previous entry — never additive). Empty on almost every call. */
    val chargedRoadsChanged: Map<String, BigDecimal> = emptyMap(),
    /** Road ids newly added to the "needs manual entry" set this call (not already flagged). */
    val newlyUnpricedRoadIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = chargedRoadsChanged.isEmpty() && newlyUnpricedRoadIds.isEmpty()
}

/**
 * Detects every real NSW toll-road gantry crossed at (lat, lng) and updates [state] in place —
 * Kotlin mirror of `app.services.tolls.apply_toll_detection`'s per-tick loop, same rules:
 *
 * 1. Dedup by ROAD id, never by gantry id (M7 alone has 45 gantries in the real dataset). **Open
 *    question, flagged rather than guessed (product report, 2026-09):** this "once per road"
 *    policy is correct for a distance-CAPPED road (M7), but the real published wording for M2
 *    ("Hills M2 Motorway") says charges "vary based on the number of toll points traversed" —
 *    which reads as a genuinely CUMULATIVE per-point charge, not a single per-road one. This file
 *    does NOT attempt to guess a per-point charging policy or invent per-point prices — M2/CCT/LCT
 *    stay in the `zone_flat` "never auto-charge, flag for manual entry" branch below until the
 *    registry publishes both the real per-point prices AND an explicit charging-policy field this
 *    function can read (a per-road "once" vs "per-point" flag) — see this repo's PR/report for the
 *    same flag, not a silent under- or over-charge shipped without it.
 * 2. `zone_flat` (M2, CCT, and — pending the registry update above — potentially LCT) and any
 *    road/revision missing the fields its pricing model actually needs (see 4/5 below) are NEVER
 *    auto-charged — added to [TollDetectionState.unpricedRoadIds] instead, exactly the
 *    driver-must-add-manually contract this whole feature is built around.
 * 3. Direction: `one_way` charges unconditionally (no bearing needed — physically single
 *    carriageway); `northbound_only`/`southbound_only` require a real, trusted bearing matching;
 *    an UNDETERMINED bearing (`null` — too little movement since the last fix, or this is the
 *    trip's very first fix) never charges but also never permanently blocks — [state] carries no
 *    "already tried and failed" marker for this case, so the very next fix with real movement
 *    tries again.
 * 4. `flat`/`time_of_day`: charge the matching Class A price once per road.
 * 5. `distance` (M7) and `distance_with_flagfall` (M4/M8/M5E/M4M5_ROZELLE — product correction,
 *    2026-09: these ARE real flagfall+per-km+cap roads, not unpriced as the superseded seed data
 *    claimed): track cumulative distance since the first gantry crossing, charge
 *    `min(flagfall? + rate * travelled, cap)`, REVISING the same [TollDetectionState.chargedRoads]
 *    entry in place as distance grows rather than adding a second one. Both pricing models read
 *    their rate/flagfall straight off [TollPriceRef] — see that type's own doc for why this file no
 *    longer derives a rate from a corridor length the way it originally did.
 *
 * A road in [TollDetectionState.dismissedRoadIds] (the driver already removed this auto-charge —
 * see [dismissCharge]) is skipped entirely for the rest of the trip, even if crossed again.
 */
fun onFix(
    state: TollDetectionState,
    registry: TollRegistrySnapshot,
    lat: Double,
    lng: Double,
    ts: ZonedDateTime,
    cumulativeDistanceKm: BigDecimal,
): TollDetectionResult {
    val previous = state.previousFix
    state.previousFix = lat to lng

    val hits = findNearbyGantries(registry, lat, lng)
    if (hits.isEmpty()) return TollDetectionResult()

    val compass = previous?.let { (plat, plng) -> classifyBearing(plat, plng, lat, lng) }

    val changed = mutableMapOf<String, BigDecimal>()
    val newlyUnpriced = mutableSetOf<String>()

    for (roadId in hits.map { it.tollRoadId }.distinct()) {
        if (roadId in state.dismissedRoadIds) continue
        val road = registry.roadsById[roadId] ?: continue

        if (road.pricingModel == "unpriced") {
            if (state.unpricedRoadIds.add(roadId)) newlyUnpriced.add(roadId)
            continue
        }

        val allowed = directionAllowsCharge(road.directional, compass)
        if (allowed == false) continue // genuinely the wrong direction — never charge
        if (allowed == null) continue // no reliable bearing yet — try again next fix

        if (road.pricingModel == "zone_flat") {
            // The published range spans a cheap ramp toll to the full mainline toll and the real
            // dataset does not say which applies to which specific gantry — charging a guessed
            // number from that range is exactly what this feature must never do. Flag instead.
            if (state.unpricedRoadIds.add(roadId)) newlyUnpriced.add(roadId)
            continue
        }

        val revisesInPlace = road.pricingModel == "distance" || road.pricingModel == "distance_with_flagfall"
        if (roadId in state.chargedRoads && !revisesInPlace) {
            continue // already charged once — nothing left to revise for this model
        }

        val price = road.currentPrice
        if (price == null || price.confidence == "not_captured") {
            if (state.unpricedRoadIds.add(roadId)) newlyUnpriced.add(roadId)
            continue
        }

        val amount: BigDecimal? = when (road.pricingModel) {
            "flat" -> price.priceClassAMax
            "time_of_day" -> selectTimeOfDayPrice(price.timeOfDayRatesClassA.orEmpty(), ts)
            // Westlink M7 (product correction, 2026-09): [TollPriceRef.ratePerKmClassA] is the
            // real published $/km rate, taken directly from the registry — see that field's own
            // doc for why this no longer derives anything from a corridor length.
            "distance" -> {
                val rate = price.ratePerKmClassA
                if (rate == null) {
                    null
                } else {
                    val entryKm = state.roadEntryDistanceKm.getOrPut(roadId) { cumulativeDistanceKm }
                    val travelledKm = (cumulativeDistanceKm - entryKm).coerceAtLeast(BigDecimal.ZERO)
                    val raw = rate * travelledKm
                    if (price.capClassA != null) raw.min(price.capClassA) else raw
                }
            }
            // WestConnex (M4/M8/M5E/M4M5_ROZELLE) — product correction, 2026-09: these are real
            // flagfall + per-km + cap roads, NOT unpriced as the (superseded) seed data claimed.
            // Both [TollPriceRef.flagfallClassA]/`.ratePerKmClassA` come straight from the
            // registry; `null` (today's actual API response — the backend hasn't shipped these
            // fields yet, see this file's own doc) correctly falls through to "unpriced" below,
            // exactly this road's honest CURRENT state, not a regression from before this pass.
            "distance_with_flagfall" -> {
                val rate = price.ratePerKmClassA
                val flagfall = price.flagfallClassA
                if (rate == null || flagfall == null) {
                    null
                } else {
                    val entryKm = state.roadEntryDistanceKm.getOrPut(roadId) { cumulativeDistanceKm }
                    val travelledKm = (cumulativeDistanceKm - entryKm).coerceAtLeast(BigDecimal.ZERO)
                    val raw = flagfall + rate * travelledKm
                    if (price.capClassA != null) raw.min(price.capClassA) else raw
                }
            }
            else -> null
        }

        if (amount == null) {
            if (state.unpricedRoadIds.add(roadId)) newlyUnpriced.add(roadId)
            continue
        }

        val rounded = amount.setScale(2, RoundingMode.HALF_UP)
        if (state.chargedRoads[roadId] == rounded) continue // no real change (e.g. plateaued at cap)
        state.chargedRoads[roadId] = rounded
        state.unpricedRoadIds.remove(roadId)
        changed[roadId] = rounded
    }

    return TollDetectionResult(chargedRoadsChanged = changed, newlyUnpricedRoadIds = newlyUnpriced)
}

/**
 * Driver-initiated correction: removes a previously auto-charged road for the rest of this trip
 * (never re-added even on a later re-crossing — see [TollDetectionState.dismissedRoadIds]'s doc).
 * Returns the amount that was charged (for the caller to subtract from the live fare total), or
 * `null` if [roadId] wasn't actually charged (no-op).
 */
fun dismissCharge(state: TollDetectionState, roadId: String): BigDecimal? {
    state.dismissedRoadIds.add(roadId)
    state.roadEntryDistanceKm.remove(roadId)
    return state.chargedRoads.remove(roadId)
}
