package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.local.entity.TripBlackoutSegmentEntity
import au.com.threesixty.cabdispatch.domain.fare.toDomainTariff
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * W6 (Tests and CI depth, 2026-09-12) additions to [MeterAccuracyTest]'s blackout coverage,
 * split into their own file purely to keep that already-large class from growing further (see
 * this class's own `LargeClass` history) — shares [urbanTariffDto]/[bentTunnelRegistry]/the
 * `TUNNEL_*` constants from `BlackoutTestFixtures.kt` and the `advanceOneTick`/
 * `advanceOneTickWithNoNewFix`/`virtualNanoTimeSource`/`fixedDayWallClock` helpers from
 * `FakeMeterGps.kt`, exactly as [MeterAccuracyTest] itself does.
 *
 * Two groups of tests:
 *  - **Restart/process-death races** (task 2): [FareEngineImpl.resumeTrip]/`reseedBlackoutState`
 *    with no open blackout to restore, and the reseed racing tick()'s own resolution when the
 *    reacquisition fix arrives on literally the first tick after restart.
 *  - **Exact-cent golden vectors** (task 1): the four canonical blackout shapes -- moving/
 *    corridor, stationary, moving/unmatched, and back-to-back -- asserted with plain
 *    `assertEquals` against a BigDecimal total, never a tolerance/epsilon.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlackoutRestartAndGoldenVectorsTest {

    // ================================================================================
    // Task 2 — restart/process-death races
    // ================================================================================

    @Test
    fun `resumeTrip with no open blackout segment never fabricates one`() = runTest {
        // The mirror image of MeterAccuracyTest's "process killed mid-tunnel" test -- a process
        // restart where NOTHING was open when it died (the overwhelming majority of real
        // restarts: most trips are not mid-blackout when a driver's tablet reboots) must never
        // invent a blackout to resolve later. `openBlackoutSegment = null` is
        // [FareEngineImpl.resumeTrip]'s own documented "the ordinary case" default.
        val gps = FakeMeterGps(60.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(),
            wallClockNow = fixedDayWallClock(),
        )
        val restoredCalcState = au.com.threesixty.cabdispatch.domain.fare.FareState(
            tariff = urbanTariffDto().toDomainTariff(),
        )
        engine.resumeTrip(
            tariff = urbanTariffDto(),
            restored = restoredCalcState,
            movingSeconds = 300,
            waitingSeconds = 40,
            openBlackoutSegment = null,
        )

        // Before the tick loop has run at all, the published state must not pre-emptively claim a
        // blackout that was never restored.
        val initialBlackout = engine.state.value.blackout
        assertTrue("resumeTrip's own initial state must never fabricate a blackout", initialBlackout == null)

        // The GPS provider has not produced a fix yet either -- exactly the real "process just
        // restarted, location has not re-locked" moment. Ticks here ARE honestly gpsLost (there is
        // no fix to trust) but must never mint a blackout record: there is no known ENTRY position
        // to reason a corridor catch-up from, only an absence of one.
        repeat(3) { advanceOneTickWithNoNewFix() }
        assertTrue("no fix yet is honestly gpsLost", engine.state.value.gpsLost)
        assertTrue(
            "gpsLost with no prior fix and no restored segment must not fabricate an entry position",
            engine.state.value.blackout == null,
        )

        // GPS re-locks. Ordinary accrual resumes with no phantom corridor catch-up ever having
        // fired for a blackout that was never real.
        repeat(5) { advanceOneTick(gps) }
        val state = engine.state.value
        assertFalse(state.gpsLost)
        assertTrue("nothing was ever open to resolve", state.lastResolvedBlackout == null)
        assertTrue(state.blackout == null)
    }

    @Test
    fun `a reacquisition fix on the very first tick after a restart still resolves the restored blackout`() = runTest {
        // Races MeterController.restoreOpenTripIfAny's reseed against tick()'s own resolution --
        // the restart path must win even when GPS reacquires on literally the FIRST tick the
        // fresh engine instance ever runs, with zero dark ticks in between (MeterAccuracyTest's
        // "process killed mid-tunnel" test spaces five dark ticks before reacquiring; this one
        // collapses that gap to nothing, so if reseedBlackoutState's fields were ever set AFTER
        // tick()'s own resolution check instead of before it, this is the test that would catch
        // it).
        val registry = bentTunnelRegistry()
        val openSegment = TripBlackoutSegmentEntity(
            clientUuid = "segment-race-1",
            tripClientUuid = "trip-race-1",
            startedAtIso = "2026-09-12T02:00:00Z",
            entryLat = TUNNEL_ENTRY_LAT,
            entryLng = TUNNEL_ENTRY_LNG,
            entryWasMoving = true,
            createdAt = 0L,
            updatedAt = 0L,
        )
        val gps = FakeMeterGps(0.0) // no fix yet, exactly like a fresh restart
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(), wallClockNow = fixedDayWallClock(),
            knownCorridorDistanceLookup = KnownCorridorDistanceLookup.of(registry),
        )
        val restoredCalcState = au.com.threesixty.cabdispatch.domain.fare.FareState(
            tariff = urbanTariffDto().toDomainTariff(),
        )
        engine.resumeTrip(
            tariff = urbanTariffDto(),
            restored = restoredCalcState,
            movingSeconds = 0,
            waitingSeconds = 0,
            openBlackoutSegment = openSegment,
        )

        // The reacquisition fix is already sitting there BEFORE the very first post-restart tick
        // fires -- no dark ticks at all between resumeTrip and reacquisition.
        gps.emitFixAt(testScheduler.currentTime, TUNNEL_EXIT_LAT, TUNNEL_EXIT_LNG)
        advanceOneTickWithNoNewFix()

        val state = engine.state.value
        assertFalse("GPS reads healthy on this very first tick", state.gpsLost)
        val expectedKnownKm = au.com.threesixty.cabdispatch.domain.fare.knownCorridorDistanceKm(
            registry, TUNNEL_ENTRY_LAT, TUNNEL_ENTRY_LNG, TUNNEL_EXIT_LAT, TUNNEL_EXIT_LNG,
        )!!
        val billed = state.distanceKm.toDouble()
        assertTrue(
            "the reseeded blackout must still resolve to the real corridor distance even when the " +
                "reacquisition fix arrives on the very first tick after restart (expected " +
                "~$expectedKnownKm km, billed $billed km)",
            (billed - expectedKnownKm.toDouble()) in -0.001..0.05,
        )
        val resolved = state.lastResolvedBlackout
        assertTrue("the reseed must not lose the race against resolution", resolved != null)
        assertEquals("CORRIDOR", resolved!!.resolution)
        assertEquals("segment-race-1", resolved.segmentId)
        assertTrue("the active-blackout record must clear once resolved", state.blackout == null)
    }

    // ================================================================================
    // Task 1 — exact-cent golden vectors for the four canonical blackout shapes. Every tolerance-
    // based blackout test in MeterAccuracyTest drives at 80km/h, whose speed-integrated distance
    // (80/3600 km per 1s tick) is an ugly repeating binary fraction with no clean decimal
    // endpoint. These four instead drive at 36km/h -- chosen for no reason other than that
    // 36/3600 = 0.01 km per tick EXACTLY, in a double that round-trips cleanly to that decimal --
    // so every ordinary tick's own contribution is a clean, hand-checkable BigDecimal and the
    // total is asserted with plain `assertEquals`, never an epsilon.
    // ================================================================================

    @Test
    fun `a moving blackout through a known corridor bills exactly the registry distance once, to the cent`() = runTest {
        val registry = bentTunnelRegistry()
        val gps = FakeMeterGps(36.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(), wallClockNow = fixedDayWallClock(),
            knownCorridorDistanceLookup = KnownCorridorDistanceLookup.of(registry),
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        // Tick #1: establishes the tunnel mouth as the last live fix (0.01km, the trip's first
        // tick).
        gps.emitFixAt(testScheduler.currentTime, TUNNEL_ENTRY_LAT, TUNNEL_ENTRY_LNG)
        advanceOneTickWithNoNewFix()

        // Dark. Because the entry fix's own tick (#1, above) already lands 1s after that fix was
        // published (emit-then-advance ordering -- see FakeMeterGps.emitFixAt's own doc), only
        // ticks #2-5 of this dark run land inside the grace window (fix age 2-5s, still
        // !gpsLost, so each bills the frozen 36km/h ordinarily -- another 0.01km each); tick #6
        // (fix age 6s) crosses MAX_FIX_AGE_MS and declares the blackout, billing nothing itself,
        // and tick #7 stays dark.
        gps.goDark()
        repeat(6) { advanceOneTickWithNoNewFix() }
        assertTrue(engine.state.value.gpsLost)

        // The rest of the tunnel transit -- confirmed nil elsewhere in this file; not re-checked
        // here, this test's whole point is the FINAL total.
        repeat(24) { advanceOneTickWithNoNewFix() }

        // Out the far end, still at 36km/h -- this recovery tick bills one more ordinary 0.01km
        // (speed-integrated: there is no previous LIVE fix to haversine from) IN ADDITION to the
        // corridor catch-up resolveBlackout bills in the same tick, through the same calcEngine.
        gps.emitFixAt(testScheduler.currentTime, TUNNEL_EXIT_LAT, TUNNEL_EXIT_LNG)
        advanceOneTickWithNoNewFix()

        val state = engine.state.value
        assertFalse(state.gpsLost)
        val resolved = state.lastResolvedBlackout
        assertTrue(resolved != null)
        assertEquals("CORRIDOR", resolved!!.resolution)

        val corridorKm = au.com.threesixty.cabdispatch.domain.fare.knownCorridorDistanceKm(
            registry, TUNNEL_ENTRY_LAT, TUNNEL_ENTRY_LNG, TUNNEL_EXIT_LAT, TUNNEL_EXIT_LNG,
        )!!
        assertEquals(
            "the resolved record's own billed distance must be exactly the corridor's real length",
            0,
            corridorKm.compareTo(resolved.billedDistanceKm),
        )

        // 6 ordinary 0.01km ticks (1 establishing + 4 grace-window + 1 recovery), plus the
        // corridor's own real distance -- both at distRate1 (2.61/km; cumulative distance stays
        // comfortably inside the 12km band-1 threshold throughout).
        val ordinaryDistanceKm = BigDecimal("0.01").multiply(BigDecimal(6))
        val expectedDistanceCharge = ordinaryDistanceKm.add(corridorKm).multiply(BigDecimal("2.61"))
        // flagfall + distance charge, no peak (fixedDayWallClock is a DAY instant), no waiting, no
        // tolls (the fixture's TUNNEL road is `unpriced`), no extras -- plus PSL, which
        // runningTotal always includes (see FareEngineImpl.runningTotal's own doc).
        val expectedTotal = BigDecimal("5.17").add(expectedDistanceCharge).add(BigDecimal("1.32"))
            .setScale(2, RoundingMode.DOWN)

        assertEquals(
            "the final billed total must equal flagfall + (ordinary ticks + corridor) distance + " +
                "PSL, to the cent, with no epsilon",
            expectedTotal,
            state.total,
        )
    }

    @Test
    fun `a stationary blackout bills waiting time throughout, to the cent`() = runTest {
        val gps = FakeMeterGps(0.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(),
            wallClockNow = fixedDayWallClock(),
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        advanceOneTick(gps) // tick #1 -- waiting (0 km/h never crosses the 26km/h threshold)

        gps.goDark()
        // ticks #2-7: tick #6 (fix age 6s) is the first to actually declare gpsLost (see the
        // corridor test above for the exact accounting) -- irrelevant here, since a stationary
        // blackout bills waiting on every tick regardless of gpsLost, dark or not.
        repeat(6) { advanceOneTickWithNoNewFix() }
        assertTrue(engine.state.value.gpsLost)
        repeat(3) { advanceOneTickWithNoNewFix() } // ticks #8-10, still dark, still billing waiting

        gps.emitFixAt(testScheduler.currentTime, TUNNEL_EXIT_LAT, TUNNEL_EXIT_LNG)
        advanceOneTickWithNoNewFix() // tick #11, reacquires and resolves STATIONARY

        val state = engine.state.value
        assertFalse(state.gpsLost)
        val resolved = state.lastResolvedBlackout
        assertTrue(resolved != null)
        assertEquals("STATIONARY", resolved!!.resolution)
        assertEquals(0, BigDecimal.ZERO.compareTo(resolved.billedDistanceKm))
        assertEquals(0, BigDecimal.ZERO.compareTo(state.distanceKm))

        // 11 ticks total, every one WAITING mode (0km/h never crosses threshold), each at exactly
        // 1.0s -- the SAME scale-20/HALF_UP `elapsed / 60` division domain.fare.FareEngine.tick
        // uses internally to turn seconds into minutes (its own `divide` helper is private, so
        // this mirrors it rather than calling it directly).
        val minutesPerTick = BigDecimal.ONE.divide(BigDecimal(60), 20, RoundingMode.HALF_UP)
        val waitingChargePerTick = minutesPerTick.multiply(BigDecimal("1.130"))
        val totalWaitingCharge = waitingChargePerTick.multiply(BigDecimal(11))
        val expectedTotal = BigDecimal("5.17").add(totalWaitingCharge).add(BigDecimal("1.32"))
            .setScale(2, RoundingMode.DOWN)

        assertEquals(
            "a stationary blackout's final total must be flagfall + 11 ticks of waiting + PSL, " +
                "exactly, with no epsilon",
            expectedTotal,
            state.total,
        )
    }

    @Test
    fun `an unmatched moving blackout bills nothing extra, to the cent`() = runTest {
        val registry = bentTunnelRegistry() // loaded, but nowhere near this route
        val gps = FakeMeterGps(36.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(), wallClockNow = fixedDayWallClock(),
            knownCorridorDistanceLookup = KnownCorridorDistanceLookup.of(registry),
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        advanceOneTick(gps) // tick #1 -- ordinary driving, FakeMeterGps's own due-east track

        gps.goDark()
        // ticks #2-7: as in the corridor test above, ticks #2-5 (fix age 2-5s) are still grace
        // (ordinary billing), tick #6 (fix age 6s) declares gpsLost, tick #7 stays dark.
        repeat(6) { advanceOneTickWithNoNewFix() }
        assertTrue(engine.state.value.gpsLost)
        repeat(24) { advanceOneTickWithNoNewFix() } // ticks #8-31, nothing accrues

        gps.setSpeed(36.0)
        advanceOneTick(gps) // tick #32, reacquire somewhere ordinary -- resolves NONE

        val state = engine.state.value
        assertFalse(state.gpsLost)
        val resolved = state.lastResolvedBlackout
        assertTrue(resolved != null)
        assertEquals("NONE", resolved!!.resolution)
        assertEquals(BigDecimal.ZERO, resolved.billedDistanceKm)
        assertTrue("no corridor road can be named for a NONE resolution", resolved.corridorRoadId == null)

        // 6 ordinary distance ticks total (1 establishing + 4 grace-window + 1 post-recovery), and
        // NOTHING for the 25 dark ticks in between.
        val expectedDistanceCharge = BigDecimal("0.01").multiply(BigDecimal(6)).multiply(BigDecimal("2.61"))
        val expectedTotal = BigDecimal("5.17").add(expectedDistanceCharge).add(BigDecimal("1.32"))
            .setScale(2, RoundingMode.DOWN)
        assertEquals(
            "an unmatched moving blackout must bill exactly the 6 ordinary ticks and nothing more, " +
                "with no epsilon",
            expectedTotal,
            state.total,
        )
    }

    @Test
    fun `back-to-back blackouts in the same trip resolve independently, to the cent`() = runTest {
        val gps = FakeMeterGps(0.0)
        val engine = FareEngineImpl(
            gps,
            backgroundScope,
            nanoTimeSource = virtualNanoTimeSource(),
            wallClockNow = fixedDayWallClock(),
        )
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        advanceOneTick(gps) // tick #1

        // First blackout: dark for ticks #2-7, resolved at tick #8.
        gps.goDark()
        repeat(6) { advanceOneTickWithNoNewFix() }
        val firstActive = engine.state.value.blackout
        assertTrue("the first blackout must have an active record", firstActive != null)

        gps.emitFixAt(testScheduler.currentTime, TUNNEL_ENTRY_LAT, TUNNEL_ENTRY_LNG)
        advanceOneTickWithNoNewFix() // tick #8
        val firstResolved = engine.state.value.lastResolvedBlackout
        assertTrue(firstResolved != null)
        assertEquals("STATIONARY", firstResolved!!.resolution)
        assertEquals(firstActive!!.segmentId, firstResolved.segmentId)
        assertTrue(
            "the active-blackout record must clear the instant the first one resolves",
            engine.state.value.blackout == null,
        )

        // Second blackout: entirely separate -- still stationary, same trip, a fresh loss.
        advanceOneTick(gps) // tick #9, ordinary
        gps.goDark()
        repeat(6) { advanceOneTickWithNoNewFix() } // ticks #10-15
        val secondActive = engine.state.value.blackout
        assertTrue("the second blackout must have its own active record", secondActive != null)
        assertTrue(
            "a fresh blackout must never reuse the previous one's segment id",
            secondActive!!.segmentId != firstActive.segmentId,
        )

        gps.emitFixAt(testScheduler.currentTime, TUNNEL_EXIT_LAT, TUNNEL_EXIT_LNG)
        advanceOneTickWithNoNewFix() // tick #16, resolves the second blackout
        val state = engine.state.value
        val secondResolved = state.lastResolvedBlackout
        assertTrue(secondResolved != null)
        assertEquals("STATIONARY", secondResolved!!.resolution)
        assertEquals(secondActive.segmentId, secondResolved.segmentId)
        assertTrue(
            "the second resolution must carry its own segment identity, never the first's",
            secondResolved.segmentId != firstResolved.segmentId,
        )

        // Every one of the 16 ticks was WAITING mode (speed stayed 0 throughout), each exactly
        // 1.0s.
        val minutesPerTick = BigDecimal.ONE.divide(BigDecimal(60), 20, RoundingMode.HALF_UP)
        val waitingChargePerTick = minutesPerTick.multiply(BigDecimal("1.130"))
        val totalWaitingCharge = waitingChargePerTick.multiply(BigDecimal(16))
        val expectedTotal = BigDecimal("5.17").add(totalWaitingCharge).add(BigDecimal("1.32"))
            .setScale(2, RoundingMode.DOWN)
        assertEquals(
            "two back-to-back stationary blackouts must bill exactly 16 ticks of waiting, once " +
                "each, never double-counted or dropped, with no epsilon",
            expectedTotal,
            state.total,
        )
        assertEquals(0, BigDecimal.ZERO.compareTo(state.distanceKm))
    }
}
