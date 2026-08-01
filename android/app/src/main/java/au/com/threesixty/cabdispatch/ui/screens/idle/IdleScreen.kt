package au.com.threesixty.cabdispatch.ui.screens.idle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.TodayStats
import au.com.threesixty.cabdispatch.domain.toMoneyString
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import java.math.RoundingMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdleScreen(
    navController: NavHostController,
    viewModel: IdleViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Cab Dispatch — ${uiState.session?.driverName ?: ""}") },
            actions = {
                // S5 (shift report) reachable from S2, per spec B5.
                IconButton(onClick = { navController.navigate(CabDispatchRoutes.SHIFT_REPORT) }) {
                    Text("📋")
                }
                // S6 (settings) reachable from anywhere, per spec B5.
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AvailabilityCard(uiState.isAvailable, viewModel::setAvailable)
            TodayStatsCard(uiState.todayStats)
            TariffCard(uiState.tariff)

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    viewModel.startHire {
                        navController.navigate(CabDispatchRoutes.HIRED)
                    }
                },
                enabled = uiState.isAvailable && uiState.tariff != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text("Start hire", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun AvailabilityCard(isAvailable: Boolean, onToggle: (Boolean) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(if (isAvailable) "AVAILABLE" else "OFF DUTY", style = MaterialTheme.typography.titleLarge)
                Text("Toggle when you're ready for a hire", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = isAvailable, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun TodayStatsCard(stats: TodayStats) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Today", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatItem("Trips", stats.tripsCount.toString())
                StatItem("Km", stats.kmTotal.setScale(1, RoundingMode.HALF_UP).toPlainString())
                StatItem("Earnings", stats.earningsTotal.toMoneyString())
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TariffCard(tariff: TariffDto?) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Tariff region", style = MaterialTheme.typography.titleMedium)
            if (tariff == null) {
                Text("Loading cached tariff…", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("${tariff.region} — ${tariff.name}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
