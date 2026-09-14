package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.domain.fare.TollGantryRef
import au.com.threesixty.cabdispatch.domain.fare.TollPriceRef
import au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot
import au.com.threesixty.cabdispatch.domain.fare.TollRoadRef
import au.com.threesixty.cabdispatch.domain.location.inertial.InertialBillingSource
import au.com.threesixty.cabdispatch.domain.location.inertial.InertialConfidence
import au.com.threesixty.cabdispatch.domain.location.inertial.InertialEstimate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** A registry whose one priced road sits entirely INSIDE the blackout -- its gantries are
     * kilometres from both portals, so `gantryChainPath` (both portals within 250m of the SAME
     * road) can never match it. The T5453 shape: Rozelle -> M4-M8 -> M4 is three roads. */
    private fun midTunnelRoadRegistry(amount: String): TollRegistrySnapshot {
        val road = TollRoadRef(
            id = "MID_TUNNEL",
            name = "Mid Tunnel Road",
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
                TollGantryRef("MID-1", road.id, -33.9200, 151.1530),
                TollGantryRef("MID-2", road.id, -33.9200, 151.1570),
            ),
        )
    }

    /** Publishes a usable estimate and hands the engine a dead-reckoned path that passes over
     * the mid-tunnel gantries (with a deliberate drift at the far end for the closure
     * correction to remove). */
    private class PathInertialSource(private val path: List<Pair<Double, Double>>) : InertialBillingSource {
        private val _estimate = MutableStateFlow<InertialEstimate?>(null)
        override val estimate: StateFlow<InertialEstimate?> = _estimate.asStateFlow()
        override val blackoutPath: List<Pair<Double, Double>> get() = path
        override fun onBlackoutEntered(
            entrySpeedKmh: Double,
            entryHeadingDeg: Double?,
            entryLocationFix: LocationFix?,
        ) {
            _estimate.value = InertialEstimate(
                speedKmh = 60.0, headingDegOrNull = 90.0, sigmaVMetresPerSecond = 0.2,
                confidence = InertialConfidence.HIGH, zuptCount = 0, calibrationGood = true, unreliable = false,
            )
        }
        override fun onBlackoutExited() = Unit
    }

    @Test
    fun `a road buried mid-tunnel that no corridor match can see is tolled off the dead-reckoned path`() = runTest {
        val registry = midTunnelRoadRegistry("5.85")
        // Straight east along the tunnel's latitude, 20m steps, ending 150m SHORT of the real
        // exit (dead-reckoning under-ran) -- closure correction stretches it to the exit fix.
        val steps = 40
        val drPath = (0..steps).map { i ->
            TUNNEL_ENTRY_LAT to (TUNNEL_ENTRY_LNG + (TUNNEL_EXIT_LNG - 0.0016 - TUNNEL_ENTRY_LNG) * i / steps)
        }
        val inertial = PathInertialSource(drPath)
        val gps = FakeMeterGps(60.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            TollRegistryProvider { registry },
            nanoTimeSource = virtualNanoTimeSource(), wallClockNow = fixedDayWallClock(),
            inertialSpeedSource = inertial,
            inertialBillingEnabled = true,
        )
        driveThroughBlackout(gps, engine)

        val state = engine.state.value
        assertEquals("INERTIAL", state.lastResolvedBlackout?.resolution)
        assertEquals(listOf("MID_TUNNEL"), state.autoTollsApplied.map { it.roadId })
        assertEquals(BigDecimal("5.85"), state.breakdown.tolls)
    }
}
