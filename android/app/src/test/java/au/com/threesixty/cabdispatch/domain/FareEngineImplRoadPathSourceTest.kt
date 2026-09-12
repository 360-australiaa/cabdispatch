package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.fare.TollGantryRef
import au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot
import au.com.threesixty.cabdispatch.domain.location.inertial.InertialBillingSource
import au.com.threesixty.cabdispatch.domain.location.inertial.InertialConfidence
import au.com.threesixty.cabdispatch.domain.location.inertial.InertialEstimate
import au.com.threesixty.cabdispatch.domain.location.roadpath.CompositeRoadPath
import au.com.threesixty.cabdispatch.domain.location.roadpath.RoadPath
import au.com.threesixty.cabdispatch.domain.location.roadpath.RoadPathSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W3 (road-geometry constraint sources, 2026-09-12 plan), task 6: proves
 * [FareEngineImpl]'s new `roadPathSource` constructor parameter actually reaches
 * [FareState.lastResolvedBlackout]'s `referenceDistanceKm` for an INERTIAL blackout, and that it is
 * consulted BEFORE the plain [KnownCorridorDistanceLookup]/toll-registry fallback -- see
 * `FareEngine.kt`'s own `lookupRoadPathKm` doc for the precedence this exercises.
 *
 * Kept as its own file, same reasoning [FareEngineImplInertialBlackoutTest]'s own doc gives for
 * being separate from [MeterAccuracyTest]: a distinct workstream (W3, not W2), reusing that same
 * file's `FakeMeterGps`/`virtualNanoTimeSource`/`fixedDayWallClock`/`advanceOneTickWithNoNewFix`
 * scaffolding rather than duplicating it. [FareEngineImplInertialBlackoutTest] itself is untouched
 * by this file, per this workstream's own "never edit W2's already-tested file" instruction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FareEngineImplRoadPathSourceTest {

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

    /** Same fixture shape as [FareEngineImplInertialBlackoutTest]'s own `straightRoadRegistry` --
     * a plain toll-registry corridor match, kept deliberately DIFFERENT in length from
     * [FakeRoadPathSource]'s own fixed answer below, so a test can tell which one actually won. */
    private fun straightRoadRegistry(
        entryLat: Double,
        entryLng: Double,
        exitLat: Double,
        exitLng: Double,
    ): TollRegistrySnapshot = TollRegistrySnapshot(
        roadsById = emptyMap(),
        gantries = listOf(
            TollGantryRef(id = "g-entry", tollRoadId = "T1", latitude = entryLat, longitude = entryLng),
            TollGantryRef(id = "g-exit", tollRoadId = "T1", latitude = exitLat, longitude = exitLng),
        ),
    )

    /** A [RoadPathSource] with a fixed, known answer -- no real geometry, just a distinctive
     * distance a test can tell apart from the plain corridor lookup's own answer. */
    private class FakeRoadPathSource(private val distanceM: Double?) : RoadPathSource {
        var pathAtCallCount = 0
            private set

        override fun pathAt(entryFix: LocationFix): RoadPath? {
            pathAtCallCount += 1
            val fixedDistanceM = distanceM ?: return null
            return object : RoadPath {
                override fun advance(metres: Double) = entryFix.lat to entryFix.lng
                override fun distanceTo(exitFix: LocationFix): Double? = fixedDistanceM
            }
        }
    }

    private class FakeInertialBillingSource : InertialBillingSource {
        private val _estimate = MutableStateFlow<InertialEstimate?>(null)
        override val estimate: StateFlow<InertialEstimate?> = _estimate.asStateFlow()

        fun publish(est: InertialEstimate?) {
            _estimate.value = est
        }

        override fun onBlackoutEntered(
            entrySpeedKmh: Double,
            entryHeadingDeg: Double?,
            entryLocationFix: LocationFix?,
        ) = Unit

        override fun onBlackoutExited() = Unit
    }

    private fun usableEstimate(speedKmh: Double) = InertialEstimate(
        speedKmh = speedKmh,
        headingDegOrNull = 90.0,
        sigmaVMetresPerSecond = 0.2,
        confidence = InertialConfidence.HIGH,
        zuptCount = 0,
        calibrationGood = true,
        unreliable = false,
    )

    private companion object {
        const val ENTRY_LAT = -33.87
        const val ENTRY_LNG = 151.21
        const val EXIT_LAT = -33.87
        const val EXIT_LNG = 151.219 // ~0.83 km east at this latitude -- the plain corridor's own answer
        const val ROAD_PATH_DISTANCE_M = 2_500.0 // deliberately different from the corridor's ~0.83 km
    }

    @Test
    fun `a wired roadPathSource reaches referenceDistanceKm and wins over the plain corridor lookup`() = runTest {
        val registry = straightRoadRegistry(ENTRY_LAT, ENTRY_LNG, EXIT_LAT, EXIT_LNG)
        val roadPathSource = FakeRoadPathSource(ROAD_PATH_DISTANCE_M)
        val inertial = FakeInertialBillingSource()
        val gps = FakeMeterGps(80.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(),
            wallClockNow = fixedDayWallClock(),
            // Wired too, deliberately -- this is what proves roadPathSource is tried FIRST, not
            // merely that it works when it is the only source available.
            knownCorridorDistanceLookup = KnownCorridorDistanceLookup.of(registry),
            inertialSpeedSource = inertial,
            inertialBillingEnabled = true,
            roadPathSource = roadPathSource,
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        gps.emitFixAt(testScheduler.currentTime, ENTRY_LAT, ENTRY_LNG)
        advanceOneTickWithNoNewFix()
        gps.goDark()
        repeat(6) { advanceOneTickWithNoNewFix() } // ages the last fix past MAX_FIX_AGE_MS

        inertial.publish(usableEstimate(speedKmh = 80.0))
        repeat(15) { advanceOneTickWithNoNewFix() }

        gps.emitFixAt(testScheduler.currentTime, EXIT_LAT, EXIT_LNG)
        advanceOneTickWithNoNewFix()

        val resolved = engine.state.value.lastResolvedBlackout
        assertTrue(resolved != null)
        assertEquals("INERTIAL", resolved!!.resolution)
        assertEquals("ROAD_PATH", resolved.referenceSource)
        assertEquals(
            "the roadPathSource's own answer must be what reaches referenceDistanceKm/billedDistanceKm, " +
                "not the plain corridor lookup's different figure",
            ROAD_PATH_DISTANCE_M / 1000.0,
            resolved.referenceDistanceKm!!.toDouble(),
            0.001,
        )
        assertEquals(ROAD_PATH_DISTANCE_M / 1000.0, resolved.billedDistanceKm.toDouble(), 0.001)
        assertTrue("pathAt must actually have been called", roadPathSource.pathAtCallCount > 0)
    }

    @Test
    fun `a roadPathSource with no match falls back to the plain corridor lookup unchanged`() = runTest {
        val registry = straightRoadRegistry(ENTRY_LAT, ENTRY_LNG, EXIT_LAT, EXIT_LNG)
        val roadPathSource = FakeRoadPathSource(distanceM = null) // never matches -- pathAt returns null
        val inertial = FakeInertialBillingSource()
        val gps = FakeMeterGps(80.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(),
            wallClockNow = fixedDayWallClock(),
            knownCorridorDistanceLookup = KnownCorridorDistanceLookup.of(registry),
            inertialSpeedSource = inertial,
            inertialBillingEnabled = true,
            roadPathSource = roadPathSource,
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        gps.emitFixAt(testScheduler.currentTime, ENTRY_LAT, ENTRY_LNG)
        advanceOneTickWithNoNewFix()
        gps.goDark()
        repeat(6) { advanceOneTickWithNoNewFix() }

        inertial.publish(usableEstimate(speedKmh = 80.0))
        repeat(15) { advanceOneTickWithNoNewFix() }

        gps.emitFixAt(testScheduler.currentTime, EXIT_LAT, EXIT_LNG)
        advanceOneTickWithNoNewFix()

        val resolved = engine.state.value.lastResolvedBlackout!!
        assertEquals("INERTIAL", resolved.resolution)
        assertEquals("ROAD_PATH", resolved.referenceSource) // the corridor lookup is ALSO a road path match
        val expectedCorridorKm = au.com.threesixty.cabdispatch.domain.location.GeoMath.distanceKm(
            ENTRY_LAT, ENTRY_LNG, EXIT_LAT, EXIT_LNG,
        )
        assertEquals(
            "an unmatched roadPathSource must fall back to lookupKnownCorridorKm exactly as W2 left it",
            expectedCorridorKm,
            resolved.billedDistanceKm.toDouble(),
            0.01,
        )
    }

    @Test
    fun `CompositeRoadPath precedence -- a higher-priority match wins over a lower-priority one`() = runTest {
        val inertial = FakeInertialBillingSource()
        val gps = FakeMeterGps(80.0)
        val higherPriority = FakeRoadPathSource(ROAD_PATH_DISTANCE_M)
        val lowerPriority = FakeRoadPathSource(9_999.0) // would also match, but must never be asked
        val composite = CompositeRoadPath(listOf(higherPriority, lowerPriority))
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(),
            wallClockNow = fixedDayWallClock(),
            inertialSpeedSource = inertial,
            inertialBillingEnabled = true,
            roadPathSource = composite,
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        gps.emitFixAt(testScheduler.currentTime, ENTRY_LAT, ENTRY_LNG)
        advanceOneTickWithNoNewFix()
        gps.goDark()
        repeat(6) { advanceOneTickWithNoNewFix() }

        inertial.publish(usableEstimate(speedKmh = 80.0))
        repeat(15) { advanceOneTickWithNoNewFix() }

        gps.emitFixAt(testScheduler.currentTime, EXIT_LAT, EXIT_LNG)
        advanceOneTickWithNoNewFix()

        val resolved = engine.state.value.lastResolvedBlackout!!
        assertEquals(
            "the first (higher-priority) source's match must win outright",
            ROAD_PATH_DISTANCE_M / 1000.0,
            resolved.referenceDistanceKm!!.toDouble(),
            0.001,
        )
        assertEquals(1, higherPriority.pathAtCallCount)
        assertEquals(
            "CompositeRoadPath must never even ask a lower-priority source once a higher one matched",
            0,
            lowerPriority.pathAtCallCount,
        )
    }
}
