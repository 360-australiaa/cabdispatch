package au.com.threesixty.cabdispatch.domain

/**
 * Which charging regime the meter is in, as far as the dial's animation is concerned.
 *
 * ### Why the dial has bands at all
 * The brief was "if the speed is under thirty or forty the animation is different; if they increase
 * the speed the animation is different". Round numbers would have worked visually, but 26 km/h is
 * already the most meaningful speed in this product: it is `Tariff.speedThresholdKmh`, the line
 * where the meter stops charging waiting time and starts charging distance
 * ([au.com.threesixty.cabdispatch.domain.fare.FareEngine]'s `tick`). Banding there means the dial's
 * character tells the driver something true about the fare rather than something decorative about
 * the speedometer.
 *
 * [FAST] is display-only — a sub-band of DISTANCE with no billing meaning. It exists because the
 * owner asked for a visibly different character at higher speed, and 60 km/h is where an urban taxi
 * is on a main road rather than in traffic.
 *
 * ### Hysteresis, and why it is not optional
 * The speed this bands on is smoothed, but the underlying GPS is a 1 Hz staircase with no filtering
 * ([au.com.threesixty.cabdispatch.domain.location.RealLocationProvider] deliberately applies no
 * Kalman). Sitting at 26 km/h in traffic, a plain threshold would flip the whole dial's colour
 * several times a second. Entering a band and leaving it therefore use different thresholds:
 * DISTANCE is entered at the tariff line and left 4 km/h below it; FAST is entered at 60 and left
 * at 55.
 *
 * The cost is that the dial lags [au.com.threesixty.cabdispatch.domain.fare.AccrualMode] by up to a
 * second or so around the threshold. That is the intended trade and worth stating plainly: **the
 * fare engine must be exact, the dial must be calm.** Nothing here feeds a price.
 *
 * Pure Kotlin with no Android or Compose imports, so the policy is exercised by
 * [au.com.threesixty.cabdispatch.domain.SpeedBand] tests on a plain JVM — the same split
 * [DeviceReadiness] and [KioskLockController] use, where a pure decision function sits behind a
 * thin live reader.
 */
enum class SpeedBand(
    /**
     * How energetic this band's animation is, 0..1. The dial interpolates every band-dependent
     * value against this single scalar rather than branching on the band, so a band change is one
     * continuous crossfade instead of a jump.
     */
    val energy: Float,
) {
    /** Under the tariff threshold: the meter is charging waiting time. The dial is calm and, at a
     * standstill, completely static. */
    WAITING(0f),

    /** At or above the tariff threshold: the meter is charging distance. */
    DISTANCE(0.5f),

    /** Well above it. Display-only — see the class doc. */
    FAST(1f);

    companion object {
        /** Fallback when no tariff has been loaded. Matches the fare engine's own default. */
        const val DEFAULT_THRESHOLD_KMH = 26.0

        /** How far below the tariff threshold the dial drops back out of [DISTANCE]. */
        const val DISTANCE_EXIT_MARGIN_KMH = 4.0

        const val FAST_ENTER_KMH = 60.0
        const val FAST_EXIT_KMH = 55.0

        /** The band for a tablet that has just started showing a dial, with no previous state. */
        fun initial(speedKmh: Double, thresholdKmh: Double = DEFAULT_THRESHOLD_KMH): SpeedBand =
            next(WAITING, speedKmh, thresholdKmh)

        /**
         * The band to show now, given the one currently showing and a fresh smoothed speed.
         *
         * A pure function of those two things — no time, no rate-limiting, no internal state — so a
         * test can drive it with a list of samples and assert on the whole sequence.
         *
         * Note the jumps are honoured in both directions: a hard brake from [FAST] goes straight to
         * [WAITING] rather than stepping through [DISTANCE], and pulling away hard goes straight to
         * [FAST]. Hysteresis is there to stop flapping at a boundary, not to slow down a real
         * change.
         */
        fun next(
            prev: SpeedBand,
            speedKmh: Double,
            thresholdKmh: Double = DEFAULT_THRESHOLD_KMH,
        ): SpeedBand {
            // A stopped vehicle is always WAITING, before any hysteresis is considered.
            //
            // Not merely defensive. The whole calm-motion design rests on the dial being completely
            // static at a standstill, and hysteresis alone cannot guarantee that: a tariff whose
            // threshold is below DISTANCE_EXIT_MARGIN_KMH clamps the exit to 0, and `0 < 0` is
            // false, which would strand a parked taxi showing distance-rate character with a live
            // ember on the ring. Stating the invariant here is cheaper than reasoning about the
            // margin arithmetic every time the thresholds are touched.
            if (speedKmh <= 0.0) return WAITING

            val enterDistance = thresholdKmh
            val exitDistance = (thresholdKmh - DISTANCE_EXIT_MARGIN_KMH).coerceAtLeast(0.0)
            return when (prev) {
                WAITING -> when {
                    speedKmh >= FAST_ENTER_KMH -> FAST
                    // >= matches FareEngine.tick's own comparison, so the dial changes character on
                    // exactly the sample where the meter changes what it is charging.
                    speedKmh >= enterDistance -> DISTANCE
                    else -> WAITING
                }
                DISTANCE -> when {
                    speedKmh >= FAST_ENTER_KMH -> FAST
                    speedKmh < exitDistance -> WAITING
                    else -> DISTANCE
                }
                FAST -> when {
                    speedKmh >= FAST_EXIT_KMH -> FAST
                    speedKmh >= exitDistance -> DISTANCE
                    else -> WAITING
                }
            }
        }
    }
}
