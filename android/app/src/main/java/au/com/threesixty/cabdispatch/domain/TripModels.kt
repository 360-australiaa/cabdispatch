package au.com.threesixty.cabdispatch.domain

import java.math.BigDecimal
import java.math.RoundingMode

enum class TripStatus { FOR_HIRE, HIRED, STOPPED, CLOSED }
enum class AccrualMode { DISTANCE, WAITING }
enum class TariffBand(val label: String) { BAND_1("Tariff 1"), BAND_2("Tariff 2") }
enum class TimeClass(val label: String) { DAY("Day"), NIGHT("Night"), HOLIDAY("Holiday") }

data class TollPreset(val id: String, val label: String, val amount: BigDecimal)

/**
 * Fixed preset list per spec B5 S3: "toll add buttons (M5, Harbour southbound,
 * airport toll presets)". Amounts are illustrative placeholders (round
 * cash-equivalent figures) — TODO(fleet-config sibling agent): source live
 * figures from tenant tariff/toll config server-side rather than hardcoding.
 */
object TollPresets {
    val M5 = TollPreset("m5", "M5", BigDecimal("4.30"))
    val HARBOUR_SOUTHBOUND = TollPreset("harbour_sb", "Harbour (southbound)", BigDecimal("4.19"))
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
     */
    val total: BigDecimal
        get() = negotiatedTotal ?: breakdown.total
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
