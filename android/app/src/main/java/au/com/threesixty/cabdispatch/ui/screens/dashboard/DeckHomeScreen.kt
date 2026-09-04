package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachMoney
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
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
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SsidChart
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Wifi
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import au.com.threesixty.cabdispatch.data.remote.JobDto
import au.com.threesixty.cabdispatch.data.remote.MapboxStaticImage
import au.com.threesixty.cabdispatch.data.remote.SydneyCbdFallback
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.ShiftDurationLimit
import au.com.threesixty.cabdispatch.domain.DriverSession
import au.com.threesixty.cabdispatch.domain.GpsQuality
import au.com.threesixty.cabdispatch.domain.TodayStats
import au.com.threesixty.cabdispatch.domain.DuressUiState
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.ShiftSubmissionHandoff
import au.com.threesixty.cabdispatch.domain.location.GeoMath
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
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.HudRing
import au.com.threesixty.cabdispatch.ui.theme.HudStatTile
import au.com.threesixty.cabdispatch.ui.theme.HudTone
import au.com.threesixty.cabdispatch.ui.theme.color
import au.com.threesixty.cabdispatch.ui.theme.hudSpring
import au.com.threesixty.cabdispatch.ui.theme.neonGlow
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.theme.DriverAvatar
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.PaneShell
import au.com.threesixty.cabdispatch.ui.theme.PulsingDot
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import au.com.threesixty.cabdispatch.ui.theme.SosControl
import au.com.threesixty.cabdispatch.ui.theme.rememberInfiniteFloat
import au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripCard
import au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelContent
import au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelViewModel
import au.com.threesixty.cabdispatch.ui.wheel.content.formatOfferRelativeTime
import androidx.compose.material.icons.rounded.WarningAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

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
private val RAIL_WIDTH = 116.dp
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
            onOpenProfile = { navController.navigate(CabDispatchRoutes.PROFILE) },
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
                        CaptainPane.METER -> HiredScreen(navController = navController)
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
                        ShiftStatsBar(
                            state = state,
                            extras = homeExtras,
                            // Honest local action (see ShiftLimitRing's own doc): no invented
                            // return-time claim, just the real setAvailable(false) call.
                            onTakeBreak = { viewModel.setAvailable(false) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        Spacer(Modifier.width(16.dp))
                        // 230dp -> 268dp so the 2x2 GPS / 4G-or-WIFI / PRINTER / METER cells
                        // fit their tone dot + label + value without wrapping.
                        SystemStatusCard(state = state, modifier = Modifier.width(268.dp).fillMaxHeight())
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
// Header (mockup: avatar/wordmark, driver identity, VERIFIED, status pill, system strip, SOS) —
// rebuilt on the HUD kit (2026-09-04): GlassCard pills with neon halos, PulsingDot, the same real
// data sources and callbacks as before, SosControl's press-and-hold untouched.
// ============================================================================================

/**
 * The header status pill's three REAL states, derived in [headerStatus] from two signals this
 * screen already holds: [DeckHomeScreen]'s Room `observeActiveTrip()` read (an OPEN `TripEntity` —
 * the same signal that gates the rail's METER item) and [WheelDashboardUiState.isAvailable].
 *
 * The mockup also draws ON TRIP / PAUSED / COMPLETED pills. None of those is honestly reachable
 * from this file, so none is faked: PAUSED is `FareState.status == STOPPED` on the live
 * [au.com.threesixty.cabdispatch.domain.FareEngine], which is instantiated privately per nav
 * entry inside `HiredViewModel` (see that engine's own "hoist to AppContainer" TODO) — the
 * dashboard cannot observe it; COMPLETED has no persisted signal a dashboard can watch (a closed
 * trip simply stops being the active one, and the pill falls back to AVAILABLE/OFF DUTY); ON TRIP
 * is indistinguishable from HIRED with the data here, so the one open-fare state is shown as the
 * mockup's HIRED / "Trip in progress".
 */
private enum class HeaderStatus(val title: String, val sub: String, val tone: HudTone) {
    HIRED("HIRED", "Trip in progress", HudTone.Success),
    AVAILABLE("AVAILABLE", "Ready to receive jobs", HudTone.Success),
    OFF_DUTY("OFF DUTY", "Tap to go available", HudTone.Neutral),
}

private fun headerStatus(isAvailable: Boolean, hasActiveTrip: Boolean): HeaderStatus = when {
    hasActiveTrip -> HeaderStatus.HIRED
    isAvailable -> HeaderStatus.AVAILABLE
    else -> HeaderStatus.OFF_DUTY
}

@Composable
private fun CaptainHeader(
    state: WheelDashboardUiState,
    verified: Boolean?,
    hasActiveTrip: Boolean,
    onShowDriverId: () -> Unit,
    onOpenProfile: () -> Unit,
    onToggleAvailability: () -> Unit,
    onSos: () -> Unit,
) {
    val status = headerStatus(isAvailable = state.isAvailable, hasActiveTrip = hasActiveTrip)
    val statusColor = status.tone.color()
    val statusNeutral = status.tone == HudTone.Neutral
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(CaptainPalette.glowPurpleSoft, Color.Transparent)))
            .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar in a soft accent halo. Tap opens the large Driver ID card (passenger face-
        // matching); the Profile route stays reachable via that card and the name tap below.
        Box(modifier = Modifier.neonGlow(CaptainPalette.hudAccent, 44.dp, strength = 0.7f)) {
            DriverAvatar(driverId = state.session?.driverId, driverName = state.session?.driverName, onClick = onShowDriverId, sizeDp = 88)
        }
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text("CAPTAIN", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 1.sp, color = CaptainPalette.textPrimary)
            Text("TAXIS", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 3.sp, color = CaptainPalette.hudSweepMid)
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
                fontSize = 15.sp,
                color = CaptainPalette.textSecondary,
            )
        }
        // "VERIFIED" — a real backend field, per the backend's own contract (2026-08-29): maps
        // to UserDto.suitabilityStatus == "clear" (fetched via GET /v1/auth/me, see
        // rememberHomeExtras below), not merely "a session exists". `null` (still loading, or the
        // field came back something other than "clear") shows nothing — never a false claim.
        if (verified == true) {
            GlassCard(modifier = Modifier.padding(start = 18.dp).height(40.dp), cornerRadiusDp = 20, glow = CaptainPalette.success) {
                Row(modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Verified, contentDescription = null, tint = CaptainPalette.success, modifier = Modifier.size(18.dp))
                    Text(
                        "VERIFIED",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.success,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        // Status pill — the same real toggle as before (WheelDashboardViewModel.setAvailable,
        // tap-to-flip), now a glass pill whose halo/dot/title take the HeaderStatus tone. Tapping
        // while HIRED still does exactly what it always did (flip availability); this pass changes
        // no control's behaviour.
        GlassCard(
            modifier = Modifier
                .padding(start = 18.dp)
                .height(64.dp)
                .gameClick(
                    onClick = onToggleAvailability,
                    shape = RoundedCornerShape(32.dp),
                    glowColor = if (statusNeutral) CaptainPalette.accent else statusColor,
                ),
            cornerRadiusDp = 32,
            glow = if (statusNeutral) null else statusColor,
        ) {
            Row(modifier = Modifier.fillMaxHeight().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(color = if (statusNeutral) CaptainPalette.textMuted else statusColor, animated = !statusNeutral, size = 14.dp)
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        status.title,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp,
                        letterSpacing = 1.sp,
                        color = if (statusNeutral) CaptainPalette.textSecondary else statusColor,
                    )
                    Text(
                        status.sub,
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        color = CaptainPalette.textSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // Real GPS/network/printer/battery — same DashboardStatusStrip WheelDashboardViewModel
        // already polls every 4s, grouped into one glass strip. GPS shows the real fix-quality
        // tier's tone (GpsQualityClassifier: GOOD/FAIR green, POOR amber, no fix/denied red) — the
        // same mapping the SYSTEM STATUS card below uses, so the two never disagree. Network label
        // is the real transport type (DeviceTelemetry.readNetworkType — "wifi"/"4g"/"offline") and
        // deliberately carries no signal-strength adjective ("STRONG"), since no TelephonyManager/
        // SignalStrength reading exists anywhere in this app to back one.
        GlassCard(modifier = Modifier.height(56.dp), cornerRadiusDp = 28) {
            Row(
                modifier = Modifier.fillMaxHeight().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                StatusDot(Icons.Rounded.LocationOn, "GPS", gpsTone(state.status.gpsQuality))
                StatusDot(networkIcon(state.status.networkType), networkStatusLabel(state.status.networkType), networkTone(state.status.networkType))
                StatusDot(Icons.Rounded.Print, "PRINTER", if (state.status.printerOk) HudTone.Success else HudTone.Danger)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val batteryColor = if (state.status.batteryOk) CaptainPalette.success else CaptainPalette.danger
                    Icon(Icons.Rounded.BatteryFull, contentDescription = null, tint = batteryColor, modifier = Modifier.size(20.dp))
                    Text(
                        state.status.batteryPercent?.let { "$it%" } ?: "—",
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = if (state.status.batteryOk) CaptainPalette.textPrimary else CaptainPalette.danger,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
            }
        }
        SosControl(onTrigger = onSos, modifier = Modifier.padding(start = 22.dp))
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

/** Tone for the same real signal: a known transport is green, a confirmed "offline" is red, and
 * `null` (DeviceTelemetry itself couldn't check) is amber — unknown, not a claimed failure. */
private fun networkTone(networkType: String?): HudTone = when (networkType) {
    "wifi", "4g" -> HudTone.Success
    "offline" -> HudTone.Danger
    else -> HudTone.Warning
}

private fun networkIcon(networkType: String?): ImageVector =
    if (networkType == "wifi") Icons.Rounded.Wifi else Icons.Rounded.SignalCellularAlt

/** [SystemStatusCard]'s network cell value for the same real signal ("ONLINE"/"OFFLINE"/"—"). */
private fun networkCellValue(networkType: String?): String = when (networkType) {
    "wifi", "4g" -> "ONLINE"
    "offline" -> "OFFLINE"
    else -> "—"
}

/** Tone for the real GPS fix-quality tier (`DashboardStatusStrip.gpsQuality`). GOOD/FAIR are
 * exactly what [au.com.threesixty.cabdispatch.domain.GpsQualityClassifier.isOk] calls ok (green),
 * POOR is a real-but-degraded fix (amber), no fix / permission denied is red. */
private fun gpsTone(quality: GpsQuality): HudTone = when (quality) {
    GpsQuality.GOOD, GpsQuality.FAIR -> HudTone.Success
    GpsQuality.POOR -> HudTone.Warning
    GpsQuality.NO_FIX, GpsQuality.PERMISSION_DENIED -> HudTone.Danger
}

private fun gpsValueLabel(quality: GpsQuality): String = when (quality) {
    GpsQuality.GOOD -> "GOOD"
    GpsQuality.FAIR -> "FAIR"
    GpsQuality.POOR -> "POOR"
    GpsQuality.NO_FIX -> "NO FIX"
    GpsQuality.PERMISSION_DENIED -> "DENIED"
}

/** One header system-strip entry: tone-tinted icon, label, and a [PulsingDot] that breathes only
 * for a red (failed) state — a healthy dot sits still. */
@Composable
private fun StatusDot(icon: ImageVector, label: String, tone: HudTone) {
    val toneColor = tone.color()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = toneColor, modifier = Modifier.size(18.dp))
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.5.sp,
            color = CaptainPalette.textPrimary,
            modifier = Modifier.padding(start = 6.dp),
        )
        PulsingDot(color = toneColor, animated = tone == HudTone.Danger, size = 9.dp, modifier = Modifier.padding(start = 7.dp))
    }
}

// ============================================================================================
// Meter card (Figma left card — night fare tile, dial, Set Price / Vouchers quick actions)
// ============================================================================================

@Composable
private fun MeterCard(
    state: WheelDashboardUiState,
    meterPhase: MeterStartPhase,
    // Real bug fixed (2026-09-02): the SET PRICE tile's subtitle used to be an unconditional
    // hardcoded "Fixed Fare · ACTIVE" literal regardless of actual state. `negotiatedTotal` is
    // `SessionHolder.pendingTrip.value?.negotiatedTotal`, collected by the caller — non-null only
    // when the driver actually used the Set Price flow for the trip about to start.
    negotiatedTotal: String?,
    onStartMeter: () -> Unit,
    onCancelStart: () -> Unit,
    onSetPrice: () -> Unit,
    onVouchers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fixedFareActive = negotiatedTotal != null
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            // Prominence pass (2026-09-02): a subtle top-to-bottom gradient instead of a flat
            // panel fill, plus a faint accent-tinted border — every major Home card gets this
            // same "gently lit, not flat" treatment (see LiveDispatchCard/ShiftStatsBar/
            // SystemStatusCard below).
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .border(1.dp, Brush.linearGradient(listOf(CaptainPalette.accent.copy(alpha = 0.3f), CaptainPalette.panelBorder)), RoundedCornerShape(18.dp))
            .padding(20.dp),
    ) {
        NightFareTile(tariff = state.tariff, modifier = Modifier.align(Alignment.TopStart))
        MeterDial(
            meterPhase = meterPhase,
            enabled = state.tariff != null,
            onStartMeter = onStartMeter,
            onCancelStart = onCancelStart,
            modifier = Modifier.align(Alignment.Center),
        )
        Column(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QuickActionTile(
                icon = Icons.Rounded.Sell,
                title = "SET PRICE",
                subtitle = if (fixedFareActive) "Fixed Fare · ACTIVE" else "Tap to set a price",
                subtitleColor = if (fixedFareActive) CaptainPalette.success else CaptainPalette.textSecondary,
                onClick = onSetPrice,
            )
            QuickActionTile(
                icon = Icons.Rounded.ConfirmationNumber,
                title = "VOUCHERS",
                subtitle = "Redeemed at payment",
                subtitleColor = CaptainPalette.textSecondary,
                onClick = onVouchers,
            )
        }
    }
}

/** Real night-rate uplift and window, not Figma's mock "1.25× / 10PM–6AM" — see this file's class
 * doc. `null` tariff (not yet signed/cached) hides the numeric ratio rather than showing a bogus
 * one. */
@Composable
private fun NightFareTile(tariff: au.com.threesixty.cabdispatch.data.remote.TariffDto?, modifier: Modifier = Modifier) {
    // 172dp -> 156dp (2026-08-29): pulled back slightly so this corner tile clears the also-bigger
    // meter dial behind it — see MeterCard's width comment. The window text now wraps to two
    // lines at this width, which is fine (the Column isn't height-constrained) — a real fix, not
    // a cosmetic call, since the alternative (172dp) visibly overlapped the dial's ring on-device.
    Column(
        modifier = modifier
            .width(156.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(18.dp))
            Text("NIGHT FARE", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = CaptainPalette.textSecondary, modifier = Modifier.padding(start = 7.dp))
        }
        Text(
            nightMultiplierLabel(tariff),
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            color = CaptainPalette.textPrimary,
            modifier = Modifier.padding(top = 6.dp),
        )
        // The backend's actual, authoritative night-rate window (confirmed directly by the
        // backend/architecture agent, 2026-08-29 contract Part 2.3/6): "10pm-6am ... hardcoded
        // server-side in TimeClass.NIGHT" and safe to display as-is since it's informational only
        // — the server enforces the real boundary at trip-tick/close time regardless. NOTE for the
        // record: this app's OWN local FareEngine.kt (used only for HiredScreen's live-ticking
        // display, a screen outside this pass's 3-screen scope) currently classifies night as
        // 8pm-6am, not 10pm-6am — a real discrepancy this pass found but does NOT fix here (fixing
        // the live meter's day/night boundary is a money-calculation change to a different screen,
        // out of this pass's mandate — flagged in the delivery notes instead).
        Text(
            "10:00 PM – 6:00 AM",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = CaptainPalette.textSecondary,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

private fun nightMultiplierLabel(tariff: au.com.threesixty.cabdispatch.data.remote.TariffDto?): String {
    val t = tariff ?: return "—"
    val day = t.distRate1.toBigDecimalOrNull() ?: return "—"
    val night = t.nightRate1.toBigDecimalOrNull() ?: return "—"
    if (day.signum() <= 0) return "—"
    val ratio = night.divide(day, 2, RoundingMode.HALF_UP)
    return "${ratio}×"
}

private fun String.toBigDecimalOrNull(): java.math.BigDecimal? = runCatching { java.math.BigDecimal(this) }.getOrNull()

@Composable
private fun QuickActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    subtitleColor: Color,
    onClick: () -> Unit,
) {
    Column(
        // 126x118 -> 156x172 (2026-08-29, revised after a real on-device check): width pulled back
        // to clear the dial (see MeterCard's width comment); height grown MORE than first tried —
        // the first pass's 144dp visibly clipped the subtitle line, confirmed live, not assumed.
        // Press feedback moved onto the shared gameClick spring/glow (game-feel pass).
        modifier = Modifier
            .width(156.dp)
            .height(172.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(20.dp))
            .gameClick(onClick = onClick, shape = RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(CaptainPalette.raised, CaptainPalette.cardBottom)))
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = CaptainPalette.textPrimary)
        Text(subtitle, fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = subtitleColor, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * The circular meter gauge — Figma's ~20 concentric-ellipse soft-glow ring is approximated with a
 * single [Canvas] draw (a few concentric strokes + a tick ring) rather than ported layer-for-layer;
 * materially cheaper to recompose and visually equivalent at this size. Ticks alternate accent/
 * neutral every 3rd position, matching the design's own rhythm.
 */
@Composable
private fun MeterDial(
    meterPhase: MeterStartPhase,
    enabled: Boolean,
    onStartMeter: () -> Unit,
    onCancelStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val starting = meterPhase is MeterStartPhase.Starting
    // Ring/glow brighten from neutral to full accent the moment a real Start Meter tap is in
    // flight — driven by [meterPhase], never a decorative loop on its own; see this file's class
    // doc for why the copy stays honest ("STARTING METER…") while the visual treatment below is
    // free to be as lively as the reference calls for.
    val ringColor by animateColorAsState(if (starting) CaptainPalette.accent else CaptainPalette.panelBorder, label = "ring-color")
    // Idle state gently BREATHES (0.32-0.5) rather than sitting at one flat value — a resting HUD
    // that's never perfectly static reads as "alive and waiting for you", not "off/broken", which
    // matters most for exactly the moment this control is trying hardest to invite a tap.
    val restPulse by rememberInfiniteFloat(enabled = !starting, from = 0.32f, to = 0.5f, durationMs = 2200)
    val glowStrength by animateFloatAsState(if (starting) 1f else restPulse, animationSpec = tween(500), label = "glow-strength")
    // Slow ambient rotation always running (the reference's light-sweep look); speeds up while
    // starting so the transition reads as "working", not just a colour change.
    val sweepAngle by rememberInfiniteTransition(label = "sweep").let { t ->
        t.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(if (starting) 1400 else 7000, easing = LinearEasing)),
            label = "sweep-angle",
        )
    }
    val pulse by rememberInfiniteFloat(enabled = true, from = 0.5f, to = 1f, durationMs = 1800)

    // 398dp -> 414dp: bigger than the original, but pulled back from an earlier 430dp pass that
    // visibly collided with the (also-bigger) corner tiles — see MeterCard's own width comment;
    // this size was picked by measuring the real overlap live, not guessed twice.
    Box(modifier = modifier.size(414.dp), contentAlignment = Alignment.Center) {
        // Soft outer glow — a few oversized, low-alpha radial-gradient circles standing in for
        // Figma's ~20-layer concentric-ellipse blur (see this composable's own doc above for why
        // that is a deliberate approximation, not a missed detail).
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxR = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(CaptainPalette.accent.copy(alpha = 0.22f * glowStrength * pulse), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = maxR,
                ),
                radius = maxR,
                center = Offset(cx, cy),
            )
            val strokeW = 2.dp.toPx()
            val radius = maxR - strokeW
            drawCircle(color = ringColor, radius = radius, style = Stroke(width = strokeW))
            // The rotating "sweep" highlight — one bright arc riding around the ring.
            drawArc(
                color = CaptainPalette.accent.copy(alpha = 0.9f * glowStrength),
                startAngle = sweepAngle,
                sweepAngle = 46f,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(width = strokeW * 2, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            val tickRadiusOuter = radius - 14.dp.toPx()
            val tickRadiusInner = tickRadiusOuter - 10.dp.toPx()
            val tickCount = 36
            for (i in 0 until tickCount) {
                val angle = (2 * Math.PI * i / tickCount)
                val accentTick = i % 3 == 0
                val color = if (accentTick) CaptainPalette.accent.copy(alpha = 0.6f + 0.4f * glowStrength) else CaptainPalette.dialNeutral
                val start = Offset(cx + (tickRadiusInner * cos(angle)).toFloat(), cy + (tickRadiusInner * sin(angle)).toFloat())
                val end = Offset(cx + (tickRadiusOuter * cos(angle)).toFloat(), cy + (tickRadiusOuter * sin(angle)).toFloat())
                drawLine(color = color, start = start, end = end, strokeWidth = if (accentTick) 3.dp.toPx() else 2.dp.toPx())
            }
            // A handful of orbiting "spark" points riding the same sweep angle, offset around the
            // ring — the reference's particle-like glints, not literal physics.
            repeat(5) { i ->
                val a = Math.toRadians((sweepAngle + i * 72).toDouble())
                val r = radius - 4.dp.toPx()
                val p = Offset(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat())
                drawCircle(color = CaptainPalette.accent.copy(alpha = 0.5f * glowStrength), radius = 2.5.dp.toPx(), center = p)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.DirectionsCar,
                contentDescription = null,
                tint = CaptainPalette.accent,
                modifier = Modifier.size(36.dp).scale(if (starting) pulse else 1f),
            )
            Text(
                "METER STATUS",
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                letterSpacing = 0.5.sp,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            val (label, sub) = when (meterPhase) {
                MeterStartPhase.Idle -> "OFF" to "Tap to start a new fare"
                MeterStartPhase.Starting -> "STARTING" to "Starting meter…"
                is MeterStartPhase.Failed -> "OFF" to meterPhase.message
            }
            // 62sp -> 76sp: the single biggest number on the screen, on purpose — an older driver
            // glancing over should never have to squint to know whether the meter is running.
            AnimatedContent(
                targetState = label,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.85f)).togetherWith(fadeOut() + scaleOut(targetScale = 1.1f)) },
                label = "meter-label",
            ) { animatedLabel ->
                Text(
                    animatedLabel,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 76.sp,
                    color = CaptainPalette.textPrimary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Text(
                sub,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 20.sp,
                color = if (meterPhase is MeterStartPhase.Failed) CaptainPalette.danger else CaptainPalette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp).width(240.dp),
            )
            Spacer(Modifier.height(28.dp))
            // Primary action on the whole screen — widened and heightened well past the standard
            // button size (184x54 -> 240x76) so it reads as unmistakably THE thing to press.
            if (meterPhase is MeterStartPhase.Starting) {
                CaptainButton(text = "CANCEL", outline = true, widthDp = 240, heightDp = 76, fontSize = 22.sp, onClick = onCancelStart)
            } else {
                CaptainButton(text = "▶  START METER", widthDp = 240, heightDp = 76, fontSize = 22.sp, enabled = enabled, onClick = onStartMeter)
            }
        }
    }
}

// ============================================================================================
// Live dispatch card (Figma right card — real JobsRepository data via AvailableTripsWheelViewModel)
// ============================================================================================

@Composable
private fun LiveDispatchCard(
    dispatchState: au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsUiState,
    onAccept: (AvailableTripCard) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .border(1.dp, Brush.linearGradient(listOf(CaptainPalette.accent.copy(alpha = 0.3f), CaptainPalette.panelBorder)), RoundedCornerShape(18.dp))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE DISPATCH", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
            if (dispatchState.cards.isNotEmpty()) {
                val badgePulse by rememberInfiniteFloat(enabled = true, from = 0.7f, to = 1f, durationMs = 1000)
                Box(
                    modifier = Modifier
                        .padding(start = 14.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(CaptainPalette.danger, CaptainPalette.danger.copy(alpha = 0.75f))))
                        .border(1.5.dp, CaptainPalette.danger.copy(alpha = badgePulse), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(dispatchState.cards.size.toString(), fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = CaptainPalette.textPrimary)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "VIEW ALL",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.clickable(onClick = onViewAll).padding(8.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        when {
            dispatchState.loading && dispatchState.cards.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Loading offers…", fontFamily = InterFamily, fontSize = 17.sp, color = CaptainPalette.textSecondary)
            }
            dispatchState.cards.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    dispatchState.error ?: "No live offers right now",
                    fontFamily = InterFamily,
                    fontSize = 17.sp,
                    color = CaptainPalette.textSecondary,
                )
            }
            else -> LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(dispatchState.cards, key = { it.offer.id }) { card ->
                    DispatchOfferRow(
                        card = card,
                        busy = dispatchState.busyOfferId == card.offer.id,
                        onAccept = { onAccept(card) },
                    )
                }
            }
        }
        dispatchState.actionError?.let {
            Text(it, fontFamily = InterFamily, fontSize = 15.sp, color = CaptainPalette.danger, modifier = Modifier.padding(top = 10.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(top = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(CaptainPalette.primary.copy(alpha = 0.22f), CaptainPalette.inset)))
                .border(1.dp, CaptainPalette.accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .gameClick(onClick = onViewAll, shape = RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("VIEW ALL JOBS   →", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
        }
    }
}

@Composable
private fun DispatchOfferRow(card: AvailableTripCard, busy: Boolean, onAccept: () -> Unit) {
    val job = card.job
    // Real server-computed distance/ETA (2026-08-29 backend contract Part 2.6/4.1: haversine +
    // a flat 30km/h heuristic, explicitly flagged by the backend as an approximation, not routed/
    // live-traffic) when present. `null` on a job created before that migration landed — falls
    // back to this app's own live-GPS straight-line distance (no ETA fabricated locally), then to
    // the offer's relative-request-time text if even a GPS fix isn't available yet.
    val fix by AppContainer.speedSource.locationFix.collectAsState()
    val distanceLabel = when {
        job.distanceKm != null && job.etaMin != null ->
            "${job.distanceKm} km · ${job.etaMin} min (approx.)"
        fix != null -> "%.1f km away".format(Locale.ENGLISH, GeoMath.distanceKm(fix!!.lat, fix!!.lng, job.originLat, job.originLng))
        else -> formatOfferRelativeTime(card.offer.offeredAt)
    }
    // Real job_type badge (2026-08-29 contract) — "NEW OFFER" is the honest fallback for a job
    // created before that field existed (server_default backfills "booked" going forward, but a
    // null here would still mean "we don't actually know").
    val (badgeText, badgeColor) = when (job.jobType) {
        "rank_hail" -> "RANK JOB" to CaptainPalette.warning
        "booked" -> "BOOKED" to CaptainPalette.primary
        else -> "NEW OFFER" to CaptainPalette.primary
    }
    // Client-side comma-split of the backend's single free-text address field into a street line
    // + locality line — per the backend contract's own note (Part 2.6): the two-line look in the
    // design is NOT two separate backend fields.
    val (originStreet, originLocality) = splitAddress(job.originAddress)
    val (destStreet, destLocality) = splitAddress(job.destAddress)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CaptainPalette.inset)
            // Colour-coded elevation (2026-09-02 prominence pass): each offer row's border now
            // tints with its own badge colour (purple = BOOKED, amber = RANK JOB) instead of a
            // uniform neutral border — the same purple/amber/green/red coding used everywhere
            // else on this screen (SOS, availability pill, SET PRICE ACTIVE state, …).
            .border(1.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(badgeColor, badgeColor.copy(alpha = 0.75f))))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(badgeText, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = CaptainPalette.textPrimary)
            }
            Spacer(Modifier.weight(1f))
            Text(distanceLabel, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = CaptainPalette.textSecondary)
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Column(modifier = Modifier.weight(1f)) {
                Text(originStreet, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = CaptainPalette.textPrimary, maxLines = 1)
                if (originLocality != null) {
                    Text(originLocality, fontFamily = InterFamily, fontSize = 14.sp, color = CaptainPalette.textSecondary, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
                Text(destStreet, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = CaptainPalette.textPrimary, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                if (destLocality != null) {
                    Text(destLocality, fontFamily = InterFamily, fontSize = 14.sp, color = CaptainPalette.textSecondary, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("EST. FARE", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = CaptainPalette.textSecondary)
                Text(
                    "$${card.job.fareEstimateLow}–$${card.job.fareEstimateHigh}",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = CaptainPalette.textPrimary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        CaptainButton(text = if (busy) "…" else "ACCEPT", widthDp = null, heightDp = 54, fontSize = 18.sp, enabled = !busy, onClick = onAccept, modifier = Modifier.fillMaxWidth())
    }
}

// ============================================================================================
// Bottom bar: shift stats + shift limit + system status
// ============================================================================================

/** Splits a single free-text address ("12 Railway Parade, Lakemba NSW 2195") into a street line
 * and a locality line on the first comma — see [DispatchOfferRow]'s own doc for why this is a
 * client-side string operation, not two backend fields. No comma (an address that doesn't follow
 * the "street, suburb state postcode" shape) just renders as one line, locality `null`. */
private fun splitAddress(address: String): Pair<String, String?> {
    val idx = address.indexOf(',')
    if (idx < 0) return address to null
    return address.substring(0, idx).trim() to address.substring(idx + 1).trim()
}

// ============================================================================================
// Bottom stats bar (mockup: SHIFT TIME / TRIPS / EARNINGS / NEXT BREAK, plus SYSTEM STATUS) —
// rebuilt on the HUD kit (2026-09-04). Every cell is a HudStatTile or a GlassCard; every figure is
// the same real source as before (session.shiftStartAt, todayStats, HomeExtras, ShiftDurationLimit,
// DashboardStatusStrip). Nothing renders a placeholder number.
// ============================================================================================

/** Bar-cell value size: 32sp (the kit's 24sp default is a card size; this bar is read at arm's
 * length by an older driver population — see this file's class doc). */
private val STATS_VALUE_SIZE = 32.sp

@Composable
private fun ShiftStatsBar(
    state: WheelDashboardUiState,
    extras: HomeExtras,
    onTakeBreak: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remaining = ShiftDurationLimit.remaining(state.session?.shiftStartAt)
    val elapsedFraction = remaining?.let { r ->
        val limit = ShiftDurationLimit.SHIFT_DURATION_LIMIT_HOURS * 3600.0
        val remainingSec = r.seconds.toDouble()
        ((limit - remainingSec) / limit).toFloat().coerceIn(0f, 1f)
    } ?: 0f
    val elapsedLabel = shiftElapsedLabel(state.session?.shiftStartAt)
    // Real shift-scoped open-trip count (2026-08-29 contract Part 2.4/4.2: GET /v1/trips?...
    // &status=open). Shown only once loaded and non-zero — a `0` here is genuinely "no active
    // trip", worth just staying quiet about rather than printing "0 Active" under a stat row.
    val activeTrips = extras.tripsActiveThisShift?.takeIf { it > 0 }
    // Real day-over-day trend (2026-08-29 contract Part 4.3: GET /v1/trips/earnings/today,
    // Sydney-local calendar day). `null` means either not loaded yet or the backend had no
    // yesterday baseline — both render nothing, never a fabricated "0%" or "+12%".
    val pctChange = extras.earningsPctChange

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HudStatTile(
            icon = Icons.Rounded.Schedule,
            label = "Shift time",
            value = elapsedLabel ?: "—",
            sub = state.session?.shiftStartAt?.let { "Started ${formatClockTime(it)}" } ?: "No active shift",
            valueFontSize = STATS_VALUE_SIZE,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            footer = { ShiftProgressBar(fraction = elapsedFraction) },
        )
        HudStatTile(
            icon = Icons.Rounded.DirectionsCar,
            label = "Trips",
            value = state.todayStats.tripsCount.toString(),
            sub = "Completed",
            tone = HudTone.Success,
            valueFontSize = STATS_VALUE_SIZE,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            footer = if (activeTrips != null) { { ActiveTripsPill(active = activeTrips) } } else null,
        )
        HudStatTile(
            icon = Icons.Rounded.AttachMoney,
            label = "Earnings",
            value = "$" + state.todayStats.earningsTotal.setScale(0, RoundingMode.HALF_UP).toPlainString(),
            sub = "Today",
            tone = HudTone.Success,
            valueFontSize = STATS_VALUE_SIZE,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            footer = if (pctChange != null) { { EarningsDelta(pct = pctChange) } } else null,
        )
        NextBreakTile(
            remaining = remaining,
            session = state.session,
            fatigueAlertCount = extras.fatigueAlertCount,
            latestFatigueKind = extras.latestFatigueKind,
            onTakeBreak = onTakeBreak,
            modifier = Modifier.weight(1.55f).fillMaxHeight(),
        )
    }
}

/** SHIFT TIME's thin elapsed-vs-limit bar: [CaptainPalette.hudTrack] track, the HUD sweep gradient
 * as fill, settling on [hudSpring] like every other gauge in the kit. */
@Composable
private fun ShiftProgressBar(fraction: Float) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), animationSpec = hudSpring(), label = "shift-progress")
    Box(
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(CaptainPalette.hudTrack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(CaptainPalette.hudSweep)),
        )
    }
}

/** TRIPS' green "N Active" pill — only ever composed for a real, loaded, non-zero count. */
@Composable
private fun ActiveTripsPill(active: Int) {
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CaptainPalette.glowSuccessSoft)
            .border(1.dp, CaptainPalette.success.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot(color = CaptainPalette.success, animated = true, size = 8.dp)
        Text(
            "$active Active",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = CaptainPalette.success,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** EARNINGS' day-over-day delta — green when up, red when down; only composed when the backend
 * returned a real yesterday baseline. */
@Composable
private fun EarningsDelta(pct: Double) {
    val up = pct >= 0
    Text(
        "${if (up) "+" else "−"}${"%.0f".format(Locale.ENGLISH, kotlin.math.abs(pct))}% vs yesterday",
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        color = if (up) CaptainPalette.success else CaptainPalette.danger,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * The mockup's NEXT BREAK cell, on [GlassCard] + [HudRing]. Every figure is the REAL 12h shift-
 * duration clock ([ShiftDurationLimit.remaining], the documented client-side mirror of the
 * backend's fatigue limit) — there is still no break-schedule / break-taken API anywhere in this
 * app (see this file's class doc, "NEXT BREAK ring"), so "Break in h:mm" here means "time until
 * the fatigue limit says you must stop", and "Working until" is that limit's wall-clock time.
 * Neither is an invented schedule. The ring's progress is the remaining fraction of the limit and
 * the card gains a red halo once genuinely close to it (<15% left).
 *
 * [fatigueAlertCount]/[latestFatigueKind] stay as the real `GET /v1/fatigue-alerts` signal
 * (see [rememberHomeExtras]), shown only once loaded and non-zero. TAKE BREAK is the pre-existing
 * real action — [onTakeBreak] is wired to `setAvailable(false)`: it honestly does the one thing
 * this app can do (stop receiving offers) and claims no return time it doesn't know.
 */
@Composable
private fun NextBreakTile(
    remaining: Duration?,
    session: DriverSession?,
    fatigueAlertCount: Int?,
    latestFatigueKind: String?,
    onTakeBreak: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = remaining?.let { r ->
        (r.seconds.toDouble() / (ShiftDurationLimit.SHIFT_DURATION_LIMIT_HOURS * 3600.0)).toFloat().coerceIn(0f, 1f)
    } ?: 0f
    val urgent = remaining != null && fraction < 0.15f
    val labelTint = if (urgent) CaptainPalette.danger else CaptainPalette.hudAccent
    GlassCard(modifier = modifier, cornerRadiusDp = 18, glow = if (urgent) CaptainPalette.danger else null) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                HudRing(progress = fraction, modifier = Modifier.fillMaxSize(), strokeWidthDp = 6)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Coffee, contentDescription = null, tint = CaptainPalette.textSecondary, modifier = Modifier.size(16.dp))
                    Text(
                        remaining?.let { formatDurationHm(it) } ?: "—",
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = CaptainPalette.textPrimary,
                    )
                }
            }
            Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Coffee, contentDescription = null, tint = labelTint, modifier = Modifier.size(16.dp))
                    Text(
                        "NEXT BREAK",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.textMuted,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(
                    remaining?.let { "Break in ${formatDurationHmm(it)}" } ?: "No active shift",
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = if (urgent) CaptainPalette.danger else CaptainPalette.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    workingUntilLabel(session?.shiftStartAt) ?: "Start a shift to see your limit",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = CaptainPalette.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
                // Real signal, finally consumed (2026-09-02): GET /v1/fatigue-alerts. Only shown once
                // loaded and non-zero — silence here is a genuine "no alerts", not "not checked".
                if (fatigueAlertCount != null && fatigueAlertCount > 0) {
                    Text(
                        "⚠ $fatigueAlertCount fatigue alert${if (fatigueAlertCount == 1) "" else "s"}" +
                            (latestFatigueKind?.let { " · ${it.replace('_', ' ')}" } ?: ""),
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = CaptainPalette.warning,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // Honest local action, not a fabricated break schedule — see this composable's own
                // doc. Sets real availability false; claims no return time this app doesn't know.
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CaptainPalette.hudAccent.copy(alpha = 0.22f))
                        .border(1.dp, CaptainPalette.hudSweepMid.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .gameClick(onClick = onTakeBreak, shape = RoundedCornerShape(12.dp), glowColor = CaptainPalette.hudSweepMid)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        "☕ TAKE BREAK",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.hudSweepMid,
                    )
                }
            }
        }
    }
}

/**
 * SYSTEM STATUS on a [GlassCard]: a 2x2 grid of small tone-tinted cells — GPS (real fix-quality
 * tier), the real transport type (4G / WI-FI, or NETWORK when offline/unknown), PRINTER and METER
 * (tariff signed and cached = READY; otherwise WAIT, amber — waiting for a tariff is not a fault).
 * Green / amber / red come from [gpsTone]/[networkTone] — the same mapping the header strip uses.
 */
@Composable
private fun SystemStatusCard(state: WheelDashboardUiState, modifier: Modifier = Modifier) {
    val meterReady = state.tariff != null
    GlassCard(modifier = modifier, cornerRadiusDp = 18) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = CaptainPalette.hudAccent, modifier = Modifier.size(16.dp))
                Text(
                    "SYSTEM STATUS",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    color = CaptainPalette.textMuted,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SystemStatusCell("GPS", gpsValueLabel(state.status.gpsQuality), gpsTone(state.status.gpsQuality), Modifier.weight(1f))
                    SystemStatusCell(networkStatusLabel(state.status.networkType), networkCellValue(state.status.networkType), networkTone(state.status.networkType), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SystemStatusCell("PRINTER", if (state.status.printerOk) "ON" else "OFF", if (state.status.printerOk) HudTone.Success else HudTone.Danger, Modifier.weight(1f))
                    SystemStatusCell("METER", if (meterReady) "READY" else "WAIT", if (meterReady) HudTone.Success else HudTone.Warning, Modifier.weight(1f))
                }
            }
        }
    }
}

/** One SYSTEM STATUS cell: tone-tinted fill/border, a [PulsingDot] that breathes only when
 * something needs attention (amber/red), muted label, tone-coloured value. */
@Composable
private fun SystemStatusCell(label: String, value: String, tone: HudTone, modifier: Modifier = Modifier) {
    val toneColor = tone.color()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(toneColor.copy(alpha = 0.10f))
            .border(1.dp, toneColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot(color = toneColor, animated = tone == HudTone.Danger || tone == HudTone.Warning, size = 8.dp)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.5.sp, color = CaptainPalette.textMuted, maxLines = 1)
            Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = toneColor, maxLines = 1)
        }
    }
}

// --- Shift-time formatting helpers (real session.shiftStartAt, no fabricated numbers) ---------

private fun shiftElapsedLabel(shiftStartAtIso: String?): String? {
    val start = shiftStartAtIso?.let { parseInstantOrOffset(it) } ?: return null
    val elapsed = Duration.between(start, Instant.now()).let { if (it.isNegative) Duration.ZERO else it }
    return formatDurationHm(elapsed)
}

/** "Working until 6:12 PM" — the wall-clock time the real 12h shift-duration limit is reached. */
private fun workingUntilLabel(shiftStartAtIso: String?): String? {
    val start = shiftStartAtIso?.let { parseInstantOrOffset(it) } ?: return null
    val end = start.plusSeconds((ShiftDurationLimit.SHIFT_DURATION_LIMIT_HOURS * 3600.0).toLong())
    val zoned = end.atZone(java.time.ZoneId.systemDefault())
    val fmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    return "Working until ${fmt.format(zoned)}"
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

/** "7:48" — [formatDurationHm] without the zero-padded hour, for the "Break in h:mm" line. */
private fun formatDurationHmm(d: Duration): String {
    val abs = d.abs()
    val h = abs.toHours()
    val m = abs.minusHours(h).toMinutes()
    val sign = if (d.isNegative) "-" else ""
    return "$sign$h:${m.toString().padStart(2, '0')}"
}

private fun parseInstantOrOffset(iso: String): Instant? =
    runCatching { Instant.parse(iso) }.recoverCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()

// ============================================================================================
// Nav rail — the ONE menu (mockup #3/#4): a single vertical icon rail, icon + short uppercase label
// per item, the active item lit by an accent glow pill. Rebuilt on the HUD kit (2026-09-04): the
// numbered "1 2 3…" circles are gone (not in the mockup), DISPATCH carries a live red offer-count
// badge, METER glows green while a fare is open. No hamburger, flyout or chevron — those were
// three duplicate menus and were deleted on 2026-09-03; see the class doc for the item mapping,
// additions (Messages, Live Map) and omissions (Help & Support, Navigate, More).
// ============================================================================================

private data class RailItem(
    val icon: ImageVector,
    val label: String,
    val action: RailAction,
)
private sealed interface RailAction {
    data class ToPane(val pane: CaptainPane) : RailAction
    data object OpenVouchers : RailAction
    data object OpenProfile : RailAction
    data object OpenSettings : RailAction
    data object LogOff : RailAction
}

/**
 * [hasActiveTrip] decides what the METER row actually points at (Phase A shell-integration,
 * 2026-09-03): the old hardcoded alias to [CaptainPane.DASHBOARD] ("meter lives on Dashboard") is
 * now only the fallback for "no fare is open right now" — tapping METER while [DeckHomeScreen]'s
 * own active-trip read is true instead jumps straight to the real, live [CaptainPane.METER] pane,
 * matching this file's class doc ("decide whether that alias should now point at the real
 * active-fare pane when a trip is active"). A plain function (not a `val`) since this genuinely
 * varies per composition rather than being a fixed table.
 */
private fun railItems(hasActiveTrip: Boolean) = listOf(
    RailItem(Icons.Rounded.Home, "DASHBOARD", RailAction.ToPane(CaptainPane.DASHBOARD)),
    RailItem(Icons.Rounded.Receipt, "TRIPS", RailAction.ToPane(CaptainPane.TRIPS)),
    RailItem(Icons.Rounded.SwapHoriz, "DISPATCH", RailAction.ToPane(CaptainPane.DISPATCH)),
    RailItem(Icons.Rounded.Speed, "METER", RailAction.ToPane(if (hasActiveTrip) CaptainPane.METER else CaptainPane.DASHBOARD)),
    RailItem(Icons.Rounded.SsidChart, "EARNINGS", RailAction.ToPane(CaptainPane.EARNINGS)),
    RailItem(Icons.Rounded.History, "HISTORY", RailAction.ToPane(CaptainPane.SHIFT)),
    RailItem(Icons.Rounded.LocationOn, "ZONES", RailAction.ToPane(CaptainPane.ZONES)),
    RailItem(Icons.Rounded.Sell, "PRICING", RailAction.ToPane(CaptainPane.PRICING)),
    RailItem(Icons.Rounded.ConfirmationNumber, "VOUCHERS", RailAction.ToPane(CaptainPane.VOUCHERS)),
    RailItem(Icons.Rounded.Person, "DRIVER", RailAction.OpenProfile),
    RailItem(Icons.Rounded.SettingsSuggest, "SETTINGS", RailAction.OpenSettings),
    // Messages, Live map and Log off used to live only in the flyout that was deleted. The one
    // rail now carries every real destination (it scrolls), so nothing working is stranded.
    RailItem(Icons.Rounded.Mail, "MESSAGES", RailAction.ToPane(CaptainPane.MESSAGES)),
    RailItem(Icons.Rounded.Map, "MAP", RailAction.ToPane(CaptainPane.MAP)),
    RailItem(Icons.AutoMirrored.Rounded.Logout, "LOG OUT", RailAction.LogOff),
)

/**
 * [dispatchOfferCount] is the size of the live pending-offer list
 * ([au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsUiState.cards]) the caller already
 * collects for the Dashboard's LiveDispatchCard — the DISPATCH badge shows it while non-zero and
 * nothing otherwise. Never a static number.
 */
@Composable
private fun CaptainNavRail(
    pane: CaptainPane,
    hasActiveTrip: Boolean,
    dispatchOfferCount: Int,
    onSelectPane: (CaptainPane) -> Unit,
    onOpenVouchers: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun dispatch(action: RailAction) {
        when (action) {
            is RailAction.ToPane -> onSelectPane(action.pane)
            RailAction.OpenVouchers -> onOpenVouchers()
            RailAction.OpenProfile -> onOpenProfile()
            RailAction.OpenSettings -> onOpenSettings()
            RailAction.LogOff -> onLogOff()
        }
    }

    // DASHBOARD and METER both alias CaptainPane.DASHBOARD while no fare is open (see railItems'
    // own comment) — matching on `pane` alone would light up BOTH simultaneously, which is not
    // what the mockup shows (exactly one item highlighted at a time). Picking only the FIRST
    // item whose target matches resolves the alias in DASHBOARD's favour without separate
    // click-tracked selection state. Once a fare IS open, METER's own target becomes
    // CaptainPane.METER (distinct from DASHBOARD's), so both light up correctly on their own pane.
    val items = railItems(hasActiveTrip)
    val activeIndex = items.indexOfFirst { (it.action as? RailAction.ToPane)?.pane == pane }
    GlassCard(modifier = modifier.width(RAIL_WIDTH), cornerRadiusDp = 22) {
        // Scrollable — with 14 real destinations at a legible touch-target size the list runs
        // taller than the rail's real available height (measured live on the SM-T575: an
        // un-scrollable Column here silently clipped everything from HISTORY down).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp, horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items.forEachIndexed { index, item ->
                val target = (item.action as? RailAction.ToPane)?.pane
                RailTile(
                    item = item,
                    selected = index == activeIndex,
                    badge = if (target == CaptainPane.DISPATCH && dispatchOfferCount > 0) dispatchOfferCount else null,
                    // METER's target is CaptainPane.METER only while a fare is actually open.
                    live = hasActiveTrip && target == CaptainPane.METER,
                    onClick = { dispatch(item.action) },
                )
            }
        }
    }
}

/**
 * One rail tile — icon over a short uppercase label, a full 96x72dp touch target. [selected] lights
 * an accent fill + a breathing [neonGlow] halo (the mockup's active pill); [live] (METER while a
 * fare is open) breathes a green halo instead so the driver can see at a glance that a meter is
 * running from any pane; [badge] is the red offer-count dot on DISPATCH. Press feedback is the
 * same bouncy spring squash every tappable surface in this app uses.
 */
@Composable
private fun RailTile(item: RailItem, selected: Boolean, badge: Int?, live: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 900f),
        label = "rail-tile-press",
    )
    val fg by animateColorAsState(
        when {
            selected -> CaptainPalette.hudSweepMid
            live -> CaptainPalette.success
            else -> CaptainPalette.textSecondary
        },
        label = "rail-fg",
    )
    val fill by animateColorAsState(if (selected) CaptainPalette.hudAccent.copy(alpha = 0.24f) else Color.Transparent, label = "rail-fill")
    val breathe by rememberInfiniteFloat(enabled = selected || live, from = 0.45f, to = 1f, durationMs = 1300)
    val halo = when {
        selected -> CaptainPalette.hudAccent
        live -> CaptainPalette.success
        else -> null
    }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(72.dp)
            .scale(scale)
            .then(if (halo != null) Modifier.neonGlow(halo, 16.dp, strength = breathe) else Modifier)
            .clip(shape)
            .background(fill)
            .then(if (halo != null) Modifier.border(1.5.dp, halo.copy(alpha = 0.35f + 0.55f * breathe), shape) else Modifier)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Icon(item.icon, contentDescription = null, tint = fg, modifier = Modifier.size(26.dp))
                if (badge != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 11.dp, y = (-9).dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(CaptainPalette.danger)
                            .border(1.5.dp, CaptainPalette.hudBg, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (badge > 9) "9+" else badge.toString(),
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = CaptainPalette.textPrimary,
                        )
                    }
                }
            }
            Text(
                item.label,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                color = fg,
                maxLines = 1,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

// ============================================================================================
// Chrome previews (2026-09-04) — the header, the rail and the bottom bar on the HUD background at
// tablet width (SM-T575 landscape ≈ 1280dp), so the chrome can be reviewed without a device.
// Fixture data lives only in these previews; nothing below is reachable from the live screen.
// ============================================================================================

private fun previewState(available: Boolean = true) = WheelDashboardUiState(
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

@Preview(name = "Header — available", widthDp = 1280, heightDp = 140, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewCaptainHeaderAvailable() {
    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg)) {
        CaptainHeader(state = previewState(), verified = true, hasActiveTrip = false, onShowDriverId = {}, onOpenProfile = {}, onToggleAvailability = {}, onSos = {})
    }
}

@Preview(name = "Header — hired", widthDp = 1280, heightDp = 140, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewCaptainHeaderHired() {
    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg)) {
        CaptainHeader(state = previewState(), verified = true, hasActiveTrip = true, onShowDriverId = {}, onOpenProfile = {}, onToggleAvailability = {}, onSos = {})
    }
}

@Preview(name = "Header — off duty", widthDp = 1280, heightDp = 140, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewCaptainHeaderOffDuty() {
    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg)) {
        CaptainHeader(state = previewState(available = false), verified = null, hasActiveTrip = false, onShowDriverId = {}, onOpenProfile = {}, onToggleAvailability = {}, onSos = {})
    }
}

@Preview(name = "Rail — dispatch selected, 3 offers, meter live", widthDp = 160, heightDp = 760, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewCaptainNavRail() {
    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg).padding(12.dp)) {
        CaptainNavRail(
            pane = CaptainPane.DISPATCH,
            hasActiveTrip = true,
            dispatchOfferCount = 3,
            onSelectPane = {},
            onOpenVouchers = {},
            onOpenProfile = {},
            onOpenSettings = {},
            onLogOff = {},
            modifier = Modifier.fillMaxHeight(),
        )
    }
}

@Preview(name = "Bottom bar", widthDp = 1280, heightDp = 176, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewShiftStatsBar() {
    val state = previewState()
    Row(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg).padding(12.dp).height(152.dp)) {
        ShiftStatsBar(
            state = state,
            extras = HomeExtras(verified = true, earningsPctChange = 12.0, tripsActiveThisShift = 1, fatigueAlertCount = 1, latestFatigueKind = "shift_duration"),
            onTakeBreak = {},
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        Spacer(Modifier.width(16.dp))
        SystemStatusCard(state = state, modifier = Modifier.width(268.dp).fillMaxHeight())
    }
}

// ============================================================================================
// Small shared pieces
// ============================================================================================

@Composable
private fun VoucherInfoDialog(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CaptainPalette.panel)
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
                .clickable(enabled = false) {}
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Vouchers", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = CaptainPalette.textPrimary)
            // Updated for Phase G (`squishy-herding-iverson.md`): a real voucher-ledger browse
            // screen now exists (the nav rail's VOUCHERS item -> VouchersPaneContent), so this
            // quick-action tile's copy no longer claims "no voucher wallet at all" — it still
            // correctly says redemption itself only ever happens at Close & Pay, against the trip
            // being paid for, never from a standalone "apply" action anywhere in this app.
            Text(
                "Browse available/used/expired vouchers from the VOUCHERS tab in the side menu. A " +
                    "voucher code is redeemed at the end of the trip, during payment.",
                fontFamily = InterFamily,
                fontSize = 17.sp,
                color = CaptainPalette.textSecondary,
            )
            CaptainButton(text = "Got it", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

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
