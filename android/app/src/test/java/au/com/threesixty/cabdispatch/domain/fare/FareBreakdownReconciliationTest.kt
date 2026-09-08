package au.com.threesixty.cabdispatch.domain.fare

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The itemised breakdown must add up to the total it is printed above.
 *
 * Found on a live tablet trip, 2026-09-08: the passenger's TAX INVOICE read
 *
 *     Hiring charge $5.00 / Distance $0.76 / Waiting $3.86 / P2P Levy $1.32
 *     TOTAL $10.93
 *
 * — four lines adding to $10.94. The sub-cent tails of the two accrued components were in the
 * total (which rounds DOWN, per Fares Order cl 4(a)) but not in the lines, which each rounded
 * HALF-UP independently with nothing reconciling them. A receipt whose items do not add up to its
 * own total is not a valid tax invoice.
 *
 * This is a device-side failure specifically. The backend rebuilds a closing state from the
 * already-rounded persisted `trip.dist_amount`/`wait_amount`, so its inputs were always whole
 * cents and its itemisation always reconciled; the device recomputes both accruals from raw metres
 * and seconds ([reconstructFareState]) and so is the only side that ever carries a sub-cent tail —
 * and the only side that prints receipts. Every existing golden vector used tidy cent-denominated
 * inputs, which is why none of them saw it. These put the tails back.
 *
 * Mirrors `test_the_itemised_breakdown_always_sums_to_the_fare_total` in
 * backend/tests/test_fare_engine_golden.py.
 */
class FareBreakdownReconciliationTest {

    /** Every line a receipt or the Close & Pay breakdown prints, added up. */
    private fun itemisedSum(b: FareBreakdown): BigDecimal =
        b.flagFall + b.peakCharge + b.distanceCharge + b.waitingCharge + b.maxiUplift +
            b.tolls + b.psl + b.extras + b.cleaningFee

    private fun closeWith(
        distance: String,
        waiting: String,
        tolls: String = "0",
        maxi: Boolean = false,
    ): FareBreakdown = FareEngine().close(
        FareState(
            tariff = URBAN_TARIFF,
            timeClass = TimeClass.DAY,
            isMaxiVehicle = maxi,
            passengerCount = if (maxi) 5 else 1,
            accruedDistanceCharge = BigDecimal(distance),
            accruedWaitingCharge = BigDecimal(waiting),
            tolls = BigDecimal(tolls),
        ),
        includePsl = true,
    )

    private fun assertReconciles(b: FareBreakdown, case: String) {
        assertEquals("$case: lines must sum to the total", 0, itemisedSum(b).compareTo(b.fareTotal))
        // Reconciling by handing the carry to the waiting line is only honest while every line
        // stays a real amount of money.
        assertTrue("$case: distance", b.distanceCharge.signum() >= 0)
        assertTrue("$case: waiting", b.waitingCharge.signum() >= 0)
        assertTrue("$case: uplift", b.maxiUplift.signum() >= 0)
    }

    @Test
    fun `the live receipt case reconciles`() {
        // The shape that produced $10.93 above lines adding to $10.94.
        val b = closeWith(distance = "0.7551", waiting = "3.8557")
        assertReconciles(b, "live case")
    }

    @Test
    fun `sub-cent tails, half-cent boundaries and exact cents all reconcile`() {
        assertReconciles(closeWith("0.7551", "3.8557"), "tails")
        assertReconciles(closeWith("1.005", "2.005"), "half-cent up")
        assertReconciles(closeWith("1.004", "2.004"), "half-cent down")
        assertReconciles(closeWith("12.34", "5.67", tolls = "8.90"), "exact cents")
        assertReconciles(closeWith("0", "0"), "empty trip")
        assertReconciles(closeWith("0.999", "0"), "distance alone, rounding up would overdraw")
    }

    @Test
    fun `a maxi trip reconciles, including its uplift line`() {
        assertReconciles(closeWith("0.7551", "3.8557", maxi = true), "maxi tails")
        assertReconciles(closeWith("2.42", "2.42", tolls = "7.13", maxi = true), "maxi with tolls")
        assertReconciles(closeWith("0.01", "0.01", maxi = true), "maxi minimum")
        assertReconciles(closeWith("0", "0", maxi = true), "maxi empty")
    }

    @Test
    fun `a non-maxi trip reports no uplift line at all`() {
        // Zero because the multiplier is 1, by the same arithmetic as the maxi case — not by a
        // special case. A stray uplift row on an ordinary sedan fare would be fiction.
        assertEquals(0, closeWith("0.7551", "3.8557").maxiUplift.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `reconciling never changes what the passenger is charged`() {
        // cl 4(a): the fare charged rounds DOWN from the raw subtotal. The itemisation is a
        // presentation of that number and must never move it -- rounding the components up first
        // (the obvious "fix") would have raised this trip by a cent.
        //   raw subtotal = flagfall 5.17 + waiting 94.335 = 99.505
        //   round_down(99.505) = 99.50   <- what must still be charged
        val b = FareEngine().close(
            FareState(
                tariff = URBAN_TARIFF,
                timeClass = TimeClass.DAY,
                accruedWaitingCharge = BigDecimal("94.335"),
            ),
        )

        assertEquals(0, b.fareTotal.compareTo(BigDecimal("99.50")))
        assertReconciles(b, "round-down boundary")
        // ...and the waiting line is the one that gives up the cent, not the total.
        assertEquals(0, b.waitingCharge.compareTo(BigDecimal("94.33")))
    }

    @Test
    fun `an absorbed fare reports no uplift line`() {
        // A negotiated or Sydney-Airport-fixed price is all-inclusive and is not itemised at all --
        // the agreed number IS the total -- so an uplift line would be fiction.
        val negotiated = FareEngine().close(
            FareState(
                tariff = URBAN_TARIFF,
                isMaxiVehicle = true,
                passengerCount = 5,
                negotiatedTotal = BigDecimal("50.00"),
            ),
        )
        val airport = FareEngine().close(
            FareState(
                tariff = URBAN_TARIFF,
                isMaxiVehicle = true,
                passengerCount = 5,
            ).also { it.fixedFare = airportFixedFare(maxi = true) },
        )

        assertEquals(0, negotiated.maxiUplift.compareTo(BigDecimal.ZERO))
        assertEquals(0, airport.maxiUplift.compareTo(BigDecimal.ZERO))
    }
}
