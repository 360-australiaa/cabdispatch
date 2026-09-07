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

        // Entry gantry at 10km cumulative. ONE confirmed gantry is not yet enough to charge a
        // road with two of them (see TOLL_MIN_CONFIRMATIONS) -- but it does anchor the accrual,
        // so the stretch from here to the next gantry is still billed once corroborated.
        val r1 = onFix(state, registry, gantryA.latitude, gantryA.longitude, ts, BigDecimal("10.0"))
        assertTrue("one gantry is not corroboration", r1.chargedRoadsChanged.isEmpty())

        // 5km further down the M7, a second real gantry of the SAME road. That is the second
        // confirmation, so the road becomes chargeable -- and it bills from the FIRST gantry, not
        // from here.
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

    // --- 6. unknown / unpriced models are never auto-charged ---------------------------------

    @Test
    fun `a pricing model this build has no formula for is flagged, never guessed`() {
        // "zone_flat" was a real, handled model until the 2026-09-07 price correction replaced it
        // with per-toll-point pricing. A device whose cached registry predates that refresh still
        // holds rows carrying it, so this is not a hypothetical value — and it stands in for any
        // future model that reaches this build before its formula does. The invariant under test
        // is "no formula -> flag for manual entry", not the string.
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

        assertTrue("an unrecognised model must never appear as a real charge", result.chargedRoadsChanged.isEmpty())
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

    // --- 7. per_point roads: the 2026-09-07 correction's real per-crossing prices -------------

    private fun perPointRoad(
        id: String,
        chargingPolicy: String,
        points: Map<String, String?>,
        /** Real display names, where a test asserts on one. Defaults to the point id elsewhere,
         * since most of these tests care about the AMOUNT, not the label. */
        pointNames: Map<String, String> = emptyMap(),
    ) = TollRoadRef(
        id = id,
        name = "Test Road $id",
        pricingModel = "per_point",
        chargingPolicy = chargingPolicy,
        directional = "both",
        // A per_point road's road-level price is a DESCRIPTIVE RANGE across its points, never a
        // real per-crossing figure. Deliberately set to something obviously wrong here (999.99),
        // so any test that starts passing by charging the road level instead of the point level
        // fails loudly rather than quietly agreeing.
        currentPrice = TollPriceRef(
            priceClassAMax = BigDecimal("999.99"),
            capClassA = null,
            ratePerKmClassA = null,
            flagfallClassA = null,
            timeOfDayRatesClassA = null,
            confidence = "verified",
        ),
        tollPoints = points.mapValues { (pointId, price) ->
            TollPointRef(
                id = pointId,
                name = pointNames[pointId] ?: pointId,
                priceClassA = price?.let { BigDecimal(it) },
                confidence = "verified",
            )
        },
    )

    @Test
    fun `cumulative per-point road charges each distinct toll point traversed`() {
        // Hills M2 prices by the number of toll points traversed, so this is the one road where a
        // second gantry of the SAME road legitimately adds money.
        val road = perPointRoad(
            "M2",
            chargingPolicy = "cumulative_per_point",
            points = mapOf("M2:north_ryde" to "10.64", "M2:windsor_rd" to "3.76"),
        )
        val registry = TollRegistrySnapshot(
            roadsById = mapOf("M2" to road),
            gantries = listOf(
                // Two gantries per point, metres apart, as a real mainline has -- a single pass
                // therefore corroborates the road (see TOLL_MIN_CONFIRMATIONS) without needing to
                // reach the second, distant toll point first.
                TollGantryRef("g1a", "M2", gantryLat, gantryLng, tollPointId = "M2:north_ryde"),
                TollGantryRef("g1b", "M2", gantryLat + 0.0002, gantryLng, tollPointId = "M2:north_ryde"),
                TollGantryRef("g2a", "M2", -33.9000, 151.1000, tollPointId = "M2:windsor_rd"),
                TollGantryRef("g2b", "M2", -33.9002, 151.1000, tollPointId = "M2:windsor_rd"),
            ),
        )
        val state = TollDetectionState()

        onFix(state, registry, approachFromSouthLat, gantryLng, ts, BigDecimal.ZERO)
        val first = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal.ZERO)
        assertEquals(mapOf("M2:north_ryde" to BigDecimal("10.64")), first.chargedRoadsChanged)

        // A later fix at the SECOND point adds to the total rather than deduplicating away.
        onFix(state, registry, -33.8973, 151.1000, ts, BigDecimal("8.0"))
        val second = onFix(state, registry, -33.9000, 151.1000, ts, BigDecimal("8.5"))
        assertEquals(mapOf("M2:windsor_rd" to BigDecimal("3.76")), second.chargedRoadsChanged)
        assertEquals(
            BigDecimal("14.40"),
            state.chargedRoads.values.fold(BigDecimal.ZERO) { acc, v -> acc + v },
        )

        // Re-crossing an already-charged point never charges it twice.
        val again = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal("20.0"))
        assertTrue(again.chargedRoadsChanged.isEmpty())
    }

    @Test
    fun `once-per-road per-point road charges only the first point crossed`() {
        // Cross City / Lane Cove Tunnel: their two points are a main tunnel and an alternate ramp
        // a vehicle uses ONE of, and no source says the charges are cumulative. This pins the
        // deliberate no-overcharge reading (a flagged interpretation call, not a stated fact) so a
        // refactor cannot silently reverse it.
        val road = perPointRoad(
            "LCT",
            chargingPolicy = "once_per_road",
            points = mapOf("LCT:main_tunnel" to "4.30", "LCT:military_e_ramp" to "2.15"),
        )
        val registry = TollRegistrySnapshot(
            roadsById = mapOf("LCT" to road),
            gantries = listOf(
                // Two mainline gantries, as the real Lane Cove Tunnel has six -- so driving the
                // main tunnel corroborates the road on its own. This matters beyond the fixture:
                // a once_per_road per_point road's points are ALTERNATIVES (main tunnel vs. ramp),
                // so a vehicle only ever crosses one of them, and corroboration has to be
                // satisfiable from a single point's own gantries or the road could never charge.
                TollGantryRef("g1a", "LCT", gantryLat, gantryLng, tollPointId = "LCT:main_tunnel"),
                TollGantryRef("g1b", "LCT", gantryLat + 0.0002, gantryLng, tollPointId = "LCT:main_tunnel"),
                TollGantryRef("g2", "LCT", -33.9000, 151.1000, tollPointId = "LCT:military_e_ramp"),
            ),
        )
        val state = TollDetectionState()

        onFix(state, registry, approachFromSouthLat, gantryLng, ts, BigDecimal.ZERO)
        val first = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal.ZERO)
        // Keyed by ROAD id here, not point id — there is only ever one charge for this road.
        assertEquals(mapOf("LCT" to BigDecimal("4.30")), first.chargedRoadsChanged)

        onFix(state, registry, -33.8973, 151.1000, ts, BigDecimal("8.0"))
        val second = onFix(state, registry, -33.9000, 151.1000, ts, BigDecimal("8.5"))
        assertTrue("a second point of a once_per_road road must never add", second.chargedRoadsChanged.isEmpty())
        assertEquals(BigDecimal("4.30"), state.chargedRoads["LCT"])
    }

    @Test
    fun `per-point road never falls back to its road-level range when a point has no price`() {
        val road = perPointRoad(
            "CCT",
            chargingPolicy = "cumulative_per_point",
            points = mapOf("CCT:main" to null),
        )
        val registry = TollRegistrySnapshot(
            roadsById = mapOf("CCT" to road),
            gantries = listOf(TollGantryRef("g1", "CCT", gantryLat, gantryLng, tollPointId = "CCT:main")),
        )
        val state = TollDetectionState()

        onFix(state, registry, approachFromSouthLat, gantryLng, ts, BigDecimal.ZERO)
        val result = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal.ZERO)

        assertTrue(result.chargedRoadsChanged.isEmpty())
        assertEquals(setOf("CCT:main"), result.newlyUnpricedRoadIds)
        assertTrue(state.chargedRoads.isEmpty())
    }

    @Test
    fun `per-point road whose gantry carries no toll point is flagged, not priced at road level`() {
        val road = perPointRoad("M2", chargingPolicy = "cumulative_per_point", points = mapOf("M2:p1" to "10.64"))
        val registry = TollRegistrySnapshot(
            roadsById = mapOf("M2" to road),
            // No tollPointId — a stale cache, or a gantry the registry never resolved.
            gantries = listOf(TollGantryRef("g1", "M2", gantryLat, gantryLng)),
        )
        val state = TollDetectionState()

        onFix(state, registry, approachFromSouthLat, gantryLng, ts, BigDecimal.ZERO)
        val result = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal.ZERO)

        assertTrue(result.chargedRoadsChanged.isEmpty())
        assertEquals(setOf("M2"), result.newlyUnpricedRoadIds)
    }

    // --- 8. WestConnex network-wide cap ------------------------------------------------------

    private fun networkRoad(id: String) = TollRoadRef(
        id = id,
        name = "Test Road $id",
        pricingModel = "distance_with_flagfall",
        chargingPolicy = "distance_metered",
        networkGroup = "WESTCONNEX",
        directional = "both",
        currentPrice = TollPriceRef(
            priceClassAMax = null,
            capClassA = BigDecimal("9.00"),
            ratePerKmClassA = BigDecimal("5.0000"),
            flagfallClassA = BigDecimal("1.80"),
            networkCapClassA = BigDecimal("12.74"),
            timeOfDayRatesClassA = null,
            confidence = "verified",
        ),
    )

    @Test
    fun `roads in one network group never bill past the shared network cap`() {
        // Each stage caps at 9.00 on its own — 18.00 together without a network clamp — against
        // WestConnex's real published 12.74 Class A cap for the whole network on one trip.
        val m4 = networkRoad("M4")
        val m8 = networkRoad("M8")
        val m4Lat = gantryLat
        val m8Lat = -33.9000
        val registry = TollRegistrySnapshot(
            roadsById = mapOf("M4" to m4, "M8" to m8),
            gantries = listOf(
                TollGantryRef("m4-g", "M4", m4Lat, gantryLng),
                TollGantryRef("m8-g", "M8", m8Lat, gantryLng),
            ),
        )
        val state = TollDetectionState()

        // Enter and run down the M4 far enough to hit its own 9.00 cap.
        onFix(state, registry, m4Lat - 0.0027, gantryLng, ts, BigDecimal.ZERO)
        onFix(state, registry, m4Lat, gantryLng, ts, BigDecimal.ZERO)
        onFix(state, registry, m4Lat, gantryLng, ts, BigDecimal("5.0"))
        assertEquals(BigDecimal("9.00"), state.chargedRoads["M4"])

        // Then the M8: on its own it would also reach 9.00, but only 3.74 of the network cap is
        // left, so THIS road absorbs the reduction (matching the backend's own reading).
        onFix(state, registry, m8Lat - 0.0027, gantryLng, ts, BigDecimal("6.0"))
        onFix(state, registry, m8Lat, gantryLng, ts, BigDecimal("6.5"))
        onFix(state, registry, m8Lat, gantryLng, ts, BigDecimal("12.0"))

        val total = state.chargedRoads.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
        assertEquals(BigDecimal("12.74"), total)
        assertEquals(BigDecimal("9.00"), state.chargedRoads["M4"])
        assertEquals(BigDecimal("3.74"), state.chargedRoads["M8"])
    }

    @Test
    fun `a road with no network group is never clamped by another road's charges`() {
        val m7 = TollRoadRef(
            id = "M7",
            name = "Westlink M7",
            pricingModel = "distance",
            chargingPolicy = "distance_metered",
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
        val registry = TollRegistrySnapshot(
            roadsById = mapOf("M7" to m7),
            gantries = listOf(TollGantryRef("g1", "M7", gantryLat, gantryLng)),
        )
        val state = TollDetectionState()

        onFix(state, registry, approachFromSouthLat, gantryLng, ts, BigDecimal.ZERO)
        onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal.ZERO)
        onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal("10.0"))

        // 0.5252 * 10 km = 5.252 -> 5.25, well under the 10.50 cap and unclamped by anything else.
        assertEquals(BigDecimal("5.25"), state.chargedRoads["M7"])
    }

    // --- 9. naming a charge the driver can actually recognise --------------------------------

    @Test
    fun `a per-point charge is named by road and point, not by its raw key`() {
        // The spoken toll alert and the fare breakdown both read this string. On a cumulative
        // per-point road the charge key is a TOLL POINT id, so a plain roads-by-id lookup would
        // announce "M2:north_ryde toll added" — a charge the driver cannot judge.
        val road = perPointRoad(
            "M2",
            chargingPolicy = "cumulative_per_point",
            points = mapOf("M2:north_ryde" to "10.64"),
            pointNames = mapOf("M2:north_ryde" to "North Ryde (mainline)"),
        ).copy(name = "Hills M2 Motorway")
        val registry = TollRegistrySnapshot(mapOf("M2" to road), emptyList())

        assertEquals("Hills M2 Motorway — North Ryde (mainline)", chargeDisplayName(registry, "M2:north_ryde"))
        assertEquals("Hills M2 Motorway", chargeDisplayName(registry, "M2"))
        // A key the registry no longer knows still returns something honest, never blank.
        assertEquals("GONE:point", chargeDisplayName(registry, "GONE:point"))
    }

    // --- 10. adjacent-road false positives ---------------------------------------------------
    //
    // The field report this rule comes from: a vehicle that never entered the toll road being
    // charged anyway, because Australian motorways are flanked by service roads and a tunnel's
    // gantry coordinate is the surface projection of a point underground.

    @Test
    fun `driving parallel to a toll road inside the watch radius never charges`() {
        // ~100m to the side: comfortably inside the 150m detection radius, comfortably outside
        // the 60m confirmation radius. This is the service-road case.
        val road = flatRoad("M7", directional = "both")
        val registry = TollRegistrySnapshot(
            roadsById = mapOf("M7" to road),
            gantries = listOf(
                TollGantryRef("g1", "M7", gantryLat, gantryLng),
                TollGantryRef("g2", "M7", gantryLat + 0.0002, gantryLng),
            ),
        )
        val state = TollDetectionState()
        val parallelLng = gantryLng + 0.00108 // ~100m east at this latitude

        // Drive the whole length of the corridor, offset the entire way.
        var result = onFix(state, registry, gantryLat - 0.0027, parallelLng, ts, BigDecimal.ZERO)
        for (step in 0..6) {
            result = onFix(state, registry, gantryLat + step * 0.0001, parallelLng, ts, BigDecimal(step))
        }

        assertTrue("a vehicle beside the road must never be charged", state.chargedRoads.isEmpty())
        assertTrue(result.chargedRoadsChanged.isEmpty())
        // And it must not be nagged about either -- a road driven past is not a road whose price
        // is unknown, and flagging it would just move the false charge to a manual prompt.
        assertTrue(state.unpricedRoadIds.isEmpty())
    }

    @Test
    fun `a single close pass is not enough on a road with several gantries`() {
        // The cross-street-over-a-tunnel case: one genuinely sub-60m fix, which on its own is a
        // coincidence rather than evidence of having travelled the road.
        val road = flatRoad("LCT", directional = "both")
        val registry = TollRegistrySnapshot(
            roadsById = mapOf("LCT" to road),
            gantries = listOf(
                TollGantryRef("g1", "LCT", gantryLat, gantryLng),
                TollGantryRef("g2", "LCT", -33.8100, gantryLng),
                TollGantryRef("g3", "LCT", -33.8200, gantryLng),
            ),
        )
        val state = TollDetectionState()

        onFix(state, registry, approachFromSouthLat, gantryLng, ts, BigDecimal.ZERO)
        val result = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal.ZERO)

        assertTrue(result.chargedRoadsChanged.isEmpty())
        assertEquals(setOf("g1"), state.confirmedGantries["LCT"])
    }

    @Test
    fun `a second confirmed gantry on the same road completes the corroboration`() {
        val road = flatRoad("LCT", directional = "both", amount = "4.30")
        val registry = TollRegistrySnapshot(
            roadsById = mapOf("LCT" to road),
            gantries = listOf(
                TollGantryRef("g1", "LCT", gantryLat, gantryLng),
                TollGantryRef("g2", "LCT", gantryLat + 0.0009, gantryLng), // ~100m along the corridor
                TollGantryRef("g3", "LCT", -33.8200, gantryLng),
            ),
        )
        val state = TollDetectionState()

        onFix(state, registry, approachFromSouthLat, gantryLng, ts, BigDecimal.ZERO)
        val first = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal.ZERO)
        assertTrue("still only one checkpoint", first.chargedRoadsChanged.isEmpty())

        val second = onFix(state, registry, gantryLat + 0.0009, gantryLng, ts, BigDecimal("0.1"))
        assertEquals(mapOf("LCT" to BigDecimal("4.30")), second.chargedRoadsChanged)
    }

    @Test
    fun `a road the registry only knows one gantry for still charges on one close pass`() {
        // Corroboration must never make a road permanently unchargeable. Where the dataset cannot
        // supply a second checkpoint, the tight 60m test is the whole gate -- trading a
        // guaranteed missed charge for a second opinion that does not exist is the worse deal.
        val road = flatRoad("ED", directional = "both", amount = "9.16")
        val registry = TollRegistrySnapshot(
            roadsById = mapOf("ED" to road),
            gantries = listOf(TollGantryRef("only", "ED", gantryLat, gantryLng)),
        )
        val state = TollDetectionState()

        onFix(state, registry, approachFromSouthLat, gantryLng, ts, BigDecimal.ZERO)
        val result = onFix(state, registry, gantryLat, gantryLng, ts, BigDecimal.ZERO)

        assertEquals(mapOf("ED" to BigDecimal("9.16")), result.chargedRoadsChanged)
    }
}
