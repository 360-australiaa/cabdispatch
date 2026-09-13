package au.com.threesixty.cabdispatch.domain.fare

import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Reconstructs a checkpoint [FareState] from a persisted [TripEntity] plus
 * its [Tariff], so S4 (Close & Pay) and S5 (Shift report) can call
 * [FareEngine.close] without needing access to the *live*, in-memory
 * ticking state S3 held while HIRED — that object
 * ([au.com.threesixty.cabdispatch.domain.FareEngineImpl]'s `FareState`, a
 * different type in a different package, see the note in AppContainer.kt) is
 * not persisted; only its running totals are, via [TripEntity.distanceM] /
 * [TripEntity.waitingS].
 *
 * This is mathematically EXACT, not an approximation: because the meter only
 * ever *adds* to cumulative distance while HIRED (never removes), the final
 * distance-charge for a total of D km is always
 * `min(D, threshold) * rate1 + max(D - threshold, 0) * rate2` regardless of
 * how many increments it took to get there — [FareEngine.tick]'s incremental
 * band-splitting is commutative for a monotonically increasing cumulative
 * total. Likewise total waiting charge is linear in total waiting seconds.
 * So re-deriving these two accrued totals from the trip's final persisted
 * counters reproduces exactly what [FareEngine.tick] would have accumulated
 * live, tick-by-tick.
 *
 * TODO(integration agent): if a future TripRepository change persists the
 * running FareState (or its two accrued totals) directly on TripEntity
 * instead of only distanceM/waitingS, prefer reading those fields over this
 * reconstruction — it exists only because they aren't there today.
 */
fun reconstructFareState(trip: TripEntity, tariff: Tariff): FareState {
    val cumulativeDistanceKm = BigDecimal(trip.distanceM)
        .divide(BigDecimal(1000), 10, RoundingMode.HALF_UP)

    val timeClass = when (trip.timeClass.lowercase()) {
        "night" -> TimeClass.NIGHT
        "holiday" -> TimeClass.HOLIDAY
        else -> TimeClass.DAY
    }

    val rate1 = when (timeClass) {
        TimeClass.NIGHT -> tariff.nightRate1
        TimeClass.HOLIDAY -> tariff.holidayRate1
        TimeClass.DAY -> tariff.distRate1
    }
    val rate2 = when (timeClass) {
        TimeClass.NIGHT -> tariff.nightRate2
        TimeClass.HOLIDAY -> tariff.holidayRate2
        TimeClass.DAY -> tariff.distRate2
    }

    val threshold = tariff.distKmThreshold
    val band1Km = cumulativeDistanceKm.min(threshold)
    val band2Km = (cumulativeDistanceKm - threshold).coerceAtLeast(BigDecimal.ZERO)

    // F9 (architecture audit §2.2): prefer the charges the live meter actually accrued, and only
    // re-derive them when they genuinely are not there.
    //
    // The derivation below is exact given its inputs -- see this file's own doc -- but its inputs
    // are lossy: [TripEntity.distanceM] is integer metres, re-rounded on every persist, and
    // `waitingS` is whole seconds. So the closing fare was reconstructed from a rounded shadow of
    // what the meter charged, rather than from the charge itself. Since schema v11 the real figures
    // travel on the row ([TripEntity.accruedDistanceCharge]), so the round-trip is simply skipped.
    //
    // A `"0"`/absent column means one of exactly two things, and the fallback is right for both: a
    // row written before v11 (its distanceM/waitingS are the only record that exists), or a trip
    // that genuinely accrued nothing yet. Neither can be distinguished from the other here, and
    // neither needs to be -- the derivation returns zero for a zero-distance trip anyway.
    val persistedDistanceCharge = trip.accruedDistanceCharge.toBigDecimalOrZero()
    val accruedDistanceCharge = if (persistedDistanceCharge.signum() > 0) {
        persistedDistanceCharge
    } else {
        band1Km * rate1 + band2Km * rate2
    }

    val waitingMinutes = BigDecimal(trip.waitingS)
        .divide(BigDecimal(60), 10, RoundingMode.HALF_UP)
    val persistedWaitingCharge = trip.accruedWaitingCharge.toBigDecimalOrZero()
    val accruedWaitingCharge = if (persistedWaitingCharge.signum() > 0) {
        persistedWaitingCharge
    } else {
        waitingMinutes * tariff.waitingRatePerMin
    }

    val state = FareState(
        tariff = tariff,
        timeClass = timeClass,
        isPeak = trip.isPeak,
        // TripEntity.maxi means "vehicle has 5+ seats excl. driver" per Fix 2 — the actual
        // maxi-RATE eligibility is derived from this plus passengerCount/wheelchairHiring/
        // airportRankRequestedMaxi, see FareState.maxiRateApplied's doc. No UI call site sets
        // passengerCount/wheelchairHiring to anything but their defaults yet (1 / false), so this
        // reconstruction is behaviourally unchanged until a future UI pass wires them up.
        isMaxiVehicle = trip.maxi,
        passengerCount = trip.passengerCount,
        wheelchairHiring = trip.wheelchairHiring,
        hired = true,
        cumulativeDistanceKm = cumulativeDistanceKm,
        accruedDistanceCharge = accruedDistanceCharge,
        accruedWaitingCharge = accruedWaitingCharge,
        tolls = trip.tolls.toBigDecimalOrZero(),
        extras = trip.extras.toBigDecimalOrZero(),
        negotiatedTotal = trip.negotiatedTotal?.toBigDecimalOrZero(),
    )

    // Sydney Airport Fixed Fare Trial (spec B5 "Airport mode") — close()
    // ignores every other accrued field once fixedFare is set, per its doc.
    // Uses the fully-derived maxiRateApplied (not the raw isMaxiVehicle flag)
    // so a $80 maxi airport fare is only ever charged when the maxi rate is
    // genuinely lawful for this hiring (Fix 2).
    if (trip.type == "airport_fixed") {
        state.fixedFare = airportFixedFare(state.maxiRateApplied)
    }

    return state
}

private fun String.toBigDecimalOrZero(): BigDecimal = runCatching { BigDecimal(this) }.getOrDefault(BigDecimal.ZERO)
