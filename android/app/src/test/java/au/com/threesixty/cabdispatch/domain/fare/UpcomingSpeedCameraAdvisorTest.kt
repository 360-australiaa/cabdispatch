package au.com.threesixty.cabdispatch.domain.fare

import au.com.threesixty.cabdispatch.domain.SpeedCamera
import au.com.threesixty.cabdispatch.domain.SpeedCameraType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpcomingSpeedCameraAdvisorTest {

    // Botany Road / McEvoy Street red-light speed camera (real TfNSW row) and a point ~500 m due
    // south of it on the same road.
    private val botanyRoad = SpeedCamera(
        id = 7101, name = "Botany Road, Mcevoy Street", type = SpeedCameraType.RED_LIGHT_SPEED,
        latitude = -33.90148908, longitude = 151.2011365,
    )
    private val southOfCameraLat = -33.90148908 - 0.0045 // ~500 m south (more negative = further south)
    private val southOfCameraLng = 151.2011365

    @Test
    fun `camera ahead on the heading within lookahead is reported with its distance`() {
        val ahead = upcomingSpeedCamera(listOf(botanyRoad), southOfCameraLat, southOfCameraLng, headingDeg = 0.0)
        assertNotNull(ahead)
        assertEquals(7101, ahead!!.id)
        assertEquals(SpeedCameraType.RED_LIGHT_SPEED, ahead.type)
        assertTrue("about 500 m, got ${ahead.distanceAheadM}", ahead.distanceAheadM in 450.0..550.0)
    }

    @Test
    fun `a camera behind the vehicle is never ahead`() {
        assertNull(upcomingSpeedCamera(listOf(botanyRoad), southOfCameraLat, southOfCameraLng, headingDeg = 180.0))
    }

    @Test
    fun `no trusted heading means no advisory`() {
        assertNull(upcomingSpeedCamera(listOf(botanyRoad), southOfCameraLat, southOfCameraLng, headingDeg = null))
    }

    @Test
    fun `outside the lookahead is silent`() {
        assertNull(upcomingSpeedCamera(listOf(botanyRoad), southOfCameraLat + 0.02, southOfCameraLng, headingDeg = 0.0))
    }

    @Test
    fun `a line-published tunnel camera warns from any vertex ahead, not only its first point`() {
        // East-west enforced length; the vehicle joins it part-way, 300 m west of the middle
        // vertex and heading east -- the first point is behind it.
        val tunnel = SpeedCamera(
            id = 6216, name = "Westconnex M4 East Tunnel (Eastbound)", type = SpeedCameraType.FIXED,
            latitude = -33.8613, longitude = 151.0809, tunnel = true,
            path = listOf(-33.8613 to 151.0809, -33.8630 to 151.0900, -33.8650 to 151.1000),
        )
        val ahead = upcomingSpeedCamera(listOf(tunnel), -33.8630, 151.0868, headingDeg = 90.0)
        assertNotNull(ahead)
        assertEquals(6216, ahead!!.id)
        assertTrue(ahead.tunnel)
        assertTrue("nearest vertex ahead ~300 m, got ${ahead.distanceAheadM}", ahead.distanceAheadM in 200.0..400.0)
    }

    @Test
    fun `the nearest of several cameras ahead wins`() {
        val nearer = botanyRoad.copy(id = 1, latitude = southOfCameraLat + 0.002) // ~220 m north = ahead
        val ahead =
            upcomingSpeedCamera(listOf(botanyRoad, nearer), southOfCameraLat, southOfCameraLng, headingDeg = 0.0)
        assertEquals(1, ahead?.id)
    }
}
