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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette

/**
 * Direct Compose port of `driver-dashboard-full-prototype.html`'s
 * `.stat-grid`/`.stat-box` rules — a row of (value, label) boxes. `internal`
 * (module-visible, not feature-package-private) for historical reasons; its
 * two originally-intended consumers have since grown their own file-local
 * equivalents (`EarningsWheelContent.kt`'s `EarningsStatTile`,
 * `ShiftWheelContent.kt`'s private `ShiftStatGrid`), so this composable
 * currently has no call sites — kept, not deleted, in case a future screen
 * wants the plain shared version back.
 *
 * **2026-09-02 Captain Taxis pass:** last remaining file on the old v2
 * [WheelColorsV2] token set after the rest of the app's purple-redesign
 * migration — re-themed onto [CaptainPalette] here so no screen (dead-code
 * call sites included) can reintroduce the legacy look by copying this file.
 */
@Composable
internal fun StatGrid(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(CaptainPalette.raised, RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        items.forEach { (value, label) -> StatBox(value, label) }
    }
}

@Composable
private fun StatBox(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = CaptainPalette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = CaptainPalette.textSecondary, fontSize = 11.sp)
    }
}
