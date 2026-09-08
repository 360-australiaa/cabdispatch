package au.com.threesixty.cabdispatch.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The meter dial's animation bands.
 *
 * The property that matters most is the one in `does not flap across the tariff threshold`: the
 * speed feeding this is a 1 Hz GPS staircase with no filtering, so a plain threshold would repaint
 * the whole dial several times a second for a taxi sitting at 26 km/h in traffic. That is the
 * failure this policy exists to prevent, and it is the reason the thresholds are asymmetric.
 *
 * Plain JVM, no Compose — same split as [DeviceReadinessTest] and [KioskLockControllerTest].
 */
class SpeedBandTest {

    @Test
    fun `a stopped taxi is charging waiting time`() {
        assertEquals(SpeedBand.WAITING, SpeedBand.next(SpeedBand.WAITING, 0.0))
        assertEquals(SpeedBand.WAITING, SpeedBand.next(SpeedBand.WAITING, 25.9))
    }

    @Test
    fun `the dial changes on exactly the sample the meter changes what it charges`() {
        // >= 26, matching FareEngine.tick's own comparison. A dial that switched at 26.1 would be
        // showing waiting-time character while the meter billed distance.
        assertEquals(SpeedBand.DISTANCE, SpeedBand.next(SpeedBand.WAITING, 26.0))
    }

    @Test
    fun `DISTANCE is held through the hysteresis gap`() {
        assertEquals(SpeedBand.DISTANCE, SpeedBand.next(SpeedBand.DISTANCE, 25.0))
        assertEquals(SpeedBand.DISTANCE, SpeedBand.next(SpeedBand.DISTANCE, 23.0))
        assertEquals(SpeedBand.DISTANCE, SpeedBand.next(SpeedBand.DISTANCE, 22.0))
    }

    @Test
    fun `and dropped once the speed really has fallen away`() {
        assertEquals(SpeedBand.WAITING, SpeedBand.next(SpeedBand.DISTANCE, 21.9))
    }

    @Test
    fun `FAST enters at 60 and is held down to 55`() {
        assertEquals(SpeedBand.FAST, SpeedBand.next(SpeedBand.DISTANCE, 60.0))
        assertEquals(SpeedBand.FAST, SpeedBand.next(SpeedBand.FAST, 56.0))
        assertEquals(SpeedBand.FAST, SpeedBand.next(SpeedBand.FAST, 55.0))
        assertEquals(SpeedBand.DISTANCE, SpeedBand.next(SpeedBand.FAST, 54.9))
    }

    @Test
    fun `does not flap across the tariff threshold`() {
        // A real 1 Hz staircase for a taxi crawling at about 25 km/h. Without hysteresis this would
        // be DISTANCE, WAITING, DISTANCE, WAITING... and the dial would strobe.
        val samples = listOf(27.0, 25.0, 27.0, 24.0, 27.0, 23.0, 26.5, 22.5)

        val bands = samples.runningFold(SpeedBand.WAITING) { prev, s -> SpeedBand.next(prev, s) }
            .drop(1)

        assertEquals(List(samples.size) { SpeedBand.DISTANCE }, bands)
    }

    @Test
    fun `a real change is not slowed down in either direction`() {
        // Hysteresis is there to stop flapping at a boundary, not to make a hard brake or a hard
        // acceleration take two steps.
        assertEquals(SpeedBand.WAITING, SpeedBand.next(SpeedBand.FAST, 10.0))
        assertEquals(SpeedBand.FAST, SpeedBand.next(SpeedBand.WAITING, 80.0))
    }

    @Test
    fun `the threshold comes from the tariff, not from a constant here`() {
        // A country tariff with a different speedThresholdKmh must move the band with it, or the
        // dial would claim waiting-time character while the meter charged distance.
        val country = 32.0
        assertEquals(SpeedBand.WAITING, SpeedBand.next(SpeedBand.WAITING, 30.0, country))
        assertEquals(SpeedBand.DISTANCE, SpeedBand.next(SpeedBand.WAITING, 32.0, country))
        assertEquals(SpeedBand.DISTANCE, SpeedBand.next(SpeedBand.DISTANCE, 28.5, country))
        assertEquals(SpeedBand.WAITING, SpeedBand.next(SpeedBand.DISTANCE, 27.9, country))
    }

    @Test
    fun `a stopped vehicle is always WAITING, from any band and any tariff`() {
        // The invariant the whole calm-motion design rests on: at a standstill the dial must be
        // completely static. Hysteresis alone does not guarantee it -- a tariff threshold below the
        // exit margin clamps the exit to 0, and `0 < 0` is false, which would leave a parked taxi
        // showing distance-rate character with a live ember on the ring.
        for (prev in SpeedBand.entries) {
            assertEquals(prev.name, SpeedBand.WAITING, SpeedBand.next(prev, 0.0))
            assertEquals(prev.name, SpeedBand.WAITING, SpeedBand.next(prev, 0.0, thresholdKmh = 2.0))
        }
    }

    @Test
    fun `the GPS simulator's constant speeds land in the three bands`() {
        // stopAndGo, plainDrive and the toll runs — so a band change is demonstrable on the tablet
        // by switching route, and each route exercises exactly one character.
        assertEquals(SpeedBand.WAITING, SpeedBand.initial(3.0))
        assertEquals(SpeedBand.DISTANCE, SpeedBand.initial(50.0))
        assertEquals(SpeedBand.FAST, SpeedBand.initial(80.0))
    }

    @Test
    fun `energy rises with the band, so the dial can interpolate instead of branching`() {
        assertEquals(0f, SpeedBand.WAITING.energy, 0f)
        assertEquals(0.5f, SpeedBand.DISTANCE.energy, 0f)
        assertEquals(1f, SpeedBand.FAST.energy, 0f)
    }
}
