package au.com.threesixty.cabdispatch.domain.fare

import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.NswPublicHolidays
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Golden compliance-evidence vectors for the NSW tariff-switching fare
 * engine — plain JUnit4, plain JVM (no Android instrumentation needed).
 *
 * Tests A-I are a direct port of the corresponding test in
 * `backend/tests/test_fare_engine_golden.py` (test_a..test_i), recomputed
 * against the **Point to Point Transport (Fares) Order 2026** rate card (see
 * [FareEngine.kt][FareEngine]'s header table) and the new round-DOWN rule for
 * [FareBreakdown.fareTotal] (Fix 3) — every expected literal below was
 * hand-recomputed, not copied from the engine's own output; the arithmetic is
 * shown in a comment above each test so a human reviewer can hand-check it.
 * Tests J onward (added for the 2026 Order compliance pass) cover the maxi
 * eligibility matrix, the rounding-down rule, public-holiday/peak
 * classification, negotiated ("Set Price") billing, and Fares Order
 * ingestion validation. Do NOT "fix" a failing assertion by copying the
 * engine's output back in — if a test fails, [FareEngine] (or this file) has
 * drifted from the reference rates/rules and must be reconciled against them.
 */
class FareEngineTest {

    @Test
    fun testA_shortUrbanDayTripAllMoving() {
        // 3km, all >=26km/h, urban day, cash. Within first-12km band throughout.
        // distance_charge = 3 * 2.61              = 7.83
        // subtotal        = flag_fall 5.17 + 7.83 = 13.00
        // fare_total      = round_down(13.00)     = 13.00 (exact, no rounding effect here)
        // gst_component   = 13.00 / 11 = 1.181818... -> 1.18
        val engine = FareEngine()
        var state = FareState(tariff = URBAN_TARIFF, timeClass = TimeClass.DAY)

        state = engine.tick(state, speedKmh = 40, distanceDeltaKm = 3, elapsedSeconds = 270)

        val breakdown = engine.close(state)

        assertEquals(BigDecimal("7.83"), breakdown.distanceCharge)
        assertEquals(BigDecimal("13.00"), breakdown.fareTotal)
        assertEquals(BigDecimal.ZERO, breakdown.surcharge)
        assertEquals(BigDecimal("13.00"), breakdown.grandTotal)
        assertEquals(BigDecimal("1.18"), breakdown.gstComponent)
    }

    @Test
    fun testB_urbanNightTripOver12kmMixesBothBands() {
        // 16km in one continuous fast segment, urban night (10pm-6am), peak hiring.
        // first 12km  @ night_rate_1 3.10 = 37.20
        // next   4km  @ night_rate_2 2.82 = 11.28
        // distance_charge = 48.48
        // subtotal = flag_fall 5.17 + peak 2.65 + 48.48 = 56.30
        // fare_total = round_down(56.30) = 56.30 (exact)
        // gst_component = 56.30 / 11 = 5.118181... -> 5.12
        val engine = FareEngine()
        var state = FareState(tariff = URBAN_TARIFF, timeClass = TimeClass.NIGHT, isPeak = true)

        state = engine.tick(state, speedKmh = 50, distanceDeltaKm = 16, elapsedSeconds = 900)

        assertEquals(BigDecimal(16), state.cumulativeDistanceKm)
        val breakdown = engine.close(state)

        assertEquals(BigDecimal("48.48"), breakdown.distanceCharge)
        assertEquals(BigDecimal("56.30"), breakdown.fareTotal)
        assertEquals(BigDecimal("5.12"), breakdown.gstComponent)
    }

    @Test
    fun testC_countryHolidayTrip() {
        // 10km, country, Sunday daytime (holiday time_class), within first-12km band.
        // distance_charge = 10 * holiday_rate_1 2.97 = 29.70
        // subtotal = flag_fall 5.29 + 29.70 = 34.99
        // fare_total = round_down(34.99) = 34.99 (exact)
        // gst_component = 34.99 / 11 = 3.180909... -> 3.18
        val engine = FareEngine()
        var state = FareState(tariff = COUNTRY_TARIFF, timeClass = TimeClass.HOLIDAY)

        state = engine.tick(state, speedKmh = 45, distanceDeltaKm = 10, elapsedSeconds = 800)

        val breakdown = engine.close(state)

        assertEquals(BigDecimal("29.70"), breakdown.distanceCharge)
        assertEquals(BigDecimal("34.99"), breakdown.fareTotal)
        assertEquals(BigDecimal("3.18"), breakdown.gstComponent)
    }

    @Test
    fun testD_waitingHeavyCbdCrawl() {
        // Mostly <26km/h (waiting), urban day, one short fast hop mixed in.
        // waiting: 10 min @ 1.130/min = 11.30
        // waiting:  5 min @ 1.130/min =  5.65
        // waiting_charge total = 16.95
        // distance: 1km @ dist_rate_1 2.61 = 2.61
        // subtotal = flag_fall 5.17 + 2.61 + 16.95 = 24.73
        // fare_total = round_down(24.73) = 24.73 (exact)
        // gst_component = 24.73 / 11 = 2.248181... -> 2.25
        val engine = FareEngine()
        var state = FareState(tariff = URBAN_TARIFF, timeClass = TimeClass.DAY)

        // Waiting mode: speed below the 26km/h threshold -> time-based charge only.
        state = engine.tick(state, speedKmh = 8, distanceDeltaKm = 0.05, elapsedSeconds = 600)
        assertEquals("waiting", state.lastMode)
        state = engine.tick(state, speedKmh = 5, distanceDeltaKm = 0.02, elapsedSeconds = 300)
        assertEquals("waiting", state.lastMode)

        // A brief fast hop: distance mode kicks in, waiting stops accruing.
        state = engine.tick(state, speedKmh = 40, distanceDeltaKm = 1, elapsedSeconds = 90)
        assertEquals("distance", state.lastMode)

        val breakdown = engine.close(state)

        assertEquals(BigDecimal("16.95"), breakdown.waitingCharge)
        assertEquals(BigDecimal("2.61"), breakdown.distanceCharge)
        assertEquals(BigDecimal("24.73"), breakdown.fareTotal)
        assertEquals(BigDecimal("2.25"), breakdown.gstComponent)
    }

    @Test
    fun testE_multipleHiringEachHirerOwes75PctAtTheirDrop() {
        // 2 hirers, meter runs ONCE (single continuous state, never reset).
        //
        // Hirer 1 drop checkpoint:
        //   distance_charge so far = 2km * 2.61 = 5.22
        //   fare_total_1 = round_down(flag_fall 5.17 + 5.22) = round_down(10.39) = 10.39
        //   owed_1 = round_half_up(10.39 * 0.75) = round_half_up(7.7925) = 7.79
        //
        // Hirer 2 (final) drop:
        //   + 3km * 2.61 = 7.83  ->  distance_charge = 5.22 + 7.83 = 13.05
        //   fare_total_2 = round_down(5.17 + 13.05) = round_down(18.22) = 18.22
        //   owed_2 = round_half_up(18.22 * 0.75) = round_half_up(13.665) = 13.67
        val engine = FareEngine()
        var state = FareState(tariff = URBAN_TARIFF, timeClass = TimeClass.DAY)

        state = engine.tick(state, speedKmh = 40, distanceDeltaKm = 2, elapsedSeconds = 180)
        val checkpoint1 = engine.close(state) // non-mutating: safe to checkpoint mid-trip
        val owed1 = engine.multiHireAmountOwed(checkpoint1, URBAN_TARIFF)

        assertEquals(BigDecimal("10.39"), checkpoint1.fareTotal)
        assertEquals(BigDecimal("7.79"), owed1)

        // Meter keeps running on the SAME state object for hirer 2.
        state = engine.tick(state, speedKmh = 40, distanceDeltaKm = 3, elapsedSeconds = 270)
        val checkpoint2 = engine.close(state)
        val owed2 = engine.multiHireAmountOwed(checkpoint2, URBAN_TARIFF)

        assertEquals(BigDecimal("18.22"), checkpoint2.fareTotal)
        assertEquals(BigDecimal("13.67"), owed2)
    }

    @Test
    fun testF_maxiCabUrbanTripApplies150Pct() {
        // 4km urban day, maxi-eligible via the "5+ passengers" limb (isMaxiVehicle + pax>=5).
        // distance_charge = 4 * 2.61 = 10.44
        // metered_fare (before maxi) = flag_fall 5.17 + 10.44 = 15.61
        // metered_fare (after maxi 1.5x) = 15.61 * 1.5 = 23.415
        // fare_total = round_DOWN(23.415) = 23.41  <- was 23.42 under the old round-half-up rule;
        //              this is a live demonstration of Fix 3, not just test O's dedicated case.
        // gst_component = 23.41 / 11 = 2.128181... -> 2.13
        val engine = FareEngine()
        var state = FareState(
            tariff = URBAN_TARIFF,
            timeClass = TimeClass.DAY,
            isMaxiVehicle = true,
            passengerCount = 5,
        )

        state = engine.tick(state, speedKmh = 40, distanceDeltaKm = 4, elapsedSeconds = 360)

        val breakdown = engine.close(state)

        assertTrue(breakdown.maxiRateApplied)
        assertEquals(BigDecimal("23.41"), breakdown.fareTotal)
        assertEquals(BigDecimal("2.13"), breakdown.gstComponent)
    }

    @Test
    fun testG_sydneyAirportFixedFareStandardAndMaxi() {
        // Fixed $60/$80 (unchanged by the 2026 Order) — no PSL, tolls, or peak may be added on
        // top, only non-cash surcharge and cleaning fee. We deliberately set tolls/PSL/peak on the
        // state to prove close() ignores them entirely for a fixed-fare trip.
        val engine = FareEngine()

        assertEquals(BigDecimal("60.00"), airportFixedFare(maxi = false))
        assertEquals(BigDecimal("80.00"), airportFixedFare(maxi = true))

        val standardState = FareState(
            tariff = URBAN_TARIFF,
            isPeak = true, // should be ignored
        )
        standardState.tolls = BigDecimal("15.00") // should be ignored
        // Not a maxi vehicle -> maxiRateApplied false -> the standard $60 figure, mirroring how
        // TripFareReconstruction actually derives fixedFare in production.
        standardState.fixedFare = airportFixedFare(standardState.maxiRateApplied)

        val breakdown = engine.close(standardState, includePsl = true) // PSL request should be ignored

        assertFalse(breakdown.maxiRateApplied)
        assertEquals(BigDecimal("60.00"), breakdown.fareTotal)
        assertEquals(BigDecimal.ZERO, breakdown.tolls)
        assertEquals(BigDecimal.ZERO, breakdown.psl)
        assertEquals(BigDecimal.ZERO, breakdown.peakCharge)
        assertEquals(BigDecimal("60.00"), breakdown.grandTotal)

        // Sydney Airport rank maxi request -- isMaxiVehicle + airportRankRequestedMaxi, true
        // independent of passenger count -- yields the flat $80 maxi figure (never `60 * 1.5`).
        val maxiState = FareState(
            tariff = URBAN_TARIFF,
            isMaxiVehicle = true,
            airportRankRequestedMaxi = true,
            passengerCount = 1,
        )
        maxiState.fixedFare = airportFixedFare(maxiState.maxiRateApplied)
        val maxiBreakdown = engine.close(maxiState)

        assertTrue(maxiBreakdown.maxiRateApplied)
        assertEquals(BigDecimal("80.00"), maxiBreakdown.fareTotal)
        assertEquals(BigDecimal("80.00"), maxiBreakdown.grandTotal)
    }

    @Test
    fun testH_nonCashSurchargeRoundsHalfUpAtExactBoundary() {
        // fare_total = 200.50 (flag_fall 5.17 + accrued_distance 195.33), custom surcharge_pct = 1.0%.
        // raw surcharge = 200.50 * 1.0 / 100 = 2.005 exactly -> the 0.5c boundary.
        // Fares Order rule (cl 4(a), UNCHANGED by Fix 3 -- only fareTotal itself moved to
        // round-down): <0.5c rounds down, >=0.5c rounds up => rounds UP to 2.01.
        // (Bankers'/round-half-even rounding would instead give 2.00 here -- this test exists
        // specifically to prove we are NOT doing that.)
        val engine = FareEngine()
        val state = FareState(
            tariff = URBAN_TARIFF,
            timeClass = TimeClass.DAY,
            accruedDistanceCharge = BigDecimal("195.33"), // + flag_fall 5.17 = 200.50
        )

        val breakdown = engine.close(state, paymentMethod = "card", surchargePct = BigDecimal("1.0"))

        assertEquals(BigDecimal("200.50"), breakdown.fareTotal)
        assertEquals(BigDecimal("2.01"), breakdown.surcharge)
        assertEquals(BigDecimal("202.51"), breakdown.grandTotal)
    }

    @Test
    fun testI_validateAgainstFaresOrderRankHailVsBooked() {
        // A rank/hail tariff with rates above the Fares Order reference must raise;
        // the identical (still excessive) tariff sold as a BOOKED fare is exempt.
        val excessiveTariff = Tariff(
            name = "rogue-urban",
            area = AreaClass.URBAN,
            flagFall = BigDecimal("5.18"), // 1c above the $5.17 reference cap
            peakCharge = URBAN_TARIFF.peakCharge,
            distRate1 = URBAN_TARIFF.distRate1,
            distRate2 = URBAN_TARIFF.distRate2,
            nightRate1 = URBAN_TARIFF.nightRate1,
            nightRate2 = URBAN_TARIFF.nightRate2,
            holidayRate1 = URBAN_TARIFF.holidayRate1,
            holidayRate2 = URBAN_TARIFF.holidayRate2,
            waitingRatePerMin = URBAN_TARIFF.waitingRatePerMin,
        )

        try {
            validateAgainstFaresOrder(excessiveTariff, URBAN_TARIFF, booked = false)
            fail("expected FaresOrderViolation")
        } catch (e: FaresOrderViolation) {
            // expected
        }

        // Booked fares are unregulated -> the identical excessive tariff is fine.
        validateAgainstFaresOrder(excessiveTariff, URBAN_TARIFF, booked = true)

        // A tariff that matches the reference exactly (not exceeding) never raises,
        // rank/hail or not.
        validateAgainstFaresOrder(URBAN_TARIFF, URBAN_TARIFF, booked = false)

        assertFalse(false) // reached without throwing
    }

    // ------------------------------------------------------------------------------------------
    // J-N: the full maxi-eligibility matrix (Fix 2). All five reuse the exact same 4km urban day
    // trip (distance_charge = 4 * 2.61 = 10.44, metered_fare before maxi = 5.17 + 10.44 = 15.61)
    // so the ONLY thing that varies between them is the eligibility inputs -- isolating exactly
    // what FareState.maxiRateApplied's boolean logic does.
    // ------------------------------------------------------------------------------------------

    private fun fourKmUrbanDayTrip(state: FareState): FareState {
        val engine = FareEngine()
        return engine.tick(state, speedKmh = 40, distanceDeltaKm = 4, elapsedSeconds = 360)
    }

    @Test
    fun testJ_maxiVehicleExactly5PassengersAppliesMaxiRatePslStillFlat() {
        // isMaxiVehicle + passengerCount == 5 (the hard cutoff, inclusive) -> maxi rate applies.
        // metered_fare_after_maxi = 15.61 * 1.5 = 23.415
        // subtotal = 23.415 + psl 1.32 = 24.735
        // fare_total = round_down(24.735) = 24.73
        // gst_component = 24.73 / 11 = 2.248181... -> 2.25
        val engine = FareEngine()
        val state = fourKmUrbanDayTrip(
            FareState(tariff = URBAN_TARIFF, timeClass = TimeClass.DAY, isMaxiVehicle = true, passengerCount = 5),
        )

        val breakdown = engine.close(state, includePsl = true)

        assertTrue(breakdown.maxiRateApplied)
        assertEquals(BigDecimal("1.32"), breakdown.psl)
        assertEquals(BigDecimal("24.73"), breakdown.fareTotal)
        assertEquals(BigDecimal("2.25"), breakdown.gstComponent)
    }

    @Test
    fun testK_maxiVehicle4PassengersNotEligibleHardCutoffAt5() {
        // isMaxiVehicle but only 4 passengers -> below the hard cutoff -> ordinary (100%) rate.
        // fare_total = round_down(15.61) = 15.61 (metered_fare, unmultiplied)
        // gst_component = 15.61 / 11 = 1.419090... -> 1.42
        val engine = FareEngine()
        val state = fourKmUrbanDayTrip(
            FareState(tariff = URBAN_TARIFF, timeClass = TimeClass.DAY, isMaxiVehicle = true, passengerCount = 4),
        )

        val breakdown = engine.close(state)

        assertFalse(breakdown.maxiRateApplied)
        assertEquals(BigDecimal("15.61"), breakdown.fareTotal)
        assertEquals(BigDecimal("1.42"), breakdown.gstComponent)
    }

    @Test
    fun testL_wheelchairHiringOverridesPassengerCountAlwaysOrdinaryRate() {
        // isMaxiVehicle + 6 passengers (would otherwise qualify) but wheelchairHiring=true ->
        // the wheelchair carve-out always wins -> ordinary (100%) rate. Same numbers as K.
        val engine = FareEngine()
        val state = fourKmUrbanDayTrip(
            FareState(
                tariff = URBAN_TARIFF,
                timeClass = TimeClass.DAY,
                isMaxiVehicle = true,
                passengerCount = 6,
                wheelchairHiring = true,
            ),
        )

        val breakdown = engine.close(state)

        assertFalse(breakdown.maxiRateApplied)
        assertEquals(BigDecimal("15.61"), breakdown.fareTotal)
    }

    @Test
    fun testM_airportRankRequestedMaxiAppliesRegardlessOfPassengerCount() {
        // isMaxiVehicle + airportRankRequestedMaxi=true, only 1 passenger, not a wheelchair
        // hiring -> maxi rate applies via the airport-request limb, independent of pax count.
        // Same math as F/J's multiplier (23.415 -> round_down -> 23.41).
        val engine = FareEngine()
        val state = fourKmUrbanDayTrip(
            FareState(
                tariff = URBAN_TARIFF,
                timeClass = TimeClass.DAY,
                isMaxiVehicle = true,
                passengerCount = 1,
                airportRankRequestedMaxi = true,
            ),
        )

        val breakdown = engine.close(state)

        assertTrue(breakdown.maxiRateApplied)
        assertEquals(BigDecimal("23.41"), breakdown.fareTotal)
    }

    @Test
    fun testN_nonMaxiVehicleNeverChargesMaxiRateRegardlessOfPassengerCount() {
        // isMaxiVehicle=false gates everything -- a sedan carrying 6 people (unlawfully, or a
        // data-entry slip) still can't be charged maxi rates. Same numbers as K/L.
        val engine = FareEngine()
        val state = fourKmUrbanDayTrip(
            FareState(tariff = URBAN_TARIFF, timeClass = TimeClass.DAY, isMaxiVehicle = false, passengerCount = 6),
        )

        val breakdown = engine.close(state)

        assertFalse(breakdown.maxiRateApplied)
        assertEquals(BigDecimal("15.61"), breakdown.fareTotal)
    }

    @Test
    fun testO_fareTotalRoundsDownNeverUpAtTheHalfCentBoundary() {
        // Constructed so the raw (pre-rounding) subtotal is 99.505 exactly -- the 0.5c boundary,
        // same shape as testH's surcharge boundary, but for fareTotal itself this time.
        // subtotal = flag_fall 5.17 + accrued_waiting 94.335 = 99.505
        // round_DOWN(99.505)     = 99.50  <- what this engine must produce (Fix 3)
        // round_HALF_UP(99.505)  = 99.51  <- what the OLD (pre-2026-Order) rule would have produced
        // These two must differ, proving the rounding rule actually changed and isn't a no-op.
        val engine = FareEngine()
        val state = FareState(
            tariff = URBAN_TARIFF,
            timeClass = TimeClass.DAY,
            accruedWaitingCharge = BigDecimal("94.335"),
        )

        val breakdown = engine.close(state)

        val rawSubtotal = BigDecimal("99.505")
        assertEquals(BigDecimal("99.50"), breakdown.fareTotal)
        assertEquals(BigDecimal("99.51"), roundHalfUp(rawSubtotal)) // the old rule, for contrast
        assertTrue(breakdown.fareTotal < roundHalfUp(rawSubtotal))
        assertEquals(BigDecimal("9.05"), breakdown.gstComponent)
    }

    @Test
    fun testP_peakChargeAppliesOnANightBeforeAGazettedPublicHoliday() {
        // Thursday 2026-12-24 night, urban -- the night BEFORE the gazetted Friday 2026-12-25
        // Christmas Day public holiday. resolveIsPeakFor() (domain/FareEngine.kt) is what actually
        // computes isPeak from the wall-clock date in production -- see TimeClassResolutionTest
        // for that computation itself; this test proves the FARE MATH consequence once isPeak is
        // (correctly) true for such a night.
        assertEquals(java.time.DayOfWeek.THURSDAY, LocalDate.of(2026, 12, 24).dayOfWeek)
        assertTrue(
            "2026-12-24 must resolve as the day before a gazetted public holiday",
            NswPublicHolidays.isDayBeforePublicHoliday(LocalDate.of(2026, 12, 24)),
        )

        // 5km urban night, peak hiring charge applies.
        // distance_charge = 5 * night_rate_1 3.10 = 15.50
        // subtotal = flag_fall 5.17 + peak 2.65 + 15.50 = 23.32
        // fare_total = round_down(23.32) = 23.32 (exact)
        // gst_component = 23.32 / 11 = 2.12 exactly
        val engine = FareEngine()
        var state = FareState(tariff = URBAN_TARIFF, timeClass = TimeClass.NIGHT, isPeak = true)
        state = engine.tick(state, speedKmh = 50, distanceDeltaKm = 5, elapsedSeconds = 450)

        val breakdown = engine.close(state)

        assertEquals(BigDecimal("2.65"), breakdown.peakCharge)
        assertEquals(BigDecimal("23.32"), breakdown.fareTotal)
        assertEquals(BigDecimal("2.12"), breakdown.gstComponent)
    }

    @Test
    fun testQ_countrySundayDaytimeGetsHolidayTimeClassAndHolidayRates() {
        // 2026-01-04 is a Sunday and not a gazetted public holiday -- resolveTimeClassFor()
        // (domain/FareEngine.kt, proven directly in TimeClassResolutionTest) classifies a
        // country-area daytime trip on this date as HOLIDAY. This test proves the fare-math
        // consequence of that classification.
        assertEquals(java.time.DayOfWeek.SUNDAY, LocalDate.of(2026, 1, 4).dayOfWeek)
        assertFalse(NswPublicHolidays.isPublicHoliday(LocalDate.of(2026, 1, 4)))

        // 6km country, HOLIDAY time class, within first-12km band.
        // distance_charge = 6 * holiday_rate_1 2.97 = 17.82
        // subtotal = flag_fall 5.29 + 17.82 = 23.11
        // fare_total = round_down(23.11) = 23.11 (exact)
        // gst_component = 23.11 / 11 = 2.100909... -> 2.10
        val engine = FareEngine()
        var state = FareState(tariff = COUNTRY_TARIFF, timeClass = TimeClass.HOLIDAY)
        state = engine.tick(state, speedKmh = 45, distanceDeltaKm = 6, elapsedSeconds = 480)

        val breakdown = engine.close(state)

        assertEquals(BigDecimal("17.82"), breakdown.distanceCharge)
        assertEquals(BigDecimal("23.11"), breakdown.fareTotal)
        assertEquals(BigDecimal("2.10"), breakdown.gstComponent)
    }

    @Test
    fun testR_negotiatedFareBillsTheAgreedAmountNotTheMeteredAccrual() {
        // $25 negotiated ("Set Price") trip, $6.43 toll, PSL on. The meter still accrued a much
        // larger metered fare (flag_fall 5.17 + accrued_distance 50.00 = 55.17) -- proving the
        // negotiated amount, not that accrual, is what actually gets billed (Fix 7 / Act s79(3)).
        // subtotal = negotiated_total 25.00 + tolls 6.43 + psl 1.32 = 32.75
        // fare_total = round_down(32.75) = 32.75 (exact)
        // grand_total = 32.75 (cash, no surcharge)
        // gst_component = 32.75 / 11 = 2.977272... -> 2.98
        val engine = FareEngine()
        val state = FareState(
            tariff = URBAN_TARIFF,
            timeClass = TimeClass.DAY,
            accruedDistanceCharge = BigDecimal("50.00"),
            tolls = BigDecimal("6.43"),
            negotiatedTotal = BigDecimal("25.00"),
        )

        val breakdown = engine.close(state, includePsl = true)

        // The meter's own reference accrual is untouched/still visible...
        assertEquals(BigDecimal("5.17"), breakdown.flagFall)
        assertEquals(BigDecimal("50.00"), breakdown.distanceCharge)
        // ...but is NOT what got charged: the negotiated $25 was billed instead of the ~$55.17
        // metered fare component.
        assertEquals(BigDecimal("25.00"), breakdown.negotiatedTotal)
        assertEquals(BigDecimal("32.75"), breakdown.fareTotal)
        assertEquals(BigDecimal("32.75"), breakdown.grandTotal)
        assertEquals(BigDecimal("2.98"), breakdown.gstComponent)
    }

    @Test
    fun testS_faresOrderValidationRejectsOverMaxTariffAtIngestion() {
        // Mirrors sync/TariffCache.kt's validateFaresOrderOrThrow() wrapper logic (region ->
        // reference tariff dispatch, booked-trips-are-exempt) at the DTO boundary -- a plain JVM
        // unit test is the right level for this per this project's own testing convention (see
        // OutboxDrainerTest.kt's doc): TariffCache.refresh() itself also calls through
        // Ed25519TariffSignatureVerifier, which needs android.util.Base64 and so can only run
        // under Robolectric/instrumentation, neither of which this module has -- this test proves
        // the actual regulatory logic (the part introduced by this pass) without that dependency.
        val overMaxUrbanDto = TariffDto(
            id = "tariff-over-max",
            tenantId = null,
            name = "rogue-urban",
            region = "urban",
            effectiveFrom = "2026-06-01T00:00:00+00:00",
            booked = false,
            flagFall = "6.00", // over the $5.17 urban cap
            distRate1 = URBAN_TARIFF.distRate1.toPlainString(),
            distRate2 = URBAN_TARIFF.distRate2.toPlainString(),
            nightRate1 = URBAN_TARIFF.nightRate1.toPlainString(),
            nightRate2 = URBAN_TARIFF.nightRate2.toPlainString(),
            waitingRatePerMin = URBAN_TARIFF.waitingRatePerMin.toPlainString(),
            createdAt = "2026-06-01T00:00:00+00:00",
            updatedAt = "2026-06-01T00:00:00+00:00",
        )

        try {
            validateAgainstFaresOrder(overMaxUrbanDto.toDomainTariff(), URBAN_TARIFF, booked = overMaxUrbanDto.booked)
            fail("expected FaresOrderViolation for an over-max urban tariff at ingestion")
        } catch (e: FaresOrderViolation) {
            // expected
        }

        // At-or-under the cap -> accepted.
        val atCapUrbanDto = overMaxUrbanDto.copy(id = "tariff-at-cap", flagFall = URBAN_TARIFF.flagFall.toPlainString())
        validateAgainstFaresOrder(atCapUrbanDto.toDomainTariff(), URBAN_TARIFF, booked = atCapUrbanDto.booked)

        // The identical over-max rates, sold as a BOOKED fare, are exempt (unregulated).
        val overMaxBookedDto = overMaxUrbanDto.copy(id = "tariff-booked", booked = true)
        validateAgainstFaresOrder(overMaxBookedDto.toDomainTariff(), URBAN_TARIFF, booked = overMaxBookedDto.booked)
    }
}
