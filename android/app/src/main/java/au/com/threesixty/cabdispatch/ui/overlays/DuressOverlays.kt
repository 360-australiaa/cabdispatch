package au.com.threesixty.cabdispatch.ui.overlays

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.domain.DuressController
import au.com.threesixty.cabdispatch.ui.theme.WheelColors

/**
 * Invisible 56dp triple-tap hit-target for the hidden duress gesture (spec §2: "active
 * throughout" / §6 step 8: "never shown as a visible control, only documented/annotated for the
 * design team"). Shared composable rather than each screen re-implementing its own tap-timing
 * logic — [au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen] (where this gesture was
 * first wired) and [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen] both
 * use this exact composable, each supplying its own [onTriggered] (typically
 * `AppContainer.duressController.trigger(vehicleId, driverId)` — see [DuressController]).
 */
@Composable
fun HiddenDuressGestureZone(onTriggered: () -> Unit, modifier: Modifier = Modifier) {
    var tapTimestamps by remember { mutableStateOf(listOf<Long>()) }
    Box(
        modifier = modifier
            .size(56.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    val now = System.currentTimeMillis()
                    val recent = (tapTimestamps + now).filter { now - it < 800 }
                    if (recent.size >= 3) {
                        tapTimestamps = emptyList()
                        onTriggered()
                    } else {
                        tapTimestamps = recent
                    }
                })
            },
    )
}

/**
 * Row 29 — "Duress triggered" contextual overlay (spec §8: "a brief full-screen confirmation
 * state shown right after the ... gesture fires, before it escalates"). Full-bleed, deliberately
 * alarming (solid [WheelColors.duress] — see that token's doc: distinct from [WheelColors.meterLedRed],
 * never conflate the two) so a driver who triple-tapped by accident notices immediately and has
 * an unmissable [onCancel] target. [secondsRemaining] mirrors
 * [DuressController.CANCEL_WINDOW_SECONDS] counting down — once it would reach 0 the caller
 * transitions to [DuressActiveBanner] instead (see [au.com.threesixty.cabdispatch.domain.DuressUiState]).
 *
 * Deliberately has NO other affordance (no back button, no dismiss-by-tapping-outside) — the only
 * way out of this screen is an explicit Cancel tap, matching a real panic-button confirmation UX
 * (accidental dismissal here would be worse than accidental non-dismissal).
 */
@Composable
fun DuressTriggeredOverlay(secondsRemaining: Int, onCancel: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "duress-pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "duress-pulse-alpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WheelColors.duress),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "⚠",
                fontSize = 56.sp,
                color = Color.White.copy(alpha = pulseAlpha),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "DURESS ALERT",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Sending your location to dispatch in $secondsRemaining…",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(28.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 40.dp, vertical = 18.dp),
            ) {
                Text(
                    "CANCEL — THIS WAS A MISTAKE",
                    color = WheelColors.duress,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
}

/**
 * Row 30 — "Duress active" contextual overlay (spec §8: "an ongoing-state indicator/banner while
 * a duress event is open"). Unlike [DuressTriggeredOverlay] this does NOT take over the screen —
 * per the backend's role policy (`escalate`/`close` are dispatcher-only, see `ApiService`'s
 * duress endpoints doc), a driver cannot self-resolve past this point, so the meter/dashboard
 * underneath must stay fully usable; this is a persistent, low-profile, un-dismissable banner, not
 * a modal. Styled with [WheelColors.duress] per spec — explicitly NOT [WheelColors.meterLedRed],
 * which is reserved for the fare-meter LED digits only (see that token's doc, do not conflate).
 */
@Composable
fun DuressActiveBanner(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "duress-active-pulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "duress-active-dot-alpha",
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(WheelColors.duress)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = dotAlpha)),
        )
        Text(
            "DURESS ACTIVE — dispatch notified",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
