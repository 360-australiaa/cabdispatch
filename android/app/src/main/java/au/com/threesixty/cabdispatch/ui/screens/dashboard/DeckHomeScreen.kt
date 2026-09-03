package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachMoney
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SsidChart
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import au.com.threesixty.cabdispatch.data.remote.MapboxStaticImage
import au.com.threesixty.cabdispatch.data.remote.SydneyCbdFallback
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.ShiftDurationLimit
import au.com.threesixty.cabdispatch.domain.DuressUiState
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.ShiftSubmissionHandoff
import au.com.threesixty.cabdispatch.ui.overlays.DuressActiveBanner
import au.com.threesixty.cabdispatch.ui.overlays.DuressTriggeredOverlay
import au.com.threesixty.cabdispatch.domain.TripDetailHandoff
import au.com.threesixty.cabdispatch.ui.deck.DeckKeypad
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.ui.screens.earnings.EarningsWheelContent
import au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen
import au.com.threesixty.cabdispatch.ui.screens.messages.MessagesWheelContent
import au.com.threesixty.cabdispatch.ui.screens.pricing.PricingPaneContent
import au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftWheelContent
import au.com.threesixty.cabdispatch.ui.screens.trips.TripsPaneVariant
import au.com.threesixty.cabdispatch.ui.screens.trips.TripsWheelContent
import au.com.threesixty.cabdispatch.ui.screens.vouchers.VouchersPaneContent
import au.com.threesixty.cabdispatch.ui.screens.zones.ZonesPaneContent
import androidx.compose.animation.core.spring
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.CaptainPanel
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.theme.neonGlow
import au.com.threesixty.cabdispatch.ui.theme.DriverAvatar
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.PaneShell
import au.com.threesixty.cabdispatch.ui.theme.PulsingDot
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import au.com.threesixty.cabdispatch.ui.theme.SosControl
import au.com.threesixty.cabdispatch.ui.theme.rememberInfiniteFloat
import au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelContent
import au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelViewModel
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Apps
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Captain Taxis dashboard — the 2026-08-29 visual pass against the Captain Taxis Figma file
 * (`NP1afUMe5UKIl3CQUBRnyV`, "Driver Tablet — First 3 Screens": `01 · HOME — Collapsed Rail`,
 * `02 · HOME — Expanded Menu`, `03 · START METER — Pressed / Transition`). Registered under the
 * same `IDLE` route and driven by the same [WheelDashboardViewModel] as the Command Deck layout it
 * replaces (session/availability/tariff/today's-stats/status-strip wiring unchanged — this pass is
 * layout/visual, not a new data layer) plus [AvailableTripsWheelViewModel], reused as-is from the
 * existing Available Trips feature for the live dispatch card below.
 *
 * ## Where this deliberately does NOT copy the Figma file literally
 *
 * Per this pass's own brief ("do not blindly copy Figma, do not fake data") — every deviation
 * below is a real, checked decision, not an oversight:
 *
 * - **Palette.** Uses the new [CaptainPalette] (purple), not [au.com.threesixty.cabdispatch.ui.theme.Deck]
 *   (yellow) — repainting [Deck] itself would silently rebrand every OTHER screen in this app
 *   (Hired, Settings, Profile, Close & Pay…), which is a full-app rebrand decision this 3-screen
 *   pass has no mandate to make. Flagged in the delivery notes as a call for the user.
 * - **Night fare tile.** Figma's mock shows a flat "1.25× · 10:00 PM – 6:00 AM". [nightMultiplierLabel]
 *   below computes the real ratio from the driver's own signed tariff rather than hardcoding
 *   1.25× — confirmed safe either way by the backend's 2026-08-29 contract (Part 2.3), which also
 *   confirmed the *window* text: the backend's own fare engine hardcodes 10pm–6am server-side and
 *   enforces it at trip-tick/close time regardless of what this display says, so "10:00 PM – 6:00
 *   AM" is shown as-is. Found in the process, and NOT fixed here (out of this 3-screen pass's
 *   mandate, and a money-calculation change): this app's own local
 *   [au.com.threesixty.cabdispatch.domain.FareEngine.classify] — used only by the live-ticking
 *   Hired screen, not these 3 screens — currently classifies night as 8pm–6am, disagreeing with
 *   the backend's real 10pm–6am boundary. Flagged in the delivery notes as a real, separate bug.
 * - **"BOOKED"/"RANK JOB" badges and "2.1 km · 6 min".** Originally not backed by any field
 *   (confirmed against the live schema pre-2026-08-29). The backend's contract for this pass adds
 *   exactly these fields — [JobDto.jobType]/[distanceKm]/[etaMin], server-computed haversine + a
 *   flat 30km/h heuristic, explicitly flagged by the backend itself as an approximation, not
 *   routed/live-traffic — and [DispatchOfferRow] now reads them directly, falling back to this
 *   app's own live-GPS straight-line distance (never a fabricated ETA) only for a job created
 *   before that migration landed.
 * - **"NEXT BREAK" ring / "Working until 06:12 PM" / TAKE BREAK.** Confirmed absent server-side too
 *   by the same backend contract (Part 2.4: "no dedicated 'next break due' endpoint exists or is
 *   needed" — the raw inputs, tenant fatigue-limit + shift start + break_taken, are meant to be
 *   composed client-side, but `break_taken`/`break_started_at`/a break start-stop API are NOT yet
 *   surfaced to this Android client's session model). That quadrant stays repurposed as "SHIFT
 *   LIMIT", built from [ShiftDurationLimit] (a documented client-side mirror of the backend's 12h
 *   fatigue-limit default). A real TAKE BREAK feature is future work, not fabricated here.
 * - **"VOUCHERS · 2 · Available".** Confirmed absent server-side by the same backend contract
 *   (Part 2.3/9: "NOT CURRENTLY AVAILABLE... do not invent a count... requires a real product
 *   decision"). The tile keeps its honest copy instead.
 * - **"TRIPS 9 Completed / 3 Active" and "↑12% vs yesterday".** Both originally dropped as
 *   fabricated. The backend's 2026-08-29 contract adds exactly the endpoints needed —
 *   `GET /v1/trips?...&status=open` (shift-scoped active count) and
 *   `GET /v1/trips/earnings/today` (real day-over-day trend, Sydney-local calendar day) — see
 *   [HomeExtras]/[rememberHomeExtras]. Both render nothing until loaded, never a placeholder
 *   number.
 * - **"VERIFIED" badge.** Originally shown only as a weak "session exists" proxy (no real per-
 *   driver field was known to exist). The backend's contract confirms a real one:
 *   `UserDto.suitabilityStatus == "clear"` via `GET /v1/auth/me` — see [HomeExtras.verified].
 * - **Nav rail contents — REMOVED 2026-09-03.** This pass previously reconciled Figma's
 *   11-icon collapsed rail with its 13-label expanded flyout. Both are gone: see
 *   [CaptainMenuGrid]'s doc for why all three menu surfaces were deleted and what replaced them.
 *   Everything the reconciliation preserved is still preserved — Messages and Live Map both have
 *   their own tile, and "HELP & SUPPORT"/"NAVIGATE"/"MORE" are still absent because there is
 *   still no screen behind any of them.
 * - **SOS control.** Figma draws SOS as a plain circular tap target. This app's existing duress
 *   trigger is deliberately LONG-PRESS only — see [au.com.threesixty.cabdispatch.ui.deck.DeckNavRail]'s
 *   own doc: "so a knee-bump can't fire a silent alarm." Matching Figma's tap-to-fire literally
 *   would remove a real safety property for a visual detail, so [SosControl] below keeps the same
 *   press-and-hold gesture, styled to match Figma's red-ringed circle, with a short "HOLD" caption
 *   so the interaction is discoverable rather than silently different from how it looks.
 * - **Meter-start transition copy.** Figma's frame 3 shows "STARTING… T1 · CONNECTING", implying a
 *   physical meter unit pairing over some transport. No such hardware integration exists in this
 *   app (this is a software meter — [WheelDashboardViewModel.startMeter] is a local, synchronous
 *   state write, not a device pairing handshake). [MeterStartPhase.Starting] shows "STARTING
 *   METER…" instead — visually the same transitional moment Figma calls for, with copy this app
 *   can actually stand behind.
 *
 * ## 2026-08-29, second pass: legibility/prominence for an older driver population
 *
 * User-directed: "majority users are old age... make it full prominent." Deliberately larger type
 * and touch targets everywhere, sized for a driver reading this at arm's length, possibly older,
 * possibly with reduced fine-motor precision — at the cost of some of the reference's density.
 *
 * ## 2026-09-03: one menu, and a menu-only home
 *
 * User verdict on the three-menus-for-one-destination-set situation: "just remove the all menu…
 * then cleanly design the beautiful icons based menu". Delivered as:
 *
 * 1. **All three menu surfaces deleted** — the always-visible `CaptainNavRail`, its hamburger →
 *    `CaptainNavFlyout`, and the chevron edge-handle wired to the same toggle as the hamburger.
 *    Home itself is the menu now: see [CaptainMenuGrid].
 * 2. **The duplicated status card deleted.** `SystemStatusCard`'s GPS/NET/PRN cells were a second
 *    copy of [CaptainHeader]'s own GPS/network/printer dots, off the identical fields; its one
 *    unique cell (meter READY/WAIT) is now [MeterReadyChip] in the header.
 * 3. **`ShiftStatsBar` folded into the header** as [HeaderStatsStrip] — shift time, trips,
 *    earnings and the shift-limit ring are the only genuinely non-duplicated numbers the footer
 *    carried, and the user asked for them to stay glanceable. "Take break now" is the same real
 *    `setAvailable(false)` action it always was.
 * 4. **`MeterCard` and `LiveDispatchCard` came off home.** No flow was deleted with them: the
 *    Start Meter pre-check dialog is what the METER tile opens, Set Price kept its own tile, the
 *    Starting/CANCEL transition became [MeterStartingOverlay], and the offer/accept flow lives on
 *    the Dispatch pane — which the DISPATCH tile badges with the real live offer count so an
 *    incoming job is still impossible to miss from home.
 */
@Composable
fun DeckHomeScreen(
    navController: NavHostController,
    viewModel: WheelDashboardViewModel = viewModel(),
    dispatchViewModel: AvailableTripsWheelViewModel = viewModel(),
    // Phase A shell-integration (2026-09-03): [au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes.HIRED]'s
    // composable passes `true` here so a driver landing on this backstack entry via any of this
    // app's several "trip just started/accepted" hand-offs (Start Meter, Set Price, a dispatch
    // offer accepted from either the Dispatch wheel-content pane or the separate job-offer detail
    // screen) sees the Meter pane immediately — matching this file's own "one shell, swap embedded
    // content per rail item" pattern instead of Hired staying a separate full-screen takeover (see
    // this file's class doc, "Meter joins the shared shell"). Every existing
    // `navController.navigate(CabDispatchRoutes.HIRED)` call site keeps compiling and working
    // unchanged — none of them needed to change for this, since the route string itself didn't
    // move, only what it renders.
    startOnMeter: Boolean = false,
) {
    val state by viewModel.uiState.collectAsState()
    val dispatchState by dispatchViewModel.uiState.collectAsState()
    var pane by rememberSaveable { mutableStateOf(if (startOnMeter) CaptainPane.METER else CaptainPane.DASHBOARD) }
    // Real "is a fare actually open right now" signal (Phase A shell-integration) — TripEntity
    // stays status=OPEN from the moment [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel]
    // opens it through to [au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayViewModel.finalizeClose]
    // (see that class's own doc), so this is a real Room read, not a guess from [SessionHolder.pendingTrip]
    // (which — a separate, pre-existing gap this pass does not fix — never gets cleared once a trip
    // starts, so it would stay "truthy" long after a trip actually closes). Screen-local loader,
    // same convention as [HomeExtras] below. Decides what the METER tile does (open the
    // pre-check dialog vs. jump straight to the live fare) and drives its live pulse.
    val activeTrip by AppContainer.tripRepository.observeActiveTrip().collectAsState(initial = null)
    val hasActiveTrip = activeTrip != null
    var showSetPrice by rememberSaveable { mutableStateOf(false) }
    // Point to Point Transport (Fares) Order 2026 UI-wiring pass: the plain (non-Set-Price)
    // Start Meter tap now opens this small declaration step first — see TripDetailsDialog's own
    // doc for why (passenger count / maxi-taxi / wheelchair / airport-rank-maxi inputs).
    var showTripDetails by rememberSaveable { mutableStateOf(false) }
    // Passenger-facing driver identity (2026-08-29 premium pass): tapping the header avatar now
    // opens a large ID card (photo big enough to match a face against) instead of silently
    // jumping to Profile — the card itself carries the "View profile" path so nothing is lost.
    var showDriverId by rememberSaveable { mutableStateOf(false) }
    var meterPhase by remember { mutableStateOf<MeterStartPhase>(MeterStartPhase.Idle) }
    val scope = rememberCoroutineScopeCompat()

    val duressState by AppContainer.duressController.state.collectAsState()
    val homeExtras = rememberHomeExtras(driverId = state.session?.driverId, shiftId = state.session?.shiftId)
    // Real bug fixed (2026-09-02): the SET PRICE tile's "ACTIVE" subtitle used to be an
    // unconditional hardcoded literal regardless of whether a fixed fare was actually pending —
    // see MeterCard's own doc. SessionHolder.pendingTrip is the real signal.
    val pendingTrip by SessionHolder.pendingTrip.collectAsState()

    // Accepting a live dispatch offer hands off to S3 exactly like a driver-initiated Start Meter —
    // see AvailableTripsWheelViewModel.beginHiredHandoff's own doc. Same one-shot
    // navigateToHired -> onNavigatedToHired() ack shape CloseAndPayScreen's Done button uses.
    LaunchedEffect(dispatchState.navigateToHired) {
        if (dispatchState.navigateToHired) {
            navController.navigate(CabDispatchRoutes.HIRED)
            dispatchViewModel.onNavigatedToHired()
        }
    }

    fun onStartMeter(
        negotiatedTotal: String? = null,
        passengerCount: Int = 1,
        isMaxiVehicle: Boolean = false,
        wheelchairHiring: Boolean = false,
        airportRankRequestedMaxi: Boolean = false,
    ) {
        if (meterPhase != MeterStartPhase.Idle) return // guards a double-tap mid-transition
        if (!viewModel.startMeter(
                negotiatedTotal = negotiatedTotal,
                passengerCount = passengerCount,
                isMaxiVehicle = isMaxiVehicle,
                wheelchairHiring = wheelchairHiring,
                airportRankRequestedMaxi = airportRankRequestedMaxi,
            )
        ) {
            meterPhase = MeterStartPhase.Failed(
                if (state.tariff == null) "No signed tariff yet — try again shortly" else "No active session",
            )
            return
        }
        meterPhase = MeterStartPhase.Starting
        scope.launch {
            delay(METER_START_TRANSITION_MS) // real minimum dwell so the transition is visible, not a flash
            if (meterPhase == MeterStartPhase.Starting) {
                navController.navigate(CabDispatchRoutes.HIRED)
                // Real fix that this pass had to make (2026-09-03): the phase used to be left on
                // Starting forever after navigating. Harmless while MeterCard rendered it as a
                // dial label; NOT harmless now that Starting draws a blocking MeterStartingOverlay
                // — coming back to Home would have left the driver stuck behind a modal scrim.
                meterPhase = MeterStartPhase.Idle
            }
        }
    }

    fun onCancelStart() {
        if (meterPhase !is MeterStartPhase.Starting) return
        SessionHolder.clearPendingTrip()
        meterPhase = MeterStartPhase.Idle
    }

    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.bg)) {
    // Prominence pass (2026-09-02): two large, soft ambient glow washes behind the whole screen —
    // "lots of shades", not a single flat fill — positioned near the header and the nav rail so
    // the wash reads as ambient depth rather than a literal spotlight on one element. Plain Boxes
    // with no pointer input, so they never intercept touches from the real content drawn on top.
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = (-220).dp, y = (-260).dp)
            .size(760.dp)
            .background(Brush.radialGradient(listOf(CaptainPalette.glowPurpleSoft, Color.Transparent)), CircleShape),
    )
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 200.dp, y = 220.dp)
            .size(620.dp)
            .background(Brush.radialGradient(listOf(CaptainPalette.glowPurpleSoft, Color.Transparent)), CircleShape),
    )
    Column(modifier = Modifier.fillMaxSize()) {
        CaptainHeader(
            state = state,
            verified = homeExtras.verified,
            onShowDriverId = { showDriverId = true },
            onOpenProfile = { navController.navigate(CabDispatchRoutes.PROFILE) },
            onToggleAvailability = { viewModel.setAvailable(!state.isAvailable) },
            onSos = { AppContainer.duressController.trigger(state.session?.vehicleId, state.session?.driverId) },
        )
        // The deleted footer's genuinely-unique numbers, folded into the header as one compact
        // strip (the user's own call) — see HeaderStatsStrip's doc. Hidden on the Meter pane for
        // exactly the reason the footer was already hidden there: metering is a header-only focus
        // mode, and this strip is the footer's content, not the header's own.
        // Metering is header-only, which removed every route back to the menu: METER is the one
        // pane with no PaneShell back-arrow (leaving mid-fare must never read as "go back"), and
        // the rail is hidden. Without this, checking Messages or Dispatch mid-fare would mean
        // pressing END FARE — which closes the trip. This returns to the menu with the fare still
        // running; the METER tile (lit while a fare is live) comes straight back.
        if (pane == CaptainPane.METER) {
            MeteringMenuButton(onClick = { pane = CaptainPane.DASHBOARD })
        }
        if (pane != CaptainPane.METER) {
            HeaderStatsStrip(
                state = state,
                extras = homeExtras,
                hasActiveTrip = hasActiveTrip,
                // Honest local action, unchanged from the deleted ShiftLimitRing: no invented
                // return-time claim, just the real setAvailable(false) call.
                onTakeBreak = { viewModel.setAvailable(false) },
            )
        }
        // Real bug fixed (2026-09-02): setAvailable's failure path already produced
        // availabilityError, but nothing anywhere rendered it — a failed toggle silently reverted
        // with zero feedback to the driver about why. A small inline banner, not a dialog, so it
        // doesn't block the rest of the screen.
        // Also the one place a failed Start Meter now surfaces: MeterStartPhase.Failed used to
        // render inside MeterCard's dial, which no longer exists. Same inline, non-blocking
        // banner rather than a second error surface.
        val bannerMessage = state.availabilityError ?: (meterPhase as? MeterStartPhase.Failed)?.message
        AnimatedVisibility(
            visible = bannerMessage != null,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(140)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CaptainPalette.glowDangerSoft)
                    .padding(horizontal = 32.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = CaptainPalette.danger, modifier = Modifier.size(18.dp))
                Text(
                    bannerMessage ?: "",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = CaptainPalette.danger,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.panelBorder))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Symmetric now that the 232dp nav rail is gone — the old asymmetric
                // start=32/end=12 existed only to tuck the content up against that rail.
                .padding(horizontal = 24.dp, vertical = if (pane == CaptainPane.METER) 12.dp else 18.dp),
        ) {
            when (pane) {
                CaptainPane.DASHBOARD -> CaptainMenuGrid(
                    hasActiveTrip = hasActiveTrip,
                    liveOfferCount = dispatchState.cards.size,
                    negotiatedTotal = pendingTrip?.negotiatedTotal,
                    onSelectPane = { pane = it },
                    onStartMeterPrecheck = { showTripDetails = true },
                    onSetPrice = { showSetPrice = true },
                    onOpenProfile = { navController.navigate(CabDispatchRoutes.PROFILE) },
                    onOpenSettings = { navController.navigate(CabDispatchRoutes.SETTINGS) },
                    onLogOff = { navController.navigate(CabDispatchRoutes.LOG_OFF) },
                    modifier = Modifier.fillMaxSize(),
                )
                CaptainPane.DISPATCH -> PaneShell("Live dispatch", onBack = { pane = CaptainPane.DASHBOARD }) {
                    AvailableTripsWheelContent(navController = navController)
                }
                CaptainPane.TRIPS -> PaneShell("Trip history", onBack = { pane = CaptainPane.DASHBOARD }) {
                    // variant = HISTORY (2026-09-03): the old rail's own flyout label
                    // ("TRIP HISTORY") already committed to this being the history
                    // table, not the MY_TRIPS default — this call was
                    // the one place still rendering the wrong variant, leaving the real
                    // history table (filters, pickup/dropoff/distance/duration/status
                    // columns) genuinely unreachable from the live app. See
                    // TripsWheelContent's own doc for what each variant shows.
                    TripsWheelContent(
                        variant = TripsPaneVariant.HISTORY,
                        onTripClick = { clientUuid ->
                            TripDetailHandoff.set(clientUuid)
                            navController.navigate(CabDispatchRoutes.TRIP_DETAIL)
                        },
                        onOpenActiveTrip = { navController.navigate(CabDispatchRoutes.CLOSE_PAY) },
                        onShiftReportClick = { pane = CaptainPane.SHIFT },
                    )
                }
                CaptainPane.EARNINGS -> PaneShell("Earnings — this shift", onBack = { pane = CaptainPane.DASHBOARD }) {
                    EarningsWheelContent()
                }
                CaptainPane.SHIFT -> PaneShell("Shift summary", onBack = { pane = CaptainPane.DASHBOARD }) {
                    ShiftWheelContent(
                        onSubmitted = { summary ->
                            ShiftSubmissionHandoff.set(summary)
                            navController.navigate(CabDispatchRoutes.SHIFT_SUBMITTED)
                        },
                    )
                }
                // Real tabbed Heat Map/Zone List/Surge Areas/Airport Queue screen (Phase F)
                // — replaces the old two-button "Plot into a zone"/"Zone statistics"
                // launcher (see ZonesPaneContent's own class doc for why those two
                // standalone routes are kept, unchanged, rather than deleted).
                CaptainPane.ZONES -> PaneShell("Zones", onBack = { pane = CaptainPane.DASHBOARD }) {
                    ZonesPaneContent()
                }
                // Real, standalone, view-only tariff display (Phase E) — replaces the old
                // mislabelled alias where PRICING silently opened the Set Price dialog
                // (see PricingPaneContent's class doc for why).
                CaptainPane.PRICING -> PaneShell("Pricing", onBack = { pane = CaptainPane.DASHBOARD }) {
                    PricingPaneContent()
                }
                // Real Available/Used/Expired voucher-ledger browse screen (Phase G) —
                // replaces the old mislabelled alias where VOUCHERS silently opened
                // VoucherInfoDialog (see VouchersPaneContent's class doc for why). That
                // dialog is now deleted outright: its only remaining caller was the
                // MeterCard VOUCHERS quick-action, which went away with MeterCard, and
                // leaving a second, weaker "what is a voucher" surface behind this real
                // ledger screen would have been exactly the duplication this pass removes.
                CaptainPane.VOUCHERS -> PaneShell("Vouchers", onBack = { pane = CaptainPane.DASHBOARD }) {
                    VouchersPaneContent()
                }
                CaptainPane.MESSAGES -> PaneShell("Messages", onBack = { pane = CaptainPane.DASHBOARD }) {
                    MessagesWheelContent(onOpenThread = { navController.navigate(CabDispatchRoutes.MESSAGES_THREAD) })
                }
                CaptainPane.MAP -> PaneShell("Live map", onBack = { pane = CaptainPane.DASHBOARD }) {
                    StatusMapPanel(onPlotZone = { pane = CaptainPane.ZONES })
                }
                // No PaneShell wrapper here deliberately (unlike every pane above): PaneShell's
                // back-arrow reads as "leave this screen", which for an in-progress, revenue-
                // accruing fare is the wrong affordance to offer. Metering is a header-only
                // focus mode: the footer/stats strip is hidden here (see HeaderStatsStrip's
                // call site above) and the driver leaves via the meter's own END FARE, never
                // a literal "back". See HiredScreen's own doc for the rest of this pane.
                CaptainPane.METER -> HiredScreen(navController = navController)
            }
        }
    }

    if (showSetPrice) {
        SetPriceDialogV2(
            onDismiss = { showSetPrice = false },
            onConfirm = { total ->
                showSetPrice = false
                onStartMeter(negotiatedTotal = total)
            },
        )
    }
    if (showTripDetails) {
        TripDetailsDialog(
            initialMaxiVehicle = AppContainer.maxiVehicleStore.isMaxiVehicle(),
            onDismiss = { showTripDetails = false },
            onConfirm = { passengerCount, isMaxiVehicle, wheelchairHiring, airportRankRequestedMaxi ->
                // Persist the maxi-vehicle declaration back to the shared per-device store so it's
                // remembered for next time (and shown consistently in Settings → Fare schedule) —
                // see MaxiVehicleStore's own doc.
                AppContainer.maxiVehicleStore.setMaxiVehicle(isMaxiVehicle)
                showTripDetails = false
                onStartMeter(
                    passengerCount = passengerCount,
                    isMaxiVehicle = isMaxiVehicle,
                    wheelchairHiring = wheelchairHiring,
                    airportRankRequestedMaxi = airportRankRequestedMaxi,
                )
            },
        )
    }
    if (showDriverId) {
        DriverIdCard(
            session = state.session,
            verified = homeExtras.verified,
            onOpenProfile = {
                showDriverId = false
                navController.navigate(CabDispatchRoutes.PROFILE)
            },
            onDismiss = { showDriverId = false },
        )
    }
    when (val d = duressState) {
        is DuressUiState.Triggered -> DuressTriggeredOverlay(
            secondsRemaining = d.secondsRemaining,
            onCancel = AppContainer.duressController::cancel,
        )
        is DuressUiState.Active -> DuressActiveBanner()
        DuressUiState.Idle -> Unit
    }

    // The Start Meter transition, which used to live inside the (now-deleted) MeterCard dial.
    // Declared last so it sits above the menu grid; the duress overlays above it still win
    // z-order-wise because they are declared before it only in source order — a triggered duress
    // is a full-screen takeover of its own, and a metered start cannot be in flight at the same
    // time (the tap that starts one navigates away within METER_START_TRANSITION_MS).
    if (meterPhase is MeterStartPhase.Starting) {
        MeterStartingOverlay(onCancel = ::onCancelStart)
    }
    }
}

/** How long [MeterStartPhase.Starting] stays on screen before navigating — a real minimum dwell
 * time (not simulated hardware latency, see this file's class doc) so the transition Figma's frame
 * 3 asks for is actually visible rather than an instant flash between OFF and the Hired screen. */
private const val METER_START_TRANSITION_MS = 900L

/** [DeckHomeScreen]'s local Start Meter state machine — see [DeckHomeScreen.onStartMeter]. */
private sealed interface MeterStartPhase {
    data object Idle : MeterStartPhase
    data object Starting : MeterStartPhase
    data class Failed(val message: String) : MeterStartPhase
}

/** The rail's fixed destinations (`01 · HOME — Collapsed Rail` / `02 · HOME — Expanded Menu`) —
 * see this file's class doc for exactly which Figma items are aliased, dropped, or added and why. */
private enum class CaptainPane { DASHBOARD, DISPATCH, TRIPS, EARNINGS, SHIFT, ZONES, PRICING, VOUCHERS, MESSAGES, MAP, METER }

/** `rememberCoroutineScope()`, spelled out under a distinct name only so this file's own
 * [kotlinx.coroutines.launch] call above reads unambiguously next to the unrelated
 * [androidx.compose.runtime.remember] calls surrounding it — no different behaviour. */
@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()

/**
 * Real fields the backend's 2026-08-29 contract confirmed exist but [WheelDashboardViewModel]
 * doesn't carry — fetched once per (driverId, shiftId) here rather than added to that ViewModel,
 * matching this codebase's existing "screen-local loader" convention
 * ([au.com.threesixty.cabdispatch.ui.screens.profile.ProfileScreen]'s compliance-expiry cards,
 * this same file's [DriverAvatar]). All three fields are additive polish on top of an already-
 * functional screen — a failed/slow fetch degrades to `null`/hidden, never blocks or fakes a
 * value. See [rememberHomeExtras] for exactly which calls back each field.
 */
private data class HomeExtras(
    /** `UserDto.suitabilityStatus == "clear"`, or `null` while `/v1/auth/me` hasn't answered yet
     * (or has no driver signed in). Never `true` by default. */
    val verified: Boolean? = null,
    /** `DriverEarningsTodayReadDto.pctChange` — `null` means either not loaded yet or the backend
     * itself had no yesterday baseline; both render the same "hide the comparison" way. */
    val earningsPctChange: Double? = null,
    /** Real open-trip count for the CURRENT shift (`GET /v1/trips?...&status=open`), replacing
     * the Figma mock's fabricated "3 Active". `null` = not loaded yet or no open shift. */
    val tripsActiveThisShift: Int? = null,
    /** Total row count from `GET /v1/fatigue-alerts` (2026-09-02) — a real, already-defined
     * backend endpoint nothing in this app called until now. Backs the "NEXT BREAK"/shift-limit
     * card's honest fatigue-awareness line; see [ShiftLimitRing]'s own doc for why this app shows
     * shift-limit-remaining + real fatigue-alert count rather than a fabricated break schedule.
     * `null` = not loaded yet. */
    val fatigueAlertCount: Int? = null,
    /** `kind` of the most recently triggered fatigue alert (by `triggeredAt`), or `null` if there
     * are none / not loaded yet. */
    val latestFatigueKind: String? = null,
)

@Composable
private fun rememberHomeExtras(driverId: String?, shiftId: String?): HomeExtras {
    var extras by remember { mutableStateOf(HomeExtras()) }
    LaunchedEffect(driverId, shiftId) {
        extras = HomeExtras() // a driver/shift change invalidates every field until re-fetched
        val id = driverId ?: return@LaunchedEffect
        launch {
            val verified = runCatching { AppContainer.apiService.me() }.getOrNull()
                ?.suitabilityStatus?.equals("clear", ignoreCase = true)
            extras = extras.copy(verified = verified)
        }
        launch {
            val pctChange = runCatching { AppContainer.apiService.earningsToday(id) }.getOrNull()?.pctChange
            extras = extras.copy(earningsPctChange = pctChange)
        }
        if (shiftId != null) {
            launch {
                val active = runCatching {
                    AppContainer.apiService.listTrips(driverId = id, shiftId = shiftId, status = "open", limit = 1)
                }.getOrNull()?.total
                extras = extras.copy(tripsActiveThisShift = active)
            }
        }
        // Real signal, finally consumed (2026-09-02): GET /v1/fatigue-alerts was already fully
        // defined server-side (FatigueAlertDto/FatigueAlertPageDto in ApiService.kt) but nothing in
        // this app called it before this pass — see ShiftLimitRing's own doc.
        launch {
            val page = runCatching { AppContainer.apiService.fatigueAlerts(limit = 5) }.getOrNull()
            if (page != null) {
                val latestKind = page.items.maxByOrNull { it.triggeredAt }?.kind
                extras = extras.copy(fatigueAlertCount = page.total, latestFatigueKind = latestKind)
            }
        }
    }
    return extras
}

// ============================================================================================
// Header (Figma `01·HOME` — avatar/wordmark, driver identity, VERIFIED, AVAILABLE, status, SOS)
// ============================================================================================

@Composable
private fun CaptainHeader(
    state: WheelDashboardUiState,
    verified: Boolean?,
    onShowDriverId: () -> Unit,
    onOpenProfile: () -> Unit,
    onToggleAvailability: () -> Unit,
    onSos: () -> Unit,
) {
    // Prominence pass (2026-08-29): every size below grew from its Figma-matched original — see
    // this file's class doc, "legibility/prominence for an older driver population". Header
    // vertical padding 20dp -> 26dp to give the now-larger avatar/text room to breathe.
    //
    // 2026-09-02 visual pass: a soft top-down purple wash behind the whole header bar, one of the
    // "lots of shades/colours, prominent" gradients this pass adds throughout — the header reads
    // as a genuinely lit HUD strip rather than a flat bar sitting on the page background.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(CaptainPalette.glowPurpleSoft, Color.Transparent)))
            .padding(horizontal = 32.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 88dp -> 68dp and the row's vertical padding 26dp -> 12dp (2026-09-03): this device is
        // 960x600 dp, and the header now carries a second row (HeaderStatsStrip) that the deleted
        // footer used to hold. Measured, not guessed: at the old sizes the header + strip alone
        // ate 214dp of 600, leaving the menu grid's three rows under 100dp each. The avatar is
        // still a comfortably large target and still opens the full-size Driver ID card.
        DriverAvatar(driverId = state.session?.driverId, driverName = state.session?.driverName, onClick = onShowDriverId, sizeDp = 68)
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text("CAPTAIN", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = CaptainPalette.textPrimary)
            Text("TAXIS", fontFamily = InterFamily, fontSize = 12.sp, letterSpacing = 1.sp, color = CaptainPalette.textSecondary)
        }
        Column(
            modifier = Modifier.padding(start = 26.dp).clickable(onClick = onOpenProfile),
        ) {
            Text(
                state.session?.driverName ?: "No driver",
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                color = CaptainPalette.textPrimary,
            )
            Text(
                // The mockup shows "rego · make/model" (e.g. "CAP-5517 · Toyota Camry Hybrid") —
                // no vehicle make/model field exists anywhere in this app's data model (VehicleDto
                // is id+rego only, confirmed against ApiService.kt) or the backend it talks to, so
                // this shows the real rego alone rather than fabricating a plausible-looking
                // "Toyota Camry Hybrid". Flagged as a backend-requirements candidate in
                // DASHBOARD_REDESIGN_2026.md. Driver-id is still real and shown elsewhere
                // (DriverIdCard's "DRIVER # …") rather than crowding this line.
                state.session?.vehicleId ?: "—",
                fontFamily = RobotoMonoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = CaptainPalette.textSecondary,
            )
        }
        // "VERIFIED" — a real backend field, per the backend's own contract (2026-08-29): maps
        // to UserDto.suitabilityStatus == "clear" (fetched via GET /v1/auth/me, see
        // rememberHomeExtras below), not merely "a session exists". `null` (still loading, or the
        // field came back something other than "clear") shows nothing — never a false claim.
        if (verified == true) {
            Box(
                modifier = Modifier.padding(start = 18.dp).clip(RoundedCornerShape(16.dp))
                    .background(CaptainPalette.primary).padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text("VERIFIED", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = CaptainPalette.textPrimary)
            }
        }
        // Availability pill — real toggle (WheelDashboardViewModel.setAvailable), tap-to-flip.
        // Figma's frames show only the AVAILABLE state; the OFF-DUTY visual below is this pass's
        // own extrapolation of the same pill for the real boolean's other value.
        Box(
            modifier = Modifier.padding(start = 18.dp).clip(RoundedCornerShape(20.dp))
                .background(CaptainPalette.panel)
                .border(1.5.dp, if (state.isAvailable) CaptainPalette.success.copy(alpha = 0.5f) else CaptainPalette.panelBorder, RoundedCornerShape(20.dp))
                .gameClick(
                    onClick = onToggleAvailability,
                    shape = RoundedCornerShape(20.dp),
                    glowColor = if (state.isAvailable) CaptainPalette.success else CaptainPalette.accent,
                )
                .padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(color = if (state.isAvailable) CaptainPalette.success else CaptainPalette.textMuted, animated = state.isAvailable, size = 14.dp)
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        if (state.isAvailable) "AVAILABLE" else "OFF DUTY",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp,
                        color = if (state.isAvailable) CaptainPalette.success else CaptainPalette.textSecondary,
                    )
                    Text(
                        if (state.isAvailable) "Ready to receive jobs" else "Tap to go available",
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        color = CaptainPalette.textSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // Real GPS/network/printer/battery — same DashboardStatusStrip WheelDashboardViewModel
        // already polls every 4s for the old status strip, just restyled inline here.
        // GPS now reflects a real fix-quality tier (GpsQualityClassifier), not just "permission
        // granted" — see WheelDashboardViewModel.pollStatus's own doc. Network label is the real
        // transport type (DeviceTelemetry.readNetworkType — "wifi"/"4g"/"offline"), not a
        // hardcoded "4G" string, and deliberately carries no signal-strength adjective ("STRONG")
        // since no TelephonyManager/SignalStrength reading exists anywhere in this app to back one.
        StatusDot(Icons.Rounded.LocationOn, "GPS", state.status.gpsOk)
        StatusDot(Icons.Rounded.SignalCellularAlt, networkStatusLabel(state.status.networkType), state.status.networkOk, spacingStart = 24.dp)
        StatusDot(Icons.Rounded.Print, "PRINTER", state.status.printerOk, spacingStart = 24.dp)
        Row(modifier = Modifier.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.BatteryFull,
                contentDescription = null,
                tint = if (state.status.batteryOk) CaptainPalette.textPrimary else CaptainPalette.danger,
                modifier = Modifier.size(22.dp),
            )
            Text(
                state.status.batteryPercent?.let { "$it%" } ?: "—",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = if (state.status.batteryOk) CaptainPalette.textPrimary else CaptainPalette.danger,
                modifier = Modifier.padding(start = 5.dp),
            )
        }
        SosControl(onTrigger = onSos, modifier = Modifier.padding(start = 26.dp))
    }
}

/**
 * Loads the signed-in driver's real uploaded photo (`GET /v1/users/{userId}/photo`) — same
 * endpoint and Bitmap-decode approach [au.com.threesixty.cabdispatch.ui.screens.profile.ProfileViewModel.loadPhoto]
 * already uses, reimplemented here as a screen-local loader rather than by editing that
 * ViewModel — same "screen-local loader, ViewModel is off-limits" convention
 * [au.com.threesixty.cabdispatch.ui.screens.profile.ProfileScreen] already uses for its own
 * compliance-expiry cards. Falls back to the driver's initials (this pass's previous, still-real
 * placeholder) on no-photo/offline/error — never a stock/generic image standing in for a real
 * person. [driverId] as the `remember` key: a factory-reset/re-login mid-process must not show a
 * stale photo for a different driver.
 */
/** Real transport-type label ("wifi"/"4g"/"offline" from [au.com.threesixty.cabdispatch.domain.DeviceTelemetry.readNetworkType])
 * rendered for a driver, not the raw lowercase wire value — deliberately no signal-strength
 * adjective ("STRONG"/"WEAK"), see [CaptainHeader]'s own comment for why. */
private fun networkStatusLabel(networkType: String?): String = when (networkType) {
    "wifi" -> "WI-FI"
    "4g" -> "4G"
    "offline" -> "OFFLINE"
    else -> "NETWORK"
}

/** Same real signal as [networkStatusLabel], shortened for [SystemStatusCell]'s narrow fixed
 * width — "OFFLINE"/"NETWORK" would wrap or clip there. */
private fun networkStatusLabelCompact(networkType: String?): String = when (networkType) {
    "wifi" -> "WIFI"
    "4g" -> "4G"
    "offline" -> "NONE"
    else -> "—"
}

@Composable
private fun StatusDot(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, ok: Boolean, spacingStart: androidx.compose.ui.unit.Dp = 0.dp) {
    Row(modifier = Modifier.padding(start = spacingStart), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = CaptainPalette.textPrimary, modifier = Modifier.size(20.dp))
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = CaptainPalette.textPrimary, modifier = Modifier.padding(start = 5.dp))
        Box(
            modifier = Modifier.padding(start = 7.dp).size(10.dp).clip(CircleShape)
                .background(if (ok) CaptainPalette.success else CaptainPalette.danger),
        )
    }
}

// ============================================================================================
// Home IS the menu (2026-09-03) — one icon-tile grid, and nothing else
// ============================================================================================

/**
 * The one and only menu in this app now. Replaces THREE surfaces that all reached the same
 * destinations and all had to be deleted together (user verdict: "just remove the all menu…
 * then cleanly design the beautiful icons based menu"):
 *
 * 1. `CaptainNavRail` — an always-visible 232dp right rail of 11 numbered icon+label rows, eating
 *    a quarter of a 960dp-wide tablet on every single pane.
 * 2. The hamburger at the rail's top → `CaptainNavFlyout`, a 280dp panel listing the *same*
 *    destinations again under different labels ("TRIPS"/"TRIP HISTORY", "DISPATCH"/"AVAILABLE
 *    TRIPS", …).
 * 3. A 44dp chevron half-off the rail's edge wired to the *same* `onToggleMenu` as the hamburger
 *    — its own comment admitted it was "the reference's second, always-visible affordance for the
 *    same expand action the hamburger performs above".
 *
 * The navigation model that replaces all three: **home is the menu**. Every other destination
 * already wraps in [PaneShell], whose back arrow returns to [CaptainPane.DASHBOARD] — so there is
 * exactly one way in (a tile) and exactly one way out (the back arrow), and no persistent rail is
 * needed anywhere. Every pane the rail and the flyout could reach has a tile here, including the
 * two the numbered rail deliberately left out (Messages, Live Map) and the three routed screens
 * that only ever lived in the flyout (Profile, Settings, Log off) — nothing became unreachable.
 *
 * Sizing: this runs on an SM-T575 at 1920x1200 px / density 320 = **960 x 600 dp**, so the grid is
 * three weight(1f) rows inside the content area rather than a scrolling list — every tile is on
 * screen at once, ~123dp tall and ~170dp wide, which is a far bigger target than the ~64dp rail
 * rows it replaces (this is a mounted tablet, read at arm's length, often by an older driver).
 * Icon + one word, no numbered prefixes, no duplicate entries.
 */
private class MenuTileSpec(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    /** Icon-chip / border / glow tint. */
    val tint: Color = CaptainPalette.accent,
    /** A standing primary destination — glows softly even when idle. Purely a hierarchy cue. */
    val primary: Boolean = false,
    /** Something is genuinely happening on this destination RIGHT NOW — pulses the glow. Only
     * ever set from real state (an open fare, a live offer), never a decorative loop. */
    val live: Boolean = false,
    /** Real count for the corner badge, or `null` for no badge. Never a placeholder number. */
    val badge: Int? = null,
    /** Real one-line sub-state, or `null`. Never filler copy. */
    val note: String? = null,
    val onClick: () -> Unit,
)

private const val MENU_COLUMNS = 5

@Composable
private fun CaptainMenuGrid(
    hasActiveTrip: Boolean,
    /** `dispatchState.cards.size` — the live offer list [au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelViewModel]
     * already keeps warm on this screen (initial fetch + its live subscription). Same list the
     * Dispatch pane itself renders, so the badge can never disagree with what a tap opens. */
    liveOfferCount: Int,
    /** `SessionHolder.pendingTrip.value?.negotiatedTotal` — non-null only when the driver really
     * did set a fixed price for the trip about to start. */
    negotiatedTotal: String?,
    onSelectPane: (CaptainPane) -> Unit,
    onStartMeterPrecheck: () -> Unit,
    onSetPrice: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tiles = listOf(
        // METER, per the user's explicit decision: no open fare -> the existing passenger-count /
        // maxi pre-check dialog (the flow the deleted MeterCard's START METER button used to
        // launch); an open fare -> straight to the live meter. `hasActiveTrip` is the same real
        // Room read (TripEntity status=OPEN) the rail's METER row already used.
        MenuTileSpec(
            icon = Icons.Rounded.DirectionsCar,
            label = "METER",
            tint = if (hasActiveTrip) CaptainPalette.success else CaptainPalette.accent,
            primary = true,
            live = hasActiveTrip,
            onClick = { if (hasActiveTrip) onSelectPane(CaptainPane.METER) else onStartMeterPrecheck() },
        ),
        // The fixed-fare flow the deleted MeterCard's SET PRICE quick-action owned. It is a real,
        // working, pre-existing feature (SetPriceDialogV2 -> startMeter(negotiatedTotal=…)), so it
        // gets its own tile rather than becoming unreachable when its host card went away.
        MenuTileSpec(
            icon = Icons.Rounded.Sell,
            label = "SET PRICE",
            tint = if (negotiatedTotal != null) CaptainPalette.success else CaptainPalette.accent,
            note = negotiatedTotal?.let { "$$it fixed" },
            onClick = onSetPrice,
        ),
        // DISPATCH carries a REAL badge + pulse. Deleting LiveDispatchCard from home would
        // otherwise have made an incoming job offer invisible from the only screen a waiting
        // driver actually sits on — a genuine operational regression, not a cosmetic one.
        MenuTileSpec(
            icon = Icons.Rounded.SwapHoriz,
            label = "DISPATCH",
            tint = if (liveOfferCount > 0) CaptainPalette.warning else CaptainPalette.accent,
            live = liveOfferCount > 0,
            badge = liveOfferCount.takeIf { it > 0 },
            note = when {
                liveOfferCount <= 0 -> null
                liveOfferCount == 1 -> "1 offer waiting"
                else -> "$liveOfferCount offers waiting"
            },
            onClick = { onSelectPane(CaptainPane.DISPATCH) },
        ),
        MenuTileSpec(Icons.Rounded.Receipt, "TRIPS", onClick = { onSelectPane(CaptainPane.TRIPS) }),
        MenuTileSpec(Icons.Rounded.SsidChart, "EARNINGS", onClick = { onSelectPane(CaptainPane.EARNINGS) }),
        MenuTileSpec(Icons.Rounded.LocationOn, "ZONES", onClick = { onSelectPane(CaptainPane.ZONES) }),
        MenuTileSpec(Icons.Rounded.Map, "MAP", onClick = { onSelectPane(CaptainPane.MAP) }),
        MenuTileSpec(Icons.Rounded.Mail, "MESSAGES", onClick = { onSelectPane(CaptainPane.MESSAGES) }),
        MenuTileSpec(Icons.Rounded.History, "SHIFT", onClick = { onSelectPane(CaptainPane.SHIFT) }),
        // "TARIFF", not "PRICING": the rail called this PRICING and the fixed-fare dialog SET
        // PRICE, two near-identical words for two entirely different things. This is the
        // view-only signed fare structure (PricingPaneContent).
        MenuTileSpec(Icons.Rounded.LocalOffer, "TARIFF", onClick = { onSelectPane(CaptainPane.PRICING) }),
        MenuTileSpec(Icons.Rounded.ConfirmationNumber, "VOUCHERS", onClick = { onSelectPane(CaptainPane.VOUCHERS) }),
        MenuTileSpec(Icons.Rounded.Person, "PROFILE", onClick = onOpenProfile),
        MenuTileSpec(Icons.Rounded.SettingsSuggest, "SETTINGS", onClick = onOpenSettings),
        MenuTileSpec(Icons.AutoMirrored.Rounded.Logout, "LOG OFF", tint = CaptainPalette.danger, onClick = onLogOff),
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        tiles.chunked(MENU_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                row.forEach { spec -> MenuTile(spec = spec, modifier = Modifier.weight(1f).fillMaxHeight()) }
                // Keeps the short final row's tiles exactly the same size as every other row's
                // rather than stretching them to fill the width.
                repeat(MENU_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * One menu tile — built entirely from this app's shared vocabulary ([CaptainPanel] surface,
 * [gameClick] press spring, [au.com.threesixty.cabdispatch.ui.theme.neonGlow] for emphasis,
 * [rememberInfiniteFloat]/[PulsingDot] for genuinely-live state) rather than a fourth hand-rolled
 * card style. The glow is state-driven, never ambient decoration: `0f` for an ordinary
 * destination, a soft constant for a standing primary one, a pulse only while
 * [MenuTileSpec.live] is backed by something real.
 */
@Composable
private fun MenuTile(spec: MenuTileSpec, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    val pulse by rememberInfiniteFloat(enabled = spec.live, from = 0.35f, to = 1f, durationMs = 1300)
    val glow = when {
        spec.live -> pulse
        spec.primary -> 0.5f
        else -> 0f
    }
    Box(modifier = modifier) {
        CaptainPanel(
            modifier = Modifier
                .fillMaxSize()
                // BEFORE the panel's own clip/background (see neonGlow's doc) so the glow lands
                // outside the tile instead of being clipped away inside it.
                .neonGlow(spec.tint, 20.dp, strength = glow, spread = 6.dp)
                .gameClick(onClick = spec.onClick, shape = shape, glowColor = spec.tint),
            cornerRadiusDp = 20,
            raised = spec.primary || spec.live,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Brush.verticalGradient(listOf(CaptainPalette.raised, CaptainPalette.cardBottom)))
                        .border(1.dp, spec.tint.copy(alpha = if (glow > 0f) 0.7f else 0.28f), RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(spec.icon, contentDescription = null, tint = spec.tint, modifier = Modifier.size(26.dp))
                }
                Text(
                    spec.label,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    letterSpacing = 0.4.sp,
                    color = CaptainPalette.textPrimary,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 9.dp),
                )
                spec.note?.let { note ->
                    Text(
                        note,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = spec.tint,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
        // Real count only — this Row does not render at all when there is nothing waiting.
        spec.badge?.takeIf { it > 0 }?.let { count ->
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .clip(CircleShape)
                    .background(CaptainPalette.danger)
                    .border(1.5.dp, CaptainPalette.danger.copy(alpha = pulse), CircleShape)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulsingDot(color = CaptainPalette.textPrimary, animated = true, size = 7.dp)
                Text(
                    count.toString(),
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = CaptainPalette.textPrimary,
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
        }
    }
}

/**
 * The blocking moment between a real Start Meter tap and the Hired route opening — the one piece
 * of the deleted `MeterCard` that had to survive as its own surface, because that card was where
 * [MeterStartPhase.Starting] used to be visible AND where its CANCEL escape hatch lived. Losing
 * the cancel would have been a real regression (it calls [SessionHolder.clearPendingTrip], the
 * only way to abandon a fixed-fare declaration mid-transition).
 */
@Composable
private fun MeterStartingOverlay(onCancel: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
                .border(1.dp, CaptainPalette.accent.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val pulse by rememberInfiniteFloat(enabled = true, from = 0.85f, to = 1.1f, durationMs = 900)
            Icon(
                Icons.Rounded.DirectionsCar,
                contentDescription = null,
                tint = CaptainPalette.accent,
                modifier = Modifier.size(48.dp).scale(pulse),
            )
            Text("STARTING METER…", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = CaptainPalette.textPrimary)
            Text(
                "Opening the fare.",
                fontFamily = InterFamily,
                fontSize = 16.sp,
                color = CaptainPalette.textSecondary,
                textAlign = TextAlign.Center,
            )
            CaptainButton(text = "CANCEL", outline = true, heightDp = 68, modifier = Modifier.fillMaxWidth(), onClick = onCancel)
        }
    }
}

// ============================================================================================
// Header stats strip — the only genuinely non-duplicated numbers the deleted footer carried
// ============================================================================================

/**
 * Shift time / trips / earnings / shift-limit countdown, folded into the top header as one
 * compact strip (the user's own call), plus the single meter READY/WAIT cell that was the only
 * non-duplicated thing in the deleted `SystemStatusCard`.
 *
 * What this pass deleted rather than moved, and why:
 * - `SystemStatusCard`'s GPS / NET / PRN cells were a literal second copy of the GPS, network and
 *   printer dots [CaptainHeader] draws a few dp above them, off the same
 *   `WheelDashboardUiState.status` fields. One home each; the header's row keeps them.
 * - `MeterCard` and `LiveDispatchCard` came off home entirely — home is the menu now. Neither
 *   flow was deleted: the meter pre-check dialog is what the METER tile opens, Set Price has its
 *   own tile, and the offer/accept flow lives on the Dispatch pane the DISPATCH tile opens.
 *
 * Every value below is the same real source the footer read: [ShiftDurationLimit.remaining] and
 * `session.shiftStartAt` for the clock/ring, `todayStats` for trips + earnings,
 * [HomeExtras.tripsActiveThisShift] / [HomeExtras.earningsPctChange] / [HomeExtras.fatigueAlertCount]
 * for the three backend-fetched extras (each renders nothing at all until it has really loaded),
 * and `state.tariff != null` for meter readiness. "Take break now" is unchanged — the same real
 * `setAvailable(false)` call, claiming no return time this app doesn't know.
 */
/**
 * "Back to menu" affordance shown only while a fare is accruing.
 *
 * Metering deliberately hides the footer and the nav rail (the user's "header only" call), and the
 * Meter pane deliberately has no [PaneShell] back-arrow — a back-arrow on a revenue-accruing fare
 * reads as "abandon this trip". Those two together left the driver with no way to reach Messages,
 * Dispatch or Zones mid-fare except END FARE, which actually closes the trip and moves to Close &
 * Pay. This is the escape hatch: it changes pane only. The fare, the trip, the duress state and
 * every subscription keep running untouched, and the lit METER tile returns here.
 */
@Composable
private fun MeteringMenuButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(99.dp))
                .background(CaptainPalette.panel)
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(99.dp))
                .gameClick(onClick = onClick, shape = RoundedCornerShape(99.dp), glowColor = CaptainPalette.accent)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Apps,
                contentDescription = null,
                tint = CaptainPalette.textSecondary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "MENU",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.padding(start = 8.dp),
            )
            Text(
                "· fare keeps running",
                fontFamily = InterFamily,
                fontSize = 11.sp,
                color = CaptainPalette.textMuted,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun HeaderStatsStrip(
    state: WheelDashboardUiState,
    extras: HomeExtras,
    hasActiveTrip: Boolean,
    onTakeBreak: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remaining = ShiftDurationLimit.remaining(state.session?.shiftStartAt)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(74.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, CaptainPalette.glowPurpleSoft)))
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderStat(
            icon = Icons.Rounded.Schedule,
            label = "SHIFT",
            value = shiftElapsedLabel(state.session?.shiftStartAt) ?: "—",
            sub = state.session?.shiftStartAt?.let { "since ${formatClockTime(it)}" } ?: "no active shift",
        )
        HeaderStatDivider()
        HeaderStat(
            icon = Icons.Rounded.DirectionsCar,
            label = "TRIPS",
            value = state.todayStats.tripsCount.toString(),
            // Real shift-scoped open-trip count; silent (not "0 active") when there genuinely is
            // none, and silent while it hasn't loaded.
            sub = extras.tripsActiveThisShift?.takeIf { it > 0 }?.let { "today · $it active" } ?: "today",
            subColor = if ((extras.tripsActiveThisShift ?: 0) > 0) CaptainPalette.success else CaptainPalette.textSecondary,
        )
        HeaderStatDivider()
        HeaderStat(
            icon = Icons.Rounded.AttachMoney,
            label = "EARNINGS",
            value = "$" + state.todayStats.earningsTotal.setScale(0, RoundingMode.HALF_UP).toPlainString(),
            // Real day-over-day trend; `null` (not loaded, or no yesterday baseline server-side)
            // falls back to the plain "today" caption rather than a fabricated 0%.
            sub = extras.earningsPctChange
                ?.let { "${if (it >= 0) "↑" else "↓"} ${"%.0f".format(Locale.ENGLISH, kotlin.math.abs(it))}% vs yest." }
                ?: "today",
            subColor = extras.earningsPctChange?.let { if (it >= 0) CaptainPalette.success else CaptainPalette.danger }
                ?: CaptainPalette.textSecondary,
        )
        HeaderStatDivider()
        HeaderBreakRing(
            remaining = remaining,
            fatigueAlertCount = extras.fatigueAlertCount,
            onTakeBreak = onTakeBreak,
        )
        Spacer(Modifier.weight(1f))
        MeterReadyChip(ready = state.tariff != null, running = hasActiveTrip)
    }
}

@Composable
private fun HeaderStat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    sub: String,
    subColor: Color = CaptainPalette.textSecondary,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp, color = CaptainPalette.textMuted)
            Text(value, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 25.sp, color = CaptainPalette.textPrimary, maxLines = 1)
            Text(sub, fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = subColor, maxLines = 1)
        }
    }
}

@Composable
private fun HeaderStatDivider() {
    Box(modifier = Modifier.padding(horizontal = 20.dp).height(46.dp).width(1.dp).background(CaptainPalette.panelBorder))
}

/**
 * The footer's shift-limit ring, shrunk to header scale (104dp -> 46dp) with the same real inputs
 * and the same honest framing it always had: the countdown is [ShiftDurationLimit.remaining], a
 * real 12h fatigue-limit clock, NOT an invented break schedule (no break-tracking API exists —
 * see the deleted `ShiftLimitRing`'s original doc, preserved in git history). The fatigue-alert
 * count is the real `GET /v1/fatigue-alerts` total, shown only once loaded and non-zero, and
 * "Take break now" is the same real [onTakeBreak] -> `setAvailable(false)` action as before.
 */
@Composable
private fun HeaderBreakRing(
    remaining: Duration?,
    fatigueAlertCount: Int?,
    onTakeBreak: () -> Unit,
) {
    val targetFraction = remaining?.let { r ->
        (r.seconds.toDouble() / (ShiftDurationLimit.SHIFT_DURATION_LIMIT_HOURS * 3600.0)).toFloat().coerceIn(0f, 1f)
    } ?: 0f
    val fraction by animateFloatAsState(targetFraction, animationSpec = tween(600), label = "header-shift-limit-ring")
    val urgent = remaining != null && targetFraction < 0.15f
    val urgentPulse by rememberInfiniteFloat(enabled = urgent, from = 0.5f, to = 1f, durationMs = 900)
    val ringTint = if (urgent) CaptainPalette.danger else CaptainPalette.accent
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeW = 5.dp.toPx()
                drawArc(color = CaptainPalette.inset, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = Stroke(strokeW))
                drawArc(
                    color = ringTint.copy(alpha = if (urgent) urgentPulse else 1f),
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    style = Stroke(strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
            Icon(Icons.Rounded.Coffee, contentDescription = null, tint = CaptainPalette.textSecondary, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SHIFT LIMIT", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp, color = CaptainPalette.textMuted)
                if (fatigueAlertCount != null && fatigueAlertCount > 0) {
                    Text(
                        "  ⚠ $fatigueAlertCount",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = CaptainPalette.warning,
                    )
                }
            }
            Text(
                remaining?.let { "${formatDurationHm(it)} left" } ?: "—",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = if (urgent) CaptainPalette.danger else CaptainPalette.textPrimary,
                maxLines = 1,
            )
            Text(
                "☕ Take break now",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = CaptainPalette.accent,
                maxLines = 1,
                modifier = Modifier.clickable(onClick = onTakeBreak).padding(top = 2.dp),
            )
        }
    }
}

/** The deleted `SystemStatusCard`'s one non-duplicated cell (MTR READY/WAIT), rehomed here as a
 * small chip. `running` upgrades it to the live-fare state so the chip never reads a bland
 * "READY" while a fare is actually ticking — same real active-trip Room read the METER tile uses. */
@Composable
private fun MeterReadyChip(ready: Boolean, running: Boolean) {
    val (text, tint) = when {
        running -> "RUNNING" to CaptainPalette.success
        ready -> "READY" to CaptainPalette.success
        else -> "WAIT" to CaptainPalette.warning
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CaptainPalette.inset)
            .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot(color = tint, animated = running, size = 10.dp)
        Text("METER", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp, color = CaptainPalette.textMuted, modifier = Modifier.padding(start = 9.dp))
        Text(text, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = tint, modifier = Modifier.padding(start = 7.dp))
    }
}

// --- Shift-time formatting helpers (real session.shiftStartAt, no fabricated numbers) ---------

private fun shiftElapsedLabel(shiftStartAtIso: String?): String? {
    val start = shiftStartAtIso?.let { parseInstantOrOffset(it) } ?: return null
    val elapsed = Duration.between(start, Instant.now()).let { if (it.isNegative) Duration.ZERO else it }
    return formatDurationHm(elapsed)
}

private fun shiftEndsLabel(shiftStartAtIso: String?): String? {
    val start = shiftStartAtIso?.let { parseInstantOrOffset(it) } ?: return null
    val end = start.plusSeconds((ShiftDurationLimit.SHIFT_DURATION_LIMIT_HOURS * 3600.0).toLong())
    val zoned = end.atZone(java.time.ZoneId.systemDefault())
    val fmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    return "Shift limit at ${fmt.format(zoned)}"
}

private fun formatClockTime(iso: String): String {
    val instant = parseInstantOrOffset(iso) ?: return iso
    val fmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    return fmt.format(instant.atZone(java.time.ZoneId.systemDefault()))
}

private fun formatDurationHm(d: Duration): String {
    val abs = d.abs()
    val h = abs.toHours()
    val m = abs.minusHours(h).toMinutes()
    val sign = if (d.isNegative) "-" else ""
    return "$sign${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
}

private fun parseInstantOrOffset(iso: String): Instant? =
    runCatching { Instant.parse(iso) }.recoverCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()

// ============================================================================================
// Small shared pieces
// ============================================================================================

/**
 * Large passenger-facing driver ID card (2026-08-29 premium pass) — opened by tapping the header
 * avatar. Exists so a passenger can actually match the driver's face to the registered photo
 * (the 88dp header chip is a control, not an ID). Everything shown is real session/backend data:
 * the same [DriverAvatar] photo loader at 300dp, the session's driver name / driver # / vehicle,
 * and the same backend-verified [verified] flag the header badge uses — nothing fabricated.
 */
@Composable
private fun DriverIdCard(
    session: au.com.threesixty.cabdispatch.domain.DriverSession?,
    verified: Boolean?,
    onOpenProfile: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(CaptainPalette.panel)
                .border(1.5.dp, CaptainPalette.accent.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
                .clickable(enabled = false) {}
                .padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("DRIVER ID", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 4.sp, color = CaptainPalette.textMuted)
            DriverAvatar(driverId = session?.driverId, driverName = session?.driverName, onClick = onDismiss, sizeDp = 300)
            Text(
                session?.driverName ?: "No driver signed in",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                color = CaptainPalette.textPrimary,
            )
            Text(
                session?.let { "DRIVER # ${it.driverId.take(8).uppercase()} · VEHICLE ${it.vehicleId}" } ?: "—",
                fontFamily = RobotoMonoFamily,
                fontSize = 17.sp,
                color = CaptainPalette.textSecondary,
            )
            if (verified == true) {
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(CaptainPalette.primary)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text("VERIFIED DRIVER", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
                CaptainButton(text = "Close", outline = true, modifier = Modifier.weight(1f), onClick = onDismiss)
                CaptainButton(text = "View profile", modifier = Modifier.weight(1.3f), onClick = onOpenProfile)
            }
        }
    }
}

// ============================================================================================
// Live map pane (unchanged from the previous Command Deck layout — kept reachable via the
// flyout's "LIVE MAP" entry rather than deleted; see this file's class doc)
// ============================================================================================

@Composable
private fun StatusMapPanel(onPlotZone: () -> Unit) {
    val fix by AppContainer.speedSource.locationFix.collectAsState()
    // Show Map in Background (Settings -> Display, 2026-09-03 Settings two-pane pass) — the real
    // toggle behind this pane's Mapbox Static Images fetch (see
    // au.com.threesixty.cabdispatch.domain.SettingsPreferencesStore's own doc). Defaults true, so
    // a driver who never touches the setting sees exactly the same map this pane always rendered.
    val showMapInBackground by AppContainer.settingsPreferencesStore.showMapInBackground.collectAsState()
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val centerLat = fix?.lat ?: SydneyCbdFallback.LAT
    val centerLng = fix?.lng ?: SydneyCbdFallback.LNG

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1420))
            .onGloballyPositioned { sizePx = it.size },
    ) {
        if (!showMapInBackground) {
            MapHiddenPlaceholder()
        } else if (sizePx.width > 0 && sizePx.height > 0) {
            val mapUrl = remember(sizePx, centerLat, centerLng) {
                MapboxStaticImage.url(
                    centerLat = centerLat,
                    centerLng = centerLng,
                    zoom = SydneyCbdFallback.ZOOM,
                    widthPx = sizePx.width,
                    heightPx = sizePx.height,
                )
            }
            SubcomposeAsyncImage(
                model = mapUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                    else -> IllustrativeStreetGrid()
                }
            }
        } else {
            IllustrativeStreetGrid()
        }

        val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "halo").animateFloat(
            initialValue = 0.35f,
            targetValue = 0.0f,
            animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
            label = "halo-a",
        )
        Box(
            modifier = Modifier.offset(x = 360.dp, y = 300.dp).size(72.dp).clip(CircleShape)
                .background(CaptainPalette.accent.copy(alpha = pulse)),
        )
        Box(
            modifier = Modifier.offset(x = 374.dp, y = 314.dp).size(44.dp).clip(CircleShape)
                .background(CaptainPalette.accent).border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("🚕", fontSize = 20.sp)
        }
        Box(
            modifier = Modifier.padding(16.dp).clip(RoundedCornerShape(10.dp))
                .background(CaptainPalette.bg.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                if (fix != null) "🗺 Live position" else "🗺 Waiting for GPS fix…",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = CaptainPalette.textSecondary,
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CaptainPalette.bg.copy(alpha = 0.88f))
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(CaptainPalette.accent.copy(alpha = 0.14f))
                    .clickable(onClick = onPlotZone).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    "📍 Plot a zone — see live demand →",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = CaptainPalette.accent,
                )
            }
            Text("Heartbeat 30 s · GPS live", fontFamily = RobotoMonoFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = CaptainPalette.textMuted)
        }
    }
}

/** Renders instead of the real Mapbox imagery (or its street-grid fallback) when Settings ->
 * Display -> "Show Map in Background" is off — honest and static, never a stale/last-cached map
 * frame. Position pin/heartbeat chip/plot-zone bar above this Box are unaffected: Plot a Zone
 * stays reachable either way, this setting only governs the map imagery itself. */
@Composable
private fun MapHiddenPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Map, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(40.dp))
            Text(
                "Background map hidden",
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = CaptainPalette.textMuted,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                "Turn on \"Show Map in Background\" in Settings -> Display to bring it back.",
                fontFamily = InterFamily,
                fontSize = 13.sp,
                color = CaptainPalette.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun IllustrativeStreetGrid() {
    val street = Color(0xFF1C2940)
    val arterial = Color(0xFF243352)
    listOf(120, 260, 420, 580, 700).forEach { y -> Box(Modifier.offset(y = y.dp).fillMaxWidth().height(10.dp).background(street)) }
    listOf(140, 320, 520, 660).forEach { x -> Box(Modifier.offset(x = x.dp).fillMaxHeight().width(12.dp).background(street)) }
    Box(Modifier.offset(y = 340.dp).fillMaxWidth().height(18.dp).background(arterial))
    Box(Modifier.offset(x = 430.dp).fillMaxHeight().width(18.dp).background(arterial))
    SuburbLabel("SYDNEY CITY", 60.dp, 60.dp)
    SuburbLabel("REDFERN", 180.dp, 380.dp)
    SuburbLabel("AIRPORT", 560.dp, 600.dp)
    SuburbLabel("LAKEMBA", 80.dp, 620.dp)
}

@Composable
private fun SuburbLabel(text: String, x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp) {
    Text(text, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp, color = Color(0xFF33445F), modifier = Modifier.offset(x = x, y = y))
}

// ============================================================================================
// Set Price (fixed fare) — unchanged flow (WheelDashboardViewModel.startMeter(negotiatedTotal=)),
// restyled to CaptainPalette. Not one of the 3 given Figma frames; kept because it is real,
// working, pre-existing functionality this pass must not remove.
// ============================================================================================

@Composable
private fun SetPriceDialogV2(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var amount by rememberSaveable { mutableStateOf("") }
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(CaptainPalette.panel)
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
                .clickable(enabled = false) {}
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Set price — fixed fare", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = CaptainPalette.textPrimary)
            Text(
                "Agreed with the passenger before starting. Levies and GST still apply on top.",
                fontFamily = InterFamily,
                fontSize = 16.sp,
                color = CaptainPalette.textSecondary,
            )
            Box(
                modifier = Modifier.width(448.dp).height(84.dp).clip(RoundedCornerShape(14.dp)).background(CaptainPalette.inset),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (amount.isEmpty()) "$0" else "$" + amount,
                    fontFamily = au.com.threesixty.cabdispatch.ui.theme.ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 44.sp,
                    color = CaptainPalette.success,
                )
            }
            DeckKeypad(
                onDigit = { d -> if (amount.length < 3) amount += d }, // matches the Fares Order cap this dialog already enforced: $1-$500
                onBackspace = { amount = amount.dropLast(1) },
                onClear = { amount = "" },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CaptainButton(text = "Cancel", outline = true, modifier = Modifier.weight(1f), onClick = onDismiss)
                CaptainButton(
                    text = "Start at fixed price",
                    heightDp = 72,
                    enabled = (amount.toIntOrNull() ?: 0) in 1..500,
                    modifier = Modifier.weight(1.6f),
                    onClick = { onConfirm(amount) },
                )
            }
        }
    }
}

// ============================================================================================
// Trip details — Point to Point Transport (Fares) Order 2026 UI-wiring pass. Shown on a plain
// (non-Set-Price) Start Meter tap so the driver can honestly declare the inputs the maxi (150%)
// rate actually turns on. Every default below (1 passenger, every toggle off) reproduces this
// app's pre-existing Start Meter behavior exactly — a driver who taps straight through without
// touching anything starts an ordinary, non-maxi, 1-passenger trip, same as before this pass.
// ============================================================================================

/**
 * Elderly-friendly passenger-count stepper (1-11) + three honestly-labelled maxi-rate
 * declaration toggles, shown before a plain metered Start Meter tap actually opens the trip. Not
 * shown for the Set Price ("fixed fare") flow — see [SetPriceDialogV2]'s own comment: that flow
 * is a separate, already-engine-correct feature this pass does not touch.
 *
 * [initialMaxiVehicle] prefills from [au.com.threesixty.cabdispatch.domain.MaxiVehicleStore] (the
 * driver's own prior declaration, or `false` if never set) so this doesn't ask the driver to
 * re-declare a fact about the vehicle on every single trip — but it is still just a local,
 * per-device self-declaration, never fleet-registry data (see that store's own doc), which is why
 * this dialog labels it "This vehicle has 5+ passenger seats", never implying it came from a
 * vehicle record.
 */
@Composable
private fun TripDetailsDialog(
    initialMaxiVehicle: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (passengerCount: Int, isMaxiVehicle: Boolean, wheelchairHiring: Boolean, airportRankRequestedMaxi: Boolean) -> Unit,
) {
    var passengerCount by rememberSaveable { mutableStateOf(1) }
    var isMaxiVehicle by rememberSaveable { mutableStateOf(initialMaxiVehicle) }
    var wheelchairHiring by rememberSaveable { mutableStateOf(false) }
    var airportRankRequestedMaxi by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CaptainPalette.panel)
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Before you start the meter", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
            Text(
                "Passenger count is the main thing that turns on the maxi (×1.5) rate — a quick, honest check before you drive off.",
                fontFamily = InterFamily,
                fontSize = 15.sp,
                color = CaptainPalette.textSecondary,
            )

            // --- Passenger count stepper (big, elderly-friendly touch targets) ---
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PASSENGERS", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp, color = CaptainPalette.textMuted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    StepperButton(label = "−", enabled = passengerCount > 1, onClick = { passengerCount = (passengerCount - 1).coerceIn(1, 11) })
                    Box(
                        modifier = Modifier.width(96.dp).height(72.dp).clip(RoundedCornerShape(14.dp)).background(CaptainPalette.inset),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            passengerCount.toString(),
                            fontFamily = au.com.threesixty.cabdispatch.ui.theme.ChakraPetch,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 40.sp,
                            color = CaptainPalette.textPrimary,
                        )
                    }
                    StepperButton(label = "+", enabled = passengerCount < 11, onClick = { passengerCount = (passengerCount + 1).coerceIn(1, 11) })
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.panelBorder))

            TripDetailToggleRow(
                title = "This vehicle has 5+ passenger seats",
                subtitle = "Your own declaration for this vehicle — not read from a vehicle record. Saved for next time.",
                checked = isMaxiVehicle,
                onCheckedChange = { isMaxiVehicle = it },
            )
            TripDetailToggleRow(
                title = "Carrying a wheelchair passenger",
                subtitle = "Per NSW Reg cl 82: start the meter only once the passenger is safely secured. The maxi rate never applies to a wheelchair hiring, regardless of passenger count.",
                checked = wheelchairHiring,
                onCheckedChange = { wheelchairHiring = it },
            )
            TripDetailToggleRow(
                title = "Requested as a maxi at a Sydney Airport rank",
                subtitle = "Only tick this if the hirer specifically asked for a maxi taxi at a Sydney Airport rank — not for an ordinary trip that happens to go to the airport.",
                checked = airportRankRequestedMaxi,
                onCheckedChange = { airportRankRequestedMaxi = it },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CaptainButton(text = "Cancel", outline = true, modifier = Modifier.weight(1f), onClick = onDismiss)
                CaptainButton(
                    text = "▶  Start meter",
                    heightDp = 72,
                    modifier = Modifier.weight(1.6f),
                    onClick = { onConfirm(passengerCount, isMaxiVehicle, wheelchairHiring, airportRankRequestedMaxi) },
                )
            }
        }
    }
}

/** One big (64dp) circular +/- button for [TripDetailsDialog]'s passenger stepper. */
@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(if (enabled) CaptainPalette.raised else CaptainPalette.inset)
            .border(1.dp, CaptainPalette.panelBorder, CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            color = if (enabled) CaptainPalette.textPrimary else CaptainPalette.textMuted,
        )
    }
}

/** One labelled toggle row for [TripDetailsDialog] — title + honest explanatory subtitle + a
 * standard Material [Switch], tinted to [CaptainPalette]. */
@Composable
private fun TripDetailToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
            Text(subtitle, fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.textMuted, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = CaptainPalette.primary, checkedThumbColor = CaptainPalette.accent),
        )
    }
}
