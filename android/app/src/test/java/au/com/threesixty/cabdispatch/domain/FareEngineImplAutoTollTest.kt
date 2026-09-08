package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.fare.TollGantryRef
import au.com.threesixty.cabdispatch.domain.fare.TollPriceRef
import au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot
import au.com.threesixty.cabdispatch.domain.fare.TollRoadRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
