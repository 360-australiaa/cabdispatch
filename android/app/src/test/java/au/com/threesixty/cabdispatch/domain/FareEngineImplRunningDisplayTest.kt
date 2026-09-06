package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.TariffDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

/** Advances the fake fare-tick coroutine by exactly one real tick (its own `delay(1000)` cadence —
 * see [FareEngineImpl.startTicking]). Top-level so it's a plain single-receiver ([TestScope])
 * extension, callable straight from inside a `runTest { ... }` body where that's the implicit
 * receiver. */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.advanceOneTick() {
    advanceTimeBy(1000)
    runCurrent()
}

/**
 * 2026-09 product report, three requirements, pinned against [FareEngineImpl] — the UI-facing
 * engine [HiredScreen][au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen]'s dial actually
 * reads (`FareState.total`, this package's own type, NOT `domain.fare.FareEngine`'s — see that
 * class's own doc for why the two are separate). [au.com.threesixty.cabdispatch.domain.fare.FareEngineTest]
 * already pins the pure engine's close()-time math (unchanged by this pass); these tests pin the
 * live-display layer that pass actually touched:
 *
 * (A) "the meter should start at flagfall + PSL, not flagfall alone."
 * (B) "the running fare display must always show the full amount the passenger will pay" —
 *     tolls/waiting must show up the moment they accrue, not only at close.
 * (C) "fixed / negotiated price must actually stick" — the dial must show the agreed amount, not
 *     restart from flagfall, while distance/time keep accruing underneath for the trip record.
 *
 * No Android framework dependency needed here — [FareEngineImpl] only touches
 * `kotlinx.coroutines`/`java.time`/this project's own plain-Kotlin domain types (its constructor
 * takes a plain [SpeedSource] + [kotlinx.coroutines.CoroutineScope], no Context) — so this runs as
 * a plain JVM test, same as every other file in this `domain` test package.
 */
class FareEngineImplRunningDisplayTest {

    /** Deterministic, test-controlled replacement for [RealLocationProvider][au.com.threesixty.cabdispatch.domain.location.RealLocationProvider] —
     * mutable speed only; [locationFix] is never read by [FareEngineImpl] itself (only by
     * [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel]'s trace recording), so it
     * stays a fixed `null`, same honest-null convention [StubSpeedSource] already uses. */
    private class FakeSpeedSource(initialSpeedKmh: Double) : SpeedSource {
        private val _speedKmh = MutableStateFlow(initialSpeedKmh)
        override val speedKmh: StateFlow<Double> = _speedKmh
        override val locationFix: StateFlow<LocationFix?> = MutableStateFlow(null)
        fun setSpeed(kmh: Double) {
            _speedKmh.value = kmh
        }
    }

    /** A real-shaped urban tariff DTO (2026 Order rate card, matching
     * [au.com.threesixty.cabdispatch.domain.fare.URBAN_TARIFF] field-for-field) — decimal-as-string
     * per this project's wire convention, same as every other [TariffDto] test fixture in this
     * codebase (see `FareEngineTest.kt`'s `testS`). */
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

    // --- (A) meter starts at flagfall + PSL, not flagfall alone -------------------------------

    @Test
    fun `running total at t=0 is flagfall plus PSL, not flagfall alone`() = runTest {
        val speedSource = FakeSpeedSource(0.0)
        val engine = FareEngineImpl(speedSource, backgroundScope)
        val tariff = urbanTariffDto()

        engine.startTrip(tariff, startLat = -33.87, startLng = 151.21)

        val state = engine.state.value
        // flagfall 5.17 + psl 1.32 = 6.49 — the same "flagfall + levy" shape as the product
        // owner's own worked example ($5.00 + $1.32 = $6.32 against the superseded tariff).
        assertEquals(BigDecimal("6.49"), state.total)
        assertEquals(BigDecimal("1.32"), state.breakdown.psl)
        assertEquals(BigDecimal.ZERO, state.breakdown.distanceAmount)
        assertEquals(BigDecimal.ZERO, state.breakdown.waitingAmount)
    }

    @Test
    fun `flagfall-only trip closes at exactly the same total the live display showed at t=0`() = runTest {
        // Direct "final total is unchanged" proof at the FareEngineImpl layer: close() here is a
        // pure snapshot (tickJob.cancel() + status flip, no recompute — see FareEngineImpl.close's
        // own body), so the closed total is definitionally identical, but this test exists to
        // document that fact explicitly rather than leave it implicit.
        val speedSource = FakeSpeedSource(0.0)
        val engine = FareEngineImpl(speedSource, backgroundScope)
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        val atStart = engine.state.value.total
        val closed = engine.close()

        assertEquals(atStart, closed.total)
        assertEquals(BigDecimal("6.49"), closed.total)
    }

    // --- (B) running total shows tolls/waiting the instant they accrue, PSL counted once ------

    @Test
    fun `a toll added mid-trip appears in the running total immediately, on top of flagfall plus PSL`() = runTest {
        val speedSource = FakeSpeedSource(0.0)
        val engine = FareEngineImpl(speedSource, backgroundScope)
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        engine.addToll(TollPresets.M5) // $4.30

        // 5.17 (flagfall) + 1.32 (psl) + 4.30 (toll) = 10.79 — visible the instant the driver taps
        // the toll chip, not only once the trip closes.
        assertEquals(BigDecimal("10.79"), engine.state.value.total)
    }

    @Test
    fun `waiting time accrues into the running total tick by tick, with PSL counted exactly once`() = runTest {
        // Speed pinned below the 26 km/h threshold the whole time -> every tick accrues via the
        // WAITING branch (real behaviour for a driver stopped at lights/a rank).
        val speedSource = FakeSpeedSource(0.0)
        val engine = FareEngineImpl(speedSource, backgroundScope)
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        val atStart = engine.state.value.total
        val pslAtStart = engine.state.value.breakdown.psl

        repeat(60) { advanceOneTick() } // 60s waiting @ 1.130 c/min -> 1 min * 1.13 = 1.13

        val afterWaiting = engine.state.value
        assertTrue(
            "waiting should have grown the running total (was $atStart, now ${afterWaiting.total})",
            afterWaiting.total > atStart,
        )
        assertEquals(BigDecimal("1.13"), afterWaiting.breakdown.waitingAmount.setScale(2, RoundingMode.HALF_UP))
        // PSL must not have been re-added by ticking — same single seeded value the whole time,
        // never incremented by tick() (FareEngineImpl.tick only ever copies distanceAmount/
        // waitingAmount off the shadow calc state — see that method's own doc).
        assertEquals(pslAtStart, afterWaiting.breakdown.psl)
        assertEquals(BigDecimal("1.32"), afterWaiting.breakdown.psl)
        // total = flagfall 5.17 + waiting 1.13 + psl 1.32 = 7.62. Rounded to cents before
        // comparing — [FareEngineImpl.tick]'s own doc: accrued amounts are kept RAW (unrounded)
        // between ticks by design, only rounded at render time ([toMeterDisplayString]/
        // [toMoneyString]), so the raw total legitimately carries trailing precision here.
        assertEquals(BigDecimal("7.62"), afterWaiting.total.setScale(2, RoundingMode.HALF_UP))
    }

    // --- (C) a negotiated/fixed price sticks on the live display, meter keeps running underneath ---

    @Test
    fun `a negotiated fixed price displays the agreed amount immediately, not flagfall`() = runTest {
        val speedSource = FakeSpeedSource(0.0)
        val engine = FareEngineImpl(speedSource, backgroundScope)

        engine.startTrip(
            urbanTariffDto(),
            startLat = -33.87,
            startLng = 151.21,
            negotiatedTotal = BigDecimal("50.00"),
        )

        val state = engine.state.value
        assertEquals(BigDecimal("50.00"), state.negotiatedTotal)
        // Deliberate design choice (documented on FareState.total): the dial shows the agreed
        // amount PLUS PSL (mandatory Fares Order pass-through, always billed on top of a
        // negotiated total too — see domain.fare.FareEngineTest's testV) — never bare flagfall,
        // and never the raw $50 alone either, because $50 alone would UNDER-state what Close & Pay
        // will actually charge (50.00 + psl 1.32 = 51.32), which would itself violate requirement
        // (B) ("always show the full amount the passenger will pay").
        assertEquals(BigDecimal("51.32"), state.total)
    }

    @Test
    fun `a negotiated fixed price does not grow as the meter accrues distance underneath it`() = runTest {
        // "the meter will keep running" (product's own words) — distance/time keep accruing for
        // the trip record/compliance evidence, but the DISPLAYED total must not follow them.
        val speedSource = FakeSpeedSource(80.0) // >= 26 km/h -> DISTANCE mode every tick
        val engine = FareEngineImpl(speedSource, backgroundScope)
        engine.startTrip(
            urbanTariffDto(),
            startLat = -33.87,
            startLng = 151.21,
            negotiatedTotal = BigDecimal("50.00"),
        )

        val totalAtStart = engine.state.value.total
        repeat(30) { advanceOneTick() } // 30s @ 80km/h -> ~0.667km accrued, a real, non-zero charge
        val afterDriving = engine.state.value

        assertTrue("distance should genuinely have accrued", afterDriving.distanceKm > BigDecimal.ZERO)
        assertTrue(
            "metered distance charge should be real and non-trivial",
            afterDriving.breakdown.distanceAmount > BigDecimal.ZERO,
        )
        // ...yet the DISPLAYED total is exactly what it was at t=0 — unchanged by all that accrual.
        assertEquals(totalAtStart, afterDriving.total)
        assertEquals(BigDecimal("51.32"), afterDriving.total)
    }

    @Test
    fun `a toll still adds on top of a negotiated fixed price`() = runTest {
        val speedSource = FakeSpeedSource(0.0)
        val engine = FareEngineImpl(speedSource, backgroundScope)
        engine.startTrip(
            urbanTariffDto(),
            startLat = -33.87,
            startLng = 151.21,
            negotiatedTotal = BigDecimal("50.00"),
        )

        engine.addToll(TollPresets.AIRPORT) // $6.43

        // 50.00 (agreed) + 1.32 (psl) + 6.43 (toll) = 57.75 — tolls are a real extra cost on top of
        // any agreed price, metered or fixed; Act s79(3) only fixes the METERED-fare component.
        assertEquals(BigDecimal("57.75"), engine.state.value.total)
    }

    @Test
    fun `an ordinary metered trip never carries a negotiatedTotal`() = runTest {
        val speedSource = FakeSpeedSource(0.0)
        val engine = FareEngineImpl(speedSource, backgroundScope)
        engine.startTrip(urbanTariffDto(), startLat = -33.87, startLng = 151.21)

        assertNull(engine.state.value.negotiatedTotal)
        assertEquals(engine.state.value.breakdown.total, engine.state.value.total)
    }
}
