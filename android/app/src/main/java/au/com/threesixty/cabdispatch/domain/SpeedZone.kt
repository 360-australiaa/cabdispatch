package au.com.threesixty.cabdispatch.domain

/**
 * The speedometer's "gaming" colour zones (owner request, 2026-09-14: "0-30 low colour, 40-80
 * good colour, 80+ caution, with shine/shades/effects"). Fixed road-speed zones, deliberately
 * separate from [SpeedBand]: that one is the TARIFF's waiting/distance line (what the passenger
 * is being charged), this one is what the ring and the big km/h figure are coloured by. The
 * 30-40 gap in the owner's wording is the LOW->GOOD blend on the ring; the readout switches at
 * the zone's own boundary with a small hysteresis so a speed hovering on a boundary never
 * flickers between two colours.
 */
enum class SpeedZone {
    /** 0-30 km/h: rolling, kerb, car park -- a cool, calm colour. */
    LOW,

    /** 30-80 km/h: the ordinary driving range -- the "good" colour. */
    GOOD,

    /** 80 km/h and above -- caution; motorway speeds, and the fixed-camera range. */
    CAUTION;

    companion object {
        const val GOOD_FROM_KMH = 30.0
        const val CAUTION_FROM_KMH = 80.0

        /** Boundary hysteresis, km/h: a zone is left only once the speed clears the boundary by
         * this much in the other direction. GPS speed jitters by 1-2 km/h at a steady cruise. */
        const val HYSTERESIS_KMH = 2.0

        fun initial(speedKmh: Double): SpeedZone = when {
            speedKmh >= CAUTION_FROM_KMH -> CAUTION
            speedKmh >= GOOD_FROM_KMH -> GOOD
            else -> LOW
        }

        fun next(prev: SpeedZone, speedKmh: Double): SpeedZone = when (prev) {
            LOW -> if (speedKmh >= GOOD_FROM_KMH) initial(speedKmh) else LOW
            GOOD -> when {
                speedKmh >= CAUTION_FROM_KMH -> CAUTION
                speedKmh < GOOD_FROM_KMH - HYSTERESIS_KMH -> LOW
                else -> GOOD
            }
            CAUTION -> if (speedKmh < CAUTION_FROM_KMH - HYSTERESIS_KMH) initial(speedKmh) else CAUTION
        }
    }
}
