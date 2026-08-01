package au.com.threesixty.cabdispatch.ui.wheel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.awaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import au.com.threesixty.cabdispatch.domain.SpeedSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.atan2

/**
 * Back-out ease with slight overshoot, `cubic-bezier(.34, 1.56, .64, 1)` per spec §3 — the direct
 * Compose equivalent of the View-system `PathInterpolator(0.34f, 1.56f, 0.64f, 1f)` the task
 * brief names (Compose has no `PathInterpolator`; `CubicBezierEasing` with the same 4 control
 * points is the documented equivalent for pure-Compose animation).
 */
val WheelSnapEasing: CubicBezierEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/**
 * Owns one wheel's live interaction state: the logical [WheelState] (selection/drag bookkeeping)
 * plus the [Animatable] that actually drives the on-screen rotation, including the settle
 * animation on release. Construct via [rememberWheelController] — the no-arg constructor is
 * `internal` mainly so callers don't forget to supply a real [CoroutineScope] to animate on.
 *
 * Read [rotationDegrees] every frame to position/rotate slots (see [WheelGeometry]); read
 * [state] for [WheelState.selectedIndex]/[WheelState.dragging]. [enabled] is the speed-lock gate
 * (spec §3: "wheel becomes non-interactive ... when vehicle speed ≥ 26 km/h") — set it directly,
 * or pass a [SpeedSource] to [rememberWheelController] to have it managed automatically.
 */
@Stable
class WheelController internal constructor(
    private val scope: CoroutineScope,
    initialState: WheelState = WheelState(),
) {
    var state: WheelState by mutableStateOf(initialState)
        private set

    /** The animated visual rotation. During a drag this tracks 1:1 with [state]'s snapped-to
     * value (no animation); on release it eases from the drag-end value to the snapped target. */
    val rotationAnimatable = Animatable(initialState.rotation)

    val rotationDegrees: Float get() = rotationAnimatable.value

    /** Speed-lock gate — false disables pointer input entirely (spec §3). Screens should also
     * grey the wheel out visually when this is false; that's a rendering concern for the
     * screen-owning sibling agent, not this class. */
    var enabled: Boolean by mutableStateOf(true)

    /** Wheel's own center, in the *local* coordinate space of whatever draws the wheel (i.e. the
     * same node [Modifier.wheelGesture] is attached to) — set via [onSizeChanged], assumes the
     * wheel is drawn filling that node's full bounds (a square [WheelGeometry.WHEEL_DIAMETER_DP]
     * box), matching the reference prototype's `wheelWrap`/`wheel` being coincident. */
    private var center: Offset = Offset.Zero

    internal fun onSizeChanged(size: IntSize) {
        center = Offset(size.width / 2f, size.height / 2f)
    }

    /** `atan2(dy, dx)` in degrees, screen-space (0°=+x, clockwise-positive since Compose's y axis
     * already points down) — exactly the JS reference's `angleFromCenter`. */
    internal fun angleFromCenter(pointerLocal: Offset): Float {
        val dx = pointerLocal.x - center.x
        val dy = pointerLocal.y - center.y
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    internal suspend fun handleDragStart(pointerLocal: Offset) {
        if (!enabled) return
        state = state.onDragStart(angleFromCenter(pointerLocal))
    }

    internal suspend fun handleDrag(pointerLocal: Offset) {
        if (!enabled || !state.dragging) return
        state = state.onDrag(angleFromCenter(pointerLocal))
        rotationAnimatable.snapTo(state.rotation) // no animation while dragging, per spec §3
    }

    internal fun handleRelease(onSettled: (Int) -> Unit) {
        if (!state.dragging) return
        animateToSnap(onSettled)
    }

    /**
     * Speed crossed the lock threshold mid-drag: snap the logical+visual state to rest
     * immediately (still animated, so it doesn't look like a glitch) without waiting for a
     * pointer-up that may never come now that input is disabled.
     */
    fun forceReleaseIfDragging() {
        if (state.dragging) animateToSnap(onSettled = {})
    }

    private fun animateToSnap(onSettled: (Int) -> Unit) {
        val release = state.onRelease()
        state = release.settledState
        scope.launch {
            rotationAnimatable.animateTo(
                targetValue = release.targetRotation,
                animationSpec = tween(
                    durationMillis = WheelGeometry.SNAP_DURATION_MS,
                    easing = WheelSnapEasing,
                ),
            )
            // Real animation-end callback, not a fixed delay (spec §11 calls out the JS
            // reference's flat 420ms timeout as an approximation only) — content swap fires
            // exactly when the snap settles.
            onSettled(release.selectedIndex)
        }
    }
}

/**
 * Creates and remembers a [WheelController]. If [speedSource] is supplied, [WheelController.enabled]
 * is kept in lock-step with the speed-lock rule automatically (spec §3) — pass `null` (default) if
 * the screen wants to manage [WheelController.enabled] itself.
 */
@Composable
fun rememberWheelController(
    speedSource: SpeedSource? = null,
    speedLockThresholdKmh: Double = WheelGeometry.SPEED_LOCK_THRESHOLD_KMH,
): WheelController {
    val scope = rememberCoroutineScope()
    val controller = remember { WheelController(scope) }
    if (speedSource != null) {
        LaunchedEffect(speedSource, speedLockThresholdKmh) {
            speedSource.speedKmh.collectLatest { speed ->
                val locked = speed >= speedLockThresholdKmh
                controller.enabled = !locked
                if (locked) controller.forceReleaseIfDragging()
            }
        }
    }
    return controller
}

/**
 * Drag-gesture modifier for the wheel. Attach to the same composable node that draws the wheel
 * (a square box sized [WheelGeometry.WHEEL_DIAMETER_DP]dp) — coordinates are read in that node's
 * local space, matched against [WheelController]'s notion of "center" via [onSizeChanged].
 *
 * Implemented as a raw `awaitPointerEventScope` loop rather than
 * `detectDragGestures`/`detectTapGestures` so there is **zero touch-slop threshold**: rotation
 * begins from the exact pointer-down angle, matching the JS reference's `pointerdown` handler
 * (`detectDragGestures` would introduce a small "must move N dp before a drag is recognized"
 * delay that the ground-truth spec doesn't have — "free rotation from anywhere on the ring" was
 * called out as an explicit correction over a worse first attempt, so this is deliberate, not an
 * oversight).
 *
 * [onSelectedIndexSettled] fires once per release, exactly when the snap animation's completion
 * callback runs (see [WheelController.handleRelease]) — content swap should key off this, not a
 * fixed delay (spec §4/§11).
 */
fun Modifier.wheelGesture(
    controller: WheelController,
    onSelectedIndexSettled: (Int) -> Unit = {},
): Modifier = this
    .onSizeChanged { controller.onSizeChanged(it) }
    .pointerInput(controller) {
        wheelPointerLoop(controller, onSelectedIndexSettled)
    }

private suspend fun PointerInputScope.wheelPointerLoop(
    controller: WheelController,
    onSelectedIndexSettled: (Int) -> Unit,
) {
    while (true) {
        awaitPointerEventScope {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!controller.enabled) return@awaitPointerEventScope
            val pointerId = down.id
            controller.handleDragStart(down.position)
            down.consume()
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (!change.pressed) {
                    change.consume()
                    controller.handleRelease(onSelectedIndexSettled)
                    break
                }
                controller.handleDrag(change.position)
                change.consume()
            }
        }
    }
}
