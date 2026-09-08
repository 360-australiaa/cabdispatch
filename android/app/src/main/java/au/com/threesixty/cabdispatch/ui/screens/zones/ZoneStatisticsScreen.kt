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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SatelliteAlt
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
import au.com.threesixty.cabdispatch.data.remote.ZoneStatsDto
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.HudStatusPill
import au.com.threesixty.cabdispatch.ui.theme.HudTone
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.color

/** [SurgeModel.multiplier] band -> [HudTone], per the HUD kit rebuild's tone convention — 1.0x
 * neutral, 1.2x accent, 1.6x warning, 2.0x danger. A pure presentation mapping over [SurgeModel]'s
 * existing real bands/formula; computes nothing new. */
internal fun surgeTone(multiplier: Double): HudTone = when {
    multiplier <= 1.0 -> HudTone.Neutral
    multiplier <= 1.2 -> HudTone.Accent
    multiplier <= 1.6 -> HudTone.Warning
    else -> HudTone.Danger
}


/**
 * Surge Areas tab content (`squishy-herding-iverson.md` Phase F) — a filtered, sorted view of the
 * SAME live [ZoneStatisticsViewModel] data [StatsTable] renders, restricted to zones whose
 * [SurgeModel.multiplier] is above the calm 1.0x band, hottest first. Never a separate data source
 * or a fabricated list — see [SurgeModel]'s doc for the exact formula.
 */
@Composable
fun SurgeAreasTabContent(viewModel: ZoneStatisticsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Zones currently reading above 1.0x — real bookings + street hails vs real vacant " +
                    "vehicles in the last hour.",
                fontFamily = InterFamily,
                fontSize = 14.sp,
                color = CaptainPalette.textMuted,
                modifier = Modifier.weight(1f),
            )
            LastUpdatedChip(state.lastUpdatedAt)
        }
        Spacer(Modifier.height(14.dp))

        val surging = remember(state.stats) {
            state.stats
                .filter { SurgeModel.multiplier(it) > 1.0 }
                .sortedByDescending { SurgeModel.demandSupplyRatio(it) }
        }

        when {
            state.loading && state.stats.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CaptainPalette.accent)
            }
            state.stats.isEmpty() -> UnavailableCard(modifier = Modifier.weight(1f), error = state.error)
            surging.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "No zone is currently surging — every zone's vacant-vehicle supply covers its " +
                        "last hour of demand.",
                    fontFamily = InterFamily,
                    fontSize = 16.sp,
                    color = CaptainPalette.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(surging, key = { it.zoneId }) { row -> SurgeAreaCard(row) }
            }
        }
    }
}

/** Now a [GlassCard] with the multiplier carried as a [HudStatusPill] (was a hand-rolled colored
 * badge `Box`) — same [SurgeModel] value/copy, toned via [surgeTone]. */
@Composable
private fun SurgeAreaCard(row: ZoneStatsDto) {
    val multiplier = SurgeModel.multiplier(row)
    val tone = surgeTone(multiplier)
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 16, glow = tone.color()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${row.zoneNumber} · ${row.zoneName}",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = CaptainPalette.textPrimary,
                )
                Text(
                    "${row.bookingsLastHour} bookings + ${row.streetHailsLastHour} street hails/hr · " +
                        "${row.vacantVehicles} vacant vehicle${if (row.vacantVehicles == 1) "" else "s"}",
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = CaptainPalette.textSecondary,
                )
            }
            Spacer(Modifier.width(16.dp))
            HudStatusPill(label = "Surge", value = SurgeModel.label(row), tone = tone, pulsing = false)
        }
    }
}

/** "Last updated 14:32:05" — the ViewModel's real last-successful-poll time
 * ([ZoneStatisticsUiState.lastUpdatedAt]), never a fabricated "just now". Shows nothing until the
 * first poll actually succeeds. Shared by the Surge Areas and Heat Map tabs. */
@Composable
fun LastUpdatedChip(lastUpdatedAt: java.time.Instant?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CaptainPalette.raised)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.Refresh, contentDescription = null, tint = CaptainPalette.textSecondary, modifier = Modifier.size(14.dp))
        Text(
            text = lastUpdatedAt?.let {
                "Last updated " + java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .format(it.atZone(java.time.ZoneId.systemDefault()))
            } ?: "Waiting for first update…",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = CaptainPalette.textSecondary,
        )
    }
}

/**
 * 26b — unavailable state: big glass card with icon, headline, explanation, and three fading
 * skeleton bars. Doubles as the no-zones-configured state with matching honest copy. No RETRY
 * button — see file doc. Now a [GlassCard] (was a flat `panel`-background `Column`) — same content,
 * unchanged.
 */
@Composable
private fun UnavailableCard(modifier: Modifier = Modifier, error: String?) {
    GlassCard(modifier = modifier.fillMaxWidth(), cornerRadiusDp = 24) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.SatelliteAlt, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(52.dp))
            Text(
                text = if (error != null) {
                    "Statistics unavailable — reconnecting to the fleet server"
                } else {
                    "No zones reporting statistics yet"
                },
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = CaptainPalette.textPrimary,
            )
            Text(
                text = if (error != null) {
                    "Live supply & demand needs a data connection. The meter itself keeps working " +
                        "offline; zone stats retry automatically every 20 s while this screen is open. ($error)"
                } else {
                    "Your operator has not published zone demand data for this region yet. This screen " +
                        "refreshes automatically every 20 s while it is open."
                },
                fontFamily = InterFamily,
                fontSize = 16.sp,
                color = CaptainPalette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(620.dp),
            )
            listOf(0.6f, 0.45f, 0.3f).forEach { a ->
                Box(
                    modifier = Modifier
                        .width(900.dp)
                        .height(26.dp)
                        .alpha(a)
                        .clip(RoundedCornerShape(13.dp))
                        .background(CaptainPalette.raised),
                )
            }
            Spacer(Modifier.weight(1f))
        }
    }
}
