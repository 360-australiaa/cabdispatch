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

/**
 * Closest approach, in metres, a trip must actually achieve to a gantry before that gantry counts
 * as CROSSED rather than merely passed nearby.
 *
 * **The problem this exists to solve** (reported from the field, 2026-09-07): a vehicle that never
 * enters a toll road can still spend a long time inside [TOLL_GANTRY_DETECTION_RADIUS_M] of its
 * gantries. Australian motorways are routinely flanked by service roads, parallel surface streets
 * and on/off ramps that carry non-tolled traffic, and a tunnel's gantry coordinate is the SURFACE
 * projection of a point underground — the street directly above it is metres away horizontally.
 * Charging on the 150m radius alone therefore bills drivers who never used the road, which is the
 * worst possible failure for a fare-regulated meter: an overcharge the passenger cannot dispute
 * because the meter "saw" a gantry.
 *
 * 150m has to stay the WATCH radius — it is derived from real worst-case GPS error plus the
 * distance covered between two fixes at motorway speed (see that constant's own doc), and
 * tightening it would start missing genuine crossings. So detection keeps its generous radius and
 * a second, tighter test decides whether to CHARGE: a vehicle actually in the tolled lanes passes
 * essentially underneath the gantry, while a vehicle on adjacent infrastructure keeps a real
 * lateral offset. 60m sits above ordinary in-vehicle GPS error (LocationFix.accuracyM documents
 * 5-30m) and below the lateral separation of a motorway from its own service road.
 */
const val TOLL_CONFIRM_RADIUS_M = 60.0

/**
 * How many of a road's own gantries must be confirmed at close range before the road is charged —
 * the "multiple checkpoints" corroboration rule.
 *
 * One close pass can still be a coincidence: a cross street that happens to run directly over a
 * gantry gives a genuine sub-60m fix. A vehicle really travelling the road passes a SEQUENCE of
 * its gantries, so requiring two independent confirmations turns a single coincidence into a
 * charge that has to be corroborated by a second, physically separate point.
 *
 * Only applied to roads that HAVE enough gantries for it to mean anything (see
 * [requiredConfirmations]). Demanding two from a road the registry only knows one gantry for
 * would silently make that road permanently unchargeable, which trades a false charge for a
 * guaranteed missed one.
 */
const val TOLL_MIN_CONFIRMATIONS = 2

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
 * Classified in NSW local time ([NSW_FARE_ZONE]), whatever zone [ts] arrives in. Those published
 * windows are Sydney wall clock, so a tablet set to another timezone would otherwise bill the
 * weekday morning peak at the night price and vice versa — the same defect (and the same fix) as
 * [au.com.threesixty.cabdispatch.domain.FareEngineImpl.resolveTimeClass], and matching the server's
 * `app.services.tolls._shb_sht_band`, which the two MUST agree with or the trip flags a variance.
 */
private fun shbShtBand(ts: ZonedDateTime): String {
    val local = ts.withZoneSameInstant(NSW_FARE_ZONE)
    val isWeekend = local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY
    val minuteOfDay = local.hour * 60 + local.minute
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
    /** Cap shared across every road in the same [TollRoadRef.networkGroup] for ONE trip, on top of
     * this road's own [capClassA] — WestConnex publishes $12.74 (Class A) across M4/M8/M5E/M4-M8
     * Link. `null` for a road with no such group, or a revision that hasn't captured the figure:
     * in both cases [onFix] applies no network clamp rather than inventing one. */
    val networkCapClassA: BigDecimal? = null,
    val timeOfDayRatesClassA: List<TimeOfDayRateRef>?,
    val confidence: String,
)

/**
 * One named toll point of a `per_point` road — the real, published, per-crossing price for M2's
 * six points and Cross City / Lane Cove Tunnel's two each.
 *
 * A `per_point` road's own [TollPriceRef.priceClassAMax] is NOT a usable price: the road-level
 * min/max is a descriptive range spanning its cheapest ramp to its full mainline toll, and
 * charging anything out of it would be exactly the guess this whole feature refuses to make.
 * These are what [onFix] charges instead.
 */
data class TollPointRef(
    val id: String,
    val name: String,
    /** `null` when the source data never resolved a price for this point — flagged, never guessed. */
    val priceClassA: BigDecimal?,
    /** `verified`/`needs_verification`/`not_captured`, or `null` when no revision is cached at all.
     * `not_captured` and `null` are treated identically: never auto-charge. */
    val confidence: String?,
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
    /** `once_per_road` / `cumulative_per_point` / `distance_metered` — how crossings become a
     * charge. Deliberately a separate axis from [pricingModel], because it is NOT derivable from
     * it: Hills M2 and Lane Cove Tunnel are both `per_point` and bill differently (see [onFix]).
     * Defaulted to the ordinary case so a road built without one behaves exactly as before. */
    val chargingPolicy: String = "once_per_road",
    /** Roads sharing one network-wide cap for a single trip ("WESTCONNEX"), or null. */
    val networkGroup: String? = null,
    val directional: String?,
    val currentPrice: TollPriceRef?,
    /** Keyed by [TollPointRef.id]. Empty for every road priced at the road level. */
    val tollPoints: Map<String, TollPointRef> = emptyMap(),
)

/** One physical gantry — device-cached subset of `TollGantryRead`. [tollPointId] is non-null only
 * on a `per_point` road, and is what tells [onFix] WHICH of that road's points was crossed (and so
 * which price applies). */
data class TollGantryRef(
    val id: String,
    val tollRoadId: String,
    val latitude: Double,
    val longitude: Double,
    val tollPointId: String? = null,
)

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

/**
 * How many close-range gantry confirmations [roadId] needs before it may be charged.
 *
 * [TOLL_MIN_CONFIRMATIONS] where the registry knows enough gantries to supply them, otherwise as
 * many as physically exist. A road the dataset only has one gantry for cannot corroborate itself;
 * demanding two would make it permanently unchargeable, silently converting a false-charge risk
 * into a guaranteed missed charge. Those roads still have to clear the tight
 * [TOLL_CONFIRM_RADIUS_M] test — they just cannot be asked for a second opinion that does not
 * exist.
 */
internal fun requiredConfirmations(registry: TollRegistrySnapshot, roadId: String): Int {
    val gantryCount = registry.gantries.count { it.tollRoadId == roadId }
    return minOf(TOLL_MIN_CONFIRMATIONS, gantryCount).coerceAtLeast(1)
}

/** Whether this trip has enough close-range evidence to charge [roadId] — see
 * [TollDetectionState.confirmedGantries] and [requiredConfirmations]. */
internal fun isCorroborated(state: TollDetectionState, registry: TollRegistrySnapshot, roadId: String): Boolean =
    (state.confirmedGantries[roadId]?.size ?: 0) >= requiredConfirmations(registry, roadId)

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
    /** Charge key -> current auto-charged amount for this trip. `distance`-model roads REVISE
     * this value in place (see [onFix]) rather than growing a second entry.
     *
     * The key is the ROAD id in every case EXCEPT a `cumulative_per_point` road (Hills M2), where
     * it is the TOLL POINT id ("M2:north_ryde") — because that road genuinely bills once per
     * distinct point traversed, so a single road-keyed entry could not represent it. This mirrors
     * the backend's `Trip.auto_tolled_roads` exactly, so a device charge and a server-side
     * reconstruction of the same trip produce identical keys. */
    val chargedRoads: MutableMap<String, BigDecimal> = mutableMapOf()

    /** roadId -> cumulative trip distance (km) at the FIRST gantry crossing for a `distance`-model
     * road — the anchor point [onFix] measures "distance travelled on this road" from. */
    val roadEntryDistanceKm: MutableMap<String, BigDecimal> = mutableMapOf()

    /** Roads (or, on a `cumulative_per_point` road, individual toll points — same keying as
     * [chargedRoads]) crossed that genuinely cannot be auto-priced from the real dataset: an
     * `unpriced` road, a point with no captured price, or a pricing model this build has no
     * formula for. Surfaced to the driver to add manually, never guessed. */
    val unpricedRoadIds: MutableSet<String> = mutableSetOf()

    /** Roads the driver has explicitly removed an auto-charge for (see [dismissCharge]) — never
     * re-added for the rest of this trip, even if the vehicle re-crosses the same gantry (e.g.
     * queued traffic near a toll point). A false positive the driver has already corrected must
     * stay corrected. */
    val dismissedRoadIds: MutableSet<String> = mutableSetOf()

    /**
     * Road id -> the ids of that road's gantries this trip has passed within
     * [TOLL_CONFIRM_RADIUS_M] of. The corroboration evidence behind every charge.
     *
     * Accumulates across the whole trip rather than per-pass: a road's gantries are physically
     * separated, so two confirmations genuinely mean two distinct places on the corridor, however
     * far apart in time they happened. Never cleared on a miss — a vehicle that legitimately
     * crossed one gantry and then lost signal has not stopped having crossed it.
     */
    val confirmedGantries: MutableMap<String, MutableSet<String>> = mutableMapOf()

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
 * 1. Never dedup by GANTRY id (M7 alone has 45 in the real dataset). Dedup by what the registry's
 *    own `charging_policy` says the road bills by — ROAD id for almost everything, TOLL POINT id
 *    for a `cumulative_per_point` road. That flag is what this function used to be missing: the
 *    published wording for Hills M2 prices by "the number of toll points traversed", which is a
 *    genuinely cumulative charge, and rather than guess a policy this file previously left M2,
 *    Cross City Tunnel and Lane Cove Tunnel permanently in the "flag for manual entry" branch.
 *    The 2026-09-07 registry correction published both the real per-point prices and the explicit
 *    policy, so they are charged properly now — read from the data, still never guessed.
 * 2. Any road/revision/toll point missing the fields its pricing model actually needs (see 4-6
 *    below), and any pricing model this build has no formula for at all (e.g. the retired
 *    `zone_flat` still sitting in a stale cache), are NEVER auto-charged — added to
 *    [TollDetectionState.unpricedRoadIds] instead, exactly the driver-must-add-manually contract
 *    this whole feature is built around.
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
 * 6. `per_point` (M2, CCT, LCT): charge the crossed TOLL POINT's own published price, never the
 *    road-level min/max (a descriptive range, not a real per-crossing figure). A
 *    `cumulative_per_point` road (M2) charges each distinct point traversed, keyed by point id; a
 *    `once_per_road` one (CCT, LCT) charges only the first point crossed, keyed by road id,
 *    because its points are a main tunnel and an alternate ramp a vehicle uses ONE of. That second
 *    reading is a flagged interpretation call carried over verbatim from the registry — see
 *    `app.models.toll`'s module docstring.
 * 7. A road in a `network_group` (WestConnex) is additionally clamped so the SUM of every charged
 *    road in that group never exceeds the group's published network-wide cap. Where a crossing
 *    would breach it, THIS road absorbs the reduction rather than retroactively lowering a charge
 *    already shown to the driver for an earlier one — same interpretation the backend makes, so
 *    device and server agree.
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

    // Record close-range evidence BEFORE any pricing. A gantry only counts as crossed once the
    // trip has actually passed within TOLL_CONFIRM_RADIUS_M of it -- see that constant's doc for
    // the adjacent-road false charge this prevents.
    for (gantry in hits) {
        if (tollHaversineM(lat, lng, gantry.latitude, gantry.longitude) <= TOLL_CONFIRM_RADIUS_M) {
            state.confirmedGantries.getOrPut(gantry.tollRoadId) { mutableSetOf() }.add(gantry.id)
            // Anchor a distance-priced road's accrual at the FIRST confirmed contact, not at the
            // point corroboration completes. Corroboration decides WHETHER to charge; it must not
            // also decide where the metering starts, or a road would silently under-bill the
            // entire stretch between its first and second confirmed gantry.
            state.roadEntryDistanceKm.getOrPut(gantry.tollRoadId) { cumulativeDistanceKm }
        }
    }

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

        // Corroboration gate. Being inside the 150m watch radius is not evidence of having used
        // the road -- see TOLL_CONFIRM_RADIUS_M. `continue`, never "flag as unpriced": a road
        // driven past is not a road whose price is unknown, and telling the driver to add it
        // manually would reintroduce exactly the false charge this gate exists to stop. A trip
        // that goes on to genuinely enter the road corroborates on a later fix and charges then.
        if (!isCorroborated(state, registry, roadId)) continue

        if (road.pricingModel == "per_point") {
            chargePerPointRoad(state, road, hits, changed, newlyUnpriced)
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

        var amount: BigDecimal? = when (road.pricingModel) {
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

        amount = networkCappedAmount(state, registry, road, amount)

        val rounded = amount.setScale(2, RoundingMode.HALF_UP)
        if (state.chargedRoads[roadId] == rounded) continue // no real change (e.g. plateaued at cap)
        state.chargedRoads[roadId] = rounded
        state.unpricedRoadIds.remove(roadId)
        changed[roadId] = rounded
    }

    return TollDetectionResult(chargedRoadsChanged = changed, newlyUnpricedRoadIds = newlyUnpriced)
}

/**
 * The driver-facing name for one entry of [TollDetectionState.chargedRoads] /
 * [TollDetectionState.unpricedRoadIds].
 *
 * Those keys are a ROAD id for almost everything, but a TOLL POINT id ("M2:north_ryde") on a
 * `cumulative_per_point` road — see [TollDetectionState.chargedRoads]'s own doc. A plain
 * `roadsById[key]` lookup therefore misses on exactly those entries and falls through to showing
 * the raw key, which is what the driver would then be read aloud by the toll alert and shown in
 * the fare breakdown. A charge the driver can't name is a charge they can't judge, which defeats
 * the point of announcing it at all.
 *
 * Returns "Hills M2 Motorway — North Ryde (mainline)" for a point, the plain road name for a road,
 * and the key itself only when the registry genuinely doesn't know it (a charge carried over from
 * a cache that has since been refreshed) — still honest, never blank.
 */
fun chargeDisplayName(registry: TollRegistrySnapshot, chargeKey: String): String {
    registry.roadsById[chargeKey]?.let { return it.name }
    for (road in registry.roadsById.values) {
        val point = road.tollPoints[chargeKey] ?: continue
        return "${road.name} — ${point.name}"
    }
    return chargeKey
}

/**
 * Charges a `per_point` road (M2, Cross City Tunnel, Lane Cove Tunnel) from the real published
 * price of the toll POINT actually crossed — see [onFix]'s doc, points 1 and 6, for why the
 * road-level min/max is never usable here.
 *
 * Split out of [onFix]'s loop rather than inlined because it is the one branch that can charge
 * more than once for the same road in a single call (a fix near two of M2's points), which makes
 * it the only place a per-road `continue` would be wrong.
 */
private fun chargePerPointRoad(
    state: TollDetectionState,
    road: TollRoadRef,
    hits: List<TollGantryRef>,
    changed: MutableMap<String, BigDecimal>,
    newlyUnpriced: MutableSet<String>,
) {
    val crossedPointIds = hits.filter { it.tollRoadId == road.id }.mapNotNull { it.tollPointId }.distinct()
    if (crossedPointIds.isEmpty()) {
        // A gantry matched this road but carries no toll point — a per_point road cannot be
        // priced without one, so flag rather than fall back to the road-level range.
        if (state.unpricedRoadIds.add(road.id)) newlyUnpriced.add(road.id)
        return
    }

    if (road.chargingPolicy == "cumulative_per_point") {
        for (pointId in crossedPointIds) {
            if (pointId in state.dismissedRoadIds) continue
            if (pointId in state.chargedRoads) continue // this exact point already charged
            val amount = priceOfPoint(road, pointId)
            if (amount == null) {
                if (state.unpricedRoadIds.add(pointId)) newlyUnpriced.add(pointId)
                continue
            }
            val rounded = amount.setScale(2, RoundingMode.HALF_UP)
            state.chargedRoads[pointId] = rounded
            state.unpricedRoadIds.remove(pointId)
            changed[pointId] = rounded
        }
        return
    }

    // once_per_road: the FIRST point actually crossed sets the price for the whole trip; a
    // different point of the same road crossed later is never charged again. Keyed by road id,
    // since there is only ever one charge for this road.
    if (road.id in state.chargedRoads) return
    val amount = priceOfPoint(road, crossedPointIds.first())
    if (amount == null) {
        if (state.unpricedRoadIds.add(road.id)) newlyUnpriced.add(road.id)
        return
    }
    val rounded = amount.setScale(2, RoundingMode.HALF_UP)
    state.chargedRoads[road.id] = rounded
    state.unpricedRoadIds.remove(road.id)
    changed[road.id] = rounded
}

/** The point's own published Class A price, or `null` if it is unknown to this registry snapshot,
 * carries no price, or is flagged `not_captured` — all three mean "flag, never guess". */
private fun priceOfPoint(road: TollRoadRef, pointId: String): BigDecimal? {
    val point = road.tollPoints[pointId] ?: return null
    if (point.confidence == null || point.confidence == "not_captured") return null
    return point.priceClassA
}

/**
 * Clamps [rawAmount] (already capped at this road's OWN cap) so the SUM of every charged road
 * sharing [road]'s `networkGroup` never exceeds that group's published network-wide cap —
 * WestConnex bills at most $12.74 (Class A) across M4/M8/M5E/M4-M8 Link for one trip, however many
 * stages are used. A no-op for a road with no group, or a group with no captured cap figure.
 *
 * Kotlin mirror of `app.services.tolls._network_capped_amount`, including its flagged
 * interpretation call: when a crossing would breach the cap, THIS (most recently crossed) road
 * absorbs the reduction rather than retroactively lowering a charge already shown to the driver
 * for an earlier one. Matching the backend matters beyond taste here — a device charge and the
 * server's own reconstruction of the same trace must agree, or every WestConnex trip trips the
 * fare-variance check.
 */
private fun networkCappedAmount(
    state: TollDetectionState,
    registry: TollRegistrySnapshot,
    road: TollRoadRef,
    rawAmount: BigDecimal,
): BigDecimal {
    val group = road.networkGroup ?: return rawAmount
    val networkCap = road.currentPrice?.networkCapClassA ?: return rawAmount

    var priorTotal = BigDecimal.ZERO
    for ((chargedId, chargedAmount) in state.chargedRoads) {
        if (chargedId == road.id) continue
        if (registry.roadsById[chargedId]?.networkGroup == group) priorTotal += chargedAmount
    }

    val remaining = (networkCap - priorTotal).coerceAtLeast(BigDecimal.ZERO)
    return rawAmount.min(remaining)
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
