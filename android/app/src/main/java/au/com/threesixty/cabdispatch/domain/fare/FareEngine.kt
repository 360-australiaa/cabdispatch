package au.com.threesixty.cabdispatch.domain.fare

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * NSW tariff-switching fare engine — pure Kotlin port of the backend reference
 * implementation at `backend/app/services/fare_engine.py`. No Android
 * framework dependency, no Room, no network: this file is plain-JVM
 * unit-testable and MUST stay that way so it can run on a CI box with no
 * emulator, exactly like the Python original runs with no DB/FastAPI import.
 *
 * Ported line-for-line from the Python module's logic. The rate card below is
 * the **Point to Point Transport (Fares) Order 2026**, effective 1 June 2026
 * (mirrored field-for-field from URBAN_TARIFF / COUNTRY_TARIFF in the Python
 * file — do not "correct" it from memory, it is transcribed exactly from the
 * gazetted Order):
 *
 * | Component                                       | Urban          | Country        |
 * |--------------------------------------------------|----------------|----------------|
 * | Hiring Charge (flag fall)                        | $5.17          | $5.29          |
 * | Peak Time Hiring Charge (urban only)              | $2.65          | n/a            |
 * | Distance Rate >=26km/h, first 12km                | $2.61/km       | $2.49/km       |
 * | Distance Rate >=26km/h, beyond 12km               | $2.37/km       | $3.41/km       |
 * | Night Distance Rate (10pm-6am any night), <12km   | $3.10/km       | $2.97/km       |
 * | Night Distance Rate, beyond 12km                  | $2.82/km       | $4.07/km       |
 * | Holiday Distance Rate (country only), <12km       | n/a            | $2.97/km       |
 * | Holiday Distance Rate, beyond 12km                | n/a            | $4.07/km       |
 * | Waiting Time <26km/h                              | 113.0 c/min    | 108.1 c/min    |
 * | Cleaning fee cap                                  | $124.14        | $124.14        |
 *
 * Superseded reference (kept only as a historical changelog comment, NOT
 * live — do not resurrect): NSW Fares Order 2025 (no.2), effective 3 Nov 2025 —
 * urban flag fall $5.00 / peak $2.56 / dist $2.52,$2.29 / night $3.00,$2.73 /
 * waiting 109.2c; country flag fall $5.11 / dist $2.41,$3.30 / night+holiday
 * $2.87,$3.93 / waiting 104.5c.
 *
 * Fare engine rule: at any instant exactly ONE of Distance Rate or Waiting
 * Time accrues, switched by the 26km/h threshold (never both, never neither,
 * while HIRED). `timeClass` (day/night/holiday) and the peak-hiring flag are
 * fixed at journey commencement and do not change mid-trip even if the clock
 * crosses a boundary. The first-12km/beyond-12km distance band applies to
 * cumulative trip distance.
 *
 * All money math uses [BigDecimal] — never Double/Float — with rounding rules
 * matched to the Fares Order (see [roundHalfUp] for the non-cash surcharge
 * and GST-component rule, [roundDownToCent] for the final fare total per
 * Act s76(5)/(6) — the regulated maximum must never be exceeded, and
 * rounding UP could push a computed fare a cent over it) so the offline
 * meter and the server compute byte-identical, always-lawful fares.
 */

// --- rounding helpers --------------------------------------------------------

/** Scale used for intermediate (non-final) divisions, e.g. grand_total/11 or
 * pct/100, before the result is quantized to cents. Mirrors the effectively
 * unbounded precision of Python's default Decimal context (28 significant
 * digits) closely enough that the subsequent cent rounding always agrees
 * with the Python engine for real-world fare amounts. */
private const val DIVISION_SCALE = 20

/** Quantize to cents, half-up (<0.5c down, >=0.5c up) — the Fares Order's
 * stated rounding rule for the non-cash payment surcharge (cl 4(a)) and used
 * generally here for the GST-component display figure. Mirrors
 * `round_half_up` in fare_engine.py. Deliberately NOT used for [FareBreakdown.fareTotal]
 * any more — see [roundDownToCent]. */
fun roundHalfUp(amount: BigDecimal): BigDecimal = amount.setScale(2, RoundingMode.HALF_UP)

/**
 * Quantize to cents, always rounding DOWN (truncating), never up — the rule
 * for [FareBreakdown.fareTotal] per Act s76(5)/(6): the regulated maximum
 * fare must never be exceeded, and rounding a computed subtotal UP even by a
 * single cent could do exactly that. Rounding down is always lawful (it can
 * only ever charge less than or equal to what was actually accrued), so this
 * is the one rounding rule this engine treats as non-negotiable for the
 * amount actually billed.
 */
fun roundDownToCent(amount: BigDecimal): BigDecimal = amount.setScale(2, RoundingMode.DOWN)

private fun divide(a: BigDecimal, b: BigDecimal): BigDecimal = a.divide(b, DIVISION_SCALE, RoundingMode.HALF_UP)

/** Mirrors Python's `_d(value) = Decimal(str(value))` — accepts Int/Long/
 * Double/BigDecimal etc. and converts via the same string round-trip so
 * literal call sites (e.g. `tick(state, 40, 3, 270)`) behave identically to
 * the Python engine's implicit coercion. */
private fun d(value: Number): BigDecimal = if (value is BigDecimal) value else BigDecimal(value.toString())

// --- time classification ------------------------------------------------------

enum class TimeClass {
    DAY,
    NIGHT, // 10pm-6am, any night (urban or country)
    HOLIDAY, // country only: Sun/public holiday 6am-10pm
}

enum class AreaClass {
    URBAN,
    COUNTRY,
}

/** Raised by [validateAgainstFaresOrder] when a rank/hail tariff's rates
 * exceed the regulated Fares Order reference. */
class FaresOrderViolation(message: String) : IllegalArgumentException(message)

// --- Tariff -------------------------------------------------------------------

data class Tariff(
    val name: String,
    val area: AreaClass,
    val flagFall: BigDecimal,
    val peakCharge: BigDecimal, // 0 where not applicable (country)
    val distRate1: BigDecimal,
    val distRate2: BigDecimal,
    val nightRate1: BigDecimal,
    val nightRate2: BigDecimal,
    val holidayRate1: BigDecimal, // 0 where not applicable (urban)
    val holidayRate2: BigDecimal, // 0 where not applicable (urban)
    val waitingRatePerMin: BigDecimal,
    val distKmThreshold: BigDecimal = BigDecimal(12),
    val speedThresholdKmh: BigDecimal = BigDecimal(26),
    val maxiMultiplier: BigDecimal = BigDecimal("1.5"),
    val multiHirePct: BigDecimal = BigDecimal("0.75"),
    val pslAmount: BigDecimal = BigDecimal("1.32"),
    val surchargePctCap: BigDecimal = BigDecimal("5.0"),
    /** Point to Point Transport (Fares) Order 2026 cap on a trip's cleaning fee
     * (soiling charge) — a flat cap, not per-tariff-configurable server-side
     * today (the wire [au.com.threesixty.cabdispatch.data.remote.TariffDto]
     * carries no such field yet), so every [Tariff] uses this same default. */
    val cleaningFeeCap: BigDecimal = BigDecimal("124.14"),
)

/** The 9 regulated rate fields checked by [validateAgainstFaresOrder], in the
 * same order as Python's `Tariff._RATE_FIELDS`. */
private fun rateFields(t: Tariff): List<Pair<String, BigDecimal>> = listOf(
    "flag_fall" to t.flagFall,
    "peak_charge" to t.peakCharge,
    "dist_rate_1" to t.distRate1,
    "dist_rate_2" to t.distRate2,
    "night_rate_1" to t.nightRate1,
    "night_rate_2" to t.nightRate2,
    "holiday_rate_1" to t.holidayRate1,
    "holiday_rate_2" to t.holidayRate2,
    "waiting_rate_per_min" to t.waitingRatePerMin,
)

// Default tariffs to ship — Point to Point Transport (Fares) Order 2026,
// effective 1 June 2026.
val URBAN_TARIFF = Tariff(
    name = "urban-2026",
    area = AreaClass.URBAN,
    flagFall = BigDecimal("5.17"),
    peakCharge = BigDecimal("2.65"),
    distRate1 = BigDecimal("2.61"),
    distRate2 = BigDecimal("2.37"),
    nightRate1 = BigDecimal("3.10"),
    nightRate2 = BigDecimal("2.82"),
    holidayRate1 = BigDecimal(0),
    holidayRate2 = BigDecimal(0),
    waitingRatePerMin = BigDecimal("1.130"),
)

val COUNTRY_TARIFF = Tariff(
    name = "country-2026",
    area = AreaClass.COUNTRY,
    flagFall = BigDecimal("5.29"),
    peakCharge = BigDecimal(0),
    distRate1 = BigDecimal("2.49"),
    distRate2 = BigDecimal("3.41"),
    nightRate1 = BigDecimal("2.97"),
    nightRate2 = BigDecimal("4.07"),
    holidayRate1 = BigDecimal("2.97"),
    holidayRate2 = BigDecimal("4.07"),
    waitingRatePerMin = BigDecimal("1.081"),
)

val AIRPORT_FIXED_FARE_STANDARD: BigDecimal = BigDecimal("60.00")
val AIRPORT_FIXED_FARE_MAXI: BigDecimal = BigDecimal("80.00")

/** Sydney Airport Fixed Fare Trial — non-booked, Airport Precinct to the
 * defined CBD zone. Exactly $60 standard / $80 maxi (unchanged by the 2026
 * Order). Callers must not add PSL, tolls, or peak charge on top — only
 * non-cash surcharge and cleaning fee may be layered on (enforced by
 * [FareEngine.close] when fixedFare is set). [maxi] here must be
 * [FareState.maxiRateApplied] (the fully-derived flag), not a raw UI toggle —
 * see that property's doc for why a $80 maxi airport fare is already the
 * regulated flat figure, not `fixed * 1.5`. */
fun airportFixedFare(maxi: Boolean): BigDecimal = if (maxi) AIRPORT_FIXED_FARE_MAXI else AIRPORT_FIXED_FARE_STANDARD

/** Rank/hail tariffs must not exceed the regulated Fares Order reference
 * rates. Booked tariffs are unregulated and skip this check entirely. */
fun validateAgainstFaresOrder(tariff: Tariff, faresOrderReference: Tariff, booked: Boolean = false) {
    if (booked) return
    val candidates = rateFields(tariff)
    val caps = rateFields(faresOrderReference).toMap()
    for ((fieldName, candidate) in candidates) {
        val cap = caps.getValue(fieldName)
        if (candidate > cap) {
            throw FaresOrderViolation(
                "Rank/hail tariff '${tariff.name}' field '$fieldName' = $candidate exceeds " +
                    "Fares Order reference '${faresOrderReference.name}' cap $cap",
            )
        }
    }
}

// --- Journey state --------------------------------------------------------------

/** Mutable per-journey state — mirrors Python's `FareState` dataclass
 * (not frozen: [FareEngine.tick] mutates it in place and returns it). */
class FareState(
    val tariff: Tariff,
    var timeClass: TimeClass = TimeClass.DAY,
    var isPeak: Boolean = false, // urban-only peak hiring charge flag, fixed at commencement
    /** Vehicle has 5+ seats excluding the driver — one of four inputs to
     * [maxiRateApplied], see that property's doc. Renamed from the old raw
     * `maxi: Boolean` (which used to directly gate the 150% multiplier on its
     * own) so a vehicle simply being maxi-capable is no longer, by itself,
     * enough to charge the maxi rate. */
    var isMaxiVehicle: Boolean = false,
    /** Number of passengers carried, including any in a wheelchair. Defaults
     * to 1 (the ordinary single-passenger case) so every existing call site
     * that never set this keeps behaving exactly as before (maxi never
     * eligible unless explicitly set up otherwise). */
    var passengerCount: Int = 1,
    /** True when the hiring is for a passenger travelling in a wheelchair —
     * per the Fares Order, this carve-out means the maxi rate is NOT charged
     * even if the vehicle is a maxi and carries 5+ people (a wheelchair
     * hiring is charged at the ordinary rate, full stop). */
    var wheelchairHiring: Boolean = false,
    /** True only when the hirer specifically requested a maxi taxi at a
     * Sydney Airport rank — the one scenario where the maxi rate applies
     * independent of [passengerCount]. */
    var airportRankRequestedMaxi: Boolean = false,
    var hired: Boolean = true,
    var cumulativeDistanceKm: BigDecimal = BigDecimal.ZERO,
    var accruedDistanceCharge: BigDecimal = BigDecimal.ZERO,
    var accruedWaitingCharge: BigDecimal = BigDecimal.ZERO,
    var lastMode: String? = null, // "distance" | "waiting" — introspection only
    var tolls: BigDecimal = BigDecimal.ZERO,
    var extras: BigDecimal = BigDecimal.ZERO,
    // Sydney Airport Fixed Fare Trial: when set, close() returns exactly this
    // amount (60/80) plus ONLY surcharge/cleaning fee — no PSL, tolls, or peak.
    var fixedFare: BigDecimal? = null,
    /**
     * "Set Price" / negotiated-fare total agreed with the passenger before
     * the trip (Act s79(3): a driver must never demand more than the agreed
     * amount). When non-null, [FareEngine.close] charges exactly this amount
     * for the metered-fare component instead of the accrued
     * flagFall+peak+distance+waiting total — see that method's doc. The
     * meter still accrues [accruedDistanceCharge]/[accruedWaitingCharge]
     * normally so the "what the meter would have charged" reference stays
     * available for display; it is simply not what gets billed.
     */
    var negotiatedTotal: BigDecimal? = null,
) {
    /**
     * Single source of truth for whether the maxi (150%) rate applies.
     * Deliberately a computed property, not a settable field — a UI layer
     * must never be able to set this directly, only the four inputs it is
     * derived from, so the app can never be coerced (by a bug or a malicious
     * client) into an unlawful over-charge. Per the Fares Order: a maxi
     * vehicle carrying 5+ passengers is charged the maxi rate, UNLESS the
     * hiring is for a wheelchair passenger (always ordinary rate regardless
     * of headcount); a maxi taxi specifically requested at a Sydney Airport
     * rank is charged the maxi rate regardless of how many passengers it
     * actually carries. A non-maxi vehicle can never charge the maxi rate no
     * matter how many passengers it (unlawfully) carries.
     */
    val maxiRateApplied: Boolean
        get() = isMaxiVehicle && !wheelchairHiring && (passengerCount >= 5 || airportRankRequestedMaxi)
}

data class FareBreakdown(
    val flagFall: BigDecimal,
    val peakCharge: BigDecimal,
    val distanceCharge: BigDecimal,
    val waitingCharge: BigDecimal,
    val tolls: BigDecimal,
    val psl: BigDecimal,
    val cleaningFee: BigDecimal,
    val extras: BigDecimal,
    val maxiRateApplied: Boolean,
    val fareTotal: BigDecimal, // after maxi multiplier / negotiated override, before non-cash surcharge
    val surcharge: BigDecimal,
    val grandTotal: BigDecimal, // fareTotal + surcharge — amount actually charged
    val gstComponent: BigDecimal, // grandTotal / 11, half-up to cents
    /** Non-null only for a negotiated ("Set Price") trip — the agreed amount that was actually
     * charged in place of the metered accrual. [flagFall]/[distanceCharge]/[waitingCharge]/
     * [peakCharge] above still reflect what the meter itself accrued, for reference/display —
     * they are simply not what [fareTotal] is derived from when this is set. */
    val negotiatedTotal: BigDecimal? = null,
)

/** Stateless engine — all mutable state lives in the [FareState] passed in. */
class FareEngine {

    private fun rate1(state: FareState): BigDecimal = when (state.timeClass) {
        TimeClass.NIGHT -> state.tariff.nightRate1
        TimeClass.HOLIDAY -> state.tariff.holidayRate1
        TimeClass.DAY -> state.tariff.distRate1
    }

    private fun rate2(state: FareState): BigDecimal = when (state.timeClass) {
        TimeClass.NIGHT -> state.tariff.nightRate2
        TimeClass.HOLIDAY -> state.tariff.holidayRate2
        TimeClass.DAY -> state.tariff.distRate2
    }

    /** Advances the meter by one increment. Exactly one of distance-rate or
     * waiting-rate accrues per tick, switched on [speedKmh] vs
     * `tariff.speedThresholdKmh`. No-op while not hired. Accepts any [Number]
     * (Int/Long/Double/BigDecimal/...) for the three measurements, converted
     * via the same string round-trip Python's `_d()` uses. */
    fun tick(
        state: FareState,
        speedKmh: Number,
        distanceDeltaKm: Number,
        elapsedSeconds: Number,
    ): FareState {
        if (!state.hired) return state

        val speed = d(speedKmh)
        val distanceDelta = d(distanceDeltaKm)
        val elapsed = d(elapsedSeconds)

        if (speed >= state.tariff.speedThresholdKmh) {
            // --- distance mode: split the delta across the 12km band boundary ---
            state.lastMode = "distance"
            if (distanceDelta <= BigDecimal.ZERO) return state
            var remaining = distanceDelta
            var cum = state.cumulativeDistanceKm
            val threshold = state.tariff.distKmThreshold
            var charge = BigDecimal.ZERO

            if (cum < threshold) {
                val portion1 = minOf(remaining, threshold - cum)
                charge += portion1 * rate1(state)
                remaining -= portion1
                cum += portion1
            }

            if (remaining > BigDecimal.ZERO) {
                charge += remaining * rate2(state)
                cum += remaining
            }

            state.cumulativeDistanceKm = cum
            state.accruedDistanceCharge += charge
        } else {
            // --- waiting mode: time-based, distance covered is negligible but
            // still folded into cumulative distance so the band tracks reality ---
            state.lastMode = "waiting"
            val minutes = divide(elapsed, BigDecimal(60))
            state.accruedWaitingCharge += minutes * state.tariff.waitingRatePerMin
            state.cumulativeDistanceKm += distanceDelta
        }

        return state
    }

    /** Clamps a requested cleaning fee to the tariff's regulated cap — never
     * silently charges more than [Tariff.cleaningFeeCap] regardless of what a
     * caller passes in. */
    private fun clampCleaningFee(tariff: Tariff, cleaningFee: BigDecimal): BigDecimal =
        cleaningFee.coerceIn(BigDecimal.ZERO, tariff.cleaningFeeCap)

    /** Assembles the final (or checkpoint — this method does not mutate
     * [state], so it may safely be called mid-trip e.g. at each hirer's
     * drop-off in a multiple-hiring scenario) fare breakdown. */
    fun close(
        state: FareState,
        paymentMethod: String = "cash",
        surchargePct: BigDecimal? = null,
        cleaningFee: BigDecimal = BigDecimal.ZERO,
        includePsl: Boolean = false,
    ): FareBreakdown {
        val cappedCleaningFee = clampCleaningFee(state.tariff, cleaningFee)

        val fixedFare = state.fixedFare
        if (fixedFare != null) {
            // Sydney Airport Fixed Fare Trial: no PSL, tolls, or peak allowed on
            // top — only the non-cash surcharge and a cleaning fee. The flat
            // $60/$80 figure is never itself re-multiplied by the maxi
            // multiplier here — [fixedFare] is expected to already be
            // `airportFixedFare(state.maxiRateApplied)`, i.e. the regulated
            // flat maxi figure, not `standard * 1.5`.
            val fareTotal = fixedFare
            var surcharge = BigDecimal.ZERO
            if (paymentMethod == "card") {
                val pct = minOf(surchargePct ?: state.tariff.surchargePctCap, state.tariff.surchargePctCap)
                surcharge = roundHalfUp(fareTotal * pct / BigDecimal(100))
            }
            val grandTotal = fareTotal + surcharge + cappedCleaningFee
            val gstComponent = roundHalfUp(divide(grandTotal, BigDecimal(11)))
            return FareBreakdown(
                flagFall = BigDecimal.ZERO,
                peakCharge = BigDecimal.ZERO,
                distanceCharge = BigDecimal.ZERO,
                waitingCharge = BigDecimal.ZERO,
                tolls = BigDecimal.ZERO,
                psl = BigDecimal.ZERO,
                cleaningFee = cappedCleaningFee,
                extras = BigDecimal.ZERO,
                maxiRateApplied = state.maxiRateApplied,
                fareTotal = fareTotal,
                surcharge = surcharge,
                grandTotal = grandTotal,
                gstComponent = gstComponent,
                negotiatedTotal = null,
            )
        }

        val flagFall = state.tariff.flagFall
        val peakCharge = if (state.isPeak) state.tariff.peakCharge else BigDecimal.ZERO
        val psl = if (includePsl) state.tariff.pslAmount else BigDecimal.ZERO

        // "The fare" per the Fares Order — flagfall + peak charge + distance
        // charge + waiting charge — is the ONLY component the 150% maxi
        // multiplier applies to. Tolls/PSL/extras/cleaning fee are added on
        // top afterwards, unmultiplied (Fix 1).
        var meteredFare = flagFall + peakCharge + state.accruedDistanceCharge + state.accruedWaitingCharge
        val maxiRateApplied = state.maxiRateApplied
        if (maxiRateApplied) {
            meteredFare *= state.tariff.maxiMultiplier
        }

        // Negotiated ("Set Price") trips bill the agreed amount instead of the
        // metered accrual (Act s79(3): never demand more than what was agreed)
        // — everything else (tolls/PSL/extras/cleaning fee/surcharge/GST)
        // still computes and adds on top exactly as it does for a metered
        // trip (Fix 7). The metered accrual itself is untouched above, so
        // [FareBreakdown.flagFall]/[distanceCharge]/[waitingCharge]/[peakCharge]
        // still show what the meter would have charged, for reference.
        val negotiatedTotal = state.negotiatedTotal
        val effectiveFare = negotiatedTotal ?: meteredFare

        val subtotal = effectiveFare + state.tolls + psl + state.extras + cappedCleaningFee

        val fareTotal = roundDownToCent(subtotal)

        var surcharge = BigDecimal.ZERO
        if (paymentMethod == "card") {
            val pct = minOf(surchargePct ?: state.tariff.surchargePctCap, state.tariff.surchargePctCap)
            surcharge = roundHalfUp(fareTotal * pct / BigDecimal(100))
        }

        val grandTotal = fareTotal + surcharge
        val gstComponent = roundHalfUp(divide(grandTotal, BigDecimal(11)))

        return FareBreakdown(
            flagFall = flagFall,
            peakCharge = peakCharge,
            distanceCharge = roundHalfUp(state.accruedDistanceCharge),
            waitingCharge = roundHalfUp(state.accruedWaitingCharge),
            tolls = state.tolls,
            psl = psl,
            cleaningFee = cappedCleaningFee,
            extras = state.extras,
            maxiRateApplied = maxiRateApplied,
            fareTotal = fareTotal,
            surcharge = surcharge,
            grandTotal = grandTotal,
            gstComponent = gstComponent,
            negotiatedTotal = negotiatedTotal,
        )
    }

    /** 75% of the metered fare (fareTotal, i.e. before non-cash surcharge)
     * demanded from EACH hirer — the meter runs once, this is a pure function
     * of a checkpoint breakdown, not a mutation of shared state. Rounding
     * here is unchanged (half-up) — only [FareBreakdown.fareTotal] itself
     * moved to round-down (Fix 3); this is a secondary split of an
     * already-rounded-down figure, not the regulated maximum itself. */
    fun multiHireAmountOwed(breakdown: FareBreakdown, tariff: Tariff): BigDecimal =
        roundHalfUp(breakdown.fareTotal * tariff.multiHirePct)
}
