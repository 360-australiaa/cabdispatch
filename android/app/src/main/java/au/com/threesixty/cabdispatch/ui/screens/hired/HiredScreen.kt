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
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.WbSunny
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.DuressUiState
import au.com.threesixty.cabdispatch.domain.FareBreakdown
import au.com.threesixty.cabdispatch.domain.FareState
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.TimeClass
import au.com.threesixty.cabdispatch.domain.TollPreset
import au.com.threesixty.cabdispatch.domain.TollPresets
import au.com.threesixty.cabdispatch.domain.TripContext
import au.com.threesixty.cabdispatch.domain.TripStatus
import au.com.threesixty.cabdispatch.domain.format.asLocalTime
import au.com.threesixty.cabdispatch.domain.toMoneyString
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.overlays.DuressActiveBanner
import au.com.threesixty.cabdispatch.ui.overlays.DuressTriggeredOverlay
import au.com.threesixty.cabdispatch.ui.overlays.HiddenDuressGestureZone
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainChip
import au.com.threesixty.cabdispatch.ui.theme.CaptainDialogScrim
import au.com.threesixty.cabdispatch.ui.theme.CaptainKeypad
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 18/18b · Hired — Meter. Phase A (2026-09-03) embedded this as
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s `CaptainPane.METER` pane
 * (shared header/footer/nav-rail — see that file's class doc and its `startOnMeter` param); this
 * composable owns only the content slot DeckHomeScreen hands it. The "game-level" visual pass
 * (same day) re-laid that slot out to match the reference mockup, purely presentationally:
 *
 * All metering logic is untouched [HiredViewModel]: live [FareState] ticks, pause/resume,
 * addToll persistence, endTrip → Close & Pay, duress state machine (hidden gesture + overlays) —
 * every `viewModel.*` call and `fareState.*`/`duressState` read below is the same call/read this
 * screen has always made; only the surrounding layout/art changed:
 *
 * - **Five-column layout** on the shell's fixed 1280×800 logical canvas (`MainActivity`'s
 *   `FixedDesignCanvas` — the SM-T575 is scaled onto it, so every dp below is authored against the
 *   ~992×420dp this pane actually receives under the shared header/footer/rail): NIGHT/DAY FARE
 *   tile + status ([NightFareTile]/[MeterStatusTile]) · the dial floating over a real Mapbox
 *   backdrop ([MeterBackdropMap] + [ActiveMeterDial]) · the SET PRICE/ADD TOLL/PAUSE FARE/MORE
 *   action tiles ([MeterActionStack]) · a scrolling far-right column with [FareBreakdownCard] and
 *   [TripDetailsCard]. Nothing but that far-right column scrolls — Phase A's left-column scroll
 *   (an on-device overflow fix) is gone because the dial no longer stacks readouts/actions/END
 *   TRIP beneath itself.
 * - **[ActiveMeterDial] is a speedometer**: the outer ring is a REAL 0–120 km/h scale whose arc
 *   sweeps to `fareState.currentSpeedKmh` (the engine's own speed, the same one it accrues
 *   distance against — not the mockup's decorative 0–350). Inside: the fare ring (bloom pulses on
 *   every fare tick), car icon, ACTIVE FARE, the fare figure with the tick flash/scale pop,
 *   RUNNING (glowing accent) / PAUSED (amber), TARIFF + EXTRAS, DISTANCE/TIME, and the **END FARE**
 *   button INSIDE the dial — the exact `viewModel.endTrip { navigate(CLOSE_PAY) }` call the old
 *   full-width END TRIP bar made.
 * - **[MeterBackdropMap]** — real map, real route (every vertex a real GPS fix), real pickup pin,
 *   NO destination pin (no real destination coordinates exist on [TripContext]) — see its doc.
 * - **[FareBreakdownCard]** keeps [HiredViewModel.breakdownExpanded]/`.toggleBreakdown()` as the
 *   HIDE/SHOW toggle; rows now carry the mockup's coloured dot bullets. **[TripDetailsCard]** is
 *   the vertical pickup→drop-off timeline + DISTANCE/DURATION/AVG SPEED/WAITING. Both still render
 *   an honest "—" wherever data is missing (see `TripContext.originAddress`'s doc).
 * - SET PRICE remains **read-only/informational** ([SetPriceInfoDialog]'s doc explains why); ADD
 *   TOLL/MORE open the same dialogs; PAUSE FARE calls `togglePause()` exactly.
 *
 * The hidden duress gesture zone's modifier (`align(Alignment.BottomEnd).padding(end = 12.dp,
 * bottom = 12.dp)`) and `onTriggered = viewModel::onDuressTriggered` call are reproduced verbatim
 * below — this pass does not move, resize, or reveal it (explicit user decision: no visible duress
 * button on this screen).
 */
@Composable
fun HiredScreen(
    navController: NavHostController,
    viewModel: HiredViewModel = viewModel(),
) {
    val fareState by viewModel.fareState.collectAsState()
    val speechEnabled by viewModel.speechEnabled.collectAsState()
    val duressState by viewModel.duressState.collectAsState()
    val breakdownExpanded by viewModel.breakdownExpanded.collectAsState()
    val isPaused = fareState.status == TripStatus.STOPPED

    // Best-effort read of the same hand-off payload HiredViewModel.init already reads once — see
    // TripContext.originAddress/.destAddress/.negotiatedTotal's docs. A screen-local read (same
    // "screen-local loader" convention DeckHomeScreen's HomeExtras/DriverAvatar already use), not a
    // new field added to HiredViewModel itself. Degrades to nulls (every dependent row below
    // already shows "—") if this VM instance somehow outlives the pendingTrip hand-off.
    val tripContext by SessionHolder.pendingTrip.collectAsState()
    // Real persisted trip row (Room, via the same observeActiveTrip Flow DeckHomeScreen's
    // hasActiveTrip read uses) — only for the Trip Details timeline's real pickup time
    // (TripEntity.startAt) — and its persisted GPS trace for the backdrop's route polyline.
    val activeTrip by AppContainer.tripRepository.observeActiveTrip().collectAsState(initial = null)
    val persistedTrace by AppContainer.tripRepository.observeActiveTripGpsTrace().collectAsState(initial = emptyList())
    val liveTrace = rememberLiveTrace()
    val liveFix by AppContainer.speedSource.locationFix.collectAsState()

    var showTollPad by remember { mutableStateOf(false) }
    var showTollMenu by remember { mutableStateOf(false) }
    var showExtrasNote by remember { mutableStateOf(false) }
    var showSetPriceInfo by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    // Point to Point Transport (Fares) Order 2026 UI-wiring pass: mid-trip passenger-count
    // correction — see HiredViewModel.updatePassengerCount's doc. Reached from the MORE sheet.
    var showPassengerEdit by remember { mutableStateOf(false) }

    var showStartedBanner by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel.isNewTripStart) {
        if (viewModel.isNewTripStart) {
            showStartedBanner = true
            kotlinx.coroutines.delay(2000)
            showStartedBanner = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // --- Maxi rate / wheelchair-hiring indicators (Point to Point Transport (Fares)
            // Order 2026 UI-wiring pass). Read ONLY [fareState.maxiRateApplied] — the pure fare
            // engine's own derived flag, copied through by FareEngineImpl — never recomputed here
            // from isMaxiVehicle/passengerCount/wheelchairHiring directly, so this banner can never
            // drift from what is actually being charged. Take vertical room only while visible.
            AnimatedVisibility(visible = fareState.maxiRateApplied, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CaptainPalette.warning)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "⚠  MAXI RATE ×1.5 ACTIVE",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.bg,
                    )
                }
            }
            AnimatedVisibility(visible = fareState.wheelchairHiring, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CaptainPalette.panel)
                        .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "♿  Wheelchair hiring — meter should start once the passenger is safely secured, per NSW Reg cl 82. Ordinary (non-maxi) rate applies.",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = CaptainPalette.textSecondary,
                    )
                }
            }

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // --- col 1: NIGHT/DAY FARE tile + compact status ---
                Column(modifier = Modifier.width(LEFT_COL_W).fillMaxHeight()) {
                    NightFareTile(timeClass = fareState.timeClass, tariff = tripContext?.tariff)
                    Spacer(Modifier.height(10.dp))
                    MeterStatusTile(fareState = fareState, gpsOk = liveFix != null, modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.width(COL_GAP))

                // --- col 2: the dial, floating over the real map backdrop ---
                Box(
                    modifier = Modifier
                        .width(MAP_COL_W)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .background(CaptainPalette.cardBottom)
                        .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    MeterBackdropMap(
                        startLat = tripContext?.startLat,
                        startLng = tripContext?.startLng,
                        persistedTrace = persistedTrace,
                        liveTrace = liveTrace,
                        liveFix = liveFix,
                        modifier = Modifier.fillMaxSize(),
                    )
                    ActiveMeterDial(
                        fareState = fareState,
                        isPaused = isPaused,
                        onEndFare = {
                            viewModel.endTrip { navController.navigate(CabDispatchRoutes.CLOSE_PAY) }
                        },
                    )
                }

                Spacer(Modifier.width(COL_GAP))

                // --- col 3: action tiles ---
                MeterActionStack(
                    isPaused = isPaused,
                    negotiatedTotal = tripContext?.negotiatedTotal,
                    tollsTotal = fareState.breakdown.tolls,
                    tollCount = fareState.tollsApplied.size,
                    onSetPrice = { showSetPriceInfo = true },
                    onAddToll = { showTollMenu = true },
                    onTogglePause = viewModel::togglePause,
                    onMore = { showMore = true },
                    modifier = Modifier.width(ACTION_COL_W).fillMaxHeight(),
                )

                Spacer(Modifier.width(COL_GAP))

                // --- col 4: Fare Breakdown + Trip Details (the one column allowed to scroll) ---
                Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    FareBreakdownCard(
                        breakdown = fareState.breakdown,
                        timeClass = fareState.timeClass,
                        nightMultiplierLabel = nightMultiplierLabel(tripContext?.tariff),
                        expanded = breakdownExpanded,
                        onToggle = viewModel::toggleBreakdown,
                    )
                    Spacer(Modifier.height(10.dp))
                    TripDetailsCard(
                        tripContext = tripContext,
                        fareState = fareState,
                        startAtIso = activeTrip?.startAt,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "One of distance or waiting accrues at a time — switches automatically at 26 km/h",
                        fontFamily = InterFamily,
                        fontSize = 10.sp,
                        color = CaptainPalette.textMuted,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showStartedBanner,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
            enter = fadeIn(),
            exit = fadeOut(animationSpec = tween(400)),
        ) {
            Box(
                modifier = Modifier
                    .neonGlow(CaptainPalette.success, 99.dp, strength = 0.8f)
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

        CaptainDialogScrim(visible = showTollMenu, onDismissRequest = { showTollMenu = false }) {
            TollPresetDialog(
                tollsTotal = fareState.breakdown.tolls,
                onDismiss = { showTollMenu = false },
                onAddPreset = { preset ->
                    showTollMenu = false
                    viewModel.addToll(preset)
                },
                onCustom = {
                    showTollMenu = false
                    showTollPad = true
                },
            )
        }
        CaptainDialogScrim(visible = showTollPad, onDismissRequest = { showTollPad = false }) {
            CustomTollDialog(
                onDismiss = { showTollPad = false },
                onConfirm = { amount ->
                    showTollPad = false
                    viewModel.addToll(TollPreset("custom", "Custom toll", amount))
                },
            )
        }
        CaptainDialogScrim(visible = showSetPriceInfo, onDismissRequest = { showSetPriceInfo = false }) {
            SetPriceInfoDialog(negotiatedTotal = tripContext?.negotiatedTotal, onDismiss = { showSetPriceInfo = false })
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
                        "are applied at Close & Pay where they exist. Tolls have their own ADD TOLL button.",
                    fontFamily = InterFamily,
                    fontSize = 15.sp,
                    color = CaptainPalette.textSecondary,
                )
                CaptainButton(text = "OK", outline = true, widthDp = 180) {
                    showExtrasNote = false
                }
            }
        }
        CaptainDialogScrim(visible = showMore, onDismissRequest = { showMore = false }) {
            MoreActionsSheet(
                speechEnabled = speechEnabled,
                passengerCount = fareState.passengerCount,
                onToggleSpeech = { viewModel.toggleSpeech(it) },
                onEditPassengers = {
                    showMore = false
                    showPassengerEdit = true
                },
                onExtras = {
                    showMore = false
                    showExtrasNote = true
                },
                onDismiss = { showMore = false },
            )
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

// Column widths, authored against the ~992dp this pane receives on the 1280×800 logical canvas
// (see HiredScreen's class doc). LEFT + MAP + ACTION + 3 gaps = 692dp, leaving ~300dp for the
// far-right cards column (weight(1f)).
private val LEFT_COL_W = 130.dp
private val MAP_COL_W = 380.dp
private val ACTION_COL_W = 146.dp
private val COL_GAP = 12.dp

/** Dial diameter (the ring's outer edge). The glow art is drawn on a larger canvas around it. */
private val DIAL_SIZE = 360.dp
private val DIAL_ART_SIZE = 470.dp

/** Real night-rate uplift, not a fabricated multiplier — same ratio-of-signed-tariff computation
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s `NightFareTile` uses (that
 * one is `private` to a different file, so this is a small, deliberate duplicate of the same
 * formula rather than a cross-file reach-around). `null` tariff (no pending-trip hand-off to read
 * it from) hides the ratio rather than showing a bogus one. */
private fun nightMultiplierLabel(tariff: TariffDto?): String? {
    val t = tariff ?: return null
    val day = t.distRate1.toBigDecimalOrNull() ?: return null
    val night = t.nightRate1.toBigDecimalOrNull() ?: return null
    if (day.signum() <= 0) return null
    return "${night.divide(day, 2, RoundingMode.HALF_UP)}×"
}

private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()

// ============================================================================================
// Shared "neon" helpers
// ============================================================================================

/**
 * Soft outer glow around a rounded-rect surface — three expanding, fading rounded rects drawn
 * behind the content (cheap `drawBehind`, no blur/RenderEffect, per the SM-T575 frame budget).
 * Place BEFORE `.clip()`/`.background()` in the modifier chain so the glow lands outside the
 * surface's own bounds. [strength] 0..1 scales every layer's alpha (animate it for a pulse).
 */
private fun Modifier.neonGlow(color: Color, cornerRadius: Dp, strength: Float = 1f, spread: Dp = 5.dp): Modifier =
    drawBehind {
        if (strength <= 0.01f) return@drawBehind
        val step = spread.toPx()
        val r = cornerRadius.toPx()
        for (i in 3 downTo 1) {
            val inset = step * i
            drawRoundRect(
                color = color.copy(alpha = (0.22f / i) * strength),
                topLeft = Offset(-inset, -inset),
                size = Size(size.width + inset * 2, size.height + inset * 2),
                cornerRadius = CornerRadius(r + inset, r + inset),
            )
        }
    }

/** Text glow — a same-colour paint shadow (blur radius in px), the cheap way to "bloom" a label. */
private fun glowStyle(color: Color, blurPx: Float = 18f, alpha: Float = 0.85f): TextStyle =
    TextStyle(shadow = Shadow(color = color.copy(alpha = alpha), offset = Offset.Zero, blurRadius = blurPx))

/** Small stacked column used for every "LABEL over VALUE" readout on this screen. */
@Composable
private fun MiniStat(label: String, value: String, valueColor: Color = CaptainPalette.textPrimary, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.sp, color = CaptainPalette.textMuted)
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = valueColor, modifier = Modifier.padding(top = 2.dp))
    }
}

// ============================================================================================
// Column 1 — NIGHT / DAY FARE tile + status
// ============================================================================================

/**
 * The mockup's NIGHT FARE tile, state-driven off the engine's own [TimeClass] (the same field the
 * breakdown's "Night Fare" row keys on — never a local clock check that could disagree with what
 * is actually being charged). NIGHT: moon, the real night/day ratio off the signed tariff, the
 * 10 PM – 6 AM window (the local engine's own boundary, `FareEngine.kt#resolveTimeClass`), neon
 * accent border + glow. DAY/HOLIDAY: a calmer "DAY FARE · 1.00×" variant rather than an empty
 * slot — 1.00× is literally true (day rate is the baseline the night ratio is measured against).
 */
@Composable
private fun NightFareTile(timeClass: TimeClass, tariff: TariffDto?) {
    val night = timeClass == TimeClass.NIGHT
    val shape = RoundedCornerShape(18.dp)
    val breath by rememberInfiniteTransition(label = "night-tile").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "night-tile-breath",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (night) Modifier.neonGlow(CaptainPalette.accent, 18.dp, strength = breath) else Modifier)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .border(if (night) 1.5.dp else 1.dp, if (night) CaptainPalette.accent.copy(alpha = 0.85f) else CaptainPalette.panelBorder, shape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (night) Icons.Rounded.Bedtime else Icons.Rounded.WbSunny,
                contentDescription = null,
                tint = if (night) CaptainPalette.accent else CaptainPalette.warning,
                modifier = Modifier.size(15.dp),
            )
            Text(
                if (night) "NIGHT FARE" else "DAY FARE",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Text(
            if (night) nightMultiplierLabel(tariff) ?: "—" else "1.00×",
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            color = CaptainPalette.textPrimary,
            style = if (night) glowStyle(CaptainPalette.accent, 22f) else TextStyle.Default,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            if (night) "10:00 PM – 6:00 AM" else "6:00 AM – 10:00 PM",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            color = CaptainPalette.textSecondary,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** The band/time-class/SIGNED + GPS readout the old top status row carried, folded into the left
 * column so the dial gets the pane's full height. Same reads as before. */
@Composable
private fun MeterStatusTile(fareState: FareState, gpsOk: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .border(1.dp, CaptainPalette.panelBorder, shape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("STATUS", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp, color = CaptainPalette.textMuted)
        StatusLine("TARIFF", fareState.band.label.uppercase().removePrefix("TARIFF ").trim(), CaptainPalette.warning)
        StatusLine("RATE", fareState.timeClass.label.uppercase(), if (fareState.timeClass == TimeClass.NIGHT) CaptainPalette.accent else CaptainPalette.textPrimary)
        StatusLine("SIGNED", "✓", CaptainPalette.success)
        StatusLine("GPS", if (gpsOk) "FIX" else "NO FIX", if (gpsOk) CaptainPalette.success else CaptainPalette.danger)
        Spacer(Modifier.weight(1f))
        val waitMin = fareState.waitingSeconds / 60
        val waitSec = fareState.waitingSeconds % 60
        StatusLine("WAITING", "%d:%02d".format(waitMin, waitSec), if (fareState.status == TripStatus.STOPPED) CaptainPalette.warning else CaptainPalette.textPrimary)
    }
}

@Composable
private fun StatusLine(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = CaptainPalette.textSecondary)
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = valueColor)
    }
}

/**
 * Real ≥56dp circular icon button replacing the previous bare-emoji `Text.clickable` (a real
 * small-touch-target accessibility problem for an elderly driver base) — same
 * `toggleSpeech(!speechEnabled)` call site, just a legible Material icon and a proper hit area.
 * Reached from [MoreActionsSheet].
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

// ============================================================================================
// Column 2 — the speedometer dial
// ============================================================================================

/**
 * The speedometer/fare dial. All art is [MeterDialArt] (one Canvas); everything readable is
 * plain Compose text on top. The speed arc is bound to `fareState.currentSpeedKmh` — the live
 * engine's own speed field, the same number FareEngineImpl decides DISTANCE-vs-WAITING accrual
 * on — animated with a ~300ms spring so it sweeps rather than jumps. It honestly sits at 0 when
 * the engine sees 0 (no fix / stationary).
 *
 * Fare-tick pulse: each time `fareState.total` changes, the numerals flash to success-green and
 * settle back, the figure pops ~6%, and [MeterDialArt]'s fare-ring bloom flares (via [tickBloom])
 * and decays over ~700ms — a live increment reads as an event, not a silent number swap.
 */
@Composable
private fun ActiveMeterDial(fareState: FareState, isPaused: Boolean, onEndFare: () -> Unit, modifier: Modifier = Modifier) {
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
    val flashColor by animateColorAsState(
        targetValue = if (justTicked) CaptainPalette.success else CaptainPalette.textPrimary,
        animationSpec = tween(if (justTicked) 0 else 280),
        label = "fare-flash",
    )
    val tickScale by animateFloatAsState(
        targetValue = if (justTicked) 1.06f else 1f,
        animationSpec = if (justTicked) tween(60, easing = FastOutSlowInEasing) else spring(dampingRatio = 0.45f, stiffness = 500f),
        label = "fare-scale",
    )
    val tickBloom by animateFloatAsState(
        targetValue = if (justTicked) 1f else 0f,
        animationSpec = tween(if (justTicked) 40 else 700, easing = FastOutSlowInEasing),
        label = "fare-bloom",
    )
    val speedTarget = fareState.currentSpeedKmh.toFloat().coerceIn(0f, SPEED_SCALE_MAX)
    val speed by animateFloatAsState(
        targetValue = speedTarget,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 120f),
        label = "speed-sweep",
    )
    val stateColor by animateColorAsState(
        targetValue = if (isPaused) CaptainPalette.warning else CaptainPalette.accent,
        animationSpec = tween(300),
        label = "state-color",
    )

    Box(modifier = modifier.size(DIAL_SIZE), contentAlignment = Alignment.Center) {
        MeterDialArt(
            speedKmh = speed,
            active = !isPaused,
            tickBloom = tickBloom,
            modifier = Modifier.size(DIAL_ART_SIZE),
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.DirectionsCar,
                contentDescription = null,
                tint = CaptainPalette.accent,
                modifier = Modifier.size(22.dp),
            )
            Text(
                "ACTIVE FARE",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.padding(top = 3.dp),
            )
            val totalText = fareState.total.toMoneyString()
            Text(
                totalText,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold,
                fontSize = if (totalText.length > 7) 38.sp else 46.sp,
                color = flashColor,
                style = glowStyle(if (justTicked) CaptainPalette.success else CaptainPalette.accent, 24f, 0.7f),
                modifier = Modifier.scale(tickScale),
            )
            Text(
                if (isPaused) "PAUSED" else "RUNNING",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 3.sp,
                color = stateColor,
                style = glowStyle(stateColor, 20f),
            )
            Text(
                "${fareState.band.label.uppercase()} + EXTRAS",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                color = CaptainPalette.textMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(modifier = Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val movingMin = fareState.movingSeconds / 60
                val movingSec = fareState.movingSeconds % 60
                DialMiniBox("DISTANCE", fareState.distanceKm.setScale(1, RoundingMode.HALF_UP).toPlainString() + " KM")
                DialMiniBox("TIME", "%d:%02d".format(movingMin, movingSec))
            }
            // END FARE — inside the dial, per the mockup. Same endTrip { navigate(CLOSE_PAY) }
            // call the old full-width END TRIP bar made (see the caller).
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .width(154.dp)
                    .height(38.dp)
                    .neonGlow(CaptainPalette.primary, 19.dp, strength = 0.9f, spread = 4.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(Brush.horizontalGradient(listOf(CaptainPalette.primary, CaptainPalette.accent)))
                    .border(1.dp, CaptainPalette.accent.copy(alpha = 0.9f), RoundedCornerShape(19.dp))
                    .gameClick(onClick = onEndFare, shape = RoundedCornerShape(19.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "END FARE",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 2.sp,
                    color = CaptainPalette.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun DialMiniBox(label: String, value: String) {
    Column(
        modifier = Modifier
            .width(88.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CaptainPalette.bg.copy(alpha = 0.55f))
            .border(1.dp, CaptainPalette.accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 8.sp, letterSpacing = 1.sp, color = CaptainPalette.textMuted)
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = CaptainPalette.warning)
    }
}

private const val SPEED_SCALE_MAX = 120f
private const val SPEED_ARC_START = 135f
private const val SPEED_ARC_SWEEP = 270f

/**
 * All of the dial's Canvas art, drawn on a canvas larger than the dial itself so the glow can
 * spill past the ring (the caller sizes this [DIAL_ART_SIZE] around a [DIAL_SIZE] dial; every
 * radius below is derived from [DIAL_SIZE], not from the canvas). Layers, back to front:
 *
 * 1. Four stacked radial-gradient discs of decreasing alpha — the layered bloom — breathing on a
 *    slow loop and flaring with [tickBloom]; dimmer while paused ([active] false), never off (a
 *    dead dial reads as broken, not paused).
 * 2. A solid, near-opaque inner disc so the fare/labels sit on a stable surface over the map.
 * 3. The fare ring: a thick accent ring with a wide low-alpha bloom stroke under it, plus two
 *    opposing bright arcs rotating around it (the old `MeterDialGlow` sweep, kept).
 * 4. The speedometer: a neutral track arc over 270° (7:30 → 4:30 o'clock), tick marks every
 *    5 km/h (major every 20), numeric labels 0–120, and the live speed arc (accent, with its own
 *    soft bloom) sweeping to [speedKmh].
 *
 * Everything is `drawArc`/`drawCircle`/`drawLine` + native text — no blur/RenderEffect, so it
 * stays cheap on the SM-T575.
 */
@Composable
private fun MeterDialArt(speedKmh: Float, active: Boolean, tickBloom: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "meter-dial")
    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (active) 5000 else 20000, easing = LinearEasing)),
        label = "meter-dial-sweep",
    )
    val breath by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "meter-dial-breath",
    )
    val glowStrength by animateFloatAsState(
        targetValue = if (active) 1f else 0.4f,
        animationSpec = tween(500),
        label = "meter-dial-glow-strength",
    )
    val accent = CaptainPalette.accent
    val primary = CaptainPalette.primary
    val labelColor = CaptainPalette.textSecondary.toArgb()
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val c = Offset(cx, cy)
        val R = DIAL_SIZE.toPx() / 2f
        val bloom = (0.65f + 0.35f * breath) * glowStrength + tickBloom * 0.9f

        // 1. Layered bloom — biggest/faintest first.
        val glowLayers = listOf(R * 1.30f to 0.07f, R * 1.16f to 0.11f, R * 1.06f to 0.17f, R * 0.98f to 0.26f)
        glowLayers.forEach { (radius, alpha) ->
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = (alpha * bloom).coerceAtMost(1f)), Color.Transparent),
                    center = c,
                    radius = radius,
                ),
                radius = radius,
                center = c,
            )
        }

        // 2. Inner disc (content surface). Sits inside the fare ring.
        val fareRingR = R - 44.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(CaptainPalette.panel.copy(alpha = 0.96f), CaptainPalette.cardBottom.copy(alpha = 0.97f)),
                center = c,
                radius = fareRingR,
            ),
            radius = fareRingR - 2.dp.toPx(),
            center = c,
        )

        // 3. Fare ring + bloom + rotating highlights.
        val fareStroke = 8.dp.toPx()
        drawCircle(color = accent.copy(alpha = (0.18f + 0.22f * tickBloom) * glowStrength), radius = fareRingR, center = c, style = Stroke(fareStroke * 3f))
        drawCircle(color = primary.copy(alpha = 0.55f + 0.45f * glowStrength), radius = fareRingR, center = c, style = Stroke(fareStroke))
        val ringRect = Size(fareRingR * 2, fareRingR * 2)
        val ringTopLeft = Offset(cx - fareRingR, cy - fareRingR)
        listOf(sweepAngle, sweepAngle + 180f).forEach { start ->
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(Color.Transparent, accent.copy(alpha = 0.95f * glowStrength), Color.Transparent),
                    center = c,
                ),
                startAngle = start,
                sweepAngle = 70f,
                useCenter = false,
                topLeft = ringTopLeft,
                size = ringRect,
                style = Stroke(fareStroke, cap = StrokeCap.Round),
            )
        }
        // Small inner hairline so the disc edge reads crisp against the ring.
        drawCircle(color = accent.copy(alpha = 0.35f), radius = fareRingR - fareStroke, center = c, style = Stroke(1.dp.toPx()))

        // 4. Speedometer ring.
        val trackR = R - 8.dp.toPx()
        val trackStroke = 5.dp.toPx()
        val trackRect = Size(trackR * 2, trackR * 2)
        val trackTopLeft = Offset(cx - trackR, cy - trackR)
        drawArc(
            color = CaptainPalette.dialNeutral,
            startAngle = SPEED_ARC_START,
            sweepAngle = SPEED_ARC_SWEEP,
            useCenter = false,
            topLeft = trackTopLeft,
            size = trackRect,
            style = Stroke(trackStroke, cap = StrokeCap.Round),
        )
        val speedSweep = SPEED_ARC_SWEEP * (speedKmh / SPEED_SCALE_MAX).coerceIn(0f, 1f)
        if (speedSweep > 0.5f) {
            drawArc(
                color = accent.copy(alpha = 0.28f),
                startAngle = SPEED_ARC_START,
                sweepAngle = speedSweep,
                useCenter = false,
                topLeft = trackTopLeft,
                size = trackRect,
                style = Stroke(trackStroke * 3f, cap = StrokeCap.Round),
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(primary, accent, CaptainPalette.success), center = c),
                startAngle = SPEED_ARC_START,
                sweepAngle = speedSweep,
                useCenter = false,
                topLeft = trackTopLeft,
                size = trackRect,
                style = Stroke(trackStroke, cap = StrokeCap.Round),
            )
        }
        // Ticks + labels.
        val tickOuter = trackR - trackStroke
        val majorLen = 11.dp.toPx()
        val minorLen = 5.dp.toPx()
        val labelR = trackR - 24.dp.toPx()
        labelPaint.textSize = 10.sp.toPx()
        labelPaint.color = labelColor
        val steps = (SPEED_SCALE_MAX / 5f).toInt() // one tick per 5 km/h
        for (i in 0..steps) {
            val kmh = i * 5f
            val major = i % 4 == 0
            val angleDeg = SPEED_ARC_START + SPEED_ARC_SWEEP * (kmh / SPEED_SCALE_MAX)
            val rad = Math.toRadians(angleDeg.toDouble())
            val dirX = cos(rad).toFloat()
            val dirY = sin(rad).toFloat()
            val len = if (major) majorLen else minorLen
            val lit = kmh <= speedKmh
            drawLine(
                color = if (lit) accent else CaptainPalette.dialNeutral,
                start = Offset(cx + dirX * tickOuter, cy + dirY * tickOuter),
                end = Offset(cx + dirX * (tickOuter - len), cy + dirY * (tickOuter - len)),
                strokeWidth = (if (major) 2.5.dp else 1.5.dp).toPx(),
                cap = StrokeCap.Round,
            )
            if (major) {
                val lx = cx + dirX * labelR
                val ly = cy + dirY * labelR - (labelPaint.ascent() + labelPaint.descent()) / 2f
                drawContext.canvas.nativeCanvas.drawText(kmh.roundToInt().toString(), lx, ly, labelPaint)
            }
        }
        // Speed value + unit under the scale's bottom gap, on the inner disc's rim.
        labelPaint.textSize = 9.sp.toPx()
        labelPaint.color = CaptainPalette.textMuted.toArgb()
        drawContext.canvas.nativeCanvas.drawText("km/h", cx, cy + R - 14.dp.toPx(), labelPaint)
        labelPaint.textSize = 13.sp.toPx()
        labelPaint.color = accent.toArgb()
        drawContext.canvas.nativeCanvas.drawText(speedKmh.roundToInt().toString(), cx, cy + R - 26.dp.toPx(), labelPaint)
    }
}

// ============================================================================================
// Column 3 — action tiles (SET PRICE / ADD TOLL / PAUSE FARE / MORE)
// ============================================================================================

@Composable
private fun MeterActionStack(
    isPaused: Boolean,
    negotiatedTotal: String?,
    tollsTotal: BigDecimal,
    tollCount: Int,
    onSetPrice: () -> Unit,
    onAddToll: () -> Unit,
    onTogglePause: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MeterActionTile(
            icon = Icons.Rounded.Sell,
            label = "SET PRICE",
            // Honest status line, not a fake "tap to edit" — see SetPriceInfoDialog's doc for why
            // this button is informational only during an active trip.
            value = if (negotiatedTotal != null) "Fixed · ${formatNegotiatedTotal(negotiatedTotal)}" else "Metered fare",
            onClick = onSetPrice,
            modifier = Modifier.weight(1f),
        )
        MeterActionTile(
            icon = Icons.Rounded.ConfirmationNumber,
            label = "ADD TOLL",
            value = "${tollsTotal.toMoneyString()} · $tollCount toll${if (tollCount == 1) "" else "s"} added",
            accentColor = CaptainPalette.warning,
            onClick = onAddToll,
            modifier = Modifier.weight(1f),
        )
        MeterActionTile(
            icon = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            label = if (isPaused) "RESUME FARE" else "PAUSE FARE",
            value = if (isPaused) "Waiting — tap to resume" else "Tap when the trip stops",
            accentColor = if (isPaused) CaptainPalette.warning else CaptainPalette.success,
            active = isPaused,
            onClick = onTogglePause,
            modifier = Modifier.weight(1f),
        )
        MeterActionTile(
            icon = Icons.Rounded.MoreHoriz,
            label = "MORE",
            value = "Extras · passengers · speech",
            onClick = onMore,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Compact square-ish tile: icon in a coloured rounded square, bold label, one-line subtext.
 * [active] lights the neon border + a breathing outer glow (PAUSE FARE while paused). */
@Composable
private fun MeterActionTile(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = CaptainPalette.accent,
    active: Boolean = false,
) {
    val shape = RoundedCornerShape(16.dp)
    val breath by rememberInfiniteTransition(label = "tile-$label").animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "tile-breath",
    )
    val borderColor by animateColorAsState(
        targetValue = if (active) accentColor.copy(alpha = 0.9f) else CaptainPalette.panelBorder,
        animationSpec = tween(250),
        label = "tile-border",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (active) Modifier.neonGlow(accentColor, 16.dp, strength = breath) else Modifier)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .border(if (active) 1.5.dp else 1.dp, borderColor, shape)
            .gameClick(onClick = onClick, shape = shape, glowColor = accentColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accentColor.copy(alpha = if (active) 0.32f else 0.18f))
                .border(1.dp, accentColor.copy(alpha = if (active) 0.9f else 0.4f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
        }
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 0.5.sp,
            color = if (active) accentColor else CaptainPalette.textPrimary,
            style = if (active) glowStyle(accentColor, 14f) else TextStyle.Default,
            modifier = Modifier.padding(top = 7.dp),
        )
        Text(
            value,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 9.5.sp,
            color = CaptainPalette.textSecondary,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** `negotiatedTotal` is a decimal-as-string (this project's money-field convention, see
 * `ApiService.kt`'s header note) — reused here as plain display text, never re-parsed into a new
 * fare calculation. Falls back to the raw string (prefixed) on a malformed value rather than
 * crashing a dialog over a display nicety. */
private fun formatNegotiatedTotal(raw: String): String =
    runCatching { BigDecimal(raw).toMoneyString() }.getOrDefault("$$raw")

/**
 * SET PRICE, tapped (Phase A step 5) — deliberately **not** an editable control.
 * `TripContext.negotiatedTotal` is real: it is the fixed fare the driver agreed with the passenger
 * at Start Meter time (via the dashboard's own Set Price flow), already persisted to
 * `TripEntity.negotiatedTotal` and synced to the backend — but nothing in [HiredViewModel] can
 * *change* it once a trip is running (no `setNegotiatedTotal()`/equivalent exists, and this pass's
 * `HiredViewModel` edit budget is scoped to wiring the already-existing
 * `breakdownExpanded`/`toggleBreakdown()` pair only — adding one would be new business logic this
 * pass has no mandate to add). Building a text field or keypad here that looks like it edits the
 * price, when nothing downstream would ever read the edit, is exactly the fake affordance this
 * codebase's own EXTRAS button already refuses to be (see that dialog's identical shape) — so this
 * shows the real value (or its real absence) and says why, instead.
 */
@Composable
private fun SetPriceInfoDialog(negotiatedTotal: String?, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .width(520.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Set Price", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
        Text(
            if (negotiatedTotal != null) {
                "This trip is a fixed fare of ${formatNegotiatedTotal(negotiatedTotal)}, agreed before the meter started. " +
                    "It can't be changed once a trip is running."
            } else {
                "This trip is running on the metered fare. To fix a price up front instead, use SET PRICE " +
                    "from the dashboard before starting the next trip."
            },
            fontFamily = InterFamily,
            fontSize = 15.sp,
            color = CaptainPalette.textSecondary,
        )
        CaptainButton(text = "OK", outline = true, widthDp = 180) { onDismiss() }
    }
}

/**
 * ADD TOLL, tapped (Phase A step 5) — the same three real toll presets ([TollPresets.ALL]) and
 * custom-amount pad this screen always had, consolidated from four separate inline chips into one
 * dialog reached from the action stack. `onAddPreset`/`onCustom` map straight back to
 * `viewModel.addToll(preset)` at the call site — no new toll logic here.
 */
@Composable
private fun TollPresetDialog(tollsTotal: BigDecimal, onDismiss: () -> Unit, onAddPreset: (TollPreset) -> Unit, onCustom: () -> Unit) {
    // Presets in one Row and the two buttons in another (game-level visual pass): this dialog is
    // hosted inside the ~420dp-tall METER pane (CaptainDialogScrim fills the pane, not the
    // window), and the previous five-row stack ran ~500dp — its Close button was clipped behind
    // the footer on-device. Same chips, same callbacks, just laid out to fit.
    Column(
        modifier = Modifier
            .width(720.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Add toll", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
        Text(
            "Tolls so far: ${tollsTotal.toMoneyString()}",
            fontFamily = InterFamily,
            fontSize = 14.sp,
            color = CaptainPalette.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TollPresets.ALL.forEach { preset ->
                CaptainChip(preset.label.uppercase(), preset.amount.toMoneyString(), modifier = Modifier.weight(1f)) {
                    onAddPreset(preset)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CaptainButton(text = "Custom amount…", outline = true, modifier = Modifier.weight(1.4f)) { onCustom() }
            CaptainButton(text = "Close", outline = true, modifier = Modifier.weight(1f)) { onDismiss() }
        }
    }
}

/**
 * MORE, tapped (Phase A step 5) — an overflow sheet for the previously-inline EXTRAS-explainer,
 * passenger-count correction, and speech-announcement toggle, so the action stack itself stays to
 * exactly four rows matching the mockup. Every row still calls the exact same pre-existing
 * ViewModel entry point (`updatePassengerCount()`/`toggleSpeech()`) via the caller's lambdas.
 */
@Composable
private fun MoreActionsSheet(
    speechEnabled: Boolean,
    passengerCount: Int,
    onToggleSpeech: (Boolean) -> Unit,
    onEditPassengers: () -> Unit,
    onExtras: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(480.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("More", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)

        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onExtras).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Receipt, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text("Extras", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
                Text("No chargeable extras configured yet", fontFamily = InterFamily, fontSize = 12.sp, color = CaptainPalette.textSecondary)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onEditPassengers).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Person, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text("Passenger count", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
                Text("$passengerCount — tap to correct", fontFamily = InterFamily, fontSize = 12.sp, color = CaptainPalette.textSecondary)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Speech announcements", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
                Text(if (speechEnabled) "Announcing each dollar increment" else "Off", fontFamily = InterFamily, fontSize = 12.sp, color = CaptainPalette.textSecondary)
            }
            SpeechToggleButton(enabled = speechEnabled, onToggle = { onToggleSpeech(!speechEnabled) })
        }

        CaptainButton(text = "Close", outline = true, modifier = Modifier.fillMaxWidth()) { onDismiss() }
    }
}


// ============================================================================================
// Column 4 — Fare Breakdown card (revives HiredViewModel.breakdownExpanded/toggleBreakdown())
// ============================================================================================

@Composable
private fun FareBreakdownCard(
    breakdown: FareBreakdown,
    timeClass: TimeClass,
    nightMultiplierLabel: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .border(1.dp, CaptainPalette.panelBorder, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("FARE BREAKDOWN", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp, color = CaptainPalette.textPrimary)
            Spacer(Modifier.weight(1f))
            val chevronRotation by animateFloatAsState(if (expanded) 90f else -90f, label = "breakdown-chevron")
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, CaptainPalette.accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (expanded) "HIDE" else "SHOW",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = CaptainPalette.accent,
                )
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = CaptainPalette.accent,
                    modifier = Modifier.size(16.dp).padding(start = 2.dp).rotate(chevronRotation),
                )
            }
        }
        AnimatedVisibility(visible = expanded, enter = fadeIn(tween(180)), exit = fadeOut(tween(140))) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                BreakdownRow("Base fare", breakdown.flagFall.toMoneyString(), CaptainPalette.success)
                BreakdownRow("Distance", breakdown.distanceAmount.toMoneyString(), CaptainPalette.success)
                BreakdownRow("Time", breakdown.waitingAmount.toMoneyString(), CaptainPalette.success)
                // Informational only: the night-rate uplift is already baked into Distance/Time
                // above (FareEngineImpl applies the night per-km/per-min rate directly — there is
                // no separate night-surcharge line item to show), so this never adds to `total`
                // itself, only explains the higher Distance/Time figures when it applies.
                if (timeClass == TimeClass.NIGHT) {
                    BreakdownRow("Night fare (${nightMultiplierLabel ?: "—"})", "included", CaptainPalette.accent)
                }
                if (breakdown.peakAmount.signum() > 0) {
                    BreakdownRow("Peak hiring", breakdown.peakAmount.toMoneyString(), CaptainPalette.accent)
                }
                BreakdownRow("Tolls", breakdown.tolls.toMoneyString(), CaptainPalette.warning)
                BreakdownRow("Levy & charges", breakdown.psl.toMoneyString(), CaptainPalette.danger)
                if (breakdown.extras.signum() > 0) {
                    BreakdownRow("Extras", breakdown.extras.toMoneyString(), CaptainPalette.warning)
                }
            }
        }
        // Total row — always visible (HIDE collapses the line items, never the total), neon
        // accent border + glow, figure in glowing accent.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .neonGlow(CaptainPalette.accent, 12.dp, strength = 0.7f, spread = 3.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CaptainPalette.primary.copy(alpha = 0.16f))
                .border(1.5.dp, CaptainPalette.accent.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("TOTAL", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp, color = CaptainPalette.textPrimary)
            Text(
                breakdown.total.toMoneyString(),
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = CaptainPalette.accent,
                style = glowStyle(CaptainPalette.accent, 22f),
            )
        }
    }
}

@Composable
private fun BreakdownRow(label: String, value: String, dotColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .drawBehind { drawCircle(dotColor.copy(alpha = 0.35f), radius = size.minDimension) }
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = CaptainPalette.textSecondary,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
        )
        Text(
            value,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = CaptainPalette.textPrimary,
        )
    }
}

// ============================================================================================
// Column 4 — Trip Details card (vertical pickup → drop-off timeline)
// ============================================================================================

/**
 * [startAtIso] is the persisted `TripEntity.startAt` (real open time) — `null` until Room has the
 * row, rendering "—". Drop-off time is always "—" here: the trip is in progress. Addresses are
 * `TripContext.originAddress`/`.destAddress` — "—" when absent (see that doc), never fabricated.
 */
@Composable
private fun TripDetailsCard(tripContext: TripContext?, fareState: FareState, startAtIso: String?) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .border(1.dp, CaptainPalette.panelBorder, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("TRIP DETAILS", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp, color = CaptainPalette.textPrimary)
            Spacer(Modifier.weight(1f))
            Text(
                tripContext?.clientUuid?.take(8)?.uppercase() ?: "—",
                fontFamily = RobotoMonoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                color = CaptainPalette.textMuted,
            )
        }
        Spacer(Modifier.height(10.dp))
        TimelineRow(
            dotColor = CaptainPalette.success,
            title = "PICKUP",
            address = tripContext?.originAddress ?: "—",
            time = startAtIso?.asLocalTime() ?: "—",
            connector = true,
        )
        TimelineRow(
            dotColor = CaptainPalette.danger,
            title = "DROP-OFF",
            address = tripContext?.destAddress ?: "—",
            time = "—",
            connector = false,
        )
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.panelBorder))
        Spacer(Modifier.height(10.dp))
        val totalSeconds = fareState.movingSeconds + fareState.waitingSeconds
        val avgSpeedKmh = if (fareState.movingSeconds > 0) {
            (fareState.distanceKm.toDouble() / (fareState.movingSeconds / 3600.0)).roundToInt()
        } else {
            0
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            MiniStat("DISTANCE", fareState.distanceKm.setScale(1, RoundingMode.HALF_UP).toPlainString() + " km", modifier = Modifier.weight(1f))
            MiniStat("DURATION", "%d:%02d".format(totalSeconds / 60, totalSeconds % 60), modifier = Modifier.weight(1f))
            MiniStat("AVG SPEED", "$avgSpeedKmh km/h", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TimelineRow(dotColor: Color, title: String, address: String, time: String, connector: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(14.dp)) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .drawBehind { drawCircle(dotColor.copy(alpha = 0.35f), radius = size.minDimension * 0.9f) }
                    .clip(CircleShape)
                    .background(dotColor),
            )
            if (connector) {
                Box(Modifier.padding(vertical = 3.dp).width(2.dp).height(22.dp).background(CaptainPalette.panelBorder))
            }
        }
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.sp, color = dotColor)
                Text(time, fontFamily = RobotoMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = CaptainPalette.textSecondary)
            }
            Text(
                address,
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = CaptainPalette.textPrimary,
                maxLines = 2,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Mid-trip passenger-count correction dialog (Point to Point Transport (Fares) Order 2026
 * UI-wiring pass) — reached only via [MoreActionsSheet] now (previously a small "PAX n ✎" tap-to-
 * edit affordance floating on the meter well); same [HiredViewModel.updatePassengerCount] call and
 * dialog otherwise. Confirming re-derives [au.com.threesixty.cabdispatch.domain.FareState.maxiRateApplied]
 * immediately for the remainder of the trip without touching any already-accrued charge — see that
 * method's own doc.
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
    // Two columns (amount + buttons | keypad) rather than one stack — same reason as
    // TollPresetDialog: hosted inside the ~420dp-tall METER pane, and title + amount + a
    // 4-row keypad + buttons stacked vertically ran past the pane's bottom edge on-device.
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(22.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(
            modifier = Modifier.width(300.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Add toll", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
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
            Spacer(Modifier.weight(1f))
            CaptainButton(
                text = "Add toll",
                enabled = amount > BigDecimal.ZERO,
                modifier = Modifier.fillMaxWidth(),
            ) { onConfirm(amount) }
            CaptainButton(text = "Cancel", outline = true, modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
        CaptainKeypad(
            onDigit = { d -> if (cents.length < 5) cents += d },
            onBackspace = { cents = cents.dropLast(1) },
            onClear = { cents = "" },
        )
    }
}
