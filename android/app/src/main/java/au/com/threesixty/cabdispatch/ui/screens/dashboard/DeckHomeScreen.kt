package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.data.remote.JobDto
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.ShiftDurationLimit
import au.com.threesixty.cabdispatch.domain.DriverSession
import au.com.threesixty.cabdispatch.domain.GpsQuality
import au.com.threesixty.cabdispatch.domain.TodayStats
import au.com.threesixty.cabdispatch.domain.DuressUiState
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.ShiftSubmissionHandoff
import au.com.threesixty.cabdispatch.ui.overlays.DuressActiveBanner
import au.com.threesixty.cabdispatch.ui.overlays.DuressTriggeredOverlay
import au.com.threesixty.cabdispatch.domain.TripDetailHandoff
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
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.color
import au.com.threesixty.cabdispatch.ui.theme.DriverAvatar
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.PaneShell
import au.com.threesixty.cabdispatch.ui.theme.SosControl
import au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelContent
import au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelViewModel
import androidx.compose.material.icons.rounded.WarningAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

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
 * - **Nav rail contents.** Figma's collapsed rail has 11 icons and its expanded flyout lists 13
 *   different (and only partially overlapping) labels — neither list includes **Messages**, a real,
 *   working, existing feature ([MessagesWheelContent], `MESSAGES_THREAD` route) that this pass must
 *   not strand (see "do not break existing features"). A Messages entry is added to both; the
 *   flyout's "HELP & SUPPORT" / "NAVIGATE" / "MORE" (no backing screen for any of them) are left out
 *   rather than added as menu items that go nowhere. "LIVE MAP" is also added to the flyout only,
 *   preserving the previous Command Deck's live-position map view ([StatusMapPanel], unchanged)
 *   rather than deleting a working feature just because it isn't one of the 3 given frames.
 * - **SOS control.** Figma draws SOS as a plain circular tap target. This app's existing duress
 *   trigger is deliberately LONG-PRESS only — see `DeckNavRail` (deleted, P0.3)'s
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
 * User-directed: "majority users are old age... make it full prominent... menu needs to be
 * properly fixed." Two kinds of change:
 *
 * 1. **One menu, not three.** The rail used to be accompanied by a hamburger at its top and a
 *    chevron half-off its edge, both opening a flyout that listed the same destinations again.
 *    All three duplicates are gone (2026-09-03, user-directed: "remove the all menu"): the
 *    [CaptainNavRail] itself is the single menu, scrolls, and now also carries the three
 *    destinations (Messages, Live map, Log off) that previously lived only in the flyout.
 * 2. **Deliberately larger type and touch targets everywhere**, sized for a driver reading this at
 *    arm's length, possibly older, possibly with reduced fine-motor precision. [Deck.TOUCH_MIN]-
 *    style minimums exist elsewhere in this app for exactly this reason; this pass pushes the same
 *    principle further for THIS screen specifically. Nothing here shrinks — every touch target
 *    (rail rows, quick-action tiles, the START METER button, the SOS control) grew, and every
 *    label/value font size grew, at the cost of some of the reference's density. See each
 *    composable below for its own before/after.
 */
/** The rail is a narrow icon-over-label column now (mockup #3/#4), not a 232dp icon+text list:
 * 96dp tiles + 10dp side padding. Every tile is still a full 96x72dp touch target. */
internal val RAIL_WIDTH = 116.dp

private val RAIL_GUTTER = 16.dp

private val CONTENT_END_PADDING = 12.dp

private val FLYOUT_WIDTH = 280.dp

/** Flyout's right edge lands exactly on the collapsed rail's left edge (plus a hair of breathing
 * room) — computed from the same constants the content [Row] and [CaptainNavRail] use, rather
 * than a second hand-tuned magic number that would silently drift the moment either changes. */

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
    // same convention as [HomeExtras] below. Drives the nav rail's METER alias (see RAIL_ITEMS'
    // own comment) and gates the footer stats bar for the Meter pane.
    val activeTrip by AppContainer.tripRepository.observeActiveTrip().collectAsState(initial = null)
    val hasActiveTrip = activeTrip != null
    var showSetPrice by rememberSaveable { mutableStateOf(false) }
    // Point to Point Transport (Fares) Order 2026 UI-wiring pass: the plain (non-Set-Price)
    // Start Meter tap now opens this small declaration step first — see TripDetailsDialog's own
    // doc for why (passenger count / maxi-taxi / wheelchair / airport-rank-maxi inputs).
    var showTripDetails by rememberSaveable { mutableStateOf(false) }
    var showVoucherInfo by rememberSaveable { mutableStateOf(false) }
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
            // Three distinct preconditions gate WheelDashboardViewModel.startMeter() (session,
            // tariff, and — 2026-09-04, real-GPS-fix-at-hire-start fix — a live location fix);
            // report whichever one is actually missing rather than defaulting to a session
            // message when the real cause is "no GPS lock yet".
            meterPhase = MeterStartPhase.Failed(
                when {
                    state.tariff == null -> "No signed tariff yet — try again shortly"
                    AppContainer.speedSource.locationFix.value == null -> "Waiting for GPS fix — try again shortly"
                    else -> "No active session"
                },
            )
            return
        }
        meterPhase = MeterStartPhase.Starting
        scope.launch {
            delay(METER_START_TRANSITION_MS) // real minimum dwell so the transition is visible, not a flash
            if (meterPhase == MeterStartPhase.Starting) navController.navigate(CabDispatchRoutes.HIRED)
        }
    }

    fun onCancelStart() {
        if (meterPhase !is MeterStartPhase.Starting) return
        SessionHolder.clearPendingTrip()
        meterPhase = MeterStartPhase.Idle
    }

    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.bg)) {
    // Futuristic-HUD reskin (2026-09-07, design brief item 1: "Overlay a subtle, very low-opacity
    // wireframe map or tech-grid pattern"). Drawn first, behind everything else, deliberately at a
    // very low, fixed alpha (see TechGridBackdrop's own doc) — this sits behind real fare/status
    // text a driver reads while driving, so legibility comes first; verified visually against every
    // panel below rather than assumed safe at a glance.
    TechGridBackdrop()
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
            // Same Room open-trip read that gates the rail's METER item — drives the header
            // pill's HIRED state (see HeaderStatus' own doc for what is NOT derivable here).
            hasActiveTrip = hasActiveTrip,
            onShowDriverId = { showDriverId = true },
            // Same lock as the rail's dispatch() guard above -- the header avatar is a second,
            // easy-to-miss escape hatch to Profile that a driver could tap mid-fare (found live:
            // it bypasses CaptainNavRail entirely, so fixing dispatch() alone did not close this
            // one). No-op while a trip is open, same as every other non-METER rail action.
            onOpenProfile = { if (!hasActiveTrip) navController.navigate(CabDispatchRoutes.PROFILE) },
            onToggleAvailability = { viewModel.setAvailable(!state.isAvailable) },
            onSos = { AppContainer.duressController.trigger(state.session?.vehicleId, state.session?.driverId) },
        )
        // Real bug fixed (2026-09-02): setAvailable's failure path already produced
        // availabilityError, but nothing anywhere rendered it — a failed toggle silently reverted
        // with zero feedback to the driver about why. A small inline banner, not a dialog, so it
        // doesn't block the rest of the screen.
        AnimatedVisibility(
            visible = state.availabilityError != null,
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
                    state.availabilityError ?: "",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = CaptainPalette.danger,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.panelBorder))
        Row(modifier = Modifier.weight(1f).padding(top = 20.dp, start = 32.dp, end = 12.dp, bottom = 20.dp)) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (pane) {
                        CaptainPane.DASHBOARD -> {
                            MeterCard(
                                state = state,
                                meterPhase = meterPhase,
                                negotiatedTotal = pendingTrip?.negotiatedTotal,
                                onStartMeter = { showTripDetails = true },
                                onCancelStart = ::onCancelStart,
                                onSetPrice = { showSetPrice = true },
                                onVouchers = { showVoucherInfo = true },
                                // 590dp -> 660dp (2026-08-29 prominence pass): grown so the bigger
                                // dial + bigger corner tiles below have real clearance from each
                                // other instead of visibly colliding — see MeterDial/NightFareTile/
                                // QuickActionTile's own comments for the exact measurements.
                                modifier = Modifier.width(660.dp).fillMaxHeight(),
                            )
                            Spacer(Modifier.width(16.dp))
                            // Driver-engagement tiles (2026-09-04, backend commit 58ccfcf): the
                            // mockup's WALLET BALANCE / RATING / ANNOUNCEMENTS / INCENTIVE PROGRESS
                            // now sit under the live-dispatch card in this right-hand column. On the
                            // fixed 1280dp canvas this column is only ~316dp wide beside the 660dp
                            // meter card, so the tiles stack vertically and the column scrolls
                            // rather than shrinking any of them; the dispatch card keeps a fixed
                            // height (its own LazyColumn needs bounded height inside a scroller) and
                            // still has VIEW ALL for the full list. See EngagementTiles.kt for the
                            // real-data / honest "Add funds" rules.
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                LiveDispatchCard(
                                    dispatchState = dispatchState,
                                    onAccept = dispatchViewModel::acceptOffer,
                                    onViewAll = { pane = CaptainPane.DISPATCH },
                                    modifier = Modifier.fillMaxWidth().height(300.dp),
                                )
                                Spacer(Modifier.height(16.dp))
                                DriverEngagementTiles(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        CaptainPane.DISPATCH -> PaneShell("Live dispatch", onBack = { pane = CaptainPane.DASHBOARD }) {
                            AvailableTripsWheelContent(navController = navController)
                        }
                        CaptainPane.TRIPS -> PaneShell("Trip history", onBack = { pane = CaptainPane.DASHBOARD }) {
                            // variant = HISTORY (2026-09-03): the rail item's own flyout label
                            // ("TRIP HISTORY", see RAIL_ITEMS below) already committed to this
                            // being the history table, not the MY_TRIPS default — this call was
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
                        // (see RAIL_ITEMS' own comment and PricingPaneContent's class doc for why).
                        CaptainPane.PRICING -> PaneShell("Pricing", onBack = { pane = CaptainPane.DASHBOARD }) {
                            PricingPaneContent()
                        }
                        // Real Available/Used/Expired voucher-ledger browse screen (Phase G) —
                        // replaces the old mislabelled alias where VOUCHERS silently opened
                        // VoucherInfoDialog (see RAIL_ITEMS' own comment and VouchersPaneContent's
                        // class doc for why). That dialog is unaffected and still reachable from
                        // the Dashboard's own MeterCard VOUCHERS quick-action tile.
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
                        // accruing fare is the wrong affordance to offer — a driver correcting course
                        // mid-trip taps another rail item directly (all still reachable, per the
                        // "header/footer/nav-rail visible while HIRED" decision), never a literal
                        // "back" out of the meter. See HiredScreen's own doc for the rest of this pane.
                        CaptainPane.METER -> {
                            // Real bug found live, 2026-09-06: a process restart mid-fare (crash,
                            // OS kill, an OTA self-update taking effect) leaves a real Room
                            // OPEN trip with no live FareEngine ever attached to it in THIS
                            // process — HiredScreen's dial would show a freshly-reset "OFF" while
                            // the nav rail above stays correctly locked for a trip the driver has
                            // no way to see or end. SessionHolder.liveTripClientUuid mismatching
                            // the real active trip's clientUuid is exactly that case — see its own
                            // doc — and gets the same "the active trip lives elsewhere" redirect
                            // TripsWheelContent's onOpenActiveTrip already uses above, since
                            // CloseAndPayViewModel reconstructs a full, correct bill from Room
                            // alone, no live FareEngine required.
                            val liveTripClientUuid by SessionHolder.liveTripClientUuid.collectAsState()
                            if (hasActiveTrip && activeTrip?.clientUuid != liveTripClientUuid) {
                                LaunchedEffect(activeTrip?.clientUuid) {
                                    navController.navigate(CabDispatchRoutes.CLOSE_PAY)
                                }
                            } else {
                                HiredScreen(navController = navController)
                            }
                        }
                    }
                }
                // Meter-focus collapse (2026-09-04): while a fare is actually live on the METER
                // pane, this footer bar (SHIFT TIME/TRIPS/EARNINGS/NEXT BREAK + the GPS/WI-FI/
                // PRINTER/METER status tray) hides so HiredScreen's Row above — already `weight(1f)`
                // in the enclosing Column — expands into the freed ~170dp and the dial/map genuinely
                // grow rather than just gaining whitespace. `hasActiveTrip` (this file's own real
                // Room "is a fare open" read, doc'd above) is the same signal already gating the nav
                // rail's METER alias, so this collapses for exactly the live-fare duration and comes
                // back the moment Close & Pay actually closes the trip. Header and nav rail are
                // untouched — the driver keeps METER/other panes and the SOS pill reachable.
                if (pane == CaptainPane.DASHBOARD || (pane == CaptainPane.METER && !hasActiveTrip)) {
                    Spacer(Modifier.height(18.dp))
                    // 136dp -> 152dp (2026-09-04 HUD chrome pass): the NEXT BREAK cell now carries
                    // the ring + "Break in" + "Working until" + the TAKE BREAK button at
                    // arm's-length sizes, which needs the extra 16dp.
                    Row(modifier = Modifier.height(152.dp).fillMaxWidth()) {
                        // SystemStatusCard (GPS/network/printer/meter, bottom-right) removed
                        // 2026-09-06 on direct driver feedback -- redundant with the header strip's
                        // own StatusDot row (CaptainHeader, ~line 903) which already shows the same
                        // GPS/network/printer/battery state, just persistently repeated a second
                        // time here. ShiftStatsBar now simply fills the whole row instead of sharing
                        // it -- weight(1f) with no sibling already stretches full-width, no extra
                        // width math needed.
                        ShiftStatsBar(
                            state = state,
                            extras = homeExtras,
                            // Real toggle (2026-09-06, matching the header pill's own
                            // onToggleAvailability): TAKE BREAK/RESUME is the same setAvailable
                            // flip either direction, not a one-way "go on break" — see
                            // NextBreakTile's own doc for why it now shows the state-appropriate
                            // label instead of always "TAKE BREAK".
                            onTakeBreak = { viewModel.setAvailable(!state.isAvailable) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            CaptainNavRail(
                pane = pane,
                hasActiveTrip = hasActiveTrip,
                // The live pending-offer list AvailableTripsWheelViewModel already collects for
                // LiveDispatchCard — the DISPATCH badge is its size, never a static number.
                dispatchOfferCount = dispatchState.cards.size,
                onSelectPane = { pane = it },
                onOpenVouchers = { showVoucherInfo = true },
                onOpenProfile = { navController.navigate(CabDispatchRoutes.PROFILE) },
                onOpenSettings = { navController.navigate(CabDispatchRoutes.SETTINGS) },
                onLogOff = { navController.navigate(CabDispatchRoutes.LOG_OFF) },
                modifier = Modifier.fillMaxHeight(),
            )
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
    if (showVoucherInfo) {
        VoucherInfoDialog(onDismiss = { showVoucherInfo = false })
    }
    if (showDriverId) {
        DriverIdCard(
            session = state.session,
            verified = homeExtras.verified,
            // Third escape hatch to Profile found live (rail dispatch() and the header avatar
            // were the other two) -- same "no-op while a trip is open" guard as both.
            onOpenProfile = {
                if (!hasActiveTrip) {
                    showDriverId = false
                    navController.navigate(CabDispatchRoutes.PROFILE)
                }
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

    }
}

/** How long [MeterStartPhase.Starting] stays on screen before navigating — a real minimum dwell
 * time (not simulated hardware latency, see this file's class doc) so the transition Figma's frame
 * 3 asks for is actually visible rather than an instant flash between OFF and the Hired screen. */
private const val METER_START_TRANSITION_MS = 900L

/** [DeckHomeScreen]'s local Start Meter state machine — see [DeckHomeScreen.onStartMeter]. */
internal sealed interface MeterStartPhase {
    data object Idle : MeterStartPhase
    data object Starting : MeterStartPhase
    data class Failed(val message: String) : MeterStartPhase
}

/** The rail's fixed destinations (`01 · HOME — Collapsed Rail` / `02 · HOME — Expanded Menu`) —
 * see this file's class doc for exactly which Figma items are aliased, dropped, or added and why. */
internal enum class CaptainPane { DASHBOARD, DISPATCH, TRIPS, EARNINGS, SHIFT, ZONES, PRICING, VOUCHERS, MESSAGES, MAP, METER }

/**
 * Design brief item 1's "wireframe map or tech-grid pattern" (2026-09-07 futuristic-HUD reskin) —
 * a fixed, static grid of thin lines spanning the whole dashboard background, at a fixed 5% alpha
 * (see constraint on this pass: "must never reduce legibility" — every panel drawn on top of this
 * is a full opaque/glass surface, never a bare text-on-grid layer, so the grid is only ever visible
 * in the gaps between panels). Plain `Canvas`, drawn once per composition/recomposition like any
 * other static background — no animation, no per-frame state, nothing on a clock.
 */
@Composable
private fun TechGridBackdrop() {
    val lineColor = CaptainPalette.hudAccent.copy(alpha = TECH_GRID_ALPHA)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val step = TECH_GRID_STEP_DP.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
    }
}

private const val TECH_GRID_ALPHA = 0.05f

private const val TECH_GRID_STEP_DP = 64

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
internal data class HomeExtras(
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
internal fun rememberHomeExtras(driverId: String?, shiftId: String?): HomeExtras {
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
// Chrome previews (2026-09-04) — the header, the rail and the bottom bar on the HUD background at
// tablet width (SM-T575 landscape ≈ 1280dp), so the chrome can be reviewed without a device.
// Fixture data lives only in these previews; nothing below is reachable from the live screen.
// ============================================================================================

internal fun previewState(available: Boolean = true) = WheelDashboardUiState(
    session = DriverSession(
        driverId = "d-4f2a9c17",
        driverName = "Ben Farid",
        vehicleId = "CAP-5517",
        shiftId = "s-1",
        shiftStartAt = Instant.now().minus(Duration.ofMinutes(252)).toString(),
    ),
    isAvailable = available,
    todayStats = TodayStats(tripsCount = 9, kmTotal = java.math.BigDecimal("84.2"), earningsTotal = java.math.BigDecimal("212.40")),
    status = DashboardStatusStrip(
        gpsOk = true,
        networkOk = true,
        printerOk = false,
        batteryOk = true,
        batteryPercent = 82,
        gpsQuality = GpsQuality.GOOD,
        networkType = "4g",
    ),
)
