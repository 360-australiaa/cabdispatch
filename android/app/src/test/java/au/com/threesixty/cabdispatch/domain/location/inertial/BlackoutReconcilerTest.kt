package au.com.threesixty.cabdispatch.domain.location.inertial

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [BlackoutReconciler] — pure function, plain JUnit. See that object's own doc for the rule. */
class BlackoutReconcilerTest {

    @Test
    fun roadPathMatch_alwaysWinsOverTheEstimate() {
        // Estimate says 4km, the real corridor is 6.2km -- the road is the truth.
        val result = BlackoutReconciler.reconcile(
            estimatedKm = BigDecimal("4.0"),
            chordKm = BigDecimal("3.5"),
            roadPathKm = BigDecimal("6.2"),
        )

        assertEquals(BigDecimal("6.2"), result.billedKm)
        assertEquals(BigDecimal.valueOf(2.2), result.correctionKm.setScale(1))
        assertEquals(BlackoutReconciler.ReferenceSource.ROAD_PATH, result.referenceSource)
        assertEquals(BigDecimal("6.2"), result.referenceKm)
    }

    @Test
    fun noRoadPath_estimateWithinBounds_isKeptUnchanged() {
        val result = BlackoutReconciler.reconcile(
            estimatedKm = BigDecimal("4.0"),
            chordKm = BigDecimal("3.0"),
            roadPathKm = null,
        )

        assertEquals(BigDecimal("4.0"), result.billedKm)
        assertEquals(BigDecimal.ZERO.setScale(1), result.correctionKm.setScale(1))
        assertEquals(BlackoutReconciler.ReferenceSource.CHORD_BOUNDED, result.referenceSource)
        assertNull(result.referenceKm)
    }

    @Test
    fun noRoadPath_estimateBelowChord_clampsUpToChord() {
        // The vehicle cannot have covered less ground than the straight line between where it
        // entered and exited the blackout.
        val result = BlackoutReconciler.reconcile(
            estimatedKm = BigDecimal("2.0"),
            chordKm = BigDecimal("5.0"),
            roadPathKm = null,
        )

        assertEquals(BigDecimal("5.0"), result.billedKm)
        assertEquals(0, BigDecimal("3.0").compareTo(result.correctionKm))
    }

    @Test
    fun noRoadPath_estimateAboveUpperBound_clampsDownToOneAndHalfChord() {
        val result = BlackoutReconciler.reconcile(
            estimatedKm = BigDecimal("20.0"),
            chordKm = BigDecimal("5.0"),
            roadPathKm = null,
        )

        assertEquals(0, BigDecimal("7.5").compareTo(result.billedKm)) // 5.0 * 1.5
        assertEquals(0, BigDecimal("-12.5").compareTo(result.correctionKm))
    }

    @Test
    fun zeroOrNegativeRoadPath_treatedAsNoMatch() {
        val result = BlackoutReconciler.reconcile(
            estimatedKm = BigDecimal("4.0"),
            chordKm = BigDecimal("3.0"),
            roadPathKm = BigDecimal.ZERO,
        )

        assertEquals(BlackoutReconciler.ReferenceSource.CHORD_BOUNDED, result.referenceSource)
        assertNull(result.referenceKm)
    }
}
