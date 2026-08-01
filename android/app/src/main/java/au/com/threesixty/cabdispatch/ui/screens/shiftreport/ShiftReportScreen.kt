package au.com.threesixty.cabdispatch.ui.screens.shiftreport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes

/**
 * S5 — Shift report (spec B5). See [ShiftReportViewModel] for the offline
 * aggregate/submit logic; this file is presentation-only.
 */
@Composable
fun ShiftReportScreen(
    navController: NavHostController,
    onDone: () -> Unit,
    viewModel: ShiftReportViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.submitted) {
        if (state.submitted) onDone()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Shift report", style = MaterialTheme.typography.headlineSmall)
            // S6 (settings) reachable from anywhere, per spec B5.
            IconButton(onClick = { navController.navigate(CabDispatchRoutes.SETTINGS) }) {
                Text("⚙")
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            state.loading -> CenteredProgress()
            state.shiftClientUuid == null -> CenteredMessage("No active shift.")
            else -> ShiftReportContent(state, viewModel)
        }
    }
}

@Composable
private fun CenteredProgress() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { CircularProgressIndicator() }
}

@Composable
private fun CenteredMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun ShiftReportContent(state: ShiftReportUiState, vm: ShiftReportViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatRow("Trips completed", state.tripsCount.toString())
                StatRow("Distance", "${state.kmTotal.setScale(1, java.math.RoundingMode.HALF_UP)} km")
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                StatRow("Cash total", state.cashTotal.money())
                StatRow("Card total", state.cardTotal.money())
                StatRow("Total takings", state.totalTakings.money(), emphasize = true)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                StatRow("PSL accrued", state.pslAccrued.money())
                if (state.unsyncedTripsCount > 0) {
                    Text(
                        "${state.unsyncedTripsCount} trip(s) still pending sync — will upload automatically once online.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.submitError != null) {
            Text(state.submitError, color = MaterialTheme.colorScheme.error)
        }

        if (state.submitting) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        } else {
            Button(onClick = vm::submitShift, modifier = Modifier.fillMaxWidth()) {
                Text("Finalize & submit shift")
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, emphasize: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private fun java.math.BigDecimal.money(): String = "$" + this.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
