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
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.com.threesixty.cabdispatch.domain.GpsQuality
import au.com.threesixty.cabdispatch.domain.NetworkStatus

// W7 file split (2026-09-13): extracted from the former monolithic SettingsScreen.kt (1,437
// lines) — see that file's own doc for the split rationale. This file is the General tab
// ([SettingsTab.GENERAL]). [GeneralTabContent] is `internal` (not `private`) because
// `MainSettingsContent` (SettingsScreen.kt) calls it across this file boundary — same package
// (`ui.screens.settings`), so no import needed either way, only the visibility modifier changes
// from the pre-split file. [GpsTile]/[NetworkTile]/[OfflineMapsTile] are only ever called from
// within this file, so they stayed `private`.

@Composable
internal fun GeneralTabContent(
    state: SettingsUiState,
    onSetAutoAccept: (Boolean) -> Unit,
    onDownloadOfflineMaps: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenOfflineSync: () -> Unit,
    onOpenPaymentMethodsTab: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionLabel("DIAGNOSTICS")
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GpsTile(state, Modifier.weight(1f))
                NetworkTile(state, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OfflineMapsTile(state, onDownloadOfflineMaps, Modifier.weight(1f))
                DiagTile(
                    icon = Icons.Rounded.VerifiedUser,
                    name = "Tariff signature",
                    sub = state.fareSchedule?.let { "${it.name} · cached — see Payment Methods" } ?: "No cached tariff",
                    tone = if (state.fareSchedule != null) DiagTone.OK else DiagTone.WARN,
                    onClick = onOpenPaymentMethodsTab,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("DISPATCH")
        Spacer(Modifier.height(12.dp))
        ToggleSettingRow(
            label = "Auto Accept Jobs",
            description = "Automatically accept the next job offer the instant it arrives instead of " +
                "waiting for a manual tap on Accept.",
            checked = state.autoAcceptJobs,
            onCheckedChange = onSetAutoAccept,
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel("PREFERENCES")
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LockedSettingRow(label = "Language", value = "English (Australia)")
            LockedSettingRow(label = "Units", value = "Kilometres")
        }

        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val mapsLabel = when (state.offlineMapDownload) {
                is OfflineMapDownloadState.Downloading -> "DOWNLOADING…"
                is OfflineMapDownloadState.Failed -> "RETRY MAPS"
                is OfflineMapDownloadState.Completed -> "MAPS READY"
                is OfflineMapDownloadState.NotStarted -> "UPDATE MAPS"
            }
            ActionTile(
                Icons.Rounded.Map,
                mapsLabel,
                onClick = onDownloadOfflineMaps,
                enabled = state.offlineMapDownload !is OfflineMapDownloadState.Downloading,
                modifier = Modifier.weight(1f),
            )
            ActionTile(Icons.Rounded.Shield, "PERMISSIONS", onClick = onOpenPermissions, modifier = Modifier.weight(1f))
            ActionTile(Icons.Rounded.SwapVert, "OFFLINE & SYNC", onClick = onOpenOfflineSync, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun GpsTile(state: SettingsUiState, modifier: Modifier) {
    val (sub, tone) = when (state.gpsQuality) {
        GpsQuality.GOOD -> "Lock · ±${state.gpsAccuracyM?.toInt()} m" to DiagTone.OK
        GpsQuality.FAIR -> "Fair · ±${state.gpsAccuracyM?.toInt()} m" to DiagTone.OK
        GpsQuality.POOR -> "Poor · ±${state.gpsAccuracyM?.toInt()} m" to DiagTone.WARN
        // STALE (G2, GPS blackout program, 2026-09-12): the driver-facing meaning is "the meter
        // cannot see you right now" -- worded like NO_FIX, not like a degraded-but-live POOR fix,
        // because a stale fix is not a live signal at all, just an old one still sitting there.
        GpsQuality.STALE -> "Signal lost" to DiagTone.BAD
        GpsQuality.NO_FIX -> "No fix" to DiagTone.BAD
        GpsQuality.PERMISSION_DENIED -> "Location permission not granted" to DiagTone.BAD
    }
    DiagTile(icon = Icons.Rounded.GpsFixed, name = "GPS", sub = sub, tone = tone, modifier = modifier)
}

@Composable
private fun NetworkTile(state: SettingsUiState, modifier: Modifier) {
    val (sub, tone) = when (state.networkStatus) {
        NetworkStatus.WIFI -> "Wi-Fi · connected to fleet server" to DiagTone.OK
        NetworkStatus.CELLULAR -> "Cellular · connected to fleet server" to DiagTone.OK
        NetworkStatus.OTHER -> "Connected to fleet server" to DiagTone.OK
        NetworkStatus.OFFLINE -> "Offline" to DiagTone.BAD
    }
    DiagTile(icon = Icons.Rounded.SignalCellularAlt, name = "Network", sub = sub, tone = tone, modifier = modifier)
}

@Composable
private fun OfflineMapsTile(state: SettingsUiState, onDownload: () -> Unit, modifier: Modifier) {
    val (sub, tone) = when (val dl = state.offlineMapDownload) {
        is OfflineMapDownloadState.Completed -> "${dl.regionLabel} · up to date" to DiagTone.OK
        is OfflineMapDownloadState.Downloading -> "Downloading… ${dl.progressPercent}%" to DiagTone.WARN
        is OfflineMapDownloadState.Failed -> "Download failed — ${dl.message}" to DiagTone.BAD
        is OfflineMapDownloadState.NotStarted -> "Not downloaded — tap to fetch" to DiagTone.WARN
    }
    DiagTile(
        icon = Icons.Rounded.Map,
        name = "Offline maps",
        sub = sub,
        tone = tone,
        onClick = if (state.offlineMapDownload is OfflineMapDownloadState.Downloading) null else onDownload,
        modifier = modifier,
    )
}
