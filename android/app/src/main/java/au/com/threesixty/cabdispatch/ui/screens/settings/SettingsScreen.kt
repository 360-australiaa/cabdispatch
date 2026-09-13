package au.com.threesixty.cabdispatch.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.overlays.reportsChromeHeader
import au.com.threesixty.cabdispatch.ui.screens.adminpin.AdminPinGateScreen
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.Space
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.theme.neonGlow

private enum class SettingsSubScreen { MAIN, FACTORY_RESET_PIN, PAIR_METER, SIMULATOR_PIN }

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
 *   [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s Live Map pane) + the real
 *   Light/Dark/System [ThemeSettingRow] (2026-09-04 day-mode pass — this replaced what used to be
 *   a permanently-locked "Theme · Dark (Captain Taxis)" row; see that composable's own doc).
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
 * precedent, rather than introducing a new DataStore dependency). Language/Units still render as
 * locked "coming soon" rows per this plan's confirmed decision — visible for completeness, never a
 * working-looking toggle with nothing behind it; see [LockedSettingRow]. Theme (2026-09-04
 * day-mode pass) is no longer one of them — see [ThemeSettingRow].
 *
 * ### W7 file split (2026-09-13)
 * This file used to be 1,437 lines holding every tab's content plus the Pair Meter sub-screen. It
 * is now the orchestrating top-level screen only ([SettingsScreen], [MainSettingsContent],
 * [SettingsTabRail], the [SettingsSubScreen]/[SettingsTab] enums) — split, one file per tab/
 * sub-screen matching [SettingsTab]'s own list, into `GeneralTabContent.kt`,
 * `DisplayTabContent.kt`, `PaymentMethodsTabContent.kt` (with the Fare Schedule content folded
 * into it, per that tab's own doc), `PrinterTabContent.kt`, `AboutTabContent.kt`,
 * `PairMeterContent.kt`, plus `SettingsSharedAtoms.kt` for the small building blocks
 * ([SectionLabel], [ToggleSettingRow], [LockedSettingRow], `DiagTile`, `ActionTile`, etc.) more
 * than one tab file uses. All eight files share this package (`ui.screens.settings`), so every
 * cross-file call is a same-package call — nothing changed shape, only which file each composable
 * lives in; composables/types called across a new file boundary are `internal` there rather than
 * `private`.
 */
@Composable
fun SettingsScreen(
    navController: NavHostController,
    onRerunSetup: () -> Unit,
    onFactoryReset: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
            onUnlockSimulatorClick = {
                viewModel.clearSimulatorPinError()
                subScreen = SettingsSubScreen.SIMULATOR_PIN
            },
            onRerunSetup = onRerunSetup,
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
        // Same server-verified admin PIN as the factory reset — see
        // [SettingsViewModel.attemptUnlockSimulator] for why the simulator needs a gate at all
        // (short version: the field tablet runs a debug build, so "debug-only" gated nothing).
        SettingsSubScreen.SIMULATOR_PIN -> AdminPinGateScreen(
            subtitle = "Enter the admin PIN to unlock the GPS simulator. It feeds synthetic " +
                "position and speed into the meter — for technicians testing a tablet, never " +
                "during a real fare.",
            errorMessage = state.simulatorPinError,
            verifying = state.simulatorPinVerifying,
            onCancel = {
                viewModel.clearSimulatorPinError()
                subScreen = SettingsSubScreen.MAIN
            },
            onVerify = { pin -> viewModel.attemptUnlockSimulator(pin) },
        )
    }

    // Drop back to the settings body the moment the PIN is accepted, so the newly unlocked panel
    // is where the technician already expects it rather than behind another tap.
    LaunchedEffect(state.simulatorUnlocked) {
        if (state.simulatorUnlocked && subScreen == SettingsSubScreen.SIMULATOR_PIN) {
            subScreen = SettingsSubScreen.MAIN
        }
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
    onUnlockSimulatorClick: () -> Unit,
    onRerunSetup: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CaptainPalette.hudBg)
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // reportsChromeHeader (tablet, 2026-09-08): this title row IS the screen's top chrome.
            // Without reporting it, the app-level status chips positioned off the last header they
            // had measured -- the 120dp home header -- and FLEET LOCKED landed across the first
            // settings tab. Reported, the chip lane sits just under this row; SettingsTabRail then
            // leaves a lane above its first tab so the two never meet.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.reportsChromeHeader().padding(bottom = 16.dp),
            ) {
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
                                onSetThemeMode = viewModel::setThemeMode,
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
                                onRerunSetup = onRerunSetup,
                                onFactoryResetClick = onFactoryResetClick,
                                onUnlockSimulatorClick = onUnlockSimulatorClick,
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
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Chip lane -- see the title row's reportsChromeHeader note.
            Spacer(Modifier.height(Space.lg))
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
