package au.com.threesixty.cabdispatch.sync

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SyncWorker]'s WorkManager `Constraints` (W4 task 6, 2026-09-12 optimisation plan): the periodic
 * backstop requires `CONNECTED` + `BATTERY_NOT_LOW`; the reconnect-triggered one-time request
 * stays `CONNECTED`-only, deliberately unconstrained by battery — see
 * [SyncWorker.enqueuePeriodic]/[SyncWorker.enqueueOneTime]'s own docs for why.
 *
 * Plain JVM: building an `androidx.work.Constraints` object needs no `WorkManager` instance, no
 * Robolectric, no Android runtime — this asserts on the actual `Constraints` values
 * [SyncWorker.enqueuePeriodic]/[SyncWorker.enqueueOneTime] hand to their respective
 * `WorkRequest.Builder.setConstraints` calls, via the same package-internal builder functions
 * those methods call, rather than re-describing the requirement independently (which could drift
 * from the real implementation silently).
 */
class SyncWorkerConstraintsTest {

    @Test
    fun `the periodic backstop requires CONNECTED and BATTERY_NOT_LOW`() {
        val constraints = SyncWorker.periodicConstraints()
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue("periodic sync must defer on low battery", constraints.requiresBatteryNotLow())
    }

    @Test
    fun `the reconnect-triggered one-time request requires CONNECTED but NOT battery`() {
        val constraints = SyncWorker.networkConstraints()
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse(
            "a user-initiated-equivalent sync must fire even on low battery, per the plan's own wording",
            constraints.requiresBatteryNotLow(),
        )
    }
}
