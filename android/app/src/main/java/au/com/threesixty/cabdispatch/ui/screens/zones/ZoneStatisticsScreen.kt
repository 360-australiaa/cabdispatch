package au.com.threesixty.cabdispatch.ui.screens.zones

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.remote.ZoneStatsDto
import au.com.threesixty.cabdispatch.ui.theme.WheelColors

/**
 * Statistics screen: a table of zones and their live demand stats (plotted/vacant/busy vehicles,
 * jobs holding, bookings/street-hails in the last hour), auto-refreshing every 15-30s while
 * visible (see ZoneStatisticsViewModel doc) -- matches a real competitor taxi meter zone-demand
 * screen (backend/app/api/v1/zones.py doc). Reached from PlotZoneScreen's "Stats" header link;
 * "Back" here pops back to Plot, not the dashboard, since that's how it was opened.
 */
@Composable
fun ZoneStatisticsScreen(
    navController: NavHostController,
    viewModel: ZoneStatisticsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WheelColors.bg)
            .padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹ Back",
                color = WheelColors.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.clickable { navController.popBackStack() },
            )
            Text("Zone statistics", color = WheelColors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(48.dp))
        }
        Spacer(Modifier.height(20.dp))

        when {
            state.loading && state.stats.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = WheelColors.gold)
            }
            state.error != null && state.stats.isEmpty() -> Text(state.error!!, color = WheelColors.duress, fontSize = 14.sp)
            state.stats.isEmpty() -> Text("No zones configured yet.", color = WheelColors.textSecondary, fontSize = 14.sp)
            else -> ZoneStatsTable(stats = state.stats, error = state.error)
        }
    }
}

@Composable
private fun ZoneStatsTable(stats: List<ZoneStatsDto>, error: String?) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (error != null) {
            Text(
                error,
                color = WheelColors.duress,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            ZoneStatsHeaderRow()
            Box(Modifier.fillMaxWidth().height(1.dp).background(WheelColors.border))
            LazyColumn(modifier = Modifier.width(TABLE_WIDTH_DP.dp)) {
                items(stats, key = { it.zoneId }) { row ->
                    ZoneStatsRow(row)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(WheelColors.border))
                }
            }
        }
    }
}

@Composable
private fun ZoneStatsHeaderRow() {
    Row(
        modifier = Modifier.width(TABLE_WIDTH_DP.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TableCell("Zone", width = ZONE_COL_DP, header = true, align = TextAlign.Start)
        TableCell("Plotted", width = STAT_COL_DP, header = true)
        TableCell("Vacant", width = STAT_COL_DP, header = true)
        TableCell("Busy", width = STAT_COL_DP, header = true)
        TableCell("Jobs", width = STAT_COL_DP, header = true)
        TableCell("Bkgs/hr", width = STAT_COL_DP, header = true)
        TableCell("Hails/hr", width = STAT_COL_DP, header = true)
    }
}

@Composable
private fun ZoneStatsRow(row: ZoneStatsDto) {
    Row(
        modifier = Modifier.width(TABLE_WIDTH_DP.dp).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TableCell("${row.zoneName} #${row.zoneNumber}", width = ZONE_COL_DP, align = TextAlign.Start, bold = true)
        TableCell(row.plottedVehicles.toString(), width = STAT_COL_DP)
        TableCell(row.vacantVehicles.toString(), width = STAT_COL_DP)
        TableCell(row.busyVehicles.toString(), width = STAT_COL_DP)
        TableCell(row.jobsHolding.toString(), width = STAT_COL_DP)
        TableCell(row.bookingsLastHour.toString(), width = STAT_COL_DP)
        TableCell(row.streetHailsLastHour.toString(), width = STAT_COL_DP)
    }
}

@Composable
private fun TableCell(
    text: String,
    width: Int,
    header: Boolean = false,
    bold: Boolean = false,
    align: TextAlign = TextAlign.Center,
) {
    Text(
        text,
        color = if (header) WheelColors.textMuted else WheelColors.textPrimary,
        fontSize = if (header) 11.sp else 13.sp,
        fontWeight = if (header || bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = align,
        maxLines = 1,
        modifier = Modifier.width(width.dp).padding(horizontal = 4.dp),
    )
}

private const val ZONE_COL_DP = 140
private const val STAT_COL_DP = 78
private const val TABLE_WIDTH_DP = ZONE_COL_DP + STAT_COL_DP * 6
