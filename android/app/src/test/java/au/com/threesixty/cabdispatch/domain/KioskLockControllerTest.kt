package au.com.threesixty.cabdispatch.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain-JVM unit tests for [KioskLockController.decideAction] — the pure decision table behind
 * [KioskLockController.applyKioskLock]. Deliberately exercises the enum/boolean overload only, not
 * [KioskLockController.applyKioskLock] itself, since that half needs a live [android.app.Activity]/
 * [android.app.ActivityManager] and is exactly what [decideAction] was split out to avoid needing
 * for these six cases — see [KioskLockController]'s class doc for the full write-up.
 */
class KioskLockControllerTest {

    @Test
    fun `not pinned and not commanded locked -- does nothing`() {
        assertEquals(
            KioskLockAction.NONE,
            KioskLockController.decideAction(LockTaskMode.NONE, desiredLocked = false),
        )
    }

    @Test
    fun `not pinned but commanded locked -- starts the pin`() {
        assertEquals(
            KioskLockAction.START,
            KioskLockController.decideAction(LockTaskMode.NONE, desiredLocked = true),
        )
    }

    @Test
    fun `pinned by this app but no longer commanded locked -- releases the pin`() {
        assertEquals(
            KioskLockAction.STOP,
            KioskLockController.decideAction(LockTaskMode.PINNED, desiredLocked = false),
        )
    }

    @Test
    fun `pinned by this app and still commanded locked -- does nothing`() {
        assertEquals(
            KioskLockAction.NONE,
            KioskLockController.decideAction(LockTaskMode.PINNED, desiredLocked = true),
        )
    }

    @Test
    fun `DPC-Knox locked and no longer commanded locked -- refuses to release a lock this app did not set`() {
        assertEquals(
            KioskLockAction.NONE,
            KioskLockController.decideAction(LockTaskMode.LOCKED, desiredLocked = false),
        )
    }

    @Test
    fun `DPC-Knox locked and still commanded locked -- does nothing (already locked, more strongly than we can)`() {
        assertEquals(
            KioskLockAction.NONE,
            KioskLockController.decideAction(LockTaskMode.LOCKED, desiredLocked = true),
        )
    }
}
