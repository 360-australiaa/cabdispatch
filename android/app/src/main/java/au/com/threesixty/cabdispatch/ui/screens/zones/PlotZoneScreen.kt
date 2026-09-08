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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.remote.ZoneDto
import au.com.threesixty.cabdispatch.data.remote.ZoneStatsDto
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.HudStatTile
import au.com.threesixty.cabdispatch.ui.theme.HudStatusPill
import au.com.threesixty.cabdispatch.ui.theme.HudTone
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * Zone List tab content (`squishy-herding-iverson.md` Phase F) — embeds this screen's real
 * zone-grid + plot/unplot flow inside the new tabbed Zones pane
 * ([au.com.threesixty.cabdispatch.ui.screens.zones.ZonesPaneContent]) rather than re-implementing
 * it: reuses [PlotZoneViewModel] and the same [ZoneCard]/[EmptyStateCard] composables
 * [PlotZoneScreen] itself uses, minus that standalone route's own title/back-row chrome (the tab
 * shell supplies its own). [PlotZoneScreen] and this route it lives on are otherwise untouched and
 * still reachable exactly as before. Also joins the same [ZoneStatisticsViewModel] its sibling tabs
 * ([HeatMapTabContent]/[AirportQueueTabContent]) already use, so [ZoneCard] can show real
 * vehicle/bookings/hails numbers where a stats row exists for the zone — see [PlotZoneScreen]'s own
 * doc for why this isn't a new data source.
 */
@Composable
fun PlotZoneTabContent(
    viewModel: PlotZoneViewModel = viewModel(),
    statsViewModel: ZoneStatisticsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val statsState by statsViewModel.uiState.collectAsState()
    val statsByZoneId = remember(statsState.stats) { statsState.stats.associateBy { it.zoneId } }

    when (val s = state) {
        is PlotZoneUiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CaptainPalette.accent)
        }
        is PlotZoneUiState.Error -> EmptyStateCard(
            modifier = Modifier.fillMaxSize(),
            title = "Couldn't load zones",
            body = s.message,
            buttonText = "RETRY",
            onButtonClick = viewModel::refresh,
        )
        is PlotZoneUiState.Loaded -> if (s.zones.isEmpty()) {
            EmptyStateCard(
                modifier = Modifier.fillMaxSize(),
                title = "No zones published for this region yet",
                body = "Your operator has not defined dispatch zones for the area you are in, or the " +
                    "zone list is still syncing. You can still receive direct job offers and street " +
                    "hails while unplotted.",
                buttonText = "REFRESH ZONES",
                onButtonClick = viewModel::refresh,
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                val plottedZone = s.zones.firstOrNull { it.id == s.plottedZoneId }
                if (plottedZone != null) {
                    HudStatusPill(
                        label = "Plotted",
                        value = "${plottedZone.number} — ${plottedZone.name}",
                        tone = HudTone.Success,
                        pulsing = false,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                if (s.error != null) {
                    Text(s.error, fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.danger)
                    Spacer(Modifier.height(8.dp))
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(s.zones, key = { it.id }) { zone ->
                        ZoneCard(
                            zone = zone,
                            stats = statsByZoneId[zone.id],
                            plotted = zone.id == s.plottedZoneId,
                            busy = s.busy,
                            onPlot = { viewModel.plotInto(zone) },
                            onUnplot = viewModel::unplot,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Zone card, now a [GlassCard] (was a hand-rolled clip/background/border `Column`) — the 52dp
 * Chakra number badge + name, pinned bottom CTA, plotted state and PLOT HERE/PLOTTED-UNPLOT
 * behaviour are all byte-for-byte unchanged. When [stats] resolves (the real
 * [au.com.threesixty.cabdispatch.data.remote.ZoneStatsDto] row for this zone — see
 * [PlotZoneScreen]'s own doc for how it's joined in), the honest "no per-zone demand field" caption
 * is replaced by real vehicle-count/bookings/hails [HudStatTile]s and a [SurgeModel]-derived surge
 * [HudStatusPill]; with no stats row yet, the exact same honest caption as before still shows.
 * Height grows from 220dp to 268dp only when the stats row is shown, to fit the extra tiles without
 * cramping the existing content.
 */
@Composable
private fun ZoneCard(
    zone: ZoneDto,
    stats: ZoneStatsDto?,
    plotted: Boolean,
    busy: Boolean,
    onPlot: () -> Unit,
    onUnplot: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.height(if (stats != null) 268.dp else 220.dp),
        cornerRadiusDp = 20,
        glow = if (plotted) CaptainPalette.success else null,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (plotted) CaptainPalette.success else CaptainPalette.raised),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        zone.number,
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Medium,
                        fontSize = 24.sp,
                        color = if (plotted) CaptainPalette.bg else CaptainPalette.accent,
                    )
                }
                Text(
                    zone.name,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = CaptainPalette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (stats != null) {
                    val multiplier = SurgeModel.multiplier(stats)
                    HudStatusPill(label = "Surge", value = SurgeModel.label(stats), tone = surgeTone(multiplier), pulsing = false)
                }
            }
            if (stats != null) {
                // Real vehicle-count/bookings/hails numbers off the same ZoneStatsDto row the
                // Statistics/Surge Areas tabs render — see this function's own doc.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudStatTile(
                        icon = Icons.Rounded.DirectionsCar,
                        label = "Vehicles",
                        value = "${stats.vacantVehicles + stats.busyVehicles}",
                        sub = "${stats.vacantVehicles} vacant",
                        valueFontSize = 18.sp,
                        modifier = Modifier.weight(1f),
                    )
                    HudStatTile(
                        icon = Icons.Rounded.EventAvailable,
                        label = "Bookings/hr",
                        value = "${stats.bookingsLastHour}",
                        valueFontSize = 18.sp,
                        modifier = Modifier.weight(1f),
                    )
                    HudStatTile(
                        icon = Icons.Rounded.Flag,
                        label = "Hails/hr",
                        value = "${stats.streetHailsLastHour}",
                        valueFontSize = 18.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!plotted) {
                        Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = CaptainPalette.textSecondary, modifier = Modifier.size(15.dp))
                    }
                    Text(
                        // No live stats row for this zone yet — see this function's own doc.
                        text = if (plotted) "Currently plotted" else "Tap to join this zone's queue",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = if (plotted) CaptainPalette.success else CaptainPalette.textSecondary,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (plotted) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.5.dp, CaptainPalette.danger.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                        .alpha(if (busy) 0.4f else 1f)
                        .clickable(enabled = !busy, onClick = onUnplot),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = CaptainPalette.danger)
                    } else {
                        Text(
                            "PLOTTED · UNPLOT",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = CaptainPalette.danger,
                        )
                    }
                }
            } else {
                CaptainButton(
                    text = "PLOT HERE",
                    heightDp = 58,
                    fontSize = 16.sp,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onPlot,
                )
            }
        }
    }
}

/** The empty/error card (also reused for the load-error state), now a [GlassCard] (was a
 * hand-rolled dashed-border `Column`) — same title/body/refresh-button content, unchanged. */
@Composable
private fun EmptyStateCard(
    modifier: Modifier = Modifier,
    title: String,
    body: String,
    buttonText: String,
    onButtonClick: () -> Unit,
) {
    GlassCard(modifier = modifier.fillMaxWidth(), cornerRadiusDp = 24) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(56.dp))
            Text(title, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = CaptainPalette.textPrimary)
            Text(
                body,
                fontFamily = InterFamily,
                fontSize = 16.sp,
                color = CaptainPalette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(560.dp),
            )
            Box(
                modifier = Modifier
                    .width(240.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CaptainPalette.raised)
                    .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp))
                    .clickable(onClick = onButtonClick),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(18.dp))
                    Text(buttonText, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = CaptainPalette.accent)
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}
