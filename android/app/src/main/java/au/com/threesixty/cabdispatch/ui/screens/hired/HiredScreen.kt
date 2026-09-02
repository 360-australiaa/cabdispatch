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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Sell
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

/**
 * 18/18b · Hired — Meter, Phase A shell-integration pass (2026-09-03). Was previously a standalone
 * full-screen route ("deliberately no nav rail/drive panel" — see git history prior to this pass);
 * now embedded as [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s
 * `CaptainPane.METER` pane, the same "one shell, swap embedded content per rail item" pattern the
 * `*WheelContent` panes already use (`ShiftWheelContent`, `EarningsWheelContent`, etc.) — see that
 * file's own class doc ("Meter joins the shared shell") and its `startOnMeter` param doc for how a
 * driver actually lands here. The header/footer/nav-rail a driver sees while HIRED now come from
 * that shared shell, not from this file — this composable owns only the content area DeckHomeScreen
 * hands it (the same slot `MeterCard`/`LiveDispatchCard` occupy for `CaptainPane.DASHBOARD`).
 *
 * All metering logic is untouched [HiredViewModel]: live [FareState] ticks, pause/resume,
 * addToll persistence, endTrip → Close & Pay, duress state machine (hidden gesture + overlays) —
 * every `viewModel.*` call and `fareState.*`/`duressState` read below is byte-for-byte the same
 * call/read this screen made before this pass; only the surrounding layout changed:
 *
 * - **Circular [ActiveMeterDial]** replaces the old rounded-rect meter well — same
 *   rotating-arc/radial-gradient glow technique as the well's old `MeterWellGlow` (now
 *   [MeterDialGlow]; the sweep/glow/pulse Canvas math is unchanged, only the outer shape is now a
 *   circle rather than a rounded rect, and a full ring stroke was added to read as a dial rather
 *   than a glowing panel), plus the same fare-tick flash/scale pulse the well always had.
 * - **[FareBreakdownCard]** revives [HiredViewModel.breakdownExpanded]/`.toggleBreakdown()` —
 *   previously wired on the ViewModel but never read/called by this screen — as a real HIDE/SHOW
 *   toggle over `fareState.breakdown`'s existing fields. No new fare data, just a real card instead
 *   of the old flat `chargesRow` of chips.
 * - **[TripDetailsCard]** — trip ID/pickup/drop-off/distance/duration/avg-speed. Pickup/drop-off
 *   read `SessionHolder.pendingTrip.value`'s new `originAddress`/`destAddress` (see
 *   `domain/Session.kt`'s `TripContext` doc) — `null` for a trip with no dispatch-offer address to
 *   carry (a street hail/rank job, or one accepted via the Dispatch wheel-content pane, which this
 *   pass's edit scope didn't extend to) renders "—", never a fabricated address. Avg speed is plain
 *   arithmetic over `fareState.distanceKm`/`.movingSeconds` — no new engine field.
 * - **Vertical action stack** (SET PRICE / ADD TOLL / PAUSE FARE / MORE) replaces the old
 *   horizontal `chargesRow` + controls `Row`. PAUSE FARE still calls `togglePause()` exactly; ADD
 *   TOLL opens the same toll presets (`TollPresets.ALL`) + custom-amount pad via `addToll()`
 *   exactly, just reached through one stack entry instead of four inline chips. SET PRICE is
 *   **read-only/informational** here — see [SetPriceInfoDialog]'s doc for why: `TripContext.negotiatedTotal`
 *   is real, already-wired data (it decided this trip's fixed fare at Start Meter time and is
 *   already persisted to `TripEntity.negotiatedTotal`), but nothing in [HiredViewModel] can *change*
 *   it mid-trip, so this pass does not fabricate a working "edit price" affordance — it shows the
 *   real value (or the real absence of one) and explains why it can't be changed here, the same
 *   honest-affordance treatment this screen's own EXTRAS button already used before this pass.
 *   MORE tucks the previously-inline passenger-count-correction and speech-toggle affordances into
 *   one overflow sheet — same `updatePassengerCount()`/`toggleSpeech()` calls, unchanged.
 *
 * The hidden duress gesture zone's modifier (`align(Alignment.BottomEnd).padding(end = 12.dp,
 * bottom = 12.dp)`) and `onTriggered = viewModel::onDuressTriggered` call are reproduced verbatim
 * below — this pass does not move, resize, or reveal it. It now anchors to this pane's own content
 * box (DeckHomeScreen's METER slot) rather than the full physical screen, since that box no longer
 * spans the whole display once the shared header/footer/nav-rail wrap around it, but the gesture
 * itself (invisible, bottom-end corner of its container, 3 taps inside 800ms) is unchanged.
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
    // new field added to HiredViewModel itself — this pass's HiredViewModel edit budget is spent
    // entirely on being read-only. Degrades to nulls (every dependent row below already shows "—")
    // if this VM instance somehow outlives the pendingTrip hand-off it was created from.
    val tripContext by SessionHolder.pendingTrip.collectAsState()

    var showTollPad by remember { mutableStateOf(false) }
    var showTollMenu by remember { mutableStateOf(false) }
    var showExtrasNote by remember { mutableStateOf(false) }
    var showSetPriceInfo by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    // Point to Point Transport (Fares) Order 2026 UI-wiring pass: mid-trip passenger-count
    // correction — see HiredViewModel.updatePassengerCount's doc. Now reached from the MORE sheet
    // rather than a floating "PAX n ✎" affordance on the well, but the same dialog/call.
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
            // --- compact status row (state pill now lives on the dial itself; this keeps the
            // band/time-class/SIGNED + GPS readout the old topBar carried, without duplicating the
            // driver avatar/name/clock the shared header above this pane already shows) ---
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${fareState.band.label.uppercase()} — ${fareState.timeClass.label.uppercase()} · SIGNED ✓",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = CaptainPalette.warning,
                )
                Spacer(Modifier.width(18.dp))
                val gpsOk = AppContainer.speedSource.locationFix.collectAsState().value != null
                Text(
                    "GPS ●",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (gpsOk) CaptainPalette.success else CaptainPalette.danger,
                )
            }

            // --- Maxi rate / wheelchair-hiring indicators (Point to Point Transport (Fares)
            // Order 2026 UI-wiring pass). Read ONLY [fareState.maxiRateApplied] — the pure fare
            // engine's own derived flag, copied through by FareEngineImpl — never recomputed here
            // from isMaxiVehicle/passengerCount/wheelchairHiring directly, so this banner can never
            // drift from what is actually being charged. Unchanged from the pre-shell-integration
            // version, just relocated above the new two-column layout instead of the old meter well.
            AnimatedVisibility(visible = fareState.maxiRateApplied, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CaptainPalette.warning)
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "⚠  MAXI RATE ×1.5 ACTIVE",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.bg,
                    )
                }
            }
            AnimatedVisibility(visible = fareState.wheelchairHiring, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CaptainPalette.panel)
                        .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
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

            Spacer(Modifier.height(14.dp))

            // Real overflow fix, found live on-device (SM-T575): once the header/footer/nav-rail
            // wrap around this pane (the whole point of Phase A), the vertical room left for the
            // dial + action stack + Trip Details/Fare Breakdown is much tighter than the old
            // full-screen route ever had to budget for — a fixed, non-scrolling left column
            // silently clipped the DISTANCE/TIME/WAITING readouts behind the footer stats bar on
            // first on-device verification. Both columns now scroll independently, and END TRIP —
            // the one action that must never be scrolled out of reach — is pinned OUTSIDE this Row
            // entirely, in its own fixed-height slot below it (see the Box after this Row).
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // --- left: circular dial + readouts + vertical action stack ---
                Column(
                    modifier = Modifier.width(420.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                ) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ActiveMeterDial(fareState = fareState, isPaused = isPaused)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        val movingMin = fareState.movingSeconds / 60
                        val movingSec = fareState.movingSeconds % 60
                        val waitMin = fareState.waitingSeconds / 60
                        val waitSec = fareState.waitingSeconds % 60
                        MeterDatum("DISTANCE", fareState.distanceKm.setScale(1, RoundingMode.HALF_UP).toPlainString() + " KM")
                        MeterDatum("TIME", "%d:%02d".format(movingMin, movingSec))
                        MeterDatum("WAITING", "%d:%02d".format(waitMin, waitSec), highlight = isPaused)
                    }
                    Spacer(Modifier.height(14.dp))
                    MeterActionStack(
                        isPaused = isPaused,
                        negotiatedTotal = tripContext?.negotiatedTotal,
                        tollsTotal = fareState.breakdown.tolls,
                        onSetPrice = { showSetPriceInfo = true },
                        onAddToll = { showTollMenu = true },
                        onTogglePause = viewModel::togglePause,
                        onMore = { showMore = true },
                    )
                }

                Spacer(Modifier.width(18.dp))

                // --- right: Trip Details + Fare Breakdown cards ---
                Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    TripDetailsCard(tripContext = tripContext, fareState = fareState)
                    Spacer(Modifier.height(16.dp))
                    FareBreakdownCard(
                        breakdown = fareState.breakdown,
                        timeClass = fareState.timeClass,
                        nightMultiplierLabel = nightMultiplierLabel(tripContext?.tariff),
                        expanded = breakdownExpanded,
                        onToggle = viewModel::toggleBreakdown,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "One of distance or waiting accrues at a time — switches automatically at 26 km/h",
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        color = CaptainPalette.textMuted,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Pinned outside both scrolling columns above — the one action on this whole pane
            // that must always stay reachable, never scrolled out of view (see this file's own
            // comment on the Row above for the on-device overflow this fixes).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
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
                    fontSize = 19.sp,
                    color = CaptainPalette.textPrimary,
                )
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

/** Real night-rate uplift, not a fabricated multiplier — same ratio-of-signed-tariff computation
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s `NightFareTile` uses (that
 * one is `private` to a different file, so this is a small, deliberate duplicate of the same
 * formula rather than a cross-file reach-around). `null` tariff (no pending-trip hand-off to read
 * it from) hides the ratio rather than showing a bogus one — see [FareBreakdownCard]'s caller. */
private fun nightMultiplierLabel(tariff: TariffDto?): String? {
    val t = tariff ?: return null
    val day = t.distRate1.toBigDecimalOrNull() ?: return null
    val night = t.nightRate1.toBigDecimalOrNull() ?: return null
    if (day.signum() <= 0) return null
    return "${night.divide(day, 2, RoundingMode.HALF_UP)}×"
}

private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()

/**
 * Real ≥56dp circular icon button replacing the previous bare-emoji `Text.clickable` (a real
 * small-touch-target accessibility problem for an elderly driver base) — same
 * `toggleSpeech(!speechEnabled)` call site, just a legible Material icon and a proper hit area. Now
 * reached from [MoreActionsSheet] rather than the old topBar, unchanged otherwise.
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
 * Circular active-fare dial (Phase A step 2) — car icon center, "ACTIVE FARE $X" with the same
 * fare-tick flash/scale pulse the old rounded-rect well used, RUNNING/PAUSED · WAITING state pill,
 * "TARIFF n + EXTRAS" subtext. Glow behind it is [MeterDialGlow] — the old well's `MeterWellGlow`
 * Canvas technique, unchanged math, now drawn into a square (circular) bounds instead of a
 * rounded-rect one; see that composable's own doc.
 */
@Composable
private fun ActiveMeterDial(fareState: FareState, isPaused: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(272.dp), contentAlignment = Alignment.Center) {
        MeterDialGlow(active = !isPaused, modifier = Modifier.fillMaxSize())

        // Fare-tick pulse — verbatim from the old meter well: each time fareState.total changes,
        // the numerals flash to success-green and settle back over ~280ms, with a matching 3% scale
        // pop, so a live increment is visually readable rather than a silent number swap.
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
            targetValue = if (justTicked) 1.03f else 1f,
            animationSpec = tween(if (justTicked) 60 else 320, easing = FastOutSlowInEasing),
            label = "fare-scale",
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.DirectionsCar, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(32.dp))
            Text(
                "ACTIVE FARE",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 2.sp,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            val totalText = fareState.total.toMoneyString()
            Text(
                totalText,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (totalText.length > 7) 50.sp else 62.sp,
                color = flashColor,
                modifier = Modifier.padding(top = 6.dp).scale(tickScale),
            )
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(if (isPaused) CaptainPalette.warning else CaptainPalette.success)
                    .padding(horizontal = 18.dp, vertical = 7.dp),
            ) {
                Text(
                    if (isPaused) "PAUSED · WAITING" else "RUNNING",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp,
                    color = CaptainPalette.bg,
                )
            }
            Text(
                "${fareState.band.label.uppercase()} + EXTRAS",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = CaptainPalette.textMuted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * Ambient glow for the circular dial — the exact rotating-arc/radial-gradient Canvas technique the
 * old meter well's `MeterWellGlow` used (a soft accent radial-gradient blob plus two opposing
 * bright arcs sweeping around), unchanged math, plus one added plain ring stroke so the shape reads
 * as a dial rather than a glowing panel (the well never needed one since its own rounded-rect
 * border already framed it — this composable has no outer border of its own to lean on). Drawn
 * into a Canvas whose bounds are a SQUARE (the caller sizes this a fixed `.size(320.dp)` circle),
 * so the same `drawArc` calls that traced an ellipse inscribed in the well's rectangle now trace a
 * true circle — no different math, only a different (square, not rectangular) canvas. [active]
 * mirrors `fareState.status == TripStatus.HIRED` exactly as before: brighter/faster while a fare is
 * accruing, dimmer/near-still while STOPPED·WAITING, never fully stopped (a frozen dial reads as
 * broken, not paused).
 */
@Composable
private fun MeterDialGlow(active: Boolean, modifier: Modifier = Modifier) {
    val sweepAngle by rememberInfiniteTransition(label = "meter-dial-sweep").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (active) 6000 else 22000, easing = LinearEasing)),
        label = "meter-dial-sweep-angle",
    )
    val glowStrength by animateFloatAsState(
        targetValue = if (active) 1f else 0.35f,
        animationSpec = tween(500),
        label = "meter-dial-glow-strength",
    )
    val pulse by rememberInfiniteTransition(label = "meter-dial-pulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "meter-dial-pulse-v",
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
        val ringRadius = kotlin.math.min(w, h) / 2f - strokeW
        drawCircle(color = CaptainPalette.panelBorder, radius = ringRadius, center = Offset(cx, cy), style = Stroke(width = strokeW))
        val inset = strokeW * 1.5f
        val rectSize = androidx.compose.ui.geometry.Size(w - inset * 2, h - inset * 2)
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
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = CaptainPalette.textMuted)
        Text(
            value,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Medium,
            fontSize = 26.sp,
            color = if (highlight) CaptainPalette.success else CaptainPalette.warning,
        )
    }
}

// ============================================================================================
// Vertical action stack (Phase A step 5) — SET PRICE / ADD TOLL / PAUSE FARE / MORE
// ============================================================================================

@Composable
private fun MeterActionStack(
    isPaused: Boolean,
    negotiatedTotal: String?,
    tollsTotal: BigDecimal,
    onSetPrice: () -> Unit,
    onAddToll: () -> Unit,
    onTogglePause: () -> Unit,
    onMore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MeterActionButton(
            icon = Icons.Rounded.Sell,
            label = "SET PRICE",
            // Honest status line, not a fake "tap to edit" — see SetPriceInfoDialog's doc for why
            // this button is informational only during an active trip.
            value = if (negotiatedTotal != null) "Fixed fare — ${formatNegotiatedTotal(negotiatedTotal)}" else "Metered fare",
            onClick = onSetPrice,
        )
        MeterActionButton(
            icon = Icons.Rounded.ConfirmationNumber,
            label = "ADD TOLL",
            value = "${tollsTotal.toMoneyString()} added so far",
            onClick = onAddToll,
        )
        MeterActionButton(
            icon = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            label = if (isPaused) "RESUME FARE" else "PAUSE FARE",
            value = if (isPaused) "Waiting — tap to resume metering" else "Tap when the passenger stops the trip",
            accentColor = if (isPaused) CaptainPalette.success else CaptainPalette.warning,
            onClick = onTogglePause,
        )
        MeterActionButton(
            icon = Icons.Rounded.MoreHoriz,
            label = "MORE",
            value = "Extras · passenger count · speech",
            onClick = onMore,
        )
    }
}

@Composable
private fun MeterActionButton(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    accentColor: Color = CaptainPalette.accent,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accentColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
            Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
            Text(
                value,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(22.dp))
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
    Column(
        modifier = Modifier
            .width(480.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Add toll", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
        Text(
            "Tolls so far: ${tollsTotal.toMoneyString()}",
            fontFamily = InterFamily,
            fontSize = 14.sp,
            color = CaptainPalette.textSecondary,
        )
        TollPresets.ALL.forEach { preset ->
            CaptainChip(preset.label.uppercase(), preset.amount.toMoneyString(), modifier = Modifier.fillMaxWidth()) {
                onAddPreset(preset)
            }
        }
        CaptainButton(text = "Custom amount…", outline = true, modifier = Modifier.fillMaxWidth()) { onCustom() }
        CaptainButton(text = "Close", outline = true, modifier = Modifier.fillMaxWidth()) { onDismiss() }
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
// Trip Details card (Phase A step 4)
// ============================================================================================

@Composable
private fun TripDetailsCard(tripContext: TripContext?, fareState: FareState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(18.dp))
            .padding(20.dp),
    ) {
        Text("TRIP DETAILS", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = CaptainPalette.textPrimary)
        Spacer(Modifier.height(12.dp))
        DetailRow("Trip ID", tripContext?.clientUuid?.take(8)?.uppercase() ?: "—")
        DetailRow("Pickup", tripContext?.originAddress ?: "—")
        DetailRow("Drop-off", tripContext?.destAddress ?: "—")
        DetailRow("Distance", fareState.distanceKm.setScale(1, RoundingMode.HALF_UP).toPlainString() + " km")
        val totalSeconds = fareState.movingSeconds + fareState.waitingSeconds
        DetailRow("Duration", "%d:%02d".format(totalSeconds / 60, totalSeconds % 60))
        val avgSpeedKmh = if (fareState.movingSeconds > 0) {
            (fareState.distanceKm.toDouble() / (fareState.movingSeconds / 3600.0)).roundToInt()
        } else {
            0
        }
        DetailRow("Avg speed", "$avgSpeedKmh km/h")
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = CaptainPalette.textSecondary)
        Text(
            value,
            fontFamily = RobotoMonoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = CaptainPalette.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false),
        )
    }
}

// ============================================================================================
// Fare Breakdown card (Phase A step 3) — revives HiredViewModel.breakdownExpanded/toggleBreakdown()
// ============================================================================================

@Composable
private fun FareBreakdownCard(
    breakdown: FareBreakdown,
    timeClass: TimeClass,
    nightMultiplierLabel: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(18.dp))
            .padding(20.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("FARE BREAKDOWN", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = CaptainPalette.textPrimary)
            Spacer(Modifier.weight(1f))
            val chevronRotation by animateFloatAsState(if (expanded) 90f else -90f, label = "breakdown-chevron")
            Row(
                modifier = Modifier.clickable(onClick = onToggle).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (expanded) "HIDE" else "SHOW",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = CaptainPalette.accent,
                )
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = CaptainPalette.accent,
                    modifier = Modifier.size(18.dp).padding(start = 4.dp).rotate(chevronRotation),
                )
            }
        }
        AnimatedVisibility(visible = expanded, enter = fadeIn(tween(180)), exit = fadeOut(tween(140))) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                BreakdownRow("Base Fare", breakdown.flagFall.toMoneyString())
                BreakdownRow("Distance", breakdown.distanceAmount.toMoneyString())
                BreakdownRow("Time", breakdown.waitingAmount.toMoneyString())
                // Informational only: the night-rate uplift is already baked into Distance/Time
                // above (FareEngineImpl applies the night per-km/per-min rate directly — there is
                // no separate night-surcharge line item to show), so this never adds to `total`
                // itself, only explains the higher Distance/Time figures when it applies.
                if (timeClass == TimeClass.NIGHT) {
                    BreakdownRow("Night Fare (${nightMultiplierLabel ?: "—"})", "included above")
                }
                if (breakdown.peakAmount.signum() > 0) {
                    BreakdownRow("Peak Hiring", breakdown.peakAmount.toMoneyString())
                }
                BreakdownRow("Tolls", breakdown.tolls.toMoneyString())
                BreakdownRow("Levy & Charges", breakdown.psl.toMoneyString())
                if (breakdown.extras.signum() > 0) {
                    BreakdownRow("Extras", breakdown.extras.toMoneyString())
                }
                Box(Modifier.fillMaxWidth().height(1.dp).padding(vertical = 10.dp).background(CaptainPalette.panelBorder))
                BreakdownRow("Total", breakdown.total.toMoneyString(), emphasized = true)
            }
        }
    }
}

@Composable
private fun BreakdownRow(label: String, value: String, emphasized: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
            fontSize = if (emphasized) 16.sp else 14.sp,
            color = if (emphasized) CaptainPalette.textPrimary else CaptainPalette.textSecondary,
        )
        Text(
            value,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (emphasized) 20.sp else 15.sp,
            color = if (emphasized) CaptainPalette.success else CaptainPalette.textPrimary,
        )
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
