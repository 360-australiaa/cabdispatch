package au.com.threesixty.cabdispatch.ui.screens.zones

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.remote.ZoneDto
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.WheelColors

/**
 * Plot screen: a simple list of dispatch zones (name/number) the driver can tap to plot into, a
 * "currently plotted in: X" indicator, and an unplot action, matching a real competitor taxi
 * meter zone-plotting screen (backend/app/api/v1/zones.py doc). Reached from a small
 * "Zones" entry point on WheelDashboardScreen top status strip (not a 7th wheel slot, see
 * CabDispatchRoutes.PLOT_ZONE doc for why), styled with the same Back-header + WheelColors card
 * pattern TripDetailScreen already uses for a screen reached the same way (small entry point off
 * the dashboard, not one of the original S1-S6 flow screens). "Stats" in the header navigates to
 * ZoneStatisticsScreen.
 */
@Composable
fun PlotZoneScreen(
    navController: NavHostController,
    viewModel: PlotZoneViewModel = viewModel(),
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
            Text("Plot", color = WheelColors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Stats ›",
                color = WheelColors.gold,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable {
                    navController.navigate(CabDispatchRoutes.ZONE_STATISTICS)
                },
            )
        }
        Spacer(Modifier.height(20.dp))

        when (val s = state) {
            is PlotZoneUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = WheelColors.gold)
            }
            is PlotZoneUiState.Error -> Column {
                Text(s.message, color = WheelColors.duress, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = viewModel::refresh,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedButtonDefaults.outlinedButtonColors(contentColor = WheelColors.textSecondary),
                ) { Text("Retry") }
            }
            is PlotZoneUiState.Loaded -> PlotZoneBody(state = s, viewModel = viewModel)
        }
    }
}

@Composable
private fun PlotZoneBody(state: PlotZoneUiState.Loaded, viewModel: PlotZoneViewModel) {
    val plottedZone = state.zones.firstOrNull { it.id == state.plottedZoneId }

    Column(modifier = Modifier.fillMaxWidth()) {
        CurrentPlotCard(plottedZone = plottedZone, busy = state.busy, onUnplot = viewModel::unplot)
        Spacer(Modifier.height(16.dp))

        if (state.error != null) {
            Text(
                state.error,
                color = WheelColors.duress,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }

        if (state.zones.isEmpty()) {
            Text("No zones configured yet.", color = WheelColors.textSecondary, fontSize = 14.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.zones, key = { it.id }) { zone ->
                    ZoneRow(
                        zone = zone,
                        plotted = zone.id == state.plottedZoneId,
                        busy = state.busy,
                        onClick = { viewModel.plotInto(zone) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentPlotCard(plottedZone: ZoneDto?, busy: Boolean, onUnplot: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WheelColors.surfaceRaised.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .border(1.dp, WheelColors.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                "CURRENTLY PLOTTED IN",
                color = WheelColors.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (plottedZone != null) "${plottedZone.name} · #${plottedZone.number}" else "Not plotted",
                color = if (plottedZone != null) WheelColors.gold else WheelColors.textSecondary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (plottedZone != null) {
            OutlinedButton(
                onClick = onUnplot,
                enabled = !busy,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedButtonDefaults.outlinedButtonColors(contentColor = WheelColors.duress),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = WheelColors.duress)
                } else {
                    Text("Unplot", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ZoneRow(zone: ZoneDto, plotted: Boolean, busy: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (plotted) WheelColors.gold.copy(alpha = 0.12f) else WheelColors.surfaceRaised.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            )
            .border(
                1.dp,
                if (plotted) WheelColors.gold else WheelColors.border,
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = !busy && !plotted, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(zone.name, color = WheelColors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Zone #${zone.number}", color = WheelColors.textSecondary, fontSize = 12.sp)
        }
        if (plotted) {
            Text("PLOTTED", color = WheelColors.gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        } else {
            Button(
                onClick = onClick,
                enabled = !busy,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WheelColors.gold, contentColor = CabIndigoOnGoldZones),
            ) {
                Text("Plot in", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private val CabIndigoOnGoldZones = Color(0xFF2A1C58)
