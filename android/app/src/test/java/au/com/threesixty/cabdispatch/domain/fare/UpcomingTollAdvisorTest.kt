package au.com.threesixty.cabdispatch.domain.fare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * Plain-JVM tests for [upcomingToll] — the meter map's "toll ahead" advisory decision function
 * (see that function's own doc) — and its [angularDifferenceDeg] helper. Same "pure geometry over
 * a hand-built [TollRegistrySnapshot], no Room/Compose" shape as [KnownCorridorTest]/[TollDetectorTest]
 * in this same package.
 */
class UpcomingTollAdvisorTest {

    // A fixed origin the vehicle sits at throughout; gantries are placed a real bearing/distance
    // away from it using the SAME [tollHaversineM]/[tollBearingDegrees] this file's production
    // code uses, so every assertion below is checked against independently-real geometry rather
    // than a hand-typed approximation that could quietly drift from what the haversine math
    // actually says.
    private val originLat = -33.8000
    private val originLng = 151.0000

    /** A point roughly [distanceM] north (bearingDeg=0), east (90), south (180) or west (270) of
     * the origin — plain equirectangular offset, accurate enough at these short (<5km) distances
     * for a test fixture; real production distance/bearing checks still go through the real
     * [tollHaversineM]/[tollBearingDegrees] functions, not this approximation. */
    private fun pointAt(bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val metresPerDegLat = 111_320.0
        val metresPerDegLng = 111_320.0 * Math.cos(Math.toRadians(originLat))
        val rad = Math.toRadians(bearingDeg)
        val dLat = (distanceM * Math.cos(rad)) / metresPerDegLat
        val dLng = (distanceM * Math.sin(rad)) / metresPerDegLng
        return (originLat + dLat) to (originLng + dLng)
    }

    private fun gantryAt(id: String, roadId: String, bearingDeg: Double, distanceM: Double, tollPointId: String? = null): TollGantryRef {
        val (lat, lng) = pointAt(bearingDeg, distanceM)
        return TollGantryRef(id, roadId, lat, lng, tollPointId)
    }

    private val flatRoad = TollRoadRef(
        id = "M2",
        name = "Hills M2 Motorway",
        pricingModel = "flat",
        directional = "both",
        currentPrice = TollPriceRef(
            priceClassAMax = BigDecimal("7.31"),
            capClassA = null,
            ratePerKmClassA = null,
            flagfallClassA = null,
            timeOfDayRatesClassA = null,
            confidence = "verified",
        ),
    )

    // --- the headline case: a gantry ahead, within range, is found and priced -------------------

    @Test
    fun `a gantry within lookahead and roughly ahead of heading is returned, with its real price`() {
        val gantry = gantryAt("M2-1", "M2", bearingDeg = 0.0, distanceM = 500.0)
        val registry = TollRegistrySnapshot(roadsById = mapOf("M2" to flatRoad), gantries = listOf(gantry))

        val result = upcomingToll(registry, originLat, originLng, headingDeg = 0.0)

        assertNotNull(result)
        assertEquals("Hills M2 Motorway", result!!.roadName)
        assertEquals(BigDecimal("7.31"), result.price)
        val expectedDistanceM = tollHaversineM(originLat, originLng, gantry.latitude, gantry.longitude)
        assertEquals(expectedDistanceM, result.distanceAheadM, 0.01)
    }

    @Test
    fun `a gantry beyond the lookahead distance is not returned`() {
        val gantry = gantryAt("M2-1", "M2", bearingDeg = 0.0, distanceM = TOLL_LOOKAHEAD_M + 500.0)
        val registry = TollRegistrySnapshot(roadsById = mapOf("M2" to flatRoad), gantries = listOf(gantry))

        assertNull(upcomingToll(registry, originLat, originLng, headingDeg = 0.0))
    }

    @Test
    fun `a gantry behind the vehicle is not returned even if close`() {
        val gantry = gantryAt("M2-1", "M2", bearingDeg = 180.0, distanceM = 300.0) // due south
        val registry = TollRegistrySnapshot(roadsById = mapOf("M2" to flatRoad), gantries = listOf(gantry))

        // Heading north (0deg): the gantry behind is 180deg off-heading, well outside tolerance.
        assertNull(upcomingToll(registry, originLat, originLng, headingDeg = 0.0))
    }

    @Test
    fun `a gantry off to the side beyond the bearing tolerance is not returned`() {
        val gantry = gantryAt("M2-1", "M2", bearingDeg = 90.0, distanceM = 300.0) // due east
        val registry = TollRegistrySnapshot(roadsById = mapOf("M2" to flatRoad), gantries = listOf(gantry))

        // Heading north (0deg): 90deg off-heading is outside the default 60deg tolerance.
        assertNull(upcomingToll(registry, originLat, originLng, headingDeg = 0.0))
    }

    @Test
    fun `a gantry within the bearing tolerance on a curving approach is still returned`() {
        val gantry = gantryAt("M2-1", "M2", bearingDeg = 45.0, distanceM = 400.0) // 45deg off north
        val registry = TollRegistrySnapshot(roadsById = mapOf("M2" to flatRoad), gantries = listOf(gantry))

        assertNotNull(upcomingToll(registry, originLat, originLng, headingDeg = 0.0))
    }

    @Test
    fun `no trusted heading means no advisory, even with a real gantry dead ahead`() {
        val gantry = gantryAt("M2-1", "M2", bearingDeg = 0.0, distanceM = 300.0)
        val registry = TollRegistrySnapshot(roadsById = mapOf("M2" to flatRoad), gantries = listOf(gantry))

        assertNull(upcomingToll(registry, originLat, originLng, headingDeg = null))
    }

    @Test
    fun `the nearest of several candidate roads ahead wins`() {
        val near = gantryAt("NEAR-1", "NEAR", bearingDeg = 0.0, distanceM = 400.0)
        val far = gantryAt("FAR-1", "FAR", bearingDeg = 0.0, distanceM = 1200.0)
        val nearRoad = flatRoad.copy(id = "NEAR", name = "Near Road")
        val farRoad = flatRoad.copy(id = "FAR", name = "Far Road")
        val registry = TollRegistrySnapshot(
            roadsById = mapOf("NEAR" to nearRoad, "FAR" to farRoad),
            gantries = listOf(far, near), // deliberately out of distance order
        )

        val result = upcomingToll(registry, originLat, originLng, headingDeg = 0.0)
        assertEquals("Near Road", result?.roadName)
    }

    @Test
    fun `a gantry whose road cannot be resolved from the registry yields no advisory`() {
        val gantry = gantryAt("ORPHAN-1", "ORPHAN", bearingDeg = 0.0, distanceM = 300.0)
        val registry = TollRegistrySnapshot(roadsById = emptyMap(), gantries = listOf(gantry))

        assertNull(upcomingToll(registry, originLat, originLng, headingDeg = 0.0))
    }

    // --- representativeTollPrice: never a guess, per pricing model ------------------------------

    @Test
    fun `per_point road prices from the crossed toll point, not the road-level range`() {
        val perPointRoad = flatRoad.copy(
            id = "M2P",
            pricingModel = "per_point",
            currentPrice = null,
            tollPoints = mapOf(
                "M2:north_ryde" to TollPointRef(id = "M2:north_ryde", name = "North Ryde", priceClassA = BigDecimal("4.86"), confidence = "verified"),
            ),
        )
        val gantry = gantryAt("M2P-1", "M2P", bearingDeg = 0.0, distanceM = 300.0, tollPointId = "M2:north_ryde")
        val registry = TollRegistrySnapshot(roadsById = mapOf("M2P" to perPointRoad), gantries = listOf(gantry))

        val result = upcomingToll(registry, originLat, originLng, headingDeg = 0.0)
        assertEquals(BigDecimal("4.86"), result?.price)
    }

    @Test
    fun `a distance-priced road shows the cap as an honest upper bound, never a guessed fare`() {
        val distanceRoad = flatRoad.copy(
            id = "M7",
            pricingModel = "distance",
            currentPrice = TollPriceRef(
                priceClassAMax = null,
                capClassA = BigDecimal("10.50"),
                ratePerKmClassA = BigDecimal("0.5252"),
                flagfallClassA = null,
                timeOfDayRatesClassA = null,
                confidence = "verified",
            ),
        )
        val gantry = gantryAt("M7-1", "M7", bearingDeg = 0.0, distanceM = 300.0)
        val registry = TollRegistrySnapshot(roadsById = mapOf("M7" to distanceRoad), gantries = listOf(gantry))

        val result = upcomingToll(registry, originLat, originLng, headingDeg = 0.0)
        assertEquals(BigDecimal("10.50"), result?.price)
    }

    @Test
    fun `an unpriced road still names the road ahead but never fabricates a price`() {
        val unpricedRoad = flatRoad.copy(id = "UNP", pricingModel = "unpriced", currentPrice = null)
        val gantry = gantryAt("UNP-1", "UNP", bearingDeg = 0.0, distanceM = 300.0)
        val registry = TollRegistrySnapshot(roadsById = mapOf("UNP" to unpricedRoad), gantries = listOf(gantry))

        val result = upcomingToll(registry, originLat, originLng, headingDeg = 0.0)
        assertNotNull(result)
        assertEquals("Hills M2 Motorway", result!!.roadName) // unpricedRoad.copy kept flatRoad's name
        assertNull(result.price)
    }

    // --- angularDifferenceDeg: pure wraparound geometry ------------------------------------------

    @Test
    fun `angularDifferenceDeg wraps correctly across zero and 360`() {
        assertEquals(0.0, angularDifferenceDeg(0.0, 360.0), 1e-9)
        assertEquals(10.0, angularDifferenceDeg(5.0, 355.0), 1e-9)
        assertEquals(180.0, angularDifferenceDeg(0.0, 180.0), 1e-9)
        assertEquals(90.0, angularDifferenceDeg(45.0, 315.0), 1e-9)
        assertEquals(90.0, angularDifferenceDeg(0.0, 90.0), 1e-9)
    }
}
