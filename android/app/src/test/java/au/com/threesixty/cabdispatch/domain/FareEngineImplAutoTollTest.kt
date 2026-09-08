package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.fare.FareEngine as CalcFareEngine
import au.com.threesixty.cabdispatch.domain.fare.FareState as CalcFareState
import au.com.threesixty.cabdispatch.domain.fare.URBAN_TARIFF
import au.com.threesixty.cabdispatch.domain.fare.airportFixedFare
import au.com.threesixty.cabdispatch.domain.fare.TollGantryRef
import au.com.threesixty.cabdispatch.domain.fare.TollPriceRef
import au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot
import au.com.threesixty.cabdispatch.domain.fare.TollRoadRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Wires [FareEngineImpl]'s live auto-toll detection (see `domain/fare/TollDetector.kt`) end to end
 * against a fake [SpeedSource] + a fake [TollRegistryProvider] — no Room, no network, plain JVM.
 * Covers exactly the scenarios the feature's contract calls out as load-bearing: an auto-detected
 * toll is billed and visible on an ordinary metered trip; the SAME auto-detected toll is recorded
 * but never inflates a negotiated ("Set Price") trip's agreed total (mirrors
 * [FareEngineImplRunningDisplayTest]'s existing manual-[TollPreset] negotiated-price tests, for the
 * automatic path); the driver can remove a false positive; and a never-cached (offline-empty)
 * registry detects nothing and never blocks the trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FareEngineImplAutoTollTest {

    private fun urbanTariffDto(): TariffDto = TariffDto(
        id = "tariff-urban-2026",
        tenantId = null,
        name = "urban-2026",
        region = "urban",
        effectiveFrom = "2026-06-01T00:00:00+00:00",
        booked = false,
        flagFall = "5.17",
        peakCharge = "2.65",
        distRate1 = "2.61",
        distRate2 = "2.37",
        nightRate1 = "3.10",
        nightRate2 = "2.82",
        waitingRatePerMin = "1.130",
        pslAmount = "1.32",
        createdAt = "2026-06-01T00:00:00+00:00",
        updatedAt = "2026-06-01T00:00:00+00:00",
    )

    private val gantryLat = -33.8000
    private val gantryLng = 151.0000

    /** A real-shaped, one_way, flat-priced road (mirrors "Military Road E-Ramp" in the real
     * dataset) — charges on the very first fix, no bearing history needed, so this test doesn't
     * also need to simulate a two-fix approach just to get a trusted bearing. */
    private fun oneWayRoadRegistry(amount: String = "2.15"): TollRegistrySnapshot {
        val road = TollRoadRef(
            id = "MILITARY_E_RAMP",
            name = "Military Road E-Ramp",
            pricingModel = "flat",
            directional = "one_way",
            currentPrice = TollPriceRef(
                priceClassAMax = BigDecimal(amount),
                capClassA = null,
                ratePerKmClassA = null,
                flagfallClassA = null,
                timeOfDayRatesClassA = null,
                confidence = "verified",
            ),
        )
        return TollRegistrySnapshot(
            roadsById = mapOf(road.id to road),
            gantries = listOf(TollGantryRef("g1", road.id, gantryLat, gantryLng)),
        )
    }

    @Test
    fun `an auto-detected toll is billed and visible on an ordinary metered trip`() = runTest {
        val speedSource = FakeMeterGps(0.0)
        val engine = FareEngineImpl(
            speedSource,
            backgroundScope,
            TollRegistryProvider { oneWayRoadRegistry() },
            nanoTimeSource = virtualNanoTimeSource(),
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)
        runCurrent() // let the async registry-snapshot load (see FareEngineImpl.startTrip) complete

        speedSource.emitFixAt(testScheduler.currentTime, gantryLat, gantryLng)
        // No new fix on this tick: the vehicle stays at the gantry the line above put it at.
        advanceOneTickWithNoNewFix()

        val state = engine.state.value
        assertEquals(1, state.autoTollsApplied.size)
        assertEquals("MILITARY_E_RAMP", state.autoTollsApplied.first().roadId)
        assertEquals(BigDecimal("2.15"), state.breakdown.tolls)
        // flagfall 5.17 + psl 1.32 + toll 2.15 = 8.64, plus the one second of WAITING-mode accrual
        // the tick itself drives at speed 0 (1/60 min * 1.130 c/min ≈ 0.0188) = 8.6588.
        //
        // 8.65, not the 8.66 this asserted before F8. The dial no longer sums [FareBreakdown]
        // itself; it publishes the pure engine's own `close(...).grandTotal`, which truncates to
        // cents per Act s76(5)/(6) (`roundDownToCent`) rather than rounding half-up, because the
        // regulated maximum fare must never be exceeded. 8.6588 -> 8.65. The old expectation was
        // the naive sum rounded UP — the dial previewing a fare one cent higher than the bill that
        // would actually be charged.
        assertEquals(BigDecimal("8.65"), state.total.setScale(2, RoundingMode.HALF_UP))
    }

    @Test
    fun `the same auto-detected toll is recorded but never inflates a negotiated fixed price`() = runTest {
        val speedSource = FakeMeterGps(0.0)
        val engine = FareEngineImpl(
            speedSource,
            backgroundScope,
            TollRegistryProvider { oneWayRoadRegistry() },
            nanoTimeSource = virtualNanoTimeSource(),
        )
        engine.startTrip(
            urbanTariffDto(),
            startLat = -33.87,
            startLng = 151.21,
            negotiatedTotal = BigDecimal("50.00"),
        )
        runCurrent()

        speedSource.emitFixAt(testScheduler.currentTime, gantryLat, gantryLng)
        // No new fix on this tick: the vehicle stays at the gantry the line above put it at.
        advanceOneTickWithNoNewFix()

        val state = engine.state.value
        // Recorded for audit — the toll genuinely happened and is still tracked...
        assertEquals(1, state.autoTollsApplied.size)
        assertEquals(BigDecimal("2.15"), state.breakdown.tolls)
        // ...but the passenger is billed EXACTLY the agreed amount, never 50.00 + 2.15.
        assertEquals(BigDecimal("50.00"), state.total)
    }

    @Test
    fun `the driver can remove a false-positive auto-detected toll`() = runTest {
        val speedSource = FakeMeterGps(0.0)
        val engine = FareEngineImpl(
            speedSource,
            backgroundScope,
            TollRegistryProvider { oneWayRoadRegistry() },
            nanoTimeSource = virtualNanoTimeSource(),
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)
        runCurrent()
        speedSource.emitFixAt(testScheduler.currentTime, gantryLat, gantryLng)
        // No new fix on this tick: the vehicle stays at the gantry the line above put it at.
        advanceOneTickWithNoNewFix()
        assertEquals(1, engine.state.value.autoTollsApplied.size)
        val tollsBeforeRemoval = engine.state.value.breakdown.tolls

        engine.removeAutoToll("MILITARY_E_RAMP")

        val state = engine.state.value
        assertTrue("removing the only auto-toll must clear the list", state.autoTollsApplied.isEmpty())
        // "0.00", not BigDecimal.ZERO: assertEquals on BigDecimal compares SCALE too, and every
        // money figure the engine produces is fixed at 2dp (same convention as the "2.15"
        // assertions above). Comparing against a scale-0 zero fails on a correct result.
        assertEquals(BigDecimal("0.00"), state.breakdown.tolls)
        assertTrue(state.breakdown.tolls < tollsBeforeRemoval)

        // Re-crossing the same gantry afterwards must not silently re-add it.
        speedSource.emitFixAt(testScheduler.currentTime, gantryLat, gantryLng + 0.00001)
        advanceOneTickWithNoNewFix()
        assertTrue(engine.state.value.autoTollsApplied.isEmpty())
    }

    @Test
    fun `an empty, never-cached toll registry detects nothing and never blocks the trip`() = runTest {
        val speedSource = FakeMeterGps(0.0)
        // No TollRegistryProvider passed — defaults to TollRegistryProvider.EMPTY, the honest
        // "offline, or nothing has ever been cached" fallback (see that companion's own doc).
        val engine = FareEngineImpl(speedSource, backgroundScope, nanoTimeSource = virtualNanoTimeSource())
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)
        runCurrent()

        speedSource.emitFixAt(testScheduler.currentTime, gantryLat, gantryLng)
        // No new fix on this tick: the vehicle stays at the gantry the line above put it at.
        advanceOneTickWithNoNewFix()

        val state = engine.state.value
        assertTrue(state.autoTollsApplied.isEmpty())
        assertTrue(state.unpricedTollRoads.isEmpty())
        assertEquals(BigDecimal.ZERO, state.breakdown.tolls)
        // The ordinary metered accrual must be completely unaffected — flagfall 5.17 + psl 1.32,
        // plus the same one second of WAITING-mode accrual as the other test above: 6.5088.
        //
        // 6.50, not the 6.51 this asserted before F8. The dial no longer sums [FareBreakdown]
        // itself; it publishes the pure engine's own `close(...).grandTotal`, which applies
        // `roundDownToCent` per Act s76(5)/(6) — the regulated maximum fare must never be exceeded,
        // so a computed subtotal is truncated rather than rounded up. 6.5088 -> 6.50. The old value
        // was the naive sum rounded half-up, i.e. the dial showing one cent MORE than the bill it
        // was previewing. This test now pins the billed figure, which is the point of F8.
        assertEquals(BigDecimal("6.50"), state.total.setScale(2, RoundingMode.HALF_UP))
    }

    // ---- Sydney Airport pickup fee (JurisdictionConfig.NSW.airportAccessFee) --------------------

    @Test
    fun `a hiring that STARTS inside the airport precinct is charged the access fee once`() = runTest {
        val speedSource = FakeMeterGps(0.0)
        val engine = FareEngineImpl(speedSource, backgroundScope, TollRegistryProvider { oneWayRoadRegistry() }, nanoTimeSource = virtualNanoTimeSource())
        // T2/T3 rank side of Sydney Airport -- inside the configured precinct.
        engine.startTrip(urbanTariffDto(), startLat = -33.9455, startLng = 151.1795)
        runCurrent()
        assertEquals(BigDecimal("6.43"), engine.state.value.breakdown.tolls)
    }

    @Test
    fun `a manual Airport preset after the automatic fee does not charge it twice`() = runTest {
        val speedSource = FakeMeterGps(0.0)
        val engine = FareEngineImpl(speedSource, backgroundScope, TollRegistryProvider { oneWayRoadRegistry() }, nanoTimeSource = virtualNanoTimeSource())
        engine.startTrip(urbanTariffDto(), startLat = -33.9399, startLng = 151.1753)
        runCurrent()
        engine.addToll(TollPresets.AIRPORT)
        assertEquals(BigDecimal("6.43"), engine.state.value.breakdown.tolls)
    }

    @Test
    fun `a hiring that starts elsewhere pays no airport fee, and the manual preset still works`() = runTest {
        val speedSource = FakeMeterGps(0.0)
        val engine = FareEngineImpl(speedSource, backgroundScope, TollRegistryProvider { oneWayRoadRegistry() }, nanoTimeSource = virtualNanoTimeSource())
        // Sydney CBD: ~9 km from the precinct centre. The fee is for PICKUPS at the airport;
        // a drop-off that later drives in is never charged by position, so start position is
        // the only thing tested here.
        engine.startTrip(urbanTariffDto(), startLat = -33.8688, startLng = 151.2093)
        runCurrent()
        assertEquals(BigDecimal.ZERO.setScale(2), engine.state.value.breakdown.tolls.setScale(2))
        engine.addToll(TollPresets.AIRPORT)
        assertEquals(BigDecimal("6.43"), engine.state.value.breakdown.tolls)
    }

    // ---- Airport access fee from server-defined airport zones (AirportZoneLookup seam) -------

    private val t1Rank = AirportZone("t1", "T1 International", -33.9361, 151.1656, radiusM = 300.0, fee = BigDecimal("6.43"))
    private val t2Rank = AirportZone("t2", "T2 Domestic", -33.9330, 151.1800, radiusM = 250.0, fee = BigDecimal("6.43"))
    /** A wider circle around the same domestic ranks, priced differently on purpose so the test
     * can tell WHICH zone's fee was charged when both contain the start fix. */
    private val domesticPrecinct = AirportZone("t23", "T2/T3 Domestic precinct", -33.9330, 151.1800, radiusM = 600.0, fee = BigDecimal("7.00"))

    private fun TestScope.engineWith(
        speedSource: FakeMeterGps,
        lookup: AirportZoneLookup,
    ) = FareEngineImpl(
        speedSource,
        backgroundScope,
        TollRegistryProvider.EMPTY,
        nanoTimeSource = virtualNanoTimeSource(),
        airportZoneLookup = lookup,
    )

    @Test
    fun `a hiring that starts inside a cached T1 zone is charged that zone's fee, labelled with the terminal`() = runTest {
        val speedSource = FakeMeterGps(0.0)
        val engine = engineWith(speedSource, AirportZoneLookup.of(listOf(t1Rank, t2Rank, domesticPrecinct)))

        engine.startTrip(urbanTariffDto(), startLat = -33.9361, startLng = 151.1656)
        runCurrent()

        val state = engine.state.value
        assertEquals(BigDecimal("6.43"), state.breakdown.tolls)
        val entry = state.tollsApplied.single()
        assertEquals(TollPresets.AIRPORT.id, entry.id) // same ledger id as the manual chip
        assertEquals("Airport access fee · T1 International", entry.label)
        assertEquals("T1 International", entry.airportZoneName)
        // ...and what the receipt will print from the persisted record of that entry.
        assertEquals(
            "incl. Airport access fee \$6.43 (T1 International)",
            AirportAccessFeeRecord.fromLedger(state.tollsApplied)!!.subLine(),
        )
        // The manual chip afterwards is a no-op, never a second fee.
        engine.addToll(TollPresets.AIRPORT)
        assertEquals(BigDecimal("6.43"), engine.state.value.breakdown.tolls)
        assertEquals(1, engine.state.value.tollsApplied.size)
    }

    @Test
    fun `overlapping T2 and T2-T3 zones charge the SMALLER zone's fee only, once`() = runTest {
        val speedSource = FakeMeterGps(0.0)
        val engine = engineWith(speedSource, AirportZoneLookup.of(listOf(domesticPrecinct, t1Rank, t2Rank)))

        // On the T2 rank: inside the 250m T2 circle AND the 600m precinct circle.
        engine.startTrip(urbanTariffDto(), startLat = -33.9331, startLng = 151.1801)
        runCurrent()

        val state = engine.state.value
        assertEquals(1, state.tollsApplied.size)
        assertEquals("T2 Domestic", state.tollsApplied.single().airportZoneName)
        assertEquals(BigDecimal("6.43"), state.breakdown.tolls) // not 7.00, and not 13.43
    }

    @Test
    fun `an empty, never-synced zone cache falls back to the legacy 1,8 km precinct circle`() = runTest {
        val speedSource = FakeMeterGps(0.0)
        // AirportZoneLookup.UNSYNCED is also the constructor default -- the pre-existing airport
        // tests above exercise that path; this one names it explicitly.
        val engine = engineWith(speedSource, AirportZoneLookup.UNSYNCED)

        // ~1 km from the compiled precinct centre: inside the 1.8 km circle, but NOT inside any
        // rank zone the tests above use -- the legacy circle is what charges here.
        engine.startTrip(urbanTariffDto(), startLat = -33.9455, startLng = 151.1795)
        runCurrent()

        val state = engine.state.value
        assertEquals(BigDecimal("6.43"), state.breakdown.tolls)
        val entry = state.tollsApplied.single()
        assertEquals(TollPresets.AIRPORT, entry) // the compiled preset, no terminal name
        assertEquals("incl. Airport access fee \$6.43", AirportAccessFeeRecord.fromLedger(state.tollsApplied)!!.subLine())
    }

    @Test
    fun `an empty zone LIST (of-empty) is treated as never synced, not as no-fee`() = runTest {
        val speedSource = FakeMeterGps(0.0)
        val engine = engineWith(speedSource, AirportZoneLookup.of(emptyList()))
        engine.startTrip(urbanTariffDto(), startLat = -33.9455, startLng = 151.1795)
        runCurrent()
        assertEquals(BigDecimal("6.43"), engine.state.value.breakdown.tolls)
    }

    @Test
    fun `with zones cached, a hiring that starts in the CBD pays nothing - even inside the old precinct circle`() = runTest {
        val speedSource = FakeMeterGps(0.0)
        val engine = engineWith(speedSource, AirportZoneLookup.of(listOf(t1Rank, t2Rank, domesticPrecinct)))

        engine.startTrip(urbanTariffDto(), startLat = -33.8688, startLng = 151.2093)
        runCurrent()
        assertTrue(engine.state.value.tollsApplied.isEmpty())
        assertEquals(BigDecimal.ZERO.setScale(2), engine.state.value.breakdown.tolls.setScale(2))

        // Inside the compiled 1.8 km circle but at no rank (the P2 car park side, say): once the
        // real zones are cached they are authoritative, and the old circle no longer charges.
        val engine2 = engineWith(FakeMeterGps(0.0), AirportZoneLookup.of(listOf(t1Rank, t2Rank, domesticPrecinct)))
        engine2.startTrip(urbanTariffDto(), startLat = -33.9455, startLng = 151.1795)
        runCurrent()
        assertTrue(engine2.state.value.tollsApplied.isEmpty())
    }

    @Test
    fun `a Set Price hiring from the T1 rank records the fee but bills exactly the agreed amount`() = runTest {
        val speedSource = FakeMeterGps(0.0)
        val engine = engineWith(speedSource, AirportZoneLookup.of(listOf(t1Rank)))

        engine.startTrip(urbanTariffDto(), startLat = -33.9361, startLng = 151.1656, negotiatedTotal = BigDecimal("60.00"))
        runCurrent()

        val state = engine.state.value
        assertEquals(BigDecimal("6.43"), state.breakdown.tolls) // recorded for audit/remittance...
        assertEquals(BigDecimal("60.00"), state.total) // ...never added on top of the agreed price
    }

    @Test
    fun `the Sydney Airport fixed fare never carries the access fee - close() bills the flat figure and zero tolls`() {
        // The fixed fare is applied at close time from the persisted trip type
        // (TripFareReconstruction sets fixedFare for type == "airport_fixed"); the pure engine's
        // fixedFare branch is what suppresses it. Pinned here with the fee's own figure.
        val state = CalcFareState(tariff = URBAN_TARIFF)
        state.tolls = BigDecimal("6.43")
        state.fixedFare = airportFixedFare(state.maxiRateApplied)

        val breakdown = CalcFareEngine().close(state, includePsl = true)

        assertEquals(BigDecimal("60.00"), breakdown.grandTotal)
        assertEquals(BigDecimal.ZERO, breakdown.tolls)
    }

    @Test
    fun `driving INTO a zone mid-trip - a drop-off at the airport - never charges the fee`() = runTest {
        val speedSource = FakeMeterGps(0.0)
        val engine = engineWith(speedSource, AirportZoneLookup.of(listOf(t1Rank, t2Rank, domesticPrecinct)))

        engine.startTrip(urbanTariffDto(), startLat = -33.8688, startLng = 151.2093) // CBD pickup
        runCurrent()

        // Arrive on the T1 rank and sit there for a few ticks.
        speedSource.emitFixAt(testScheduler.currentTime, -33.9361, 151.1656)
        advanceOneTickWithNoNewFix()
        speedSource.emitFixAt(testScheduler.currentTime, -33.9361, 151.1656)
        advanceOneTickWithNoNewFix()

        val state = engine.state.value
        assertTrue(state.tollsApplied.isEmpty())
        assertEquals(BigDecimal.ZERO.setScale(2), state.breakdown.tolls.setScale(2))
    }
}
