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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.remote.ZoneStatsDto
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * 26 · Zone Statistics — Command Deck v2 port (Figma `h0PSsXQ971dOJvt25tN7BA` nodes `25:175`
 * populated / `25:340` unavailable). [ZoneStatisticsViewModel]/[ZoneStatisticsUiState] and the
 * 20s polling loop are unchanged — layout/tokens only. The frame's "⟳ auto-refresh 20 s" chip
 * states the ViewModel's REAL refresh interval.
 *
 * Chrome note: same as [PlotZoneScreen] — the frames' status strip/nav rail belong to the home
 * shell; this standalone route owns the whole canvas without chrome.
 *
 * Honesty notes:
 * - The unavailable frame's "⟳ RETRY NOW" button is NOT reproduced: the ViewModel exposes no
 *   manual-refresh method (refreshOnce is private to its polling loop), so the card states the
 *   truth instead — retries happen automatically every 20 s. Its "Last snapshot: 4:01 PM" line
 *   is likewise dropped (no snapshot timestamp exists on the state).
 * - The frame's hot-zone highlight/tip is computed from the REAL rows (highest bookings+hails
 *   demand, shown only when any demand exists) — never hardcoded to "Airport".
 * - Rows beyond the fixed table height scroll INSIDE the table container only.
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
            .background(Deck.canvas)
            .padding(start = 72.dp, end = 72.dp, top = 44.dp, bottom = 36.dp),
    ) {
        // Header (Figma 25:226 + 25:338): title · auto-refresh chip · back-to-plot.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Zone statistics — live supply & demand",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = Deck.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Deck.card)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "⟳ auto-refresh 20 s",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = Deck.textSecondary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .width(220.dp)
                    .height(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Deck.card)
                    .clickable { navController.popBackStack() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "← BACK TO PLOT",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Deck.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        when {
            state.loading && state.stats.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Deck.yellow)
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
                        color = Deck.hired,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                    )
                }
                HotZoneTip(stats = state.stats)
            }
        }
    }
}

// Column widths — Figma 25:231's exact 1084 split, kept as weights of the available width.
private const val ZONE_COL = 300f
private const val PLOTTED_COL = 130f
private const val VACANT_COL = 130f
private const val BUSY_COL = 130f
private const val JOBS_COL = 140f
private const val BOOKINGS_COL = 130f
private const val HAILS_COL = 124f

/** Frame `25:291`'s hot-row amber-tinted fill — introduced by the populated frame. */
private val HotRowBg = Color(0xFF22160B)

/** The zone this table should call out as hottest: most demand (bookings + hails last hour),
 * only when any demand exists at all. Pure derivation from the real rows. */
private fun hottestZone(stats: List<ZoneStatsDto>): ZoneStatsDto? =
    stats.maxByOrNull { it.bookingsLastHour + it.streetHailsLastHour }
        ?.takeIf { it.bookingsLastHour + it.streetHailsLastHour > 0 }

/** Figma `25:230` — panel table, 52dp card-toned header row + 72dp rows. Rows scroll inside. */
@Composable
private fun StatsTable(stats: List<ZoneStatsDto>, modifier: Modifier = Modifier) {
    val hot = hottestZone(stats)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Deck.panel)
            .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(18.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).background(Deck.card),
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
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(stats, key = { it.zoneId }) { row ->
                StatsRow(row = row, hot = row.zoneId == hot?.zoneId)
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
            .background(if (hot) HotRowBg else Deck.panel),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(ZONE_COL).padding(start = 24.dp)) {
            Text(
                "${row.zoneNumber} · ${row.zoneName}",
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = if (hot) Deck.stopped else Deck.textPrimary,
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
        color = Deck.textMuted,
        textAlign = align,
        maxLines = 1,
        modifier = Modifier
            .weight(weight)
            .padding(start = if (align == TextAlign.Start) 24.dp else 0.dp),
    )
}

/** Chakra Petch 22 figure — muted at 0, primary otherwise; demand columns go amber when high
 * (≥5/hr), matching the populated frame's amber demand figures (a presentation threshold). */
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
            value == 0 -> Deck.textMuted
            demand && value >= 5 -> Deck.stopped
            else -> Deck.textPrimary
        },
        textAlign = TextAlign.Center,
        modifier = Modifier.weight(weight),
    )
}

/** Figma `25:336` — amber hot-zone tip bar, composed from the real hottest row (hidden when no
 * zone reports any demand). */
@Composable
private fun HotZoneTip(stats: List<ZoneStatsDto>) {
    val hot = hottestZone(stats) ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(Deck.R_MD.dp))
            .background(Deck.stopped.copy(alpha = 0.1f))
            .border(1.dp, Deck.stopped.copy(alpha = 0.5f), RoundedCornerShape(Deck.R_MD.dp))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "🔥 ${hot.zoneName}: ${hot.streetHailsLastHour} street hails + ${hot.bookingsLastHour} bookings " +
                "in the last hour and ${hot.vacantVehicles} vacant cars — best plot right now",
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            color = Deck.stopped,
            maxLines = 1,
        )
    }
}

/**
 * 26b — unavailable state (Figma `25:392`): big panel card with 📡, headline, explanation, and
 * the frame's three fading skeleton bars. Doubles as the no-zones-configured state (no dedicated
 * frame exists for it) with matching honest copy. No RETRY button — see file doc.
 */
@Composable
private fun UnavailableCard(modifier: Modifier = Modifier, error: String?) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Deck.panel)
            .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(24.dp))
            .padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Text("📡", fontSize = 52.sp)
        Text(
            text = if (error != null) {
                "Statistics unavailable — reconnecting to the fleet server"
            } else {
                "No zones reporting statistics yet"
            },
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = Deck.textPrimary,
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
            color = Deck.textSecondary,
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
                    .background(Deck.card),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}
