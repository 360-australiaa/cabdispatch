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
 * Ported line-for-line from the Python module's logic. Do not "correct" the
 * NSW Fares Order 2025 (no.2) rates below from memory — they are transcribed
 * exactly from the product spec, mirrored field-for-field from URBAN_TARIFF /
 * COUNTRY_TARIFF in the Python file:
 *
 * | Component                                       | Urban          | Country        |
 * |--------------------------------------------------|----------------|----------------|
 * | Hiring Charge (flag fall)                        | $5.00          | $5.11          |
 * | Peak Time Hiring Charge (urban only)              | $2.56          | n/a            |
 * | Distance Rate >=26km/h, first 12km                | $2.52/km       | $2.41/km       |
 * | Distance Rate >=26km/h, beyond 12km               | $2.29/km       | $3.30/km       |
 * | Night Distance Rate (10pm-6am any night), <12km   | $3.00/km       | $2.87/km       |
 * | Night Distance Rate, beyond 12km                  | $2.73/km       | $3.93/km       |
 * | Holiday Distance Rate (country only), <12km       | n/a            | $2.87/km       |
 * | Holiday Distance Rate, beyond 12km                | n/a            | $3.93/km       |
 * | Waiting Time <26km/h                              | 109.2 c/min    | 104.5 c/min    |
 *
 * Fare engine rule: at any instant exactly ONE of Distance Rate or Waiting
 * Time accrues, switched by the 26km/h threshold (never both, never neither,
 * while HIRED). `timeClass` (day/night/holiday) and the peak-hiring flag are
 * fixed at journey commencement and do not change mid-trip even if the clock
 * crosses a boundary. The first-12km/beyond-12km distance band applies to
 * cumulative trip distance.
 *
 * All money math uses [BigDecimal] — never Double/Float — with HALF_UP
 * rounding at exactly the same points as the Python `Decimal`/`ROUND_HALF_UP`
 * calls, so the offline meter and the server compute byte-identical fares.
 */

// --- rounding helpers --------------------------------------------------------

/** Scale used for intermediate (non-final) divisions, e.g. grand_total/11 or
 * pct/100, before the result is quantized to cents. Mirrors the effectively
 * unbounded precision of Python's default Decimal context (28 significant
 * digits) closely enough that the subsequent half-up cent rounding always
 * agrees with the Python engine for real-world fare amounts. */
private const val DIVISION_SCALE = 20

/** Quantize to cents, half-up (<0.5c down, >=0.5c up) — the rounding rule the
 * Fares Order mandates for the non-cash surcharge, applied generally here for
 * all displayed money amounts. Mirrors `round_half_up` in fare_engine.py. */
fun roundHalfUp(amount: BigDecimal): BigDecimal = amount.setScale(2, RoundingMode.HALF_UP)

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

// Default tariffs to ship — NSW Fares Order 2025 (no.2), effective 3 Nov 2025.
val URBAN_TARIFF = Tariff(
    name = "urban-2025-no2",
    area = AreaClass.URBAN,
    flagFall = BigDecimal("5.00"),
    peakCharge = BigDecimal("2.56"),
    distRate1 = BigDecimal("2.52"),
    distRate2 = BigDecimal("2.29"),
    nightRate1 = BigDecimal("3.00"),
    nightRate2 = BigDecimal("2.73"),
    holidayRate1 = BigDecimal(0),
    holidayRate2 = BigDecimal(0),
    waitingRatePerMin = BigDecimal("1.092"),
)

val COUNTRY_TARIFF = Tariff(
    name = "country-2025-no2",
    area = AreaClass.COUNTRY,
    flagFall = BigDecimal("5.11"),
    peakCharge = BigDecimal(0),
    distRate1 = BigDecimal("2.41"),
    distRate2 = BigDecimal("3.30"),
    nightRate1 = BigDecimal("2.87"),
    nightRate2 = BigDecimal("3.93"),
    holidayRate1 = BigDecimal("2.87"),
    holidayRate2 = BigDecimal("3.93"),
    waitingRatePerMin = BigDecimal("1.045"),
)

val AIRPORT_FIXED_FARE_STANDARD: BigDecimal = BigDecimal("60.00")
val AIRPORT_FIXED_FARE_MAXI: BigDecimal = BigDecimal("80.00")

/** Sydney Airport Fixed Fare Trial — non-booked, Airport Precinct to the
 * defined CBD zone. Exactly $60 standard / $80 maxi. Callers must not add
 * PSL, tolls, or peak charge on top — only non-cash surcharge and cleaning
 * fee may be layered on (enforced by [FareEngine.close] when fixedFare is
 * set). */
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
    var maxi: Boolean = false,
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
)

data class FareBreakdown(
    val flagFall: BigDecimal,
    val peakCharge: BigDecimal,
    val distanceCharge: BigDecimal,
    val waitingCharge: BigDecimal,
    val tolls: BigDecimal,
    val psl: BigDecimal,
    val cleaningFee: BigDecimal,
    val extras: BigDecimal,
    val maxiApplied: Boolean,
    val fareTotal: BigDecimal, // after maxi multiplier, before non-cash surcharge
    val surcharge: BigDecimal,
    val grandTotal: BigDecimal, // fareTotal + surcharge — amount actually charged
    val gstComponent: BigDecimal, // grandTotal / 11, half-up to cents
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
        val fixedFare = state.fixedFare
        if (fixedFare != null) {
            // Sydney Airport Fixed Fare Trial: no PSL, tolls, or peak allowed on
            // top — only the non-cash surcharge and a cleaning fee.
            val fareTotal = fixedFare
            var surcharge = BigDecimal.ZERO
            if (paymentMethod == "card") {
                val pct = minOf(surchargePct ?: state.tariff.surchargePctCap, state.tariff.surchargePctCap)
                surcharge = roundHalfUp(fareTotal * pct / BigDecimal(100))
            }
            val grandTotal = fareTotal + surcharge + cleaningFee
            val gstComponent = roundHalfUp(divide(grandTotal, BigDecimal(11)))
            return FareBreakdown(
                flagFall = BigDecimal.ZERO,
                peakCharge = BigDecimal.ZERO,
                distanceCharge = BigDecimal.ZERO,
                waitingCharge = BigDecimal.ZERO,
                tolls = BigDecimal.ZERO,
                psl = BigDecimal.ZERO,
                cleaningFee = cleaningFee,
                extras = BigDecimal.ZERO,
                maxiApplied = state.maxi,
                fareTotal = fareTotal,
                surcharge = surcharge,
                grandTotal = grandTotal,
                gstComponent = gstComponent,
            )
        }

        val flagFall = state.tariff.flagFall
        val peakCharge = if (state.isPeak) state.tariff.peakCharge else BigDecimal.ZERO
        val psl = if (includePsl) state.tariff.pslAmount else BigDecimal.ZERO

        var subtotal = flagFall + peakCharge + state.accruedDistanceCharge + state.accruedWaitingCharge +
            state.tolls + psl + state.extras + cleaningFee

        val maxiApplied = state.maxi
        if (maxiApplied) {
            subtotal *= state.tariff.maxiMultiplier
        }

        val fareTotal = roundHalfUp(subtotal)

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
            cleaningFee = cleaningFee,
            extras = state.extras,
            maxiApplied = maxiApplied,
            fareTotal = fareTotal,
            surcharge = surcharge,
            grandTotal = grandTotal,
            gstComponent = gstComponent,
        )
    }

    /** 75% of the metered fare (fareTotal, i.e. before non-cash surcharge)
     * demanded from EACH hirer — the meter runs once, this is a pure function
     * of a checkpoint breakdown, not a mutation of shared state. */
    fun multiHireAmountOwed(breakdown: FareBreakdown, tariff: Tariff): BigDecimal =
        roundHalfUp(breakdown.fareTotal * tariff.multiHirePct)
}
