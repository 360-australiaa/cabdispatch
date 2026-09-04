package au.com.threesixty.cabdispatch.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.HudStatusPill
import au.com.threesixty.cabdispatch.ui.theme.HudTone
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.PaneShell
import au.com.threesixty.cabdispatch.ui.theme.color
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.theme.neonGlow

private enum class SettingsSubScreen { MAIN, FACTORY_RESET_PIN, PAIR_METER }

/**
 * Settings' left-rail tabs (Settings two-pane pass, 2026-09-03) — matches the mockup's fixed
 * General/Notifications/Sound & Voice/Display/Payment Methods/Printer/About list exactly, rather
 * than inventing an eighth tab for Fare Schedule (folded into [SettingsTab.PAYMENT_METHODS] — see
 * [PaymentMethodsTabContent]'s doc) or a ninth for the diagnostics-only bits now split between
 * [SettingsTab.GENERAL] and [SettingsTab.ABOUT].
 */
private enum class SettingsTab(val label: String) {
    GENERAL("General"),
    NOTIFICATIONS("Notifications"),
    SOUND_VOICE("Sound & Voice"),
    DISPLAY("Display"),
    PAYMENT_METHODS("Payment Methods"),
    PRINTER("Printer"),
    ABOUT("About"),
}

/**
 * 31 · Settings & Diagnostics — two-pane pass (2026-09-03), restyled onto the HUD kit
 * (2026-09-04): a persistent left tab rail + right content panel, both now [GlassCard]s
 * ([SettingsTabRail] for the rail, its active tile glowing; a `GlassCard` content panel in
 * [MainSettingsContent] for the tab body), replacing the old single-panel [SettingsSubScreen]
 * state-machine's MAIN screen and, in this pass, the flat-panel `TwoPaneShell` that first replaced
 * it — matching the mockup's General/Notifications/Sound & Voice/Display/Payment Methods/Printer/
 * About layout. Every [SettingsViewModel] read/call is unchanged by either pass (GPS/network
 * polling, printer discovery/pairing, offline-map download states, fare schedule, force-update +
 * heartbeat, MDM locate response, admin-PIN-gated factory reset) — this rebuild only re-skins the
 * surfaces each tab's content already sat on: diagnostics tiles now show their status through a
 * [HudStatusPill] instead of a coloured dot, and every toggle/locked/fare-schedule row is a
 * [GlassCard] instead of a flat bordered panel.
 *
 * Content mapping, deliberate (not 1:1 with the old single-screen layout):
 * - **General**: GPS/Network/Offline-maps/Tariff-signature diagnostics, the real Auto Accept Jobs
 *   toggle, locked Language/Units rows, and the Update-maps/Permissions/Offline-&-sync action row.
 * - **Notifications** / **Sound & Voice**: no real backing state exists for either anywhere in
 *   this app — rendered as an honest "coming soon" panel rather than fabricated toggles.
 * - **Display**: the real Show Map in Background toggle (gates
 *   [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s Live Map pane) + a
 *   locked Theme row.
 * - **Payment Methods**: the real Allow Cash toggle (gates
 *   [au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayScreen]'s CASH card) with the
 *   fare schedule folded in directly below it (own tab wasn't worth it for content this is already
 *   payment/rates-related) — see [FareScheduleBody]'s doc.
 * - **Printer**: hosts the existing printer-pairing flow verbatim ([PrinterTabContent]).
 * - **About**: app version, device heartbeat/pair-meter and the MDM locate diagnostic, plus the
 *   admin-PIN-gated factory reset — grouped here as this app's "about this device" surface, since
 *   both are rare, deliberate, full-attention actions that don't need to fit the two-pane shape
 *   (they still open as their own full [au.com.threesixty.cabdispatch.ui.theme.PaneShell]-style
 *   screen exactly as before this pass — see [SettingsSubScreen.FACTORY_RESET_PIN]/[SettingsSubScreen.PAIR_METER]).
 *
 * Real new preference rows (Settings two-pane pass): Auto Accept Jobs, Show Map in Background,
 * Allow Cash — all backed by
 * [au.com.threesixty.cabdispatch.domain.SettingsPreferencesStore] (see that class's own doc for
 * why a small SharedPreferences-backed store, matching this app's existing
 * [au.com.threesixty.cabdispatch.domain.DevicePairingStore]/[au.com.threesixty.cabdispatch.domain.MaxiVehicleStore]
 * precedent, rather than introducing a new DataStore dependency). Language/Theme/Units render as
 * locked "coming soon" rows per this plan's confirmed decision — visible for completeness, never a
 * working-looking toggle with nothing behind it; see [LockedSettingRow].
 */
@Composable
fun SettingsScreen(
    navController: NavHostController,
    onFactoryReset: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var subScreen by remember { mutableStateOf(SettingsSubScreen.MAIN) }
    var tab by remember { mutableStateOf(SettingsTab.GENERAL) }

    LaunchedEffect(state.factoryResetComplete) {
        if (state.factoryResetComplete) onFactoryReset()
    }

    when (subScreen) {
        SettingsSubScreen.MAIN -> MainSettingsContent(
            state = state,
            viewModel = viewModel,
            tab = tab,
            onSelectTab = { tab = it },
            onBack = { navController.popBackStack() },
            onFactoryResetClick = {
                viewModel.clearFactoryResetError()
                subScreen = SettingsSubScreen.FACTORY_RESET_PIN
            },
            onOpenPermissions = { navController.navigate(CabDispatchRoutes.PERMISSIONS_CHECKLIST) },
            onOpenOfflineSync = { navController.navigate(CabDispatchRoutes.OFFLINE_SYNC) },
            onOpenPairMeter = {
                viewModel.clearPairMeterError()
                subScreen = SettingsSubScreen.PAIR_METER
            },
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
    viewModel: SettingsViewModel,
    tab: SettingsTab,
    onSelectTab: (SettingsTab) -> Unit,
    onBack: () -> Unit,
    onFactoryResetClick: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenOfflineSync: () -> Unit,
    onOpenPairMeter: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CaptainPalette.hudBg)
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                        .background(CaptainPalette.hudGlass)
                        .border(1.dp, CaptainPalette.hudGlassBorderPurple, CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("←", fontSize = 24.sp, color = CaptainPalette.textPrimary)
                }
                Text(
                    "Settings & diagnostics",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    color = CaptainPalette.textPrimary,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            Row(modifier = Modifier.fillMaxSize()) {
                SettingsTabRail(
                    selected = tab,
                    onSelect = onSelectTab,
                    modifier = Modifier.width(230.dp).fillMaxHeight(),
                )
                Spacer(Modifier.width(16.dp))
                GlassCard(modifier = Modifier.weight(1f).fillMaxHeight(), cornerRadiusDp = 20) {
                    Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                        when (tab) {
                            SettingsTab.GENERAL -> GeneralTabContent(
                                state = state,
                                onSetAutoAccept = viewModel::setAutoAcceptJobs,
                                onDownloadOfflineMaps = viewModel::downloadOfflineMaps,
                                onOpenPermissions = onOpenPermissions,
                                onOpenOfflineSync = onOpenOfflineSync,
                                onOpenPaymentMethodsTab = { onSelectTab(SettingsTab.PAYMENT_METHODS) },
                            )
                            SettingsTab.NOTIFICATIONS -> ComingSoonTabContent(
                                title = "Notifications",
                                message = "Job offers and dispatcher messages are always delivered — per-notification " +
                                    "preferences (sound, priority, quiet hours) aren't configurable yet.",
                            )
                            SettingsTab.SOUND_VOICE -> ComingSoonTabContent(
                                title = "Sound & Voice",
                                message = "Alert tones and voice-guidance options aren't configurable yet.",
                            )
                            SettingsTab.DISPLAY -> DisplayTabContent(
                                state = state,
                                onSetShowMap = viewModel::setShowMapInBackground,
                            )
                            SettingsTab.PAYMENT_METHODS -> PaymentMethodsTabContent(
                                state = state,
                                onSetAllowCash = viewModel::setAllowCash,
                                onSetMaxiVehicle = viewModel::setMaxiVehicle,
                            )
                            SettingsTab.PRINTER -> PrinterTabContent(state = state, viewModel = viewModel)
                            SettingsTab.ABOUT -> AboutTabContent(
                                state = state,
                                onOpenPairMeter = onOpenPairMeter,
                                onFactoryResetClick = onFactoryResetClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Left tab rail — a [GlassCard] hosting a vertical list of tab tiles, the active one glowing
 * ([neonGlow] halo + accent border + [gameClick] press feedback), replacing the old
 * `TwoPaneShell`'s plain panel/highlight rail. Purely visual: [selected]/[onSelect] are the exact
 * same caller-controlled state `MainSettingsContent` already held — no new selection logic.
 */
@Composable
private fun SettingsTabRail(selected: SettingsTab, onSelect: (SettingsTab) -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, cornerRadiusDp = 20) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsTab.values().forEach { t ->
                val isSelected = t == selected
                val shape = RoundedCornerShape(12.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .then(if (isSelected) Modifier.neonGlow(CaptainPalette.hudAccent, 12.dp, strength = 0.6f, spread = 3.dp) else Modifier)
                        .clip(shape)
                        .background(if (isSelected) CaptainPalette.hudAccent.copy(alpha = 0.22f) else Color.Transparent)
                        .then(if (isSelected) Modifier.border(1.dp, CaptainPalette.hudSweepMid, shape) else Modifier)
                        .gameClick(onClick = { onSelect(t) }, shape = shape, glowColor = CaptainPalette.hudSweepMid)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        t.label,
                        fontFamily = InterFamily,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 16.sp,
                        color = if (isSelected) CaptainPalette.textPrimary else CaptainPalette.textSecondary,
                    )
                }
            }
        }
    }
}

// --- Tab content -------------------------------------------------------------------------------

@Composable
private fun GeneralTabContent(
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
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GpsTile(state, Modifier.weight(1f))
                NetworkTile(state, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LockedSettingRow(label = "Language", value = "English (Australia)")
            LockedSettingRow(label = "Units", value = "Kilometres")
        }

        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
private fun ComingSoonTabContent(title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(36.dp))
        Text(
            title,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = CaptainPalette.textPrimary,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            message,
            fontFamily = InterFamily,
            fontSize = 15.sp,
            color = CaptainPalette.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(16.dp))
        ComingSoonBadge()
    }
}

@Composable
private fun DisplayTabContent(state: SettingsUiState, onSetShowMap: (Boolean) -> Unit) {
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
        LockedSettingRow(label = "Theme", value = "Dark (Captain Taxis)")
    }
}

/**
 * Payment Methods tab — the real Allow Cash toggle, with the fare schedule folded directly in
 * below it via [FareScheduleBody] rather than given its own eighth tab (task's own "your call, but
 * don't lose it" — rates/charges are already this tab's subject, so folding reads naturally rather
 * than as a leftover).
 */
@Composable
private fun PaymentMethodsTabContent(
    state: SettingsUiState,
    onSetAllowCash: (Boolean) -> Unit,
    onSetMaxiVehicle: (Boolean) -> Unit,
) {
    FareScheduleBody(state = state, onSetMaxiVehicle = onSetMaxiVehicle) {
        ToggleSettingRow(
            label = "Allow Cash",
            description = "When off, CASH is disabled on the Close & Pay screen — card, CabCharge/TTSS, " +
                "voucher, account and split-fare payments are unaffected.",
            checked = state.allowCash,
            onCheckedChange = onSetAllowCash,
        )
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.hudTrack))
        Spacer(Modifier.height(20.dp))
    }
}

/** Printer tab — hosts the existing printer-pairing flow verbatim (scan + discovered-device list +
 * paired status), just without the [au.com.threesixty.cabdispatch.ui.theme.PaneShell]/back-arrow
 * wrapper the old standalone sub-screen used, since [MainSettingsContent]'s own [GlassCard] content
 * panel already supplies the bounded panel this renders inside. Deliberately not wrapped in a
 * scrolling `Column` — the `LazyColumn` below needs a bounded height to measure, which it gets from
 * that panel; nesting it inside an outer `verticalScroll` would hand it an infinite height instead
 * and crash. HUD kit rebuild: the paired-status line is now a [HudStatusPill] and each discovered
 * device is a [GlassCard] row — same discovery/pairing calls as before. */
@Composable
private fun PrinterTabContent(state: SettingsUiState, viewModel: SettingsViewModel) {
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

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.discoveredPrinters) { device: PrinterDevice ->
                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 14) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(device.name, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textPrimary)
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

/** About tab — app version, device heartbeat/pair-meter and the MDM locate diagnostic, plus the
 * admin-PIN-gated factory reset. Both the pair-meter and factory-reset flows still open as their
 * own full screen exactly as before this pass ([SettingsSubScreen.PAIR_METER]/
 * [SettingsSubScreen.FACTORY_RESET_PIN]) — deliberately not squeezed into the two-pane shape, per
 * this plan's own "rare, deliberate, full-attention action" call for factory reset. */
@Composable
private fun AboutTabContent(
    state: SettingsUiState,
    onOpenPairMeter: () -> Unit,
    onFactoryResetClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionLabel("DEVICE")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AppVersionTile(state, Modifier.weight(1f))
            HeartbeatTile(state, onOpenPairMeter, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        LocateTile(state, Modifier.fillMaxWidth())

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

// --- Shared tab-content atoms -------------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 1.sp,
        color = CaptainPalette.textMuted,
    )
}

@Composable
private fun ComingSoonBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CaptainPalette.hudTrack)
            .border(1.dp, CaptainPalette.hudGlassBorderPurple, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            "COMING SOON",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 0.5.sp,
            color = CaptainPalette.textMuted,
        )
    }
}

/** A real, working preference row — [checked]/[onCheckedChange] both back a genuine
 * [au.com.threesixty.cabdispatch.domain.SettingsPreferencesStore] flag (Auto Accept Jobs/Show Map
 * in Background/Allow Cash), never decorative. Same [androidx.compose.material3.Switch] color
 * treatment [FareScheduleContent]'s pre-existing maxi-vehicle switch already used, for visual
 * consistency across every real toggle in this screen. HUD kit rebuild: the row's surface is now a
 * [GlassCard] instead of a flat bordered [Row] — the toggle's [checked]/[onCheckedChange] wiring is
 * untouched. */
@Composable
private fun ToggleSettingRow(label: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 16) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = CaptainPalette.textPrimary)
                Text(
                    description,
                    fontFamily = InterFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = CaptainPalette.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            androidx.compose.material3.Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedTrackColor = CaptainPalette.primary,
                    checkedThumbColor = CaptainPalette.hudAccent,
                ),
            )
        }
    }
}

/**
 * A decorative, deliberately-locked row (Language/Theme/Units) — per this plan's confirmed
 * decision, shown for visual completeness but never fake-functional: greyed out (55% alpha), a
 * lock glyph instead of a working control, and a "COMING SOON" badge instead of a value that looks
 * editable. Tapping it is safe (never a crash, never a silent no-op that looks like it worked) — a
 * brief [android.widget.Toast] names the row and says it isn't available yet, then disappears on
 * its own; no new dialog/snackbar plumbing needed for a row this deliberately inert. HUD kit
 * rebuild: same [GlassCard] surface as [ToggleSettingRow], still no glow (a locked row shouldn't
 * read as "live" the way a real toggle does).
 */
@Composable
private fun LockedSettingRow(label: String, value: String) {
    val context = LocalContext.current
    GlassCard(modifier = Modifier.fillMaxWidth().alpha(0.55f), cornerRadiusDp = 16) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    android.widget.Toast.makeText(context, "$label isn't available yet", android.widget.Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Text(label, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = CaptainPalette.textPrimary)
                Text(value, fontFamily = InterFamily, fontSize = 14.sp, color = CaptainPalette.textMuted, modifier = Modifier.padding(top = 2.dp))
            }
            ComingSoonBadge()
        }
    }
}

// --- Diagnostics tiles ---

private enum class DiagTone { OK, WARN, BAD }

/** Maps this screen's pre-existing OK/WARN/BAD diagnostic tone onto the kit's [HudTone] so
 * [DiagTile] can render its status through [HudStatusPill] — same three-way meaning, just the kit's
 * vocabulary (BAD -> Danger is the one non-obvious mapping, since this screen never uses
 * [HudTone.Accent] for a diagnostic). */
private fun DiagTone.toHudTone(): HudTone = when (this) {
    DiagTone.OK -> HudTone.Success
    DiagTone.WARN -> HudTone.Warning
    DiagTone.BAD -> HudTone.Danger
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
        LocateResponseState.NoVehicleBound -> "No vehicle bound" to DiagTone.WARN
        is LocateResponseState.Failed -> "Failed to send — ${locate.message}" to DiagTone.BAD
    }
    if (row == null) {
        Spacer(modifier)
    } else {
        DiagTile(icon = Icons.Rounded.LocationOn, name = "Locate request", sub = row.first, tone = row.second, modifier = modifier)
    }
}

/** One diagnostics tile: icon · name, then the real status read through a [HudStatusPill]
 * ([tone] mapped via [DiagTone.toHudTone]) instead of the old colored-dot-in-a-panel — the kit's
 * "GlassCard surface, HudStatusPill status" convention. [glow] halos the whole card when
 * non-nominal, same "amber/red border on trouble" signal the old tile gave, just as a kit glow
 * instead of a border. */
@Composable
private fun DiagTile(
    icon: ImageVector,
    name: String,
    sub: String,
    tone: DiagTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val hudTone = tone.toHudTone()
    GlassCard(
        modifier = modifier
            .height(128.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        cornerRadiusDp = 16,
        glow = if (tone == DiagTone.OK) null else hudTone.color(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = CaptainPalette.hudAccent, modifier = Modifier.size(20.dp))
                Text(
                    name,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = CaptainPalette.textPrimary,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            HudStatusPill(label = "Status", value = sub, tone = hudTone, pulsing = false, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Action tile: icon above a bold label, elderly-friendly button sizing; danger-tinted for the
 * factory reset via the same [GlassCard] `glow` the rest of the kit uses for a non-neutral surface,
 * instead of the old flat danger border. */
@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val tint = if (danger) CaptainPalette.danger else CaptainPalette.hudAccent
    GlassCard(
        modifier = modifier
            .height(96.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick),
        cornerRadiusDp = 16,
        glow = if (danger) CaptainPalette.danger else null,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp),
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
}

// --- Fare schedule (folded into the Payment Methods tab — see PaymentMethodsTabContent) --------

/**
 * Fare Schedule content — NSW Point to Point Transport Regulation 2017 cl.15 fare-display
 * requirement. Formerly its own standalone sub-screen ([SettingsSubScreen.FARE_SCHEDULE]); folded
 * directly into the Payment Methods tab by the Settings two-pane pass (2026-09-03) rather than
 * given its own eighth tab — see [PaymentMethodsTabContent]'s doc. [header] injects that tab's
 * Allow Cash toggle above the fare content, inside this same scrolling `Column` (never a second,
 * nested `verticalScroll` — see this composable's own scroll container below).
 *
 * Every dollar figure and percentage below is read live off [SettingsUiState.fareSchedule] (the
 * signed active `TariffDto`) or, for the two figures the wire tariff doesn't carry yet (the
 * cleaning-fee cap and the Sydney Airport Fixed Fare amounts), off the same
 * [au.com.threesixty.cabdispatch.domain.fare] constants the engine itself bills from — never a new
 * hardcoded literal. Only the Hotline number and its explanatory copy are static text, because
 * that's a fixed regulatory number, not a tenant/tariff value.
 */
@Composable
private fun FareScheduleBody(
    state: SettingsUiState,
    onSetMaxiVehicle: (Boolean) -> Unit,
    header: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        header()

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
            state.fareScheduleLoading -> CircularProgressIndicator(color = CaptainPalette.hudAccent)
            tariff == null -> Text(
                "No cached fare schedule available.",
                fontFamily = InterFamily,
                fontSize = 16.sp,
                color = CaptainPalette.textSecondary,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18, glow = CaptainPalette.hudAccent) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(tariff.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = CaptainPalette.textPrimary)
                        Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.hudTrack))
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

                MaxiCabFaresSection(maxiMultiplier = tariff.maxiMultiplier)
                AdditionalChargesSection(pslAmount = tariff.pslAmount, cleaningFeeCap = tariff.cleaningFeeCap)
                SydneyAirportFixedFareSection()
            }
        }

        Spacer(Modifier.height(20.dp))

        // Point to Point Transport (Fares) Order 2026 UI-wiring pass — a local,
        // honestly-labelled driver self-declaration (see MaxiVehicleStore's own doc for why
        // this is not read from a real vehicle record: `VehicleDto` carries no such field
        // anywhere server-side). Placed here, the app's existing vehicle/fare-schedule
        // area, rather than inventing a new settings section. HUD kit rebuild: this row was its own
        // hand-rolled panel+switch before this pass — now the exact same [ToggleSettingRow] every
        // other real toggle on this screen uses, same [GlassCard] surface, same switch wiring.
        ToggleSettingRow(
            label = "This vehicle has 5+ passenger seats",
            description = "Your own declaration for this vehicle, saved on this device — not read from a vehicle record. " +
                "Also shown on the Start Meter card. Turns on the maxi (×1.5) rate only together with 5+ " +
                "passengers, or a Sydney Airport rank maxi request, and never for a wheelchair hiring.",
            checked = state.isMaxiVehicle,
            onCheckedChange = onSetMaxiVehicle,
        )
    }
}

/** One label + tabular-figure dollar value row (`FareScheduleBody`'s convention — see
 * [au.com.threesixty.cabdispatch.ui.screens.pricing.PricingPaneContent]'s own `PricingRow`, the
 * same idea in the Pricing pane). */
@Composable
private fun FareScheduleRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textSecondary)
        Text(
            "$$value",
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = CaptainPalette.textPrimary,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

@Composable
private fun FareScheduleSectionTitle(text: String) {
    Text(
        text,
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 1.sp,
        color = CaptainPalette.hudAccent,
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
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18) {
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
            Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.hudTrack))
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
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18) {
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
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FareScheduleSectionTitle("ADDITIONAL CHARGES")
            CaptainChip(label = "PASSENGER SERVICE LEVY", value = "$$pslAmount")
            FareScheduleNote(
                "Optional to pass on to the passenger. Charged once per trip, regardless of the number of " +
                    "passengers. (Fares Order 2026, cl 3.)",
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.hudTrack))
            CaptainChip(label = "CLEANING FEE CAP", value = "$$cleaningFeeCap + GST")
            FareScheduleNote(
                "Only chargeable when soiling means the vehicle can't reasonably be used before it's " +
                    "cleaned. (Fares Order 2026, cl 2(f).)",
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.hudTrack))
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
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18) {
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
            .background(CaptainPalette.hudBg)
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
                        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 16, glow = CaptainPalette.success) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
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
                    }
                    else -> {
                        GlassCard(modifier = Modifier.fillMaxWidth().height(72.dp), cornerRadiusDp = 12, glow = CaptainPalette.hudAccent) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    code.ifEmpty { "········" },
                                    fontFamily = ChakraPetch,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 28.sp,
                                    letterSpacing = 6.sp,
                                    color = if (code.isEmpty()) CaptainPalette.textMuted else CaptainPalette.textPrimary,
                                )
                            }
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
                            .background(CaptainPalette.hudGlass)
                            .border(1.dp, CaptainPalette.hudGlassBorderPurple, RoundedCornerShape(8.dp))
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
