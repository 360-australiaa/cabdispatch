package au.com.threesixty.cabdispatch.domain

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

/**
 * Field finding, T5453, Rozelle Interchange, 2026-09-14: a tunnel crossing billed NO toll and never
 * raised the "unpriced road" prompt, because every gantry of a tunnel is underground -- exactly
 * where the per-fix detector is (correctly) skipped for lack of a fix. [FareEngineImpl] now walks
 * the resolved corridor's own gantry chain through the ordinary detector at reacquisition
 * (`sweepCorridorTolls`); these pin both halves of that contract on the same bent three-gantry
 * tunnel [BlackoutTestFixtures] already uses for the distance catch-up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FareEngineImplCorridorTollTest {

    /** [bentTunnelRegistry]'s exact geometry, but priced -- a flat, both-direction road, so the
     * once-per-road charge lands as soon as two of its gantries are confirmed. */
    private fun pricedBentTunnelRegistry(amount: String): TollRegistrySnapshot {
        val road = TollRoadRef(
            id = "TUNNEL",
            name = "Test Tunnel",
            pricingModel = "flat",
            directional = "both",
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
            gantries = listOf(
                TollGantryRef("TUNNEL-A", road.id, TUNNEL_ENTRY_LAT, TUNNEL_ENTRY_LNG),
                TollGantryRef("TUNNEL-B", road.id, TUNNEL_MID_LAT, TUNNEL_MID_LNG),
                TollGantryRef("TUNNEL-C", road.id, TUNNEL_EXIT_LAT, TUNNEL_EXIT_LNG),
            ),
        )
    }

    private suspend fun kotlinx.coroutines.test.TestScope.driveThroughBlackout(
        gps: FakeMeterGps,
        engine: FareEngineImpl,
    ) {
        run {
            engine.startTrip(urbanTariffDto(), startLat = TUNNEL_ENTRY_LAT, startLng = TUNNEL_ENTRY_LNG - 0.01)
            runCurrent() // registry snapshot load
            // Live, moving, at the tunnel mouth -- then the sky closes over.
            gps.emitFixAt(testScheduler.currentTime, TUNNEL_ENTRY_LAT, TUNNEL_ENTRY_LNG - 0.002)
            advanceOneTickWithNoNewFix()
            gps.emitFixAt(testScheduler.currentTime, TUNNEL_ENTRY_LAT, TUNNEL_ENTRY_LNG)
            advanceOneTickWithNoNewFix()
            gps.goDark()
            repeat(6) { advanceOneTickWithNoNewFix() } // ages the last fix past MAX_FIX_AGE_MS
            repeat(20) { advanceOneTickWithNoNewFix() } // underground: no fixes, no gantries seen
            // Reacquired just past the far portal.
            gps.emitFixAt(testScheduler.currentTime, TUNNEL_EXIT_LAT, TUNNEL_EXIT_LNG + 0.001)
            advanceOneTickWithNoNewFix()
        }
    }

    @Test
    fun `a priced tunnel crossed entirely in a blackout is tolled at reacquisition`() = runTest {
        val registry = pricedBentTunnelRegistry("6.48")
        val gps = FakeMeterGps(60.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            TollRegistryProvider { registry },
            nanoTimeSource = virtualNanoTimeSource(), wallClockNow = fixedDayWallClock(),
            knownCorridorDistanceLookup = KnownCorridorDistanceLookup.of(registry),
        )
        driveThroughBlackout(gps, engine)

        val state = engine.state.value
        assertEquals("CORRIDOR", state.lastResolvedBlackout?.resolution)
        assertEquals(listOf("TUNNEL"), state.autoTollsApplied.map { it.roadId })
        assertEquals(BigDecimal("6.48"), state.breakdown.tolls)
        assertTrue("the driver/passenger must be told a toll was added", state.lastAutoTollAlert != null)
    }

    @Test
    fun `an unpriced tunnel crossed in a blackout raises the manual-toll prompt instead`() = runTest {
        val registry = bentTunnelRegistry() // `unpriced`, exactly the registry's Rozelle Interchange shape
        val gps = FakeMeterGps(60.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            TollRegistryProvider { registry },
            nanoTimeSource = virtualNanoTimeSource(), wallClockNow = fixedDayWallClock(),
            knownCorridorDistanceLookup = KnownCorridorDistanceLookup.of(registry),
        )
        driveThroughBlackout(gps, engine)

        val state = engine.state.value
        assertEquals("CORRIDOR", state.lastResolvedBlackout?.resolution)
        assertTrue("nothing may be charged for a road with no published price", state.autoTollsApplied.isEmpty())
        assertEquals(0, BigDecimal.ZERO.compareTo(state.breakdown.tolls))
        assertEquals(
            "the road must be surfaced for the driver to add by hand",
            listOf("TUNNEL"),
            state.unpricedTollRoads.map { it.roadId },
        )
    }
}
