package au.com.threesixty.cabdispatch.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local cache of one real NSW toll road's identity + CURRENT price only (`GET /v1/toll-roads` —
 * see [au.com.threesixty.cabdispatch.sync.TollRegistryCache]).
 *
 * Same offline-first role [TariffEntity] plays for fares: this is what lets
 * [au.com.threesixty.cabdispatch.domain.fare.onFix] keep auto-detecting tolls with zero
 * connectivity for the whole trip — it reads only [au.com.threesixty.cabdispatch.sync.TollRegistryCache.snapshot]
 * (a pure Room read), never the network directly.
 *
 * Unlike the backend's own `TollRoad`/`TollRoadPriceRevision` split (kept separate there so a
 * disputed historical trip can be checked against the price actually in force on the day it
 * happened — see `app.models.toll`'s module doc), this table flattens the CURRENT revision's
 * fields directly onto the road row: a live on-device meter only ever needs "the price in force
 * right now" to decide what to charge a crossing happening right now, never a past revision. A
 * disputed-trip audit against toll-road pricing history is a dashboard/backend-side concern, not
 * something this offline cache needs to carry.
 *
 * [id] is the real NSW toll-road registry's own natural key (e.g. "M7", "SHB_SHT" — see
 * `app.models.toll.TollRoad.id`'s own doc), not a synthetic UUID.
 *
 * Money fields are decimal-as-string, same convention as every other cached money value in this
 * app (see [TariffEntity]/ApiService.kt's header note) — `null` exactly when the server's own
 * `current_price`/that field was `null` (an "unpriced"/`not_captured` road genuinely has no price
 * to cache), never defaulted to "0".
 */
@Entity(tableName = "toll_roads")
data class TollRoadEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** One of `flat`/`per_point`/`distance`/`distance_with_flagfall`/`time_of_day`/`unpriced` —
     * see `app.models.toll.TOLL_PRICING_MODELS`. Never re-interpreted or renamed on-device.
     * (`zone_flat` was retired by the 2026-09-07 price correction; a cached row still carrying it
     * is simply a model this app has no formula for, and lands in the "flag, never guess" branch
     * of [au.com.threesixty.cabdispatch.domain.fare.onFix] until the next registry refresh.) */
    val pricingModel: String,
    /** One of `once_per_road`/`cumulative_per_point`/`distance_metered` — see
     * `app.models.toll.TOLL_CHARGING_POLICIES`. NOT derivable from [pricingModel]: Hills M2 and
     * Lane Cove Tunnel are both `per_point` and charge differently. */
    val chargingPolicy: String,
    /** Roads sharing one cap for a single trip ("WESTCONNEX"), or null. */
    val networkGroup: String?,
    /** One of `both`/`one_way`/`northbound_only`/`southbound_only`, or `null` — see
     * `app.models.toll.TOLL_DIRECTIONS`. */
    val directional: String?,
    /** Real derived corridor length, decimal-as-string — **legacy/unused as of the 2026-09
     * per-km-rate correction**: this device no longer computes a `distance`-model rate from
     * `cap / corridor` (see [au.com.threesixty.cabdispatch.domain.fare.TollPriceRef]'s doc for why
     * that derivation was wrong and removed outright). Kept as a column only to avoid a
     * DROP COLUMN migration for a field that costs nothing sitting unread; new rows still populate
     * it from the API response for potential future/debug use, but no on-device pricing logic
     * consumes it any more. */
    val derivedCorridorKm: String?,
    /** Current revision's Class A max price (`flat` model) — `null` if the road has no current
     * revision at all, or the field itself was `null` on the server. */
    val priceClassAMax: String?,
    /** Current revision's Class A cap (`distance`/`distance_with_flagfall` models' cap, applied
     * after flagfall + rate × distance) — `null` if not applicable/not captured. */
    val capClassA: String?,
    /** Real `distance`/`distance_with_flagfall` per-km rate, decimal-as-string — see
     * [au.com.threesixty.cabdispatch.data.remote.TollRoadPriceRevisionDto.ratePerKmClassA]'s doc
     * (2026-09 pricing correction). */
    val rateClassAPerKm: String?,
    /** Real `distance_with_flagfall` flat component, decimal-as-string — see
     * [au.com.threesixty.cabdispatch.data.remote.TollRoadPriceRevisionDto.flagfallClassA]'s doc. */
    val flagfallClassA: String?,
    /** Cap shared across every road in the same [networkGroup] for one trip, decimal-as-string.
     * Null for a road with no such group. */
    val networkCapClassA: String?,
    /** JSON-encoded `List<au.com.threesixty.cabdispatch.data.remote.TollTimeOfDayRateDto>`
     * (`time_of_day` model only, SHB_SHT today) — raw blob, same small-list-not-a-child-table
     * convention [TripEntity.gpsTraceJson] already uses. `null` for every other pricing model. */
    val timeOfDayRatesJson: String?,
    /** Current revision's `confidence` (`verified`/`needs_verification`/`not_captured`), or `null`
     * if the road has NO current revision cached at all (distinct from `not_captured`: this means
     * "we have never even seen a priced revision for this road", both cases are treated identically
     * by [au.com.threesixty.cabdispatch.domain.fare.onFix] — never auto-charge). */
    val confidence: String?,
    val fetchedAt: Long,
)
