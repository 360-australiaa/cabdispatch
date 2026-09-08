package au.com.threesixty.cabdispatch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for tariffs, tariff presets, signing keys and the NSW toll-road registry.
 *
 * Split out of [ApiService] verbatim in Phase 0 (P0.4) — same package, same
 * visibility, same declarations, no behaviour change. `DriverEngagementDtos.kt` set
 * the precedent. [ApiService]'s file header still carries the rules these all follow:
 * money and other Pydantic `Decimal`s arrive as JSON *strings* and are kept as [String]
 * (never Float/Double), datetimes are ISO-8601 normalised to UTC, and unknown keys are
 * ignored by the shared [au.com.threesixty.cabdispatch.data.cabDispatchJson] config so
 * additive backend fields never break an older build.
 */

/**
 * Mirrors `TariffRead` — money/rate fields are decimal-as-string, see file header.
 *
 * [signature] is only ever populated on [ApiService.activeTariff]'s response (backend's
 * `SignedTariffRead`, `TariffRead` + a `signature` field) — every other endpoint that returns
 * this shape (`currentFaresOrder`, plain tariff CRUD) serves the unsigned `TariffRead` and leaves
 * it absent, which `ignoreUnknownKeys`/a nullable default (see data/JsonConfig.kt) makes safe to
 * share one DTO for. See `au.com.threesixty.cabdispatch.security.canonicalTariffPayload` (the
 * Kotlin port of `backend/app/services/tariff_signing.canonical_tariff_payload`, the exact
 * byte-format this signs) and [au.com.threesixty.cabdispatch.sync.TariffCache.refresh] (where the
 * signature is actually checked, then run through [au.com.threesixty.cabdispatch.domain.fare.validateAgainstFaresOrder]
 * — Point to Point Transport (Fares) Order 2026, effective 1 June 2026 — before this DTO is
 * trusted/cached).
 *
 * None of this DTO's own field defaults below hardcode a stale rate figure from the superseded
 * Fares Order 2025 (no.2) — every actual rate field (`flag_fall`/`dist_rate_1`/`dist_rate_2`/
 * `night_rate_1`/`night_rate_2`/`waiting_rate_per_min`) is mandatory on the wire, with no
 * client-side default to go stale; only the non-rate structural defaults below (thresholds,
 * multipliers, the PSL flat amount) have literal defaults, and none of those changed in the 2026
 * Order.
 */
@Serializable
data class TariffDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String?,
    val name: String,
    val region: String,
    @SerialName("effective_from") val effectiveFrom: String,
    @SerialName("effective_to") val effectiveTo: String? = null,
    val booked: Boolean = false,
    @SerialName("flag_fall") val flagFall: String,
    @SerialName("peak_charge") val peakCharge: String = "0",
    @SerialName("dist_rate_1") val distRate1: String,
    @SerialName("dist_rate_2") val distRate2: String,
    @SerialName("night_rate_1") val nightRate1: String,
    @SerialName("night_rate_2") val nightRate2: String,
    @SerialName("holiday_rate_1") val holidayRate1: String = "0",
    @SerialName("holiday_rate_2") val holidayRate2: String = "0",
    @SerialName("waiting_rate_per_min") val waitingRatePerMin: String,
    @SerialName("dist_km_threshold") val distKmThreshold: String = "12",
    @SerialName("speed_threshold_kmh") val speedThresholdKmh: String = "26",
    @SerialName("maxi_multiplier") val maxiMultiplier: String = "1.5",
    @SerialName("multi_hire_pct") val multiHirePct: String = "0.75",
    @SerialName("psl_amount") val pslAmount: String = "1.32",
    @SerialName("surcharge_pct_cap") val surchargePctCap: String = "5.0",
    // Point to Point Transport (Fares) Order 2026 cl 2(f): up to $124.14 — added server-side
    // alongside the 2026 rate-card pass. Defaults to that same figure so a tariff signed by an
    // older backend build (pre-field) still deserializes to the correct current cap rather than
    // "0".
    @SerialName("cleaning_fee_cap") val cleaningFeeCap: String = "124.14",
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val signature: String? = null,
)

// ---- Toll roads (real NSW toll registry — mirrors `backend/app/schemas/toll.py` field-for-field,
// same decimal-as-string convention as every other money field in this file: see that file's
// header note. [TollRoadPriceRevisionDto.timeOfDayRatesClassA]'s own `price` field is the ONE
// exception — see [TollTimeOfDayRateDto]'s doc for why.) ----

/** Mirrors `TollRoadRead`. [currentPrice] is the ONLY pricing this app ever caches/uses on-device
 * (see [au.com.threesixty.cabdispatch.sync.TollRegistryCache]'s doc) — unlike the backend's own
 * `TollRoadPriceRevision` table, the device has no use for historical revisions (a live meter only
 * ever needs "the price in force right now"; a disputed-trip audit against a past revision is a
 * dashboard/backend-side concern, per `app.models.toll`'s own module doc). */
@Serializable
data class TollRoadDto(
    val id: String,
    @SerialName("api_code") val apiCode: String? = null,
    val name: String,
    val operator: String? = null,
    @SerialName("pricing_model") val pricingModel: String,
    /** How gantry crossings become a charge (`once_per_road` /
     * `cumulative_per_point` / `distance_metered`) — see
     * [au.com.threesixty.cabdispatch.domain.fare.TollRoadRef.chargingPolicy]. Defaulted rather
     * than required so a response from a backend predating the 2026-09-07 correction still
     * decodes, landing on the ordinary case. */
    @SerialName("charging_policy") val chargingPolicy: String = "once_per_road",
    /** Roads sharing one network-wide cap for a single trip ("WESTCONNEX" today). */
    @SerialName("network_group") val networkGroup: String? = null,
    val directional: String? = null,
    val description: String? = null,
    @SerialName("derived_corridor_km") val derivedCorridorKm: String? = null,
    @SerialName("source_note") val sourceNote: String? = null,
    @SerialName("gantry_count") val gantryCount: Int = 0,
    @SerialName("current_price") val currentPrice: TollRoadPriceRevisionDto? = null,
    /** Non-empty only for a `per_point` road (M2/CCT/LCT). Such a road has no real road-level
     * price — its `current_price` min/max is a descriptive range across these points, never
     * what a crossing is charged — so this is where its actual prices live. Returned on the
     * LIST endpoint too, so one call is enough to price every road. */
    @SerialName("toll_points") val tollPoints: List<TollPointDto> = emptyList(),
)

/** Mirrors `TollPointRead` — one named toll point of a `per_point` road. */
@Serializable
data class TollPointDto(
    val id: String,
    @SerialName("toll_road_id") val tollRoadId: String,
    val name: String,
    val description: String? = null,
    @SerialName("source_note") val sourceNote: String? = null,
    @SerialName("gantry_count") val gantryCount: Int = 0,
    @SerialName("current_price") val currentPrice: TollPointPriceRevisionDto? = null,
)

/** Mirrors `TollPointPriceRevisionRead`. Same decimal-as-string convention as every other money
 * field here. A point whose `priceClassA` is null is FLAGGED for manual entry by
 * [au.com.threesixty.cabdispatch.domain.fare.onFix], never charged a guessed figure. */
@Serializable
data class TollPointPriceRevisionDto(
    val id: String,
    @SerialName("price_class_a") val priceClassA: String? = null,
    @SerialName("price_class_b") val priceClassB: String? = null,
    val currency: String = "AUD",
    @SerialName("gst_included") val gstIncluded: Boolean = true,
    @SerialName("effective_date") val effectiveDate: String,
    val indexation: String,
    val confidence: String,
    @SerialName("verify_note") val verifyNote: String? = null,
)

/**
 * Mirrors `TollRoadPriceRevisionRead`.
 *
 * [ratePerKmClassA]/[flagfallClassA] (product correction, 2026-09): the `distance`/
 * `distance_with_flagfall` pricing models' real per-km rate and flagfall component, taken directly
 * from the registry — see [au.com.threesixty.cabdispatch.domain.fare.TollPriceRef]'s own doc for
 * why the on-device detector no longer derives a rate from `cap_class_a / derived_corridor_km`
 * (confirmed wrong: Westlink M7's real published rate is $0.5252/km capped at $10.50, not the
 * geometrically-derived figure that formula produced).
 *
 * These field names were originally a forward-compatible GUESS made before the backend shipped
 * them, and the guess was WRONG in one place: `rate_class_a_per_km` is really `rate_per_km_class_a`
 * (`backend/app/schemas/toll.py`). Corrected here against the real schema — while it was wrong,
 * every `distance`/`distance_with_flagfall` road (M7 and all of WestConnex) silently decoded a
 * null rate and fell through to "unpriced, add manually", i.e. the meter never auto-charged
 * Sydney's biggest toll roads. Nothing in this file may guess a field name again: mismatches here
 * are silent, and a silently-null price is indistinguishable from an honestly-unpriced road.
 */
@Serializable
data class TollRoadPriceRevisionDto(
    val id: String,
    @SerialName("price_class_a_min") val priceClassAMin: String? = null,
    @SerialName("price_class_a_max") val priceClassAMax: String? = null,
    @SerialName("price_class_b_min") val priceClassBMin: String? = null,
    @SerialName("price_class_b_max") val priceClassBMax: String? = null,
    @SerialName("cap_class_a") val capClassA: String? = null,
    @SerialName("cap_class_b") val capClassB: String? = null,
    @SerialName("rate_per_km_class_a") val ratePerKmClassA: String? = null,
    @SerialName("flagfall_class_a") val flagfallClassA: String? = null,
    /** The cap shared across every road in the same `network_group` for ONE trip (WestConnex:
     * $12.74 Class A across M4/M8/M5E/M4-M8 Link), on top of each road's own `cap_class_a`. */
    @SerialName("network_cap_class_a") val networkCapClassA: String? = null,
    @SerialName("time_of_day_rates_class_a") val timeOfDayRatesClassA: List<TollTimeOfDayRateDto>? = null,
    val currency: String = "AUD",
    @SerialName("gst_included") val gstIncluded: Boolean = true,
    @SerialName("effective_date") val effectiveDate: String,
    val indexation: String,
    val confidence: String,
    @SerialName("verify_note") val verifyNote: String? = null,
)

/** One Class-A band entry of `TollRoadPriceRevision.time_of_day_rates_class_a` (SHB_SHT only,
 * today). **Not** decimal-as-string like every other money field in this file: the backend column
 * is a raw passthrough JSON blob (`scripts/seed_toll_roads.py` stores the source dataset's dict
 * verbatim, never routed through a Pydantic `Decimal` field — see `app.models.toll.TollRoadPriceRevision`'s
 * own field comment), so `price` arrives as a plain JSON number. [au.com.threesixty.cabdispatch.domain.fare.selectTimeOfDayPrice]
 * converts it via the same `.toString()` round-trip the Python original itself uses
 * (`Decimal(str(entry["price"]))`) rather than parsing a `Double` directly into fare math. */
@Serializable
data class TollTimeOfDayRateDto(
    val band: String,
    val price: Double? = null,
    val windows: String? = null,
)

/** Mirrors `TollGantryRead`. */
@Serializable
data class TollGantryDto(
    val id: String,
    @SerialName("toll_road_id") val tollRoadId: String,
    /** Non-null only on a `per_point` road — says which named toll point (and so which price)
     * this physical gantry charges. Null on every road priced at the road level. */
    @SerialName("toll_point_id") val tollPointId: String? = null,
    val location: String,
    val ramp: String? = null,
    val direction: String? = null,
    val latitude: Double,
    val longitude: Double,
)

/** Mirrors `TollRoadDetailRead` — [TollRoadDto]'s fields plus this one road's real gantries.
 * [priceHistory] is fetched (the backend always returns it) but deliberately never cached/read
 * on-device — see [TollRoadDto.currentPrice]'s doc. */
@Serializable
data class TollRoadDetailDto(
    val id: String,
    @SerialName("api_code") val apiCode: String? = null,
    val name: String,
    val operator: String? = null,
    @SerialName("pricing_model") val pricingModel: String,
    @SerialName("charging_policy") val chargingPolicy: String = "once_per_road",
    @SerialName("network_group") val networkGroup: String? = null,
    val directional: String? = null,
    val description: String? = null,
    @SerialName("derived_corridor_km") val derivedCorridorKm: String? = null,
    @SerialName("source_note") val sourceNote: String? = null,
    @SerialName("gantry_count") val gantryCount: Int = 0,
    @SerialName("current_price") val currentPrice: TollRoadPriceRevisionDto? = null,
    @SerialName("toll_points") val tollPoints: List<TollPointDto> = emptyList(),
    val gantries: List<TollGantryDto> = emptyList(),
    @SerialName("price_history") val priceHistory: List<TollRoadPriceRevisionDto> = emptyList(),
)

/** Response for [ApiService.tariffSigningPublicKey] — the backend's `TariffSigningPublicKeyRead`.
 * [publicKey] is X.509 SubjectPublicKeyInfo DER, base64-encoded, matching
 * `au.com.threesixty.cabdispatch.security.RsaTariffSignatureVerifier`'s existing key-encoding
 * convention (see that class's doc) even though the actual algorithm here is Ed25519. */
@Serializable
data class TariffSigningPublicKeyDto(
    @SerialName("public_key") val publicKey: String,
    val algorithm: String = "Ed25519",
)

// ---- Command Deck v2 additions (2026-08-27 redesign port) ----------------------------------

/** Mirrors `TariffPresetRead` (`backend/app/schemas/tariffs.py`). Only the fields the Tariff
 * Select screen renders are declared — `ignoreUnknownKeys` drops the rest safely. */
@Serializable
data class TariffPresetDto(
    val key: String,
    val label: String,
    val description: String,
    val defaults: TariffPresetDefaultsDto,
)

@Serializable
data class TariffPresetDefaultsDto(
    val region: String,
    val booked: Boolean,
    @SerialName("flag_fall") val flagFall: String,
    @SerialName("dist_rate_1") val distRate1: String,
    @SerialName("dist_rate_2") val distRate2: String,
    @SerialName("night_rate_1") val nightRate1: String,
    @SerialName("night_rate_2") val nightRate2: String,
    @SerialName("waiting_rate_per_min") val waitingRatePerMin: String,
)

/** Mirrors `TariffSuggestionRead`. */
@Serializable
data class TariffSuggestionDto(
    @SerialName("tariff_id") val tariffId: String,
    @SerialName("tariff_name") val tariffName: String,
    @SerialName("time_class") val timeClass: String,
    val reason: String,
)
