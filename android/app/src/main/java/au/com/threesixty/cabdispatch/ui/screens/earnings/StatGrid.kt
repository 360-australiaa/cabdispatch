package au.com.threesixty.cabdispatch.ui.screens.earnings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.ui.theme.WheelColorsV2

/**
 * Direct Compose port of `driver-dashboard-full-prototype.html`'s
 * `.stat-grid`/`.stat-box` rules — a row of (value, label) boxes. Shared by
 * the Earnings and Shift wheel-content panes (both spec'd as "stat grid" in
 * §4), `internal` (module-visible, not feature-package-private) since both
 * consumers live in this app module just under different `ui/screens` sub-
 * packages.
 *
 * **2026-08-27 fidelity pass:** was the one remaining screen still on the old v1
 * [au.com.threesixty.cabdispatch.ui.theme.WheelColors] token set after the rest of the app's
 * Phase B v2 migration (`WheelColorsV2`, sourced from Figma fileKey `JhEhok3n9bntRNS5Y1u3Yc`) —
 * flagged as a known straggler, fixed here. Matches the Figma Earnings frame's stat-row style
 * (node 8:187: centered columns, single glass panel, 28px gap) rather than v1's separate
 * bordered boxes per stat.
 */
@Composable
internal fun StatGrid(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(WheelColorsV2.rowGlassStrong, RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        items.forEach { (value, label) -> StatBox(value, label) }
    }
}

@Composable
private fun StatBox(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = WheelColorsV2.mutedFigure, fontSize = 11.sp)
    }
}
