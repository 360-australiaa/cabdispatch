package au.com.threesixty.cabdispatch.ui.screens.login

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.ui.deck.DeckButton
import au.com.threesixty.cabdispatch.ui.deck.DeckButtonKind
import au.com.threesixty.cabdispatch.ui.deck.DeckKey
import au.com.threesixty.cabdispatch.ui.deck.DeckKeypad
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily

/**
 * S1 — the four-step sign-on flow, Command Deck v2 port (Figma `h0PSsXQ971dOJvt25tN7BA` frames
 * `9:31` 04·Driver Login, `9:79` 05·MFA Code, `10:91` 06·Vehicle Bind, `10:111` 07·Pre-Shift
 * Inspection). All state/logic lives unchanged in [LoginVehicleBindViewModel] — this file is the
 * visual layer over the same [LoginStep] machine (with the MFA challenge rendered whenever
 * [LoginVehicleBindUiState.mfaToken] is set, exactly as before).
 *
 * One deliberate deviation from the Figma login frame: it draws only the numeric `c/keypad`, but
 * real driver codes are alphanumeric (`GL2HY`). When the DRIVER # field is focused the keypad
 * slot swaps to a compact A–Z+digit grid in the same visual language; focusing PIN restores the
 * design's exact numeric pad. Nothing else on the frame changes.
 */
@Composable
fun LoginVehicleBindScreen(
    navController: NavHostController,
    viewModel: LoginVehicleBindViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Deck.canvas)) {
        when {
            state.mfaToken != null -> MfaStep(state, viewModel)
            state.step == LoginStep.DRIVER_LOGIN -> DriverLoginStep(state, viewModel)
            state.step == LoginStep.VEHICLE_BIND -> VehicleBindStep(state, viewModel)
            state.step == LoginStep.INSPECTION -> InspectionStep(state, viewModel) {
                navController.navigate(CabDispatchRoutes.SHIFT_START)
            }
        }
    }
}

// --- shared field shells ---------------------------------------------------------------------

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Deck.textMuted)
}

/** 420×68 input shell (Figma 9:39/9:43): card bg, strokeStrong border — yellow when focused. */
@Composable
private fun InputShell(
    focused: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Deck.R_MD.dp)
    Box(
        modifier = modifier
            .height(68.dp)
            .clip(shape)
            .background(Deck.card)
            .border(if (focused) 2.dp else 1.5.dp, if (focused) Deck.yellow else Deck.strokeStrong, shape)
            .clickable(onClick = onClick)
            .padding(start = 20.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

// --- 04 · Driver Login -----------------------------------------------------------------------

private enum class LoginField { DRIVER_ID, PIN }

@Composable
private fun DriverLoginStep(state: LoginVehicleBindUiState, viewModel: LoginVehicleBindViewModel) {
    var focusedField by remember { mutableStateOf(LoginField.DRIVER_ID) }

    Row(modifier = Modifier.fillMaxSize().padding(start = 96.dp, end = 96.dp, top = 110.dp, bottom = 60.dp)) {
        // Left — brand row, fields, hint, error, Cancel.
        Column(modifier = Modifier.width(420.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Deck.yellow),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("CD", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Deck.onYellow)
                }
                Text("Driver sign-in", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, color = Deck.textPrimary)
            }
            Spacer(Modifier.height(28.dp))
            FieldLabel("DRIVER #")
            Spacer(Modifier.height(8.dp))
            InputShell(focused = focusedField == LoginField.DRIVER_ID, modifier = Modifier.width(420.dp), onClick = { focusedField = LoginField.DRIVER_ID }) {
                Text(
                    text = state.driverIdInput.ifEmpty { " " },
                    fontFamily = RobotoMonoFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 24.sp,
                    color = Deck.textPrimary,
                )
            }
            Spacer(Modifier.height(24.dp))
            FieldLabel("PIN")
            Spacer(Modifier.height(8.dp))
            InputShell(focused = focusedField == LoginField.PIN, modifier = Modifier.width(420.dp), onClick = { focusedField = LoginField.PIN }) {
                Text(
                    text = if (state.pinInput.isEmpty()) " " else "• ".repeat(state.pinInput.length).trim(),
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 24.sp,
                    color = Deck.yellow,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "PIN is numeric only — verified by the fleet server, cached 7 days for offline sign-in.",
                fontFamily = InterFamily,
                fontSize = 15.sp,
                color = Deck.textMuted,
                modifier = Modifier.width(420.dp),
            )
            state.loginError?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Deck.hired)
            }
            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "QUICK LOGIN (DEMO DRIVER)",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Deck.info,
                    modifier = Modifier.clickable { viewModel.quickLoginDemoDriver() },
                )
            }
            Spacer(Modifier.weight(1f))
            DeckButton(text = "Cancel", kind = DeckButtonKind.Ghost, modifier = Modifier.width(200.dp)) {
                viewModel.onDriverIdChanged("")
                viewModel.onPinChanged("")
            }
        }
        Spacer(Modifier.weight(1f))
        // Right — keypad slot (448 wide) + Sign In.
        Column {
            if (focusedField == LoginField.PIN) {
                DeckKeypad(
                    onDigit = { d -> viewModel.onPinChanged(state.pinInput + d) },
                    onBackspace = { viewModel.onPinChanged(state.pinInput.dropLast(1)) },
                    onClear = { viewModel.onPinChanged("") },
                )
            } else {
                AlphaNumPad(
                    onKey = { c -> viewModel.onDriverIdChanged(state.driverIdInput + c) },
                    onBackspace = { viewModel.onDriverIdChanged(state.driverIdInput.dropLast(1)) },
                    onClear = { viewModel.onDriverIdChanged("") },
                )
            }
            Spacer(Modifier.weight(1f))
            DeckButton(
                text = if (state.isLoggingIn) "Signing in…" else "Sign In",
                kind = DeckButtonKind.Primary,
                heightDp = 72,
                enabled = !state.isLoggingIn,
                modifier = Modifier.width(448.dp),
            ) { viewModel.login() }
        }
    }
}

/**
 * Compact alphanumeric pad filling the numeric keypad's 448dp slot — 6 keys per row at 63×54,
 * same card/border/Chakra language as `c/key`, used only while DRIVER # is focused (see the
 * class doc's deviation note).
 */
@Composable
private fun AlphaNumPad(onKey: (Char) -> Unit, onBackspace: () -> Unit, onClear: () -> Unit) {
    val rows = listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ0123", "456789")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c ->
                    Box(
                        modifier = Modifier
                            .width(66.dp)
                            .height(54.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Deck.card)
                            .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(10.dp))
                            .clickable { onKey(c) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(c.toString(), fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Deck.textPrimary)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .width(219.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Deck.card)
                    .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(10.dp))
                    .clickable(onClick = onBackspace),
                contentAlignment = Alignment.Center,
            ) {
                Text("⌫", fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Deck.stopped)
            }
            Box(
                modifier = Modifier
                    .width(219.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Deck.card)
                    .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(10.dp))
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Text("CLR", fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Deck.stopped)
            }
        }
    }
}

// --- 05 · MFA Code ---------------------------------------------------------------------------

@Composable
private fun MfaStep(state: LoginVehicleBindUiState, viewModel: LoginVehicleBindViewModel) {
    Row(modifier = Modifier.fillMaxSize().padding(start = 96.dp, end = 96.dp, top = 110.dp, bottom = 60.dp)) {
        Column(modifier = Modifier.width(460.dp)) {
            Text("Two-factor check", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, color = Deck.textPrimary)
            Spacer(Modifier.height(24.dp))
            Text(
                "This driver account has MFA enabled. Enter the 6-digit code from your authenticator app.",
                fontFamily = InterFamily,
                fontSize = 17.sp,
                color = Deck.textSecondary,
                modifier = Modifier.width(420.dp),
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(6) { i ->
                    val ch = state.mfaCodeInput.getOrNull(i)?.toString()
                    val isCursor = state.mfaCodeInput.length == i
                    val shape = RoundedCornerShape(Deck.R_MD.dp)
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(80.dp)
                            .clip(shape)
                            .background(Deck.card)
                            .border(if (isCursor) 2.dp else 1.5.dp, if (isCursor) Deck.yellow else Deck.strokeStrong, shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        ch?.let { Text(it, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, color = Deck.textPrimary) }
                    }
                }
            }
            state.loginError?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Deck.hired)
            }
            Spacer(Modifier.weight(1f))
            DeckButton(text = "Back", kind = DeckButtonKind.Ghost, modifier = Modifier.width(200.dp)) {
                viewModel.cancelMfaChallenge()
            }
        }
        Spacer(Modifier.weight(1f))
        Column {
            DeckKeypad(
                onDigit = { d -> if (state.mfaCodeInput.length < 6) viewModel.onMfaCodeChanged(state.mfaCodeInput + d) },
                onBackspace = { viewModel.onMfaCodeChanged(state.mfaCodeInput.dropLast(1)) },
                onClear = { viewModel.onMfaCodeChanged("") },
            )
            Spacer(Modifier.weight(1f))
            DeckButton(
                text = if (state.isLoggingIn) "Verifying…" else "Verify",
                kind = DeckButtonKind.Primary,
                heightDp = 72,
                enabled = !state.isLoggingIn,
                modifier = Modifier.width(448.dp),
            ) { viewModel.verifyMfaCode() }
        }
    }
}

// --- 06 · Vehicle Bind -----------------------------------------------------------------------

@Composable
private fun VehicleBindStep(state: LoginVehicleBindUiState, viewModel: LoginVehicleBindViewModel) {
    // Real QR scan (2026-08-28) needs an Activity to host its scan UI — this app is
    // single-activity (see MainActivity's own doc), so LocalContext.current always is one here.
    val activity = LocalContext.current as android.app.Activity
    Column(modifier = Modifier.fillMaxSize().padding(start = 72.dp, end = 72.dp, top = 64.dp, bottom = 48.dp)) {
        Text("Bind to vehicle", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, color = Deck.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Scan the QR on the dash, or type the rego to pair this tablet with the vehicle.",
            fontFamily = InterFamily,
            fontSize = 17.sp,
            color = Deck.textSecondary,
        )
        Spacer(Modifier.height(44.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // QR zone — dashed 400×420 panel. Tapping runs the (stub) scanner, same as before.
            Column(
                modifier = Modifier
                    .width(400.dp)
                    .height(480.dp)
                    .clip(RoundedCornerShape(Deck.R_XL.dp))
                    .background(Deck.panel)
                    .border(2.dp, Deck.strokeStrong, RoundedCornerShape(Deck.R_XL.dp))
                    .clickable { viewModel.scanQr(activity) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier.size(220.dp).clip(RoundedCornerShape(Deck.R_LG.dp)).background(Deck.inset),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("▣", fontSize = 110.sp, color = Deck.textMuted)
                }
                Spacer(Modifier.height(16.dp))
                Text("Point camera at vehicle QR", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Deck.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.qrScanAttempted && state.vehicleIdInput.isBlank()) {
                        "No code detected — tap to try again, or use manual entry"
                    } else {
                        "Pairing code is single-use and expires in 10 minutes"
                    },
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = Deck.textMuted,
                )
            }
            // Manual entry card
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(480.dp)
                    .clip(RoundedCornerShape(Deck.R_XL.dp))
                    .background(Deck.panel)
                    .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(Deck.R_XL.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FieldLabel("OR ENTER REGO MANUALLY")
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .clip(RoundedCornerShape(Deck.R_MD.dp))
                        .background(Deck.card)
                        .border(2.dp, Deck.yellow, RoundedCornerShape(Deck.R_MD.dp))
                        .padding(start = 24.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        state.vehicleIdInput.ifEmpty { " " },
                        fontFamily = RobotoMonoFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 30.sp,
                        color = Deck.textPrimary,
                    )
                }
                // Compact rego keys — letters+digits so any NSW rego is typeable without an IME.
                RegoKeyRows(
                    onKey = { c -> viewModel.onVehicleIdChanged(state.vehicleIdInput + c) },
                    onBackspace = { viewModel.onVehicleIdChanged(state.vehicleIdInput.dropLast(1)) },
                )
                Spacer(Modifier.weight(1f))
                DeckButton(
                    text = "Bind Vehicle",
                    kind = DeckButtonKind.Primary,
                    heightDp = 72,
                    enabled = state.vehicleIdInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { viewModel.bindVehicle() }
            }
        }
    }
}

@Composable
private fun RegoKeyRows(onKey: (Char) -> Unit, onBackspace: () -> Unit) {
    // "-" appended to the digit row — real AU regos are commonly hyphenated (e.g. "KHI-01"), and
    // without this key a driver typing one here could only ever produce a *different* string than
    // what the fleet backend has on file for that vehicle, which then silently 404s every later
    // lookup keyed off rego (found live: a real seeded vehicle "KHI-01", bound here as "KHI01",
    // 404ing `POST /v1/fleet/positions` on every heartbeat).
    val rows = listOf("ABCDEFGHI", "JKLMNOPQR", "STUVWXYZ⌫", "0123456789-")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Deck.card)
                            .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(8.dp))
                            .clickable { if (c == '⌫') onBackspace() else onKey(c) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            c.toString(),
                            fontFamily = ChakraPetch,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            color = if (c == '⌫') Deck.stopped else Deck.textPrimary,
                        )
                    }
                }
            }
        }
    }
}

// --- 07 · Pre-Shift Inspection ---------------------------------------------------------------

/** Emoji + sub-label per checklist key (titles come from [PRE_SHIFT_CHECKLIST_ITEMS]). */
private val CHECK_META = mapOf(
    "tyres" to ("🛞" to "Condition and pressure OK"),
    "lights" to ("💡" to "All working"),
    "brakes" to ("🛑" to "Feel normal"),
    "meter_tablet" to ("📱" to "Secure in mount"),
    "duress" to ("🆘" to "Reachable & test light OK"),
    "interior" to ("🧼" to "Clean, no damage"),
    "cameras" to ("📷" to "Unobstructed"),
    "fare_card" to ("🪪" to "Visible to passengers"),
    "first_aid" to ("🧯" to "Present and in date"),
)

@Composable
private fun InspectionStep(
    state: LoginVehicleBindUiState,
    viewModel: LoginVehicleBindViewModel,
    onShiftStarted: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(start = 72.dp, end = 72.dp, top = 48.dp, bottom = 44.dp)) {
        Text("Pre-shift safety inspection", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = Deck.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Required under your Safety Management System (cl 14). Tick each item before starting the shift." +
                (state.boundVehicleId?.let { " Vehicle $it." } ?: ""),
            fontFamily = InterFamily,
            fontSize = 16.sp,
            color = Deck.textSecondary,
        )
        Spacer(Modifier.height(28.dp))
        PRE_SHIFT_CHECKLIST_ITEMS.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                rowItems.forEach { (key, title) ->
                    val checked = state.checklist[key] == true
                    val (emoji, sub) = CHECK_META[key] ?: ("✔" to "")
                    CheckCard(
                        emoji = emoji,
                        title = title,
                        sub = sub,
                        checked = checked,
                        modifier = Modifier.weight(1f),
                    ) { viewModel.toggleChecklistItem(key) }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        state.shiftError?.let {
            Text(it, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Deck.hired)
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.weight(1f))
        Row {
            DeckButton(text = "⚠ Report a defect", kind = DeckButtonKind.Outline, modifier = Modifier.width(280.dp)) {
                // Defect reporting posts through the messages channel on the shift screen today —
                // here it simply blocks the green CTA path by leaving items unticked.
            }
            Spacer(Modifier.weight(1f))
            DeckButton(
                text = if (state.isStartingShift) "Starting…" else "All checks passed — Continue",
                kind = DeckButtonKind.Success,
                heightDp = 72,
                enabled = state.allChecklistItemsChecked && !state.isStartingShift,
                modifier = Modifier.width(440.dp),
            ) { viewModel.startShift(onShiftStarted) }
        }
    }
}

@Composable
private fun CheckCard(
    emoji: String,
    title: String,
    sub: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(Deck.R_LG.dp)
    Row(
        modifier = modifier
            .height(112.dp)
            .clip(shape)
            .background(Deck.panel)
            .border(1.5.dp, if (checked) Deck.forHire.copy(alpha = 0.45f) else Deck.strokeSubtle, shape)
            .clickable(onClick = onToggle)
            .padding(start = 18.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(emoji, fontSize = 26.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Deck.textPrimary)
            Spacer(Modifier.height(3.dp))
            Text(sub, fontFamily = InterFamily, fontSize = 13.sp, color = Deck.textMuted)
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (checked) Deck.forHire else Deck.raised),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Deck.onForHire)
            }
        }
    }
}
