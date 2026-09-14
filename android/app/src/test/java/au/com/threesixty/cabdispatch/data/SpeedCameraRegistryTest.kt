package au.com.threesixty.cabdispatch.data

import au.com.threesixty.cabdispatch.domain.SpeedCameraType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Holds the REAL bundled asset to the parser's contract, so a regenerated file that drifts from
 * the compact key layout fails here and never as an empty camera layer on a live tablet. */
class SpeedCameraRegistryTest {

    private fun assetText(): String {
        // Gradle runs unit tests with the module directory as the working directory.
        val candidates = listOf(
            File("src/main/assets/${SpeedCameraRegistry.ASSET_NAME}"),
            File("app/src/main/assets/${SpeedCameraRegistry.ASSET_NAME}"),
        )
        return candidates.first { it.exists() }.readText()
    }

    @Test
    fun `the bundled TfNSW list parses to hundreds of cameras of both types`() {
        val cameras = SpeedCameraRegistry.parse(assetText())
        assertTrue("expected the full statewide list, got ${cameras.size}", cameras.size >= 400)
        assertTrue(cameras.any { it.type == SpeedCameraType.FIXED })
        assertTrue(cameras.any { it.type == SpeedCameraType.RED_LIGHT_SPEED })
        assertTrue(
            "every camera must sit inside NSW",
            cameras.all { it.latitude in -37.6..-28.0 && it.longitude in 140.9..153.7 },
        )
        assertEquals("ids are unique", cameras.size, cameras.map { it.id }.toSet().size)
    }

    @Test
    fun `tunnel average-speed cameras carry their enforced path`() {
        val cameras = SpeedCameraRegistry.parse(assetText())
        val withPath = cameras.filter { it.path != null }
        assertTrue("TfNSW publishes ~20 line cameras, got ${withPath.size}", withPath.size >= 15)
        assertTrue(withPath.all { it.path!!.size >= 2 })
        assertTrue(cameras.any { it.tunnel })
        assertTrue(cameras.any { it.schoolZone })
    }

    @Test
    fun `a corrupt or empty document yields no cameras rather than a crash`() {
        assertEquals(emptyList<Any>(), SpeedCameraRegistry.parse("{}"))
    }
}
