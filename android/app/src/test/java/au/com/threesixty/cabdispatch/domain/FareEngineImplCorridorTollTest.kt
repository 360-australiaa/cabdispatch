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
    private class PathInertialSource(
        private val path: List<Pair<Double, Double>>,
        private val roadLocked: Boolean = false,
        private val roadIds: Set<String> = emptySet(),
    ) : InertialBillingSource {
        private val _estimate = MutableStateFlow<InertialEstimate?>(null)
        override val estimate: StateFlow<InertialEstimate?> = _estimate.asStateFlow()
        override val blackoutPath: List<Pair<Double, Double>> get() = path
        override val blackoutPathIsRoadLocked: Boolean get() = roadLocked
        override val lockedRoadIds: Set<String> get() = roadIds
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

    /** [midTunnelRoadRegistry] shifted ~350 m north of the tunnel latitude: the surface gantry
     * sites of a real tunnel road sit that far from the tunnel alignment (Haberfield: 374 m). */
    private fun offsetMidTunnelRoadRegistry(amount: String): TollRegistrySnapshot {
        val base = midTunnelRoadRegistry(amount)
        return base.copy(gantries = base.gantries.map { it.copy(latitude = it.latitude + 0.00315) })
    }

    private fun straightTunnelPath(): List<Pair<Double, Double>> {
        val steps = 40
        return (0..steps).map { i ->
            TUNNEL_ENTRY_LAT to (TUNNEL_ENTRY_LNG + (TUNNEL_EXIT_LNG - TUNNEL_ENTRY_LNG) * i / steps)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.engineOn(
        registry: TollRegistrySnapshot,
        inertial: InertialBillingSource,
        gps: FakeMeterGps,
    ) = FareEngineImpl(
        gps,
        backgroundScope,
        TollRegistryProvider { registry },
        nanoTimeSource = virtualNanoTimeSource(), wallClockNow = fixedDayWallClock(),
        inertialSpeedSource = inertial,
        inertialBillingEnabled = true,
    )

    @Test
    fun `a road-locked tunnel path tolls gantries that sit 350 m off the alignment`() = runTest {
        // Anzac Bridge -> M4 East, 2026-09-15: at the live-GPS radii the sweep missed the Haberfield
        // entry gantry (374 m from the tunnel path) and charged the M4 for 1 km instead of 5.
        val registry = offsetMidTunnelRoadRegistry("5.85")
        val gps = FakeMeterGps(60.0)
        val inertial = PathInertialSource(straightTunnelPath(), roadLocked = true, roadIds = setOf("MID_TUNNEL"))
        val engine = engineOn(registry, inertial, gps)
        driveThroughBlackout(gps, engine)
        assertEquals(listOf("MID_TUNNEL"), engine.state.value.autoTollsApplied.map { it.roadId })
        assertEquals(BigDecimal("5.85"), engine.state.value.breakdown.tolls)
    }

    @Test
    fun `a road-locked sweep keeps the surface radii for roads the lock does not run along`() = runTest {
        // Second Anzac Bridge -> M4 East run, 2026-09-15: a flat 450 m charged the M4-M8 Link,
        // whose Haberfield ramp gantries are 400 m from the M4 East bore. Same 350 m offset road,
        // but the lock says the vehicle is on "M4", not on it.
        val registry = offsetMidTunnelRoadRegistry("5.85")
        val gps = FakeMeterGps(60.0)
        val inertial = PathInertialSource(straightTunnelPath(), roadLocked = true, roadIds = setOf("M4"))
        val engine = engineOn(registry, inertial, gps)
        driveThroughBlackout(gps, engine)
        assertTrue("not the locked road", engine.state.value.autoTollsApplied.isEmpty())
    }

    @Test
    fun `a toll-free road crossed in the sweep is neither charged nor flagged for manual entry`() = runTest {
        // Iron Cove Link, 2026-09-15 bench: `toll_free` fell through to the unpriced prompt.
        val base = midTunnelRoadRegistry("0.00")
        val free = base.roadsById.getValue("MID_TUNNEL").copy(id = "FREE_LINK", pricingModel = "toll_free")
        val registry = TollRegistrySnapshot(
            roadsById = mapOf(free.id to free),
            gantries = base.gantries.map { it.copy(tollRoadId = free.id) },
        )
        val gps = FakeMeterGps(60.0)
        val engine = engineOn(registry, PathInertialSource(straightTunnelPath()), gps)
        driveThroughBlackout(gps, engine)
        assertTrue(engine.state.value.autoTollsApplied.isEmpty())
        assertTrue("free road must not ask the driver to add a toll", engine.state.value.unpricedTollRoads.isEmpty())
    }

    @Test
    fun `a free-run dead-reckoned path keeps the tight surface radii`() = runTest {
        val registry = offsetMidTunnelRoadRegistry("5.85")
        val gps = FakeMeterGps(60.0)
        val engine = engineOn(registry, PathInertialSource(straightTunnelPath(), roadLocked = false), gps)
        driveThroughBlackout(gps, engine)
        assertTrue("350 m off a guessed line is not on the road", engine.state.value.autoTollsApplied.isEmpty())
    }
}
