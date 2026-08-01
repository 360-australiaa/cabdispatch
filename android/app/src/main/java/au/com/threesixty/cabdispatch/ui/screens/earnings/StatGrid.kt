package au.com.threesixty.cabdispatch.ui.screens.earnings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.ui.theme.WheelColors

/**
 * Direct Compose port of `driver-dashboard-full-prototype.html`'s
 * `.stat-grid`/`.stat-box` rules — a row of (value, label) boxes. Shared by
 * the Earnings and Shift wheel-content panes (both spec'd as "stat grid" in
 * §4), `internal` (module-visible, not feature-package-private) since both
 * consumers live in this app module just under different `ui/screens/*`
 * packages.
 */
@Composable
internal fun StatGrid(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { (value, label) -> StatBox(value, label) }
    }
}

@Composable
private fun StatBox(value: String, label: String) {
    Column(
        modifier = Modifier
            .widthIn(min = 120.dp)
            .background(WheelColors.surfaceRaised.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .border(1.dp, WheelColors.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(value, color = WheelColors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = WheelColors.textSecondary, fontSize = 11.sp)
    }
}
