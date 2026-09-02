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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CarRepair
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Sos
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.TireRepair
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainDialogScrim
import au.com.threesixty.cabdispatch.ui.theme.CaptainKeypad
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.CaptainPanel
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import kotlinx.coroutines.launch

/**
 * S1 — the four-step sign-on flow, reskinned onto [CaptainPalette]/`CaptainWidgets`
 * (2026-08-29 purple migration pass). All state/logic lives unchanged in
 * [LoginVehicleBindViewModel] — this file is the visual layer over the same [LoginStep] machine
 * (with the MFA challenge rendered whenever [LoginVehicleBindUiState.mfaToken] is set, exactly as
 * before).
 *
 * One deliberate deviation kept from the previous pass: the sign-in keypad is only numeric
 * ([CaptainKeypad]), but real driver codes are alphanumeric (`GL2HY`). When the DRIVER # field is
 * focused the keypad slot swaps to a compact A–Z+digit grid in the same visual language; focusing
 * PIN restores [CaptainKeypad]. Nothing else changes.
 *
 * "Report a defect" (2026-08-29): previously a no-op button (its own comment said defect
 * reporting "posts through the messages channel on the shift screen today"). It now opens a small
 * dialog that captures free-text notes and posts them as a real dispatch message via
 * [AppContainer.messagesRepository] `sendMessage(driverId = null, body = "[Vehicle defect] " +
 * notes)` — the exact call [au.com.threesixty.cabdispatch.ui.screens.messages.MessagesViewModel]
 * already makes for a quick-reply. This is a genuine message, not a fabricated "submitted" claim:
 * failure shows an honest inline error, success shows a brief confirmation and dismisses. The
 * checklist-must-be-ticked gating logic is untouched — this only adds the message side-effect.
 */
@Composable
fun LoginVehicleBindScreen(
    navController: NavHostController,
    viewModel: LoginVehicleBindViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.bg)) {
        when {
            state.mfaToken != null -> MfaStep(state, viewModel)
            state.step == LoginStep.DRIVER_LOGIN -> DriverLoginStep(state, viewModel)
            state.step == LoginStep.VEHICLE_BIND -> VehicleBindStep(state, viewModel)
            state.step == LoginStep.INSPECTION -> InspectionStep(state, viewModel) {
                navController.navigate(CabDispatchRoutes.SHIFT_START)
            }
        }

        // Advisory-only (the shift is already open by the time this can ever show — see
        // LoginVehicleBindUiState.deviceMismatchWarning's doc): a dismissible dialog, not a
        // blocking gate, mirroring ReportDefectDialog's CaptainDialogScrim/CaptainPanel styling
        // below rather than the inline-Text `shiftError` convention (that one predates a
        // successful start and gates the button; this one never does).
        DeviceMismatchWarningDialog(
            message = state.deviceMismatchWarning,
            onDismiss = { viewModel.dismissDeviceMismatchWarning() },
        )
    }
}

// --- shared field shells ---------------------------------------------------------------------

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CaptainPalette.textMuted)
}

/** 420×68 input shell: raised bg, panelBorder border — accent when focused. */
@Composable
private fun InputShell(
    focused: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(68.dp)
            .clip(shape)
            .background(CaptainPalette.raised)
            .border(if (focused) 2.dp else 1.5.dp, if (focused) CaptainPalette.accent else CaptainPalette.panelBorder, shape)
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
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(CaptainPalette.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("CD", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = CaptainPalette.textPrimary)
                }
                Text("Driver sign-in", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, color = CaptainPalette.textPrimary)
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
                    color = CaptainPalette.textPrimary,
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
                    color = CaptainPalette.accent,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "PIN is numeric only — verified by the fleet server, cached 7 days for offline sign-in.",
                fontFamily = InterFamily,
                fontSize = 15.sp,
                color = CaptainPalette.textMuted,
                modifier = Modifier.width(420.dp),
            )
            state.loginError?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = CaptainPalette.danger)
            }
            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "QUICK LOGIN (DEMO DRIVER)",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = CaptainPalette.accent,
                    modifier = Modifier.clickable { viewModel.quickLoginDemoDriver() },
                )
            }
            Spacer(Modifier.weight(1f))
            CaptainButton(text = "Cancel", outline = true, modifier = Modifier.width(200.dp)) {
                viewModel.onDriverIdChanged("")
                viewModel.onPinChanged("")
            }
        }
        Spacer(Modifier.weight(1f))
        // Right — keypad slot (448 wide) + Sign In.
        Column {
            if (focusedField == LoginField.PIN) {
                CaptainKeypad(
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
            CaptainButton(
                text = if (state.isLoggingIn) "Signing in…" else "Sign In",
                heightDp = 72,
                enabled = !state.isLoggingIn,
                modifier = Modifier.width(448.dp),
            ) { viewModel.login() }
        }
    }
}

/**
 * Compact alphanumeric pad filling the numeric keypad's 448dp slot — used only while DRIVER # is
 * focused (see the class doc's deviation note).
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
                            .background(CaptainPalette.raised)
                            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(10.dp))
                            .clickable { onKey(c) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(c.toString(), fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = CaptainPalette.textPrimary)
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
                    .background(CaptainPalette.raised)
                    .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(10.dp))
                    .clickable(onClick = onBackspace),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Backspace", tint = CaptainPalette.warning, modifier = Modifier.size(20.dp))
            }
            Box(
                modifier = Modifier
                    .width(219.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CaptainPalette.raised)
                    .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(10.dp))
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Text("CLR", fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = CaptainPalette.warning)
            }
        }
    }
}

// --- 05 · MFA Code ---------------------------------------------------------------------------

@Composable
private fun MfaStep(state: LoginVehicleBindUiState, viewModel: LoginVehicleBindViewModel) {
    Row(modifier = Modifier.fillMaxSize().padding(start = 96.dp, end = 96.dp, top = 110.dp, bottom = 60.dp)) {
        Column(modifier = Modifier.width(460.dp)) {
            Text("Two-factor check", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, color = CaptainPalette.textPrimary)
            Spacer(Modifier.height(24.dp))
            Text(
                "This driver account has MFA enabled. Enter the 6-digit code from your authenticator app.",
                fontFamily = InterFamily,
                fontSize = 17.sp,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.width(420.dp),
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(6) { i ->
                    val ch = state.mfaCodeInput.getOrNull(i)?.toString()
                    val isCursor = state.mfaCodeInput.length == i
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(80.dp)
                            .clip(shape)
                            .background(CaptainPalette.raised)
                            .border(if (isCursor) 2.dp else 1.5.dp, if (isCursor) CaptainPalette.accent else CaptainPalette.panelBorder, shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        ch?.let { Text(it, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, color = CaptainPalette.textPrimary) }
                    }
                }
            }
            state.loginError?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = CaptainPalette.danger)
            }
            Spacer(Modifier.weight(1f))
            CaptainButton(text = "Back", outline = true, modifier = Modifier.width(200.dp)) {
                viewModel.cancelMfaChallenge()
            }
        }
        Spacer(Modifier.weight(1f))
        Column {
            CaptainKeypad(
                onDigit = { d -> if (state.mfaCodeInput.length < 6) viewModel.onMfaCodeChanged(state.mfaCodeInput + d) },
                onBackspace = { viewModel.onMfaCodeChanged(state.mfaCodeInput.dropLast(1)) },
                onClear = { viewModel.onMfaCodeChanged("") },
            )
            Spacer(Modifier.weight(1f))
            CaptainButton(
                text = if (state.isLoggingIn) "Verifying…" else "Verify",
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
    // Real QR scan needs an Activity to host its scan UI — this app is single-activity (see
    // MainActivity's own doc), so LocalContext.current always is one here.
    val activity = LocalContext.current as android.app.Activity
    Column(modifier = Modifier.fillMaxSize().padding(start = 72.dp, end = 72.dp, top = 64.dp, bottom = 48.dp)) {
        Text("Bind to vehicle", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, color = CaptainPalette.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Scan the QR on the dash, or type the rego to pair this tablet with the vehicle.",
            fontFamily = InterFamily,
            fontSize = 17.sp,
            color = CaptainPalette.textSecondary,
        )
        Spacer(Modifier.height(44.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // QR zone — dashed 400×480 panel. Tapping runs the scanner, same as before.
            Column(
                modifier = Modifier
                    .width(400.dp)
                    .height(480.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(CaptainPalette.panel)
                    .border(2.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
                    .clickable { viewModel.scanQr(activity) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(CaptainPalette.inset),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(96.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("Point camera at vehicle QR", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = CaptainPalette.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.qrScanAttempted && state.vehicleIdInput.isBlank()) {
                        "No code detected — tap to try again, or use manual entry"
                    } else {
                        "Pairing code is single-use and expires in 10 minutes"
                    },
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = CaptainPalette.textMuted,
                )
            }
            // Manual entry card
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(480.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(CaptainPalette.panel)
                    .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FieldLabel("OR ENTER REGO MANUALLY")
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CaptainPalette.raised)
                        .border(2.dp, CaptainPalette.accent, RoundedCornerShape(14.dp))
                        .padding(start = 24.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        state.vehicleIdInput.ifEmpty { " " },
                        fontFamily = RobotoMonoFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 30.sp,
                        color = CaptainPalette.textPrimary,
                    )
                }
                // Compact rego keys — letters+digits so any NSW rego is typeable without an IME.
                RegoKeyRows(
                    onKey = { c -> viewModel.onVehicleIdChanged(state.vehicleIdInput + c) },
                    onBackspace = { viewModel.onVehicleIdChanged(state.vehicleIdInput.dropLast(1)) },
                )
                Spacer(Modifier.weight(1f))
                CaptainButton(
                    text = "Bind Vehicle",
                    heightDp = 72,
                    enabled = state.vehicleIdInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { viewModel.bindVehicle() }
            }
        }
    }
}

/** One rego-pad cell: either a real character key, or the backspace action (`char == null`) —
 * modelled as data instead of a sentinel character embedded in a string literal. */
private data class RegoKey(val char: Char?)

@Composable
private fun RegoKeyRows(onKey: (Char) -> Unit, onBackspace: () -> Unit) {
    // "-" appended to the digit row — real AU regos are commonly hyphenated (e.g. "KHI-01"), and
    // without this key a driver typing one here could only ever produce a *different* string than
    // what the fleet backend has on file for that vehicle, which then silently 404s every later
    // lookup keyed off rego (found live: a real seeded vehicle "KHI-01", bound here as "KHI01",
    // 404ing `POST /v1/fleet/positions` on every heartbeat).
    val rows = listOf(
        "ABCDEFGHI".map { RegoKey(it) },
        "JKLMNOPQR".map { RegoKey(it) },
        "STUVWXYZ".map { RegoKey(it) } + RegoKey(null),
        "0123456789-".map { RegoKey(it) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(CaptainPalette.raised)
                            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(8.dp))
                            .clickable { if (key.char == null) onBackspace() else onKey(key.char) },
                        contentAlignment = Alignment.Center,
                    ) {
                        val c = key.char
                        if (c == null) {
                            Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Backspace", tint = CaptainPalette.warning, modifier = Modifier.size(16.dp))
                        } else {
                            Text(
                                c.toString(),
                                fontFamily = ChakraPetch,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp,
                                color = CaptainPalette.textPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- 07 · Pre-Shift Inspection ---------------------------------------------------------------

/** Icon + sub-label per checklist key (titles come from [PRE_SHIFT_CHECKLIST_ITEMS]). */
private val CHECK_META: Map<String, Pair<ImageVector, String>> = mapOf(
    "tyres" to (Icons.Rounded.TireRepair to "Condition and pressure OK"),
    "lights" to (Icons.Rounded.Lightbulb to "All working"),
    "brakes" to (Icons.Rounded.CarRepair to "Feel normal"),
    "meter_tablet" to (Icons.Rounded.TabletAndroid to "Secure in mount"),
    "duress" to (Icons.Rounded.Sos to "Reachable & test light OK"),
    "interior" to (Icons.Rounded.CleaningServices to "Clean, no damage"),
    "cameras" to (Icons.Rounded.Videocam to "Unobstructed"),
    "fare_card" to (Icons.Rounded.Badge to "Visible to passengers"),
    "first_aid" to (Icons.Rounded.MedicalServices to "Present and in date"),
)

@Composable
private fun InspectionStep(
    state: LoginVehicleBindUiState,
    viewModel: LoginVehicleBindViewModel,
    onShiftStarted: () -> Unit,
) {
    var showDefectDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(start = 72.dp, end = 72.dp, top = 48.dp, bottom = 44.dp)) {
        Text("Pre-shift safety inspection", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = CaptainPalette.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Required under your Safety Management System (cl 14). Tick each item before starting the shift." +
                (state.boundVehicleId?.let { " Vehicle $it." } ?: ""),
            fontFamily = InterFamily,
            fontSize = 16.sp,
            color = CaptainPalette.textSecondary,
        )
        Spacer(Modifier.height(28.dp))
        PRE_SHIFT_CHECKLIST_ITEMS.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                rowItems.forEach { (key, title) ->
                    val checked = state.checklist[key] == true
                    val (icon, sub) = CHECK_META[key] ?: (Icons.Rounded.Warning to "")
                    CheckCard(
                        icon = icon,
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
            Text(it, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = CaptainPalette.danger)
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.weight(1f))
        Row {
            CaptainButton(text = "Report a defect", outline = true, modifier = Modifier.width(280.dp)) {
                // Does NOT touch the checklist gating below — see file doc. Opens the real-message
                // dialog instead of the previous no-op.
                showDefectDialog = true
            }
            Spacer(Modifier.weight(1f))
            CaptainButton(
                text = if (state.isStartingShift) "Starting…" else "All checks passed — Continue",
                heightDp = 72,
                enabled = state.allChecklistItemsChecked && !state.isStartingShift,
                modifier = Modifier.width(440.dp),
            ) { viewModel.startShift(onShiftStarted) }
        }
    }

    ReportDefectDialog(visible = showDefectDialog, onDismiss = { showDefectDialog = false })
}

/**
 * The "Report a defect" dialog: free-text notes, posted as a real dispatch message via
 * [AppContainer.messagesRepository] `sendMessage(driverId = null, body = "[Vehicle defect] " +
 * notes)` — the same call [au.com.threesixty.cabdispatch.ui.screens.messages.MessagesViewModel]
 * already makes for a quick-reply. Never claims success on a failed send; never touches the
 * checklist gate.
 */
@Composable
private fun ReportDefectDialog(visible: Boolean, onDismiss: () -> Unit) {
    var notes by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reset() {
        notes = ""
        sending = false
        errorText = null
        sent = false
    }

    CaptainDialogScrim(visible = visible, onDismissRequest = { reset(); onDismiss() }) {
        CaptainPanel(modifier = Modifier.width(480.dp), cornerRadiusDp = 20, raised = true) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = CaptainPalette.warning, modifier = Modifier.size(24.dp))
                    Text("Report a defect", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = CaptainPalette.textPrimary)
                }
                if (sent) {
                    Text(
                        "Sent to dispatch. Thanks — your defect note has been logged.",
                        fontFamily = InterFamily,
                        fontSize = 15.sp,
                        color = CaptainPalette.success,
                    )
                    CaptainButton(text = "Done", modifier = Modifier.fillMaxWidth()) {
                        reset()
                        onDismiss()
                    }
                } else {
                    Text(
                        "Describe the defect. This sends a real message to dispatch — it does not tick any checklist item.",
                        fontFamily = InterFamily,
                        fontSize = 14.sp,
                        color = CaptainPalette.textSecondary,
                    )
                    TextField(
                        value = notes,
                        onValueChange = { notes = it; errorText = null },
                        placeholder = { Text("Describe the defect", color = CaptainPalette.textMuted) },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = CaptainPalette.raised,
                            unfocusedContainerColor = CaptainPalette.raised,
                            disabledContainerColor = CaptainPalette.raised,
                            focusedTextColor = CaptainPalette.textPrimary,
                            unfocusedTextColor = CaptainPalette.textPrimary,
                            cursorColor = CaptainPalette.accent,
                            focusedIndicatorColor = CaptainPalette.accent,
                            unfocusedIndicatorColor = CaptainPalette.panelBorder,
                        ),
                    )
                    errorText?.let {
                        Text(it, fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.danger)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CaptainButton(text = "Cancel", outline = true, modifier = Modifier.weight(1f)) {
                            reset()
                            onDismiss()
                        }
                        CaptainButton(
                            text = if (sending) "Sending…" else "Send",
                            enabled = notes.isNotBlank() && !sending,
                            modifier = Modifier.weight(1f),
                        ) {
                            sending = true
                            errorText = null
                            scope.launch {
                                val result = AppContainer.messagesRepository.sendMessage(
                                    driverId = null,
                                    body = "[Vehicle defect] $notes",
                                )
                                sending = false
                                result.onSuccess {
                                    sent = true
                                }.onFailure {
                                    errorText = "Could not send — check connection"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Device/shift mismatch heads-up (backend `ShiftDto.deviceMismatchWarning` — see
 * [LoginVehicleBindUiState.deviceMismatchWarning]'s doc). By the time this can show, the shift
 * has ALREADY started successfully; this is purely informational, never a retry/error state like
 * [ReportDefectDialog], so it's a single acknowledgement, not a form.
 */
@Composable
private fun DeviceMismatchWarningDialog(message: String?, onDismiss: () -> Unit) {
    CaptainDialogScrim(visible = message != null, onDismissRequest = onDismiss) {
        CaptainPanel(modifier = Modifier.width(480.dp), cornerRadiusDp = 20, raised = true) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = CaptainPalette.warning, modifier = Modifier.size(24.dp))
                    Text("Check tablet placement", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = CaptainPalette.textPrimary)
                }
                Text(
                    message ?: "",
                    fontFamily = InterFamily,
                    fontSize = 15.sp,
                    color = CaptainPalette.textSecondary,
                )
                Text(
                    "Your shift has already started — this is just a heads-up, nothing to fix here right now.",
                    fontFamily = InterFamily,
                    fontSize = 13.sp,
                    color = CaptainPalette.textMuted,
                )
                CaptainButton(text = "OK, continue", modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun CheckCard(
    icon: ImageVector,
    title: String,
    sub: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .height(112.dp)
            .clip(shape)
            .background(CaptainPalette.panel)
            .border(1.5.dp, if (checked) CaptainPalette.success.copy(alpha = 0.45f) else CaptainPalette.panelBorder, shape)
            .clickable(onClick = onToggle)
            .padding(start = 18.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(26.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
            Spacer(Modifier.height(3.dp))
            Text(sub, fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.textMuted)
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (checked) CaptainPalette.success else CaptainPalette.raised),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = CaptainPalette.bg, modifier = Modifier.size(20.dp))
            }
        }
    }
}
