package au.com.threesixty.cabdispatch.ui.screens.hired

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.domain.DuressUiState
import au.com.threesixty.cabdispatch.domain.TollPreset
import au.com.threesixty.cabdispatch.domain.TollPresets
import au.com.threesixty.cabdispatch.domain.TripStatus
import au.com.threesixty.cabdispatch.domain.toMoneyString
import au.com.threesixty.cabdispatch.ui.deck.DeckButton
import au.com.threesixty.cabdispatch.ui.deck.DeckButtonKind
import au.com.threesixty.cabdispatch.ui.deck.DeckKeypad
import au.com.threesixty.cabdispatch.ui.deck.rememberDeckClock
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.overlays.DuressActiveBanner
import au.com.threesixty.cabdispatch.ui.overlays.DuressTriggeredOverlay
import au.com.threesixty.cabdispatch.ui.overlays.HiddenDuressGestureZone
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.Deck
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
 * differ by: amber pill/top-border, amber fare numerals, WAITING column highlighted, and the
 * wait button flipping to RESUME. The frame's "✚ EXTRAS" opens an honest notice — the live
 * engine has no extras input (extras exist only in the close-time fare reconstruction), so the
 * button explains that instead of faking a charge. Toll chips carry the app's REAL preset
 * amounts ([TollPresets]), not the frame's illustrative figures; "+ TOLL…" opens a custom-amount
 * pad. The previous version's cosmetic $0→flagfall ramp was dropped in this port (the METER
 * STARTED banner stays); fare numerals now bind the engine total directly.
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

    LaunchedEffect(viewModel.isNewTripStart) {
        if (viewModel.isNewTripStart) {
            showStartedBanner = true
            kotlinx.coroutines.delay(2000)
            showStartedBanner = false
        }
    }

    val stateColor = if (isPaused) Deck.stopped else Deck.hired
    val ledColor = if (isPaused) Deck.ledAmber else Deck.ledGreen

    Box(modifier = Modifier.fillMaxSize().background(Deck.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // --- topBar (56dp, state-colored bottom border) ---
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .background(Deck.panel)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        rememberDeckClock(),
                        fontFamily = RobotoMonoFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = Deck.textSecondary,
                    )
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
                            color = if (isPaused) Deck.onStopped else Color.White,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            "${fareState.band.label.uppercase()} — ${fareState.timeClass.label.uppercase()} · SIGNED ✓",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Deck.ledAmber,
                        )
                        val gpsOk = AppContainer.speedSource.locationFix.collectAsState().value != null
                        Text(
                            "GPS ●",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (gpsOk) Deck.forHire else Deck.hired,
                        )
                        Text(
                            if (speechEnabled) "🔊" else "🔇",
                            fontSize = 15.sp,
                            modifier = Modifier.clickable { viewModel.toggleSpeech(!speechEnabled) },
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(stateColor))
            }

            // --- meterWell ---
            Box(
                modifier = Modifier
                    .padding(horizontal = 64.dp)
                    .padding(top = 26.dp)
                    .fillMaxWidth()
                    .height(400.dp)
                    .clip(RoundedCornerShape(Deck.R_XL.dp))
                    .background(Deck.inset)
                    .border(1.5.dp, Deck.strokeSubtle, RoundedCornerShape(Deck.R_XL.dp)),
            ) {
                Text(
                    "F A R E",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = 6.sp,
                    color = Deck.textMuted,
                    modifier = Modifier.padding(start = 38.dp, top = 26.dp),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 20.dp, end = 34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Deck.ledAmber.copy(alpha = 0.12f))
                        .border(1.dp, Deck.ledAmber.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (fareState.band.label.endsWith("2")) "T2" else "T1",
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Medium,
                        fontSize = 20.sp,
                        color = Deck.ledAmber,
                    )
                }
                val totalText = fareState.total.toMoneyString()
                Text(
                    totalText,
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (totalText.length > 7) 128.sp else 158.sp,
                    color = ledColor,
                    modifier = Modifier.align(Alignment.Center).padding(bottom = 30.dp),
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
                ChargeChip("EXTRAS", fareState.breakdown.extras.toMoneyString())
                ChargeChip("LEVY (PSL)", fareState.breakdown.psl.toMoneyString())
                ChargeChip("TOLLS", fareState.breakdown.tolls.toMoneyString())
                Box(Modifier.width(2.dp).height(36.dp).background(Deck.strokeSubtle))
                TollAddChip("+ ${TollPresets.M5.label.uppercase()} ${TollPresets.M5.amount.toMoneyString()}") {
                    viewModel.addToll(TollPresets.M5)
                }
                TollAddChip("+ HARBOUR ${TollPresets.HARBOUR_SOUTHBOUND.amount.toMoneyString()}") {
                    viewModel.addToll(TollPresets.HARBOUR_SOUTHBOUND)
                }
                TollAddChip("+ ${TollPresets.AIRPORT.label.uppercase()} ${TollPresets.AIRPORT.amount.toMoneyString()}") {
                    viewModel.addToll(TollPresets.AIRPORT)
                }
                TollAddChip("+ TOLL…") { showTollPad = true }
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
                        .clip(RoundedCornerShape(Deck.R_LG.dp))
                        .background(if (isPaused) Deck.forHire else Deck.stopped)
                        .clickable { viewModel.togglePause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (isPaused) "▶ RESUME — METERED" else "⏸ STOP — WAITING",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp,
                        color = if (isPaused) Deck.onForHire else Deck.onStopped,
                    )
                }
                DeckButton(
                    text = "✚ EXTRAS",
                    kind = DeckButtonKind.Outline,
                    heightDp = 92,
                    fontSize = 21,
                    modifier = Modifier.width(220.dp),
                ) { showExtrasNote = true }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(92.dp)
                        .clip(RoundedCornerShape(Deck.R_LG.dp))
                        .background(Deck.hired)
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
                        color = Color.White,
                    )
                }
            }
            Text(
                "One of distance or waiting accrues at a time — switches automatically at 26 km/h",
                fontFamily = InterFamily,
                fontSize = 13.sp,
                color = Deck.textMuted,
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
                    .background(Deck.forHire)
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            ) {
                Text(
                    "● METER STARTED",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Deck.onForHire,
                )
            }
        }

        // Custom toll amount pad
        if (showTollPad) {
            CustomTollDialog(
                onDismiss = { showTollPad = false },
                onConfirm = { amount ->
                    showTollPad = false
                    viewModel.addToll(TollPreset("custom", "Custom toll", amount))
                },
            )
        }
        if (showExtrasNote) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { showExtrasNote = false },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .width(560.dp)
                        .clip(RoundedCornerShape(Deck.R_XL.dp))
                        .background(Deck.panel)
                        .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(Deck.R_XL.dp))
                        .padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("Extras", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Deck.textPrimary)
                    Text(
                        "No chargeable extras are configured for this fleet yet — extras (e.g. cleaning fee) " +
                            "are applied at Close & Pay where they exist. Tolls have their own chips above.",
                        fontFamily = InterFamily,
                        fontSize = 15.sp,
                        color = Deck.textSecondary,
                    )
                    DeckButton(text = "OK", kind = DeckButtonKind.Outline, modifier = Modifier.width(180.dp)) {
                        showExtrasNote = false
                    }
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

@Composable
private fun MeterDatum(label: String, value: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Deck.textMuted)
        Text(
            value,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Medium,
            fontSize = 40.sp,
            color = if (highlight) Deck.ledGreen else Deck.ledAmber,
        )
    }
}

@Composable
private fun ChargeChip(label: String, value: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Deck.panel)
            .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Deck.textMuted)
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Medium, fontSize = 22.sp, color = Deck.textPrimary)
    }
}

@Composable
private fun TollAddChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Deck.card)
            .border(1.dp, Deck.strokeStrong, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Deck.info)
    }
}

@Composable
private fun CustomTollDialog(onDismiss: () -> Unit, onConfirm: (BigDecimal) -> Unit) {
    var cents by remember { mutableStateOf("") }
    val amount = if (cents.isEmpty()) BigDecimal.ZERO else BigDecimal(cents).movePointLeft(2)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(Deck.R_XL.dp))
                .background(Deck.panel)
                .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(Deck.R_XL.dp))
                .clickable(enabled = false) {}
                .padding(30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Add toll", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Deck.textPrimary)
            Box(
                modifier = Modifier
                    .width(448.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(Deck.R_MD.dp))
                    .background(Deck.inset),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    amount.toMoneyString(),
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 38.sp,
                    color = Deck.ledGreen,
                )
            }
            DeckKeypad(
                onDigit = { d -> if (cents.length < 5) cents += d },
                onBackspace = { cents = cents.dropLast(1) },
                onClear = { cents = "" },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DeckButton(text = "Cancel", kind = DeckButtonKind.Outline, modifier = Modifier.weight(1f), onClick = onDismiss)
                DeckButton(
                    text = "Add toll",
                    kind = DeckButtonKind.Primary,
                    enabled = amount > BigDecimal.ZERO,
                    modifier = Modifier.weight(1.4f),
                ) { onConfirm(amount) }
            }
        }
    }
}
