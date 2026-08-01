package au.com.threesixty.cabdispatch.ui.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginVehicleBindScreen(
    navController: NavHostController,
    viewModel: LoginVehicleBindViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Cab Dispatch — Sign in") },
            actions = {
                // S6 (settings/diagnostics) reachable from anywhere, per spec
                // B5 — useful pre-login too (GPS/network diagnostics don't
                // need a signed-in driver).
                IconButton(onClick = { navController.navigate(CabDispatchRoutes.SETTINGS) }) {
                    Text("⚙")
                }
            },
        )
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepIndicator(step = uiState.step)

            when (uiState.step) {
                LoginStep.DRIVER_LOGIN -> DriverLoginStep(uiState, viewModel)
                LoginStep.VEHICLE_BIND -> VehicleBindStep(uiState, viewModel)
                LoginStep.INSPECTION -> InspectionStep(uiState, viewModel) {
                    navController.navigate(CabDispatchRoutes.IDLE) {
                        popUpTo(CabDispatchRoutes.LOGIN_VEHICLE_BIND) { inclusive = true }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepIndicator(step: LoginStep) {
    val labels = listOf("1. Driver login", "2. Vehicle pairing", "3. Pre-shift check")
    val activeIndex = when (step) {
        LoginStep.DRIVER_LOGIN -> 0
        LoginStep.VEHICLE_BIND -> 1
        LoginStep.INSPECTION -> 2
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            AssistChip(
                onClick = {},
                enabled = index <= activeIndex,
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun DriverLoginStep(uiState: LoginVehicleBindUiState, viewModel: LoginVehicleBindViewModel) {
    Text("Driver sign in", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(
        value = uiState.driverIdInput,
        onValueChange = viewModel::onDriverIdChanged,
        label = { Text("Driver ID") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = uiState.pinInput,
        onValueChange = viewModel::onPinChanged,
        label = { Text("PIN") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    uiState.loginError?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
    }
    Text(
        "Works fully offline once you've logged in on this device before.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        onClick = viewModel::login,
        enabled = !uiState.isLoggingIn,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (uiState.isLoggingIn) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text("Log in")
        }
    }
}

@Composable
private fun VehicleBindStep(uiState: LoginVehicleBindUiState, viewModel: LoginVehicleBindViewModel) {
    Text("Pair vehicle", style = MaterialTheme.typography.titleLarge)
    Text("Welcome, ${uiState.loggedInDriverName ?: uiState.loggedInDriverId}")

    OutlinedButton(onClick = viewModel::scanQr, modifier = Modifier.fillMaxWidth()) {
        Text("Scan vehicle QR code")
    }
    if (uiState.qrScanAttempted) {
        Text(
            "Camera scan unavailable on this device/build — enter the vehicle ID manually below.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    OutlinedTextField(
        value = uiState.vehicleIdInput,
        onValueChange = viewModel::onVehicleIdChanged,
        label = { Text("Vehicle ID (manual entry)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = viewModel::bindVehicle,
        enabled = uiState.vehicleIdInput.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Pair vehicle")
    }
}

@Composable
private fun InspectionStep(
    uiState: LoginVehicleBindUiState,
    viewModel: LoginVehicleBindViewModel,
    onShiftStarted: () -> Unit,
) {
    Text("Pre-shift inspection", style = MaterialTheme.typography.titleLarge)
    Text("Vehicle: ${uiState.boundVehicleId}", style = MaterialTheme.typography.bodyMedium)

    PRE_SHIFT_CHECKLIST_ITEMS.forEach { (key, label) ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = uiState.checklist[key] == true,
                onCheckedChange = { viewModel.toggleChecklistItem(key) },
            )
            Text(label)
        }
    }

    uiState.shiftError?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
    }

    Button(
        onClick = { viewModel.startShift(onShiftStarted) },
        enabled = uiState.allChecklistItemsChecked && !uiState.isStartingShift,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (uiState.isStartingShift) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text("Start shift")
        }
    }
}
