package au.com.threesixty.cabdispatch.ui.screens.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.domain.ThemeMode
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

// W7 file split (2026-09-13): extracted from the former monolithic SettingsScreen.kt (1,437
// lines) — see that file's own doc for the split rationale. This file is the Display tab
// ([SettingsTab.DISPLAY]). [DisplayTabContent] is `internal` (not `private`) because
// `MainSettingsContent` (SettingsScreen.kt) calls it across this file boundary — same package
// (`ui.screens.settings`), so no import needed either way, only the visibility modifier changes
// from the pre-split file. [ThemeSettingRow]/[ThemeModeSegment] are only ever called from within
// this file, so they stayed `private`.

@Composable
internal fun DisplayTabContent(
    state: SettingsUiState,
    onSetShowMap: (Boolean) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionLabel("MAP")
        Spacer(Modifier.height(12.dp))
        ToggleSettingRow(
            label = "Show Map in Background",
            description = "Render the live Mapbox map behind the dashboard's Live Map pane. Turn off to " +
                "save data/battery — position tracking and Plot a Zone keep working either way.",
            checked = state.showMapInBackground,
            onCheckedChange = onSetShowMap,
        )
        Spacer(Modifier.height(24.dp))
        SectionLabel("APPEARANCE")
        Spacer(Modifier.height(12.dp))
        ThemeSettingRow(themeMode = state.themeMode, onSelect = onSetThemeMode)
    }
}

/**
 * The real Theme row (2026-09-04 day-mode pass) — replaces the old permanently-[LockedSettingRow]
 * "Theme · Dark (Captain Taxis)" placeholder. A 3-way segmented picker rather than a `Switch`:
 * [ThemeMode] has three real values (the required Light/Dark plus the System nice-to-have), and a
 * row of large, equally-weighted, elderly-friendly touch targets reads its current selection at a
 * glance the way a two-state switch can't for three options. Same [GlassCard] surface as every
 * other real row on this tab; the selected segment gets a solid [CaptainPalette.primary] fill +
 * [CaptainPalette.accent] border, matching how `TwoPaneShell`'s own selected-tab treatment reads
 * "this one is active" elsewhere on this screen.
 */
@Composable
private fun ThemeSettingRow(themeMode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 16) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text("Theme", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = CaptainPalette.textPrimary)
            Text(
                "Light mode trades the glowing HUD look for a high-contrast daylight palette — easier to " +
                    "read on a dashboard-mounted tablet in direct sun.",
                fontFamily = InterFamily,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = CaptainPalette.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEach { mode ->
                    ThemeModeSegment(
                        mode = mode,
                        selected = mode == themeMode,
                        onClick = { onSelect(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeModeSegment(mode: ThemeMode, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(64.dp)
            .clip(shape)
            .background(if (selected) CaptainPalette.primary else CaptainPalette.raised)
            .border(1.dp, if (selected) CaptainPalette.accent else CaptainPalette.panelBorder, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            mode.label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = if (selected) CaptainPalette.onAccent else CaptainPalette.textSecondary,
        )
    }
}
