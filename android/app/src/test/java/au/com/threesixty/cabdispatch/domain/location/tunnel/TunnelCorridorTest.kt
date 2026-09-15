package au.com.threesixty.cabdispatch.domain.location.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelCorridorTest {

    // A 3 km east-west bore at Lane Cove's latitude, entry at the east end, running WEST.
    private val westbound = TunnelCorridor(
        id = "wb", name = "Test Tunnel (Westbound)", roadId = "LCT",
        points = (0..30).map { i -> -33.80 to (151.20 - i * 0.001) },
    )
    private val eastbound = TunnelCorridor(
        id = "eb", name = "Test Tunnel (Eastbound)", roadId = "LCT",
        points = (0..30).map { i -> -33.8004 to (151.17 + i * 0.001) },
    )
    private val registry = TunnelRegistry(listOf(westbound, eastbound))

    @Test
    fun `locks to the bore that matches the heading, not the opposite one`() {
        val lock = registry.match(-33.8001, 151.1995, headingDeg = 275.0)
        assertNotNull(lock)
        assertEquals("wb", lock!!.corridor.id)
        assertTrue(lock.entryAlongKm < 0.2)
        assertEquals("eb", registry.match(-33.8004, 151.1705, headingDeg = 95.0)!!.corridor.id)
    }

    @Test
    fun `no heading, too far away, or arriving at the exit portal means no lock`() {
        assertNull(registry.match(-33.8001, 151.1995, headingDeg = null))
        assertNull(registry.match(-33.81, 151.1995, headingDeg = 275.0)) // ~1.1 km off
        // ~330 m before the portal: still a lock.
        assertNotNull(registry.match(-33.8001, 151.2035, headingDeg = 275.0))
        assertNull(registry.match(-33.80, 151.171, headingDeg = 275.0)) // 100 m before the WB exit portal
    }

    @Test
    fun `the locked position walks the corridor and holds at the exit portal`() {
        val lock = registry.match(-33.80, 151.20, headingDeg = 270.0)!!
        val (lat, lng) = lock.positionAt(traveledKm = 1.0)
        assertEquals(-33.80, lat, 1e-6)
        assertTrue("~1 km west of the entry, got $lng", lng in 151.188..151.190)
        assertEquals(270.0, lock.headingAt(1.0), 1.5)
        val (endLat, endLng) = lock.positionAt(traveledKm = 50.0)
        assertEquals(westbound.points.last().second, endLng, 1e-9)
        assertEquals(westbound.points.last().first, endLat, 1e-9)
        assertTrue(lock.atExit(50.0))
        assertTrue(lock.pathTraveled(1.0).size >= 10)
    }

    @Test
    fun `road distance to the reacquisition fix is along the corridor, chord-free`() {
        val lock = registry.match(-33.80, 151.20, headingDeg = 270.0)!!
        // Reacquired 2 km along, 40 m off the centreline.
        val km = lock.roadKmTo(-33.8004, 151.20 - 0.0216)
        assertNotNull(km)
        assertEquals(2.0, km!!, 0.12)
        // Reacquired 500 m past the exit portal: full corridor plus the run beyond.
        val beyond = lock.roadKmTo(-33.80, 151.17 - 0.0054)
        assertNotNull(beyond)
        assertTrue("got $beyond", beyond!! > westbound.lengthKm)
        // Reacquired somewhere else entirely: not a road-locked answer.
        assertNull(lock.roadKmTo(-33.85, 151.25))
    }

    @Test
    fun `a bore that continues into another bore is locked as one chain, never the opposite bore`() {
        // Bore A runs west and ends where bore B (also westbound, 500 m gap) begins; the eastbound
        // bore of A starts at A's exit portal too, pointing back east.
        val a = TunnelCorridor("a", "A (Westbound)", "M4", (0..20).map { i -> -33.80 to (151.20 - i * 0.001) })
        val b = TunnelCorridor("b", "B (Westbound)", "M8", (0..20).map { i -> -33.80 to (151.175 - i * 0.001) })
        val aBack = TunnelCorridor("a-eb", "A (Eastbound)", "M4", (0..20).map { i -> -33.8004 to (151.18 + i * 0.001) })
        val reg = TunnelRegistry(listOf(a, b, aBack))
        val lock = reg.match(-33.80, 151.199, headingDeg = 270.0)!!
        assertEquals("a+b", lock.corridor.id)
        assertEquals("A (Westbound)", lock.corridor.name)
        assertTrue(
            "chain spans both bores plus the gap, got ${lock.corridor.lengthKm}",
            lock.corridor.lengthKm > 4.0,
        )
        // 3 km along the chain is inside bore B, not parked at A's exit.
        val (_, lng) = lock.positionAt(traveledKm = 3.0)
        assertTrue("got $lng", lng < 151.175)
    }
}
