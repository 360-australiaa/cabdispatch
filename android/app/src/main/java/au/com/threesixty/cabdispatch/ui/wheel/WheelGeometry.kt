package au.com.threesixty.cabdispatch.ui.wheel

import kotlin.math.cos
import kotlin.math.sin

/**
 * Layout constants for the wheel, per design spec TCT-DRIVER-APP-01.md §3 and the reference
 * prototype's literal CSS/JS values (docs/driver-dashboard-full-prototype.html: `radius=130,
 * labelRadius=172`, `.wheel-wrap{width:352px;height:352px}`, `.dot-btn{width:44px}`,
 * `.slot.selected .dot-btn{width:60px}`) — these are the ground-truth numbers named in the task
 * brief, which take precedence over the design doc's looser "≈140/185-190/176-180" range
 * language for the same geometry.
 *
 * All values below are dp, taken directly from the prototype's px values on the assumption that
 * the reference canvas (1280×800) and the prototype's own CSS px are already a reasonable
 * tablet dp space. A screen-owning sibling agent placing the wheel on a differently-sized real
 * tablet should scale every constant here by the same factor (so all ratios — icon-ring radius /
 * wheel diameter, label radius / wheel diameter, etc. — stay identical to the reference), not
 * pick new numbers independently.
 */
object WheelGeometry {
    /** Number of slots / degrees-per-slot — re-exported from [WheelState] for convenience. */
    const val SLOT_COUNT = WheelState.SLOT_COUNT
    const val STEP_DEGREES = WheelState.STEP_DEGREES

    /** `.wheel-wrap` — the square hit-target/visual bounds the wheel is drawn in. */
    const val WHEEL_DIAMETER_DP = 352f

    /** Distance from wheel center to each slot's icon dot center (`radius` in the JS). */
    const val ICON_RING_RADIUS_DP = 130f

    /** Distance from wheel center to each slot's label anchor (`labelRadius` in the JS). */
    const val LABEL_RING_RADIUS_DP = 172f

    /** `.dot-btn` unselected size. */
    const val UNSELECTED_DOT_SIZE_DP = 44f

    /** `.slot.selected .dot-btn` size. */
    const val SELECTED_DOT_SIZE_DP = 60f

    /** Snap animation duration — spec §3 "~450-500ms"; see `WheelSnapEasing` in WheelGesture.kt. */
    const val SNAP_DURATION_MS = 480

    /** Content-swap fade duration — spec §4 "~130-150ms fade". */
    const val CONTENT_FADE_MS = 140

    /** Speed-lock threshold (km/h) — spec §3, same GPS feed as the fare engine's tariff switch. */
    const val SPEED_LOCK_THRESHOLD_KMH = 26.0

    /**
     * A slot's current on-screen angle in degrees, screen-space convention (0°=3 o'clock/+x,
     * increasing clockwise — i.e. `atan2(dy,dx)` in Compose's y-down coordinate space), normalized
     * to [0, 360).
     */
    fun slotScreenAngleDeg(slotIndex: Int, wheelRotationDeg: Float): Float =
        WheelState.normalizeUnsigned(slotIndex * STEP_DEGREES + wheelRotationDeg)

    /**
     * (x, y) dp offset from the wheel's center for a point at [angleDeg] (screen-space
     * convention, see [slotScreenAngleDeg]) and [radiusDp] from center. Use with
     * `Modifier.offset(x.dp, y.dp)` anchored at the wheel's center to place a slot's dot or
     * label — matches the JS reference's
     * `rotate(baseAngle) translate(radius) rotate(-baseAngle)` composition, just computed
     * directly instead of via nested CSS transforms.
     */
    fun offsetForAngle(angleDeg: Float, radiusDp: Float): Pair<Float, Float> {
        val rad = Math.toRadians(angleDeg.toDouble())
        return (radiusDp * cos(rad)).toFloat() to (radiusDp * sin(rad)).toFloat()
    }
}
