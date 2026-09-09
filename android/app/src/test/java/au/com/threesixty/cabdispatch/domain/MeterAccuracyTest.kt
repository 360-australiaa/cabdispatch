package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.TariffDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The fare-accuracy findings of the 2026-09-08 Android architecture audit (§2.1-§2.3), pinned
 * against [FareEngineImpl] — F1 (monotonic tick), F2 (haversine distance), F3 (GPS staleness),
 * F8 (one truth for the displayed total) and T1 (manual/auto toll double-charge).
 *
 * These are money tests. Each one is written so that it **fails against the code as it was**, not
 * merely passes against the code as it is — the distinction matters, because three of the four
 * blockers here were invisible to the previous test suite precisely because its fakes could not
 * express the conditions under which the bugs appeared (see [FakeMeterGps]'s own doc).
 *
 * F6 lives in [au.com.threesixty.cabdispatch.domain.location.RealLocationProvider] rather than in
 * this engine, and is covered in `location/LocationFilteringTest.kt`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeterAccuracyTest {

    /** The 2026 Order urban rate card, matching `domain.fare.URBAN_TARIFF` field-for-field —
     * decimal-as-string per this project's wire convention. */
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

    // ================================================================================
    // F1 — the tick bills the time that actually passed, not the time it hoped for
    // ================================================================================

    @Test
    fun `a slow tick bills the real elapsed time, not one hardcoded second`() = runTest {
        // The bug: startTicking() delays 1000ms and tick() billed `elapsedSeconds = 1`, on the
        // assumption those are the same thing. delay() guarantees *at least* its argument. Under
        // GC pressure or a busy dispatcher a tick genuinely takes longer, and every millisecond of
        // the excess used to be free travel for the passenger.
        //
        // Simulated here by advancing the virtual clock 2000ms per tick instead of 1000: the
        // engine's delay(1000) is satisfied, and 2.0s of wall clock have really passed.
        val gps = FakeMeterGps(0.0)
        val engine = FareEngineImpl(gps, backgroundScope, nanoTimeSource = virtualNanoTimeSource())
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        repeat(30) {
            gps.emitFixAt(testScheduler.currentTime)
            advanceTimeBy(2000)
            runCurrent()
        }

        // 30 ticks x 2.0s = 60s of waiting at 1.130/min = exactly 1.13.
        // The old code billed 30 x 1s = 30s = 0.565 — it charged half of what it should have, and
        // the shortfall grew with device load without bound.
        assertEquals(
            BigDecimal("1.13"),
            engine.state.value.breakdown.waitingAmount.setScale(2, RoundingMode.HALF_UP),
        )
        assertEquals(60, engine.state.value.waitingSeconds)
    }

    @Test
    fun `a tick after a long gap is clamped, never billed as motion`() = runTest {
        // The correctness half of F4's hoist. A process that was frozen, Doze-throttled, or
        // restarted can hand tick() an arbitrarily large delta; billing it at the last known speed
        // would invent kilometres out of a window in which the app was not running and observed
        // nothing.
        val gps = FakeMeterGps(80.0)
        val engine = FareEngineImpl(gps, backgroundScope, nanoTimeSource = virtualNanoTimeSource())
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        // One ten-minute gap between ticks, with GPS live at both ends.
        gps.emitFixAt(testScheduler.currentTime)
        advanceTimeBy(600_000)
        gps.emitFixAt(testScheduler.currentTime)
        runCurrent()

        // At 80 km/h, ten minutes is 13.3 km. MAX_TICK_SECONDS caps the billable delta at 5s, so at
        // most 80 x 5 / 3600 x 1.5 = 0.167 km can be charged for that tick.
        val distanceKm = engine.state.value.distanceKm.toDouble()
        assertTrue(
            "a 10-minute gap must never bill as 10 minutes of driving (billed ${"%.3f".format(distanceKm)} km)",
            distanceKm <= 0.2,
        )
    }

    // ================================================================================
    // F2 — distance is ground covered between fixes, not integrated speed
    // ================================================================================

    @Test
    fun `distance comes from the haversine between fixes, matching the ground actually driven`() = runTest {
        val gps = FakeMeterGps(60.0)
        val engine = FareEngineImpl(gps, backgroundScope, nanoTimeSource = virtualNanoTimeSource())
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        repeat(60) { advanceOneTick(gps) }

        // 60s at 60 km/h is 1.0 km, and the fake genuinely moved that far along its track. The
        // engine's cumulative distance must agree with the ground the vehicle covered — that is the
        // whole of F2. A few metres of tolerance covers the one first tick that has no previous fix
        // to measure from and falls back to speed x dt.
        val driven = gps.travelledKm
        val billed = engine.state.value.distanceKm.toDouble()
        assertEquals("billed distance must track ground covered", driven, billed, 0.02)
        assertTrue("the fake must genuinely have driven a real distance", driven > 0.9)
    }

    @Test
    fun `a GPS jump cannot bill more than the speed physically allows`() = runTest {
        // Multipath off a building, or a provider handover, can move a reported "position" hundreds
        // of metres between one sample and the next. Trusting the haversine blindly would charge
        // that as travel. The cap is speed x dt x 1.5.
        val gps = FakeMeterGps(30.0)
        val engine = FareEngineImpl(gps, backgroundScope, nanoTimeSource = virtualNanoTimeSource())
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        // Settle one normal tick so there is a previous fix to jump away from.
        advanceOneTick(gps)

        // Now teleport 5 km east while still reporting 30 km/h.
        gps.emitFixAt(testScheduler.currentTime, -33.87, 151.21 + 0.054)
        advanceTimeBy(1000)
        runCurrent()

        // 30 km/h for 1s is 8.3m; the cap allows 12.5m. Nothing near 5 km may be billed.
        val billed = engine.state.value.distanceKm.toDouble()
        assertTrue("a 5km GPS jump must not be billed as travel (billed $billed km)", billed < 0.1)
    }

    // ================================================================================
    // F3 — a meter that cannot see the vehicle does not charge it for moving
    // ================================================================================

    @Test
    fun `a tunnel accrues no distance and reports gpsLost`() = runTest {
        // The audit's own worked example: enter at 80 km/h, lose GPS, and the old meter billed the
        // frozen speed for the whole blackout — 5.3 km over four minutes that nobody drove.
        val gps = FakeMeterGps(80.0)
        val engine = FareEngineImpl(gps, backgroundScope, nanoTimeSource = virtualNanoTimeSource())
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        // Ten seconds of normal driving into the tunnel mouth.
        repeat(10) { advanceOneTick(gps) }
        val distanceAtTunnelMouth = engine.state.value.distanceKm
        assertFalse("GPS is healthy on the approach", engine.state.value.gpsLost)

        // Into the tunnel. The last fix is still believed for MAX_FIX_AGE_MS (5s) — deliberately,
        // since ordinary GPS jitter routinely leaves gaps of one to two seconds and treating those
        // as a blackout would stop the meter constantly. So a few seconds of speed-integrated
        // accrual after the final fix is correct and expected.
        gps.goDark()
        repeat(6) { advanceOneTickWithNoNewFix() }

        assertTrue("six seconds past the last fix, the meter must know it is blind", engine.state.value.gpsLost)
        val distanceWhenLostDeclared = engine.state.value.distanceKm
        val waitingWhenLostDeclared = engine.state.value.breakdown.waitingAmount

        // The remaining ~4 minutes of tunnel. THIS is the part the old meter billed at the frozen
        // 80 km/h, and not one metre of it may accrue now.
        repeat(234) { advanceOneTickWithNoNewFix() }

        val state = engine.state.value
        assertTrue("the driver must be told the meter has lost the vehicle", state.gpsLost)
        assertEquals(
            "not one metre may accrue once GPS is known lost",
            distanceWhenLostDeclared,
            state.distanceKm,
        )
        assertEquals(
            "a vehicle doing 80 into a tunnel is not waiting either",
            waitingWhenLostDeclared,
            state.breakdown.waitingAmount,
        )

        // And the whole blackout, end to end, cost the passenger only the staleness window: at
        // 80 km/h, 5s is ~0.11 km. The audit's worked example for the old behaviour was 5.3 km.
        val billedThroughBlackout = (state.distanceKm - distanceAtTunnelMouth).toDouble()
        assertTrue(
            "a four-minute blackout must cost far less than a kilometre (billed $billedThroughBlackout km)",
            billedThroughBlackout < 0.2,
        )
    }

    @Test
    fun `a stationary vehicle that loses GPS keeps accruing waiting time`() = runTest {
        // The mirror case, and the reason F3 is not simply "stop charging when GPS drops". A cab
        // stopped at a kerb under a bridge is genuinely waiting and the passenger genuinely owes
        // that time; refusing to charge it would be its own quiet error, just in the driver's
        // direction rather than the passenger's.
        val gps = FakeMeterGps(0.0)
        val engine = FareEngineImpl(gps, backgroundScope, nanoTimeSource = virtualNanoTimeSource())
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        repeat(10) { advanceOneTick(gps) }
        val waitingBefore = engine.state.value.breakdown.waitingAmount

        gps.goDark()
        repeat(60) { advanceOneTickWithNoNewFix() }

        val state = engine.state.value
        assertTrue("GPS really is lost", state.gpsLost)
        assertTrue(
            "a stationary vehicle still accrues waiting time through a blackout",
            state.breakdown.waitingAmount > waitingBefore,
        )
        assertEquals("but still no distance", BigDecimal.ZERO.compareTo(state.distanceKm), 0)
    }

    @Test
    fun `GPS coming back clears gpsLost and resumes accrual`() = runTest {
        val gps = FakeMeterGps(60.0)
        val engine = FareEngineImpl(gps, backgroundScope, nanoTimeSource = virtualNanoTimeSource())
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        repeat(5) { advanceOneTick(gps) }
        gps.goDark()
        repeat(30) { advanceOneTickWithNoNewFix() }
        assertTrue(engine.state.value.gpsLost)
        val distanceInTunnel = engine.state.value.distanceKm

        // Out the far end. The first fix back must NOT draw a segment across the whole blackout and
        // charge it in one tick — that would be the phantom distance re-entering by the F2 door.
        repeat(10) { advanceOneTick(gps) }

        val state = engine.state.value
        assertFalse("GPS is healthy again", state.gpsLost)
        val accruedSinceTunnel = (state.distanceKm - distanceInTunnel).toDouble()
        assertTrue("accrual resumes", accruedSinceTunnel > 0.0)
        // 10s at 60 km/h is 0.167 km. Anything approaching the blackout's own 0.5 km would mean the
        // tunnel had been back-charged.
        assertTrue(
            "the blackout must not be back-charged on the first fix out (accrued $accruedSinceTunnel km)",
            accruedSinceTunnel < 0.25,
        )
    }

    // ================================================================================
    // Known-corridor GPS blackout billing (owner's decision, 2026-09-09) — a blackout that can be
    // explained by a known, mapped toll-road corridor bills the REAL distance along that road's own
    // points at the normal distance rate, instead of F3's ordinary "accrue nothing" fallback. See
    // [au.com.threesixty.cabdispatch.domain.fare.knownCorridorDistanceKm]'s own doc for the geometry,
    // and [KnownCorridorDistanceLookup]'s for why this is wired as its own injectable seam.
    // ================================================================================

    @Test
    fun `a GPS blackout across a known toll tunnel bills the real corridor distance, not nothing`() = runTest {
        val registry = bentTunnelRegistry()
        val gps = FakeMeterGps(80.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(),
            knownCorridorDistanceLookup = KnownCorridorDistanceLookup.of(registry),
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        // Drive up to the tunnel mouth and establish it as the last live fix.
        gps.emitFixAt(testScheduler.currentTime, TUNNEL_ENTRY_LAT, TUNNEL_ENTRY_LNG)
        advanceOneTickWithNoNewFix()
        val distanceAmountAtTunnelMouth = engine.state.value.breakdown.distanceAmount
        val tollsAtTunnelMouth = engine.state.value.breakdown.tolls
        assertFalse(engine.state.value.gpsLost)

        // Into the tunnel -- GPS drops. The last fix stays believed for MAX_FIX_AGE_MS (5s, same
        // grace period every F3 test accounts for), so a few seconds of ordinary speed-integrated
        // accrual after it are expected here too; this test's own known-corridor arithmetic below is
        // measured from the moment [gpsLost] is actually declared, not from the tunnel mouth itself.
        gps.goDark()
        repeat(6) { advanceOneTickWithNoNewFix() }
        assertTrue("GPS must be known lost mid-tunnel", engine.state.value.gpsLost)
        val distanceWhenLostDeclared = engine.state.value.distanceKm

        // The remaining tunnel transit -- confirms nothing accrues purely from more dark ticks.
        repeat(24) { advanceOneTickWithNoNewFix() }
        assertEquals(
            "nothing accrues from further dark ticks alone -- the catch-up only ever fires on recovery",
            distanceWhenLostDeclared,
            engine.state.value.distanceKm,
        )

        // Out the far end, at the known exit gantry.
        gps.emitFixAt(testScheduler.currentTime, TUNNEL_EXIT_LAT, TUNNEL_EXIT_LNG)
        advanceOneTickWithNoNewFix()

        val state = engine.state.value
        assertFalse("GPS is healthy again", state.gpsLost)

        val expectedKnownKm = au.com.threesixty.cabdispatch.domain.fare.knownCorridorDistanceKm(
            registry, TUNNEL_ENTRY_LAT, TUNNEL_ENTRY_LNG, TUNNEL_EXIT_LAT, TUNNEL_EXIT_LNG,
        )!!
        val straightLineKm = BigDecimal.valueOf(
            au.com.threesixty.cabdispatch.domain.fare.tollHaversineM(
                TUNNEL_ENTRY_LAT, TUNNEL_ENTRY_LNG, TUNNEL_EXIT_LAT, TUNNEL_EXIT_LNG,
            ) / 1000.0,
        )
        assertTrue(
            "the bent corridor's real path must bill more than the straight entry-exit chord",
            expectedKnownKm > straightLineKm,
        )

        val billedThroughBlackout = state.distanceKm - distanceWhenLostDeclared
        // The known corridor distance, plus this recovery tick's own ordinary ~1s of continued
        // travel (never more than that -- no back-charging beyond the corridor itself).
        assertTrue(
            "the blackout must bill the known corridor distance (expected ~$expectedKnownKm km, " +
                "billed $billedThroughBlackout km)",
            (billedThroughBlackout - expectedKnownKm).toDouble() in -0.001..0.05,
        )
        assertTrue(
            "distance must actually have been charged at the normal distance rate, through the same " +
                "calcEngine.tick() path every ordinary GPS tick uses -- not a separate money path",
            state.breakdown.distanceAmount > distanceAmountAtTunnelMouth,
        )
        assertEquals(
            "toll charging is a separate, unaffected concern -- this unpriced tunnel is never charged",
            tollsAtTunnelMouth,
            state.breakdown.tolls,
        )

        // The catch-up must fire exactly ONCE per blackout, never again on ordinary ticks after
        // recovery.
        val distanceRightAfterRecovery = state.distanceKm
        repeat(3) { advanceOneTickWithNoNewFix() }
        val laterDistance = engine.state.value.distanceKm
        assertTrue(
            "the corridor bonus ($expectedKnownKm km) must not repeat on ordinary post-recovery " +
                "ticks (only ~3 more seconds of ordinary 80km/h continuation is expected here)",
            (laterDistance - distanceRightAfterRecovery).toDouble() < 0.15,
        )
    }

    @Test
    fun `a blackout with no known-corridor match accrues nothing, even with a real registry loaded`() = runTest {
        val registry = bentTunnelRegistry()
        val gps = FakeMeterGps(80.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(),
            knownCorridorDistanceLookup = KnownCorridorDistanceLookup.of(registry),
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        // Ordinary driving, nowhere near the known tunnel's own gantries.
        repeat(10) { advanceOneTick(gps) }

        gps.goDark()
        // Grace period (MAX_FIX_AGE_MS) plus enough further dark ticks to be a genuine blackout.
        repeat(6) { advanceOneTickWithNoNewFix() }
        assertTrue(engine.state.value.gpsLost)
        val distanceWhenLostDeclared = engine.state.value.distanceKm
        repeat(24) { advanceOneTickWithNoNewFix() }

        // Reacquire somewhere ordinary too -- nothing about this blackout touches the known tunnel.
        gps.setSpeed(80.0)
        advanceOneTick(gps)

        val billed = (engine.state.value.distanceKm - distanceWhenLostDeclared).toDouble()
        assertTrue(
            "an unrelated blackout must accrue nothing beyond the ordinary post-recovery tick (billed $billed km)",
            billed < 0.05,
        )
    }

    @Test
    fun `GPS reacquired far from where the known corridor would put it is never falsely matched`() = runTest {
        val registry = bentTunnelRegistry()
        val gps = FakeMeterGps(80.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(),
            knownCorridorDistanceLookup = KnownCorridorDistanceLookup.of(registry),
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        // Enter genuinely at the known tunnel's mouth...
        gps.emitFixAt(testScheduler.currentTime, TUNNEL_ENTRY_LAT, TUNNEL_ENTRY_LNG)
        advanceOneTickWithNoNewFix()

        gps.goDark()
        repeat(6) { advanceOneTickWithNoNewFix() }
        assertTrue(engine.state.value.gpsLost)
        val distanceWhenLostDeclared = engine.state.value.distanceKm
        repeat(24) { advanceOneTickWithNoNewFix() }

        // ...but reacquire miles from the tunnel's own exit gantry -- a fix that cannot plausibly be
        // this corridor's continuation.
        gps.emitFixAt(testScheduler.currentTime, TUNNEL_ENTRY_LAT, TUNNEL_ENTRY_LNG + 0.20)
        advanceOneTickWithNoNewFix()

        val billed = (engine.state.value.distanceKm - distanceWhenLostDeclared).toDouble()
        assertTrue(
            "a wildly-off reacquisition fix must never be billed as this corridor's known distance " +
                "(billed $billed km)",
            billed < 0.05,
        )
    }

    @Test
    fun `a stationary vehicle near a known corridor still only bills waiting time, never both`() = runTest {
        // The double-billing trap this feature must avoid: F3 already bills WAITING time throughout
        // a blackout where the vehicle was stationary when signal dropped. If the entry/exit fixes
        // also happen to sit near a known corridor's gantries, the catch-up must NOT also add the
        // corridor's distance on top of time already billed for the same minutes.
        val registry = bentTunnelRegistry()
        val gps = FakeMeterGps(0.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(),
            knownCorridorDistanceLookup = KnownCorridorDistanceLookup.of(registry),
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        gps.emitFixAt(testScheduler.currentTime, TUNNEL_ENTRY_LAT, TUNNEL_ENTRY_LNG)
        advanceOneTickWithNoNewFix()
        val waitingBefore = engine.state.value.breakdown.waitingAmount

        gps.goDark()
        repeat(30) { advanceOneTickWithNoNewFix() }
        assertTrue(engine.state.value.gpsLost)
        assertTrue(
            "a stationary blackout keeps billing waiting time exactly as before this feature existed",
            engine.state.value.breakdown.waitingAmount > waitingBefore,
        )

        gps.emitFixAt(testScheduler.currentTime, TUNNEL_EXIT_LAT, TUNNEL_EXIT_LNG)
        advanceOneTickWithNoNewFix()

        assertEquals(
            "a stationary blackout must never ALSO pick up the corridor's known distance",
            BigDecimal.ZERO.compareTo(engine.state.value.distanceKm),
            0,
        )
    }

    // ================================================================================
    // F8 — the dial shows the bill
    // ================================================================================

    @Test
    fun `on a maxi trip the live dial equals the billed total, multiplier included`() = runTest {
        // The audit's finding, verbatim: "a maxi trip's live dial under-reads the actual bill by
        // 50% of the metered base". FareState.total was a naive sum of FareBreakdown, which carries
        // the RAW cumulative flagfall/distance/waiting figures — the 150% maxi multiplier is applied
        // once, wholesale, at close time, and the sum never knew about it.
        val gps = FakeMeterGps(60.0)
        val engine = FareEngineImpl(gps, backgroundScope, nanoTimeSource = virtualNanoTimeSource())
        engine.startTrip(
            urbanTariffDto(),
            startLat = -33.87,
            startLng = 151.21,
            isMaxiVehicle = true,
            passengerCount = 6,
        )
        repeat(120) { advanceOneTick(gps) }

        val state = engine.state.value
        assertTrue("this hiring must genuinely be on the maxi rate", state.maxiRateApplied)

        // The independent expected figure, computed here rather than read off the engine:
        //   metered = (flagfall + peak + distance + waiting) x 1.5, then + PSL, then rounded DOWN.
        val breakdown = state.breakdown
        val metered = (breakdown.flagFall + breakdown.peakAmount + breakdown.distanceAmount + breakdown.waitingAmount)
            .multiply(BigDecimal("1.5"))
        val expected = (metered + breakdown.psl + breakdown.tolls + breakdown.extras)
            .setScale(2, RoundingMode.DOWN)

        assertEquals("the dial must show the maxi-multiplied, rounded-down bill", expected, state.total)

        // ...and concretely: the naive sum the dial used to show is materially lower. This is the
        // regression guard — if FareState.total ever falls back to summing the breakdown, the maxi
        // half of the metered fare silently disappears from the driver's screen again.
        assertTrue(
            "the maxi dial must read HIGHER than the naive breakdown sum it used to show",
            state.total > breakdown.total,
        )
    }

    @Test
    fun `an ordinary metered dial is the round-down of the accrued components`() = runTest {
        val gps = FakeMeterGps(0.0)
        val engine = FareEngineImpl(gps, backgroundScope, nanoTimeSource = virtualNanoTimeSource())
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)
        repeat(45) { advanceOneTick(gps) }

        val state = engine.state.value
        assertFalse(state.maxiRateApplied)
        // Act s76(5)/(6): the regulated maximum must never be exceeded, so the total truncates to
        // cents rather than rounding up. Never more than the raw accrual, and never a cent more.
        assertEquals(state.breakdown.total.setScale(2, RoundingMode.DOWN), state.total)
        assertTrue(state.total <= state.breakdown.total)
    }

    // ================================================================================
    // T1 — a road is charged once, by one route or the other, never both
    // ================================================================================

    @Test
    fun `tapping the M5 preset withdraws an auto-detected M5SW charge instead of doubling it`() = runTest {
        val gps = FakeMeterGps(0.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            TollRegistryProvider { m5Registry() },
            nanoTimeSource = virtualNanoTimeSource(),
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)
        runCurrent() // let the registry snapshot load

        // Cross the gantry: the detector charges M5SW automatically.
        gps.emitFixAt(testScheduler.currentTime, M5_GANTRY_LAT, M5_GANTRY_LNG)
        advanceOneTickWithNoNewFix()
        assertEquals(1, engine.state.value.autoTollsApplied.size)
        assertEquals(BigDecimal("4.09"), engine.state.value.breakdown.tolls)

        // Now the driver taps M5 as well — the single most likely thing for a driver on the M5 to
        // do, and previously a double charge for one crossing through two lists that never
        // consulted each other.
        engine.addToll(TollPresets.M5) // $4.30

        val state = engine.state.value
        assertTrue(
            "the auto charge must be withdrawn in favour of the driver's explicit entry",
            state.autoTollsApplied.isEmpty(),
        )
        assertEquals("exactly one M5 charge stands, the driver's", BigDecimal("4.30"), state.breakdown.tolls)
        assertEquals(1, state.tollsApplied.size)
    }

    @Test
    fun `a manually added road is never auto-charged again later in the trip`() = runTest {
        val gps = FakeMeterGps(0.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            TollRegistryProvider { m5Registry() },
            nanoTimeSource = virtualNanoTimeSource(),
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)
        runCurrent()

        // Driver taps first, then drives past the gantry.
        engine.addToll(TollPresets.M5)
        gps.emitFixAt(testScheduler.currentTime, M5_GANTRY_LAT, M5_GANTRY_LNG)
        advanceOneTickWithNoNewFix()

        val state = engine.state.value
        assertTrue("the detector must not add a second charge for a road already entered by hand", state.autoTollsApplied.isEmpty())
        assertEquals(BigDecimal("4.30"), state.breakdown.tolls)
    }

    @Test
    fun `the airport preset carries no registry road and never suppresses a detection`() = runTest {
        // The $6.43 Sydney Airport figure is a regulated access fee levied at the rank, not a toll
        // gantry — there is no registry road it could double up with, so its registryRoadId is
        // null. That must mean "nothing to reconcile", never "suppress whatever happens to be
        // detected".
        val gps = FakeMeterGps(0.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            TollRegistryProvider { m5Registry() },
            nanoTimeSource = virtualNanoTimeSource(),
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)
        runCurrent()

        engine.addToll(TollPresets.AIRPORT) // $6.43, registryRoadId == null
        gps.emitFixAt(testScheduler.currentTime, M5_GANTRY_LAT, M5_GANTRY_LNG)
        advanceOneTickWithNoNewFix()

        val state = engine.state.value
        assertEquals("the real M5SW crossing is still detected", 1, state.autoTollsApplied.size)
        // 6.43 airport fee + 4.09 M5SW toll — two genuinely different charges, both owed.
        assertEquals(BigDecimal("10.52"), state.breakdown.tolls)
    }

    private companion object {
        const val M5_GANTRY_LAT = -33.9500
        const val M5_GANTRY_LNG = 151.0500

        /** A flat-priced, one-way M5SW — charges on the first fix, no bearing history needed, so
         * these tests need not also simulate a two-fix approach just to establish a trusted
         * bearing. The id is the registry's own natural key, which is what [TollPreset.M5] maps to. */
        fun m5Registry(): au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot {
            val road = au.com.threesixty.cabdispatch.domain.fare.TollRoadRef(
                id = "M5SW",
                name = "M5 South-West Motorway",
                pricingModel = "flat",
                directional = "one_way",
                currentPrice = au.com.threesixty.cabdispatch.domain.fare.TollPriceRef(
                    priceClassAMax = BigDecimal("4.09"),
                    capClassA = null,
                    ratePerKmClassA = null,
                    flagfallClassA = null,
                    timeOfDayRatesClassA = null,
                    confidence = "verified",
                ),
            )
            return au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot(
                roadsById = mapOf(road.id to road),
                gantries = listOf(
                    au.com.threesixty.cabdispatch.domain.fare.TollGantryRef("m5g1", road.id, M5_GANTRY_LAT, M5_GANTRY_LNG),
                ),
            )
        }

        // A bent three-gantry corridor -- entry near A, exit near C, with B off to the side between
        // them (same shape [au.com.threesixty.cabdispatch.domain.fare.KnownCorridorTest] uses) so the
        // real path through B is measurably longer than the straight A-C chord: a test that only
        // checked "did SOME distance get billed" could not tell the correct behaviour apart from a
        // regression back to the straight-line chord.
        const val TUNNEL_ENTRY_LAT = -33.9200
        const val TUNNEL_ENTRY_LNG = 151.1500
        const val TUNNEL_MID_LAT = -33.9180
        const val TUNNEL_MID_LNG = 151.1550
        const val TUNNEL_EXIT_LAT = -33.9200
        const val TUNNEL_EXIT_LNG = 151.1600

        /** `unpriced` deliberately: toll charging is a wholly separate concern from the known-
         * corridor distance catch-up (see that feature's own doc), and giving this road no real
         * price makes "this path never adds a toll" a trivially checkable assertion rather than one
         * that depends on also getting [au.com.threesixty.cabdispatch.domain.fare.onFix]'s own
         * corroboration rules right in the same fixture. */
        fun bentTunnelRegistry(): au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot {
            val road = au.com.threesixty.cabdispatch.domain.fare.TollRoadRef(
                id = "TUNNEL",
                name = "Test Tunnel",
                pricingModel = "unpriced",
                directional = "both",
                currentPrice = null,
            )
            return au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot(
                roadsById = mapOf(road.id to road),
                gantries = listOf(
                    au.com.threesixty.cabdispatch.domain.fare.TollGantryRef("TUNNEL-A", road.id, TUNNEL_ENTRY_LAT, TUNNEL_ENTRY_LNG),
                    au.com.threesixty.cabdispatch.domain.fare.TollGantryRef("TUNNEL-B", road.id, TUNNEL_MID_LAT, TUNNEL_MID_LNG),
                    au.com.threesixty.cabdispatch.domain.fare.TollGantryRef("TUNNEL-C", road.id, TUNNEL_EXIT_LAT, TUNNEL_EXIT_LNG),
                ),
            )
        }
    }
}
