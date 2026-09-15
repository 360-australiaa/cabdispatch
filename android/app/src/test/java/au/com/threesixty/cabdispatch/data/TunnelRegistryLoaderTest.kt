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
}
