package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.fare.TollGantryRef
import au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot
import au.com.threesixty.cabdispatch.domain.location.GeoMath
import au.com.threesixty.cabdispatch.domain.location.inertial.InertialBillingSource
import au.com.threesixty.cabdispatch.domain.location.inertial.InertialConfidence
import au.com.threesixty.cabdispatch.domain.location.inertial.InertialEstimate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W2 (inertial dead-reckoning through a GPS blackout, 2026-09-12): billing integration tests for
 * [FareEngineImpl.tick]'s `inertialSpeedSource`/`inertialBillingEnabled` path. Kept as its own file
 * rather than added to [MeterAccuracyTest] purely to avoid two concurrent workstreams editing the
 * same large file -- style and scaffolding (`FakeMeterGps`, `advanceOneTickWithNoNewFix`,
 * `virtualNanoTimeSource`, `fixedDayWallClock`) are all borrowed verbatim from that file's own
 * conventions; see [FakeMeterGps.kt] for what each does.
 *
 * [inertialBillingEnabled] defaults to the real `BuildConfig.INERTIAL_BILLING_ENABLED` (off) in
 * production; every test below that wants the INERTIAL path passes `true` explicitly (the seam
 * exists precisely so this class of test does not need a separate build variant -- see
 * [FareEngineImpl]'s own `inertialBillingEnabled` parameter doc).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FareEngineImplInertialBlackoutTest {

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

    /** Straight two-gantry road whose registry-known distance is EXACTLY the great-circle distance
     * between [entryLat]/[entryLng] and [exitLat]/[exitLng] -- gantries sit precisely on the fixes
     * a test places, so `knownCorridorDistanceKm`'s own greedy-chain walk reduces to one leg. */
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

    private class FakeInertialBillingSource : InertialBillingSource {
        private val _estimate = MutableStateFlow<InertialEstimate?>(null)
        override val estimate: StateFlow<InertialEstimate?> = _estimate.asStateFlow()
        var enteredSpeedKmh: Double? = null
            private set
        var enteredHeadingDeg: Double? = null
            private set
        var exitedCount = 0
            private set

        fun publish(est: InertialEstimate?) {
            _estimate.value = est
        }

        override fun onBlackoutEntered(
            entrySpeedKmh: Double,
            entryHeadingDeg: Double?,
            entryLocationFix: LocationFix?,
        ) {
            enteredSpeedKmh = entrySpeedKmh
            enteredHeadingDeg = entryHeadingDeg
        }

        override fun onBlackoutExited() {
            exitedCount += 1
        }
    }

    private fun usableEstimate(speedKmh: Double, zuptCount: Int = 0) = InertialEstimate(
        speedKmh = speedKmh,
        headingDegOrNull = 90.0,
        sigmaVMetresPerSecond = 0.2,
        confidence = InertialConfidence.HIGH,
        zuptCount = zuptCount,
        calibrationGood = true,
        unreliable = false,
    )

    private companion object {
        const val ENTRY_LAT = -33.87
        const val ENTRY_LNG = 151.21
        const val EXIT_LAT = -33.87
        const val EXIT_LNG = 151.219 // ~0.83 km east at this latitude
    }

    @Test
    fun `INERTIAL segment reconciles to the road-path distance when a corridor matches`() = runTest {
        val registry = straightRoadRegistry(ENTRY_LAT, ENTRY_LNG, EXIT_LAT, EXIT_LNG)
        val inertial = FakeInertialBillingSource()
        val gps = FakeMeterGps(80.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(), wallClockNow = fixedDayWallClock(),
            knownCorridorDistanceLookup = KnownCorridorDistanceLookup.of(registry),
            inertialSpeedSource = inertial,
            inertialBillingEnabled = true,
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        gps.emitFixAt(testScheduler.currentTime, ENTRY_LAT, ENTRY_LNG)
        advanceOneTickWithNoNewFix()
        gps.goDark()
        // The last fix stays believed for MAX_FIX_AGE_MS (5s) -- same grace period every F3 test
        // accounts for -- so gpsLost (and the entry-declared/estimator-seed side effect) only
        // fires once six dark ticks have aged it past that threshold.
        repeat(6) { advanceOneTickWithNoNewFix() }

        assertEquals(
            "the estimator must be seeded from the real speed at loss",
            80.0,
            inertial.enteredSpeedKmh!!,
            0.001,
        )

        inertial.publish(usableEstimate(speedKmh = 80.0))
        repeat(15) { advanceOneTickWithNoNewFix() } // billed inertially for these ticks

        assertTrue(
            "an in-progress INERTIAL blackout must show the live estimate on the dial",
            engine.state.value.blackout?.estimatedSpeedKmh == 80.0,
        )

        gps.emitFixAt(testScheduler.currentTime, EXIT_LAT, EXIT_LNG)
        advanceOneTickWithNoNewFix()

        val resolved = engine.state.value.lastResolvedBlackout
        assertTrue(resolved != null)
        assertEquals("INERTIAL", resolved!!.resolution)
        assertEquals(1, inertial.exitedCount)

        val expectedCorridorKm = GeoMath.distanceKm(ENTRY_LAT, ENTRY_LNG, EXIT_LAT, EXIT_LNG)
        assertEquals(
            "a road-path match must always win over the estimate",
            expectedCorridorKm,
            resolved.billedDistanceKm.toDouble(),
            0.01,
        )
        assertEquals("ROAD_PATH", resolved.referenceSource)
        assertTrue(resolved.estimatedDistanceKm != null)
        assertEquals("HIGH", resolved.confidence)
    }

    @Test
    fun `INERTIAL segment with no road-path match clamps to the chord bounds`() = runTest {
        val inertial = FakeInertialBillingSource()
        val gps = FakeMeterGps(80.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(), wallClockNow = fixedDayWallClock(),
            // No registry wired -- KnownCorridorDistanceLookup.NONE by default -- so nothing can
            // ever match a road path; every reconciliation here must fall to CHORD_BOUNDED.
            inertialSpeedSource = inertial,
            inertialBillingEnabled = true,
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        // A short chord: entry and exit only ~50m apart (a car-park-style loop), but the estimate
        // will claim a much larger distance was covered -- this must clamp DOWN to 1.5x the chord.
        val exitLat = -33.8704
        val exitLng = 151.21
        val chordKm = GeoMath.distanceKm(ENTRY_LAT, ENTRY_LNG, exitLat, exitLng)

        gps.emitFixAt(testScheduler.currentTime, ENTRY_LAT, ENTRY_LNG)
        advanceOneTickWithNoNewFix()
        gps.goDark()
        repeat(6) { advanceOneTickWithNoNewFix() } // ages the last fix past MAX_FIX_AGE_MS

        inertial.publish(usableEstimate(speedKmh = 80.0)) // wildly overestimates for this short chord
        repeat(20) { advanceOneTickWithNoNewFix() }

        gps.emitFixAt(testScheduler.currentTime, exitLat, exitLng)
        advanceOneTickWithNoNewFix()

        val resolved = engine.state.value.lastResolvedBlackout!!
        assertEquals("INERTIAL", resolved.resolution)
        assertEquals("CHORD_BOUNDED", resolved.referenceSource)
        assertNull(resolved.referenceDistanceKm)
        assertTrue(
            "billed must be clamped to <= 1.5x the chord (chord=$chordKm, billed=${resolved.billedDistanceKm})",
            resolved.billedDistanceKm.toDouble() <= chordKm * 1.5 + 0.001,
        )
        assertTrue(
            "billed must never be below the chord itself",
            resolved.billedDistanceKm.toDouble() >= chordKm - 0.001,
        )
    }

    @Test
    fun `DIRECT OWNER DECISION 2026-09-14 - losing calibration mid-blackout no longer stops accrual`() = runTest {
        // This test used to be named "estimator invalidating mid-blackout resolves UNCALIBRATED
        // and applies no further correction", and asserted the opposite of what it asserts now --
        // see FareEngineImpl.kt#tick's `inertialUsable` doc for the full "why". The owner explicitly
        // chose to never let the meter go flat through a blackout again, even once an estimate has
        // stopped being calibrated/confident, over the previous, more conservative "give up and
        // bill nothing further" behaviour this test used to lock in. `calibrationGood` flipping to
        // false mid-blackout (tablet disturbed in its mount, or simply never calibrated in the
        // first place) no longer matters to `inertialUsable` at all -- only whether an estimate
        // exists and the master flag is on.
        val inertial = FakeInertialBillingSource()
        val gps = FakeMeterGps(80.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(), wallClockNow = fixedDayWallClock(),
            inertialSpeedSource = inertial,
            inertialBillingEnabled = true,
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        // A short chord, same shape as the CHORD_BOUNDED test above: entry and exit only ~50m
        // apart, but the estimate claims a much larger distance was covered.
        val exitLat = -33.8704
        val exitLng = 151.21

        gps.emitFixAt(testScheduler.currentTime, ENTRY_LAT, ENTRY_LNG)
        advanceOneTickWithNoNewFix()
        gps.goDark()
        repeat(6) { advanceOneTickWithNoNewFix() } // ages the last fix past MAX_FIX_AGE_MS

        inertial.publish(usableEstimate(speedKmh = 80.0))
        repeat(5) { advanceOneTickWithNoNewFix() }
        val billedBeforeCalibrationLoss = engine.state.value.distanceKm

        // Calibration lost mid-blackout (tablet disturbed in its mount) -- must NOT stop accrual.
        inertial.publish(usableEstimate(speedKmh = 80.0).copy(calibrationGood = false))
        repeat(10) { advanceOneTickWithNoNewFix() }

        assertTrue(
            "distance must keep accruing off the estimate even once calibrationGood is false",
            engine.state.value.distanceKm.toDouble() > billedBeforeCalibrationLoss.toDouble() + 0.001,
        )

        gps.emitFixAt(testScheduler.currentTime, exitLat, exitLng)
        advanceOneTickWithNoNewFix()

        val resolved = engine.state.value.lastResolvedBlackout!!
        assertEquals(
            "inertial billing engaged and stayed engaged -- this is a real INERTIAL segment, " +
                "never UNCALIBRATED, regardless of calibrationGood mid-blackout",
            "INERTIAL",
            resolved.resolution,
        )
    }

    @Test
    fun `flag off keeps a wired inertial source from changing blackout behaviour at all`() = runTest {
        val registry = straightRoadRegistry(ENTRY_LAT, ENTRY_LNG, EXIT_LAT, EXIT_LNG)
        val inertial = FakeInertialBillingSource()
        val gps = FakeMeterGps(80.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(), wallClockNow = fixedDayWallClock(),
            knownCorridorDistanceLookup = KnownCorridorDistanceLookup.of(registry),
            inertialSpeedSource = inertial,
            inertialBillingEnabled = false, // the default in production today
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        gps.emitFixAt(testScheduler.currentTime, ENTRY_LAT, ENTRY_LNG)
        advanceOneTickWithNoNewFix()
        gps.goDark()
        repeat(6) { advanceOneTickWithNoNewFix() } // ages the last fix past MAX_FIX_AGE_MS
        inertial.publish(usableEstimate(speedKmh = 80.0)) // usable, but must never be consulted
        repeat(15) { advanceOneTickWithNoNewFix() }

        gps.emitFixAt(testScheduler.currentTime, EXIT_LAT, EXIT_LNG)
        advanceOneTickWithNoNewFix()

        val resolved = engine.state.value.lastResolvedBlackout!!
        assertEquals(
            "with the flag off this must resolve exactly like plain W1 CORRIDOR behaviour",
            "CORRIDOR",
            resolved.resolution,
        )
        assertNull(
            "the flag gates BILLING only -- estimatedDistanceKm/correction fields must stay null " +
                "for a CORRIDOR resolution exactly as they always have (shadow mode still seeds/exits " +
                "the estimator underneath, which is a separate, non-billing concern)",
            resolved.estimatedDistanceKm,
        )
    }

    @Test
    fun `closing a fare mid-blackout releases the inertial source so the next fare shadow-seeds`() = runTest {
        // Bench finding, Karachi tablet, 2026-09-14: the About-tab diagnostics read "Not yet
        // calibrated" for the whole of a fresh fare because a PREVIOUS fare had been closed while
        // GPS was lost -- the engine reset its own blackout fields without telling the inertial
        // source, which stayed in "blackout" mode (no shadow reseeding) for the rest of the process.
        val inertial = FakeInertialBillingSource()
        val gps = FakeMeterGps(60.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(), wallClockNow = fixedDayWallClock(),
            inertialSpeedSource = inertial,
            inertialBillingEnabled = true,
        )
        engine.startTrip(urbanTariffDto(), startLat = ENTRY_LAT, startLng = ENTRY_LNG)
        gps.emitFixAt(testScheduler.currentTime, ENTRY_LAT, ENTRY_LNG)
        advanceOneTickWithNoNewFix()
        gps.goDark()
        repeat(8) { advanceOneTickWithNoNewFix() } // blackout declared, never resolved
        assertEquals("the blackout must have been declared to the source", 60.0, inertial.enteredSpeedKmh)
        assertEquals(0, inertial.exitedCount)

        engine.close() // meter ended underground
        assertEquals("close() must release the open blackout", 1, inertial.exitedCount)

        // A second fare started straight after must not release it AGAIN (nothing is open now) --
        // and, symmetrically, a fare that was never closed but simply restarted must release once.
        engine.startTrip(urbanTariffDto(), startLat = ENTRY_LAT, startLng = ENTRY_LNG)
        assertEquals(1, inertial.exitedCount)
        gps.emitFixAt(testScheduler.currentTime, ENTRY_LAT, ENTRY_LNG)
        advanceOneTickWithNoNewFix()
        gps.goDark()
        repeat(8) { advanceOneTickWithNoNewFix() }
        engine.startTrip(urbanTariffDto(), startLat = ENTRY_LAT, startLng = ENTRY_LNG) // restarted mid-blackout
        assertEquals("startTrip over an open blackout must release it too", 2, inertial.exitedCount)
    }
}
