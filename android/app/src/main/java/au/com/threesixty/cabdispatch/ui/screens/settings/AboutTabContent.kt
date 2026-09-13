package au.com.threesixty.cabdispatch.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// W7 file split (2026-09-13): extracted from the former monolithic SettingsScreen.kt (1,437
// lines) — see that file's own doc for the split rationale. This file is the About tab
// ([SettingsTab.ABOUT]). [AboutTabContent] is `internal` (not `private`) because
// `MainSettingsContent` (SettingsScreen.kt) calls it across this file boundary — same package
// (`ui.screens.settings`), so no import needed either way, only the visibility modifier changes
// from the pre-split file. [AppVersionTile]/[RerunSetupTile]/[HeartbeatTile]/[LocateTile] are only
// ever called from within this file, so they stayed `private`.

/** About tab — app version, device heartbeat/pair-meter and the MDM locate diagnostic, plus the
 * admin-PIN-gated factory reset. Both the pair-meter and factory-reset flows still open as their
 * own full screen exactly as before this pass (`SettingsSubScreen.PAIR_METER`/
 * `SettingsSubScreen.FACTORY_RESET_PIN`) — deliberately not squeezed into the two-pane shape, per
 * this plan's own "rare, deliberate, full-attention action" call for factory reset. */
@Composable
internal fun AboutTabContent(
    state: SettingsUiState,
    onOpenPairMeter: () -> Unit,
    onRerunSetup: () -> Unit,
    onFactoryResetClick: () -> Unit,
    onUnlockSimulatorClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionLabel("DEVICE")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AppVersionTile(state, Modifier.weight(1f))
            HeartbeatTile(state, onOpenPairMeter, Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        LocateTile(state, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        // A technician servicing an ALREADY-commissioned tablet needs the checklist back. Without
        // this the only route to it was a factory reset, which wipes the pairing and the driver's
        // session to answer a question as ordinary as "is the offline map still there?".
        RerunSetupTile(onRerunSetup, Modifier.fillMaxWidth())

        Spacer(Modifier.height(24.dp))
        // W2 (inertial dead-reckoning, 2026-09-12), task 8: shadow-mode calibration/residual
        // evidence -- read-only (see InertialDiagnosticsPanel's own doc for why no PIN gate is
        // needed here). Placed ahead of the GPS simulator so a technician sees the meter's real
        // sensor state before reaching for a fabricated one.
        SectionLabel("MOTION SENSORS")
        Spacer(Modifier.height(12.dp))
        InertialDiagnosticsPanel(modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(24.dp))
        SectionLabel(
            if (SIMULATOR_REQUIRES_ADMIN_PIN) "GPS SIMULATOR (TESTING)" else "GPS SIMULATOR (TESTING · UNLOCKED)",
        )
        Spacer(Modifier.height(12.dp))
        // Admin-PIN gated. The panel drives synthetic speed/position into the same SpeedSource the
        // fare engine bills from, so an ungated one is a one-tap way for any driver to make the
        // meter run a trip that never happened — and "it's a debug build" is not a gate here,
        // because the field tablet IS a debug build. See SettingsViewModel.attemptUnlockSimulator.
        if (state.simulatorUnlocked) {
            GpsSimulatorPanel(modifier = Modifier.fillMaxWidth())
        } else {
            ActionTile(
                Icons.Rounded.Lock,
                "UNLOCK SIMULATOR · ADMIN PIN",
                onClick = onUnlockSimulatorClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Settings ▸ Diagnostics, debug-only (W4 task 7, 2026-09-12 optimisation plan) —
        // BatteryStatsPanel self-gates on BuildConfig.DEBUG and renders nothing in a release
        // build, so no admin-PIN gate is needed here the way the GPS simulator above needs one:
        // reading these counters cannot fabricate a fare or otherwise affect anything a driver or
        // the server relies on.
        Spacer(Modifier.height(24.dp))
        SectionLabel("DIAGNOSTICS")
        Spacer(Modifier.height(12.dp))
        BatteryStatsPanel(modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(24.dp))
        SectionLabel("ADVANCED")
        Spacer(Modifier.height(12.dp))
        ActionTile(
            Icons.Rounded.RestartAlt,
            "FACTORY RESET · ADMIN PIN",
            onClick = onFactoryResetClick,
            danger = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AppVersionTile(state: SettingsUiState, modifier: Modifier) {
    val (label, tone) = when (state.forceUpdateStatus) {
        ForceUpdateStatus.UNKNOWN_NO_DEVICE -> "unknown (device not paired)" to DiagTone.WARN
        ForceUpdateStatus.UNKNOWN_OFFLINE -> "unknown (offline)" to DiagTone.WARN
        ForceUpdateStatus.UNREGISTERED -> "unknown (tablet not registered)" to DiagTone.BAD
        ForceUpdateStatus.UP_TO_DATE -> "up to date" to DiagTone.OK
        ForceUpdateStatus.REQUIRED -> "update required" to DiagTone.BAD
        ForceUpdateStatus.FLAGGED_NO_RELEASE -> "flagged by depot — no newer build published" to DiagTone.WARN
    }
    DiagTile(icon = Icons.Rounded.Inventory2, name = "App version", sub = "v${state.appVersion} · $label", tone = tone, modifier = modifier)
}

/**
 * Sends a technician back through the first-install commissioning checklist.
 *
 * Clears only the "someone has set this tablet up" flag — not the pairing, not the driver session,
 * not anything the meter needs to keep working. The checklist re-reads live state, so a tablet that
 * is genuinely fine shows six ticks and a Finish button, and one that has quietly lost its offline
 * map or its GPS shows exactly which.
 */
@Composable
private fun RerunSetupTile(onRerunSetup: () -> Unit, modifier: Modifier) {
    DiagTile(
        icon = Icons.Rounded.Checklist,
        name = "Re-run setup checks",
        sub = "Technician checklist — registration, GPS, offline map, tariff",
        tone = DiagTone.OK,
        onClick = onRerunSetup,
        modifier = modifier,
    )
}

/** Binds the frame's "Live heartbeat" tile to the real one-shot heartbeat result — see class doc. */
@Composable
private fun HeartbeatTile(state: SettingsUiState, onOpenPairMeter: () -> Unit, modifier: Modifier) {
    val (sub, tone) = when (state.forceUpdateStatus) {
        // FLAGGED_NO_RELEASE belongs here too: the flag was READ off an acknowledged heartbeat.
        ForceUpdateStatus.UP_TO_DATE, ForceUpdateStatus.REQUIRED, ForceUpdateStatus.FLAGGED_NO_RELEASE ->
            "Sent on open · acknowledged by fleet server" to DiagTone.OK
        ForceUpdateStatus.UNKNOWN_OFFLINE -> "Failed — offline or server unreachable" to DiagTone.BAD
        // The server answered, and answered 404: this tablet's device record is gone (most often
        // removed by a fleet wipe). Remote lock/locate/update cannot reach it until it is paired
        // again, so the message says that rather than blaming the network.
        ForceUpdateStatus.UNREGISTERED -> "Not registered — re-pair this tablet" to DiagTone.BAD
        ForceUpdateStatus.UNKNOWN_NO_DEVICE -> "Not sent — device not registered, tap to pair" to DiagTone.WARN
    }
    // Real device pairing (2026-08-28) — tapping this tile is the entry point when unpaired, same
    // "diagnostic tile doubles as its own fix affordance" pattern the Offline-maps tile already
    // uses. Once paired (UP_TO_DATE/REQUIRED), the tile shows real status only, per DiagTile
    // convention elsewhere (Tariff signature tile stays non-clickable once cached).
    val onClick = if (state.forceUpdateStatus == ForceUpdateStatus.UNKNOWN_NO_DEVICE) onOpenPairMeter else null
    DiagTile(icon = Icons.Rounded.MonitorHeart, name = "Device heartbeat", sub = sub, tone = tone, onClick = onClick, modifier = modifier)
}

/** MDM locate-response diagnostic — only meaningful once an admin has requested a locate;
 * renders a quiet placeholder tile otherwise (About tab's own device-diagnostics section). */
@Composable
private fun LocateTile(state: SettingsUiState, modifier: Modifier) {
    val row = when (val locate = state.locateResponse) {
        LocateResponseState.Idle -> null
        LocateResponseState.Sent -> "Position sent to fleet server" to DiagTone.OK
        LocateResponseState.NoFixYet -> "Waiting for GPS fix" to DiagTone.WARN
        is LocateResponseState.Failed -> "Failed to send — ${locate.message}" to DiagTone.BAD
    }
    if (row == null) {
        Spacer(modifier)
    } else {
        DiagTile(icon = Icons.Rounded.LocationOn, name = "Locate request", sub = row.first, tone = row.second, modifier = modifier)
    }
}
