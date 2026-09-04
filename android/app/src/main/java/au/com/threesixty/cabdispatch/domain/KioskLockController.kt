package au.com.threesixty.cabdispatch.domain

import android.app.Activity
import android.app.ActivityManager
import android.content.Context

/**
 * The current OS-reported lock-task state, as a plain enum so the decision logic below
 * ([KioskLockController.decideAction]) never has to touch a live [ActivityManager] to be tested.
 * Mirrors [ActivityManager]'s three `LOCK_TASK_MODE_*` constants 1:1 — see
 * [KioskLockController.currentLockTaskMode] for the live mapping.
 */
enum class LockTaskMode {
    /** `ActivityManager.LOCK_TASK_MODE_NONE` — not pinned/locked at all. */
    NONE,

    /** `ActivityManager.LOCK_TASK_MODE_PINNED` — pinned by [Activity.startLockTask] with no
     * DPC allowlist backing it (screen pinning, the only mode this app can ever put itself into —
     * see [KioskLockController]'s class doc). This app both starts and may stop this mode. */
    PINNED,

    /** `ActivityManager.LOCK_TASK_MODE_LOCKED` — a device-owner/Knox-allowlisted lock. This app
     * holds no device-owner provisioning (see [DeviceCommandHeartbeat]'s class doc), so it never
     * *causes* this mode itself, but a DPC/Knox policy elsewhere on the tablet could still leave the
     * OS reporting it — see [KioskLockController.decideAction]'s doc for why that case is always a
     * no-op here. */
    LOCKED,
}

/** What [KioskLockController.decideAction] wants done, decoupled from any live [Activity] call so
 * it stays trivially unit-testable — see that function's doc for the full decision table. */
enum class KioskLockAction {
    /** Nothing to do — already in the state the desired flag calls for, or refusing to act (see
     * [KioskLockController.decideAction]'s [LockTaskMode.LOCKED] case). */
    NONE,
    START,
    STOP,
}

/**
 * Turns `Device.kiosk_locked` (as last read off [DeviceCommandHeartbeat.state]) into
 * [Activity.startLockTask]/[Activity.stopLockTask] calls — the piece [MainActivity]'s old
 * `TODO(kiosk agent)` doc comment pointed at and nothing implemented.
 *
 * ### Screen pinning, not device-owner kiosk mode
 * This app holds no device-owner provisioning and the manifest sets no `android:lockTaskMode` on
 * `.MainActivity` (both confirmed absent), so the only lock-task mode this app can ever *start*
 * is plain Android screen pinning (`Activity.startLockTask()` with no DPC allowlist), which lands
 * in [LockTaskMode.PINNED] — not [LockTaskMode.LOCKED]. Pinning hides Home/Recents/Overview and
 * traps the driver on this Activity, but is user-escapable (a long Back+Recents press prompts to
 * unpin) and does not survive a reboot or task removal — see [DeviceCommandHeartbeat]'s "Act-then-
 * delay" section for why the heartbeat re-applies it on every poll rather than once.
 *
 * ### The one-way [LockTaskMode.LOCKED] rule
 * [decideAction] never returns [KioskLockAction.STOP] for [LockTaskMode.LOCKED]. That mode means a
 * DPC/Knox-side allowlisted lock this app did not start (it has no device-owner permissions to
 * start one), so it has no business releasing one either — see [DeviceCommandHeartbeat]'s doc,
 * "One limit on that, cross-referencing MainActivity's deliberate `LOCK_TASK_MODE_LOCKED` trade".
 * Only a pin this app itself put in [LockTaskMode.PINNED] is ever stopped.
 */
object KioskLockController {

    /**
     * Pure decision table — no [Activity]/[ActivityManager] dependency, so this is unit-testable
     * with plain enum/boolean arguments. All six `(currentMode, desiredLocked)` combinations:
     *
     * | currentMode | desiredLocked | action                                            |
     * |-------------|---------------|----------------------------------------------------|
     * | NONE        | false         | NONE (already unlocked)                             |
     * | NONE        | true          | START                                               |
     * | PINNED      | false         | STOP (release the pin this app owns)                |
     * | PINNED      | true          | NONE (already pinned)                               |
     * | LOCKED      | false         | NONE (refuse — not this app's pin to release)       |
     * | LOCKED      | true          | NONE (already locked, and more strongly than we can)|
     */
    fun decideAction(currentMode: LockTaskMode, desiredLocked: Boolean): KioskLockAction {
        return when (currentMode) {
            LockTaskMode.NONE -> if (desiredLocked) KioskLockAction.START else KioskLockAction.NONE
            LockTaskMode.PINNED -> if (desiredLocked) KioskLockAction.NONE else KioskLockAction.STOP
            LockTaskMode.LOCKED -> KioskLockAction.NONE
        }
    }

    /** Live [ActivityManager.getLockTaskModeState] mapped onto [LockTaskMode] — the only place in
     * this file that touches a real system service, kept separate from [decideAction] so that
     * function stays instrumentation-free. */
    private fun currentLockTaskMode(activity: Activity): LockTaskMode {
        val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return when (activityManager.lockTaskModeState) {
            ActivityManager.LOCK_TASK_MODE_LOCKED -> LockTaskMode.LOCKED
            ActivityManager.LOCK_TASK_MODE_PINNED -> LockTaskMode.PINNED
            else -> LockTaskMode.NONE
        }
    }

    /**
     * Reads [activity]'s live lock-task mode, runs it through [decideAction] against
     * [desiredLocked], and performs the resulting [KioskLockAction] (or does nothing). Called from
     * [MainActivity]'s composition root on every [DeviceCommandHeartbeat.state] change — see that
     * file's doc for the collection site.
     */
    fun applyKioskLock(activity: Activity, desiredLocked: Boolean) {
        when (decideAction(currentLockTaskMode(activity), desiredLocked)) {
            KioskLockAction.START -> activity.startLockTask()
            KioskLockAction.STOP -> activity.stopLockTask()
            KioskLockAction.NONE -> Unit
        }
    }
}
