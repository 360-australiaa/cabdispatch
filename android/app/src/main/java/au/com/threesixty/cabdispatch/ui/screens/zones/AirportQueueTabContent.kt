package au.com.threesixty.cabdispatch.ui.screens.zones

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.data.remote.ZoneDto
import au.com.threesixty.cabdispatch.data.remote.ZoneStatsDto
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.HudStatusPill
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * Airport Queue tab (`squishy-herding-iverson.md` Phase F).
 *
 * Checked before building anything here: this backend has NO airport-specific queue/rank concept
 * anywhere — `backend/app/services/zones.py`'s `Zone`/`compute_zone_stats` carry no `is_airport`
 * flag or distinct queue-position field, and grepping the whole tree for "airport" turns up only
 * fare logic (`airportRankRequestedMaxi`, the Sydney Airport Fixed Fare tariff constants) plus
 * "Airport" used purely as an example zone *name* in tests/fixtures (`backend/tests/test_zones.py`)
 * — never a real domain concept distinct from an ordinary zone.
 *
 * Per the task brief's instruction not to fabricate a queue/rank feature that doesn't exist: this
 * tab is a plain, honest **name filter** over the real zone list — any zone whose real
 * [ZoneDto.name] contains "airport" (case-insensitive) — joined with its real live stats from the
 * same [ZoneStatisticsViewModel] every other tab uses. If an operator has never created a zone
 * named "Airport" (or similar), this tab honestly shows nothing rather than inventing one.
 *
 * **HUD kit rebuild (2026-09-04).** [AirportZoneCard] is now a [GlassCard] carrying its surge
 * multiplier as a [HudStatusPill] (was a `Surface` with a hand-rolled colored badge) — same
 * filter/data/[SurgeModel] computation, unchanged.
 */
@Composable
fun AirportQueueTabContent(
    plotViewModel: PlotZoneViewModel = viewModel(),
    statsViewModel: ZoneStatisticsViewModel = viewModel(),
) {
    val zoneState by plotViewModel.uiState.collectAsState()
    val statsState by statsViewModel.uiState.collectAsState()

    val zones = (zoneState as? PlotZoneUiState.Loaded)?.zones.orEmpty()
    val airportZones = remember(zones) { zones.filter { it.name.contains("airport", ignoreCase = true) } }
    val statsByZoneId = remember(statsState.stats) { statsState.stats.associateBy { it.zoneId } }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Real zones whose name contains \"airport\" — this backend has no separate airport " +
                "rank/queue concept, so this is a name filter over the same live zone data as " +
                "every other tab, not a fabricated feature.",
            fontFamily = InterFamily,
            fontSize = 14.sp,
            color = CaptainPalette.textMuted,
        )
        Spacer(Modifier.height(14.dp))

        when {
            zoneState is PlotZoneUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CaptainPalette.accent)
            }
            airportZones.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.FlightTakeoff, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(40.dp))
                    Text(
                        "No airport zone is configured for this operator yet.",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = CaptainPalette.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "This tab will show it automatically once a zone with \"airport\" in its " +
                            "name exists — nothing is invented here.",
                        fontFamily = InterFamily,
                        fontSize = 14.sp,
                        color = CaptainPalette.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(airportZones, key = { it.id }) { zone -> AirportZoneCard(zone, statsByZoneId[zone.id]) }
            }
        }
    }
}

/** Now a [GlassCard] with the multiplier carried as a [HudStatusPill] (was a `Surface` with a
 * hand-rolled colored badge `Box`) — same real zone/stats content, toned via [surgeTone]. */
@Composable
private fun AirportZoneCard(zone: ZoneDto, stats: ZoneStatsDto?) {
    val multiplier = stats?.let(SurgeModel::multiplier) ?: 1.0
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 16) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(CaptainPalette.raised),
                contentAlignment = Alignment.Center,
            ) {
                Text(zone.number, fontFamily = ChakraPetch, fontWeight = FontWeight.Medium, fontSize = 22.sp, color = CaptainPalette.accent)
            }
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(zone.name, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = CaptainPalette.textPrimary)
                Text(
                    text = if (stats != null) {
                        "${stats.plottedVehicles} plotted · ${stats.vacantVehicles} vacant · " +
                            "${stats.bookingsLastHour + stats.streetHailsLastHour} demand/hr"
                    } else {
                        "No live statistics reported for this zone yet."
                    },
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = CaptainPalette.textSecondary,
                )
            }
            if (stats != null) {
                HudStatusPill(label = "Surge", value = SurgeModel.label(stats), tone = surgeTone(multiplier), pulsing = false)
            }
        }
    }
}
