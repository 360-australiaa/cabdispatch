package au.com.threesixty.cabdispatch.domain.fare

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [FareEngine.reconcileBlackoutDistance] — W2's additive-only correction method (see that
 * method's own doc). Every existing [FareEngineTest]/[FareBreakdownReconciliationTest] golden
 * vector is untouched by this file; these are new tests for a new method, never a change to an
 * existing one. Expected figures hand-computed against [URBAN_TARIFF]'s own
 * `distRate1 = 2.61`, `distRate2 = 2.37`, `distKmThreshold = 12`.
 */
class FareEngineReconcileTest {

    private fun freshState(cumulativeDistanceKm: BigDecimal, accruedDistanceCharge: BigDecimal = BigDecimal.ZERO) =
        FareState(tariff = URBAN_TARIFF, timeClass = TimeClass.DAY).apply {
            this.cumulativeDistanceKm = cumulativeDistanceKm
            this.accruedDistanceCharge = accruedDistanceCharge
        }

    @Test
    fun positiveDelta_entirelyWithinBand1() {
        // cum=5, delta=3, both entirely under the 12km threshold: 3 * 2.61 = 7.83
        val engine = FareEngine()
        val state = freshState(BigDecimal("5"))

        engine.reconcileBlackoutDistance(state, BigDecimal("3"))

        assertEquals(BigDecimal("8"), state.cumulativeDistanceKm)
        assertEquals(BigDecimal("7.83"), state.accruedDistanceCharge)
    }

    @Test
    fun positiveDelta_crossesBandBoundaryUpward() {
        // cum=10, delta=5: 2km at rate1 (10->12) + 3km at rate2 (12->15)
        // = 2*2.61 + 3*2.37 = 5.22 + 7.11 = 12.33
        val engine = FareEngine()
        val state = freshState(BigDecimal("10"))

        engine.reconcileBlackoutDistance(state, BigDecimal("5"))

        assertEquals(BigDecimal("15"), state.cumulativeDistanceKm)
        assertEquals(BigDecimal("12.33"), state.accruedDistanceCharge)
    }

    @Test
    fun negativeDelta_entirelyWithinBand2() {
        // cum=15, delta=-3: unwinds 3km of band-2 (15->12) = 3*2.37 = 7.11 refunded
        val engine = FareEngine()
        val state = freshState(BigDecimal("15"), BigDecimal("30.00"))

        engine.reconcileBlackoutDistance(state, BigDecimal("-3"))

        assertEquals(BigDecimal("12"), state.cumulativeDistanceKm)
        assertEquals(BigDecimal("22.89"), state.accruedDistanceCharge) // 30.00 - 7.11
    }

    @Test
    fun negativeDelta_crossesBandBoundaryDownward() {
        // cum=14, delta=-5: unwinds 2km band-2 (14->12, 2*2.37=4.74) then 3km band-1
        // (12->9, 3*2.61=7.83) = 12.57 refunded total
        val engine = FareEngine()
        val state = freshState(BigDecimal("14"), BigDecimal("50.00"))

        engine.reconcileBlackoutDistance(state, BigDecimal("-5"))

        assertEquals(BigDecimal("9"), state.cumulativeDistanceKm)
        assertEquals(BigDecimal("37.43"), state.accruedDistanceCharge) // 50.00 - 12.57
    }

    @Test
    fun zeroDelta_isNoOp() {
        val engine = FareEngine()
        val state = freshState(BigDecimal("10"), BigDecimal("20.00"))

        engine.reconcileBlackoutDistance(state, BigDecimal.ZERO)

        assertEquals(BigDecimal("10"), state.cumulativeDistanceKm)
        assertEquals(BigDecimal("20.00"), state.accruedDistanceCharge)
    }

    @Test
    fun notHired_isNoOp() {
        val engine = FareEngine()
        val state = freshState(BigDecimal("10"), BigDecimal("20.00")).apply { hired = false }

        engine.reconcileBlackoutDistance(state, BigDecimal("5"))

        assertEquals(BigDecimal("10"), state.cumulativeDistanceKm)
        assertEquals(BigDecimal("20.00"), state.accruedDistanceCharge)
    }

    @Test
    fun negativeDelta_neverUnwindsPastZero() {
        // A correction larger in magnitude than what was ever billed would be a
        // BlackoutReconciler contract violation, but this method itself still must not go
        // negative even if handed one -- defence in depth, not a documented supported input.
        val engine = FareEngine()
        val state = freshState(BigDecimal("2"), BigDecimal("5.22"))

        engine.reconcileBlackoutDistance(state, BigDecimal("-10"))

        assertFalse("cumulativeDistanceKm must never go negative", state.cumulativeDistanceKm.signum() < 0)
    }
}
