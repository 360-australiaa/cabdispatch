package au.com.threesixty.cabdispatch.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.hardware.printing.PrinterDevice
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.HudStatusPill
import au.com.threesixty.cabdispatch.ui.theme.HudTone
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

// W7 file split (2026-09-13): extracted from the former monolithic SettingsScreen.kt (1,437
// lines) — see that file's own doc for the split rationale. This file is the Printer tab
// ([SettingsTab.PRINTER]). [PrinterTabContent] is `internal` (not `private`) because
// `MainSettingsContent` (SettingsScreen.kt) calls it across this file boundary — same package
// (`ui.screens.settings`), so no import needed either way, only the visibility modifier changes
// from the pre-split file.

/** Printer tab — hosts the existing printer-pairing flow verbatim (scan + discovered-device list +
 * paired status), just without the `PaneShell`/back-arrow wrapper the old standalone sub-screen
 * used, since `MainSettingsContent`'s own [GlassCard] content panel already supplies the bounded
 * panel this renders inside. Deliberately not wrapped in a scrolling `Column` — the `LazyColumn`
 * below needs a bounded height to measure, which it gets from that panel; nesting it inside an
 * outer `verticalScroll` would hand it an infinite height instead and crash. HUD kit rebuild: the
 * paired-status line is now a [HudStatusPill] and each discovered device is a [GlassCard] row —
 * same discovery/pairing calls as before. */
@Composable
internal fun PrinterTabContent(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        HudStatusPill(
            label = "Printer",
            value = state.pairedPrinter?.let { "Paired: ${it.name}" } ?: "No printer paired",
            tone = if (state.pairedPrinter != null) HudTone.Success else HudTone.Neutral,
            pulsing = false,
        )
        Spacer(Modifier.height(20.dp))

        CaptainButton(
            text = if (state.printerDiscovering) "SCANNING…" else "SCAN FOR PRINTERS",
            enabled = !state.printerDiscovering,
            widthDp = 340,
            onClick = viewModel::discoverPrinters,
        )
        Spacer(Modifier.height(20.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.discoveredPrinters) { device: PrinterDevice ->
                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 14) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            device.name,
                            fontFamily = InterFamily,
                            fontSize = 16.sp,
                            color = CaptainPalette.textPrimary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                        )
                        CaptainButton(
                            text = "PAIR",
                            heightDp = 56,
                            fontSize = 18.sp,
                            widthDp = 130,
                        ) { viewModel.pairPrinter(device) }
                    }
                }
            }
        }
    }
}
