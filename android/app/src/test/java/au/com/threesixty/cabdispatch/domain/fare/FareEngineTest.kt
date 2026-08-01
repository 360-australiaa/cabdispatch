package au.com.threesixty.cabdispatch.domain.fare

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Golden compliance-evidence vectors for the NSW tariff-switching fare
 * engine — plain JUnit4, plain JVM (no Android instrumentation needed).
 *
 * Every test here is a direct port of the corresponding test in
 * `backend/tests/test_fare_engine_golden.py` (test_a..test_i). Same
 * scenarios, same expected Decimal/BigDecimal numbers, so the Kotlin offline
 * meter and the Python server engine are provably in lockstep. Do NOT "fix" a
 * failing assertion by copying the engine's output back in — if a test
 * fails, [FareEngine] (or this file) has drifted from the Python reference
 * and must be reconciled against it.
 */
class FareEngineTest {

    @Test
    fun testA_shortUrbanDayTripAllMoving() {
        // 3km, all >=26km/h, urban day, cash. Within first-12km band throughout.
        // distance_charge = 3 * 2.52              = 7.56
        // subtotal        = flag_fall 5.00 + 7.56 = 12.56
        // gst_component   = 12.56 / 11 = 1.1418... -> 1.14
        val engine = FareEngine()
        var state = FareState(tariff = URBAN_TARIFF, timeClass = TimeClass.DAY)

        state = engine.tick(state, speedKmh = 40, distanceDeltaKm = 3, elapsedSeconds = 270)

        val breakdown = engine.close(state)

        assertEquals(BigDecimal("7.56"), breakdown.distanceCharge)
        assertEquals(BigDecimal("12.56"), breakdown.fareTotal)
        assertEquals(BigDecimal.ZERO, breakdown.surcharge)
        assertEquals(BigDecimal("12.56"), breakdown.grandTotal)
        assertEquals(BigDecimal("1.14"), breakdown.gstComponent)
    }

    @Test
    fun testB_urbanNightTripOver12kmMixesBothBands() {
        // 16km in one continuous fast segment, urban night (10pm-6am), peak hiring.
        // first 12km  @ night_rate_1 3.00 = 36.00
        // next   4km  @ night_rate_2 2.73 = 10.92
        // distance_charge = 46.92
        // subtotal = flag_fall 5.00 + peak 2.56 + 46.92 = 54.48
        // gst_component = 54.48 / 11 = 4.9527... -> 4.95
        val engine = FareEngine()
        var state = FareState(tariff = URBAN_TARIFF, timeClass = TimeClass.NIGHT, isPeak = true)

        state = engine.tick(state, speedKmh = 50, distanceDeltaKm = 16, elapsedSeconds = 900)

        assertEquals(BigDecimal(16), state.cumulativeDistanceKm)
        val breakdown = engine.close(state)

        assertEquals(BigDecimal("46.92"), breakdown.distanceCharge)
        assertEquals(BigDecimal("54.48"), breakdown.fareTotal)
        assertEquals(BigDecimal("4.95"), breakdown.gstComponent)
    }

    @Test
    fun testC_countryHolidayTrip() {
        // 10km, country, Sunday daytime (holiday time_class), within first-12km band.
        // distance_charge = 10 * holiday_rate_1 2.87 = 28.70
        // subtotal = flag_fall 5.11 + 28.70 = 33.81
        // gst_component = 33.81 / 11 = 3.07363... -> 3.07
        val engine = FareEngine()
        var state = FareState(tariff = COUNTRY_TARIFF, timeClass = TimeClass.HOLIDAY)

        state = engine.tick(state, speedKmh = 45, distanceDeltaKm = 10, elapsedSeconds = 800)

        val breakdown = engine.close(state)

        assertEquals(BigDecimal("28.70"), breakdown.distanceCharge)
        assertEquals(BigDecimal("33.81"), breakdown.fareTotal)
        assertEquals(BigDecimal("3.07"), breakdown.gstComponent)
    }

    @Test
    fun testD_waitingHeavyCbdCrawl() {
        // Mostly <26km/h (waiting), urban day, one short fast hop mixed in.
        // waiting: 10 min @ 1.092/min = 10.92
        // waiting:  5 min @ 1.092/min =  5.46
        // waiting_charge total = 16.38
        // distance: 1km @ dist_rate_1 2.52 = 2.52
        // subtotal = flag_fall 5.00 + 2.52 + 16.38 = 23.90
        // gst_component = 23.90 / 11 = 2.17272... -> 2.17
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

        assertEquals(BigDecimal("16.38"), breakdown.waitingCharge)
        assertEquals(BigDecimal("2.52"), breakdown.distanceCharge)
        assertEquals(BigDecimal("23.90"), breakdown.fareTotal)
        assertEquals(BigDecimal("2.17"), breakdown.gstComponent)
    }

    @Test
    fun testE_multipleHiringEachHirerOwes75PctAtTheirDrop() {
        // 2 hirers, meter runs ONCE (single continuous state, never reset).
        //
        // Hirer 1 drop checkpoint:
        //   distance_charge so far = 2km * 2.52 = 5.04
        //   fare_total_1 = flag_fall 5.00 + 5.04 = 10.04
        //   owed_1 = 10.04 * 0.75 = 7.53
        //
        // Hirer 2 (final) drop:
        //   + 3km * 2.52 = 7.56  ->  distance_charge = 5.04 + 7.56 = 12.60
        //   fare_total_2 = 5.00 + 12.60 = 17.60
        //   owed_2 = 17.60 * 0.75 = 13.20
        val engine = FareEngine()
        var state = FareState(tariff = URBAN_TARIFF, timeClass = TimeClass.DAY)

        state = engine.tick(state, speedKmh = 40, distanceDeltaKm = 2, elapsedSeconds = 180)
        val checkpoint1 = engine.close(state) // non-mutating: safe to checkpoint mid-trip
        val owed1 = engine.multiHireAmountOwed(checkpoint1, URBAN_TARIFF)

        assertEquals(BigDecimal("10.04"), checkpoint1.fareTotal)
        assertEquals(BigDecimal("7.53"), owed1)

        // Meter keeps running on the SAME state object for hirer 2.
        state = engine.tick(state, speedKmh = 40, distanceDeltaKm = 3, elapsedSeconds = 270)
        val checkpoint2 = engine.close(state)
        val owed2 = engine.multiHireAmountOwed(checkpoint2, URBAN_TARIFF)

        assertEquals(BigDecimal("17.60"), checkpoint2.fareTotal)
        assertEquals(BigDecimal("13.20"), owed2)
    }

    @Test
    fun testF_maxiCabUrbanTripApplies150Pct() {
        // 4km urban day, maxi flagged (5+ pax).
        // subtotal_before_maxi = flag_fall 5.00 + (4 * 2.52 = 10.08) = 15.08
        // fare_total = 15.08 * 1.5 = 22.62
        // gst_component = 22.62 / 11 = 2.05636... -> 2.06
        val engine = FareEngine()
        var state = FareState(tariff = URBAN_TARIFF, timeClass = TimeClass.DAY, maxi = true)

        state = engine.tick(state, speedKmh = 40, distanceDeltaKm = 4, elapsedSeconds = 360)

        val breakdown = engine.close(state)

        assertTrue(breakdown.maxiApplied)
        assertEquals(BigDecimal("22.62"), breakdown.fareTotal)
        assertEquals(BigDecimal("2.06"), breakdown.gstComponent)
    }

    @Test
    fun testG_sydneyAirportFixedFareStandardAndMaxi() {
        // Fixed $60/$80 — no PSL, tolls, or peak may be added on top, only
        // non-cash surcharge and cleaning fee. We deliberately set tolls/PSL/peak
        // on the state to prove close() ignores them entirely for a fixed-fare trip.
        val engine = FareEngine()

        assertEquals(BigDecimal("60.00"), airportFixedFare(maxi = false))
        assertEquals(BigDecimal("80.00"), airportFixedFare(maxi = true))

        val standardState = FareState(
            tariff = URBAN_TARIFF,
            isPeak = true, // should be ignored
            fixedFare = airportFixedFare(maxi = false),
        )
        standardState.tolls = BigDecimal("15.00") // should be ignored

        val breakdown = engine.close(standardState, includePsl = true) // PSL request should be ignored

        assertEquals(BigDecimal("60.00"), breakdown.fareTotal)
        assertEquals(BigDecimal.ZERO, breakdown.tolls)
        assertEquals(BigDecimal.ZERO, breakdown.psl)
        assertEquals(BigDecimal.ZERO, breakdown.peakCharge)
        assertEquals(BigDecimal("60.00"), breakdown.grandTotal)

        val maxiState = FareState(tariff = URBAN_TARIFF, maxi = true, fixedFare = airportFixedFare(maxi = true))
        val maxiBreakdown = engine.close(maxiState)

        assertEquals(BigDecimal("80.00"), maxiBreakdown.fareTotal)
        assertEquals(BigDecimal("80.00"), maxiBreakdown.grandTotal)
    }

    @Test
    fun testH_nonCashSurchargeRoundsHalfUpAtExactBoundary() {
        // fare_total = 200.50, custom surcharge_pct = 1.0%.
        // raw surcharge = 200.50 * 1.0 / 100 = 2.005 exactly -> the 0.5c boundary.
        // Fares Order rule: <0.5c rounds down, >=0.5c rounds up => rounds UP to 2.01.
        // (Bankers'/round-half-even rounding would instead give 2.00 here — this test
        // exists specifically to prove we are NOT doing that.)
        val engine = FareEngine()
        val state = FareState(
            tariff = URBAN_TARIFF,
            timeClass = TimeClass.DAY,
            accruedDistanceCharge = BigDecimal("195.50"), // + flag_fall 5.00 = 200.50
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
            flagFall = BigDecimal("5.01"), // 1c above the $5.00 reference cap
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
}
