package au.com.threesixty.cabdispatch.ui.screens.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.data.local.dao.TripPeriod
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripStatus
import au.com.threesixty.cabdispatch.domain.format.asLocalTime
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.domain.format.asPaymentMethodLabel
import au.com.threesixty.cabdispatch.domain.format.asTripTypeLabel
import au.com.threesixty.cabdispatch.domain.format.toBigDecimalOrZero
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.HudStatusPill
import au.com.threesixty.cabdispatch.ui.theme.HudTone
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RollingMoneyText
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.theme.neonGlow
import au.com.threesixty.cabdispatch.ui.theme.rememberInfiniteFloat
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.TRIPS] wheel-slot content, per design spec
 * TCT-DRIVER-APP-01.md §4 ("Trips: trip history rows — route, time, payment method, fare
 * amount"). Reused for BOTH the "My Trips" and "History" dock destinations (this app has one real
 * trips data source, not two — see [au.com.threesixty.cabdispatch.ui.screens.dashboard.HomeDashboardV2ChromeOverlay]'s
 * `dockTiles` doc for why), presented with two different layouts per [TripsPaneVariant]: the
 * focused active/recent cards + "OPEN ACTIVE TRIP" CTA ([TripsPaneVariant.MY_TRIPS]) and the
 * filterable history table ([TripsPaneVariant.HISTORY], what the rail's TRIPS entry opens).
 * [TripsWheelUiState.activeTrip] is real (backed by
 * [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeActiveTrip], the same query
 * [au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen] is keyed off), not fabricated —
 * see that field's own doc for the one honest caveat (re-opening `HIRED` on an already-open trip
 * with no fresh [au.com.threesixty.cabdispatch.domain.SessionHolder.pendingTrip] hand-off does not
 * resume the live fare-engine state; that gap pre-dates this pass and is a product decision, not
 * something this visual reskin can silently fix).
 *
 * **HUD kit rebuild (2026-09-04).** The previous premium pass restyled this screen with hand-rolled
 * surfaces (`CaptainPanel` rows with a status spine, a local `StatusChip`, a local copy of
 * `neonGlow`). It is now built from the shared kit in `ui/theme/Hud.kt` so it reads as one system
 * with the meter: the history table and its totals footer are [GlassCard]s, every status is a
 * [HudStatusPill], the period total is a [RollingMoneyText] (it rolls when the filter changes),
 * money/number cells use tabular figures, and the glow/press primitives are the shared
 * [neonGlow]/[gameClick] rather than file-local copies. **Strictly visual** — every value still
 * comes from exactly the same [TripEntity] field / [TripsWheelViewModel] state, the filter pills
 * still drive the same real date-range query, and no affordance was added or removed (the standing
 * no-decorative-controls rule). `PaneShell` supplies the "Trip history" title above this content.
 *
 * Verified (reconciliation pass): [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]
 * renders this composable for [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.TRIPS], wiring
 * [onTripClick] exactly as suggested below — see that screen's `TripsSlotContent`.
 */
enum class TripsPaneVariant { MY_TRIPS, HISTORY }

@Composable
fun TripsWheelContent(
    modifier: Modifier = Modifier,
    variant: TripsPaneVariant = TripsPaneVariant.MY_TRIPS,
    onTripClick: (clientUuid: String) -> Unit = {},
    onOpenActiveTrip: () -> Unit = {},
    onShiftReportClick: () -> Unit = {},
    viewModel: TripsWheelViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    when {
        variant == TripsPaneVariant.MY_TRIPS && state.loading ->
            Text("Loading trips…", fontFamily = InterFamily, color = CaptainPalette.textSecondary, fontSize = 16.sp)
        variant == TripsPaneVariant.MY_TRIPS && state.trips.isEmpty() && state.activeTrip == null ->
            Text("No trips yet.", fontFamily = InterFamily, color = CaptainPalette.textSecondary, fontSize = 16.sp)
        variant == TripsPaneVariant.MY_TRIPS -> MyTripsBody(
            modifier = modifier,
            activeTrip = state.activeTrip,
            recentTrips = state.trips,
            onTripClick = onTripClick,
            onOpenActiveTrip = onOpenActiveTrip,
        )
        state.historyLoading -> Text("Loading trips…", fontFamily = InterFamily, color = CaptainPalette.textSecondary, fontSize = 16.sp)
        else -> TripHistoryBody(
            modifier = modifier,
            trips = state.historyTrips,
            period = state.historyPeriod,
            onPeriodChange = viewModel::setHistoryPeriod,
            onTripClick = onTripClick,
            onShiftReportClick = onShiftReportClick,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Shared typography — tabular figures so every money/number column lines up digit-for-digit.
// ---------------------------------------------------------------------------------------------

private val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

private val HeaderLabelStyle = TextStyle(
    fontFamily = InterFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 11.sp,
    letterSpacing = 1.sp,
    color = CaptainPalette.textMuted,
)

// ---------------------------------------------------------------------------------------------
// My Trips variant — focused ACTIVE + recent DONE cards, "OPEN ACTIVE TRIP" CTA.
// ---------------------------------------------------------------------------------------------

@Composable
private fun MyTripsBody(
    activeTrip: TripEntity?,
    recentTrips: List<TripEntity>,
    onTripClick: (String) -> Unit,
    onOpenActiveTrip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (activeTrip != null) {
                item(key = "active-${activeTrip.clientUuid}") {
                    MyTripRow(trip = activeTrip, active = true) { onTripClick(activeTrip.clientUuid) }
                }
            }
            items(recentTrips.take(6), key = { it.clientUuid }) { trip ->
                MyTripRow(trip = trip, active = false) { onTripClick(trip.clientUuid) }
            }
        }
        if (activeTrip != null) {
            Spacer(Modifier.height(14.dp))
            CaptainButton(
                text = "OPEN ACTIVE TRIP",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 16.sp,
                onClick = onOpenActiveTrip,
            )
        }
    }
}

/**
 * One My-Trips card: a [GlassCard] with time · type · [HudStatusPill] · fare. Only a genuinely
 * live row breathes (a [rememberInfiniteFloat]-driven [neonGlow] in the warning tone) — a
 * finished row is static, so the motion carries state rather than decorating.
 */
@Composable
private fun MyTripRow(trip: TripEntity, active: Boolean, onClick: () -> Unit) {
    val accent = if (active) CaptainPalette.warning else CaptainPalette.success
    val breath by rememberInfiniteFloat(enabled = active, from = 0.25f, to = 0.75f, durationMs = 1600)
    val shape = RoundedCornerShape(16.dp)

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (active) Modifier.neonGlow(accent, 16.dp, strength = breath, spread = 4.dp) else Modifier)
            .gameClick(onClick = onClick, shape = shape, glowColor = accent),
        cornerRadiusDp = 16,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                trip.startAt.asLocalTime(),
                color = CaptainPalette.textPrimary,
                fontWeight = FontWeight.Bold,
                fontFamily = ChakraPetch,
                fontSize = 17.sp,
                style = TabularFigures,
            )
            Text(
                trip.type.asTripTypeLabel(),
                color = CaptainPalette.textPrimary,
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            HudStatusPill(
                label = trip.paymentMethod.asPaymentMethodLabel(),
                value = if (active) "Active" else "Done",
                tone = if (active) HudTone.Warning else HudTone.Success,
                pulsing = active,
            )
            Text(
                if (active) "${trip.deviceTotal.toBigDecimalOrZero().asMoney()} est" else trip.deviceTotal.toBigDecimalOrZero().asMoney(),
                color = if (active) CaptainPalette.warning else CaptainPalette.textPrimary,
                fontWeight = FontWeight.Bold,
                fontFamily = ChakraPetch,
                fontSize = 18.sp,
                style = TabularFigures,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Trip History variant — filter pills + date readout + the history TABLE + shift-report footer.
//
// Filter pills are real (Phase C, 2026-09-03): [TripPeriod] (shared with the Earnings pane's
// period tabs, see [au.com.threesixty.cabdispatch.data.local.dao.TripDao]) drives a genuine
// [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeTripsInRange] query per pill.
//
// Table columns are TIME · PICKUP · DROPOFF · DISTANCE · DURATION · FARE · STATUS, per the mockup.
// Distance/duration/status are plain arithmetic over [TripEntity.distanceM]/`.movingS`+`.waitingS`/
// `.status` — already-persisted fields, no new plumbing. Pickup/dropoff read
// [TripEntity.pickupAddress]/`.dropoffAddress` — "—" for a `null` value (a trip with no
// dispatch-offer address to carry — most rank/hail trips), never a fabricated address.
// ---------------------------------------------------------------------------------------------

@Composable
private fun TripHistoryBody(
    trips: List<TripEntity>,
    period: TripPeriod,
    onPeriodChange: (TripPeriod) -> Unit,
    onTripClick: (String) -> Unit,
    onShiftReportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                TripPeriod.entries.forEach { p ->
                    HistoryFilterPill(label = p.label, selected = p == period, onClick = { onPeriodChange(p) })
                }
            }
            PeriodDateReadout(period)
        }
        Spacer(Modifier.height(14.dp))
        HistoryTable(trips = trips, onTripClick = onTripClick)
        Spacer(Modifier.height(14.dp))
        HistoryTotalsBar(
            period = period,
            tripCount = trips.size,
            total = trips.fold(BigDecimal.ZERO) { acc, t -> acc + t.deviceTotal.toBigDecimalOrZero() },
            onShiftReportClick = onShiftReportClick,
        )
    }
}

/**
 * The mockup's date readout beside the filter pills — the calendar window the selected
 * [TripPeriod] actually queries, computed by the same device-local calendar rule as
 * [TripPeriod.startEpochMillis] (Monday-start week, first-of-month), ending today. Not a date
 * picker: it is a label for the real range, and "All time" for [TripPeriod.ALL].
 */
@Composable
private fun PeriodDateReadout(period: TripPeriod) {
    val readout = remember(period) { period.rangeReadout() }
    Text(
        readout,
        fontFamily = ChakraPetch,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        color = CaptainPalette.textSecondary,
        style = TabularFigures,
        maxLines = 1,
        modifier = Modifier.padding(start = 16.dp),
    )
}

private val DAY_READOUT = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val SHORT_DAY_READOUT = DateTimeFormatter.ofPattern("EEE d MMM")
private val MONTH_READOUT = DateTimeFormatter.ofPattern("MMMM yyyy")

private fun TripPeriod.rangeReadout(today: LocalDate = LocalDate.now()): String = when (this) {
    TripPeriod.ALL -> "All time"
    TripPeriod.TODAY -> today.format(DAY_READOUT)
    TripPeriod.WEEK -> "${today.with(DayOfWeek.MONDAY).format(SHORT_DAY_READOUT)} – ${today.format(DAY_READOUT)}"
    TripPeriod.MONTH -> today.format(MONTH_READOUT)
}

// Column weights — one place, shared by the header row and every data row so they line up.
private const val W_TIME = 0.9f
private const val W_ADDRESS = 1.7f
private const val W_DISTANCE = 0.8f
private const val W_DURATION = 0.8f
private const val W_FARE = 0.9f
private const val W_STATUS = 1.6f

/**
 * The history table: one [GlassCard] holding a real header row (muted upper-case column labels on
 * a purple rule) and a lazy list of rows with zebra fills and hairline rules between them. Row
 * geometry (weights, padding) is shared with the header through the `W_*` constants above so the
 * columns genuinely align rather than approximately.
 */
@Composable
private fun HistoryTable(trips: List<TripEntity>, onTripClick: (String) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("TIME", style = HeaderLabelStyle, modifier = Modifier.weight(W_TIME))
                Text("PICKUP", style = HeaderLabelStyle, modifier = Modifier.weight(W_ADDRESS))
                Text("DROPOFF", style = HeaderLabelStyle, modifier = Modifier.weight(W_ADDRESS))
                Text("DISTANCE", style = HeaderLabelStyle, modifier = Modifier.weight(W_DISTANCE))
                Text("DURATION", style = HeaderLabelStyle, modifier = Modifier.weight(W_DURATION))
                Text("FARE", style = HeaderLabelStyle, textAlign = TextAlign.End, modifier = Modifier.weight(W_FARE))
                Text("STATUS", style = HeaderLabelStyle, modifier = Modifier.weight(W_STATUS))
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.hudGlassBorderPurple))
            if (trips.isEmpty()) {
                Text(
                    "No trips in this period.",
                    fontFamily = InterFamily,
                    color = CaptainPalette.textSecondary,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 28.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(trips, key = { _, t -> t.clientUuid }) { index, trip ->
                        TripHistoryRow(trip = trip, zebra = index % 2 == 1, last = index == trips.lastIndex) {
                            onTripClick(trip.clientUuid)
                        }
                    }
                }
            }
        }
    }
}

/** "3.2 km" from [TripEntity.distanceM]. */
private fun Int.asKm(): String =
    BigDecimal(this).divide(BigDecimal(1000), 1, RoundingMode.HALF_UP).toPlainString() + " km"

/** "12 min" from cumulative [TripEntity.movingS] + [TripEntity.waitingS]. */
private fun asDuration(movingS: Int, waitingS: Int): String {
    val totalMin = (movingS + waitingS) / 60
    return if (totalMin < 1) "<1 min" else "$totalMin min"
}

/** [TripEntity.status] -> display label ("open"/"closed"/"synced", see [TripStatus]). */
private fun String.asTripStatusLabel(): String = when (this) {
    TripStatus.OPEN -> "In Progress"
    TripStatus.CLOSED -> "Completed"
    TripStatus.SYNCED -> "Synced"
    else -> this.replaceFirstChar { it.uppercase() }
}

private fun String.asTripStatusTone(): HudTone = when (this) {
    TripStatus.OPEN -> HudTone.Warning
    TripStatus.CLOSED -> HudTone.Success
    TripStatus.SYNCED -> HudTone.Accent
    else -> HudTone.Neutral
}

/**
 * Splits the one address string a trip carries into the mockup's two lines: the part before the
 * first comma (street) and everything after it (suburb / locality). No geocoding, no lookup —
 * if the carried string has no comma there is no second line, and a `null` address is a single
 * honest "—". Never a fabricated suburb.
 */
private fun String?.asAddressLines(): Pair<String, String?> {
    if (this.isNullOrBlank()) return "—" to null
    val comma = indexOf(',')
    if (comma < 0) return trim() to null
    val street = substring(0, comma).trim()
    val rest = substring(comma + 1).trim().takeIf { it.isNotEmpty() }
    return (street.ifEmpty { "—" }) to rest
}

@Composable
private fun TripHistoryRow(trip: TripEntity, zebra: Boolean, last: Boolean, onClick: () -> Unit) {
    val live = trip.status == TripStatus.OPEN
    val tone = trip.status.asTripStatusTone()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (zebra) Modifier.background(CaptainPalette.raised.copy(alpha = 0.45f)) else Modifier)
                .gameClick(onClick = onClick, shape = RoundedCornerShape(0.dp), glowColor = CaptainPalette.hudAccent, pressScale = 0.985f)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                trip.startAt.asLocalTime(),
                color = CaptainPalette.textPrimary,
                fontWeight = FontWeight.Bold,
                fontFamily = ChakraPetch,
                fontSize = 16.sp,
                style = TabularFigures,
                maxLines = 1,
                modifier = Modifier.weight(W_TIME),
            )
            AddressCell(trip.pickupAddress, modifier = Modifier.weight(W_ADDRESS))
            AddressCell(trip.dropoffAddress, modifier = Modifier.weight(W_ADDRESS))
            NumberCell(trip.distanceM.asKm(), modifier = Modifier.weight(W_DISTANCE))
            NumberCell(asDuration(trip.movingS, trip.waitingS), modifier = Modifier.weight(W_DURATION))
            Text(
                trip.deviceTotal.toBigDecimalOrZero().asMoney(),
                color = CaptainPalette.textPrimary,
                fontWeight = FontWeight.Bold,
                fontFamily = ChakraPetch,
                fontSize = 18.sp,
                style = TabularFigures,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.weight(W_FARE),
            )
            Box(modifier = Modifier.weight(W_STATUS), contentAlignment = Alignment.CenterStart) {
                // The pill's muted label carries the payment method (spec §4 asks for it on every
                // history row; the mockup has no separate column for it) and its toned value is
                // the status — "CARD · Completed". Only a genuinely open trip pulses.
                HudStatusPill(
                    label = trip.paymentMethod.asPaymentMethodLabel(),
                    value = trip.status.asTripStatusLabel(),
                    tone = tone,
                    pulsing = live,
                )
            }
        }
        if (!last) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.hudTrack))
        }
    }
}

/** Two-line pickup/dropoff cell — street on top, suburb beneath when the address carries one. */
@Composable
private fun AddressCell(address: String?, modifier: Modifier = Modifier) {
    val (street, suburb) = remember(address) { address.asAddressLines() }
    Column(modifier = modifier) {
        Text(
            street,
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = if (address.isNullOrBlank()) CaptainPalette.textMuted else CaptainPalette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (suburb != null) {
            Text(
                suburb,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = CaptainPalette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

@Composable
private fun NumberCell(value: String, modifier: Modifier = Modifier) {
    Text(
        value,
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        color = CaptainPalette.textSecondary,
        style = TabularFigures,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * Footer summary + the real SHIFT REPORT action. Exactly the same three values as before
 * ([TripPeriod.label], the row count, and the summed [TripEntity.deviceTotal]) on a [GlassCard];
 * the total is a [RollingMoneyText] so switching filters visibly rolls the figure to the new
 * period's sum rather than snapping.
 */
@Composable
private fun HistoryTotalsBar(
    period: TripPeriod,
    tripCount: Int,
    total: BigDecimal,
    onShiftReportClick: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 16) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${period.label.uppercase()} · $tripCount TRIPS", style = HeaderLabelStyle)
                RollingMoneyText(amount = total.asMoney(), fontSize = 28.sp, modifier = Modifier.padding(top = 2.dp))
            }
            ShiftReportButton(onClick = onShiftReportClick)
        }
    }
}

/** The existing SHIFT REPORT action, in the kit's glow language: a hudAccent halo + gradient-border glass button. */
@Composable
private fun ShiftReportButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .height(56.dp)
            .neonGlow(CaptainPalette.hudAccent, 14.dp, strength = 0.55f, spread = 4.dp)
            .clip(shape)
            .background(CaptainPalette.hudAccent.copy(alpha = 0.18f))
            .border(1.dp, CaptainPalette.hudSweepMid.copy(alpha = 0.7f), shape)
            .gameClick(onClick = onClick, shape = shape, glowColor = CaptainPalette.hudSweepMid)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "SHIFT REPORT",
            color = CaptainPalette.hudSweepMid,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            letterSpacing = 1.sp,
        )
    }
}

/**
 * Period filter pill. Geometry/typography deliberately identical to `ZonesTabPill`
 * (`ui/screens/zones/ZonesPaneContent.kt`) and `VoucherTabPill`
 * (`ui/screens/vouchers/VouchersPaneContent.kt`) — 44dp tall, fully-round, 16dp horizontal
 * padding, 13sp bold uppercase — so all the tab rows in the app are one control. The selected
 * pill glows in the HUD accent via the shared [neonGlow]; the press feel is [gameClick].
 */
@Composable
private fun HistoryFilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    val bg = if (selected) CaptainPalette.hudAccent else CaptainPalette.hudGlass
    val textColor = if (selected) CaptainPalette.textPrimary else CaptainPalette.textSecondary
    Box(
        modifier = Modifier
            .height(44.dp)
            .then(if (selected) Modifier.neonGlow(CaptainPalette.hudAccent, 999.dp, strength = 0.8f, spread = 4.dp) else Modifier)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) CaptainPalette.hudSweepMid else CaptainPalette.hudGlassBorderPurple, shape)
            .gameClick(onClick = onClick, shape = shape, glowColor = CaptainPalette.hudSweepMid)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), color = textColor, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------------------------------------
// Preview — PREVIEW-ONLY fake rows. Nothing here is read by the app; the live screen is fed by
// TripsWheelViewModel. Addresses/fares below are invented purely to exercise the table layout
// (two-line addresses, a "—" address-less rank trip, an in-progress row).
// ---------------------------------------------------------------------------------------------

private fun previewTrip(
    uuid: String,
    startAt: String,
    status: String,
    total: String,
    pickup: String?,
    dropoff: String?,
    distanceM: Int,
    movingS: Int,
    payment: String = "card",
): TripEntity = TripEntity(
    clientUuid = uuid,
    vehicleId = "preview-vehicle",
    driverId = "preview-driver",
    shiftId = null,
    tariffId = "preview-tariff",
    type = if (pickup == null) "rank_hail" else "booked",
    status = status,
    timeClass = "day",
    isPeak = false,
    maxi = false,
    startAt = startAt,
    startLat = 0.0,
    startLng = 0.0,
    distanceM = distanceM,
    movingS = movingS,
    waitingS = 90,
    paymentMethod = payment,
    pickupAddress = pickup,
    dropoffAddress = dropoff,
    deviceTotal = total,
    createdAt = 0L,
    updatedAt = 0L,
)

@Preview(widthDp = 1000, heightDp = 620, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewTripHistory() {
    val trips = listOf(
        previewTrip("p1", "2026-09-04T03:12:00Z", TripStatus.OPEN, "18.40", "12 Bay St, Glebe NSW", "Central Station, Haymarket NSW", 3200, 540),
        previewTrip("p2", "2026-09-04T01:48:00Z", TripStatus.CLOSED, "42.15", "1 Macquarie St, Sydney NSW", "Sydney Airport T2, Mascot NSW", 12400, 1500, "cash"),
        previewTrip("p3", "2026-09-04T00:30:00Z", TripStatus.CLOSED, "23.90", null, null, 6100, 780),
        previewTrip("p4", "2026-09-03T22:05:00Z", TripStatus.SYNCED, "15.60", "8 Oxford St, Paddington NSW", "Bondi Beach, Bondi NSW", 4800, 660, "account"),
    )
    Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        TripHistoryBody(
            trips = trips,
            period = TripPeriod.TODAY,
            onPeriodChange = {},
            onTripClick = {},
            onShiftReportClick = {},
        )
    }
}
