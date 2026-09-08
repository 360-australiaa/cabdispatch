package au.com.threesixty.cabdispatch.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The Fares Order urban distance/waiting changeover speed, and the single fallback used whenever a
 * [au.com.threesixty.cabdispatch.data.remote.TariffDto] carries no usable
 * `speedThresholdKmh` of its own (missing, zero, or negative).
 *
 * F7 (architecture audit 2026-09-08, §2.1): this literal `26.0` used to be written out by hand in
 * three separate places -- `FareEngineImpl.startTrip`, `FareEngineImpl.tick`, and
 * [FareState.speedThresholdKmh]'s own default -- so a correction to one was a silent, invisible
 * disagreement with the other two. The dial bands on [FareState.speedThresholdKmh] while the meter
 * charges on `tick`'s copy: if those two ever drifted apart, the ring would claim waiting-time
 * character on exactly the speeds the meter was billing as distance, with nothing on screen to
 * suggest anything was wrong. One constant, three readers.
 *
 * Note this is only ever a *fallback*. A real tariff row's own `speedThresholdKmh` always wins --
 * a country tariff has a different changeover, and hardcoding this figure into the accrual
 * decision (rather than into the "we have no tariff figure at all" branch) is precisely the
 * NSW-hardcoding the audit's §2.4 catalogues.
 */
const val DEFAULT_SPEED_THRESHOLD_KMH: Double = 26.0

enum class TripStatus { FOR_HIRE, HIRED, STOPPED, CLOSED }
enum class AccrualMode { DISTANCE, WAITING }
enum class TariffBand(val label: String) { BAND_1("Tariff 1"), BAND_2("Tariff 2") }
enum class TimeClass(val label: String) { DAY("Day"), NIGHT("Night"), HOLIDAY("Holiday") }

data class TollPreset(
    val id: String,
    val label: String,
    val amount: BigDecimal,
    /**
     * The id this preset's road carries in the NSW toll registry
     * ([au.com.threesixty.cabdispatch.data.local.entity.TollRoadEntity.id]), or `null` when this
     * preset is not a registry toll road at all.
     *
     * **T1 (architecture audit 2026-09-08, §2.3).** Manual presets and the automatic gantry
     * detector reached [FareBreakdown.tolls] by two completely independent routes with no
     * cross-check between them: [FareState.tollsApplied] (what the driver tapped) and
     * [FareState.autoTollsApplied] (what the detector found). A driver who tapped M5 while driving
     * the M5 -- the single most likely thing for a driver to do -- had the crossing charged twice,
     * and neither list on screen made it obvious why the toll line was double what it should be.
     * The two lists could not be reconciled because nothing connected `"m5"` to the registry's
     * `"M5SW"`. This field is that connection.
     *
     * `null` for the airport preset, and correctly so: the $6.43 Sydney Airport figure is a
     * regulated access fee levied at the rank, not a toll gantry, so there is no registry road it
     * could ever double up with. A `null` here means "nothing to reconcile", never "not looked up".
     */
    val registryRoadId: String? = null,
)

/**
 * Fixed preset list per spec B5 S3: "toll add buttons (M5, Harbour southbound,
 * airport toll presets)". Amounts are illustrative placeholders (round
 * cash-equivalent figures) — TODO(fleet-config sibling agent): source live
 * figures from tenant tariff/toll config server-side rather than hardcoding.
 */
object TollPresets {
    // registryRoadId values are the NSW registry's own natural keys (backend's
    // `app/data/nsw_toll_roads.json`): the "M5" a Sydney driver means is the M5 South-West
    // Motorway, "M5SW", not WestConnex's separate "M5E" (M5 East).
    val M5 = TollPreset("m5", "M5", BigDecimal("4.30"), registryRoadId = "M5SW")
    val HARBOUR_SOUTHBOUND =
        TollPreset("harbour_sb", "Harbour (southbound)", BigDecimal("4.19"), registryRoadId = "SHB_SHT")
    // $6.43 Sydney Airport access fee (Point to Point Transport (Fares) Order 2026, effective 1
    // June 2026 — was $6.30 under the superseded Fares Order 2025 (no.2)).
    val AIRPORT = TollPreset("airport", "Airport", BigDecimal("6.43"))
    val ALL = listOf(M5, HARBOUR_SOUTHBOUND, AIRPORT)
}

/**
 * One auto-detected NSW toll-road crossing currently reflected in [FareBreakdown.tolls] — the
 * automatic counterpart to a driver-tapped [TollPreset], surfaced in [FareState.autoTollsApplied]
 * so the driver can see (and, via [FareEngine.removeAutoToll], remove) exactly what was added and
 * why. [amount] is the CURRENT charge for [roadId] — for a `distance`-model road (M7) this same
 * entry is replaced in place as distance accrues (see [au.com.threesixty.cabdispatch.domain.fare.onFix]'s
 * doc), never duplicated into a second entry for the same road.
 */
data class AutoTollEntry(val roadId: String, val roadName: String, val amount: BigDecimal)

/**
 * A real NSW toll road the vehicle has crossed that the on-device registry genuinely cannot auto-
 * price (`zone_flat` roads like M2/Cross City Tunnel, whose published range doesn't say which
 * gantry gets which price, or a road with no captured price at all — M4/M8/M5E/M4M5_ROZELLE/M12).
 * Surfaced here so the driver knows to add a real toll manually (the existing ADD TOLL flow) rather
 * than the fare silently missing a real cost the trip actually incurred — never an invented dollar
 * figure. See [FareEngine.dismissUnpricedToll] for the driver's "I've dealt with this" affordance.
 */
data class UnpricedTollRoad(val roadId: String, val roadName: String)

/**
 * One-shot signal that an auto-toll was just added/revised — drives BOTH the audible confirmation
 * ([au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel]'s speech announcement) and the
 * transient on-screen banner ([au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen]),
 * product requirement (2026-09): "when vehicle move from that location diameter, automatically it
 * will make beep sound and show toll has been added" — a driver who can't tell WHICH toll fired
 * can't tell whether it was wrong, so this always names the real road and amount, never a generic
 * "toll added". [id] is a monotonic per-[au.com.threesixty.cabdispatch.domain.FareEngineImpl]-
 * instance counter (NOT a timestamp/random value) — the one thing consumers key off
 * (`LaunchedEffect`/`distinctUntilChanged`) to fire exactly once per real event, even if the same
 * road's amount is later revised to the identical figure twice in a row.
 */
data class AutoTollAlert(val roadName: String, val amount: BigDecimal, val id: Long)

data class FareBreakdown(
    val flagFall: BigDecimal = BigDecimal.ZERO,
    val distanceAmount: BigDecimal = BigDecimal.ZERO,
    val waitingAmount: BigDecimal = BigDecimal.ZERO,
    val peakAmount: BigDecimal = BigDecimal.ZERO,
    val tolls: BigDecimal = BigDecimal.ZERO,
    val psl: BigDecimal = BigDecimal.ZERO,
    val extras: BigDecimal = BigDecimal.ZERO,
) {
    val total: BigDecimal
        get() = listOf(flagFall, distanceAmount, waitingAmount, peakAmount, tolls, psl, extras)
            .fold(BigDecimal.ZERO, BigDecimal::add)
}

data class FareState(
    val status: TripStatus = TripStatus.FOR_HIRE,
    val mode: AccrualMode = AccrualMode.DISTANCE,
    val band: TariffBand = TariffBand.BAND_1,
    val timeClass: TimeClass = TimeClass.DAY,
    val breakdown: FareBreakdown = FareBreakdown(),
    val distanceKm: BigDecimal = BigDecimal.ZERO,
    val currentSpeedKmh: Double = 0.0,
    /**
     * The tariff's own waiting/distance line, carried here so the UI does not have to reach for a
     * Tariff it otherwise never needs.
     *
     * The meter dial marks this speed on its ring and bands its animation on it
     * ([au.com.threesixty.cabdispatch.domain.SpeedBand]) — a country tariff's different threshold
     * has to move both, or the dial would claim waiting-time character while the meter charged
     * distance. Defaults to the Fares Order urban figure, matching the fare engine's own fallback.
     */
    val speedThresholdKmh: Double = DEFAULT_SPEED_THRESHOLD_KMH,
    /**
     * Cumulative whole seconds spent in [AccrualMode.DISTANCE] / [AccrualMode.WAITING]
     * respectively, since [status] became [TripStatus.HIRED]. Added so
     * [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel] can persist
     * this live-ticking state's cumulative counters to
     * [au.com.threesixty.cabdispatch.data.repository.TripRepository] (matching
     * [au.com.threesixty.cabdispatch.data.local.entity.TripEntity.movingS]/
     * `.waitingS`) without a separate, error-prone tally kept outside the engine —
     * see [FareEngineImpl.tick], the only place these increment.
     */
    val movingSeconds: Int = 0,
    val waitingSeconds: Int = 0,
    val tollsApplied: List<TollPreset> = emptyList(),
    /**
     * Passenger count declared for this hiring — mirrors
     * [au.com.threesixty.cabdispatch.domain.fare.FareState.passengerCount]. Editable mid-trip
     * (miscounts happen) via [FareEngineImpl.updatePassengerCount]; changing it re-derives
     * [maxiRateApplied] for the remainder of the trip only, never retroactively. Defaults to 1 so
     * every pre-existing reader of this struct that never looked at this field keeps working.
     */
    val passengerCount: Int = 1,
    /**
     * Mirrors [au.com.threesixty.cabdispatch.domain.fare.FareState.wheelchairHiring] — set once at
     * [FareEngineImpl.startTrip], not editable mid-trip. Purely informational on this screen (a
     * reminder of the NSW Reg cl 82 safe-securement rule) — never itself gates the maxi rate here;
     * [maxiRateApplied] already bakes this carve-out in via the pure engine's own derivation.
     */
    val wheelchairHiring: Boolean = false,
    /**
     * The pure NSW-fares engine's own derived
     * [au.com.threesixty.cabdispatch.domain.fare.FareState.maxiRateApplied] — copied over verbatim
     * on every [FareEngineImpl] state update (start + every tick + passenger-count edit), never
     * recomputed independently here. This is the ONLY thing the UI should ever read to decide
     * whether to show a "maxi rate active" indicator — never re-derive it from
     * [passengerCount]/[wheelchairHiring] locally, or a UI-layer bug could show (or hide) the
     * indicator out of step with what is actually being charged.
     */
    val maxiRateApplied: Boolean = false,
    /**
     * "Set Price" / negotiated-fare amount agreed with the passenger BEFORE the meter started
     * (dashboard's Set Price entry point -> [au.com.threesixty.cabdispatch.domain.TripContext.negotiatedTotal]),
     * mirrored verbatim off [au.com.threesixty.cabdispatch.domain.fare.FareState.negotiatedTotal] —
     * copied once at [FareEngineImpl.startTrip], never recomputed here. `null` for every ordinary
     * metered trip (the default, unchanged case).
     *
     * Real fix (2026-09, product-reported): before this field existed, [FareEngineImpl] never
     * learned about a negotiated total at all — [FareState.total] always read the live metered
     * accrual, so a driver who set a $50 fixed price still watched the dial climb from flagfall
     * exactly as if no price had been agreed. The actual bill was always correct (Close & Pay's
     * [au.com.threesixty.cabdispatch.domain.fare.reconstructFareState] already wired
     * `TripEntity.negotiatedTotal` into the pure engine's `close()`, which bills the agreed amount
     * per Act s79(3)) — this was a live-display honesty bug only, the same category as the PSL gap
     * [total] fixes below, not a billing one. See [total]'s doc for how this is now used.
     */
    val negotiatedTotal: BigDecimal? = null,
    /**
     * Auto-detected NSW toll-road crossings currently included in [breakdown]'s tolls total — see
     * [AutoTollEntry]'s own doc. Empty for every trip where the on-device toll registry was never
     * cached (offline-empty-cache fallback) or simply hasn't crossed a real gantry yet — this list
     * growing is the driver's only signal that a toll was added automatically rather than by their
     * own ADD TOLL tap, so [au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen] must render
     * it, not just fold its total silently into [breakdown.tolls].
     */
    val autoTollsApplied: List<AutoTollEntry> = emptyList(),
    /**
     * Real toll roads crossed this trip that the registry genuinely cannot auto-price — see
     * [UnpricedTollRoad]'s own doc. Never auto-charged; existing purely so the driver knows to add
     * a manual toll for these via the existing ADD TOLL flow.
     */
    val unpricedTollRoads: List<UnpricedTollRoad> = emptyList(),
    /**
     * Set on the exact tick a real auto-toll charge is added or revised, `null` otherwise — see
     * [AutoTollAlert]'s own doc. Consumers must key off [AutoTollAlert.id], not nullness alone
     * (this field never resets back to `null` on a later tick — there is nothing further to say
     * once an alert has been shown, so a `LaunchedEffect(state.lastAutoTollAlert?.id)` keyed
     * effect is the correct way to fire exactly once per real event, not an `if (x != null)` gate
     * that would refire on every subsequent unrelated recomposition).
     */
    val lastAutoTollAlert: AutoTollAlert? = null,
    /**
     * True while the newest GPS fix is older than
     * [au.com.threesixty.cabdispatch.domain.FareEngineImpl.MAX_FIX_AGE_MS], or while there is no
     * fix at all -- i.e. the meter currently has no idea whether the vehicle is moving.
     *
     * F3 (architecture audit 2026-09-08, §2.1, rated **blocker**): before this existed,
     * [au.com.threesixty.cabdispatch.domain.location.RealLocationProvider] only ever updated its
     * speed when a fix was *accepted*, and only ever zeroed it when the location permission was
     * lost. So in a tunnel the last speed simply froze and the meter kept accruing against it --
     * enter the Sydney Harbour Tunnel at 80 km/h, lose GPS for four minutes, and the passenger is
     * billed 5.3 km that nobody drove. (Frozen at zero is the mirror-image bug: billing waiting
     * time at 100 km/h.)
     *
     * While this is true [au.com.threesixty.cabdispatch.domain.FareEngineImpl.tick] accrues **no
     * distance at all**, and accrues waiting time only when the last *known* speed was below the
     * threshold -- see that method's own doc for the full rule. It is published here rather than
     * kept private because a meter that silently stops charging is exactly as dishonest as one
     * that silently over-charges: the driver has to be told. A4 owns the dial treatment ("GPS LOST
     * -- waiting time only"); this field is the engine's half of that contract.
     */
    val gpsLost: Boolean = false,
    /**
     * The authoritative running total -- literally
     * [au.com.threesixty.cabdispatch.domain.fare.FareEngine.close]`(...).grandTotal`, recomputed
     * off the shadow calc state on every tick. `null` only before the first
     * [au.com.threesixty.cabdispatch.domain.FareEngineImpl.startTrip] (a [FareState] that has
     * never been near a tariff), in which case [total] falls back to the naive [breakdown] sum.
     *
     * F8 (architecture audit 2026-09-08, §2.2): [FareBreakdown.total] is a plain seven-way sum. It
     * applies no `roundDownToCent` (Act s76(5)/(6)) and, far worse, **no maxi multiplier** -- so on
     * a maxi hiring the live dial under-read the actual bill by a full 50% of the metered base for
     * the whole trip, and only jumped to the real figure at Close & Pay. The driver quoting the
     * dial to the passenger was quoting the wrong number. The pure engine already knew the right
     * answer; nothing was asking it. Now something does, once per tick.
     */
    val runningTotal: BigDecimal? = null,
) {
    /**
     * The full amount the passenger will pay if the trip closed right now — see the class-level
     * "must always show the full amount" requirement this exists to satisfy (2026-09 product
     * report: the dial used to show flagfall only, or the raw metered accrual, and only grew to
     * the true payable figure — PSL included — once the trip actually closed).
     *
     * For an ordinary metered trip this is [FareBreakdown.total] (flagFall + distance + waiting +
     * peak + tolls + PSL + extras — every component already accrues live via [FareEngineImpl.tick]/
     * `.addToll()`; PSL is seeded once at [FareEngineImpl.startTrip] from the tariff, see that
     * method's doc for why it's no longer left at zero for the trip's whole duration).
     *
     * For a "Set Price" trip ([negotiatedTotal] non-null) this is EXACTLY [negotiatedTotal] — full
     * stop, nothing added on top — mirroring the pure engine's own
     * [au.com.threesixty.cabdispatch.domain.fare.FareEngine.close] negotiated-fare branch (2026-09
     * product requirement: "fixed price means, all toll fees everything included ... driver will
     * straight charge $50 or $60 or whatever they decide"). [breakdown.tolls]/[breakdown.psl]/
     * [breakdown.extras] keep accruing underneath exactly as before (still real amounts owed for
     * PSL-ledger remittance / toll audit — see [FareEngine.close]'s doc) but are deliberately
     * excluded here: a driver who agreed $50 must see $50 on the dial the whole trip, never a
     * bigger figure that isn't what the passenger will actually be charged (Act s79(3) — the
     * agreed amount is what's charged, full stop).
     *
     * **F8 (architecture audit 2026-09-08, §2.2) -- where this figure now actually comes from.**
     * Both paragraphs above describe what this property must *mean*; they no longer describe how
     * it is computed. Re-deriving the answer here, by hand, from [breakdown], was the bug: this
     * getter had no `roundDownToCent` and no maxi multiplier, so a maxi trip's dial sat 50% of the
     * metered base below the bill for the trip's whole duration. It now simply *reads*
     * [runningTotal] -- the pure, golden-vector-tested engine's own
     * `close(...).grandTotal`, recomputed each tick by [au.com.threesixty.cabdispatch.domain.FareEngineImpl.tick]
     * -- which already implements every rule this doc describes (the negotiated-fare branch, the
     * levy, the maxi multiplier, the round-down) and is the same code path Close & Pay bills off.
     * One computation, two readers, no drift possible by construction.
     *
     * The two fallbacks behind it are for a [FareState] that has never been ticked -- a default
     * instance, or a UI preview -- and preserve the old behaviour exactly for those.
     */
    val total: BigDecimal
        get() = runningTotal ?: negotiatedTotal ?: breakdown.total
}

/** Formats a decimal-as-string-contract money value for display. Never use
 * Float/Double for the underlying value — see ApiService.kt header comment. */
fun BigDecimal.toMoneyString(): String = "$" + this.setScale(2, RoundingMode.HALF_UP).toPlainString()

/**
 * Display-only "classic meter tick" rounding for the live fare ticker (driver request,
 * 2026-09-05): floors down to the nearest 10 cents so the cents digit always reads "0" and only
 * the dimes digit visibly rolls — the mechanical-meter feel of a fare that ticks over in whole
 * dimes rather than jittering on every cent. **Never** touches the real fare — this is a
 * formatting choice for [au.com.threesixty.cabdispatch.ui.screens.hired.MeterDial]'s ticker only;
 * billing, Close & Pay, receipts, and sync all keep reading [toMoneyString]'s exact-cent value.
 * Floors rather than rounds so the display can never show more than what is actually being
 * charged at that instant.
 */
fun BigDecimal.toMeterDisplayString(): String =
    "$" + this.setScale(1, RoundingMode.FLOOR).setScale(2).toPlainString()
