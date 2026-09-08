package au.com.threesixty.cabdispatch.ui.screens.hired

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ConfirmationNumber
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.location.RegionResolver
import au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto
import au.com.threesixty.cabdispatch.domain.AutoTollAlert
import au.com.threesixty.cabdispatch.domain.DuressUiState
import au.com.threesixty.cabdispatch.domain.FareState
import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.TollPreset
import au.com.threesixty.cabdispatch.domain.TripContext
import au.com.threesixty.cabdispatch.domain.TripStatus
import au.com.threesixty.cabdispatch.domain.toMoneyString
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.overlays.DuressActiveBanner
import au.com.threesixty.cabdispatch.ui.overlays.DuressTriggeredOverlay
import au.com.threesixty.cabdispatch.ui.overlays.HiddenDuressGestureZone
import au.com.threesixty.cabdispatch.ui.overlays.NavigationTarget
import au.com.threesixty.cabdispatch.ui.overlays.openInMaps
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainDialogScrim
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.GlowingSpeedometer
import au.com.threesixty.cabdispatch.ui.theme.HudStatusPill
import au.com.threesixty.cabdispatch.ui.theme.HudTone
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.Radius
import au.com.threesixty.cabdispatch.ui.theme.RollingMoneyText
import au.com.threesixty.cabdispatch.ui.theme.neonGlow
import java.math.BigDecimal

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
    // Read straight from the simulator rather than from the trip row, so the banner is live the
    // instant simulation starts or stops -- including on a trip that was opened before it was
    // switched on. See the banner itself, below.
    val simulatingGps by AppContainer.gpsSimulator.active.collectAsState()
    val context = LocalContext.current

    // BACK IS NOW ALLOWED (A4, 2026-09-08) — the `BackHandler(enabled = true) {}` that used to sit
    // here is gone.
    //
    // WHY IT WAS THERE. Back popped the HIRED entry, destroying this [HiredViewModel] and with it
    // the live-ticking `FareEngineImpl` it owned in its `viewModelScope`. Because
    // [au.com.threesixty.cabdispatch.domain.SessionHolder.pendingTrip] is never cleared once a trip
    // starts, the next "METER" tap built a BRAND NEW HiredViewModel that re-ran startTrip()/
    // openTripInRoom() against that same stale pending context — resetting the on-screen fare to
    // $0/0:00 and opening a second, orphaned TripEntity row alongside the still-OPEN original.
    // Swallowing back was the only thing standing between a driver's thumb and a destroyed fare.
    //
    // WHY IT IS NO LONGER NEEDED. A1 hoisted the fare engine out of this ViewModel entirely: it now
    // lives in `AppContainer.meterController`, driven by a `MeterForegroundService`, for the
    // lifetime of the *process* rather than of this composable. Destroying this ViewModel no longer
    // stops the meter — it stops an observer of it. `restoreOpenTripIfAny()` covers the harder case
    // (the process itself dying) by rebuilding the engine from the OPEN Room row. Back is safe.
    //
    // WHY REMOVING IT IS A FIX AND NOT JUST A TIDY-UP. A control that silently does nothing is its
    // own bug. A driver pressing the tablet's back button and getting no response at all cannot
    // tell a deliberate design from a frozen app, and the honest answer to "can I leave this
    // screen?" is now yes: back returns to the dashboard, the fare keeps running in the service,
    // the persistent "Fare running · $X.XX" notification stays up, and the nav rail's METER item
    // glows (A3 wired that off `hasActiveTrip`) as the way back in.
    //
    // Deliberately NOT a `BackHandler` that navigates somewhere itself: the default pop already
    // lands on the dashboard, and re-implementing it here would be a second, competing definition
    // of where back goes.

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
    // The tariff the running fare is actually charging, for a RESUMED fare (tablet, 2026-09-08).
    // `tripContext` above is the in-memory hand-off set when the driver tapped START; after a
    // process death it is null, so on a resumed fare the Night Fare tile and the breakdown's
    // "Night fare (x)" row read "—" while the engine -- restored from Room with its tariff --
    // was charging correctly. The cache is keyed by region, and the fallback is accepted ONLY when
    // its id matches the open trip's own tariffId, so the label can never name a tariff other than
    // the one on the bill. No match: still an honest dash, never a plausible number.
    val region = remember { RegionResolver.resolve(AppContainer.speedSource.locationFix.value) }
    val cachedTariff by remember(region) { AppContainer.tariffCache.observeActiveTariff(region) }.collectAsState(initial = null)
    val resumedTariff: TariffDto? = cachedTariff?.takeIf { activeTrip != null && it.id == activeTrip?.tariffId }
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

    // "FARE RESUMED" — the driver-facing half of A1's restart recovery (A4, 2026-09-08).
    //
    // When the process died mid-hiring, `restoreOpenTripIfAny()` rebuilds the engine from the OPEN
    // Room row and the dial comes back showing the correct running total. Silently. From the
    // driver's side that is indistinguishable from a meter that just restarted and lost the first
    // ten minutes of the trip — and a driver who believes that is a driver who stops the fare and
    // argues with a passenger about it. This says which of the two actually happened.
    //
    // Longer than METER STARTED's 2s (5s): this one is read after an unexpected event, by someone
    // who was not necessarily looking at the tablet when it happened. Still self-dismissing, still
    // a single delayed reset rather than a loop.
    var showResumedBanner by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel.isRestoredFare) {
        if (viewModel.isRestoredFare) {
            showResumedBanner = true
            kotlinx.coroutines.delay(5000)
            showResumedBanner = false
        }
    }

    // Automatic NSW toll-road detection — audible+on-screen confirmation (product requirement,
    // 2026-09: "when vehicle move from that location diameter, automatically it will make beep
    // sound and show toll has been added"). The speech half lives in HiredViewModel (see its own
    // doc); this is the "show toll has been added" half — a one-shot, self-dismissing banner, same
    // pattern as showStartedBanner immediately above (a single delayed reset, not a repeating/
    // looping animation). Keyed on the alert's own [AutoTollAlert.id], not nullness, so a second
    // real alert while the first is still fading restarts the timer instead of being ignored.
    var autoTollBanner by remember { mutableStateOf<AutoTollAlert?>(null) }
    LaunchedEffect(fareState.lastAutoTollAlert?.id) {
        val alert = fareState.lastAutoTollAlert
        if (alert != null) {
            autoTollBanner = alert
            // 7s, not 4: long enough for a passenger to look up, find the banner, and read both
            // the road and the amount off it. Still self-dismissing -- it must never sit over the
            // running fare permanently.
            kotlinx.coroutines.delay(7000)
            autoTollBanner = null
        }
    }

    val onEndFare: () -> Unit = {
        viewModel.endTrip { navController.navigate(CabDispatchRoutes.CLOSE_PAY) }
    }
    val actions = MeterActions(
        isPaused = isPaused,
        negotiatedTotal = tripContext?.negotiatedTotal,
        tollsTotal = fareState.breakdown.tolls,
        // Includes auto-detected tolls (Automatic NSW toll-road detection pass) — the ADD TOLL
        // tile's subtext is the driver's at-a-glance signal that something was added even if they
        // never open the dialog; see TollPresetDialog's own auto-tolls section for the detail view.
        tollCount = fareState.tollsApplied.size + fareState.autoTollsApplied.size,
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
            // Fabricated GPS in use. Deliberately NOT an AnimatedVisibility fade like the
            // banners below it, and deliberately the loudest colour on the palette: a driver (or
            // anyone glancing at the tablet) must never be able to look at a running meter and
            // not know the position, distance and fare are being driven by a simulator rather
            // than the road. See GpsSimulator's own doc -- this banner is one of the two things
            // that make that tool safe to have in the app at all.
            if (simulatingGps) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // start inset: the FLEET LOCKED / TABLET NOT REGISTERED chips overlay this
                        // exact lane top-start (tablet, 2026-09-08 -- the chip sat on the banner's
                        // left end). The banner begins to the right of the chip lane instead.
                        .padding(start = 176.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CaptainPalette.danger)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "⚠  SIMULATED GPS — NOT A REAL FARE",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.bg,
                    )
                }
            }
            AnimatedVisibility(visible = fareState.maxiRateApplied, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
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
                GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), cornerRadiusDp = 12) {
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
                    persistedTrace = persistedTrace,
                    liveTrace = liveTrace,
                    liveFix = liveFix,
                    navState = navState,
                    hasDestination = hasDestination,
                    speechEnabled = speechEnabled,
                    onEndFare = onEndFare,
                    onTogglePause = viewModel::togglePause,
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
                    .padding(horizontal = 24.dp, vertical = 8.dp),
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

        AnimatedVisibility(
            visible = showResumedBanner,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
            enter = fadeIn(),
            exit = fadeOut(animationSpec = tween(400)),
        ) {
            Row(
                modifier = Modifier
                    .neonGlow(CaptainPalette.hudAccent, 99.dp, strength = 0.8f)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(CaptainPalette.hudAccent)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Decorative (A4 a11y pass, reviewed): the text beside this glyph already IS its label,
                // and Compose merges this node's semantics into one announcement -- a description here
                // would make TalkBack read the same words twice. The audit's finding was unlabelled
                // *controls*; every control on this screen now carries a real name. This is a reviewed
                // null, not an overlooked one.
                Icon(
                    Icons.Rounded.Restore,
                    contentDescription = null,
                    tint = CaptainPalette.bg,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    // Says the two things a driver needs after a crash, in order: the fare did not
                    // restart, and this figure is the real running total.
                    "FARE RESUMED after restart",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = CaptainPalette.bg,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = autoTollBanner != null,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
            enter = fadeIn(),
            exit = fadeOut(animationSpec = tween(400)),
        ) {
            val banner = autoTollBanner
            if (banner != null) {
                // Sized for the PASSENGER, not the driver. This is a charge appearing on the fare
                // without anyone touching the meter, so the person paying it has to be able to
                // read what was added and how much from across the back seat -- on a dash-mounted
                // 1200px tablet an ordinary 15sp label is illegible from there. The amount is the
                // single most important thing on it, so it gets the largest type on the screen
                // after the fare itself; the road name answers "for what?".
                Column(
                    modifier = Modifier
                        .neonGlow(CaptainPalette.warning, 28.dp, strength = 0.85f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(CaptainPalette.panel)
                        .border(2.dp, CaptainPalette.warning, RoundedCornerShape(24.dp))
                        // Ties into the same inspect/correct affordance the driver would reach via
                        // ADD TOLL — tapping the confirmation opens the exact dialog that lists it
                        // (with Remove), so "I heard a beep I think is wrong" is one tap away.
                        .clickable { showTollMenu = true }
                        .padding(horizontal = 44.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Decorative (A4 a11y pass, reviewed): the text beside this glyph already IS its label,
                        // and Compose merges this node's semantics into one announcement -- a description here
                        // would make TalkBack read the same words twice. The audit's finding was unlabelled
                        // *controls*; every control on this screen now carries a real name. This is a reviewed
                        // null, not an overlooked one.
                        Icon(
                            Icons.Rounded.ConfirmationNumber,
                            contentDescription = null,
                            tint = CaptainPalette.warning,
                            modifier = Modifier.size(34.dp),
                        )
                        Text(
                            "TOLL ADDED",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            letterSpacing = 4.sp,
                            color = CaptainPalette.warning,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        banner.roadName,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 28.sp,
                        color = CaptainPalette.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        banner.amount.toMoneyString(),
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 76.sp,
                        color = CaptainPalette.warning,
                    )
                }
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
                autoTolls = fareState.autoTollsApplied,
                unpricedRoads = fareState.unpricedTollRoads,
                onDismiss = { showTollMenu = false },
                onAddPreset = { preset ->
                    showTollMenu = false
                    viewModel.addToll(preset)
                },
                onCustom = {
                    showTollMenu = false
                    showTollPad = true
                },
                onRemoveAutoToll = { roadId -> viewModel.removeAutoToll(roadId) },
                onAddManualForUnpriced = {
                    showTollMenu = false
                    showTollPad = true
                },
                onDismissUnpriced = { roadId -> viewModel.dismissUnpricedToll(roadId) },
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
                verticalArrangement = Arrangement.spacedBy(16.dp),
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
                tariffFallback = resumedTariff,
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
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp),
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
internal class MeterActions(
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
// DIAL LEFT | MAP RIGHT, no third column — see MeterPaneLayout's doc). Weighted, not fixed dp, so
// each panel genuinely fills its share of whatever width/height DeckHomeScreen's slot gives this
// Row (which is already the full footer-collapsed height for the whole time this pane is shown).
//
// Rebalanced twice on direct driver feedback. First (2026-09-06 AM): "map should be the center, so
// driver should see the route very straight" -- moved off an even 50/50 split to give the map a
// clear ~58% majority. Then reverted the same day (2026-09-06 PM), same driver, on a second real-
// world test: the fare/distance/time/tariff text needs to be readable by a PASSENGER sitting to the
// side of the vehicle, not just the driver navigating -- "make it prominent... fifty percent should
// have the speedometer, and fifty percent should have the map... the text will be increased." Back
// to an even split, and MeterDial's own text sizes are bumped alongside this (see that function).
// This still keeps BOTH panels as their own real, clearly bounded surface (the original 2026-09-04b
// correction: "never a backdrop the dial sits on top of" -- an equal split is not the same change
// as making one float over the other, and does not undo that fix).
internal const val DIAL_COL_WEIGHT = 1f

private const val MAP_COL_WEIGHT = 1f

private val COL_GAP = 16.dp

/** Text glow — a same-colour paint shadow (blur radius in px), the cheap way to "bloom" a label.
 * Text only; every arc/ring glow on this screen is the kit's. */
internal fun glowStyle(color: Color, blurPx: Float = 18f, alpha: Float = 0.85f): TextStyle =
    TextStyle(shadow = Shadow(color = color.copy(alpha = alpha), offset = Offset.Zero, blurRadius = blurPx))

/** Muted upper-case section label used by every card header on this screen. */
@Composable
internal fun SectionLabel(text: String, color: Color = CaptainPalette.textPrimary) {
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
 * bigger. [DIAL_COL_WEIGHT]/[MAP_COL_WEIGHT] are back to an even split (2026-09-06, see those
 * constants' own doc for the two-round rebalance) — a passenger seated beside the dial needs the
 * fare/distance/time text at real size, which needs the dial at its full half-share, not a map
 * majority.
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
internal fun RowScope.MeterPaneLayout(
    fareState: FareState,
    isPaused: Boolean,
    tripContext: TripContext?,
    persistedTrace: List<TelemetryPointDto>,
    liveTrace: List<MapPoint>,
    liveFix: LocationFix?,
    navState: MeterNavUiState,
    hasDestination: Boolean,
    speechEnabled: Boolean,
    onEndFare: () -> Unit,
    onTogglePause: () -> Unit,
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
                onTogglePause = onTogglePause,
                modifier = Modifier.fillMaxSize().padding(6.dp),
            )
        }
        ControlsHandle(onClick = onOpenControls, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
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
        Column(modifier = Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            // The actual turn-by-turn guidance, given real top-of-map billing (direct correction,
            // 2026-09-05: the maneuver used to be a 10sp subtext line buried in the bottom-corner
            // DESTINATION card — easy to miss entirely while driving). Shown only once a route is
            // actually live; before that the search bar above is the only thing here, same as today.
            AnimatedVisibility(
                visible = hasDestination && navState.route != null,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
            ) {
                NavTurnBanner(navState = navState, modifier = Modifier.widthIn(max = 480.dp))
            }
        }
        if (hasDestination) {
            // One slim glass bar, not three stacked cards — direct correction, 2026-09-05: the old
            // PICK UP/DESTINATION row + separate ETA strip + separate OPEN NAVIGATION row cost
            // ~190dp of map real estate and read as opaque boxes over the driving surface. Same
            // fields, same actions, laid out to cost roughly a third of that.
            NavBottomBar(
                navState = navState,
                onChange = onChangeDestination,
                onClear = onClearDestination,
                onRetryRoute = onRetryRoute,
                onOpenNavigation = { destination?.let { onOpenNavigation(NavigationTarget(it.lat, it.lng, it.placeName)) } },
                speechEnabled = speechEnabled,
                onToggleVoice = onToggleVoice,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            )
        }
    }
}
