package au.com.threesixty.cabdispatch.ui.wheel

/**
 * The 6 fixed wheel slots, in wheel order (index == rest-angle-slot == `index * 60°`), per
 * design spec TCT-DRIVER-APP-01.md §3 and the reference prototype
 * (docs/driver-dashboard-full-prototype.html's `data` array, same order). Screen-owning sibling
 * agents should key off [index]/this enum rather than inventing their own 0..5 mapping, so every
 * screen agrees on which wheel position is which.
 */
enum class WheelSlot(val index: Int, val label: String) {
    OFF_DUTY_AVAILABLE(0, "Off Duty/Available"),
    AVAILABLE_TRIPS(1, "Available Trips"),
    MESSAGES(2, "Messages"),
    TRIPS(3, "Trips"),
    EARNINGS(4, "Earnings"),
    SHIFT(5, "Shift"),
    ;

    companion object {
        fun fromIndex(index: Int): WheelSlot = entries.first { it.index == index }
    }
}

/**
 * Pure state holder for the six-slot rotating wheel selector — no Compose/animation types here on
 * purpose, so this class is trivially unit-testable. [WheelGesture.kt] drives an
 * `Animatable<Float, ...>` from the values this class computes; it does not own one itself.
 *
 * This is a line-for-line port of the working JS reference implementation's `rotation`/
 * `dragging`/`lastAngle`/`snapAndSelect()` state
 * (docs/driver-dashboard-full-prototype.html) — treat that file as the spec if anything here is
 * ambiguous, per the task brief. Mechanics recap:
 *
 * - 6 slots, 60° apart. Slot `i`'s REST angle is `i * 60°`, measured clockwise from the wheel's
 *   un-rotated 0° point (screen-space convention: 0°=3 o'clock/+x, angles increase clockwise —
 *   i.e. standard `atan2(dy, dx)` in a y-down coordinate space, which is what
 *   [android.graphics]/Compose already use, so no sign flip is needed anywhere in this file or
 *   [WheelGesture.kt]).
 * - [rotation] is one shared value applied to the whole ring; a slot's current on-screen angle is
 *   `(i*60 + rotation) mod 360` (see [WheelGeometry.slotScreenAngleDeg]).
 * - [rotation] is NOT normalized between calls — it accumulates exactly like the JS `rotation`
 *   variable does (can exceed 360 or go negative after many drags). Only [onRelease] normalizes,
 *   the same places the JS does (`norm = ((rotation%360)+360)%360`).
 */
data class WheelState(
    val rotation: Float = 0f,
    val selectedIndex: Int = 0,
    val dragging: Boolean = false,
    val lastPointerAngle: Float = 0f,
) {
    companion object {
        const val SLOT_COUNT = 6
        const val STEP_DEGREES = 360f / SLOT_COUNT // 60°

        /**
         * Matches the JS reference's inline delta-clamp exactly:
         * `if (delta>180) delta-=360; if (delta<-180) delta+=360;` (strict `<`, not `<=`, on the
         * negative branch — kept literal rather than "cleaned up" to (-180,180] so this stays a
         * faithful port).
         */
        fun normalizeSigned(angleDeg: Float): Float {
            var a = angleDeg % 360f
            if (a > 180f) a -= 360f
            if (a < -180f) a += 360f
            return a
        }

        /** Normalizes into [0, 360), matching the JS reference's `((x%360)+360)%360`. */
        fun normalizeUnsigned(angleDeg: Float): Float {
            var a = angleDeg % 360f
            if (a < 0f) a += 360f
            return a
        }
    }

    /**
     * Pointer-down: begin tracking. Records [pointerAngle] (degrees, `atan2(dy,dx)` relative to
     * the wheel's center) as the baseline the first `onDrag` delta is measured from — mirrors the
     * JS `pointerdown` handler's `lastAngle = angleFromCenter(...)`.
     */
    fun onDragStart(pointerAngle: Float): WheelState =
        copy(dragging = true, lastPointerAngle = pointerAngle)

    /**
     * Pointer-move: free rotation from anywhere on the ring (not just a slot) — this was an
     * explicit correction over an earlier wrong version, see spec §3. Mirrors the JS
     * `pointermove` handler exactly:
     * ```
     * delta = normalizeSigned(cur - lastAngle); rotation += delta; lastAngle = cur
     * ```
     * No easing/animation applied here — the caller re-renders immediately at the new [rotation].
     * No-ops (returns `this` unchanged) if not currently [dragging], so a stray move event after
     * release can't corrupt state.
     */
    fun onDrag(pointerAngle: Float): WheelState {
        if (!dragging) return this
        val delta = normalizeSigned(pointerAngle - lastPointerAngle)
        return copy(rotation = rotation + delta, lastPointerAngle = pointerAngle)
    }

    /**
     * Pointer-up/cancel: click-stop/detent snap to the nearest 60° multiple — never leaves the
     * wheel at an arbitrary angle (spec §3, "physics/precision decision for a moving vehicle, not
     * just aesthetic"). Mirrors the JS `snapAndSelect()`:
     * ```
     * norm = normalizeUnsigned(rotation)
     * nearest = round(norm/60) mod 6
     * delta = normalizeSigned(nearest*60 - norm)
     * rotation += delta
     * selectedIndex = (6 - nearest) mod 6   // NOT a typo — see class doc / WheelGeometry
     * ```
     * This method does not itself animate anything — it is pure arithmetic. It returns a
     * [ReleaseResult] describing the animation the caller ([WheelGesture.kt]'s
     * `Animatable`-driving code) should run, plus the [WheelState] to settle into once that
     * animation completes (`dragging=false`, final [rotation]/[selectedIndex] already applied —
     * safe to adopt immediately; only the *visual* rotation should lag behind via the
     * `Animatable`, not this logical state).
     *
     * No-ops (returns a [ReleaseResult] that changes nothing) if not currently [dragging].
     */
    fun onRelease(): ReleaseResult {
        if (!dragging) {
            return ReleaseResult(
                startRotation = rotation,
                targetRotation = rotation,
                selectedIndex = selectedIndex,
                settledState = this,
            )
        }
        val norm = normalizeUnsigned(rotation)
        val nearestSlot = Math.round(norm / STEP_DEGREES) % SLOT_COUNT
        val slotTargetAngle = nearestSlot * STEP_DEGREES
        val snapDelta = normalizeSigned(slotTargetAngle - norm)
        val targetRotation = rotation + snapDelta
        // selectedIndex = (6 - nearestSlot) mod 6 — see class doc for why this is correct, not
        // a typo: the fixed visual selector reference is the wheel's own un-rotated 0° point;
        // rotating the ring clockwise by nearestSlot*60° is what brings slot
        // (6-nearestSlot) mod 6 into that fixed point.
        val newSelectedIndex = (SLOT_COUNT - nearestSlot) % SLOT_COUNT
        return ReleaseResult(
            startRotation = rotation,
            targetRotation = targetRotation,
            selectedIndex = newSelectedIndex,
            settledState = copy(
                rotation = targetRotation,
                selectedIndex = newSelectedIndex,
                dragging = false,
            ),
        )
    }
}

/**
 * What [WheelState.onRelease] computed: the caller should animate visual rotation from
 * [startRotation] to [targetRotation] (over ~450-500ms, back-out ease — see
 * [WheelGeometry.SNAP_DURATION_MS]/`WheelSnapEasing` in WheelGesture.kt), then once that
 * animation's completion callback fires (not a fixed delay — see spec §4/§11), swap displayed
 * content to [selectedIndex] and adopt [settledState].
 */
data class ReleaseResult(
    val startRotation: Float,
    val targetRotation: Float,
    val selectedIndex: Int,
    val settledState: WheelState,
)
