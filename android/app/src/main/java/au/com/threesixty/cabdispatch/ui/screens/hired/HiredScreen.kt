package au.com.threesixty.cabdispatch.ui.screens.hired

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.ForkLeft
import androidx.compose.material.icons.rounded.ForkRight
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.RoundaboutLeft
import androidx.compose.material.icons.rounded.RoundaboutRight
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSharpLeft
import androidx.compose.material.icons.rounded.TurnSharpRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.WbSunny
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.GeocodeResult
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto
import au.com.threesixty.cabdispatch.domain.DuressUiState
import au.com.threesixty.cabdispatch.domain.FareBreakdown
import au.com.threesixty.cabdispatch.domain.FareState
import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.TimeClass
import au.com.threesixty.cabdispatch.domain.TollPreset
import au.com.threesixty.cabdispatch.domain.TollPresets
import au.com.threesixty.cabdispatch.domain.TripContext
import au.com.threesixty.cabdispatch.domain.TripStatus
import au.com.threesixty.cabdispatch.domain.format.asLocalTime
import au.com.threesixty.cabdispatch.domain.toMoneyString
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.overlays.DuressActiveBanner
import au.com.threesixty.cabdispatch.ui.overlays.DuressTriggeredOverlay
import au.com.threesixty.cabdispatch.ui.overlays.HiddenDuressGestureZone
import au.com.threesixty.cabdispatch.ui.overlays.NavigationTarget
import au.com.threesixty.cabdispatch.ui.overlays.openInMaps
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainChip
import au.com.threesixty.cabdispatch.ui.theme.CaptainDialogScrim
import au.com.threesixty.cabdispatch.ui.theme.CaptainKeypad
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.GlowingSpeedometer
import au.com.threesixty.cabdispatch.ui.theme.HudStatusPill
import au.com.threesixty.cabdispatch.ui.theme.HudTone
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import au.com.threesixty.cabdispatch.ui.theme.RollingMoneyText
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.theme.hudSpring
import au.com.threesixty.cabdispatch.ui.theme.neonGlow
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 18/18b · Hired — Meter, on the shared HUD kit ([au.com.threesixty.cabdispatch.ui.theme]'s
 * `Hud.kt`, 2026-09-03), navigator fully wired (2026-09-04), then re-laid-out to a passenger-facing
 * two-panel shape (2026-09-04b) per a direct, verbatim user correction over the previous
 * three-column build: *"when the meter is running, i dont want this tray on the right side, only
 * meter speedometer and map should show in this screen, and make it big, and prominent, so from
 * behind passenger can see the fare easily, and speedometer position should be left side, and
 * mapbox map position should be on right side."* Phase A embedded this as
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s `CaptainPane.METER` pane
 * (shared header / ONE right rail / bottom stats bar — see that file); this composable owns only
 * the content slot DeckHomeScreen hands it, and — because that file collapses its footer stats bar
 * + status tray for the whole `hasActiveTrip` duration this pane is ever shown for (see its own
 * "Meter-focus collapse" comment; that gate is untouched by this pass) — the slot this Row fills is
 * already the full freed height, which is exactly why the dial and map below can be sized so large.
 *
 * All metering logic is untouched [HiredViewModel]: live [FareState] ticks, pause/resume,
 * addToll persistence, endTrip → Close & Pay, duress state machine (hidden gesture + overlays) —
 * every `viewModel.*` call and `fareState.*`/`duressState` read below is the same call/read this
 * screen has always made. The navigator half is [MeterNavViewModel] — a second, independent
 * `viewModel()` instantiated alongside [HiredViewModel] (see that class's own wiring-notes doc) —
 * every `meterNavViewModel.*` call and `navState.*` read below is a real, already-merged entry
 * point on it; nothing here recomputes routing/ETA/off-route logic that already lives in
 * [MeterNavViewModel]/[NavProgress].
 *
 * - **Only two things are ever visible by default: the dial (LEFT) and the real map (RIGHT).**
 *   [MeterPaneLayout] is now a plain two-column `Row` — no permanent third column, no action-tile
 *   tray, no FARE BREAKDOWN/TRIP DETAILS/NIGHT-DAY card sitting beside the dial. Both columns are
 *   equal-weight (`DIAL_COL_WEIGHT == MAP_COL_WEIGHT`) so each roughly fills its half of the width
 *   the old right column used to occupy — a bigger [GlowingSpeedometer]/[RollingMoneyText] fare
 *   figure a back-seat passenger can actually read, and a bigger, more legible [MeterBackdropMap].
 * - **Nothing here hand-rolls an arc, a glow, a digit or a card.** The dial is
 *   [GlowingSpeedometer] (real 0–120 km/h scale driven by `fareState.currentSpeedKmh`, the
 *   engine's own speed) with its `content` slot holding a circular [GlassCard] disc carrying the
 *   fare as [RollingMoneyText], the RUNNING/PAUSED state, TARIFF + EXTRAS, the DISTANCE / TIME /
 *   WAITING readouts (the dial OWNS these — they appear nowhere else) and END FARE. Every tile and
 *   card is a [GlassCard]; the nav variant's status/reroute pills are [HudStatusPill]; the map's
 *   routes are the kit's two-layer `createGlowLine` (see [MeterBackdropMap]).
 * - **Every real control still exists — nothing is deleted, only relocated.** SET PRICE, ADD TOLL,
 *   PAUSE FARE, MORE (destination/extras/passengers), FARE BREAKDOWN/DETAILS, TRIP DETAILS and the
 *   NIGHT/DAY FARE tile all move into [ControlsDrawer], a single on-demand sheet opened by
 *   [ControlsHandle] — a small, low-profile handle docked in the dial panel's empty corner (a
 *   circle inscribed in a rect never reaches the rect's corners, so the handle never sits over the
 *   ring, the fare figure or END FARE). This is the production taxi-meter/kiosk convention this
 *   pass was explicitly asked to follow: one big always-on passenger-facing readout, plus a small
 *   secondary-actions affordance the driver deliberately opens — not a shopping list of buttons
 *   fighting the passenger display for attention. One tap opens it, the drawer's own Close (or
 *   tapping the scrim) collapses it, and opening any one-shot action inside it (SET PRICE/ADD
 *   TOLL/MORE) also closes it first — see the `actions` callbacks built in [HiredScreen] — so the
 *   driver lands back on the plain dial+map view rather than two stacked scrims.
 * - **Destination search is always visible on the map panel, never gated behind a menu**
 *   (2026-09-05 pass, direct user correction: it was "multiple taps deep" — MORE tile inside
 *   [ControlsDrawer] inside [ControlsHandle] — before this). [MapDestinationSearchBar] now sits at
 *   the top of the (right-hand) map panel for the entire time a fare is running, in both map-panel
 *   shapes below — a compact, genuinely optional "Enter destination" field with a search icon that
 *   opens the same [DestinationSearchDialog] on tap. [ControlsDrawer]'s MORE tile still opens the
 *   identical dialog too (a harmless second entry point, not a duplicate implementation) — see
 *   [MapDestinationSearchBar]'s own doc.
 * - **Two map-panel shapes, one real trigger, unchanged since the last pass — just re-homed to the
 *   right side and bigger.** Mockup #3 (no destination) is the plain driven-route + pickup-pin
 *   backdrop. Mockup #4 (destination set) is used **when `meterNavViewModel.uiState.value
 *   .destination != null`** — a real Mapbox-geocoded place the driver picked via the now-visible
 *   destination search — and adds the real planned route, destination pin, PICK UP/DESTINATION
 *   cards (the DESTINATION card's own real turn icon + remaining-trip summary — see
 *   [maneuverIcon]/[remainingSummary]), the route/ETA strip, OPEN NAVIGATION and the voice toggle
 *   directly onto the (now right-hand, now bigger) map panel — exactly the same content and
 *   the same `onOpenNavigation`/`onToggleVoice`/`onChangeDestination`/`onClearDestination`/
 *   `onRetryRoute` callbacks as before, just literally swapped from the left/centre side to the
 *   right.
 * - **Every control is wired to a real call.** SET PRICE/ADD TOLL/PAUSE FARE/MORE call the same
 *   `viewModel.*` entry points as before; destination search/select/clear/retry call
 *   `meterNavViewModel.onQueryChange`/`.selectDestination`/`.clearDestination`/`.retryRoute`; the
 *   voice toggle flips `HiredViewModel.speechEnabled` (the single source of truth for both
 *   announcers — a `LaunchedEffect` mirrors it into `meterNavViewModel.setVoiceEnabled` so muting
 *   either control mutes both, per [MeterNavViewModel.setVoiceEnabled]'s doc); OPEN NAVIGATION
 *   calls the real [openInMaps] with the destination's real coordinates; END FARE calls the
 *   identical `viewModel.endTrip { navigate(CLOSE_PAY) }` in both map-panel shapes, and stays
 *   inside the dial exactly where it was — the one control this pass does NOT tuck away.
 * - **[MeterBackdropMap]** — real map, untouched by this pass. Mockup #3: real route (every vertex
 *   a real GPS fix), real pickup pin, no destination pin. Mockup #4: adds the real planned route
 *   ([au.com.threesixty.cabdispatch.data.remote.DirectionsRoute.points]) and a real destination
 *   pin, and switches the camera from vehicle-follow to a bounds-fit framing the whole trip — see
 *   that file's doc.
 * - **[FareBreakdownCard]** (inside [ControlsDrawer]) keeps [HiredViewModel.breakdownExpanded]/
 *   `.toggleBreakdown()` as the HIDE/SHOW toggle (no total row — the dial's ACTIVE FARE figure IS
 *   the total). **[TripDetailsCard]** (also inside [ControlsDrawer]) is the vertical
 *   pickup→drop-off timeline + AVG SPEED, mockup-#3 only — dropped once the map panel's PICK
 *   UP/DESTINATION cards already carry the same pair. Every card renders an honest "—" wherever
 *   data is missing, never fabricates one.
 * - SET PRICE remains **read-only/informational** ([SetPriceInfoDialog]'s doc explains why); ADD
 *   TOLL/MORE open the same dialogs; PAUSE FARE calls `togglePause()` exactly.
 *
 * The hidden duress gesture zone's modifier (`align(Alignment.BottomEnd).padding(end = 12.dp,
 * bottom = 12.dp)`) and `onTriggered = viewModel::onDuressTriggered` call are reproduced verbatim
 * below — this pass does not move, resize, or reveal it (explicit user decision: no visible duress
 * button on this screen).
 */
@Composable
fun HiredScreen(
    navController: NavHostController,
    viewModel: HiredViewModel = viewModel(),
    meterNavViewModel: MeterNavViewModel = viewModel(),
) {
    val fareState by viewModel.fareState.collectAsState()
    val speechEnabled by viewModel.speechEnabled.collectAsState()
    val duressState by viewModel.duressState.collectAsState()
    val breakdownExpanded by viewModel.breakdownExpanded.collectAsState()
    val isPaused = fareState.status == TripStatus.STOPPED
    val context = LocalContext.current

    // Real correctness fix (fare-reset-on-renavigation bug): this pane is reached only while
    // CabDispatchRoutes.HIRED is on the back stack (DeckHomeScreen's `when (pane)` — see that
    // file's own comment on CaptainPane.METER: deliberately no PaneShell/back-arrow here, "never a
    // literal back out of the meter", since every other rail item is reachable via a same-entry
    // pane swap that leaves this composable's NavBackStackEntry — and therefore [viewModel]'s
    // ViewModelStore — untouched). The one path that comment never actually closed off is the
    // system/gesture back button: with no BackHandler, it popped the HIRED entry, destroying this
    // [HiredViewModel] instance (cancelling its live-ticking FareEngineImpl) mid-fare. Because
    // [au.com.threesixty.cabdispatch.domain.SessionHolder.pendingTrip] is never cleared once a
    // trip starts, the next "METER" tap (the nav rail's `hasActiveTrip` alias, reachable from
    // wherever back landed) then created a BRAND NEW HiredViewModel that re-ran startTrip()/
    // openTripInRoom() against that same stale pending context — resetting the on-screen fare to
    // $0/0:00 and opening a second, orphaned TripEntity row in Room alongside the still-OPEN
    // original. Swallowing back here (matching the design this screen already documents) closes
    // that path: every other way to leave this pane (a rail tap, or any `navController.navigate`
    // to a screen layered on top, e.g. Profile/Messages/Trip Detail) already pushes/pops without
    // ever popping the HIRED entry itself, so this is the only gap.
    BackHandler(enabled = true) {}

    // Best-effort read of the same hand-off payload HiredViewModel.init already reads once — see
    // TripContext.originAddress/.destAddress/.negotiatedTotal's docs. A screen-local read (same
    // "screen-local loader" convention DeckHomeScreen's HomeExtras/DriverAvatar already use), not a
    // new field added to HiredViewModel itself. Degrades to nulls (every dependent row below
    // already shows "—") if this VM instance somehow outlives the pendingTrip hand-off.
    val tripContext by SessionHolder.pendingTrip.collectAsState()
    // Real persisted trip row (Room, via the same observeActiveTrip Flow DeckHomeScreen's
    // hasActiveTrip read uses) — only for the Trip Details timeline's real pickup time
    // (TripEntity.startAt) — and its persisted GPS trace for the backdrop's route polyline.
    val activeTrip by AppContainer.tripRepository.observeActiveTrip().collectAsState(initial = null)
    val persistedTrace by AppContainer.tripRepository.observeActiveTripGpsTrace().collectAsState(initial = emptyList())
    val liveTrace = rememberLiveTrace()
    val liveFix by AppContainer.speedSource.locationFix.collectAsState()

    // ---- Navigator (real, wired) ----------------------------------------------------------------
    // MeterNavViewModel is the merged, in-flight navigator ViewModel (destination search, real
    // Directions-API route, ETA, off-route/reroute, spoken turns) — see its own class doc for why
    // it is deliberately separate from HiredViewModel (fare maths) and how the two share exactly
    // one thing, the process-wide TTS engine. `destination != null` is the one real trigger for
    // the mockup-#4 layout switch — never TripContext.destAddress, which (per that field's own
    // doc) never carries coordinates for a rank/hail trip.
    val navState by meterNavViewModel.uiState.collectAsState()
    val hasDestination = navState.destination != null
    // HiredViewModel.speechEnabled stays the single source of truth for "is anything spoken right
    // now" — this mirrors it into the navigator's own flag on every change so the one voice toggle
    // (wherever it's tapped — the MORE sheet's existing switch or the nav pane's) mutes both
    // announcers, per MeterNavViewModel.setVoiceEnabled's doc.
    LaunchedEffect(speechEnabled) { meterNavViewModel.setVoiceEnabled(speechEnabled) }

    var showTollPad by remember { mutableStateOf(false) }
    var showTollMenu by remember { mutableStateOf(false) }
    var showExtrasNote by remember { mutableStateOf(false) }
    var showSetPriceInfo by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showDestinationSearch by remember { mutableStateOf(false) }
    // The collapsed control surface (2026-09-04b redesign) — SET PRICE/ADD TOLL/PAUSE FARE/MORE +
    // FARE BREAKDOWN/TRIP DETAILS/NIGHT-DAY FARE, all moved off the permanent right column into
    // this single on-demand sheet. See ControlsDrawer's/ControlsHandle's own docs.
    var showControls by remember { mutableStateOf(false) }
    // Point to Point Transport (Fares) Order 2026 UI-wiring pass: mid-trip passenger-count
    // correction — see HiredViewModel.updatePassengerCount's doc. Reached from the MORE sheet.
    var showPassengerEdit by remember { mutableStateOf(false) }

    var showStartedBanner by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel.isNewTripStart) {
        if (viewModel.isNewTripStart) {
            showStartedBanner = true
            kotlinx.coroutines.delay(2000)
            showStartedBanner = false
        }
    }

    val onEndFare: () -> Unit = {
        viewModel.endTrip { navController.navigate(CabDispatchRoutes.CLOSE_PAY) }
    }
    val actions = MeterActions(
        isPaused = isPaused,
        negotiatedTotal = tripContext?.negotiatedTotal,
        tollsTotal = fareState.breakdown.tolls,
        tollCount = fareState.tollsApplied.size,
        // Every one-shot action also closes ControlsDrawer first — the driver lands on the plain
        // dial+map view under the dialog it opened, rather than two stacked scrims. PAUSE FARE
        // stays in-place (togglePause() has no dialog of its own), leaving the drawer open.
        onSetPrice = { showControls = false; showSetPriceInfo = true },
        onAddToll = { showControls = false; showTollMenu = true },
        onTogglePause = viewModel::togglePause,
        onMore = { showControls = false; showMore = true },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // --- Maxi rate / wheelchair-hiring indicators (Point to Point Transport (Fares)
            // Order 2026 UI-wiring pass). Read ONLY [fareState.maxiRateApplied] — the pure fare
            // engine's own derived flag, copied through by FareEngineImpl — never recomputed here
            // from isMaxiVehicle/passengerCount/wheelchairHiring directly, so this banner can never
            // drift from what is actually being charged. Take vertical room only while visible.
            AnimatedVisibility(visible = fareState.maxiRateApplied, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CaptainPalette.warning)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "⚠  MAXI RATE ×1.5 ACTIVE",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.bg,
                    )
                }
            }
            AnimatedVisibility(visible = fareState.wheelchairHiring, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), cornerRadiusDp = 12) {
                    Text(
                        "♿  Wheelchair hiring — meter should start once the passenger is safely secured, per NSW Reg cl 82. Ordinary (non-maxi) rate applies.",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = CaptainPalette.textSecondary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                MeterPaneLayout(
                    fareState = fareState,
                    isPaused = isPaused,
                    tripContext = tripContext,
                    startAtIso = activeTrip?.startAt,
                    persistedTrace = persistedTrace,
                    liveTrace = liveTrace,
                    liveFix = liveFix,
                    navState = navState,
                    hasDestination = hasDestination,
                    speechEnabled = speechEnabled,
                    onEndFare = onEndFare,
                    onOpenNavigation = { target -> openInMaps(context, target) },
                    onToggleVoice = { viewModel.toggleSpeech(!speechEnabled) },
                    onRetryRoute = meterNavViewModel::retryRoute,
                    onChangeDestination = { showDestinationSearch = true },
                    onClearDestination = meterNavViewModel::clearDestination,
                    onOpenControls = { showControls = true },
                )
            }
        }

        AnimatedVisibility(
            visible = showStartedBanner,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
            enter = fadeIn(),
            exit = fadeOut(animationSpec = tween(400)),
        ) {
            Box(
                modifier = Modifier
                    .neonGlow(CaptainPalette.success, 99.dp, strength = 0.8f)
                    .clip(RoundedCornerShape(99.dp))
                    .background(CaptainPalette.success)
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            ) {
                Text(
                    "● METER STARTED",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = CaptainPalette.bg,
                )
            }
        }

        CaptainDialogScrim(visible = showPassengerEdit, onDismissRequest = { showPassengerEdit = false }) {
            PassengerEditDialog(
                initialCount = fareState.passengerCount,
                onDismiss = { showPassengerEdit = false },
                onConfirm = { count ->
                    showPassengerEdit = false
                    viewModel.updatePassengerCount(count)
                },
            )
        }

        CaptainDialogScrim(visible = showTollMenu, onDismissRequest = { showTollMenu = false }) {
            TollPresetDialog(
                tollsTotal = fareState.breakdown.tolls,
                onDismiss = { showTollMenu = false },
                onAddPreset = { preset ->
                    showTollMenu = false
                    viewModel.addToll(preset)
                },
                onCustom = {
                    showTollMenu = false
                    showTollPad = true
                },
            )
        }
        CaptainDialogScrim(visible = showTollPad, onDismissRequest = { showTollPad = false }) {
            CustomTollDialog(
                onDismiss = { showTollPad = false },
                onConfirm = { amount ->
                    showTollPad = false
                    viewModel.addToll(TollPreset("custom", "Custom toll", amount))
                },
            )
        }
        CaptainDialogScrim(visible = showSetPriceInfo, onDismissRequest = { showSetPriceInfo = false }) {
            SetPriceInfoDialog(negotiatedTotal = tripContext?.negotiatedTotal, onDismiss = { showSetPriceInfo = false })
        }
        CaptainDialogScrim(visible = showExtrasNote, onDismissRequest = { showExtrasNote = false }) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(CaptainPalette.panel)
                    .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Extras", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
                Text(
                    "No chargeable extras are configured for this fleet yet — extras (e.g. cleaning fee) " +
                        "are applied at Close & Pay where they exist. Tolls have their own ADD TOLL button.",
                    fontFamily = InterFamily,
                    fontSize = 15.sp,
                    color = CaptainPalette.textSecondary,
                )
                CaptainButton(text = "OK", outline = true, widthDp = 180) {
                    showExtrasNote = false
                }
            }
        }
        CaptainDialogScrim(visible = showMore, onDismissRequest = { showMore = false }) {
            MoreActionsSheet(
                speechEnabled = speechEnabled,
                passengerCount = fareState.passengerCount,
                hasDestination = hasDestination,
                destinationLabel = navState.destination?.placeName,
                onToggleSpeech = { viewModel.toggleSpeech(it) },
                onEditPassengers = {
                    showMore = false
                    showPassengerEdit = true
                },
                onExtras = {
                    showMore = false
                    showExtrasNote = true
                },
                onNavigate = {
                    showMore = false
                    showDestinationSearch = true
                },
                onDismiss = { showMore = false },
            )
        }
        CaptainDialogScrim(visible = showControls, onDismissRequest = { showControls = false }) {
            ControlsDrawer(
                fareState = fareState,
                tripContext = tripContext,
                startAtIso = activeTrip?.startAt,
                hasDestination = hasDestination,
                breakdownExpanded = breakdownExpanded,
                onToggleBreakdown = viewModel::toggleBreakdown,
                actions = actions,
                onDismiss = { showControls = false },
            )
        }
        CaptainDialogScrim(visible = showDestinationSearch, onDismissRequest = { showDestinationSearch = false }) {
            DestinationSearchDialog(
                nav = navState,
                onQueryChange = meterNavViewModel::onQueryChange,
                onSelect = { result ->
                    meterNavViewModel.selectDestination(result)
                    showDestinationSearch = false
                },
                onDismiss = { showDestinationSearch = false },
            )
        }

        HiddenDuressGestureZone(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 12.dp),
            onTriggered = viewModel::onDuressTriggered,
        )
        when (val d = duressState) {
            is DuressUiState.Active -> DuressActiveBanner(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 10.dp),
            )
            is DuressUiState.Triggered -> DuressTriggeredOverlay(
                secondsRemaining = d.secondsRemaining,
                onCancel = viewModel::cancelDuress,
            )
            DuressUiState.Idle -> Unit
        }
    }
}

/** The four action-tile callbacks + the real values their subtext lines show, bundled so both
 * layouts render the identical tiles from the identical `viewModel.*` entry points. */
private class MeterActions(
    val isPaused: Boolean,
    val negotiatedTotal: String?,
    val tollsTotal: BigDecimal,
    val tollCount: Int,
    val onSetPrice: () -> Unit,
    val onAddToll: () -> Unit,
    val onTogglePause: () -> Unit,
    val onMore: () -> Unit,
)

// Column proportions for the two-column shape every state of this pane uses (2026-09-04b redesign:
// DIAL LEFT | MAP RIGHT, no third column — see MeterPaneLayout's doc). Equal weights, not fixed dp,
// so each panel genuinely fills half of whatever width/height DeckHomeScreen's slot gives this Row
// (which is already the full footer-collapsed height for the whole time this pane is shown) — per
// the direct correction that both the dial and the map should grow to fill their half, not just one
// of them.
private const val DIAL_COL_WEIGHT = 1f
private const val MAP_COL_WEIGHT = 1f
private val COL_GAP = 16.dp

/** The circular glass disc inside the speedometer ring, as a fraction of the ring's diameter —
 * sized so it sits just inside the kit's tick-label radius (`tickOuter − 11dp − 9dp`). */
private const val DIAL_INNER_FRACTION = 0.70f

/** Real night-rate uplift, not a fabricated multiplier — same ratio-of-signed-tariff computation
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s `NightFareTile` uses (that
 * one is `private` to a different file, so this is a small, deliberate duplicate of the same
 * formula rather than a cross-file reach-around). `null` tariff (no pending-trip hand-off to read
 * it from) hides the ratio rather than showing a bogus one. */
private fun nightMultiplierLabel(tariff: TariffDto?): String? {
    val t = tariff ?: return null
    val day = t.distRate1.toBigDecimalOrNull() ?: return null
    val night = t.nightRate1.toBigDecimalOrNull() ?: return null
    if (day.signum() <= 0) return null
    return "${night.divide(day, 2, RoundingMode.HALF_UP)}×"
}

private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()

private val ETA_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

/** "1.2 km" / "350 m" / "—" for a null distance — the navigator's remaining-distance readout. */
private fun formatDistanceM(m: Double?): String {
    if (m == null || m.isNaN()) return "—"
    return if (m >= 1000) "%.1f km".format(m / 1000.0) else "${m.roundToInt()} m"
}

/** "1h 05m" / "12 min" / "—" for a null duration — the navigator's remaining-time readout. */
private fun formatDurationS(s: Double?): String {
    if (s == null || s.isNaN()) return "—"
    val totalMin = (s / 60.0).roundToInt()
    return if (totalMin >= 60) "${totalMin / 60}h %02dm".format(totalMin % 60) else "$totalMin min"
}

/** "4:32 PM" / "—" for a null ETA — same clock format as [asLocalTime], just from an epoch millis
 * (the navigator's own field) rather than an ISO-8601 string. */
private fun formatEtaClock(epochMillis: Long?): String {
    if (epochMillis == null) return "—"
    return runCatching {
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(ETA_TIME_FORMATTER)
    }.getOrDefault("—")
}

/** Text glow — a same-colour paint shadow (blur radius in px), the cheap way to "bloom" a label.
 * Text only; every arc/ring glow on this screen is the kit's. */
private fun glowStyle(color: Color, blurPx: Float = 18f, alpha: Float = 0.85f): TextStyle =
    TextStyle(shadow = Shadow(color = color.copy(alpha = alpha), offset = Offset.Zero, blurRadius = blurPx))

/** Muted upper-case section label used by every card header on this screen. */
@Composable
private fun SectionLabel(text: String, color: Color = CaptainPalette.textPrimary) {
    Text(text, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp, color = color)
}

// ============================================================================================
// The pane — two columns (DIAL LEFT | MAP RIGHT), plus one on-demand ControlsDrawer, for every
// state (2026-09-04b redesign)
// ============================================================================================

/**
 * The whole content Row for this pane, in every state — a plain two-column shape (2026-09-04b
 * redesign, replacing the previous three-column build), per the direct user correction quoted on
 * [HiredScreen]'s own class doc: dial LEFT, map RIGHT, both big, nothing else on-screen by default.
 * The dial gets its own [GlassCard] on the LEFT, with [ControlsHandle] docked in its otherwise-empty
 * corner. The map is a real, clearly bounded panel on the RIGHT the driver (and a back-seat
 * passenger) can actually read — never a backdrop the dial sits on top of. Both columns are
 * equal-weight, so removing the old third column lets each one grow into roughly half the freed
 * width, and both inherit the full footer-collapsed height [DeckHomeScreen] already gives this pane
 * for the entire time it is shown (see that file's own "Meter-focus collapse" comment) — so the
 * dial's own `min(width, height)` sizing (see [MeterDial]) and the map panel both end up visibly
 * bigger, not just padded.
 *
 * Everything that used to sit in the permanent third column — NIGHT/DAY FARE, SET PRICE/ADD
 * TOLL/PAUSE FARE/MORE, FARE BREAKDOWN/DETAILS, TRIP DETAILS — is unchanged in substance but moved
 * into [ControlsDrawer], opened on demand via [onOpenControls] (wired to [ControlsHandle] below).
 * Nothing is deleted; every one of those controls still calls the identical `viewModel.*`/
 * `actions.*` entry point it always did.
 *
 * [hasDestination] (a real [MeterNavViewModel.uiState] destination, never `TripContext.destAddress`
 * — see [HiredScreen]'s class doc) changes only what the (now right-hand) map panel additionally
 * shows: the driven route + pickup pin are always there; once a destination is picked, the same
 * panel gains the real planned route, a destination pin, the PICK UP/DESTINATION cards, the
 * route/ETA strip, OPEN NAVIGATION and the voice toggle — mockup #4's content, reached by growing
 * this one panel rather than swapping in a separate one. [TripDetailsCard] (inside [ControlsDrawer])
 * drops out once the map panel already carries the same pickup/destination pair, so the two never
 * duplicate each other.
 */
@Composable
private fun RowScope.MeterPaneLayout(
    fareState: FareState,
    isPaused: Boolean,
    tripContext: TripContext?,
    startAtIso: String?,
    persistedTrace: List<TelemetryPointDto>,
    liveTrace: List<MapPoint>,
    liveFix: LocationFix?,
    navState: MeterNavUiState,
    hasDestination: Boolean,
    speechEnabled: Boolean,
    onEndFare: () -> Unit,
    onOpenNavigation: (NavigationTarget) -> Unit,
    onToggleVoice: () -> Unit,
    onRetryRoute: () -> Unit,
    onChangeDestination: () -> Unit,
    onClearDestination: () -> Unit,
    onOpenControls: () -> Unit,
) {
    val destination = navState.destination

    // --- col 1: the dial, LEFT — its own glass surface, big, with the ControlsHandle docked in
    // its empty corner (a circle inscribed in a rect never reaches the rect's corners, whichever
    // way this panel's aspect ratio runs, so the handle never overlaps the ring/fare/END FARE). ---
    Box(modifier = Modifier.weight(DIAL_COL_WEIGHT).fillMaxHeight()) {
        GlassCard(modifier = Modifier.fillMaxSize(), cornerRadiusDp = 24) {
            MeterDial(
                fareState = fareState,
                isPaused = isPaused,
                onEndFare = onEndFare,
                modifier = Modifier.fillMaxSize().padding(6.dp),
            )
        }
        ControlsHandle(onClick = onOpenControls, modifier = Modifier.align(Alignment.TopEnd).padding(14.dp))
    }

    Spacer(Modifier.width(COL_GAP))

    // --- col 2: the map, RIGHT — a real bounded panel, big and legible, never behind the dial ---
    Box(
        modifier = Modifier
            .weight(MAP_COL_WEIGHT)
            .fillMaxHeight()
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.hudBg)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp)),
    ) {
        MeterBackdropMap(
            startLat = tripContext?.startLat,
            startLng = tripContext?.startLng,
            persistedTrace = persistedTrace,
            liveTrace = liveTrace,
            liveFix = liveFix,
            destLat = destination?.lat,
            destLng = destination?.lng,
            plannedRoute = if (hasDestination) navState.route?.points?.map { MapPoint(it.lat, it.lng) } ?: emptyList() else emptyList(),
            // Lighter wash than the old dial-backdrop default: the map is the content here, not
            // scenery behind a dial, so it needs to actually be legible (direct user correction).
            dimAlpha = 0.30f,
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudStatusPill(
                    label = "Trip",
                    value = if (isPaused) "PAUSED" else "IN PROGRESS",
                    tone = if (isPaused) HudTone.Warning else HudTone.Accent,
                )
                AnimatedVisibility(visible = hasDestination && navState.offRoute, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                    HudStatusPill(label = "Nav", value = "REROUTING…", tone = HudTone.Warning)
                }
            }
            // The destination search, surfaced directly on the map panel — visible the entire
            // time a fare is running, never behind ControlsHandle/MORE. Genuinely optional: an
            // empty field with placeholder text, never blocking or nagging. Tapping it opens the
            // exact same DestinationSearchDialog (same onQueryChange/selectDestination/
            // clearDestination calls) — see MeterPaneLayout's own doc for why the on-screen
            // AddressKeypad lives in that dialog rather than inline here.
            MapDestinationSearchBar(
                destinationLabel = destination?.placeName,
                onClick = onChangeDestination,
                onClear = if (hasDestination) onClearDestination else null,
            )
        }
        if (hasDestination) {
            Column(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NavStopCard(
                        icon = Icons.Rounded.Place,
                        tone = CaptainPalette.success,
                        label = "PICK UP",
                        address = tripContext?.originAddress ?: "—",
                        detail = startAtIso?.asLocalTime()?.let { "Picked up $it" } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    NavDestinationCard(
                        navState = navState,
                        onChange = onChangeDestination,
                        onClear = onClearDestination,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                RouteEtaPanel(navState = navState, onRetry = onRetryRoute)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CaptainButton(
                        text = "OPEN NAVIGATION",
                        heightDp = 50,
                        fontSize = 15.sp,
                        enabled = destination != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        destination?.let { onOpenNavigation(NavigationTarget(it.lat, it.lng, it.placeName)) }
                    }
                    SpeechToggleButton(enabled = speechEnabled, onToggle = onToggleVoice)
                }
            }
        }
    }
}

/**
 * The primary, always-visible destination entry point (2026-09-05 pass): an optional "Enter
 * destination" field with a search icon, sitting directly on the map panel — visible for the
 * entire time a fare is running, never gated behind [ControlsHandle]/[ControlsDrawer]'s MORE tile.
 * Direct user correction: destination search was "multiple taps deep" before this; this affordance
 * is now the first thing on the map panel, right where the trip/nav status pills already sit.
 *
 * Genuinely optional — an empty field just shows placeholder text, never a validation error or a
 * nag — and it never types inline: tapping it (whatever its current label) opens the exact same
 * [DestinationSearchDialog] this screen already had, wired to the exact same
 * [MeterNavViewModel.onQueryChange]/[MeterNavViewModel.selectDestination] calls (see that dialog's
 * own doc for why address entry there is the hand-rolled [AddressKeypad], not a system
 * `TextField` — the identical constraint applies to any text entry on this screen, so this bar is a
 * button-shaped launcher for that dialog rather than a second, competing text-input
 * implementation). [destinationLabel] is the real [MeterNavUiState.destination]'s place name once
 * one is picked (replacing the placeholder); [onClear] is non-null (and renders a small clear
 * icon) only once a destination actually exists, so an empty/optional field never shows a
 * clear affordance with nothing to clear.
 */
@Composable
private fun MapDestinationSearchBar(
    destinationLabel: String?,
    onClick: () -> Unit,
    onClear: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(min = 220.dp, max = 340.dp)
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CaptainPalette.panel.copy(alpha = 0.92f))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = CaptainPalette.hudAccent, modifier = Modifier.size(18.dp))
        Text(
            destinationLabel ?: "Enter destination (optional)",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = if (destinationLabel == null) CaptainPalette.textMuted else CaptainPalette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
        )
        if (onClear != null) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Clear destination",
                tint = CaptainPalette.textMuted,
                modifier = Modifier.size(15.dp).padding(start = 6.dp).clickable(onClick = onClear),
            )
        }
    }
}

private val NAV_TILE_H = 92.dp

/**
 * The small, low-profile affordance that opens [ControlsDrawer] — docked in the dial panel's
 * otherwise-empty corner (see [MeterPaneLayout]'s doc for why a corner is always safe there). One
 * tap opens the drawer; the drawer's own Close (or tapping the scrim) collapses it again — this is
 * the "small secondary-actions affordance the driver deliberately opens" half of the production
 * taxi-meter/kiosk convention this redesign follows, paired with the big always-on dial+map readout
 * a back-seat passenger can actually read.
 */
@Composable
private fun ControlsHandle(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(99.dp))
            .background(CaptainPalette.raised.copy(alpha = 0.92f))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(99.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.MoreHoriz, contentDescription = "Open controls", tint = CaptainPalette.textSecondary, modifier = Modifier.size(18.dp))
        Text(
            "CONTROLS",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = CaptainPalette.textSecondary,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * The collapsed control surface itself (2026-09-04b redesign) — everything that used to sit in the
 * permanent third column (NIGHT/DAY FARE, the four action tiles, FARE BREAKDOWN/DETAILS, TRIP
 * DETAILS), unchanged in substance, moved here behind [ControlsHandle]. Same dialog shell as every
 * other action on this screen ([TollPresetDialog], [SetPriceInfoDialog], [MoreActionsSheet] etc.) —
 * a fixed-width [GlassCard]-style panel via [CaptainDialogScrim], scrollable so it never clips on
 * the pane's real height. [actions] is the identical [MeterActions] bundle [HiredScreen] already
 * builds — SET PRICE/ADD TOLL/MORE close this drawer first (see those callbacks' own doc at the
 * `actions` call site) so the driver lands back on the plain dial+map view under whichever dialog it
 * opened; PAUSE FARE has no dialog of its own and leaves the drawer open.
 */
@Composable
private fun ControlsDrawer(
    fareState: FareState,
    tripContext: TripContext?,
    startAtIso: String?,
    hasDestination: Boolean,
    breakdownExpanded: Boolean,
    onToggleBreakdown: () -> Unit,
    actions: MeterActions,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(440.dp)
            .heightIn(max = 600.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(24.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Controls", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = CaptainPalette.textPrimary)
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Close controls",
                tint = CaptainPalette.textMuted,
                modifier = Modifier.size(22.dp).clickable(onClick = onDismiss),
            )
        }
        Spacer(Modifier.height(14.dp))
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            NightFareTile(timeClass = fareState.timeClass, tariff = tripContext?.tariff)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SetPriceTile(actions, Modifier.weight(1f).height(NAV_TILE_H))
                AddTollTile(actions, Modifier.weight(1f).height(NAV_TILE_H))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PauseFareTile(actions, Modifier.weight(1f).height(NAV_TILE_H))
                MoreTile(actions, Modifier.weight(1f).height(NAV_TILE_H))
            }
            Spacer(Modifier.height(10.dp))
            FareBreakdownCard(
                title = if (hasDestination) "FARE DETAILS" else "FARE BREAKDOWN",
                breakdown = fareState.breakdown,
                timeClass = fareState.timeClass,
                nightMultiplierLabel = nightMultiplierLabel(tripContext?.tariff),
                expanded = breakdownExpanded,
                onToggle = onToggleBreakdown,
            )
            // Dropped once the map panel already carries the same PICK UP/DESTINATION pair — the
            // two must never show the same address/time twice.
            if (!hasDestination) {
                Spacer(Modifier.height(10.dp))
                TripDetailsCard(tripContext = tripContext, fareState = fareState, startAtIso = startAtIso)
            }
            Spacer(Modifier.height(8.dp))
            AccrualNote()
        }
    }
}

/** PICK UP card on the nav pane — icon in a tinted square, label, address, detail. Shared shell
 * ([IconSquare]) with [NavDestinationCard]; PICK UP has no actions (the pickup is fixed once a
 * trip is open), so it stays the plain read-only card. */
@Composable
private fun NavStopCard(icon: ImageVector, tone: Color, label: String, address: String, detail: String, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, cornerRadiusDp = 16) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            IconSquare(icon = icon, tint = tone, size = 30.dp)
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.sp, color = tone)
                Text(
                    address,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = CaptainPalette.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(detail, fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 10.sp, color = CaptainPalette.textSecondary, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/**
 * A real directional icon for the current maneuver, derived ONLY from Mapbox's own
 * `maneuver.type`/`maneuver.modifier` fields ([RouteStep.maneuverType]/[RouteStep.modifier],
 * parsed in [MapboxDirections.parseRoute]) — never inferred by pattern-matching
 * [RouteStep.instruction] text, and never a generic/default arrow when the data doesn't support
 * one (returns `null`, so the caller falls back to the instruction text alone).
 *
 * `depart`/`arrive` get fixed icons (a maneuver TYPE, not a laterality — nothing to guess).
 * `roundabout`/`rotary`/`fork` need a real `left`/`right` modifier for their directional icon
 * (Mapbox does supply one for these); anything else on them falls through to the generic
 * modifier-only mapping below. `merge` has no left/right icon in this project's icon set, so it
 * gets one direction-neutral glyph regardless of modifier — that is not a guess, it is the
 * accurate icon for "merge" full stop. `uturn` has no laterality in Mapbox's data at all (the
 * modifier is just `"uturn"`), and this icon set's only u-turn glyphs are direction-specific
 * (`UTurnLeft`/`UTurnRight`) — picking either would assert a direction the API never gave, so it
 * intentionally maps to `null` (text only) rather than a distinct icon.
 */
private fun maneuverIcon(maneuverType: String?, modifier: String?): ImageVector? = when {
    maneuverType == "arrive" -> Icons.Rounded.Flag
    maneuverType == "depart" -> Icons.Rounded.DirectionsCar
    (maneuverType == "roundabout" || maneuverType == "rotary" || maneuverType == "roundabout turn") && modifier == "left" -> Icons.Rounded.RoundaboutLeft
    (maneuverType == "roundabout" || maneuverType == "rotary" || maneuverType == "roundabout turn") && modifier == "right" -> Icons.Rounded.RoundaboutRight
    maneuverType == "fork" && modifier == "left" -> Icons.Rounded.ForkLeft
    maneuverType == "fork" && modifier == "right" -> Icons.Rounded.ForkRight
    maneuverType == "merge" -> Icons.Rounded.Merge
    modifier == "left" -> Icons.Rounded.TurnLeft
    modifier == "right" -> Icons.Rounded.TurnRight
    modifier == "slight left" -> Icons.Rounded.TurnSlightLeft
    modifier == "slight right" -> Icons.Rounded.TurnSlightRight
    modifier == "sharp left" -> Icons.Rounded.TurnSharpLeft
    modifier == "sharp right" -> Icons.Rounded.TurnSharpRight
    modifier == "straight" -> Icons.Rounded.Straight
    else -> null
}

/**
 * "2.4 km · 6 min · ETA 4:32 PM" — the real remaining-trip summary
 * ([MeterNavUiState.remainingDistanceM]/[.remainingDurationS]/[.etaEpochMillis], the same fields
 * [RouteEtaPanel] renders as three separate stats) collapsed into one line so it can sit directly
 * beside the turn icon/instruction, per the direct request to show a distance/time/ETA summary
 * "next to or below the turn icon" rather than only in the separate strip below. `null` (no route
 * yet) renders as "—", never a fabricated distance.
 */
private fun remainingSummary(navState: MeterNavUiState): String {
    if (navState.route == null) return "—"
    return "${formatDistanceM(navState.remainingDistanceM)} · " +
        "${formatDurationS(navState.remainingDurationS)} · " +
        "ETA ${formatEtaClock(navState.etaEpochMillis)}"
}

/**
 * DESTINATION card on the nav pane — the real [GeocodeResult] the driver picked
 * ([MeterNavViewModel.selectDestination]), the current maneuver
 * ([navState.currentInstruction] plus a real [maneuverIcon] derived from
 * `navState.route.steps[navState.currentStepIndex]`'s own `maneuverType`/`modifier` — never a
 * guessed icon), the real remaining-trip [remainingSummary], and CHANGE/clear actions
 * ([onChange] reopens the same destination-search entry point; [onClear] calls
 * [MeterNavViewModel.clearDestination] outright, dropping back to mockup #3).
 */
@Composable
private fun NavDestinationCard(navState: MeterNavUiState, onChange: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val destination = navState.destination
    val currentStep = navState.route?.steps?.getOrNull(navState.currentStepIndex)
    val icon = maneuverIcon(currentStep?.maneuverType, currentStep?.modifier)
    GlassCard(modifier = modifier, cornerRadiusDp = 16) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            IconSquare(icon = Icons.Rounded.Flag, tint = CaptainPalette.danger, size = 30.dp)
            Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("DESTINATION", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.sp, color = CaptainPalette.danger)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "CHANGE",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            color = CaptainPalette.hudSweepMid,
                            modifier = Modifier.clickable(onClick = onChange),
                        )
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Clear destination",
                            tint = CaptainPalette.textMuted,
                            modifier = Modifier.size(13.dp).clickable(onClick = onClear),
                        )
                    }
                }
                Text(
                    destination?.placeName ?: "—",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = CaptainPalette.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                // Real turn icon (never guessed — see maneuverIcon's doc) beside the spoken
                // instruction text; the icon is simply omitted when the data doesn't support one.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    if (icon != null) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = CaptainPalette.hudAccent,
                            modifier = Modifier.size(14.dp).padding(end = 4.dp),
                        )
                    }
                    Text(
                        navState.currentInstruction ?: "—",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 10.sp,
                        color = CaptainPalette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    remainingSummary(navState),
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    color = CaptainPalette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Route/ETA strip under the PICK UP/DESTINATION cards — honestly reflects exactly one of four
 * real states: fetching ([navState.routing], a spinner), failed ([navState.routeError] + a real
 * RETRY calling [MeterNavViewModel.retryRoute]), live ([navState.route] non-null: DISTANCE/ETA/
 * ARRIVE from [MeterNavViewModel]'s own [NavProgress] arithmetic), or none yet.
 */
@Composable
private fun RouteEtaPanel(navState: MeterNavUiState, onRetry: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 14) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            when {
                navState.routing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = CaptainPalette.hudAccent, strokeWidth = 2.dp)
                    Text(
                        "Finding route…",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = CaptainPalette.textSecondary,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                navState.routeError != null -> {
                    Text(
                        navState.routeError,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = CaptainPalette.danger,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    CaptainButton(text = "RETRY", outline = true, heightDp = 34, fontSize = 12.sp, widthDp = 88, onClick = onRetry)
                }
                navState.route != null -> {
                    MiniEtaStat("DISTANCE", formatDistanceM(navState.remainingDistanceM))
                    MiniEtaStat("ETA", formatDurationS(navState.remainingDurationS))
                    MiniEtaStat("ARRIVE", formatEtaClock(navState.etaEpochMillis))
                }
                else -> Text(
                    "No route yet",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = CaptainPalette.textMuted,
                )
            }
        }
    }
}

@Composable
private fun MiniEtaStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 8.sp, letterSpacing = 1.sp, color = CaptainPalette.textMuted)
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = CaptainPalette.textPrimary, modifier = Modifier.padding(top = 1.dp))
    }
}

@Composable
private fun AccrualNote() {
    Text(
        "One of distance or waiting accrues at a time — switches automatically at 26 km/h",
        fontFamily = InterFamily,
        fontSize = 10.sp,
        color = CaptainPalette.textMuted,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

// ============================================================================================
// Destination search — reached from [MapDestinationSearchBar] (the always-visible map-panel
// entry point, both mockups), [ControlsDrawer]'s MORE tile, or CHANGE (mockup #4's DESTINATION
// card) — all three open this identical dialog, never a duplicate search implementation.
// ============================================================================================

/**
 * The real destination search — [MeterNavViewModel.onQueryChange] drives the debounced Mapbox
 * Geocoding lookup, [nav.suggestions] is whatever it really returned, [nav.searching]/
 * [nav.searchError] are its real loading/failure states. Selecting a row calls
 * [onSelect] → [MeterNavViewModel.selectDestination] (the caller then dismisses this dialog,
 * which flips [MeterNavUiState.destination] non-null and switches the whole screen to mockup #4).
 *
 * **No system text field.** Every other on-device text entry in this app — driver #/PIN sign-in,
 * vehicle rego (`LoginVehicleBindScreen.kt`'s `AlphaNumPad`/`RegoKeyRows`) — is typed on a
 * hand-rolled on-screen keyboard, never the platform IME: on-device verification of this exact
 * dialog confirmed why (tapping a real `TextField` here never brought up a soft keyboard on the
 * target tablet — `dumpsys input_method` showed `mShowRequested=false` even with a Compose input
 * connection attached, and `adb shell input text`/`keyevent` landed nowhere). Building this dialog
 * on a system `TextField`, the one screen in this pass reaching for it, would have shipped a
 * control a real driver could never type into on the real hardware. [AddressKeypad] below is the
 * same on-screen-keyboard idiom as the rest of the app, sized for full address text (letters,
 * digits, space) rather than a short code. Every keystroke still goes through the identical
 * `onQueryChange(String)` call a system field would have made — [MeterNavViewModel] cannot tell
 * the difference.
 *
 * Same dialog shell as every other action on this screen ([TollPresetDialog], [SetPriceInfoDialog]
 * etc.) — no result is ever invented: an empty/erroring search renders exactly that, honestly.
 */
@Composable
private fun DestinationSearchDialog(
    nav: MeterNavUiState,
    onQueryChange: (String) -> Unit,
    onSelect: (GeocodeResult) -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(modifier = Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (nav.destination != null) "Change destination" else "Set destination",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = CaptainPalette.textPrimary,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CaptainPalette.inset)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = CaptainPalette.hudAccent, modifier = Modifier.size(18.dp))
                Text(
                    nav.query.ifEmpty { "Search an address…" },
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = if (nav.query.isEmpty()) CaptainPalette.textMuted else CaptainPalette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                )
                if (nav.searching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CaptainPalette.hudAccent, strokeWidth = 2.dp)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp, max = 190.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    nav.query.isBlank() -> SearchHintLine("Type at least 3 characters to search.")
                    nav.searchError != null && nav.suggestions.isEmpty() -> SearchHintLine(nav.searchError, color = CaptainPalette.danger)
                    nav.suggestions.isEmpty() && !nav.searching -> SearchHintLine("No matches found.")
                    else -> nav.suggestions.forEach { result ->
                        SuggestionRow(result = result, onClick = { onSelect(result) })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            CaptainButton(text = "Close", outline = true, modifier = Modifier.fillMaxWidth()) { onDismiss() }
        }
        AddressKeypad(
            onKey = { c -> onQueryChange(nav.query + c) },
            onSpace = { onQueryChange("${nav.query} ".trimStart()) },
            onBackspace = { onQueryChange(nav.query.dropLast(1)) },
            onClear = { onQueryChange("") },
        )
    }
}

/** Six-per-row alphabet+digit layout (26 letters + 10 digits fill exactly six rows of six) plus a
 * wide SPACE / BACKSPACE / CLR row — the same on-screen-keyboard shape [AlphaNumPad] in
 * `LoginVehicleBindScreen.kt` uses for driver #/rego entry, sized here for a full street address
 * rather than a short code. A private, small duplicate rather than a cross-file reach into that
 * `private` composable. */
@Composable
private fun AddressKeypad(onKey: (Char) -> Unit, onSpace: () -> Unit, onBackspace: () -> Unit, onClear: () -> Unit) {
    val rows = listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ0123", "456789")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c -> AddressKey(label = c.toString(), modifier = Modifier.size(48.dp), onClick = { onKey(c) }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AddressKey(label = "SPACE", modifier = Modifier.width(216.dp).height(48.dp), onClick = onSpace)
            AddressKey(modifier = Modifier.size(48.dp), onClick = onBackspace) {
                Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Backspace", tint = CaptainPalette.warning, modifier = Modifier.size(18.dp))
            }
            AddressKey(label = "CLR", modifier = Modifier.size(48.dp), accent = true, onClick = onClear)
        }
    }
}

@Composable
private fun AddressKey(
    label: String? = null,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CaptainPalette.raised)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            content != null -> content()
            label != null -> Text(
                label,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (label.length > 1) 12.sp else 18.sp,
                color = if (accent) CaptainPalette.warning else CaptainPalette.textPrimary,
            )
        }
    }
}

@Composable
private fun SearchHintLine(text: String, color: Color = CaptainPalette.textSecondary) {
    Text(text, fontFamily = InterFamily, fontSize = 13.sp, color = color, modifier = Modifier.padding(vertical = 10.dp))
}

@Composable
private fun SuggestionRow(result: GeocodeResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Place, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(result.shortName, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = CaptainPalette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(result.placeName, fontFamily = InterFamily, fontSize = 12.sp, color = CaptainPalette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ============================================================================================
// NIGHT / DAY FARE tile
// ============================================================================================

/**
 * The mockup's NIGHT FARE tile, state-driven off the engine's own [TimeClass] (the same field the
 * breakdown's "Night Fare" row keys on — never a local clock check that could disagree with what
 * is actually being charged). NIGHT: moon, the real night/day ratio off the signed tariff, the
 * 10 PM – 6 AM window (the local engine's own boundary, `FareEngine.kt#resolveTimeClass`), on a
 * [GlassCard] with the kit's accent halo. DAY/HOLIDAY: a calmer "DAY FARE · 1.00×" variant rather
 * than an empty slot — 1.00× is literally true (day rate is the baseline the night ratio is
 * measured against).
 */
@Composable
private fun NightFareTile(timeClass: TimeClass, tariff: TariffDto?) {
    val night = timeClass == TimeClass.NIGHT
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadiusDp = 18,
        glow = if (night) CaptainPalette.hudAccent else null,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (night) Icons.Rounded.Bedtime else Icons.Rounded.WbSunny,
                    contentDescription = null,
                    tint = if (night) CaptainPalette.hudAccent else CaptainPalette.warning,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    if (night) "NIGHT FARE" else "DAY FARE",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = CaptainPalette.textSecondary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                if (night) nightMultiplierLabel(tariff) ?: "—" else "1.00×",
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                color = CaptainPalette.textPrimary,
                style = if (night) glowStyle(CaptainPalette.hudAccent, 22f) else TextStyle.Default,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                if (night) "10:00 PM – 6:00 AM" else "6:00 AM – 10:00 PM",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/**
 * Real ≥56dp circular icon button replacing the previous bare-emoji `Text.clickable` (a real
 * small-touch-target accessibility problem for an elderly driver base) — same
 * `toggleSpeech(!speechEnabled)` call site (mockup #3, via [MoreActionsSheet]) or the mirrored
 * `onToggleVoice` (mockup #4's nav pane) — just a legible Material icon and a proper hit area.
 */
@Composable
private fun SpeechToggleButton(enabled: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (enabled) CaptainPalette.raised else CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (enabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
            contentDescription = if (enabled) "Speech announcements on" else "Speech announcements off",
            tint = if (enabled) CaptainPalette.accent else CaptainPalette.textMuted,
            modifier = Modifier.size(26.dp),
        )
    }
}

// ============================================================================================
// The dial — GlowingSpeedometer + inner glass disc
// ============================================================================================

/**
 * The speedometer/fare dial, entirely from the kit: [GlowingSpeedometer] is the outer ring (its
 * arc sweeps to `fareState.currentSpeedKmh` on [au.com.threesixty.cabdispatch.ui.theme.hudSpring]
 * — the live engine's own speed field, the same number FareEngineImpl decides DISTANCE-vs-WAITING
 * accrual on; it honestly sits at 0 with no fix). Its `content` slot holds a circular [GlassCard]
 * disc (so the figures sit on a stable surface over the map) carrying, top to bottom: car icon ·
 * ACTIVE FARE · the fare as [RollingMoneyText] · RUNNING (accent, glowing) / PAUSED (amber) ·
 * TARIFF + EXTRAS · DISTANCE / TIME / WAITING · END FARE. The numeric speed sits in the ring's
 * bottom gap. The dial is sized to the smaller of the slot's dimensions so it stays a circle
 * whatever the maxi/wheelchair banners do to the pane's height.
 */
@Composable
private fun MeterDial(fareState: FareState, isPaused: Boolean, onEndFare: () -> Unit, modifier: Modifier = Modifier) {
    val stateColor by animateColorAsState(
        targetValue = if (isPaused) CaptainPalette.warning else CaptainPalette.hudAccent,
        animationSpec = tween(300),
        label = "state-color",
    )
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val d: Dp = minOf(maxWidth, maxHeight)
        val inner: Dp = d * DIAL_INNER_FRACTION
        GlowingSpeedometer(
            speedKmh = fareState.currentSpeedKmh.toFloat(),
            modifier = Modifier.size(d),
        ) {
            GlassCard(
                modifier = Modifier.size(inner),
                cornerRadiusDp = (inner.value / 2f).roundToInt(),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.DirectionsCar,
                        contentDescription = null,
                        tint = stateColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "ACTIVE FARE",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 2.sp,
                        color = CaptainPalette.textSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    val totalText = fareState.total.toMoneyString()
                    RollingMoneyText(
                        amount = totalText,
                        fontSize = if (totalText.length > 8) 36.sp else 44.sp,
                    )
                    Text(
                        if (isPaused) "PAUSED" else "RUNNING",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 3.sp,
                        color = stateColor,
                        style = glowStyle(stateColor, 20f),
                    )
                    Text(
                        "${fareState.band.label.uppercase()} + EXTRAS",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 9.sp,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.textMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    // The dial OWNS these three live readouts (dedupe pass) — they appear nowhere
                    // else on the screen. WAITING goes amber while actually accruing.
                    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DialReadout("DISTANCE", fareState.distanceKm.setScale(1, RoundingMode.HALF_UP).toPlainString() + " KM")
                        DialReadout("TIME", "%d:%02d".format(fareState.movingSeconds / 60, fareState.movingSeconds % 60))
                        DialReadout(
                            "WAITING",
                            "%d:%02d".format(fareState.waitingSeconds / 60, fareState.waitingSeconds % 60),
                            valueColor = if (isPaused) CaptainPalette.warning else CaptainPalette.textPrimary,
                        )
                    }
                    // END FARE — inside the dial, per the mockup. Same endTrip { navigate(CLOSE_PAY) }
                    // call the old full-width END TRIP bar made (see the caller).
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .width(140.dp)
                            .height(36.dp)
                            .neonGlow(CaptainPalette.primary, 18.dp, strength = 0.9f, spread = 4.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Brush.horizontalGradient(listOf(CaptainPalette.primary, CaptainPalette.hudAccent)))
                            .border(1.dp, CaptainPalette.hudSweepMid.copy(alpha = 0.9f), RoundedCornerShape(18.dp))
                            .gameClick(onClick = onEndFare, shape = RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "END FARE",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 2.sp,
                            // onAccent (fixed white), not textPrimary — this label sits on a solid
                            // primary/hudAccent gradient fill (see CaptainPalette.onAccent's doc).
                            color = CaptainPalette.onAccent,
                        )
                    }
                }
            }
            // Numeric speed in the ring's bottom gap (the 270° arc leaves 7:30 → 4:30 open).
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    fareState.currentSpeedKmh.roundToInt().toString(),
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = CaptainPalette.hudAccent,
                )
                Text(
                    " km/h",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    color = CaptainPalette.textMuted,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun DialReadout(label: String, value: String, valueColor: Color = CaptainPalette.textPrimary) {
    Column(modifier = Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 8.sp, letterSpacing = 1.sp, color = CaptainPalette.textMuted)
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = valueColor, maxLines = 1)
    }
}

// ============================================================================================
// Action tiles (SET PRICE / ADD TOLL / PAUSE FARE / MORE) — a 2×2 grid in MeterPaneLayout's
// right column (both tiles-per-row calls live there now; no separate vertical-stack variant).
// ============================================================================================

@Composable
private fun SetPriceTile(a: MeterActions, modifier: Modifier) = MeterActionTile(
    icon = Icons.Rounded.Sell,
    label = "SET PRICE",
    // Honest status line, not a fake "tap to edit" — see SetPriceInfoDialog's doc for why this
    // button is informational only during an active trip.
    value = if (a.negotiatedTotal != null) "Fixed · ${formatNegotiatedTotal(a.negotiatedTotal)}" else "Metered fare",
    onClick = a.onSetPrice,
    modifier = modifier,
)

@Composable
private fun AddTollTile(a: MeterActions, modifier: Modifier) = MeterActionTile(
    icon = Icons.Rounded.ConfirmationNumber,
    label = "ADD TOLL",
    value = "${a.tollsTotal.toMoneyString()} · ${a.tollCount} toll${if (a.tollCount == 1) "" else "s"} added",
    accentColor = CaptainPalette.warning,
    onClick = a.onAddToll,
    modifier = modifier,
)

@Composable
private fun PauseFareTile(a: MeterActions, modifier: Modifier) = MeterActionTile(
    icon = if (a.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
    label = if (a.isPaused) "RESUME FARE" else "PAUSE FARE",
    value = if (a.isPaused) "Waiting — tap to resume" else "Tap when the trip stops",
    accentColor = if (a.isPaused) CaptainPalette.warning else CaptainPalette.success,
    active = a.isPaused,
    onClick = a.onTogglePause,
    modifier = modifier,
)

@Composable
private fun MoreTile(a: MeterActions, modifier: Modifier) = MeterActionTile(
    icon = Icons.Rounded.MoreHoriz,
    label = "MORE",
    value = "Destination · extras · passengers",
    onClick = a.onMore,
    modifier = modifier,
)

/** Compact [GlassCard] tile: icon in a coloured rounded square, bold label, one-line subtext.
 * [active] lights the kit's halo in the accent colour (PAUSE FARE while paused). */
@Composable
private fun MeterActionTile(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = CaptainPalette.hudAccent,
    active: Boolean = false,
) {
    val shape = RoundedCornerShape(16.dp)
    val labelColor by animateColorAsState(
        targetValue = if (active) accentColor else CaptainPalette.textPrimary,
        animationSpec = tween(250),
        label = "tile-label",
    )
    GlassCard(
        modifier = modifier.fillMaxWidth().gameClick(onClick = onClick, shape = shape, glowColor = accentColor),
        cornerRadiusDp = 16,
        glow = if (active) accentColor else null,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            IconSquare(icon = icon, tint = accentColor, size = 32.dp, lit = active)
            Text(
                label,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 0.5.sp,
                color = labelColor,
                style = if (active) glowStyle(accentColor, 14f) else TextStyle.Default,
                maxLines = 1,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                value,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 9.5.sp,
                color = CaptainPalette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Icon in a tinted, rounded square — the mockup's action-tile / stop-card glyph treatment. */
@Composable
private fun IconSquare(icon: ImageVector, tint: Color, size: Dp, lit: Boolean = false) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = if (lit) 0.32f else 0.18f))
            .border(1.dp, tint.copy(alpha = if (lit) 0.9f else 0.4f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.6f))
    }
}

/** `negotiatedTotal` is a decimal-as-string (this project's money-field convention, see
 * `ApiService.kt`'s header note) — reused here as plain display text, never re-parsed into a new
 * fare calculation. Falls back to the raw string (prefixed) on a malformed value rather than
 * crashing a dialog over a display nicety. */
private fun formatNegotiatedTotal(raw: String): String =
    runCatching { BigDecimal(raw).toMoneyString() }.getOrDefault("$$raw")

/**
 * SET PRICE, tapped (Phase A step 5) — deliberately **not** an editable control.
 * `TripContext.negotiatedTotal` is real: it is the fixed fare the driver agreed with the passenger
 * at Start Meter time (via the dashboard's own Set Price flow), already persisted to
 * `TripEntity.negotiatedTotal` and synced to the backend — but nothing in [HiredViewModel] can
 * *change* it once a trip is running (no `setNegotiatedTotal()`/equivalent exists, and this pass's
 * `HiredViewModel` edit budget is scoped to wiring the already-existing
 * `breakdownExpanded`/`toggleBreakdown()` pair only — adding one would be new business logic this
 * pass has no mandate to add). Building a text field or keypad here that looks like it edits the
 * price, when nothing downstream would ever read the edit, is exactly the fake affordance this
 * codebase's own EXTRAS button already refuses to be (see that dialog's identical shape) — so this
 * shows the real value (or its real absence) and says why, instead.
 */
@Composable
private fun SetPriceInfoDialog(negotiatedTotal: String?, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .width(520.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Set Price", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
        Text(
            if (negotiatedTotal != null) {
                "This trip is a fixed fare of ${formatNegotiatedTotal(negotiatedTotal)}, agreed before the meter started. " +
                    "It can't be changed once a trip is running."
            } else {
                "This trip is running on the metered fare. To fix a price up front instead, use SET PRICE " +
                    "from the dashboard before starting the next trip."
            },
            fontFamily = InterFamily,
            fontSize = 15.sp,
            color = CaptainPalette.textSecondary,
        )
        CaptainButton(text = "OK", outline = true, widthDp = 180) { onDismiss() }
    }
}

/**
 * ADD TOLL, tapped (Phase A step 5) — the same three real toll presets ([TollPresets.ALL]) and
 * custom-amount pad this screen always had, consolidated from four separate inline chips into one
 * dialog reached from the action stack. `onAddPreset`/`onCustom` map straight back to
 * `viewModel.addToll(preset)` at the call site — no new toll logic here.
 */
@Composable
private fun TollPresetDialog(tollsTotal: BigDecimal, onDismiss: () -> Unit, onAddPreset: (TollPreset) -> Unit, onCustom: () -> Unit) {
    // Presets in one Row and the two buttons in another (game-level visual pass): this dialog is
    // hosted inside the ~420dp-tall METER pane (CaptainDialogScrim fills the pane, not the
    // window), and the previous five-row stack ran ~500dp — its Close button was clipped behind
    // the footer on-device. Same chips, same callbacks, just laid out to fit.
    Column(
        modifier = Modifier
            .width(720.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Add toll", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
        Text(
            "Tolls so far: ${tollsTotal.toMoneyString()}",
            fontFamily = InterFamily,
            fontSize = 14.sp,
            color = CaptainPalette.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TollPresets.ALL.forEach { preset ->
                CaptainChip(preset.label.uppercase(), preset.amount.toMoneyString(), modifier = Modifier.weight(1f)) {
                    onAddPreset(preset)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CaptainButton(text = "Custom amount…", outline = true, modifier = Modifier.weight(1.4f)) { onCustom() }
            CaptainButton(text = "Close", outline = true, modifier = Modifier.weight(1f)) { onDismiss() }
        }
    }
}

/**
 * MORE, tapped (Phase A step 5; destination search added 2026-09-04) — an overflow sheet for the
 * previously-inline EXTRAS-explainer, passenger-count correction, speech-announcement toggle, and
 * now the real destination search (opens [DestinationSearchDialog] via [onNavigate]), so the
 * action stack itself stays to exactly four rows matching the mockup. Every row still calls the
 * exact same pre-existing ViewModel entry point (`updatePassengerCount()`/`toggleSpeech()`) or the
 * real [MeterNavViewModel] search flow via the caller's lambdas.
 */
@Composable
private fun MoreActionsSheet(
    speechEnabled: Boolean,
    passengerCount: Int,
    hasDestination: Boolean,
    destinationLabel: String?,
    onToggleSpeech: (Boolean) -> Unit,
    onEditPassengers: () -> Unit,
    onExtras: () -> Unit,
    onNavigate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(480.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("More", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)

        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onNavigate).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Navigation, contentDescription = null, tint = CaptainPalette.hudAccent, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    if (hasDestination) "Change destination" else "Set destination",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = CaptainPalette.textPrimary,
                )
                Text(
                    destinationLabel ?: "Search an address to start turn-by-turn",
                    fontFamily = InterFamily,
                    fontSize = 12.sp,
                    color = CaptainPalette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onExtras).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Receipt, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text("Extras", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
                Text("No chargeable extras configured yet", fontFamily = InterFamily, fontSize = 12.sp, color = CaptainPalette.textSecondary)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onEditPassengers).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Person, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text("Passenger count", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
                Text("$passengerCount — tap to correct", fontFamily = InterFamily, fontSize = 12.sp, color = CaptainPalette.textSecondary)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Speech announcements", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
                Text(if (speechEnabled) "Announcing fare + nav turns" else "Off", fontFamily = InterFamily, fontSize = 12.sp, color = CaptainPalette.textSecondary)
            }
            SpeechToggleButton(enabled = speechEnabled, onToggle = { onToggleSpeech(!speechEnabled) })
        }

        CaptainButton(text = "Close", outline = true, modifier = Modifier.fillMaxWidth()) { onDismiss() }
    }
}

// ============================================================================================
// Fare Breakdown card (HiredViewModel.breakdownExpanded / toggleBreakdown())
// ============================================================================================

@Composable
private fun FareBreakdownCard(
    title: String,
    breakdown: FareBreakdown,
    timeClass: TimeClass,
    nightMultiplierLabel: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(title)
                Spacer(Modifier.weight(1f))
                val chevronRotation by animateFloatAsStateHud(if (expanded) 90f else -90f)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, CaptainPalette.hudAccent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onToggle)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (expanded) "HIDE" else "SHOW",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = CaptainPalette.hudSweepMid,
                    )
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = CaptainPalette.hudSweepMid,
                        modifier = Modifier.size(16.dp).padding(start = 2.dp).rotate(chevronRotation),
                    )
                }
            }
            AnimatedVisibility(visible = expanded, enter = fadeIn(tween(180)), exit = fadeOut(tween(140))) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    BreakdownRow("Base fare", breakdown.flagFall.toMoneyString(), CaptainPalette.success)
                    BreakdownRow("Distance", breakdown.distanceAmount.toMoneyString(), CaptainPalette.success)
                    BreakdownRow("Time", breakdown.waitingAmount.toMoneyString(), CaptainPalette.success)
                    // Informational only: the night-rate uplift is already baked into Distance/Time
                    // above (FareEngineImpl applies the night per-km/per-min rate directly — there is
                    // no separate night-surcharge line item to show), so this never adds to `total`
                    // itself, only explains the higher Distance/Time figures when it applies.
                    if (timeClass == TimeClass.NIGHT) {
                        BreakdownRow("Night fare (${nightMultiplierLabel ?: "—"})", "included", CaptainPalette.hudSweepMid)
                    }
                    if (breakdown.peakAmount.signum() > 0) {
                        BreakdownRow("Peak hiring", breakdown.peakAmount.toMoneyString(), CaptainPalette.hudSweepMid)
                    }
                    BreakdownRow("Tolls", breakdown.tolls.toMoneyString(), CaptainPalette.warning)
                    BreakdownRow("Levy & charges", breakdown.psl.toMoneyString(), CaptainPalette.danger)
                    if (breakdown.extras.signum() > 0) {
                        BreakdownRow("Extras", breakdown.extras.toMoneyString(), CaptainPalette.warning)
                    }
                }
            }
            // No TOTAL row, deliberately: the dial's ACTIVE FARE figure IS the total; this card
            // owns the itemisation that makes it up.
        }
    }
}

/** The kit's one spring for a small UI transition (the HIDE/SHOW chevron). */
@Composable
private fun animateFloatAsStateHud(target: Float) =
    animateFloatAsState(target, animationSpec = hudSpring(), label = "hud-chevron")

@Composable
private fun BreakdownRow(label: String, value: String, dotColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(dotColor, 8.dp)
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = CaptainPalette.textSecondary,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
        )
        Text(
            value,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = CaptainPalette.textPrimary,
        )
    }
}

/** Coloured bullet with a soft same-colour halo ring. */
@Composable
private fun ColorDot(color: Color, size: Dp) {
    Box(
        modifier = Modifier
            .size(size + 6.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).clip(CircleShape).background(color))
    }
}

// ============================================================================================
// Trip Details card (vertical pickup → drop-off timeline) — mockup #3 only
// ============================================================================================

/**
 * [startAtIso] is the persisted `TripEntity.startAt` (real open time) — `null` until Room has the
 * row, rendering "—". Drop-off time is always "—" here: the trip is in progress. Addresses are
 * `TripContext.originAddress`/`.destAddress` — "—" when absent (see that doc), never fabricated.
 * DISTANCE and DURATION live on the dial (its own readouts); AVG SPEED is genuinely only here.
 */
@Composable
private fun TripDetailsCard(tripContext: TripContext?, fareState: FareState, startAtIso: String?) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("TRIP DETAILS")
                Spacer(Modifier.weight(1f))
                Text(
                    tripContext?.clientUuid?.take(8)?.uppercase() ?: "—",
                    fontFamily = RobotoMonoFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    color = CaptainPalette.textMuted,
                )
            }
            Spacer(Modifier.height(10.dp))
            TimelineRow(
                dotColor = CaptainPalette.success,
                title = "PICKUP",
                address = tripContext?.originAddress ?: "—",
                time = startAtIso?.asLocalTime() ?: "—",
                connector = true,
            )
            TimelineRow(
                dotColor = CaptainPalette.danger,
                title = "DROP-OFF",
                address = tripContext?.destAddress ?: "—",
                time = "—",
                connector = false,
            )
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.panelBorder))
            Spacer(Modifier.height(10.dp))
            val avgSpeedKmh = if (fareState.movingSeconds > 0) {
                (fareState.distanceKm.toDouble() / (fareState.movingSeconds / 3600.0)).roundToInt()
            } else {
                0
            }
            Column {
                Text("AVG SPEED", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.sp, color = CaptainPalette.textMuted)
                Text("$avgSpeedKmh km/h", fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = CaptainPalette.textPrimary, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun TimelineRow(dotColor: Color, title: String, address: String, time: String, connector: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(16.dp)) {
            ColorDot(dotColor, 10.dp)
            if (connector) {
                Box(Modifier.padding(vertical = 3.dp).width(2.dp).height(20.dp).background(CaptainPalette.panelBorder))
            }
        }
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.sp, color = dotColor)
                Text(time, fontFamily = RobotoMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = CaptainPalette.textSecondary)
            }
            Text(
                address,
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = CaptainPalette.textPrimary,
                maxLines = 2,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Mid-trip passenger-count correction dialog (Point to Point Transport (Fares) Order 2026
 * UI-wiring pass) — reached only via [MoreActionsSheet] now (previously a small "PAX n ✎" tap-to-
 * edit affordance floating on the meter well); same [HiredViewModel.updatePassengerCount] call and
 * dialog otherwise. Confirming re-derives [au.com.threesixty.cabdispatch.domain.FareState.maxiRateApplied]
 * immediately for the remainder of the trip without touching any already-accrued charge — see that
 * method's own doc.
 */
@Composable
private fun PassengerEditDialog(initialCount: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var count by remember { mutableStateOf(initialCount) }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(30.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Correct passenger count", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
        Text(
            "5 or more passengers may trigger the maxi rate — only for a genuine maxi vehicle, and never for a wheelchair hiring.",
            fontFamily = InterFamily,
            fontSize = 14.sp,
            color = CaptainPalette.textSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(if (count > 1) CaptainPalette.raised else CaptainPalette.inset)
                    .border(1.dp, CaptainPalette.panelBorder, CircleShape)
                    .then(if (count > 1) Modifier.clickable { count -= 1 } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text("−", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = CaptainPalette.textPrimary)
            }
            Box(
                modifier = Modifier.width(96.dp).height(72.dp).clip(RoundedCornerShape(14.dp)).background(CaptainPalette.inset),
                contentAlignment = Alignment.Center,
            ) {
                Text(count.toString(), fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, color = CaptainPalette.textPrimary)
            }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(if (count < 11) CaptainPalette.raised else CaptainPalette.inset)
                    .border(1.dp, CaptainPalette.panelBorder, CircleShape)
                    .then(if (count < 11) Modifier.clickable { count += 1 } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = CaptainPalette.textPrimary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CaptainButton(text = "Cancel", outline = true, modifier = Modifier.weight(1f), onClick = onDismiss)
            CaptainButton(text = "Update", modifier = Modifier.weight(1.4f)) { onConfirm(count) }
        }
    }
}

@Composable
private fun CustomTollDialog(onDismiss: () -> Unit, onConfirm: (BigDecimal) -> Unit) {
    var cents by remember { mutableStateOf("") }
    val amount = if (cents.isEmpty()) BigDecimal.ZERO else BigDecimal(cents).movePointLeft(2)
    // Two columns (amount + buttons | keypad) rather than one stack — same reason as
    // TollPresetDialog: hosted inside the ~420dp-tall METER pane, and title + amount + a
    // 4-row keypad + buttons stacked vertically ran past the pane's bottom edge on-device.
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(22.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(
            modifier = Modifier.width(300.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Add toll", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CaptainPalette.inset),
                contentAlignment = Alignment.Center,
            ) {
                // Rolling digits here too — money that changes as the driver types.
                RollingMoneyText(amount = amount.toMoneyString(), fontSize = 38.sp, color = CaptainPalette.success, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            CaptainButton(
                text = "Add toll",
                enabled = amount > BigDecimal.ZERO,
                modifier = Modifier.fillMaxWidth(),
            ) { onConfirm(amount) }
            CaptainButton(text = "Cancel", outline = true, modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
        CaptainKeypad(
            onDigit = { d -> if (cents.length < 5) cents += d },
            onBackspace = { cents = cents.dropLast(1) },
            onClear = { cents = "" },
        )
    }
}
