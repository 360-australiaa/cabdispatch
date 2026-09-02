package au.com.threesixty.cabdispatch.ui.screens.hired

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.domain.DuressUiState
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.TollPreset
import au.com.threesixty.cabdispatch.domain.TollPresets
import au.com.threesixty.cabdispatch.domain.TripStatus
import au.com.threesixty.cabdispatch.domain.toMoneyString
import au.com.threesixty.cabdispatch.ui.deck.rememberDeckClock
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.overlays.DuressActiveBanner
import au.com.threesixty.cabdispatch.ui.overlays.DuressTriggeredOverlay
import au.com.threesixty.cabdispatch.ui.overlays.HiddenDuressGestureZone
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainChip
import au.com.threesixty.cabdispatch.ui.theme.CaptainDialogScrim
import au.com.threesixty.cabdispatch.ui.theme.CaptainKeypad
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.DriverAvatar
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 18/18b · Hired — Meter, Command Deck v2 port (Figma `h0PSsXQ971dOJvt25tN7BA` frames `18:208`
 * HIRED and `19:208` STOPPED·WAITING). Full-screen takeover — deliberately no nav rail/drive
 * panel; the meter owns the whole 1280×800 while a fare is accruing.
 *
 * All metering logic is untouched [HiredViewModel]: live [FareState] ticks, pause/resume,
 * addToll persistence, endTrip → Close & Pay, duress state machine (hidden gesture + overlays).
 * The 18b "stopped" variant is this same layout with the state-driven swaps the two frames
 * differ by: warning pill/top-border, warning fare numerals, WAITING column highlighted, and the
 * wait button flipping to RESUME. The frame's "✚ EXTRAS" opens an honest notice — the live
 * engine has no extras input (extras exist only in the close-time fare reconstruction), so the
 * button explains that instead of faking a charge. Toll chips carry the app's REAL preset
 * amounts ([TollPresets]), not the frame's illustrative figures; "+ TOLL…" opens a custom-amount
 * pad. The previous version's cosmetic $0→flagfall ramp was dropped in this port (the METER
 * STARTED banner stays); fare numerals now bind the engine total directly.
 *
 * 2026-08-29 Captain Taxis repaint: migrated off the yellow/black [Deck] palette onto
 * [CaptainPalette] (this app's other screens still use [Deck] — see that object's own doc for why
 * this is deliberately scoped, not a global reskin) and closed this screen's biggest UX gap —
 * zero animation anywhere and several small, hard-to-tap targets for an elderly driver base.
 * Every `viewModel.*` call, `fareState.*`/`duressState` read, and the hidden duress gesture zone's
 * geometry are unchanged; only presentation moved.
 */
@Composable
fun HiredScreen(
    navController: NavHostController,
    viewModel: HiredViewModel = viewModel(),
) {
    val fareState by viewModel.fareState.collectAsState()
    val speechEnabled by viewModel.speechEnabled.collectAsState()
    val duressState by viewModel.duressState.collectAsState()
    val isPaused = fareState.status == TripStatus.STOPPED

    var showStartedBanner by remember { mutableStateOf(false) }
    var showTollPad by remember { mutableStateOf(false) }
    var showExtrasNote by remember { mutableStateOf(false) }
    // Point to Point Transport (Fares) Order 2026 UI-wiring pass: mid-trip passenger-count
    // correction — see HiredViewModel.updatePassengerCount's doc.
    var showPassengerEdit by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.isNewTripStart) {
        if (viewModel.isNewTripStart) {
            showStartedBanner = true
            kotlinx.coroutines.delay(2000)
            showStartedBanner = false
        }
    }

    // Animated instead of snapping (premium pass): HIRED↔STOPPED now cross-fades the pill/border
    // color over 400ms rather than hard-cutting.
    val stateColor by animateColorAsState(
        targetValue = if (isPaused) CaptainPalette.warning else CaptainPalette.success,
        animationSpec = tween(400),
        label = "state-color",
    )

    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // --- topBar (54dp, state-colored bottom border) ---
            Column {
                Row(
                    // 54dp -> 66dp (premium pass): grown just enough to carry the driver's real
                    // photo + name — passengers watch THIS screen for the whole trip, so this is
                    // where face-matching actually happens, not just the Home header.
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(66.dp)
                        .background(CaptainPalette.panel)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val session = SessionHolder.session.collectAsState().value
                    DriverAvatar(driverId = session?.driverId, driverName = session?.driverName, onClick = {}, sizeDp = 46)
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            session?.driverName ?: "—",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            color = CaptainPalette.textPrimary,
                        )
                        Text(
                            rememberDeckClock(),
                            fontFamily = RobotoMonoFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            color = CaptainPalette.textSecondary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(stateColor)
                            .padding(horizontal = 26.dp, vertical = 8.dp),
                    ) {
                        Text(
                            if (isPaused) "STOPPED · WAITING" else "HIRED",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            letterSpacing = 3.sp,
                            color = CaptainPalette.bg,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            "${fareState.band.label.uppercase()} — ${fareState.timeClass.label.uppercase()} · SIGNED ✓",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = CaptainPalette.warning,
                        )
                        val gpsOk = AppContainer.speedSource.locationFix.collectAsState().value != null
                        Text(
                            "GPS ●",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (gpsOk) CaptainPalette.success else CaptainPalette.danger,
                        )
                        // 15sp emoji glyph on a bare clickable Text -> real ≥56dp circular icon
                        // button (Icons.Filled.VolumeUp/VolumeOff) — the old target was well under
                        // Android's 48dp minimum and unreadable at a glance for an older driver.
                        SpeechToggleButton(enabled = speechEnabled, onToggle = { viewModel.toggleSpeech(!speechEnabled) })
                    }
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(stateColor))
            }

            // --- Maxi rate / wheelchair-hiring indicators (Point to Point Transport (Fares)
            // Order 2026 UI-wiring pass). Read ONLY [fareState.maxiRateApplied] — the pure fare
            // engine's own derived flag, copied through by FareEngineImpl — never recomputed here
            // from isMaxiVehicle/passengerCount/wheelchairHiring directly, so this banner can never
            // drift from what is actually being charged. Unmissable per the brief: a full-width,
            // high-contrast banner, not a small chip easy to miss.
            AnimatedVisibility(visible = fareState.maxiRateApplied, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CaptainPalette.warning)
                        .padding(horizontal = 32.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "⚠  MAXI RATE ×1.5 ACTIVE",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.bg,
                    )
                }
            }
            // Informational only (NSW Reg cl 82) — never changes actual meter start/stop
            // mechanics, which this pass does not touch.
            AnimatedVisibility(visible = fareState.wheelchairHiring, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CaptainPalette.panel)
                        .padding(horizontal = 32.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "♿  Wheelchair hiring — meter should start once the passenger is safely secured, per NSW Reg cl 82. Ordinary (non-maxi) rate applies.",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = CaptainPalette.textSecondary,
                    )
                }
            }

            // --- meterWell ---
            Box(
                modifier = Modifier
                    .padding(horizontal = 64.dp)
                    .padding(top = 26.dp)
                    .fillMaxWidth()
                    .height(400.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(CaptainPalette.inset)
                    .border(1.5.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp)),
            ) {
                // Ambient state-driven glow — same Canvas/drawArc rotating-sweep + radial-gradient
                // technique as DeckHomeScreen's MeterDial, adapted to this well's rounded-rect
                // shape: brighter and gently rotating while a fare is actively accruing (HIRED),
                // dimmed and slowed to a near-still crawl while STOPPED·WAITING — a genuine
                // at-a-glance state cue that costs nothing computed client-side (purely visual).
                MeterWellGlow(active = !isPaused, modifier = Modifier.fillMaxSize())

                Text(
                    "F A R E",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = 6.sp,
                    color = CaptainPalette.textMuted,
                    modifier = Modifier.padding(start = 38.dp, top = 26.dp),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 20.dp, end = 34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CaptainPalette.warning.copy(alpha = 0.12f))
                        .border(1.dp, CaptainPalette.warning.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (fareState.band.label.endsWith("2")) "T2" else "T1",
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Medium,
                        fontSize = 20.sp,
                        color = CaptainPalette.warning,
                    )
                }

                // Small, deliberately unobtrusive tap-to-edit affordance (Point to Point Transport
                // (Fares) Order 2026 UI-wiring pass) — miscounts happen; this is a correction path,
                // not a prominent control, so it sits below the T1/T2 badge rather than competing
                // with the fare numerals for attention.
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 66.dp, end = 34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showPassengerEdit = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "PAX ${fareState.passengerCount}  ✎",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = CaptainPalette.textMuted,
                    )
                }

                // Fare-tick pulse: this screen previously had ZERO animation, so every fare
                // increment landed silently. Each time `fareState.total` changes, the numerals
                // flash to success-green and settle back to textPrimary over ~280ms — a live,
                // readable "the meter just moved" cue without ever re-deriving the amount itself.
                var lastTotal by remember { mutableStateOf(fareState.total) }
                var justTicked by remember { mutableStateOf(false) }
                LaunchedEffect(fareState.total) {
                    if (fareState.total != lastTotal) {
                        lastTotal = fareState.total
                        justTicked = true
                        kotlinx.coroutines.delay(220)
                        justTicked = false
                    }
                }
                // Snap to success the instant the total ticks (tween(0)), then ease back to
                // textPrimary over ~280ms once `justTicked` drops — the flash the task calls for,
                // without a client-side Animatable<Color> (avoids this Compose BOM's ambiguous
                // single-arg Color factory overload).
                val flashColor by animateColorAsState(
                    targetValue = if (justTicked) CaptainPalette.success else CaptainPalette.textPrimary,
                    animationSpec = tween(if (justTicked) 0 else 280),
                    label = "fare-flash",
                )
                // Scale pulse pairs with the color flash: numerals swell 3% on each tick and
                // relax back — the "odometer breath" that makes an increment feel physical.
                val tickScale by animateFloatAsState(
                    targetValue = if (justTicked) 1.03f else 1f,
                    animationSpec = tween(if (justTicked) 60 else 320, easing = FastOutSlowInEasing),
                    label = "fare-scale",
                )
                val totalText = fareState.total.toMoneyString()
                Text(
                    totalText,
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (totalText.length > 7) 128.sp else 158.sp,
                    color = flashColor,
                    modifier = Modifier.align(Alignment.Center).padding(bottom = 30.dp).scale(tickScale),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 40.dp, vertical = 24.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val movingMin = fareState.movingSeconds / 60
                    val movingSec = fareState.movingSeconds % 60
                    val waitMin = fareState.waitingSeconds / 60
                    val waitSec = fareState.waitingSeconds % 60
                    MeterDatum("DISTANCE", fareState.distanceKm.setScale(1, RoundingMode.HALF_UP).toPlainString() + " KM")
                    MeterDatum("TIME", "%d:%02d".format(movingMin, movingSec))
                    MeterDatum("WAITING", "%d:%02d".format(waitMin, waitSec), highlight = isPaused)
                    MeterDatum("SPEED", "${fareState.currentSpeedKmh.toInt()} km/h")
                }
            }

            // --- chargesRow ---
            Row(
                modifier = Modifier.padding(start = 64.dp, top = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CaptainChip("EXTRAS", fareState.breakdown.extras.toMoneyString())
                CaptainChip("LEVY (PSL)", fareState.breakdown.psl.toMoneyString())
                CaptainChip("TOLLS", fareState.breakdown.tolls.toMoneyString())
                Box(Modifier.width(2.dp).height(36.dp).background(CaptainPalette.panelBorder))
                CaptainChip("+ ${TollPresets.M5.label.uppercase()}", TollPresets.M5.amount.toMoneyString()) {
                    viewModel.addToll(TollPresets.M5)
                }
                CaptainChip("+ HARBOUR", TollPresets.HARBOUR_SOUTHBOUND.amount.toMoneyString()) {
                    viewModel.addToll(TollPresets.HARBOUR_SOUTHBOUND)
                }
                CaptainChip("+ ${TollPresets.AIRPORT.label.uppercase()}", TollPresets.AIRPORT.amount.toMoneyString()) {
                    viewModel.addToll(TollPresets.AIRPORT)
                }
                CaptainChip("+ TOLL…", "") { showTollPad = true }
            }

            Spacer(Modifier.weight(1f))

            // --- controls ---
            Row(
                modifier = Modifier.padding(horizontal = 64.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .height(92.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isPaused) CaptainPalette.success else CaptainPalette.warning)
                        .clickable { viewModel.togglePause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null,
                            tint = CaptainPalette.bg,
                            modifier = Modifier.size(26.dp),
                        )
                        Text(
                            if (isPaused) "RESUME — METERED" else "STOP — WAITING",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 21.sp,
                            color = CaptainPalette.bg,
                        )
                    }
                }
                CaptainButton(
                    text = "EXTRAS",
                    outline = true,
                    heightDp = 92,
                    fontSize = 21.sp,
                    widthDp = 220,
                ) { showExtrasNote = true }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(92.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.horizontalGradient(listOf(CaptainPalette.primary, CaptainPalette.accent)))
                        .clickable {
                            viewModel.endTrip { navController.navigate(CabDispatchRoutes.CLOSE_PAY) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "END TRIP — CLOSE & PAY",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp,
                        color = CaptainPalette.textPrimary,
                    )
                }
            }
            Text(
                "One of distance or waiting accrues at a time — switches automatically at 26 km/h",
                fontFamily = InterFamily,
                fontSize = 13.sp,
                color = CaptainPalette.textMuted,
                modifier = Modifier.padding(start = 64.dp, top = 12.dp, bottom = 14.dp),
            )
        }

        AnimatedVisibility(
            visible = showStartedBanner,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 66.dp),
            enter = fadeIn(),
            exit = fadeOut(animationSpec = tween(400)),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(CaptainPalette.success)
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            ) {
                Text(
                    "● METER STARTED",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = CaptainPalette.bg,
                )
            }
        }

        // Mid-trip passenger-count correction (Point to Point Transport (Fares) Order 2026
        // UI-wiring pass) — see HiredViewModel.updatePassengerCount's doc.
        CaptainDialogScrim(visible = showPassengerEdit, onDismissRequest = { showPassengerEdit = false }) {
            PassengerEditDialog(
                initialCount = fareState.passengerCount,
                onDismiss = { showPassengerEdit = false },
                onConfirm = { count ->
                    showPassengerEdit = false
                    viewModel.updatePassengerCount(count)
                },
            )
        }

        // Custom toll amount pad
        CaptainDialogScrim(visible = showTollPad, onDismissRequest = { showTollPad = false }) {
            CustomTollDialog(
                onDismiss = { showTollPad = false },
                onConfirm = { amount ->
                    showTollPad = false
                    viewModel.addToll(TollPreset("custom", "Custom toll", amount))
                },
            )
        }
        CaptainDialogScrim(visible = showExtrasNote, onDismissRequest = { showExtrasNote = false }) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(CaptainPalette.panel)
                    .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Extras", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
                Text(
                    "No chargeable extras are configured for this fleet yet — extras (e.g. cleaning fee) " +
                        "are applied at Close & Pay where they exist. Tolls have their own chips above.",
                    fontFamily = InterFamily,
                    fontSize = 15.sp,
                    color = CaptainPalette.textSecondary,
                )
                CaptainButton(text = "OK", outline = true, widthDp = 180) {
                    showExtrasNote = false
                }
            }
        }

        HiddenDuressGestureZone(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 12.dp),
            onTriggered = viewModel::onDuressTriggered,
        )
        when (val d = duressState) {
            is DuressUiState.Active -> DuressActiveBanner(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 10.dp),
            )
            is DuressUiState.Triggered -> DuressTriggeredOverlay(
                secondsRemaining = d.secondsRemaining,
                onCancel = viewModel::cancelDuress,
            )
            DuressUiState.Idle -> Unit
        }
    }
}

/**
 * Real ≥56dp circular icon button replacing the previous bare-emoji `Text.clickable` (a real
 * small-touch-target accessibility problem for an elderly driver base) — same
 * `toggleSpeech(!speechEnabled)` call site, just a legible Material icon and a proper hit area.
 */
@Composable
private fun SpeechToggleButton(enabled: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (enabled) CaptainPalette.raised else CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (enabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
            contentDescription = if (enabled) "Speech announcements on" else "Speech announcements off",
            tint = if (enabled) CaptainPalette.accent else CaptainPalette.textMuted,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * Ambient glow behind the fare well — adapts DeckHomeScreen's `MeterDial` Canvas/drawArc technique
 * (radial-gradient blob + a rotating sweep highlight) to this well's rounded-RECT shape rather than
 * a circular dial: a soft accent-colored radial glow plus two bright elliptical arcs riding the
 * well's inscribed ellipse. [active] (mirrors `fareState.status == TripStatus.HIRED`) drives both
 * strength (dim to ~0.35 alpha while STOPPED·WAITING) and speed (rotation slows to a near-still
 * crawl rather than a full stop, so the well never looks frozen/broken). Drawn as the Box's first
 * child so it sits under the fare text and is clipped by the same parent's rounded-corner shape.
 */
@Composable
private fun MeterWellGlow(active: Boolean, modifier: Modifier = Modifier) {
    val sweepAngle by rememberInfiniteTransition(label = "meter-well-sweep").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (active) 6000 else 22000, easing = LinearEasing)),
        label = "meter-well-sweep-angle",
    )
    val glowStrength by animateFloatAsState(
        targetValue = if (active) 1f else 0.35f,
        animationSpec = tween(500),
        label = "meter-well-glow-strength",
    )
    val pulse by rememberInfiniteTransition(label = "meter-well-pulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "meter-well-pulse-v",
    )
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val maxR = kotlin.math.hypot(w, h) / 2.4f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(CaptainPalette.accent.copy(alpha = 0.16f * glowStrength * pulse), Color.Transparent),
                center = Offset(cx, cy),
                radius = maxR,
            ),
            radius = maxR,
            center = Offset(cx, cy),
        )
        val strokeW = 3.dp.toPx()
        val inset = strokeW * 1.5f
        val rectSize = Size(w - inset * 2, h - inset * 2)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = CaptainPalette.accent.copy(alpha = 0.5f * glowStrength),
            startAngle = sweepAngle,
            sweepAngle = 60f,
            useCenter = false,
            topLeft = topLeft,
            size = rectSize,
            style = Stroke(width = strokeW, cap = StrokeCap.Round),
        )
        drawArc(
            color = CaptainPalette.accent.copy(alpha = 0.5f * glowStrength),
            startAngle = sweepAngle + 180f,
            sweepAngle = 60f,
            useCenter = false,
            topLeft = topLeft,
            size = rectSize,
            style = Stroke(width = strokeW, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun MeterDatum(label: String, value: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CaptainPalette.textMuted)
        Text(
            value,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Medium,
            fontSize = 40.sp,
            color = if (highlight) CaptainPalette.success else CaptainPalette.warning,
        )
    }
}

/**
 * Mid-trip passenger-count correction dialog (Point to Point Transport (Fares) Order 2026
 * UI-wiring pass) — reached only via [HiredScreen]'s small "PAX n ✎" tap-to-edit affordance, never
 * a prominent control. Confirming calls [HiredViewModel.updatePassengerCount], which re-derives
 * [au.com.threesixty.cabdispatch.domain.FareState.maxiRateApplied] immediately for the remainder
 * of the trip without touching any already-accrued charge — see that method's own doc.
 */
@Composable
private fun PassengerEditDialog(initialCount: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var count by remember { mutableStateOf(initialCount) }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(30.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Correct passenger count", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
        Text(
            "5 or more passengers may trigger the maxi rate — only for a genuine maxi vehicle, and never for a wheelchair hiring.",
            fontFamily = InterFamily,
            fontSize = 14.sp,
            color = CaptainPalette.textSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(if (count > 1) CaptainPalette.raised else CaptainPalette.inset)
                    .border(1.dp, CaptainPalette.panelBorder, CircleShape)
                    .then(if (count > 1) Modifier.clickable { count -= 1 } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text("−", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = CaptainPalette.textPrimary)
            }
            Box(
                modifier = Modifier.width(96.dp).height(72.dp).clip(RoundedCornerShape(14.dp)).background(CaptainPalette.inset),
                contentAlignment = Alignment.Center,
            ) {
                Text(count.toString(), fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, color = CaptainPalette.textPrimary)
            }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(if (count < 11) CaptainPalette.raised else CaptainPalette.inset)
                    .border(1.dp, CaptainPalette.panelBorder, CircleShape)
                    .then(if (count < 11) Modifier.clickable { count += 1 } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = CaptainPalette.textPrimary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CaptainButton(text = "Cancel", outline = true, modifier = Modifier.weight(1f), onClick = onDismiss)
            CaptainButton(text = "Update", modifier = Modifier.weight(1.4f)) { onConfirm(count) }
        }
    }
}

@Composable
private fun CustomTollDialog(onDismiss: () -> Unit, onConfirm: (BigDecimal) -> Unit) {
    var cents by remember { mutableStateOf("") }
    val amount = if (cents.isEmpty()) BigDecimal.ZERO else BigDecimal(cents).movePointLeft(2)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Add toll", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
        Box(
            modifier = Modifier
                .width(448.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CaptainPalette.inset),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                amount.toMoneyString(),
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 38.sp,
                color = CaptainPalette.success,
            )
        }
        CaptainKeypad(
            onDigit = { d -> if (cents.length < 5) cents += d },
            onBackspace = { cents = cents.dropLast(1) },
            onClear = { cents = "" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CaptainButton(text = "Cancel", outline = true, modifier = Modifier.weight(1f), onClick = onDismiss)
            CaptainButton(
                text = "Add toll",
                enabled = amount > BigDecimal.ZERO,
                modifier = Modifier.weight(1.4f),
            ) { onConfirm(amount) }
        }
    }
}
