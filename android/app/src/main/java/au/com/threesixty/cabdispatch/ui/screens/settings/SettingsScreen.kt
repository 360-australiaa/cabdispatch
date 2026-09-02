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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachMoney
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.domain.GpsQuality
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.fare.AIRPORT_FIXED_FARE_MAXI
import au.com.threesixty.cabdispatch.domain.fare.AIRPORT_FIXED_FARE_STANDARD
import au.com.threesixty.cabdispatch.hardware.printing.PrinterDevice
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.screens.adminpin.AdminPinGateScreen
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainChip
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.CaptainPanel
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.PaneShell

private enum class SettingsSubScreen { MAIN, PRINTER_PAIRING, FARE_SCHEDULE, FACTORY_RESET_PIN, PAIR_METER }

/**
 * 31 · Settings & Diagnostics — Captain Taxis purple redesign (2026-08-29 pass), migrated off the
 * old yellow/black `Deck` palette onto [CaptainPalette] to match the redesigned Home dashboard
 * ([au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]). Presentation-only rewrite:
 * every [SettingsViewModel] read/call below (GPS/network polling, printer discovery/pairing,
 * offline-map download states, fare schedule, force-update + heartbeat, MDM locate response,
 * admin-PIN-gated factory reset) is the exact same [SettingsUiState] surface as before, and
 * [onFactoryReset] still fires via `LaunchedEffect(factoryResetComplete)`.
 *
 * Each sub-screen is now wrapped in the shared [PaneShell] (title + back-arrow rail + bordered
 * panel) that Home's own reused panes use, so Settings reads as part of the same design system
 * rather than a bespoke one-off. The diagnostics grid keeps its no-scroll 2×4 shape (100dp tiles:
 * icon · name · live sub-line · status dot, warning/danger border on non-nominal) plus a row of
 * action tiles. Grid tiles double as tap targets where a real action exists (Printer → pairing,
 * Offline maps → download, Tariff signature → fare schedule).
 *
 * Frame deviations, flagged: the "Live heartbeat" tile binds to the real one-shot device
 * heartbeat this ViewModel already sends on open ([SettingsUiState.forceUpdateStatus] — there is
 * no periodic background publisher on this screen, see [SettingsViewModel.loadDeviceStatus]'s
 * doc), not a recurring-poll sample. "Meter calibration" has no backing state anywhere
 * ([SettingsUiState] has none) so that slot instead shows the real MDM locate-request diagnostic
 * when one is active.
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CaptainPalette.bg)
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        PaneShell(title = "Settings & diagnostics", onBack = onBack) {
            Column(modifier = Modifier.fillMaxSize()) {
                // --- 2×4 diagnostics grid — all live ViewModel state ---
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        GpsTile(state, Modifier.weight(1f))
                        NetworkTile(state, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        DiagTile(
                            icon = Icons.Rounded.Print,
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
                            icon = Icons.Rounded.VerifiedUser,
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

                // --- Action row (96dp tiles, icon + label) ---
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ActionTile(Icons.Rounded.AttachMoney, "FARE SCHEDULE", onClick = onOpenFareSchedule, modifier = Modifier.weight(1f))
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
                    ActionTile(
                        Icons.Rounded.RestartAlt,
                        "FACTORY RESET · ADMIN PIN",
                        onClick = onFactoryResetClick,
                        danger = true,
                        modifier = Modifier.weight(1.4f),
                    )
                }
            }
        }
    }
}

// --- Diagnostics tiles ---

private enum class DiagTone(val dot: Color) {
    OK(CaptainPalette.success),
    WARN(CaptainPalette.warning),
    BAD(CaptainPalette.danger),
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

@Composable
private fun AppVersionTile(state: SettingsUiState, modifier: Modifier) {
    val (label, tone) = when (state.forceUpdateStatus) {
        ForceUpdateStatus.UNKNOWN_NO_DEVICE -> "unknown (device not paired)" to DiagTone.WARN
        ForceUpdateStatus.UNKNOWN_OFFLINE -> "unknown (offline)" to DiagTone.WARN
        ForceUpdateStatus.UP_TO_DATE -> "up to date" to DiagTone.OK
        ForceUpdateStatus.REQUIRED -> "update required" to DiagTone.BAD
    }
    DiagTile(icon = Icons.Rounded.Inventory2, name = "App version", sub = "v${state.appVersion} · $label", tone = tone, modifier = modifier)
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
    DiagTile(icon = Icons.Rounded.MonitorHeart, name = "Device heartbeat", sub = sub, tone = tone, onClick = onClick, modifier = modifier)
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
        DiagTile(icon = Icons.Rounded.LocationOn, name = "Locate request", sub = row.first, tone = row.second, modifier = modifier)
    }
}

/** One 100dp diagnostics tile: icon · name 18sp semibold · sub 16sp · 12dp status dot; amber/red
 * 1.5dp border when non-nominal. */
@Composable
private fun DiagTile(
    icon: ImageVector,
    name: String,
    sub: String,
    tone: DiagTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    val borderMod = when (tone) {
        DiagTone.OK -> Modifier.border(1.dp, CaptainPalette.panelBorder, shape)
        DiagTone.WARN -> Modifier.border(1.5.dp, CaptainPalette.warning.copy(alpha = 0.7f), shape)
        DiagTone.BAD -> Modifier.border(1.5.dp, CaptainPalette.danger.copy(alpha = 0.7f), shape)
    }
    Row(
        modifier = modifier
            .height(100.dp)
            .clip(shape)
            .background(CaptainPalette.raised)
            .then(borderMod)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(CaptainPalette.panel).border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = CaptainPalette.textPrimary)
            Text(
                sub,
                fontFamily = InterFamily,
                fontSize = 16.sp,
                color = if (tone == DiagTone.OK) CaptainPalette.textMuted else tone.dot,
            )
        }
        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(tone.dot))
    }
}

/** 96dp action tile: icon above a bold label, elderly-friendly button sizing; danger-tinted for
 * the factory reset. */
@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(16.dp)
    val borderColor = if (danger) CaptainPalette.danger.copy(alpha = 0.7f) else CaptainPalette.panelBorder
    val tint = if (danger) CaptainPalette.danger else CaptainPalette.accent
    Column(
        modifier = modifier
            .height(96.dp)
            .clip(shape)
            .background(CaptainPalette.raised)
            .border(1.5.dp, borderColor, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = if (danger) CaptainPalette.danger else CaptainPalette.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

// --- Sub-screens (same SettingsViewModel wiring as before, reskinned to CaptainPalette tokens) ---

@Composable
private fun PrinterPairingContent(state: SettingsUiState, viewModel: SettingsViewModel, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CaptainPalette.bg)
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        PaneShell(title = "Printer pairing", onBack = onBack) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    state.pairedPrinter?.let { "Paired: ${it.name}" } ?: "No printer paired",
                    fontFamily = InterFamily,
                    fontSize = 16.sp,
                    color = CaptainPalette.textSecondary,
                )
                Spacer(Modifier.height(20.dp))

                CaptainButton(
                    text = if (state.printerDiscovering) "SCANNING…" else "SCAN FOR PRINTERS",
                    enabled = !state.printerDiscovering,
                    widthDp = 340,
                    onClick = viewModel::discoverPrinters,
                )
                Spacer(Modifier.height(20.dp))

                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.discoveredPrinters) { device: PrinterDevice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CaptainPalette.panel)
                                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp))
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(device.name, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textPrimary)
                            CaptainButton(
                                text = "PAIR",
                                heightDp = 56,
                                fontSize = 18.sp,
                                widthDp = 130,
                            ) { viewModel.pairPrinter(device.id) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Fare Schedule — NSW Point to Point Transport Regulation 2017 cl.15 fare-display requirement.
 * 2026-09-02 compliance pass ([FARE_SCHEDULE_COMPLIANCE_2026.md] at repo root): this screen used to
 * show only the raw metered rate rows; it now also carries the cl.15(1A) Taxi Fare Hotline notice
 * (no prior analog anywhere in the app) and explains, in plain driver-facing language, the three
 * regulated charges the old screen displayed no context for at all — the maxi-cab 150% condition,
 * the Passenger Service Levy, and the cleaning-fee cap — plus the Sydney Airport Fixed Fare. Every
 * dollar figure and percentage below is read live off [SettingsUiState.fareSchedule] (the signed
 * active [TariffDto]) or, for the two figures the wire tariff doesn't carry yet (the cleaning-fee
 * cap and the Sydney Airport Fixed Fare amounts), off the same [au.com.threesixty.cabdispatch.domain.fare]
 * constants the engine itself bills from — never a new hardcoded literal. Only the Hotline number
 * and its explanatory copy are static text, because that's a fixed regulatory number, not a
 * tenant/tariff value.
 */
@Composable
private fun FareScheduleContent(state: SettingsUiState, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CaptainPalette.bg)
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        PaneShell(title = "Fare schedule", onBack = onBack) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Rates displayed to passengers per the taxi fare regulations (cl.15 display requirement).",
                    fontFamily = InterFamily,
                    fontSize = 16.sp,
                    color = CaptainPalette.textMuted,
                )
                Spacer(Modifier.height(20.dp))

                TaxiFareHotlineNotice()
                Spacer(Modifier.height(20.dp))

                val tariff = state.fareSchedule
                when {
                    state.fareScheduleLoading -> CircularProgressIndicator(color = CaptainPalette.accent)
                    tariff == null -> Text(
                        "No cached fare schedule available.",
                        fontFamily = InterFamily,
                        fontSize = 16.sp,
                        color = CaptainPalette.textSecondary,
                    )
                    else -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(CaptainPalette.panel)
                                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(18.dp))
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(tariff.name, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = CaptainPalette.textPrimary)
                            Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.panelBorder))
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

                        MaxiCabFaresSection(maxiMultiplier = tariff.maxiMultiplier)
                        AdditionalChargesSection(pslAmount = tariff.pslAmount, cleaningFeeCap = tariff.cleaningFeeCap)
                        SydneyAirportFixedFareSection()
                    }
                }
            }
        }
    }
}

@Composable
private fun FareScheduleRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textSecondary)
        Text("$$value", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
    }
}

@Composable
private fun FareScheduleSectionTitle(text: String) {
    Text(
        text,
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = CaptainPalette.accent,
    )
}

@Composable
private fun FareScheduleNote(text: String) {
    Text(
        text,
        fontFamily = InterFamily,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        color = CaptainPalette.textSecondary,
    )
}

/**
 * Regulation cl.15(1A) requires every vehicle to display, in the Commissioner's approved form, the
 * Taxi Fare Hotline number and a statement that the meter must always be on during a rank or hail
 * trip — this app had no such notice anywhere before this pass. The number itself and this
 * explanatory copy are fixed regulatory text, not tariff data, so they're static (not read off
 * [TariffDto]) — a QR code linking to the hotline is the Commissioner's approved form's "ideally"
 * addition, not a hard requirement, and is left as a future nice-to-have (no QR library in this
 * project yet) rather than fabricated here.
 */
@Composable
private fun TaxiFareHotlineNotice() {
    CaptainPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FareScheduleSectionTitle("TAXI FARE HOTLINE")
            Text(
                "1800 500 410",
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 30.sp,
                color = CaptainPalette.textPrimary,
            )
            FareScheduleNote("Ask your driver, or call this number, if you believe you've been charged incorrectly.")
            Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.panelBorder))
            FareScheduleNote("The meter must always be switched on during a rank or hail trip.")
        }
    }
}

/**
 * Fares Order 2026 cl 2(d) — a maxi-cab (5+ seats excl. driver) may charge up to the configured
 * multiplier only when carrying 5+ passengers, or when requested as a maxi at a Sydney Airport
 * rank — never for a wheelchair-accessible hiring. The percentage shown is derived from
 * [TariffDto.maxiMultiplier] (e.g. "1.5" -> "150%"), never a hardcoded "150%" literal, so this
 * stays correct if the configured multiplier ever changes.
 */
@Composable
private fun MaxiCabFaresSection(maxiMultiplier: String) {
    CaptainPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FareScheduleSectionTitle("MAXI-CAB FARES")
            CaptainChip(label = "MAXI RATE", value = "${formatMaxiPercent(maxiMultiplier)}%")
            FareScheduleNote(
                "A maxi-cab (5 or more seats, excluding the driver) may charge up to this rate on the fare " +
                    "only when carrying 5 or more passengers, or when a passenger requests a maxi-cab at a " +
                    "Sydney Airport rank. This never applies to a wheelchair-accessible hiring. " +
                    "(Point to Point Transport (Fares) Order 2026, cl 2(d).)",
            )
        }
    }
}

/**
 * Passenger Service Levy (cl 3) and cleaning-fee cap (cl 2(f)) — the old screen showed neither.
 * Both come live off [TariffDto] ([TariffDto.pslAmount] / [TariffDto.cleaningFeeCap], the latter
 * added server-side alongside the 2026 rate-card pass) rather than a hardcoded literal, so this
 * stays correct if a tenant's configured tariff ever sets a lower cap than the Order's own
 * maximum. Also carries the tolls pass-through rule (no numeric field — tolls vary per trip, so
 * there's nothing to display but the rule itself).
 */
@Composable
private fun AdditionalChargesSection(pslAmount: String, cleaningFeeCap: String) {
    CaptainPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FareScheduleSectionTitle("ADDITIONAL CHARGES")
            CaptainChip(label = "PASSENGER SERVICE LEVY", value = "$$pslAmount")
            FareScheduleNote(
                "Optional to pass on to the passenger. Charged once per trip, regardless of the number of " +
                    "passengers. (Fares Order 2026, cl 3.)",
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.panelBorder))
            CaptainChip(label = "CLEANING FEE CAP", value = "$$cleaningFeeCap + GST")
            FareScheduleNote(
                "Only chargeable when soiling means the vehicle can't reasonably be used before it's " +
                    "cleaned. (Fares Order 2026, cl 2(f).)",
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.panelBorder))
            FareScheduleNote(
                "Tolls are passed on at the actual cost incurred during this hiring only — never a " +
                    "\"return\" toll.",
            )
        }
    }
}

/**
 * Sydney Airport Fixed Fare Trial (cl 5) — non-booked journey from a Sydney Airport rank to the
 * CBD trial area. $60/$80 are all-inclusive regulated flat figures, unchanged by the 2026 Order
 * (see [AIRPORT_FIXED_FARE_STANDARD]/[AIRPORT_FIXED_FARE_MAXI]'s own doc), so this reads those
 * same engine constants rather than a new hardcoded literal here.
 */
@Composable
private fun SydneyAirportFixedFareSection() {
    CaptainPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FareScheduleSectionTitle("SYDNEY AIRPORT FIXED FARE")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CaptainChip(label = "STANDARD", value = "$${AIRPORT_FIXED_FARE_STANDARD.toPlainString()}")
                CaptainChip(label = "MAXI", value = "$${AIRPORT_FIXED_FARE_MAXI.toPlainString()}")
            }
            FareScheduleNote(
                "All-inclusive fare for a non-booked journey from a Sydney Airport rank to the defined CBD " +
                    "trial area. (Fares Order 2026, cl 5.)",
            )
        }
    }
}

/** Formats a tariff's [TariffDto.maxiMultiplier] wire string ("1.5") as a driver-facing whole or
 * one-decimal percentage ("150"), so the maxi-cab section never hardcodes "150%" as a literal —
 * falls back to the regulated 150% default only if the wire value is somehow unparseable. */
private fun formatMaxiPercent(multiplier: String): String {
    val pct = (multiplier.toDoubleOrNull() ?: 1.5) * 100
    return if (pct == pct.toLong().toDouble()) pct.toLong().toString() else "%.1f".format(pct)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CaptainPalette.bg)
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        PaneShell(title = "Pair meter", onBack = onBack) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    "Enter the 8-character code shown on the dashboard, or scan its QR.",
                    fontFamily = InterFamily,
                    fontSize = 16.sp,
                    color = CaptainPalette.textSecondary,
                )
                Spacer(Modifier.height(24.dp))

                when (pairState) {
                    is PairMeterState.Success -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(CaptainPalette.success.copy(alpha = 0.12f))
                                .border(1.5.dp, CaptainPalette.success, RoundedCornerShape(16.dp))
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = CaptainPalette.success, modifier = Modifier.size(24.dp))
                            Text(
                                "Paired" + (pairState.vehicleId?.let { " — vehicle $it" } ?: ""),
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp,
                                color = CaptainPalette.success,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CaptainPalette.panel)
                                .border(2.dp, CaptainPalette.accent, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                code.ifEmpty { "········" },
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 28.sp,
                                letterSpacing = 6.sp,
                                color = if (code.isEmpty()) CaptainPalette.textMuted else CaptainPalette.textPrimary,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        PairCodeKeyRows(
                            onKey = { c -> if (code.length < 8) code += c },
                            onBackspace = { code = code.dropLast(1) },
                        )
                        Spacer(Modifier.height(16.dp))
                        if (pairState is PairMeterState.Error) {
                            Text(pairState.message, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.warning)
                            Spacer(Modifier.height(12.dp))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            CaptainButton(
                                text = "SCAN QR",
                                outline = true,
                                modifier = Modifier.weight(1f),
                                enabled = pairState !is PairMeterState.Submitting,
                            ) { viewModel.scanPairingQr(activity) }
                            CaptainButton(
                                text = if (pairState is PairMeterState.Submitting) "PAIRING…" else "PAIR",
                                modifier = Modifier.weight(1f),
                                enabled = code.length == 8 && pairState !is PairMeterState.Submitting,
                            ) { viewModel.submitPairingCode(code) }
                        }
                    }
                }
            }
        }
    }
}

/** Same alphanumeric-grid shape as `LoginVehicleBindScreen`'s rego keypad, kept separate (not
 * extracted to a shared composable) since that one is `private` in a different screen and this is
 * a small, one-off widget — not worth a cross-screen refactor for. Pairing codes exclude
 * `0/1/O/I` server-side (transcription-ambiguity avoidance, per spec) — filtered out of the
 * keyboard entirely so a driver physically cannot type a character the server would reject. Not
 * [au.com.threesixty.cabdispatch.ui.theme.CaptainKeypad]: that shared widget is a numeric-only 0-9
 * pad, and this one needs the full A-Z/2-9 alphabet. */
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
                            .height(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(CaptainPalette.raised)
                            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(8.dp))
                            .clickable { if (c == '⌫') onBackspace() else onKey(c) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(c.toString(), fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
                    }
                }
            }
        }
    }
}
