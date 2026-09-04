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

/**
 * 26 · Zone Statistics — reskinned onto [CaptainPalette] (2026-08-29 purple migration pass).
 * [ZoneStatisticsViewModel]/[ZoneStatisticsUiState] and the 20s polling loop are unchanged —
 * layout/tokens only. The "auto-refresh 20 s" chip states the ViewModel's REAL refresh interval.
 *
 * Chrome note: same as [PlotZoneScreen] — a persistent status strip belongs to the home shell;
 * this standalone route owns the whole canvas without chrome.
 *
 * Honesty notes:
 * - A manual "RETRY NOW" button is NOT reproduced: the ViewModel exposes no manual-refresh method
 *   (refreshOnce is private to its polling loop), so the card states the truth instead — retries
 *   happen automatically every 20 s. No snapshot timestamp is shown either (none exists on state).
 * - The hot-zone highlight/tip is computed from the REAL rows (highest bookings+hails demand,
 *   shown only when any demand exists) — never hardcoded to a fixed zone.
 * - Rows beyond the fixed table height scroll INSIDE the table container only.
 *
 * **HUD kit rebuild (2026-09-04).** [StatsTable]/[HotZoneTip]/[UnavailableCard] are now [GlassCard]s
 * (were flat `panel`-background containers) — same header-row-then-purple-divider convention
 * `TripsWheelContent`'s history table already uses, for one consistent chrome language app-wide.
 * [SurgeAreaCard] (the Surge Areas tab) now carries its multiplier as a [HudStatusPill] instead of a
 * hand-rolled colored badge, toned via [surgeTone] — 1.0x neutral, 1.2x accent, 1.6x warning, 2.0x
 * danger, a pure presentation mapping over [SurgeModel]'s existing real bands. The table's own
 * columns/numbers and every ViewModel/polling behaviour are unchanged.
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
            .background(CaptainPalette.bg)
            .padding(start = 72.dp, end = 72.dp, top = 44.dp, bottom = 36.dp),
    ) {
        // Header: title · auto-refresh chip · back-to-plot.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Zone statistics — live supply & demand",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = CaptainPalette.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(CaptainPalette.raised)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = CaptainPalette.textSecondary, modifier = Modifier.size(15.dp))
                Text(
                    "auto-refresh 20 s",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = CaptainPalette.textSecondary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .width(220.dp)
                    .height(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CaptainPalette.raised)
                    .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(12.dp))
                    .clickable { navController.popBackStack() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "BACK TO PLOT",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = CaptainPalette.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        when {
            state.loading && state.stats.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = CaptainPalette.accent)
            }
            state.stats.isEmpty() -> UnavailableCard(
                modifier = Modifier.weight(1f),
                error = state.error,
            )
            else -> {
                StatsTable(stats = state.stats, modifier = Modifier.weight(1f))
                Spacer(Modifier.height(14.dp))
                if (state.error != null) {
                    Text(
                        "Refresh failed — showing the last received figures. ${state.error}",
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        color = CaptainPalette.danger,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                    )
                }
                HotZoneTip(stats = state.stats)
            }
        }
    }
}

// Column widths — kept as weights of the available width.
private const val ZONE_COL = 300f
private const val PLOTTED_COL = 130f
private const val VACANT_COL = 130f
private const val BUSY_COL = 130f
private const val JOBS_COL = 140f
private const val BOOKINGS_COL = 130f
private const val HAILS_COL = 124f

/** The zone this table should call out as hottest: most demand (bookings + hails last hour),
 * only when any demand exists at all. Pure derivation from the real rows. */
private fun hottestZone(stats: List<ZoneStatsDto>): ZoneStatsDto? =
    stats.maxByOrNull { it.bookingsLastHour + it.streetHailsLastHour }
        ?.takeIf { it.bookingsLastHour + it.streetHailsLastHour > 0 }

/** [SurgeModel.multiplier] band -> [HudTone], per the HUD kit rebuild's tone convention — 1.0x
 * neutral, 1.2x accent, 1.6x warning, 2.0x danger. A pure presentation mapping over [SurgeModel]'s
 * existing real bands/formula; computes nothing new. */
internal fun surgeTone(multiplier: Double): HudTone = when {
    multiplier <= 1.0 -> HudTone.Neutral
    multiplier <= 1.2 -> HudTone.Accent
    multiplier <= 1.6 -> HudTone.Warning
    else -> HudTone.Danger
}

/** [GlassCard] table, 52dp header row + 72dp rows, header/rows separated by the same purple
 * divider convention `TripsWheelContent`'s history table uses. Rows scroll inside. */
@Composable
private fun StatsTable(stats: List<ZoneStatsDto>, modifier: Modifier = Modifier) {
    val hot = hottestZone(stats)
    GlassCard(modifier = modifier.fillMaxWidth(), cornerRadiusDp = 18) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderCell("ZONE", ZONE_COL, align = TextAlign.Start)
                HeaderCell("PLOTTED", PLOTTED_COL)
                HeaderCell("VACANT", VACANT_COL)
                HeaderCell("BUSY", BUSY_COL)
                HeaderCell("JOBS HOLDING", JOBS_COL)
                HeaderCell("BOOKINGS/HR", BOOKINGS_COL)
                HeaderCell("HAILS/HR", HAILS_COL)
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.hudGlassBorderPurple))
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(stats, key = { it.zoneId }) { row ->
                    StatsRow(row = row, hot = row.zoneId == hot?.zoneId)
                }
            }
        }
    }
}

@Composable
private fun StatsRow(row: ZoneStatsDto, hot: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(if (hot) CaptainPalette.warning.copy(alpha = 0.08f) else CaptainPalette.panel),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(ZONE_COL).padding(start = 24.dp)) {
            Text(
                "${row.zoneNumber} · ${row.zoneName}",
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = if (hot) CaptainPalette.warning else CaptainPalette.textPrimary,
                maxLines = 1,
            )
        }
        NumberCell(row.plottedVehicles, PLOTTED_COL)
        NumberCell(row.vacantVehicles, VACANT_COL)
        NumberCell(row.busyVehicles, BUSY_COL)
        NumberCell(row.jobsHolding, JOBS_COL)
        NumberCell(row.bookingsLastHour, BOOKINGS_COL, demand = true)
        NumberCell(row.streetHailsLastHour, HAILS_COL, demand = true)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(
    text: String,
    weight: Float,
    align: TextAlign = TextAlign.Center,
) {
    Text(
        text,
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = CaptainPalette.textMuted,
        textAlign = align,
        maxLines = 1,
        modifier = Modifier
            .weight(weight)
            .padding(start = if (align == TextAlign.Start) 24.dp else 0.dp),
    )
}

/** Chakra Petch 22 figure — muted at 0, primary otherwise; demand columns go amber when high
 * (≥5/hr), matching the previous presentation threshold. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.NumberCell(
    value: Int,
    weight: Float,
    demand: Boolean = false,
) {
    Text(
        value.toString(),
        fontFamily = ChakraPetch,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        color = when {
            value == 0 -> CaptainPalette.textMuted
            demand && value >= 5 -> CaptainPalette.warning
            else -> CaptainPalette.textPrimary
        },
        textAlign = TextAlign.Center,
        modifier = Modifier.weight(weight),
    )
}

/** Amber hot-zone tip bar, now a [GlassCard], composed from the real hottest row (hidden when no
 * zone reports any demand). */
@Composable
private fun HotZoneTip(stats: List<ZoneStatsDto>) {
    val hot = hottestZone(stats) ?: return
    GlassCard(modifier = Modifier.fillMaxWidth().height(58.dp), cornerRadiusDp = 14, glow = CaptainPalette.warning) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null, tint = CaptainPalette.warning, modifier = Modifier.size(20.dp))
            Text(
                "${hot.zoneName}: ${hot.streetHailsLastHour} street hails + ${hot.bookingsLastHour} bookings " +
                    "in the last hour and ${hot.vacantVehicles} vacant cars — best plot right now",
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = CaptainPalette.warning,
                maxLines = 1,
            )
        }
    }
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
