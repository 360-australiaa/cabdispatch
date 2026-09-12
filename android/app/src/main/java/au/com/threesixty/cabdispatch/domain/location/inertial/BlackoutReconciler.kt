package au.com.threesixty.cabdispatch.domain.location.inertial

import java.math.BigDecimal

/**
 * Task 6's "Reconcile at reacquisition": turns whatever [InertialSpeedEstimator] billed tick-by-
 * tick through an INERTIAL blackout into a final, bounded distance, and the one-shot correction
 * `au.com.threesixty.cabdispatch.domain.fare.FareEngine.reconcileBlackoutDistance` applies to make
 * the trip's ledger agree with it.
 *
 * Pure function over already-known figures — no sensors, no Room, no engine dependency — so it is
 * exercised directly with a table of (estimated, chord, roadPath) inputs, the same reasoning
 * [au.com.threesixty.cabdispatch.domain.fare.KnownCorridor]'s `greedyChainDistanceM` is a free
 * function rather than inlined into [au.com.threesixty.cabdispatch.domain.FareEngineImpl].
 *
 * **The rule, verbatim from the plan:** "reference distance = W3's road-path distance between the
 * entry and exit fixes if any source matched, else the straight-line chord. `billed = referenceKm`
 * when a road path matched (the road is the truth); otherwise `billed = clamp(estimatedKm, chordKm,
 * chordKm x 1.5)`." A road-path match is always trusted completely over the estimate — a mapped
 * road's real length is never in question the way an accelerometer integral is. Without one, the
 * estimate is kept, but never allowed below the straight-line minimum (the vehicle cannot have
 * covered less ground than the direct line between where it entered and exited the blackout) nor
 * above 1.5x that minimum (an upper sanity bound on how indirect the untracked road could plausibly
 * have been, matching the same spirit as [au.com.threesixty.cabdispatch.domain.FareEngineImpl]'s
 * own `MAX_DISTANCE_OVERSHOOT_FACTOR` guard against a single bad reading inflating a fare).
 */
object BlackoutReconciler {

    /** Task 6's upper clamp multiple on the chord when no road path matched. */
    const val CHORD_UPPER_BOUND_MULTIPLE = 1.5

    data class Result(
        val billedKm: BigDecimal,
        /** `billedKm - estimatedKm` — the signed delta [FareEngineImpl] applies via
         * `CalcFareEngine.reconcileBlackoutDistance`. Positive means the trip is topped up;
         * negative means part of what was already billed tick-by-tick is refunded. */
        val correctionKm: BigDecimal,
        val referenceSource: ReferenceSource,
        /** The road-path distance that decided [billedKm], when [referenceSource] is [ReferenceSource.ROAD_PATH]. */
        val referenceKm: BigDecimal?,
    )

    enum class ReferenceSource { ROAD_PATH, CHORD_BOUNDED }

    fun reconcile(estimatedKm: BigDecimal, chordKm: BigDecimal, roadPathKm: BigDecimal?): Result {
        val billed: BigDecimal
        val source: ReferenceSource
        if (roadPathKm != null && roadPathKm.signum() > 0) {
            billed = roadPathKm
            source = ReferenceSource.ROAD_PATH
        } else {
            val upperBound = chordKm * BigDecimal.valueOf(CHORD_UPPER_BOUND_MULTIPLE)
            billed = estimatedKm.coerceIn(chordKm, upperBound)
            source = ReferenceSource.CHORD_BOUNDED
        }
        return Result(
            billedKm = billed,
            correctionKm = billed - estimatedKm,
            referenceSource = source,
            referenceKm = roadPathKm?.takeIf { source == ReferenceSource.ROAD_PATH },
        )
    }
}
