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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * Nav rail `ZONES` pane (`squishy-herding-iverson.md` Phase F) — a real tabbed Heat Map / Zone
 * List / Surge Areas / Airport Queue screen. Before this pass, the rail's ZONES item opened a
 * two-button launcher ("Plot into a zone" / "Zone statistics") that navigated away to the two
 * standalone routes below; this pane folds their real content in as tabs instead, matching the
 * mockup's layout. [PlotZoneScreen]/[PlotZoneViewModel] and [ZoneStatisticsScreen]/
 * [ZoneStatisticsViewModel] are UNCHANGED and still reachable at their own standalone routes
 * (`CabDispatchRoutes.PLOT_ZONE`/`ZONE_STATISTICS`) — nothing currently live navigates to them
 * (those routes were only ever reached from this pane's old launcher buttons, plus dead legacy
 * dashboard code already unreferenced by `CabDispatchNavHost`), but a working screen is left in
 * place rather than deleted. Each tab below reuses those same ViewModels/composables rather than
 * re-implementing them — see each tab file's own doc.
 *
 * **Surge multiplier**: computed client-side from real zone-stats data, never a fabricated field —
 * see [SurgeModel]'s doc for the exact formula and the design decision behind it.
 *
 * **Airport Queue**: this backend has no airport-specific queue/rank concept anywhere (checked —
 * see [AirportQueueTabContent]'s doc). That tab is an honest name filter over the real zone list,
 * not a fabricated feature.
 *
 * All four tabs' ViewModels are created with the default `viewModel()` key inside this pane's own
 * composition, scoped to `DeckHomeScreen`'s ViewModelStoreOwner (the "home" nav-graph entry) — so
 * they are created once and keep polling/state across tab switches, exactly like every other
 * ViewModel-backed pane in this shell.
 */
@Composable
fun ZonesPaneContent() {
    var tab by remember { mutableStateOf(ZonesTab.HEAT_MAP) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ZonesTab.entries.forEach { t -> ZonesTabPill(t.label, tab == t) { tab = t } }
        }
        Spacer(Modifier.height(16.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                ZonesTab.HEAT_MAP -> HeatMapTabContent()
                ZonesTab.ZONE_LIST -> PlotZoneTabContent()
                ZonesTab.SURGE_AREAS -> SurgeAreasTabContent()
                ZonesTab.AIRPORT_QUEUE -> AirportQueueTabContent()
            }
        }
    }
}

private enum class ZonesTab(val label: String) {
    HEAT_MAP("Heat Map"),
    ZONE_LIST("Zone List"),
    SURGE_AREAS("Surge Areas"),
    AIRPORT_QUEUE("Airport Queue"),
}

@Composable
private fun ZonesTabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) CaptainPalette.primary else CaptainPalette.raised
    val textColor = if (selected) CaptainPalette.textPrimary else CaptainPalette.textSecondary
    Box(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, if (selected) CaptainPalette.primary else CaptainPalette.panelBorder, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), color = textColor, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
