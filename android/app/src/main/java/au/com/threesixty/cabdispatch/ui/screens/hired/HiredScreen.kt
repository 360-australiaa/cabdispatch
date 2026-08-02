package au.com.threesixty.cabdispatch.ui.screens.hired

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.AccrualMode
import au.com.threesixty.cabdispatch.domain.FareBreakdown
import au.com.threesixty.cabdispatch.domain.TariffBand
import au.com.threesixty.cabdispatch.domain.TimeClass
import au.com.threesixty.cabdispatch.domain.TollPreset
import au.com.threesixty.cabdispatch.domain.TollPresets
import au.com.threesixty.cabdispatch.domain.DuressUiState
import au.com.threesixty.cabdispatch.domain.TripStatus
import au.com.threesixty.cabdispatch.domain.toMoneyString
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.overlays.DuressActiveBanner
import au.com.threesixty.cabdispatch.ui.overlays.DuressTriggeredOverlay
import au.com.threesixty.cabdispatch.ui.overlays.HiddenDuressGestureZone
import au.com.threesixty.cabdispatch.ui.theme.WheelColors
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * S3 — Hired / active-fare meter. Restyled to match the reference prototype's
 * "physical taxi meter" look (spec TCT-DRIVER-APP-01.md §6, driver-dashboard-
 * full-prototype.html `#meter`): LED-style fare digits, mode/tariff pills, a
 * once-per-trip fare-ramp + "METER STARTED" banner, and toll chips that are
 * for-real wired into the persisted trip (see [HiredViewModel.doPersistTick]
 * / [au.com.threesixty.cabdispatch.data.repository.TripRepository.tick] — the
 * chips themselves were already calling [HiredViewModel.addToll] before this
 * pass, but nothing carried that total into the Room row S4/S5 actually
 * charge/report from; that's the real gap this pass closes, not the click
 * handler).
 *
 * Deliberately does NOT port the prototype's random distance/waiting
 * mode-switching (`Math.random() > 0.35` in the JS) — that's explicitly
 * flagged as demo-only fakery in spec §11. [ModePill] here is driven by the
 * real fare engine's [AccrualMode], itself driven by actual GPS speed vs the
 * 26 km/h threshold (see `domain/FareEngine.kt`'s `tick()`).
 */
@Composable
fun HiredScreen(
    navController: NavHostController,
    viewModel: HiredViewModel = viewModel(),
) {
    val fareState by viewModel.fareState.collectAsState()
    val speechEnabled by viewModel.speechEnabled.collectAsState()
    val breakdownExpanded by viewModel.breakdownExpanded.collectAsState()
    val duressState by viewModel.duressState.collectAsState()

    // --- Fare-ramp ($0 -> flag fall over 500ms) + "METER STARTED" banner ---
    // Runs exactly once per trip (gated on [HiredViewModel.isNewTripStart],
    // fixed at ViewModel construction — see its doc), per spec §6 step 3:
    // ramp completes, banner shows (~2s), then fades. `rampTargetTotal` is
    // captured once via `remember` (no keys) — it's the engine's initial
    // total (flag fall + any peak charge/PSL applied at startTrip()), which
    // is already correct the instant the trip opens; the ramp is a purely
    // cosmetic reveal of a number the engine has already committed to, not a
    // recomputation.
    val rampTargetTotal = remember { fareState.total }
    val rampProgress = remember { Animatable(0f) }
    var showStartedBanner by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.isNewTripStart) {
        if (viewModel.isNewTripStart) {
            rampProgress.animateTo(1f, animationSpec = tween(durationMillis = 500))
            showStartedBanner = true
            delay(2000)
            showStartedBanner = false
        } else {
            // Re-entered this screen without a fresh trip hand-off (e.g.
            // process/config-change recomposition) — show the real total
            // immediately, no ramp/banner replay.
            rampProgress.snapTo(1f)
        }
    }

    val isPaused = fareState.status == TripStatus.STOPPED
    val displayedTotal = if (rampProgress.value < 1f) {
        rampTargetTotal.multiply(BigDecimal.valueOf(rampProgress.value.toDouble()))
            .setScale(2, RoundingMode.HALF_UP)
    } else {
        fareState.total
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WheelColors.bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TariffPill(band = fareState.band, timeClass = fareState.timeClass)
                ModePill(mode = fareState.mode, speedKmh = fareState.currentSpeedKmh, paused = isPaused)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "FARE SO FAR",
                        color = WheelColors.textSecondary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LedFareDigits(amount = displayedTotal, dimmed = isPaused)
                    Spacer(modifier = Modifier.height(18.dp))
                    FareSummaryLine(fareState.breakdown)
                    Spacer(modifier = Modifier.height(16.dp))
                    BreakdownDrawer(
                        expanded = breakdownExpanded,
                        onToggle = viewModel::toggleBreakdown,
                        breakdown = fareState.breakdown,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        "ADD TOLL",
                        color = WheelColors.textSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TollPresets.ALL.forEach { preset ->
                            TollChip(preset = preset, onClick = { viewModel.addToll(preset) })
                        }
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PauseButton(paused = isPaused, onClick = viewModel::togglePause)
                    StopMeterButton(
                        onClick = {
                            viewModel.endTrip {
                                navController.navigate(CabDispatchRoutes.CLOSE_PAY)
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            SpeechToggleRow(speechEnabled, viewModel::toggleSpeech)
        }

        AnimatedVisibility(
            visible = showStartedBanner,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
            enter = fadeIn(),
            exit = fadeOut(animationSpec = tween(durationMillis = 400)),
        ) {
            MeterStartedBanner()
        }

        // Hidden triple-tap-corner duress gesture — deliberately no visible
        // affordance, per spec B5 S3 ("hidden duress gesture") / §6 step 8.
        // Shared implementation (see ui/overlays/DuressOverlays.kt) — also used by
        // WheelDashboardScreen so the gesture is live outside an active trip too (spec §2:
        // "active throughout").
        HiddenDuressGestureZone(
            modifier = Modifier.align(Alignment.TopEnd),
            onTriggered = viewModel::onDuressTriggered,
        )

        // Settings (S6) must be reachable from anywhere (spec B5). Kept
        // deliberately small/low-contrast in the opposite corner from the
        // duress hit-target so it doesn't compete with this screen's
        // distraction-minimal running-fare display.
        Text(
            "⚙",
            color = WheelColors.textSecondary,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .align(Alignment.TopStart)
                .clickable { navController.navigate(CabDispatchRoutes.SETTINGS) }
                .padding(12.dp),
        )

        // Rows 29/30 — "Duress triggered"/"Duress active" contextual overlays (spec §8), driven
        // by AppContainer.duressController via HiredViewModel.duressState — see
        // ui/overlays/DuressOverlays.kt's doc for why these are two different presentations
        // (full-screen confirmation vs. a persistent non-modal banner) rather than one.
        when (val d = duressState) {
            is DuressUiState.Active -> DuressActiveBanner(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 52.dp),
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
 * LED-style fare digits (spec §6 step 5): monospace + glowing white + glow, as an
 * explicit fallback for a true 7-segment font (none available/licensed — see
 * spec §11's own caveat; do not block on sourcing one). Originally the
 * prototype's LED red ([WheelColors.meterLedRed]); switched to
 * [WheelColors.meterLedWhite] and sized up for back-seat-passenger legibility
 * — white glows more prominently against the dark dashboard than red at a
 * glance, and the passenger reading this is often at an angle/distance, not
 * looking straight at the tablet. The glow is a single [Shadow] on the
 * [TextStyle] — Compose's nearest equivalent to CSS `text-shadow` for a
 * `Text` composable, matching what the prototype's CSS
 * (`text-shadow:0 0 18px ..., 0 0 4px ...`) achieves, just brighter/wider to
 * suit white-on-dark rather than red-on-dark. Dims (per spec §6 step 6,
 * "frozen fare shown dimmer") when [dimmed] (i.e. paused).
 */
@Composable
private fun LedFareDigits(amount: BigDecimal, dimmed: Boolean) {
    val alpha = if (dimmed) 0.45f else 1f
    Text(
        text = amount.toMoneyString(),
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 104.sp,
            letterSpacing = 2.sp,
            color = WheelColors.meterLedWhite.copy(alpha = alpha),
            shadow = Shadow(
                color = WheelColors.meterLedWhite.copy(alpha = alpha * 0.85f),
                offset = Offset.Zero,
                blurRadius = if (dimmed) 14f else 36f,
            ),
        ),
    )
}

/** Compact single-line running breakdown, matching the prototype's `.fare-breakdown` text under the digits. */
@Composable
private fun FareSummaryLine(breakdown: FareBreakdown) {
    Text(
        text = "Flag fall ${breakdown.flagFall.toMoneyString()} · Distance ${breakdown.distanceAmount.toMoneyString()} · " +
            "Waiting ${breakdown.waitingAmount.toMoneyString()} · Tolls ${breakdown.tolls.toMoneyString()}",
        color = WheelColors.textSecondary,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Green "METER STARTED" confirmation banner (spec §6 step 3 / prototype `.started-banner`). */
@Composable
private fun MeterStartedBanner() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(WheelColors.available)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
        Text(
            "METER STARTED",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Shared pill chrome for [TariffPill]/[ModePill] — matches prototype `.pill`. */
@Composable
private fun PillChip(dotColor: Color, content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(WheelColors.surfaceRaised)
            .border(1.dp, WheelColors.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        content()
    }
}

@Composable
private fun TariffPill(band: TariffBand, timeClass: TimeClass) {
    PillChip(dotColor = WheelColors.available) {
        Text(
            "${band.label} · ${timeClass.label} rate",
            color = WheelColors.textPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Distance/Waiting mode pill — color-coded, driven by the real fare engine's
 * [AccrualMode] and GPS-derived [speedKmh] (never the prototype's random
 * mode-switching demo — see this file's header doc).
 */
@Composable
private fun ModePill(mode: AccrualMode, speedKmh: Double, paused: Boolean) {
    val dotColor = when {
        paused -> WheelColors.textMuted
        mode == AccrualMode.DISTANCE -> WheelColors.available
        else -> WheelColors.waiting
    }
    val label = when {
        paused -> "Paused"
        mode == AccrualMode.DISTANCE -> "Distance mode"
        else -> "Waiting mode"
    }
    PillChip(dotColor = dotColor) {
        Text(
            String.format(Locale.US, "%.0f km/h · %s", speedKmh, label),
            color = WheelColors.textPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Toll quick-add chip (spec §6 step 7 / prototype `.toll-chip`). The click
 * handler itself ([onClick] -> [HiredViewModel.addToll]) already existed
 * before this pass; what's new is that the amount it adds now actually
 * survives to the persisted trip (see [HiredViewModel.doPersistTick]) instead
 * of only updating this screen's live display.
 */
@Composable
private fun TollChip(preset: TollPreset, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(WheelColors.surfaceRaised.copy(alpha = 0.8f))
            .border(1.dp, WheelColors.border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            "+ ${preset.label} ${preset.amount.toMoneyString()}",
            color = WheelColors.textSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** PAUSE/RESUME — freezes accrual via [HiredViewModel.togglePause] (spec §6 step 6). */
@Composable
private fun PauseButton(paused: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .width(200.dp)
            .height(56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = WheelColors.surfaceRaised,
            contentColor = WheelColors.textPrimary,
        ),
        border = BorderStroke(1.dp, WheelColors.borderStrong),
    ) {
        Text(if (paused) "RESUME" else "PAUSE", fontWeight = FontWeight.Bold)
    }
}

/** STOP METER — ends the trip and moves to S4 payment (spec §6 step 6/§7). */
@Composable
private fun StopMeterButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .width(260.dp)
            .height(72.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = WheelColors.gold,
            contentColor = WheelColors.surfaceRaised,
        ),
    ) {
        Text("STOP METER", fontWeight = FontWeight.Black, fontSize = 17.sp)
    }
}

@Composable
private fun BreakdownDrawer(
    expanded: Boolean,
    onToggle: () -> Unit,
    breakdown: FareBreakdown,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WheelColors.surfaceRaised)
            .border(1.dp, WheelColors.border, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Fare breakdown", color = WheelColors.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text(if (expanded) "▲" else "▼", color = WheelColors.textPrimary)
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            BreakdownRow("Flag fall", breakdown.flagFall)
            BreakdownRow("Distance", breakdown.distanceAmount)
            BreakdownRow("Waiting", breakdown.waitingAmount)
            BreakdownRow("Peak", breakdown.peakAmount)
            BreakdownRow("Tolls", breakdown.tolls)
            BreakdownRow("PSL", breakdown.psl)
        }
    }
}

@Composable
private fun BreakdownRow(label: String, amount: BigDecimal) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = WheelColors.textSecondary)
        Text(amount.toMoneyString(), color = WheelColors.textPrimary)
    }
}

@Composable
private fun SpeechToggleRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Spoken fare announcements", color = WheelColors.textSecondary)
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = WheelColors.gold),
        )
    }
}

