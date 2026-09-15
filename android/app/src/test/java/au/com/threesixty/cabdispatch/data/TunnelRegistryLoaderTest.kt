package au.com.threesixty.cabdispatch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Holds the REAL bundled TfNSW tunnel asset to the loader's contract. */
class TunnelRegistryLoaderTest {
    private fun assetText(): String = listOf(
        File("src/main/assets/${TunnelRegistryLoader.ASSET_NAME}"),
        File("app/src/main/assets/${TunnelRegistryLoader.ASSET_NAME}"),
    ).first { it.exists() }.readText()

    @Test
    fun `all 22 Sydney tunnel corridors load with real lengths and road ids`() {
        val registry = TunnelRegistryLoader.parse(assetText())
        assertEquals(22, registry.corridors.size)
        assertTrue(registry.corridors.all { it.lengthKm > 1.0 && it.lengthKm < 15.0 })
        assertTrue(registry.corridors.all { it.roadId != null })
        assertTrue(registry.corridors.any { it.name.startsWith("Lane Cove Tunnel") })
        assertTrue(registry.corridors.any { it.name.startsWith("Westconnex M4 East") })
    }

    @Test
    fun `the Lane Cove Tunnel westbound portal locks a westbound car`() {
        val registry = TunnelRegistryLoader.parse(assetText())
        val wb = registry.corridors.first { it.name == "Lane Cove Tunnel (Westbound)" }
        val (lat, lng) = wb.points[3]
        val lock = registry.match(lat, lng, headingDeg = wb.bearingAt(wb.cumulativeKm[3]))
        assertEquals("Lane Cove Tunnel (Westbound)", lock?.corridor?.name)
    }

    @Test
    fun `the Anzac Bridge entry locks the Rozelle westbound bore chained into the M4 East`() {
        // T5453's last fix before its 2026-09-15 blackout, heading west onto the Rozelle Interchange.
        val registry = TunnelRegistryLoader.parse(assetText())
        val lock = registry.match(-33.8684, 151.1828, headingDeg = 285.0)
        assertEquals("Rozelle Interchange Tunnel (Westbound)", lock?.corridor?.name)
        assertTrue(
            "chain must reach the M4 East (>= 10 km of road), got ${lock?.corridor?.lengthKm}",
            (lock?.corridor?.lengthKm ?: 0.0) >= 10.0,
        )
        // 8 km in, the locked position is west of Haberfield, i.e. inside the M4 East bore.
        val (_, lng) = lock!!.positionAt(traveledKm = 8.0)
        assertTrue("got $lng", lng < 151.14)
    }
}
