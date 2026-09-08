package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale

/**
 * The home screen's motion vocabulary (workstream A3, 2026-09-08).
 *
 * THE BRIEF, AND THE CONSTRAINT IT HAS TO SURVIVE. The owner asked for *"beautiful animations / UI
 * like a game interface"*. This repo has already rejected exactly one attempt at that: a travelling
 * highlight that continuously circled the speedometer ring, reverted immediately on direct
 * feedback — *"this circle is moving continuously, its doing pain in my head... calm animations"*
 * (see [au.com.threesixty.cabdispatch.ui.theme.GlowingSpeedometer]'s own `motion` doc, Hud.kt:437,
 * for the full incident). The program plan's operating rule 9 states the settlement: **nothing
 * animates continuously while the vehicle is parked; every per-frame delta is a function of speed
 * or a one-shot reaction to a state change.**
 *
 * Those two things are not in conflict, because what actually makes an interface feel like a game
 * is not ambience — it is **arrival, weight and reaction**. A game's HUD does not shimmer at you
 * while you stand still; it snaps into place when it appears and it punches when a number changes.
 * So the whole of this file is entrance choreography and one-shot value reactions. The operative
 * sentence, and the one to hold any future addition here against:
 *
 * > **Alive when something is happening, perfectly still when it isn't.**
 *
 * Both helpers below settle to a fixed state and then produce *zero* frames until real state
 * changes again. Neither uses `rememberInfiniteTransition`. There is no clock anywhere in this
 * file — grep it: no `withFrameNanos`, no `infiniteRepeatable`, no `delay` driving a visual.
 */

/**
 * Staggered entrance: fade + a short upward drift on a bouncy spring, delayed by [index].
 *
 * ONE-SHOT BY CONSTRUCTION. The [MutableTransitionState] is created with `false` and flipped to
 * `true` inside `remember`, so it runs exactly once per composition entry (pane entry, in
 * practice — the Dashboard pane's children are disposed when the driver switches panes and rebuilt
 * on return, which is precisely when a re-play is wanted). It has no exit transition and nothing
 * ever sets the target back to `false`: once the last child has landed the entire screen is
 * static, and stays static for the rest of the shift.
 *
 * THE BUDGET. [STAGGER_STEP_MS] × the highest index in use, plus the 240ms fade, is the total
 * settle time. At 45ms per step and indices 0..5 that is 225 + 240 ≈ **465ms** — under the half
 * second that reads as "the screen assembled itself" rather than "the screen is slow". Do not
 * raise the step without re-checking that total; a stagger that outlasts a driver's glance stops
 * being choreography and becomes waiting.
 *
 * The drift is `it / 12` — one twelfth of the child's own height, so a tall card and a short strip
 * travel proportionally rather than a fixed dp that would look like a lurch on the small one.
 */
@Composable
internal fun StaggerIn(index: Int, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(durationMillis = STAGGER_FADE_MS, delayMillis = index * STAGGER_STEP_MS)) +
            slideInVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            ) { fullHeight -> fullHeight / 12 },
    ) {
        content()
    }
}

private const val STAGGER_STEP_MS = 45

private const val STAGGER_FADE_MS = 240

/**
 * A one-shot scale "pop" every time [value] changes — the physics half of the brief.
 *
 * Returns a [Modifier] that springs up to [peak] and settles straight back to `1f`. The meter
 * screen already uses this shape for its fare tick and band change
 * (`ui/screens/hired/…`, per the audit's motion inventory); this is the same idea offered to the
 * dashboard's stat tiles so a completed trip *lands* on the trip counter instead of the digit
 * silently swapping.
 *
 * Keyed on the value, not on a timer: composing this with an unchanging [value] costs nothing and
 * animates nothing. The initial composition does not pop — [LaunchedEffect] on the *first* value
 * only records it, so a driver arriving at the screen does not see their existing trip count
 * pointlessly bounce. Only a genuine increment (or any later change) fires it.
 */
@Composable
internal fun rememberValueChangePop(value: Any?, peak: Float = 1.18f): Modifier {
    // An Animatable, not two assignments to an animateFloatAsState target: a "go to peak, then
    // come back" gesture has to *await* the outward leg before starting the return one. Setting a
    // target twice in a row within the same frame would simply coalesce to the final value and
    // animate nothing at all — the pop would silently never happen.
    val scale = remember { Animatable(1f) }
    // `first` guards the initial composition: the effect runs once for the value that was already
    // on screen when this composable appeared, and only *subsequent* keys are real changes worth
    // reacting to.
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(value) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        scale.animateTo(peak, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh))
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    return Modifier.scale(scale.value)
}
