package au.com.threesixty.cabdispatch.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.hardware.printing.PrinterDevice

private enum class SettingsSubScreen { MAIN, PRINTER_PAIRING, FARE_SCHEDULE }

/**
 * S6 — Settings/Diagnostics (spec B5). See [SettingsViewModel] for the data
 * flow; this file is presentation-only. [onFactoryReset] is called once the
 * device has been wiped, so the caller (NavHost) can pop back to S1 with an
 * empty back stack. [navController] is used only for the "< Back" affordance
 * below — S6 is reachable from every other screen (settings icon in each
 * screen's header/corner), so it needs an explicit way back to wherever the
 * driver came from, not just the system back gesture.
 */
@Composable
fun SettingsScreen(
    navController: NavHostController,
    onFactoryReset: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var subScreen by remember { mutableStateOf(SettingsSubScreen.MAIN) }
    var showResetDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }

    LaunchedEffect(state.factoryResetComplete) {
        if (state.factoryResetComplete) onFactoryReset()
    }

    when (subScreen) {
        SettingsSubScreen.MAIN -> MainSettingsContent(
            state = state,
            onBack = { navController.popBackStack() },
            onOpenPrinterPairing = { subScreen = SettingsSubScreen.PRINTER_PAIRING },
            onOpenFareSchedule = { subScreen = SettingsSubScreen.FARE_SCHEDULE },
            onFactoryResetClick = { showResetDialog = true },
            onDownloadOfflineMaps = viewModel::downloadOfflineMaps,
        )
        SettingsSubScreen.PRINTER_PAIRING -> PrinterPairingContent(
            state = state,
            viewModel = viewModel,
            onBack = { subScreen = SettingsSubScreen.MAIN },
        )
        SettingsSubScreen.FARE_SCHEDULE -> FareScheduleContent(
            state = state,
            onBack = { subScreen = SettingsSubScreen.MAIN },
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = {
                showResetDialog = false
                pinInput = ""
                viewModel.clearFactoryResetError()
            },
            title = { Text("Factory reset") },
            text = {
                Column {
                    Text("Enter the admin PIN to wipe all local trip/shift data and sign out. This cannot be undone.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it },
                        label = { Text("Admin PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.factoryResetError != null) {
                        Text(state.factoryResetError, color = MaterialTheme.colorScheme.error)
                    }
                    if (state.factoryResetInProgress) {
                        CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.attemptFactoryReset(pinInput) },
                    enabled = !state.factoryResetInProgress,
                ) { Text("Wipe device") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    pinInput = ""
                    viewModel.clearFactoryResetError()
                }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MainSettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenPrinterPairing: () -> Unit,
    onOpenFareSchedule: () -> Unit,
    onFactoryResetClick: () -> Unit,
    onDownloadOfflineMaps: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("< Back") }
            }
        }
        item { Text("Settings / Diagnostics", style = MaterialTheme.typography.headlineSmall) }

        item {
            DiagnosticsCard(state)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("App version", style = MaterialTheme.typography.titleMedium)
                    Text(state.appVersion)
                    val (label, color) = when (state.forceUpdateStatus) {
                        ForceUpdateStatus.UNKNOWN_NO_DEVICE -> "Update status unknown (device not paired)" to MaterialTheme.colorScheme.onSurfaceVariant
                        ForceUpdateStatus.UNKNOWN_OFFLINE -> "Update status unknown (offline)" to MaterialTheme.colorScheme.onSurfaceVariant
                        ForceUpdateStatus.UP_TO_DATE -> "Up to date" to MaterialTheme.colorScheme.primary
                        ForceUpdateStatus.REQUIRED -> "Update required" to MaterialTheme.colorScheme.error
                    }
                    Text(label, color = color)
                }
            }
        }

        item {
            OutlinedButton(onClick = onOpenPrinterPairing, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (state.pairedPrinter != null) {
                        "Receipt printer: ${state.pairedPrinter.name}"
                    } else {
                        "Pair a receipt printer"
                    },
                )
            }
        }

        item {
            OutlinedButton(onClick = onOpenFareSchedule, modifier = Modifier.fillMaxWidth()) {
                Text("Fare schedule (passenger display)")
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Offline maps", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Download the Sydney metro region so the dashboard's map background keeps " +
                            "working with zero signal — the meter itself already works fully offline, " +
                            "this just covers the map imagery too.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    when (val dl = state.offlineMapDownload) {
                        is OfflineMapDownloadState.NotStarted ->
                            OutlinedButton(onClick = onDownloadOfflineMaps, modifier = Modifier.fillMaxWidth()) {
                                Text("Download offline maps")
                            }
                        is OfflineMapDownloadState.Downloading -> {
                            LinearProgressIndicator(
                                progress = { dl.progressPercent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("Downloading… ${dl.progressPercent}%", style = MaterialTheme.typography.bodySmall)
                        }
                        is OfflineMapDownloadState.Completed -> Text(
                            "Offline maps ready",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        is OfflineMapDownloadState.Failed -> Column {
                            Text("Download failed: ${dl.message}", color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = onDownloadOfflineMaps, modifier = Modifier.fillMaxWidth()) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = onFactoryResetClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Factory reset") }
        }
    }
}

@Composable
private fun DiagnosticsCard(state: SettingsUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val (gpsLabel, gpsColor) = when (state.gpsQuality) {
                GpsQuality.GOOD -> "Good (${state.gpsAccuracyM?.toInt()}m)" to MaterialTheme.colorScheme.primary
                GpsQuality.FAIR -> "Fair (${state.gpsAccuracyM?.toInt()}m)" to MaterialTheme.colorScheme.tertiary
                GpsQuality.POOR -> "Poor (${state.gpsAccuracyM?.toInt()}m)" to MaterialTheme.colorScheme.error
                GpsQuality.NO_FIX -> "No fix" to MaterialTheme.colorScheme.error
                GpsQuality.PERMISSION_DENIED -> "Location permission not granted" to MaterialTheme.colorScheme.error
            }
            DiagnosticRow("GPS quality", gpsLabel, gpsColor)

            val (netLabel, netColor) = when (state.networkStatus) {
                NetworkStatus.WIFI -> "Wi-Fi" to MaterialTheme.colorScheme.primary
                NetworkStatus.CELLULAR -> "Cellular" to MaterialTheme.colorScheme.primary
                NetworkStatus.OTHER -> "Connected" to MaterialTheme.colorScheme.primary
                NetworkStatus.OFFLINE -> "Offline" to MaterialTheme.colorScheme.error
            }
            DiagnosticRow("Network", netLabel, netColor)
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

@Composable
private fun PrinterPairingContent(state: SettingsUiState, viewModel: SettingsViewModel, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("< Back") }
        }
        Text("Printer pairing", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        Text(
            if (state.pairedPrinter != null) "Paired: ${state.pairedPrinter.name}" else "No printer paired",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))

        Button(onClick = viewModel::discoverPrinters, enabled = !state.printerDiscovering) {
            Text(if (state.printerDiscovering) "Scanning..." else "Scan for printers")
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.discoveredPrinters) { device: PrinterDevice ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(device.name)
                        OutlinedButton(onClick = { viewModel.pairPrinter(device.id) }) { Text("Pair") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FareScheduleContent(state: SettingsUiState, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("< Back") }
        }
        Text("Fare schedule", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Rates displayed to passengers per the taxi fare regulations (cl.15 display requirement).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        val tariff = state.fareSchedule
        when {
            state.fareScheduleLoading -> CircularProgressIndicator()
            tariff == null -> Text("No cached fare schedule available.")
            else -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tariff.name, style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        FareScheduleRow("Hiring charge (flag fall)", tariff.flagFall)
                        if (tariff.peakCharge != "0") FareScheduleRow("Peak time hiring charge", tariff.peakCharge)
                        FareScheduleRow("Distance rate, first ${tariff.distKmThreshold}km", "${tariff.distRate1}/km")
                        FareScheduleRow("Distance rate, beyond ${tariff.distKmThreshold}km", "${tariff.distRate2}/km")
                        FareScheduleRow("Night distance rate, first ${tariff.distKmThreshold}km", "${tariff.nightRate1}/km")
                        FareScheduleRow("Night distance rate, beyond ${tariff.distKmThreshold}km", "${tariff.nightRate2}/km")
                        if (tariff.holidayRate1 != "0") {
                            FareScheduleRow("Holiday distance rate, first ${tariff.distKmThreshold}km", "${tariff.holidayRate1}/km")
                        }
                        if (tariff.holidayRate2 != "0") {
                            FareScheduleRow("Holiday distance rate, beyond ${tariff.distKmThreshold}km", "${tariff.holidayRate2}/km")
                        }
                        FareScheduleRow("Waiting time", "${tariff.waitingRatePerMin}/min")
                        FareScheduleRow("Non-cash payment surcharge cap", "${tariff.surchargePctCap}%")
                    }
                }
            }
        }
    }
}

@Composable
private fun FareScheduleRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text("$$value", style = MaterialTheme.typography.bodyMedium)
    }
}
