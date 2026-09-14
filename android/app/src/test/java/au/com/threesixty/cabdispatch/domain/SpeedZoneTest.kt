package au.com.threesixty.cabdispatch.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedZoneTest {
    @Test
    fun `initial zone follows the fixed boundaries`() {
        assertEquals(SpeedZone.LOW, SpeedZone.initial(0.0))
        assertEquals(SpeedZone.LOW, SpeedZone.initial(29.9))
        assertEquals(SpeedZone.GOOD, SpeedZone.initial(30.0))
        assertEquals(SpeedZone.GOOD, SpeedZone.initial(79.9))
        assertEquals(SpeedZone.CAUTION, SpeedZone.initial(80.0))
        assertEquals(SpeedZone.CAUTION, SpeedZone.initial(110.0))
    }

    @Test
    fun `a boundary is left only past the hysteresis`() {
        var z = SpeedZone.initial(35.0)
        z = SpeedZone.next(z, 29.0) // within 2 km/h of the boundary: still GOOD
        assertEquals(SpeedZone.GOOD, z)
        z = SpeedZone.next(z, 27.9)
        assertEquals(SpeedZone.LOW, z)
        z = SpeedZone.next(z, 30.0)
        assertEquals(SpeedZone.GOOD, z)
        z = SpeedZone.next(z, 80.0)
        assertEquals(SpeedZone.CAUTION, z)
        z = SpeedZone.next(z, 79.0)
        assertEquals(SpeedZone.CAUTION, z)
        z = SpeedZone.next(z, 77.0)
        assertEquals(SpeedZone.GOOD, z)
    }

    @Test
    fun `a hard brake from caution lands in the right zone directly`() {
        assertEquals(SpeedZone.LOW, SpeedZone.next(SpeedZone.CAUTION, 10.0))
    }
}
