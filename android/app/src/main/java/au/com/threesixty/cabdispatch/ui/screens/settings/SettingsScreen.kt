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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.hardware.printing.PrinterDevice
import au.com.threesixty.cabdispatch.ui.deck.DeckButton
import au.com.threesixty.cabdispatch.ui.deck.DeckButtonKind
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.screens.adminpin.AdminPinGateScreen
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

private enum class SettingsSubScreen { MAIN, PRINTER_PAIRING, FARE_SCHEDULE, FACTORY_RESET_PIN, PAIR_METER }

/**
 * 31 · Settings & Diagnostics — Command Deck v2 port (Figma `h0PSsXQ971dOJvt25tN7BA` node
 * `28:107`). Presentation-only rewrite: every [SettingsViewModel] read/call below (GPS/network
 * polling, printer discovery/pairing, offline-map download states, fare schedule, force-update +
 * heartbeat, MDM locate response, admin-PIN-gated factory reset) is the exact same [SettingsUiState]
 * surface as before, and [onFactoryReset] still fires via `LaunchedEffect(factoryResetComplete)`.
 *
 * The old scrolling two-pane layout is replaced by the frame's no-scroll 2×4 diagnostics grid
 * (100dp tiles: emoji · name · live sub-line · status dot, amber border on warn) plus a row of
 * outline action buttons and a ghost "← Dashboard". Grid tiles double as tap targets where a real
 * action exists (Printer → pairing, Offline maps → download, Tariff signature → fare schedule).
 *
 * Frame deviations, flagged: the "Live heartbeat" tile binds to the real one-shot device
 * heartbeat this ViewModel already sends on open ([SettingsUiState.forceUpdateStatus] — there is
 * no periodic 30s publisher on this screen, see [SettingsViewModel.loadDeviceStatus]'s doc), not
 * the frame's "every 30 s · last 4:05:12 PM" sample. "Meter calibration" has no backing state
 * anywhere ([SettingsUiState] has none) so that slot instead shows the real MDM locate-request
 * diagnostic when one is active. "🔔 TEST ALARM" has no backing action and is replaced by the
 * real FARE SCHEDULE / PERMISSIONS / OFFLINE & SYNC entries this screen has always offered. The
 * status strip is dashboard-owned state — omitted (ShiftStart/Permissions port precedent).
 */
@Composable
fun SettingsScreen(
    navController: NavHostController,
    onFactoryReset: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var subScreen by remember { mutableStateOf(SettingsSubScreen.MAIN) }

    LaunchedEffect(state.factoryResetComplete) {
        if (state.factoryResetComplete) onFactoryReset()
    }

    when (subScreen) {
        SettingsSubScreen.MAIN -> MainSettingsContent(
            state = state,
            onBack = { navController.popBackStack() },
            onOpenPrinterPairing = { subScreen = SettingsSubScreen.PRINTER_PAIRING },
            onOpenFareSchedule = { subScreen = SettingsSubScreen.FARE_SCHEDULE },
            onFactoryResetClick = {
                viewModel.clearFactoryResetError()
                subScreen = SettingsSubScreen.FACTORY_RESET_PIN
            },
            onDownloadOfflineMaps = viewModel::downloadOfflineMaps,
            onOpenPermissions = { navController.navigate(CabDispatchRoutes.PERMISSIONS_CHECKLIST) },
            onOpenOfflineSync = { navController.navigate(CabDispatchRoutes.OFFLINE_SYNC) },
            onOpenPairMeter = {
                viewModel.clearPairMeterError()
                subScreen = SettingsSubScreen.PAIR_METER
            },
        )
        SettingsSubScreen.PRINTER_PAIRING -> PrinterPairingContent(
            state = state,
            viewModel = viewModel,
            onBack = { subScreen = SettingsSubScreen.MAIN },
        )
        SettingsSubScreen.FARE_SCHEDULE -> FareScheduleContent(
            state = state,
            onBack = { subScreen = SettingsSubScreen.MAIN },
        )
        SettingsSubScreen.FACTORY_RESET_PIN -> AdminPinGateScreen(
            subtitle = "Enter the admin PIN to wipe all local trip/shift data and sign out. This cannot be undone.",
            errorMessage = state.factoryResetError,
            verifying = state.factoryResetInProgress,
            onCancel = {
                viewModel.clearFactoryResetError()
                subScreen = SettingsSubScreen.MAIN
            },
            onVerify = { pin -> viewModel.attemptFactoryReset(pin) },
        )
        SettingsSubScreen.PAIR_METER -> PairMeterContent(
            state = state,
            viewModel = viewModel,
            onBack = { subScreen = SettingsSubScreen.MAIN },
        )
    }
}

@Composable
private fun MainSettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenPrinterPairing: () -> Unit,
    onOpenFareSchedule: () -> Unit,
    onFactoryResetClick: () -> Unit,
    onDownloadOfflineMaps: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenOfflineSync: () -> Unit,
    onOpenPairMeter: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Deck.canvas)
            .padding(start = 72.dp, end = 72.dp, top = 40.dp, bottom = 24.dp),
    ) {
        Text("Settings & diagnostics", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Deck.textPrimary)
        Spacer(Modifier.height(20.dp))

        // --- 2×4 diagnostics grid (frame `diagGrid`, 100dp tiles) — all live ViewModel state ---
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GpsTile(state, Modifier.weight(1f))
                NetworkTile(state, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DiagTile(
                    emoji = "🖨",
                    name = "Printer",
                    sub = state.pairedPrinter?.let { "Paired · ${it.name}" } ?: "Not paired — tap to pair",
                    tone = if (state.pairedPrinter != null) DiagTone.OK else DiagTone.WARN,
                    onClick = onOpenPrinterPairing,
                    modifier = Modifier.weight(1f),
                )
                OfflineMapsTile(state, onDownloadOfflineMaps, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DiagTile(
                    emoji = "🔏",
                    name = "Tariff signature",
                    sub = state.fareSchedule?.let { "${it.name} · cached" } ?: "No cached tariff",
                    tone = if (state.fareSchedule != null) DiagTone.OK else DiagTone.WARN,
                    onClick = onOpenFareSchedule,
                    modifier = Modifier.weight(1f),
                )
                AppVersionTile(state, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HeartbeatTile(state, onOpenPairMeter, Modifier.weight(1f))
                LocateTile(state, Modifier.weight(1f))
            }
        }

        Spacer(Modifier.weight(1f))

        // --- Action row (frame `settingsActions`, 68dp outline buttons) ---
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ActionTile("💲 FARE SCHEDULE", onClick = onOpenFareSchedule, modifier = Modifier.weight(1f))
            val mapsLabel = when (state.offlineMapDownload) {
                is OfflineMapDownloadState.Downloading -> "🗺 DOWNLOADING…"
                is OfflineMapDownloadState.Failed -> "🗺 RETRY MAPS"
                is OfflineMapDownloadState.Completed -> "🗺 MAPS READY"
                is OfflineMapDownloadState.NotStarted -> "🗺 UPDATE MAPS"
            }
            ActionTile(
                mapsLabel,
                onClick = onDownloadOfflineMaps,
                enabled = state.offlineMapDownload !is OfflineMapDownloadState.Downloading,
                modifier = Modifier.weight(1f),
            )
            ActionTile("🔐 PERMISSIONS", onClick = onOpenPermissions, modifier = Modifier.weight(1f))
            ActionTile("⇅ OFFLINE & SYNC", onClick = onOpenOfflineSync, modifier = Modifier.weight(1f))
            ActionTile(
                "⚠ FACTORY RESET · ADMIN PIN",
                onClick = onFactoryResetClick,
                danger = true,
                modifier = Modifier.weight(1.4f),
            )
        }

        Spacer(Modifier.height(16.dp))
        DeckButton(text = "← Dashboard", kind = DeckButtonKind.Ghost, modifier = Modifier.width(220.dp), onClick = onBack)
    }
}

// --- Diagnostics tiles ---

private enum class DiagTone(val dot: Color) {
    OK(Deck.forHire),
    WARN(Deck.stopped),
    BAD(Deck.hired),
}

@Composable
private fun GpsTile(state: SettingsUiState, modifier: Modifier) {
    val (sub, tone) = when (state.gpsQuality) {
        GpsQuality.GOOD -> "Lock · ±${state.gpsAccuracyM?.toInt()} m" to DiagTone.OK
        GpsQuality.FAIR -> "Fair · ±${state.gpsAccuracyM?.toInt()} m" to DiagTone.OK
        GpsQuality.POOR -> "Poor · ±${state.gpsAccuracyM?.toInt()} m" to DiagTone.WARN
        GpsQuality.NO_FIX -> "No fix" to DiagTone.BAD
        GpsQuality.PERMISSION_DENIED -> "Location permission not granted" to DiagTone.BAD
    }
    DiagTile(emoji = "🛰", name = "GPS", sub = sub, tone = tone, modifier = modifier)
}

@Composable
private fun NetworkTile(state: SettingsUiState, modifier: Modifier) {
    val (sub, tone) = when (state.networkStatus) {
        NetworkStatus.WIFI -> "Wi-Fi · connected to fleet server" to DiagTone.OK
        NetworkStatus.CELLULAR -> "Cellular · connected to fleet server" to DiagTone.OK
        NetworkStatus.OTHER -> "Connected to fleet server" to DiagTone.OK
        NetworkStatus.OFFLINE -> "Offline" to DiagTone.BAD
    }
    DiagTile(emoji = "📶", name = "Network", sub = sub, tone = tone, modifier = modifier)
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
        emoji = "🗺",
        name = "Offline maps",
        sub = sub,
        tone = tone,
        onClick = if (state.offlineMapDownload is OfflineMapDownloadState.Downloading) null else onDownload,
        modifier = modifier,
    )
}

@Composable
private fun AppVersionTile(state: SettingsUiState, modifier: Modifier) {
    val (label, tone) = when (state.forceUpdateStatus) {
        ForceUpdateStatus.UNKNOWN_NO_DEVICE -> "unknown (device not paired)" to DiagTone.WARN
        ForceUpdateStatus.UNKNOWN_OFFLINE -> "unknown (offline)" to DiagTone.WARN
        ForceUpdateStatus.UP_TO_DATE -> "up to date" to DiagTone.OK
        ForceUpdateStatus.REQUIRED -> "update required" to DiagTone.BAD
    }
    DiagTile(emoji = "📦", name = "App version", sub = "v${state.appVersion} · $label", tone = tone, modifier = modifier)
}

/** Binds the frame's "Live heartbeat" tile to the real one-shot heartbeat result — see class doc. */
@Composable
private fun HeartbeatTile(state: SettingsUiState, onOpenPairMeter: () -> Unit, modifier: Modifier) {
    val (sub, tone) = when (state.forceUpdateStatus) {
        ForceUpdateStatus.UP_TO_DATE, ForceUpdateStatus.REQUIRED ->
            "Sent on open · acknowledged by fleet server" to DiagTone.OK
        ForceUpdateStatus.UNKNOWN_OFFLINE -> "Failed — offline or server unreachable" to DiagTone.BAD
        ForceUpdateStatus.UNKNOWN_NO_DEVICE -> "Not sent — device not registered, tap to pair" to DiagTone.WARN
    }
    // Real device pairing (2026-08-28) — tapping this tile is the entry point when unpaired, same
    // "diagnostic tile doubles as its own fix affordance" pattern the Printer/Offline-maps tiles
    // already use. Once paired (UP_TO_DATE/REQUIRED), the tile shows real status only, per DiagTile
    // convention elsewhere (Tariff signature tile stays non-clickable once cached).
    val onClick = if (state.forceUpdateStatus == ForceUpdateStatus.UNKNOWN_NO_DEVICE) onOpenPairMeter else null
    DiagTile(emoji = "💓", name = "Device heartbeat", sub = sub, tone = tone, onClick = onClick, modifier = modifier)
}

/** MDM locate-response diagnostic — only meaningful once an admin has requested a locate;
 * renders a quiet placeholder tile otherwise (the frame's 8th grid slot). */
@Composable
private fun LocateTile(state: SettingsUiState, modifier: Modifier) {
    val row = when (val locate = state.locateResponse) {
        LocateResponseState.Idle -> null
        LocateResponseState.Sent -> "Position sent to fleet server" to DiagTone.OK
        LocateResponseState.NoFixYet -> "Waiting for GPS fix" to DiagTone.WARN
        LocateResponseState.NoVehicleBound -> "No vehicle bound" to DiagTone.WARN
        is LocateResponseState.Failed -> "Failed to send — ${locate.message}" to DiagTone.BAD
    }
    if (row == null) {
        Spacer(modifier)
    } else {
        DiagTile(emoji = "📡", name = "Locate request", sub = row.first, tone = row.second, modifier = modifier)
    }
}

/** One 100dp diagnostics tile (frame node 28:136 etc): emoji 26 · name 17 semibold · sub 14 ·
 * 12dp status dot; amber/red 1.5dp border when non-nominal. */
@Composable
private fun DiagTile(
    emoji: String,
    name: String,
    sub: String,
    tone: DiagTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    val borderMod = when (tone) {
        DiagTone.OK -> Modifier.border(1.dp, Deck.strokeSubtle, shape)
        DiagTone.WARN -> Modifier.border(1.5.dp, Deck.stopped.copy(alpha = 0.7f), shape)
        DiagTone.BAD -> Modifier.border(1.5.dp, Deck.hired.copy(alpha = 0.7f), shape)
    }
    Row(
        modifier = modifier
            .height(100.dp)
            .clip(shape)
            .background(Deck.panel)
            .then(borderMod)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(emoji, fontSize = 26.sp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Deck.textPrimary)
            Text(
                sub,
                fontFamily = InterFamily,
                fontSize = 14.sp,
                color = if (tone == DiagTone.OK) Deck.textMuted else tone.dot,
            )
        }
        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(tone.dot))
    }
}

/** 68dp outline action button (frame `settingsActions`); red-tinted for the factory reset. */
@Composable
private fun ActionTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    val borderColor = if (danger) Deck.hired.copy(alpha = 0.6f) else Deck.strokeStrong
    Box(
        modifier = modifier
            .height(68.dp)
            .clip(shape)
            .background(Deck.card)
            .border(1.5.dp, borderColor, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = if (danger) Deck.hired else Deck.textSecondary,
        )
    }
}

// --- Sub-screens (same SettingsViewModel wiring as before, reskinned to Deck tokens) ---

@Composable
private fun PrinterPairingContent(state: SettingsUiState, viewModel: SettingsViewModel, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Deck.canvas)
            .padding(start = 72.dp, end = 72.dp, top = 40.dp, bottom = 24.dp),
    ) {
        Text("Printer pairing", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Deck.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            state.pairedPrinter?.let { "Paired: ${it.name}" } ?: "No printer paired",
            fontFamily = InterFamily,
            fontSize = 15.sp,
            color = Deck.textSecondary,
        )
        Spacer(Modifier.height(20.dp))

        DeckButton(
            text = if (state.printerDiscovering) "SCANNING…" else "SCAN FOR PRINTERS",
            kind = DeckButtonKind.Primary,
            enabled = !state.printerDiscovering,
            modifier = Modifier.width(320.dp),
            onClick = viewModel::discoverPrinters,
        )
        Spacer(Modifier.height(20.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.discoveredPrinters) { device: PrinterDevice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Deck.panel)
                        .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(14.dp))
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(device.name, fontFamily = InterFamily, fontSize = 15.sp, color = Deck.textPrimary)
                    DeckButton(
                        text = "PAIR",
                        kind = DeckButtonKind.Primary,
                        heightDp = 44,
                        fontSize = 13,
                        modifier = Modifier.width(110.dp),
                    ) { viewModel.pairPrinter(device.id) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        DeckButton(text = "← Back", kind = DeckButtonKind.Ghost, modifier = Modifier.width(180.dp), onClick = onBack)
    }
}

@Composable
private fun FareScheduleContent(state: SettingsUiState, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Deck.canvas)
            .padding(start = 72.dp, end = 72.dp, top = 40.dp, bottom = 24.dp),
    ) {
        Text("Fare schedule", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Deck.textPrimary)
        Text(
            "Rates displayed to passengers per the taxi fare regulations (cl.15 display requirement).",
            fontFamily = InterFamily,
            fontSize = 13.sp,
            color = Deck.textMuted,
        )
        Spacer(Modifier.height(20.dp))

        val tariff = state.fareSchedule
        when {
            state.fareScheduleLoading -> CircularProgressIndicator(color = Deck.yellow)
            tariff == null -> Text(
                "No cached fare schedule available.",
                fontFamily = InterFamily,
                fontSize = 15.sp,
                color = Deck.textSecondary,
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Deck.panel)
                    .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(18.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tariff.name, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Deck.textPrimary)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Deck.strokeSubtle))
                FareScheduleRow("Hiring charge (flag fall)", tariff.flagFall)
                if (tariff.peakCharge != "0") FareScheduleRow("Peak time hiring charge", tariff.peakCharge)
                FareScheduleRow("Distance rate, first ${tariff.distKmThreshold}km", "${tariff.distRate1}/km")
                FareScheduleRow("Distance rate, beyond ${tariff.distKmThreshold}km", "${tariff.distRate2}/km")
                FareScheduleRow("Night distance rate, first ${tariff.distKmThreshold}km", "${tariff.nightRate1}/km")
                FareScheduleRow("Night distance rate, beyond ${tariff.distKmThreshold}km", "${tariff.nightRate2}/km")
                if (tariff.holidayRate1 != "0") {
                    FareScheduleRow("Holiday distance rate, first ${tariff.distKmThreshold}km", "${tariff.holidayRate1}/km")
                }
                if (tariff.holidayRate2 != "0") {
                    FareScheduleRow("Holiday distance rate, beyond ${tariff.distKmThreshold}km", "${tariff.holidayRate2}/km")
                }
                FareScheduleRow("Waiting time", "${tariff.waitingRatePerMin}/min")
                FareScheduleRow("Non-cash payment surcharge cap", "${tariff.surchargePctCap}%")
            }
        }

        Spacer(Modifier.weight(1f))
        DeckButton(text = "← Back", kind = DeckButtonKind.Ghost, modifier = Modifier.width(180.dp), onClick = onBack)
    }
}

@Composable
private fun FareScheduleRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = InterFamily, fontSize = 14.sp, color = Deck.textSecondary)
        Text("$$value", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Deck.textPrimary)
    }
}

// --- Pair Meter (2026-08-28, real device pairing — backend spec) --------------------------------

/**
 * Real "Pair Meter" screen — registers this tablet as a vehicle's meter via a short-lived
 * admin-generated code (`POST /v1/fleet/devices/register`). Two entry paths per spec: manual
 * 8-char code entry (primary — works with zero dashboard dependency) and QR scan (secondary,
 * reuses [au.com.threesixty.cabdispatch.domain.RealQrScanner] via a **separate** result handler
 * from the vehicle-bind rego scanner — same underlying ML Kit call, different semantic target,
 * per the spec's explicit "do not repoint the existing scanner" instruction).
 */
@Composable
private fun PairMeterContent(state: SettingsUiState, viewModel: SettingsViewModel, onBack: () -> Unit) {
    val activity = LocalContext.current as android.app.Activity
    var code by remember { mutableStateOf("") }
    val pairState = state.pairMeter

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Deck.canvas)
            .padding(start = 72.dp, end = 72.dp, top = 48.dp, bottom = 32.dp),
    ) {
        Text("Pair meter", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Deck.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter the 8-character code shown on the dashboard, or scan its QR.",
            fontFamily = InterFamily,
            fontSize = 15.sp,
            color = Deck.textSecondary,
        )
        Spacer(Modifier.height(28.dp))

        when (pairState) {
            is PairMeterState.Success -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Deck.forHire.copy(alpha = 0.12f))
                        .border(1.5.dp, Deck.forHire, RoundedCornerShape(16.dp))
                        .padding(20.dp),
                ) {
                    Text(
                        "✓ Paired" + (pairState.vehicleId?.let { " — vehicle $it" } ?: ""),
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = Deck.forHire,
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Deck.card)
                        .border(2.dp, Deck.yellow, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        code.ifEmpty { "········" },
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                        letterSpacing = 6.sp,
                        color = if (code.isEmpty()) Deck.textMuted else Deck.textPrimary,
                    )
                }
                Spacer(Modifier.height(16.dp))
                PairCodeKeyRows(
                    onKey = { c -> if (code.length < 8) code += c },
                    onBackspace = { code = code.dropLast(1) },
                )
                Spacer(Modifier.height(16.dp))
                if (pairState is PairMeterState.Error) {
                    Text(pairState.message, fontFamily = InterFamily, fontSize = 14.sp, color = Deck.stopped)
                    Spacer(Modifier.height(12.dp))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DeckButton(
                        text = "📷 Scan QR",
                        kind = DeckButtonKind.Outline,
                        modifier = Modifier.weight(1f),
                        enabled = pairState !is PairMeterState.Submitting,
                    ) { viewModel.scanPairingQr(activity) }
                    DeckButton(
                        text = if (pairState is PairMeterState.Submitting) "Pairing…" else "Pair",
                        kind = DeckButtonKind.Primary,
                        modifier = Modifier.weight(1f),
                        enabled = code.length == 8 && pairState !is PairMeterState.Submitting,
                    ) { viewModel.submitPairingCode(code) }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        DeckButton(text = "← Back", kind = DeckButtonKind.Ghost, modifier = Modifier.width(180.dp), onClick = onBack)
    }
}

/** Same alphanumeric-grid shape as `LoginVehicleBindScreen`'s rego keypad, kept separate (not
 * extracted to a shared composable) since that one is `private` in a different screen and this is
 * a small, one-off widget — not worth a cross-screen refactor for. Pairing codes exclude
 * `0/1/O/I` server-side (transcription-ambiguity avoidance, per spec) — filtered out of the
 * keyboard entirely so a driver physically cannot type a character the server would reject. */
@Composable
private fun PairCodeKeyRows(onKey: (Char) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ", "23456789⌫")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Deck.card)
                            .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(8.dp))
                            .clickable { if (c == '⌫') onBackspace() else onKey(c) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(c.toString(), fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Deck.textPrimary)
                    }
                }
            }
        }
    }
}
