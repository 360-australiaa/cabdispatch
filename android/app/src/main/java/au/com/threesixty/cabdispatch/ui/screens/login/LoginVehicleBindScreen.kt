package au.com.threesixty.cabdispatch.ui.screens.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.WheelColors

/**
 * Rows 2–4 of spec §8's "System" group (PIN login / vehicle QR pairing / pre-shift inspection) —
 * all three flagged **not yet designed** in the spec, no Figma/HTML reference exists for any of
 * them. Designed inline here, restyled onto the [WheelColors] token system.
 *
 * **Restructuring decision (documented per this agent's brief):** the existing
 * [LoginVehicleBindViewModel] already models exactly these three rows as a linear
 * [LoginStep] state machine (`DRIVER_LOGIN` -> `VEHICLE_BIND` -> `INSPECTION`), each with its own
 * validation, error state, and loading state — i.e. it's already functionally three distinct
 * screens' worth of state, just sharing one composable host and one back stack entry. Splitting
 * each step into its own nav route would mean either (a) threading all of `uiState` through nav
 * args/a second hand-off object for no behavioural gain, or (b) giving each step its own
 * ViewModel and re-deriving the driver-login -> vehicle-bind -> inspection sequencing that
 * already works. Neither buys anything the spec asks for — the spec's "screen inventory" table is
 * counting *design rows*, not literally mandating one nav destination per row (row 6, the
 * dashboard, is one persistent screen with wheel content swapped underneath it, the same
 * one-host-many-facets shape used here). So: kept the working step logic as-is, did a full visual
 * pass (dark [WheelColors] surfaces/cards, gold accent, rounded corners, custom text fields —
 * replacing the old plain-Material3-defaults look) to bring it in line with the rest of the
 * redesigned screens, and split what *was* missing — the shift-start confirmation (row 5) — out
 * into its own screen/route ([au.com.threesixty.cabdispatch.ui.screens.shiftstart.ShiftStartScreen])
 * since that's a genuinely distinct moment (after the flow, not a step within it), not something
 * [LoginStep] modeled at all before this pass.
 */
@Composable
fun LoginVehicleBindScreen(
    navController: NavHostController,
    viewModel: LoginVehicleBindViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WheelColors.bg),
    ) {
        LoginTopBar(navController)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            StepIndicator(step = uiState.step)

            when (uiState.step) {
                LoginStep.DRIVER_LOGIN -> DriverLoginStep(uiState, viewModel)
                LoginStep.VEHICLE_BIND -> VehicleBindStep(uiState, viewModel)
                LoginStep.INSPECTION -> InspectionStep(uiState, viewModel) {
                    // Row 5 (shift-start confirmation) — see class doc for why this is a
                    // separate screen/route rather than a fourth LoginStep.
                    navController.navigate(CabDispatchRoutes.SHIFT_START) {
                        popUpTo(CabDispatchRoutes.LOGIN_VEHICLE_BIND) { inclusive = true }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LoginTopBar(navController: NavHostController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Cab Dispatch", color = WheelColors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        // S6 (settings/diagnostics) reachable from anywhere, per spec — useful pre-login too
        // (GPS/network diagnostics don't need a signed-in driver).
        Text(
            "⚙",
            color = WheelColors.textSecondary,
            fontSize = 20.sp,
            modifier = Modifier
                .clickable { navController.navigate(CabDispatchRoutes.SETTINGS) }
                .padding(4.dp),
        )
    }
}

@Composable
private fun StepIndicator(step: LoginStep) {
    val labels = listOf("Driver login", "Vehicle pairing", "Pre-shift check")
    val activeIndex = when (step) {
        LoginStep.DRIVER_LOGIN -> 0
        LoginStep.VEHICLE_BIND -> 1
        LoginStep.INSPECTION -> 2
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEachIndexed { index, label ->
            val done = index < activeIndex
            val active = index == activeIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(96.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            when {
                                done -> WheelColors.available
                                active -> WheelColors.gold
                                else -> WheelColors.surfaceRaised
                            },
                            CircleShape,
                        )
                        .border(1.dp, if (active) WheelColors.gold else WheelColors.border, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (done) "✓" else "${index + 1}",
                        color = if (done || active) IndigoOnGold else WheelColors.textMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    color = if (active) WheelColors.textPrimary else WheelColors.textMuted,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun DriverLoginStep(uiState: LoginVehicleBindUiState, viewModel: LoginVehicleBindViewModel) {
    StepCard(
        icon = "🔑",
        title = "Driver PIN login",
        subtitle = "Works fully offline once you've logged in on this device before.",
    ) {
        DarkTextField(
            value = uiState.driverIdInput,
            onValueChange = viewModel::onDriverIdChanged,
            label = "Driver ID",
        )
        Spacer(Modifier.height(12.dp))
        DarkTextField(
            value = uiState.pinInput,
            onValueChange = viewModel::onPinChanged,
            label = "PIN",
            isPassword = true,
            keyboardType = KeyboardType.NumberPassword,
        )
        uiState.loginError?.let {
            Spacer(Modifier.height(10.dp))
            ErrorText(it)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryGoldButton(label = "LOG IN", loading = uiState.isLoggingIn, onClick = viewModel::login)

        // Debug-build only — never renders in a release build. Fills + submits the seeded demo
        // driver's credentials in one tap, so testing on-device doesn't mean retyping the same
        // PIN after every rebuild/reinstall. See LoginVehicleBindViewModel.quickLoginDemoDriver.
        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(12.dp))
            SecondaryOutlineButton(
                label = "QUICK LOGIN (DEMO DRIVER)",
                onClick = viewModel::quickLoginDemoDriver,
            )
        }
    }
}

@Composable
private fun VehicleBindStep(uiState: LoginVehicleBindUiState, viewModel: LoginVehicleBindViewModel) {
    StepCard(
        icon = "🚗",
        title = "Pair vehicle",
        subtitle = "Welcome, ${uiState.loggedInDriverName ?: uiState.loggedInDriverId}",
    ) {
        SecondaryOutlineButton(label = "SCAN VEHICLE QR CODE", onClick = viewModel::scanQr)
        if (uiState.qrScanAttempted) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Camera scan unavailable on this device/build — enter the vehicle ID manually below.",
                color = WheelColors.textMuted,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(16.dp))
        DarkTextField(
            value = uiState.vehicleIdInput,
            onValueChange = viewModel::onVehicleIdChanged,
            label = "Vehicle ID (manual entry)",
        )
        Spacer(Modifier.height(20.dp))
        PrimaryGoldButton(
            label = "PAIR VEHICLE",
            enabled = uiState.vehicleIdInput.isNotBlank(),
            onClick = viewModel::bindVehicle,
        )
    }
}

@Composable
private fun InspectionStep(
    uiState: LoginVehicleBindUiState,
    viewModel: LoginVehicleBindViewModel,
    onShiftStarted: () -> Unit,
) {
    StepCard(
        icon = "📋",
        title = "Pre-shift inspection",
        subtitle = "Vehicle ${uiState.boundVehicleId}",
    ) {
        PRE_SHIFT_CHECKLIST_ITEMS.forEach { (key, label) ->
            ChecklistRow(
                label = label,
                checked = uiState.checklist[key] == true,
                onToggle = { viewModel.toggleChecklistItem(key) },
            )
        }
        uiState.shiftError?.let {
            Spacer(Modifier.height(10.dp))
            ErrorText(it)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryGoldButton(
            label = "START SHIFT",
            enabled = uiState.allChecklistItemsChecked,
            loading = uiState.isStartingShift,
            onClick = { viewModel.startShift(onShiftStarted) },
        )
    }
}

// --- Shared dark-theme building blocks (kept local to this file — same convention every other
// wheel-redesign screen in this module uses, e.g. MessageThreadScreen's CabDispatchIndigoText) ---

@Composable
private fun StepCard(
    icon: String,
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(WheelColors.surfaceRaised.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .border(1.dp, WheelColors.border, RoundedCornerShape(16.dp))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Text(title, color = WheelColors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = WheelColors.textSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun DarkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = WheelColors.surface,
            unfocusedContainerColor = WheelColors.surface,
            disabledContainerColor = WheelColors.surface,
            focusedBorderColor = WheelColors.gold,
            unfocusedBorderColor = WheelColors.borderStrong,
            focusedTextColor = WheelColors.textPrimary,
            unfocusedTextColor = WheelColors.textPrimary,
            focusedLabelColor = WheelColors.gold,
            unfocusedLabelColor = WheelColors.textMuted,
            cursorColor = WheelColors.gold,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PrimaryGoldButton(
    label: String,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val clickable = enabled && !loading
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(if (enabled) WheelColors.gold else WheelColors.surfaceRaised, RoundedCornerShape(14.dp))
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = IndigoOnGold, strokeWidth = 2.dp)
        } else {
            Text(
                label,
                color = if (enabled) IndigoOnGold else WheelColors.textMuted,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun SecondaryOutlineButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .border(1.dp, WheelColors.borderStrong, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = WheelColors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun ChecklistRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(if (checked) WheelColors.gold else Color.Transparent, RoundedCornerShape(6.dp))
                .border(1.5.dp, if (checked) WheelColors.gold else WheelColors.borderStrong, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Text("✓", color = IndigoOnGold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = WheelColors.textPrimary, fontSize = 14.sp)
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = WheelColors.duress, fontSize = 12.sp)
}

/** #2A1C58 — text color on top of a gold surface (button fill, selected step dot). Same
 * coincidental-not-semantic reuse [MessageThreadScreen.CabDispatchIndigoText] documents; kept as
 * its own local constant per that file's established convention rather than a shared one. */
private val IndigoOnGold = Color(0xFF2A1C58)
