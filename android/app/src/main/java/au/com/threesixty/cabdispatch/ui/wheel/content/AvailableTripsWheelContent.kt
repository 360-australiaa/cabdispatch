package au.com.threesixty.cabdispatch.ui.wheel.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Place
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.remote.JobDto
import au.com.threesixty.cabdispatch.data.remote.JobOfferDto
import au.com.threesixty.cabdispatch.data.remote.MapboxStaticImage
import au.com.threesixty.cabdispatch.domain.JobOfferHandoff
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.domain.format.toBigDecimalOrZero
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.GlowingMeterGauge
import au.com.threesixty.cabdispatch.ui.theme.HudStatusPill
import au.com.threesixty.cabdispatch.ui.theme.HudTone
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RollingMoneyText
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.theme.neonGlow
import au.com.threesixty.cabdispatch.ui.theme.rememberInfiniteFloat
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Wheel slot 1 — "Available Trips" / live dispatch content pane (spec §4: "list of job cards —
 * route, distance, estimated fare, time since requested"; §8 row 11 "Job queue list").
 *
 * **HUD kit rebuild (2026-09-04) — the mockup's "FARE PREVIEW" dispatch offer.** Each live offer
 * is one [GlassCard] built from the shared kit in `ui/theme/Hud.kt`, replacing the previous
 * hand-rolled gradient `CaptainPanel` + drain bar + file-local `neonGlow` copy:
 * - ESTIMATED FARE as a big [RollingMoneyText] (the server's low–high range, both ends rolling);
 * - the itemised estimate — **only the lines the offer actually carries** ([JobDto.distanceKm],
 *   [JobDto.etaMin], [JobDto.jobType]). The mockup's base / distance / time / night / airport /
 *   levy money lines do not exist on a [JobDto] (the backend sends a single low–high range, not a
 *   breakdown), so they are not shown — a per-line dollar figure here would be fabricated;
 * - a mini map preview centred on the pickup ([JobDto.originLat]/`originLng`) via the app's
 *   existing Mapbox Static Images approach ([MapboxStaticImage] + Coil, the same call the Home
 *   dashboard's map pane makes) — a real map tile, no `MapView`, and an honest "unavailable"
 *   placeholder (not a decorative grid) when the image can't load;
 * - PICKUP / DROP OFF address cards;
 * - the real countdown as an urgency ring — [GlowingMeterGauge] driven by
 *   `secondsLeft / offerWindow`, both read off the `offered_at`/`expires_at` pair the card already
 *   carries — with the seconds readout escalating green → amber → red;
 * - a glowing primary **ACCEPT JOB** and a quiet DECLINE outline.
 * PAYMENT METHOD is not shown: [JobDto] carries no payment method field. "Distance to pickup"
 * (spec §4's third column) is still omitted: it needs the driver's live position, and GPS is
 * stubbed project-wide (`HANDOFF.md`) — the distance line shown is the job's own pickup→drop
 * estimate, labelled as such.
 *
 * Strictly visual: the offer window, accept/decline calls, expiry rule, tap-through-to-detail and
 * every displayed value are exactly what they were ([AvailableTripsWheelViewModel] unchanged).
 *
 * Accept/Decline are inline on each card (not only in the tap-through detail screen,
 * [au.com.threesixty.cabdispatch.ui.screens.availabletrips.AvailableTripOfferScreen]): offers
 * expire ~20s after being sent (see [au.com.threesixty.cabdispatch.domain.JobsRepository] doc), so
 * a "tap card, then tap accept" two-step would burn meaningfully into that window on a slow
 * network — the fast path has to be one tap from the list itself.
 *
 * Verified (reconciliation pass): [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]
 * embeds this composable directly with the shared [NavHostController] for the
 * [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.AVAILABLE_TRIPS] slot — see that screen's
 * `AvailableTripsSlotContent`; `DeckHomeScreen`'s DISPATCH pane does the same inside `PaneShell`.
 */
@Composable
fun AvailableTripsWheelContent(
    navController: NavHostController,
    viewModel: AvailableTripsWheelViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // One-shot: navigate to S3 the instant an accept lands, then let the ViewModel clear the
    // flag — see AvailableTripsUiState.navigateToHired's doc for why this shape.
    LaunchedEffect(state.navigateToHired) {
        if (state.navigateToHired) {
            navController.navigate(CabDispatchRoutes.HIRED)
            viewModel.onNavigatedToHired()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        val actionError = state.actionError
        if (actionError != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = CaptainPalette.danger, modifier = Modifier.size(16.dp))
                Text(
                    actionError,
                    color = CaptainPalette.danger,
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }

        val error = state.error
        when {
            state.loading && state.cards.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = CaptainPalette.hudAccent)
            }
            error != null -> Text(error, color = CaptainPalette.danger, fontFamily = InterFamily, fontSize = 15.sp)
            state.cards.isEmpty() -> EmptyOfferState()
            else -> LazyColumn(
                // Bounded on purpose: WheelDashboardScreen hosts this inside a verticalScroll.
                modifier = Modifier.heightIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.cards, key = { it.offer.id }) { card ->
                    JobOfferCard(
                        card = card,
                        busy = state.busyOfferId == card.offer.id,
                        onOpenDetail = {
                            JobOfferHandoff.set(card.job, card.offer)
                            navController.navigate(CabDispatchRoutes.AVAILABLE_TRIP_OFFER)
                        },
                        onAccept = { viewModel.acceptOffer(card) },
                        onDecline = { viewModel.declineOffer(card) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared typography
// ---------------------------------------------------------------------------------------------

private val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

private val EyebrowStyle = TextStyle(
    fontFamily = InterFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 11.sp,
    letterSpacing = 1.5.sp,
    color = CaptainPalette.textMuted,
)

/**
 * The "waiting for work" state on a [GlassCard] — an honest "No live offers right now" plus a
 * slow breathing radar dot so an idle-but-listening pane doesn't read as a broken/blank one. The
 * dot's motion says "still listening"; it is not a claim about any offer.
 */
@Composable
private fun EmptyOfferState() {
    val breath by rememberInfiniteFloat(enabled = true, from = 0.2f, to = 0.6f, durationMs = 2200)
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .neonGlow(CaptainPalette.hudAccent, 99.dp, strength = breath, spread = 5.dp)
                    .clip(CircleShape)
                    .background(CaptainPalette.hudAccent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Inbox, contentDescription = null, tint = CaptainPalette.hudSweepMid, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    "No live offers right now",
                    color = CaptainPalette.textPrimary,
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
                Text(
                    "New offers appear here automatically.",
                    color = CaptainPalette.textSecondary,
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Urgency tier for one live offer. Deliberately a *tier*, not a continuous value: the card's
 * breathing glow keys its period off this, and a period that changed every tick would restart the
 * infinite transition once a second. Thresholds keep the original "<= 8s is red" rule as
 * [CRITICAL] with one intermediate step.
 */
private enum class OfferUrgency(val color: Color, val pulseMs: Int) {
    CALM(CaptainPalette.success, 1500),
    WARN(CaptainPalette.warning, 900),
    CRITICAL(CaptainPalette.danger, 460),
    GONE(CaptainPalette.textMuted, 0),
}

private fun urgencyOf(secondsLeft: Long?): OfferUrgency = when {
    secondsLeft == null -> OfferUrgency.CALM
    secondsLeft <= 0L -> OfferUrgency.GONE
    secondsLeft <= 8L -> OfferUrgency.CRITICAL
    secondsLeft <= 14L -> OfferUrgency.WARN
    else -> OfferUrgency.CALM
}

/** Corner radius shared by the offer card and its press shape. */
private const val OFFER_CARD_RADIUS = 20

@Composable
private fun JobOfferCard(
    card: AvailableTripCard,
    busy: Boolean,
    onOpenDetail: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val secondsLeft by rememberOfferCountdown(card.offer.expiresAt)
    val expired = (secondsLeft ?: 1L) <= 0L
    val urgency = urgencyOf(secondsLeft)
    val live = !expired
    val breath by rememberInfiniteFloat(
        enabled = live && urgency != OfferUrgency.GONE,
        from = if (urgency == OfferUrgency.CRITICAL) 0.45f else 0.25f,
        to = 1f,
        durationMs = if (urgency.pulseMs > 0) urgency.pulseMs else 1500,
    )
    // Fraction of the offer's own window still remaining. Both ends come off the offer the card
    // already holds (`offered_at` / `expires_at`) — no new data, just a second reading of the same
    // countdown the label shows. Unparseable timestamps => no ring (never a guessed one).
    val window = remember(card.offer.offeredAt, card.offer.expiresAt) {
        offerWindowSeconds(card.offer.offeredAt, card.offer.expiresAt)
    }
    val remainingFraction = if (window != null && secondsLeft != null) {
        (secondsLeft!!.toFloat() / window.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    val shape = RoundedCornerShape(OFFER_CARD_RADIUS.dp)

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (live) Modifier.neonGlow(urgency.color, OFFER_CARD_RADIUS.dp, strength = breath, spread = 6.dp) else Modifier)
            .gameClick(onClick = onOpenDetail, shape = shape, glowColor = urgency.color, enabled = !expired, pressScale = 0.985f),
        cornerRadiusDp = OFFER_CARD_RADIUS,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            // --- header: FARE PREVIEW · #ref · requested · countdown ring ------------------------
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("FARE PREVIEW", style = EyebrowStyle)
                        Text(
                            "#${card.job.id.takeLast(4)}",
                            color = CaptainPalette.textMuted,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }
                    HudStatusPill(
                        label = "Requested",
                        value = formatOfferRelativeTime(card.offer.offeredAt),
                        tone = HudTone.Neutral,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                OfferCountdownRing(secondsLeft = secondsLeft, remainingFraction = remainingFraction, urgency = urgency)
            }

            Spacer(Modifier.height(16.dp))

            // --- body: fare + itemised estimate | map preview + addresses -----------------------
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FareEstimateColumn(job = card.job, modifier = Modifier.weight(1f).fillMaxHeight())
                Column(modifier = Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (card.job.originLat != 0.0 || card.job.originLng != 0.0) {
                        OfferMapPreview(
                            lat = card.job.originLat,
                            lng = card.job.originLng,
                            modifier = Modifier.fillMaxWidth().height(132.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AddressCard(
                            icon = Icons.Rounded.Place,
                            label = "Pickup",
                            address = card.job.originAddress,
                            tint = CaptainPalette.hudSweepMid,
                            modifier = Modifier.weight(1f),
                        )
                        AddressCard(
                            icon = Icons.Rounded.Flag,
                            label = "Drop off",
                            address = card.job.destAddress,
                            tint = CaptainPalette.success,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // --- actions -----------------------------------------------------------------------
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallOutlineButton(label = "Decline", enabled = !busy && !expired, onClick = onDecline)
                AcceptJobButton(
                    busy = busy,
                    enabled = !busy && !expired,
                    pulse = breath,
                    modifier = Modifier.weight(1f),
                    onClick = onAccept,
                )
            }
        }
    }
}

/**
 * ESTIMATED FARE (the server's low–high range as rolling money) and the itemised lines the offer
 * genuinely carries. See [AvailableTripsWheelContent]'s class doc for why there are no per-line
 * dollar amounts: a [JobDto] has no fare breakdown, only the range.
 */
@Composable
private fun FareEstimateColumn(job: JobDto, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text("ESTIMATED FARE", style = EyebrowStyle)
        val low = job.fareEstimateLow.toBigDecimalOrZero()
        val high = job.fareEstimateHigh.toBigDecimalOrZero()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            RollingMoneyText(amount = low.asMoney(), fontSize = 34.sp, color = CaptainPalette.warning)
            if (high.compareTo(low) != 0) {
                Text(
                    "–",
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    color = CaptainPalette.textMuted,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                RollingMoneyText(amount = high.asMoney(), fontSize = 34.sp, color = CaptainPalette.warning)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.hudTrack))
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Server-computed at job creation (straight-line + flat-speed heuristic per the
            // backend's own doc) — labelled "est." for that reason; `null` on older jobs => no row.
            job.distanceKm?.let { EstimateRow("Trip distance (est.)", "$it km") }
            job.etaMin?.let { EstimateRow("Travel time (est.)", "$it min") }
            job.jobType?.let { EstimateRow("Job type", it.asJobTypeLabel()) }
        }
    }
}

private fun String.asJobTypeLabel(): String = when (this) {
    "booked" -> "Booked"
    "rank_hail" -> "Rank / hail"
    else -> replaceFirstChar { it.uppercase() }
}

@Composable
private fun EstimateRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = CaptainPalette.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = CaptainPalette.textPrimary,
            style = TabularFigures,
        )
    }
}

/**
 * The countdown as the kit's gauge: a full-circle [GlowingMeterGauge] whose progress is the
 * fraction of the offer window still remaining, with the whole-seconds readout (the same single
 * number the previous "expires 12s" label showed — [rememberOfferCountdown] over the offer's own
 * `expires_at`) in the urgency colour at its centre. With an unparseable window there is no ring,
 * only the seconds; with no parseable expiry there is nothing.
 */
@Composable
private fun OfferCountdownRing(secondsLeft: Long?, remainingFraction: Float?, urgency: OfferUrgency) {
    if (secondsLeft == null) return
    val readout: @Composable () -> Unit = {
        if (urgency == OfferUrgency.GONE) {
            Text(
                "EXPIRED",
                color = CaptainPalette.textMuted,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.6.sp,
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    secondsLeft.toString(),
                    color = urgency.color,
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    style = TabularFigures,
                )
                Text(
                    "SEC",
                    color = CaptainPalette.textMuted,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
    if (remainingFraction != null) {
        GlowingMeterGauge(
            progress = remainingFraction,
            modifier = Modifier.size(96.dp),
            strokeWidthDp = 8,
            sweepDeg = 360f,
            startDeg = -90f,
        ) { readout() }
    } else {
        Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) { readout() }
    }
}

/**
 * Mini map preview centred on the pickup — the app's existing Mapbox Static Images approach
 * ([MapboxStaticImage] + Coil `SubcomposeAsyncImage`, exactly as the Home dashboard's map pane
 * loads its background), sized to the box's real pixel size so the tile isn't upscaled. No
 * `MapView` (see that helper's doc for why the full SDK isn't used here). While the image is
 * loading or if it fails, the box says so — it never draws a decorative stand-in street grid.
 */
@Composable
private fun OfferMapPreview(lat: Double, lng: Double, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(CaptainPalette.hudTrack)
            .border(1.dp, Brush.linearGradient(listOf(CaptainPalette.hudGlassBorderPurple, CaptainPalette.hudGlassBorderWhite)), shape)
            .onGloballyPositioned { sizePx = it.size },
        contentAlignment = Alignment.Center,
    ) {
        if (sizePx.width > 0 && sizePx.height > 0) {
            val mapUrl = remember(sizePx, lat, lng) {
                MapboxStaticImage.url(
                    centerLat = lat,
                    centerLng = lng,
                    zoom = OFFER_MAP_ZOOM,
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
                    is AsyncImagePainter.State.Loading -> MapPreviewNote("Loading map…")
                    else -> MapPreviewNote("Map preview unavailable")
                }
            }
        } else {
            MapPreviewNote("Loading map…")
        }
        // Pickup marker — the static image is centred on the pickup coordinate, so the centre of
        // the box IS the pickup; a glowing accent dot marks it.
        Box(
            modifier = Modifier
                .size(14.dp)
                .neonGlow(CaptainPalette.hudAccent, 99.dp, strength = 0.9f, spread = 3.dp)
                .clip(CircleShape)
                .background(CaptainPalette.hudSweepMid)
                .border(2.dp, CaptainPalette.textPrimary, CircleShape),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CaptainPalette.hudGlass)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text("PICKUP AREA", style = EyebrowStyle)
        }
    }
}

/** Street-level framing for a pickup preview. */
private const val OFFER_MAP_ZOOM = 14.0

@Composable
private fun MapPreviewNote(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = CaptainPalette.textMuted)
    }
}

/** PICKUP / DROP OFF address card — a small [GlassCard] with a tinted icon, eyebrow and the address. */
@Composable
private fun AddressCard(icon: ImageVector, label: String, address: String, tint: Color, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, cornerRadiusDp = 14) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                Text(label.uppercase(), style = EyebrowStyle)
            }
            Text(
                address,
                color = CaptainPalette.textPrimary,
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SmallOutlineButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .height(64.dp)
            .clip(shape)
            .border(1.5.dp, CaptainPalette.hudGlassBorderPurple, shape)
            .gameClick(onClick = onClick, shape = shape, glowColor = CaptainPalette.danger, enabled = enabled)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Close, contentDescription = null, tint = CaptainPalette.textSecondary.copy(alpha = alpha), modifier = Modifier.size(18.dp))
        Text(
            label,
            color = CaptainPalette.textSecondary.copy(alpha = alpha),
            fontFamily = InterFamily,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * The primary target: ACCEPT JOB. Widest control on the card, 64dp tall, gradient-filled and
 * wearing a live green [neonGlow] that breathes in time with the card's urgency — so "accept" is
 * unmistakably the thing to hit and DECLINE reads as the quiet alternative. Disabled/expired
 * collapses to the flat track fill with no glow, so a dead offer never looks pressable.
 */
@Composable
private fun AcceptJobButton(
    busy: Boolean,
    enabled: Boolean,
    pulse: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .height(64.dp)
            .then(if (enabled) Modifier.neonGlow(CaptainPalette.success, 16.dp, strength = 0.35f + 0.65f * pulse, spread = 6.dp) else Modifier)
            .clip(shape)
            .then(
                if (enabled) {
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(CaptainPalette.success, CaptainPalette.success.copy(alpha = 0.78f)),
                        ),
                    )
                } else {
                    Modifier.background(CaptainPalette.hudTrack)
                },
            )
            .gameClick(onClick = onClick, shape = shape, glowColor = CaptainPalette.success, enabled = enabled)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CaptainPalette.hudBg)
        } else {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = if (enabled) CaptainPalette.hudBg else CaptainPalette.textMuted,
                modifier = Modifier.size(22.dp),
            )
            Text(
                "ACCEPT JOB",
                color = if (enabled) CaptainPalette.hudBg else CaptainPalette.textMuted,
                fontFamily = InterFamily,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * Whole seconds an offer's window lasts (`expires_at` - `offered_at`), or `null` when either
 * timestamp doesn't parse. Local to this file rather than added to `AvailableTripsFormat.kt`
 * (whose own parser is private) — it exists purely so the countdown can also be drawn as a
 * fraction, and it reads only fields the card already carries.
 */
private fun offerWindowSeconds(offeredAt: String, expiresAt: String): Long? {
    val start = parseOfferTimestamp(offeredAt) ?: return null
    val end = parseOfferTimestamp(expiresAt) ?: return null
    return Duration.between(start, end).seconds.takeIf { it > 0L }
}

private fun parseOfferTimestamp(iso: String): Instant? =
    runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .getOrNull()

// ---------------------------------------------------------------------------------------------
// Preview — PREVIEW-ONLY fake offer. Nothing here is read by the app; the live pane is fed by
// AvailableTripsWheelViewModel. The job/offer below are invented purely to exercise the card
// layout (a 20s window opened 4s ago, so the ring/readout are live in an interactive preview;
// the map tile needs network so the preview shows the honest "Loading map…" note).
// ---------------------------------------------------------------------------------------------

@Preview(widthDp = 900, heightDp = 520, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewDispatchOffer() {
    val now = remember { Instant.now() }
    val previewCard = remember(now) {
        AvailableTripCard(
            job = JobDto(
                id = "preview-job-7f3a",
                tenantId = "preview-tenant",
                originLat = -33.8790,
                originLng = 151.1870,
                originAddress = "12 Bay St, Glebe NSW (preview)",
                destLat = -33.8830,
                destLng = 151.2070,
                destAddress = "Central Station, Haymarket NSW (preview)",
                status = "offered",
                fareEstimateLow = "18.50",
                fareEstimateHigh = "24.00",
                requestedAt = now.minusSeconds(40).toString(),
                createdByUserId = null,
                acceptedByDriverId = null,
                createdAt = now.minusSeconds(40).toString(),
                updatedAt = now.minusSeconds(4).toString(),
                jobType = "booked",
                distanceKm = "3.4",
                etaMin = 9,
            ),
            offer = JobOfferDto(
                id = "preview-offer",
                jobId = "preview-job-7f3a",
                tenantId = "preview-tenant",
                driverId = "preview-driver",
                status = "pending",
                offeredAt = now.minusSeconds(4).toString(),
                expiresAt = now.plusSeconds(16).toString(),
                respondedAt = null,
            ),
        )
    }
    Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        JobOfferCard(card = previewCard, busy = false, onOpenDetail = {}, onAccept = {}, onDecline = {})
    }
}
