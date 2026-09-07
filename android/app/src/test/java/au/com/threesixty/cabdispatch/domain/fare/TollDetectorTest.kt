package au.com.threesixty.cabdispatch.domain.fare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Plain-JVM golden tests for the on-device NSW toll-road auto-detector ([onFix] et al.) — no
 * Android/Room/network dependency, same discipline [FareEngineTest] already holds this package to.
 * Each test pins one specific rule from the feature's contract (dedup-by-road, direction handling,
 * distance-model accrual/cap/revise-in-place, the never-guess "unpriced" fallback, and the driver
 * correction affordances) directly against [onFix], not against any UI/Android wiring on top of it.
 */
class TollDetectorTest {

    private val ts: ZonedDateTime = ZonedDateTime.of(2026, 9, 7, 10, 0, 0, 0, ZoneOffset.UTC) // Monday, off-peak-ish

    // A fixed gantry point and a "300m away" approach point outside the 150m detection radius, so
    // the first onFix call at the approach point establishes a real previousFix WITHOUT itself
    // registering a hit — exactly the two-fix "approaching, then at the gantry" shape a real trip
    // produces.
    private val gantryLat = -33.8000
    private val gantryLng = 151.0000
    private val approachFromSouthLat = gantryLat - 0.0027 // ~300m south of the gantry
    private val approachFromNorthLat = gantryLat + 0.0027 // ~300m north of the gantry

    private fun flatRoad(id: String, directional: String?, amount: String = "4.30") = TollRoadRef(
        id = id,
        name = "Test Road $id",
        pricingModel = "flat",
        directional = directional,
        currentPrice = TollPriceRef(
            priceClassAMax = BigDecimal(amount),
            capClassA = null,
            ratePerKmClassA = null,
            flagfallClassA = null,
            timeOfDayRatesClassA = null,
            confidence = "verified",
        ),
    )

    // --- 1. Dedup by ROAD, never by gantry ---------------------------------------------------

    @Test
    fun `many gantries of the same road only charge once`() {
        val road = flatRoad("M7", directional = "both")
        val registry = TollRegistrySnapshot(
            roadsById = mapOf(road.id to road),
            gantries = listOf(
                TollGantryRef("g1", "M7", gantryLat, gantryLng),
                TollGantryRef("g2", "M7", gantryLat + 0.0001, gantryLng), // a few metres away, same road
                TollGantryRef("g3", "M7", gantryLat - 0.0001, gantryLng + 0.0001),
            ),
        )
        val state = TollDetectionState()

        // One fix hits all three gantries at once (all within radius of each other) — still exactly
        // one charge for the road.
        val result = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal.ZERO)

        assertEquals(mapOf("M7" to BigDecimal("4.30")), result.chargedRoadsChanged)
        assertEquals(mapOf("M7" to BigDecimal("4.30")), state.chargedRoads)

        // A second fix still near the gantry cluster must NOT add a second charge.
        val secondResult = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal("0.5"))
        assertTrue("a repeat crossing of an already-charged flat road must change nothing", secondResult.isEmpty)
        assertEquals(BigDecimal("4.30"), state.chargedRoads["M7"])
    }

    // --- 2. one_way charges unconditionally, no bearing needed -------------------------------

    @Test
    fun `one_way road charges on the very first fix with no prior movement at all`() {
        val road = flatRoad("MILITARY_E_RAMP", directional = "one_way", amount = "2.15")
        val registry = TollRegistrySnapshot(
            roadsById = mapOf(road.id to road),
            gantries = listOf(TollGantryRef("g1", road.id, gantryLat, gantryLng)),
        )
        val state = TollDetectionState() // previousFix is null — the trip's very first GPS fix

        val result = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal.ZERO)

        assertEquals(mapOf(road.id to BigDecimal("2.15")), result.chargedRoadsChanged)
    }

    // --- 3 & 4. Directional roads: wrong direction never charges; undetermined direction retries,
    // it does not permanently block --------------------------------------------------------------

    @Test
    fun `northbound_only road never charges a southbound crossing`() {
        val road = flatRoad("ED", directional = "northbound_only", amount = "3.20")
        val registry = TollRegistrySnapshot(
            roadsById = mapOf(road.id to road),
            gantries = listOf(TollGantryRef("g1", road.id, gantryLat, gantryLng)),
        )
        val state = TollDetectionState()

        // Approaching from the north, heading south into the gantry.
        onFix(state, registry, approachFromNorthLat, gantryLng, ts, BigDecimal.ZERO) // sets previousFix only, no hit
        val result = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal("0.3"))

        assertTrue("a genuinely wrong-direction crossing must never charge", result.isEmpty)
        assertTrue(state.chargedRoads.isEmpty())
        assertTrue("a wrong-direction road must not be silently flagged unpriced either", state.unpricedRoadIds.isEmpty())
    }

    @Test
    fun `northbound_only road charges a genuine northbound crossing`() {
        val road = flatRoad("ED", directional = "northbound_only", amount = "3.20")
        val registry = TollRegistrySnapshot(
            roadsById = mapOf(road.id to road),
            gantries = listOf(TollGantryRef("g1", road.id, gantryLat, gantryLng)),
        )
        val state = TollDetectionState()

        onFix(state, registry, approachFromSouthLat, gantryLng, ts, BigDecimal.ZERO) // heading north
        val result = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal("0.3"))

        assertEquals(mapOf(road.id to BigDecimal("3.20")), result.chargedRoadsChanged)
    }

    @Test
    fun `undetermined direction on the very first fix does not charge but does not permanently block either`() {
        val road = flatRoad("ED", directional = "northbound_only", amount = "3.20")
        val registry = TollRegistrySnapshot(
            roadsById = mapOf(road.id to road),
            gantries = listOf(TollGantryRef("g1", road.id, gantryLat, gantryLng)),
        )
        val state = TollDetectionState() // no previous fix at all -> compass is null, not "wrong"

        val firstResult = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal.ZERO)
        assertTrue("an undetermined bearing must never charge", firstResult.isEmpty)
        assertTrue(state.chargedRoads.isEmpty())
        assertTrue(
            "undetermined must be treated as distinct from wrong-direction — never flagged unpriced",
            state.unpricedRoadIds.isEmpty(),
        )

        // The very next fix, now with real northward movement since the last one, must still be
        // free to charge — the earlier undetermined fix must not have permanently blocked this road.
        val secondResult = onFix(state, registry, gantryLat, gantryLng + 0.0001, ts, BigDecimal("0.05"))
        // (~9m of GPS jitter — still under the 15m trust threshold: still undetermined, not wrong-direction)
        assertTrue(secondResult.isEmpty)

        onFix(state, registry, approachFromSouthLat, gantryLng, ts, BigDecimal("0.1")) // real northward movement
        val thirdResult = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal("0.4"))
        assertEquals(mapOf(road.id to BigDecimal("3.20")), thirdResult.chargedRoadsChanged)
    }

    // --- 5. distance model (M7): accrual, cap, revise in place -------------------------------

    @Test
    fun `distance model accrues the real published rate times travelled, respects the cap, and revises the same entry in place`() {
        // Westlink M7's real published rate (product correction, 2026-09): $0.5252/km capped at
        // $10.50 — taken straight from TollPriceRef.ratePerKmClassA, NOT derived from a corridor
        // length (that derivation was confirmed wrong; see TollPriceRef's own doc).
        val road = TollRoadRef(
            id = "M7",
            name = "Westlink M7",
            pricingModel = "distance",
            directional = "both",
            currentPrice = TollPriceRef(
                priceClassAMax = null,
                capClassA = BigDecimal("10.50"),
                ratePerKmClassA = BigDecimal("0.5252"),
                flagfallClassA = null,
                timeOfDayRatesClassA = null,
                confidence = "verified",
            ),
        )
        val gantryA = TollGantryRef("m7-g1", "M7", -33.80, 151.00)
        val gantryB = TollGantryRef("m7-g2", "M7", -33.90, 151.10) // a different, distant gantry, same road
        val registry = TollRegistrySnapshot(roadsById = mapOf("M7" to road), gantries = listOf(gantryA, gantryB))
        val state = TollDetectionState()

        // Entry gantry at 10km cumulative — anchor point, zero travelled yet.
        val r1 = onFix(state, registry, gantryA.latitude, gantryA.longitude, ts, BigDecimal("10.0"))
        assertEquals(mapOf("M7" to BigDecimal("0.00")), r1.chargedRoadsChanged)

        // 5km further down the M7, a second real gantry of the SAME road — revises the SAME entry.
        val r2 = onFix(state, registry, gantryB.latitude, gantryB.longitude, ts, BigDecimal("15.0"))
        assertEquals(mapOf("M7" to BigDecimal("2.63")), r2.chargedRoadsChanged) // 5km * 0.5252 = 2.626 -> 2.63
        assertEquals(1, state.chargedRoads.size) // still one entry, not two

        // 20km travelled -> 10.504 raw, over the cap.
        val r3 = onFix(state, registry, gantryB.latitude, gantryB.longitude, ts, BigDecimal("30.0"))
        assertEquals(mapOf("M7" to BigDecimal("10.50")), r3.chargedRoadsChanged)

        // Well past the cap: the raw formula would give ~26.26, but the charge must stay at 10.50,
        // and since the amount hasn't actually CHANGED, this fix must report no change at all.
        val r4 = onFix(state, registry, gantryB.latitude, gantryB.longitude, ts, BigDecimal("60.0"))
        assertTrue("a plateaued (capped) distance charge that hasn't changed must report no change", r4.isEmpty)
        assertEquals(BigDecimal("10.50"), state.chargedRoads["M7"])
    }

    @Test
    fun `distance_with_flagfall model charges flagfall plus rate times travelled, capped, revised in place`() {
        // Illustrative WestConnex-shaped figures (product correction, 2026-09: M4/M8/M5E/
        // M4M5_ROZELLE are real flagfall+per-km+cap roads, not unpriced) — NOT real published M4
        // prices (this pass's forward-compatible field mapping has no real data to test against
        // yet; see TollRoadPriceRevisionDto.rateClassAPerKm/.flagfallClassA's own doc), only
        // exercising the formula itself: flagfall + rate * travelled, capped.
        val road = TollRoadRef(
            id = "M4",
            name = "WestConnex M4",
            pricingModel = "distance_with_flagfall",
            directional = null,
            currentPrice = TollPriceRef(
                priceClassAMax = null,
                capClassA = BigDecimal("8.00"),
                ratePerKmClassA = BigDecimal("0.30"),
                flagfallClassA = BigDecimal("1.50"),
                timeOfDayRatesClassA = null,
                confidence = "verified",
            ),
        )
        val gantry = TollGantryRef("m4-g1", "M4", gantryLat, gantryLng)
        val registry = TollRegistrySnapshot(mapOf("M4" to road), listOf(gantry))
        val state = TollDetectionState()

        // Entry: flagfall 1.50 + 0km * 0.30 = 1.50.
        val r1 = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal("5.0"))
        assertEquals(mapOf("M4" to BigDecimal("1.50")), r1.chargedRoadsChanged)

        // 10km travelled: 1.50 + 10*0.30 = 4.50 — revises the SAME entry, not a second one.
        val r2 = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal("15.0"))
        assertEquals(mapOf("M4" to BigDecimal("4.50")), r2.chargedRoadsChanged)
        assertEquals(1, state.chargedRoads.size)

        // 30km travelled: 1.50 + 30*0.30 = 10.50 raw, capped at 8.00.
        val r3 = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal("35.0"))
        assertEquals(mapOf("M4" to BigDecimal("8.00")), r3.chargedRoadsChanged)
    }

    @Test
    fun `distance and distance_with_flagfall roads with no rate captured yet are treated as unpriced, never guessed`() {
        // Today's ACTUAL API response for every distance/distance_with_flagfall road (the backend
        // hasn't shipped rateClassAPerKm/flagfallClassA yet — see those DTO fields' own doc): this
        // must be the honest, safe behaviour, not a crash or a guessed figure.
        val m7NoRateYet = TollRoadRef(
            id = "M7",
            name = "Westlink M7",
            pricingModel = "distance",
            directional = "both",
            currentPrice = TollPriceRef(
                priceClassAMax = null,
                capClassA = BigDecimal("10.50"),
                ratePerKmClassA = null,
                flagfallClassA = null,
                timeOfDayRatesClassA = null,
                confidence = "verified",
            ),
        )
        val registry = TollRegistrySnapshot(mapOf("M7" to m7NoRateYet), listOf(TollGantryRef("g1", "M7", gantryLat, gantryLng)))
        val state = TollDetectionState()

        val result = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal("10.0"))

        assertTrue(result.chargedRoadsChanged.isEmpty())
        assertEquals(setOf("M7"), result.newlyUnpricedRoadIds)
    }

    // --- 6. zone_flat / unpriced roads are never auto-charged --------------------------------

    @Test
    fun `zone_flat road is never auto-charged, only flagged for manual entry`() {
        val road = TollRoadRef(
            id = "M2",
            name = "Hills M2 Motorway",
            pricingModel = "zone_flat",
            directional = "both",
            currentPrice = TollPriceRef(
                priceClassAMax = BigDecimal("10.64"),
                capClassA = null,
                ratePerKmClassA = null,
                flagfallClassA = null,
                timeOfDayRatesClassA = null,
                confidence = "verified",
            ),
        )
        val registry = TollRegistrySnapshot(mapOf("M2" to road), listOf(TollGantryRef("g1", "M2", gantryLat, gantryLng)))
        val state = TollDetectionState()

        val result = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal.ZERO)

        assertTrue("zone_flat must never appear as a real charge", result.chargedRoadsChanged.isEmpty())
        assertEquals(setOf("M2"), result.newlyUnpricedRoadIds)
        assertTrue(state.chargedRoads.isEmpty())
    }

    @Test
    fun `unpriced road (e_g_ the M12 stub, or a road with no captured price) is never auto-charged`() {
        val road = TollRoadRef(
            id = "M12",
            name = "M12 Motorway",
            pricingModel = "unpriced",
            directional = null,
            currentPrice = null,
        )
        val registry = TollRegistrySnapshot(mapOf("M12" to road), listOf(TollGantryRef("g1", "M12", gantryLat, gantryLng)))
        val state = TollDetectionState()

        val result = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal.ZERO)

        assertTrue(result.chargedRoadsChanged.isEmpty())
        assertEquals(setOf("M12"), result.newlyUnpricedRoadIds)

        // A second crossing must not re-flag it as "newly" unpriced (already known).
        val second = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal("0.2"))
        assertTrue(second.newlyUnpricedRoadIds.isEmpty())
    }

    @Test
    fun `a road whose revision is still confidence=not_captured is treated as unpriced regardless of pricing model`() {
        val road = TollRoadRef(
            id = "M4",
            name = "WestConnex M4",
            pricingModel = "distance_with_flagfall",
            directional = null,
            currentPrice = TollPriceRef(
                priceClassAMax = null,
                capClassA = null,
                ratePerKmClassA = null,
                flagfallClassA = null,
                timeOfDayRatesClassA = null,
                confidence = "not_captured",
            ),
        )
        val registry = TollRegistrySnapshot(mapOf("M4" to road), listOf(TollGantryRef("g1", "M4", gantryLat, gantryLng)))
        val state = TollDetectionState()

        val result = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal.ZERO)

        assertTrue(result.chargedRoadsChanged.isEmpty())
        assertEquals(setOf("M4"), result.newlyUnpricedRoadIds)
    }

    // --- Driver correction affordances --------------------------------------------------------

    @Test
    fun `dismissing an auto-charge removes it and blocks it for the rest of the trip`() {
        val road = flatRoad("M5SW", directional = "both")
        val registry = TollRegistrySnapshot(mapOf(road.id to road), listOf(TollGantryRef("g1", road.id, gantryLat, gantryLng)))
        val state = TollDetectionState()

        onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal.ZERO)
        assertEquals(BigDecimal("4.30"), state.chargedRoads[road.id])

        val removed = dismissCharge(state, road.id)
        assertEquals(BigDecimal("4.30"), removed)
        assertTrue(state.chargedRoads.isEmpty())

        // Re-crossing the same gantry (e.g. queued traffic) must never re-add it.
        val afterDismiss = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal("0.1"))
        assertTrue("a dismissed road must never be re-auto-charged for the rest of the trip", afterDismiss.isEmpty)
    }

    @Test
    fun `dismissing a road that was never charged is a harmless no-op`() {
        val state = TollDetectionState()
        assertNull(dismissCharge(state, "NEVER_CROSSED"))
    }

    // --- time-of-day band selection (SHB_SHT) -------------------------------------------------

    @Test
    fun `time-of-day price picks the band the real crossing timestamp actually falls into`() {
        val rates = listOf(
            TimeOfDayRateRef("peak", 4.55),
            TimeOfDayRateRef("off_peak", 3.41),
            TimeOfDayRateRef("night", 2.85),
        )
        val mondayPeak = ZonedDateTime.of(2026, 9, 7, 7, 0, 0, 0, ZoneOffset.UTC) // Mon 07:00
        val mondayOffPeak = ZonedDateTime.of(2026, 9, 7, 11, 0, 0, 0, ZoneOffset.UTC) // Mon 11:00
        val mondayNight = ZonedDateTime.of(2026, 9, 7, 23, 0, 0, 0, ZoneOffset.UTC) // Mon 23:00
        val saturdayDay = ZonedDateTime.of(2026, 9, 12, 12, 0, 0, 0, ZoneOffset.UTC) // Sat 12:00 -> off_peak

        assertEquals(BigDecimal("4.55"), selectTimeOfDayPrice(rates, mondayPeak))
        assertEquals(BigDecimal("3.41"), selectTimeOfDayPrice(rates, mondayOffPeak))
        assertEquals(BigDecimal("2.85"), selectTimeOfDayPrice(rates, mondayNight))
        assertEquals(BigDecimal("3.41"), selectTimeOfDayPrice(rates, saturdayDay))
    }

    @Test
    fun `southbound_only time-of-day road (SHB_SHT) only charges southbound, at the real timestamp's band`() {
        val road = TollRoadRef(
            id = "SHB_SHT",
            name = "Sydney Harbour Bridge/Tunnel",
            pricingModel = "time_of_day",
            directional = "southbound_only",
            currentPrice = TollPriceRef(
                priceClassAMax = null,
                capClassA = null,
                ratePerKmClassA = null,
                flagfallClassA = null,
                timeOfDayRatesClassA = listOf(TimeOfDayRateRef("peak", 4.55), TimeOfDayRateRef("off_peak", 3.41), TimeOfDayRateRef("night", 2.85)),
                confidence = "needs_verification",
            ),
        )
        val registry = TollRegistrySnapshot(mapOf(road.id to road), listOf(TollGantryRef("g1", road.id, gantryLat, gantryLng)))
        val morningPeak = ZonedDateTime.of(2026, 9, 7, 7, 0, 0, 0, ZoneOffset.UTC)

        // Northbound crossing at peak time -> never charged (wrong direction), regardless of price.
        val northState = TollDetectionState()
        onFix(northState, registry, approachFromSouthLat, gantryLng, morningPeak, BigDecimal.ZERO)
        val northResult = onFix(northState, registry, gantryLat, gantryLng, morningPeak, BigDecimal("0.3"))
        assertTrue(northResult.isEmpty)

        // Southbound crossing at the same peak time -> charged the real peak Class A price.
        val southState = TollDetectionState()
        onFix(southState, registry, approachFromNorthLat, gantryLng, morningPeak, BigDecimal.ZERO)
        val southResult = onFix(southState, registry, gantryLat, gantryLng, morningPeak, BigDecimal("0.3"))
        assertEquals(mapOf(road.id to BigDecimal("4.55")), southResult.chargedRoadsChanged)
    }
}
