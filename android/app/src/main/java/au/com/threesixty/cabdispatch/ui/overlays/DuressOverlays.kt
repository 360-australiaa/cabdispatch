package au.com.threesixty.cabdispatch.ui.overlays

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.domain.DuressController
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * Invisible 56dp triple-tap hit-target for the hidden duress gesture (spec §2: "active
 * throughout" / §6 step 8: "never shown as a visible control, only documented/annotated for the
 * design team"). Shared composable rather than each screen re-implementing its own tap-timing
 * logic — [au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen] (where this gesture was
 * first wired) and [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen] both
 * use this exact composable, each supplying its own [onTriggered] (typically
 * `AppContainer.duressController.trigger(vehicleId, driverId)` — see [DuressController]).
 *
 * Untouched by the Command Deck v2 reskin below — this is behaviour, not visuals, and it has no
 * visuals at all.
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
 * Row 29 — "Duress triggered" contextual overlay, Command Deck v2 port (Figma
 * `h0PSsXQ971dOJvt25tN7BA` node `28:1466`, "33 · Duress — Triggered (arming)"). Visual layer
 * ONLY — [secondsRemaining]/[onCancel] and every trigger/countdown/cancel state-machine behaviour
 * driving them are 100% unchanged; see [DuressController] (untouched) for that logic.
 *
 * v2 layout, literal from the frame's overlay layer: a `rgba(5,7,12,0.8)` full-bleed dim over
 * whatever screen is underneath (the frame's meter backdrop belongs to the Hired screen, not this
 * overlay), then the 608dp-wide `armingCard` 140dp from the top — near-black red-tinted panel
 * (`#160B0B`), 2dp [Deck.hired] border, radius 24 — stacking the 110dp countdown ring (Chakra
 * SemiBold 56 numeral), "DURESS ALARM ARMING" (Inter Bold 28), the plain-language body, a
 * 520×88 white CANCEL — I AM SAFE bar, and the escalation footnote. The card is red
 * ([Deck.hired]) exactly as drawn in the frame; [Deck.duress] purple is reserved for frame 34's
 * stealth lamp ([DuressActiveBanner]). Every line of copy states something real: audio recording
 * does begin ([au.com.threesixty.cabdispatch.domain.duress.DuressAudioRecorder]), GPS relays on a
 * 5 s cadence ([DuressController]'s active phase), and Twilio voice escalation exists server-side
 * (`backend/app/services/duress.py`).
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
            .background(Color(0xCC05070C)), // rgba(5,7,12,0.8) — frame 28:1521 "dim"
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 140.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF160B0B))
                .border(2.dp, Deck.hired, RoundedCornerShape(24.dp))
                .padding(horizontal = 44.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(Deck.hired.copy(alpha = 0.12f))
                    .border(5.dp, Deck.hired.copy(alpha = pulseAlpha), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = secondsRemaining.toString(),
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 56.sp,
                    color = Deck.hired,
                )
            }
            Text(
                text = "DURESS ALARM ARMING",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = Deck.hired,
            )
            Text(
                text = "Silent alarm will be sent to the monitoring centre in $secondsRemaining seconds. " +
                    "Audio recording will begin. Cancel now if triggered accidentally.",
                fontFamily = InterFamily,
                fontSize = 16.sp,
                color = Deck.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(520.dp),
            )
            Box(
                modifier = Modifier
                    .width(520.dp)
                    .height(88.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Deck.textPrimary)
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "CANCEL — I AM SAFE",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = Deck.canvas,
                )
            }
            Text(
                text = "If not cancelled: GPS relays every 5 s · dispatch + monitoring notified · " +
                    "Twilio voice escalation on final stage",
                fontFamily = InterFamily,
                fontSize = 13.sp,
                color = Deck.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(520.dp),
            )
        }
    }
}

/**
 * Row 30 — "Duress active" contextual indicator, Command Deck v2 port (Figma
 * `h0PSsXQ971dOJvt25tN7BA` node `28:1530`, "34 · Duress — Active (stealth)"). The frame's whole
 * philosophy is that an ACTIVE duress screen is pixel-identical to the normal meter except for
 * one 10dp purple `stealthLamp` 8dp in from the bottom-right corner (frame node `28:1585`) — a
 * driver under duress with someone else able to see the screen must not have anything on-screen
 * give away that dispatch has been silently notified. So this renders ONLY that lamp: no banner,
 * no text, no box, nothing else.
 *
 * The public signature is unchanged (bare [modifier], no parameters — callers in
 * `HiredScreen`/`WheelDashboardScreen` pass their own top-aligned modifier from the pre-v2
 * banner era and are not edited by this pass), so the composable now fills its parent and pins
 * the lamp to the bottom-end corner itself, matching the frame's placement regardless of the
 * incoming alignment. The fill Box carries no pointer-input modifier, so it is not hit-testable —
 * the meter/dashboard underneath stays fully usable, exactly as before. Still no cancel
 * affordance by design, not by omission: past the arming window, resolution is
 * dispatcher/backend-only (see [DuressController.cancel]'s doc). Colored [Deck.duress] — the
 * one place the duress purple appears while stealth-active.
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

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 8.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(Deck.duress.copy(alpha = dotAlpha)),
        )
    }
}
