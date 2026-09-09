package au.com.threesixty.cabdispatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM unit tests for [KioskLockController.decideAction] and
 * [KioskLockController.isPinConfirmed] — the two pure decision tables behind
 * [KioskLockController.applyKioskLock]. Deliberately exercises the enum/boolean overloads only,
 * not [KioskLockController.applyKioskLock] itself, since that needs a live
 * [android.app.Activity]/[android.app.ActivityManager] and is exactly what both functions were
 * split out to avoid needing for these cases — see [KioskLockController]'s class doc for the full
 * write-up.
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

    // --- isPinConfirmed: the after-the-fact OS read applyKioskLock now exposes to
    // KioskLockedBanner, so a driver can tell "the depot asked for this" apart from "and the OS
    // actually granted it" -- see KioskLockController.applyKioskLock's doc and
    // FleetCommandOverlays.kt's KioskLockedBanner doc for the defect this closes. Deliberately
    // exercises the pure enum/boolean function only, matching this file's existing split for
    // decideAction: no live Activity/ActivityManager needed for any of these cases.

    @Test
    fun `pin confirmed -- OS reports PINNED after a requested lock`() {
        // The success case startLockTask() silently failing would have masked before this fix:
        // requesting a lock and the OS actually granting the plain screen pin it can grant.
        assertTrue(KioskLockController.isPinConfirmed(LockTaskMode.PINNED, desiredLocked = true))
    }

    @Test
    fun `pin confirmed -- OS reports the stronger DPC-Knox LOCKED after a requested lock`() {
        // LOCKED is a guarantee stronger than this app can ever request on its own, so a tablet
        // already locked that way has nothing left to confirm.
        assertTrue(KioskLockController.isPinConfirmed(LockTaskMode.LOCKED, desiredLocked = true))
    }

    @Test
    fun `pin NOT confirmed -- OS still reports NONE after a requested lock (startLockTask silently refused)`() {
        // This is the exact silent-failure case the ticket describes: a device whose screen-pinning
        // setting is disabled, or any other OEM quirk, leaves startLockTask() a no-op with no
        // exception and no return value to check. Only re-reading the live mode afterwards catches it.
        assertFalse(KioskLockController.isPinConfirmed(LockTaskMode.NONE, desiredLocked = true))
    }

    @Test
    fun `pin confirmation is distinct from not having been requested at all`() {
        // desiredLocked = false is "not requested" -- KioskLockedBanner's caller never even composes
        // the chip in that state (see MainActivity.kt's `if (commandState.kioskLocked)` gate), which
        // is a third, separate state from "requested but not confirmed". Confirming that here keeps
        // isPinConfirmed's two booleans from ever being read as if they collapsed into the same case.
        assertTrue(KioskLockController.isPinConfirmed(LockTaskMode.NONE, desiredLocked = false))
        assertFalse(KioskLockController.isPinConfirmed(LockTaskMode.NONE, desiredLocked = true))
    }

    @Test
    fun `pin confirmed unlocked -- OS reports NONE after a released lock`() {
        assertTrue(KioskLockController.isPinConfirmed(LockTaskMode.NONE, desiredLocked = false))
    }

    @Test
    fun `pin NOT confirmed unlocked -- OS still reports PINNED after a requested release`() {
        assertFalse(KioskLockController.isPinConfirmed(LockTaskMode.PINNED, desiredLocked = false))
    }
}
