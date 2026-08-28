package au.com.threesixty.cabdispatch.ui.screens.availabletrips

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.ui.deck.DeckButton
import au.com.threesixty.cabdispatch.ui.deck.DeckButtonKind
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.overlays.NavigationTarget
import au.com.threesixty.cabdispatch.ui.overlays.openInMaps
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.wheel.content.formatOfferRelativeTime
import au.com.threesixty.cabdispatch.ui.wheel.content.rememberOfferCountdown

/**
 * 23 · Job Offer — Command Deck v2 port (Figma `h0PSsXQ971dOJvt25tN7BA` node `23:30`), a
 * full-canvas takeover screen (no status strip / nav rail in the frame). All behavior unchanged
 * from the previous version: [AvailableTripOfferViewModel] accept/decline, the accept → HIRED and
 * decline → pop navigations, the pre-accept `openInMaps` deep-link, and the live
 * [rememberOfferCountdown]-driven expiry (which now drives the frame's countdown circle).
 *
 * Honesty notes carried forward from the previous version of this file:
 * - The frame's "TO PICKUP / ETA / TYPE / ZONE" stat tiles have no real data source ([JobDto]
 *   carries no distance/ETA/type/zone fields, and live GPS distance remains a TODO — see the
 *   identical note in AvailableTripsWheelContent.kt), so only the tiles this app can back with
 *   real fields render: fare estimate and requested-time. Nothing is faked to fill the row.
 * - `#0A1220` is the frame's own takeover background (node 23:30 fill) — slightly bluer than
 *   [Deck.canvas], introduced by this frame.
 */
@Composable
fun AvailableTripOfferScreen(
    navController: NavHostController,
    viewModel: AvailableTripOfferViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.navigateToHired) {
        if (state.navigateToHired) {
            navController.navigate(CabDispatchRoutes.HIRED) {
                popUpTo(CabDispatchRoutes.AVAILABLE_TRIP_OFFER) { inclusive = true }
            }
        }
    }
    LaunchedEffect(state.declined) {
        if (state.declined) navController.popBackStack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OfferCanvas),
    ) {
        val pending = state.pending
        if (pending == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No job offer selected.", fontFamily = InterFamily, fontSize = 16.sp, color = Deck.textSecondary)
            }
            return@Box
        }

        val secondsLeft by rememberOfferCountdown(pending.offer.expiresAt)
        val expired = (secondsLeft ?: 1L) <= 0L
        val context = LocalContext.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 72.dp, end = 72.dp, top = 56.dp, bottom = 44.dp),
        ) {
            // Header — countdown circle + NEW JOB OFFER (Figma 23:31). The ring is the frame's
            // static full border circle; the number inside is the ViewModel's real expiry state.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Back affordance kept from the previous version (not in the frame — discreet).
                Text(
                    "‹",
                    color = Deck.textSecondary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { navController.popBackStack() }
                        .padding(end = 4.dp),
                )
                CountdownCircle(secondsLeft = secondsLeft, expired = expired)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "NEW JOB OFFER",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Deck.stopped,
                    )
                    Text(
                        "First to accept wins — every available driver sees this.",
                        fontFamily = InterFamily,
                        fontSize = 16.sp,
                        color = Deck.textSecondary,
                    )
                }
                Spacer(Modifier.weight(1f))
                // S6 (settings) reachable from anywhere, per spec — kept from the previous version.
                Text(
                    "⚙",
                    color = Deck.textMuted,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clickable { navController.navigate(CabDispatchRoutes.SETTINGS) }
                        .padding(4.dp),
                )
            }

            Spacer(Modifier.height(40.dp))

            // Address cards (Figma 23:38): pickup (green border) + destination (blue border).
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                AddressCard(
                    label = "① PICKUP",
                    labelColor = Deck.forHire,
                    borderColor = Deck.forHire.copy(alpha = 0.5f),
                    address = pending.job.originAddress,
                    modifier = Modifier.weight(1f),
                )
                AddressCard(
                    label = "② DESTINATION (if provided)",
                    labelColor = Deck.info,
                    borderColor = Deck.info.copy(alpha = 0.5f),
                    address = pending.job.destAddress,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(36.dp))

            // Stat tiles (Figma 23:45). Only the two tiles with real backing fields render —
            // see this file's doc for why the frame's distance/ETA/type/zone tiles are omitted.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OfferStatTile(
                    value = "$${pending.job.fareEstimateLow}–${pending.job.fareEstimateHigh}",
                    label = "EST. FARE",
                )
                OfferStatTile(
                    value = formatOfferRelativeTime(pending.offer.offeredAt),
                    label = "REQUESTED",
                )
                Spacer(Modifier.width(20.dp))
                // Pre-accept maps deep-link — kept from the previous version (the only point in
                // the flow where a maps deep-link is allowed; accepting jumps straight to Hired).
                Text(
                    "📍 Navigate to pickup",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = Deck.info,
                    modifier = Modifier.clickable {
                        openInMaps(
                            context,
                            NavigationTarget(pending.job.originLat, pending.job.originLng, pending.job.originAddress),
                        )
                    },
                )
            }

            val error = state.error
            if (error != null) {
                Spacer(Modifier.height(14.dp))
                Text(error, fontFamily = InterFamily, fontSize = 14.sp, color = Deck.hired)
            }

            Spacer(Modifier.weight(1f))

            // CTAs (Figma 23:61/23:63): 320dp outline DECLINE + full-width green ACCEPT JOB, 96dp.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                DeckButton(
                    text = "DECLINE",
                    kind = DeckButtonKind.Outline,
                    heightDp = 96,
                    fontSize = 19,
                    enabled = !state.busy && !expired,
                    modifier = Modifier.width(320.dp),
                    onClick = viewModel::decline,
                )
                DeckButton(
                    text = if (state.busy) "ACCEPTING…" else "ACCEPT JOB",
                    kind = DeckButtonKind.Success,
                    heightDp = 96,
                    fontSize = 26,
                    enabled = !state.busy && !expired,
                    modifier = Modifier.weight(1f),
                    onClick = viewModel::accept,
                )
            }
        }
    }
}

/** Frame `23:30`'s own takeover background fill — introduced by this frame (see file doc). */
private val OfferCanvas = Color(0xFF0A1220)

/**
 * Figma `23:32` — the 140dp amber countdown circle (6dp border, 10% amber fill) with the seconds
 * value over a "SECONDS" caption. Bound to the real offer expiry; turns danger-red when expired.
 * If the expiry timestamp doesn't parse ([rememberOfferCountdown] returns null) the ring renders
 * full with an em-dash rather than a fabricated number.
 */
@Composable
private fun CountdownCircle(secondsLeft: Long?, expired: Boolean) {
    val ringColor = if (expired) Deck.hired else Deck.stopped
    Column(
        modifier = Modifier
            .size(140.dp)
            .clip(CircleShape)
            .background(ringColor.copy(alpha = 0.1f))
            .border(6.dp, ringColor, CircleShape),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = when {
                expired -> "0"
                secondsLeft != null -> secondsLeft.toString()
                else -> "—"
            },
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = 52.sp,
            color = ringColor,
        )
        Text(
            text = if (expired) "EXPIRED" else "SECONDS",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = ringColor,
        )
    }
}

/** Figma `23:39`/`23:42` — 180dp panel card, radius 20, 2dp tinted border, label + 30sp address. */
@Composable
private fun AddressCard(
    label: String,
    labelColor: Color,
    borderColor: Color,
    address: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(180.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Deck.panel)
            .border(2.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 26.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = labelColor)
        Text(
            address,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            color = Deck.textPrimary,
        )
    }
}

/** Figma `23:46` — 213×96 card tile: Chakra Petch 28 value over a 12sp bold muted label. */
@Composable
private fun OfferStatTile(value: String, label: String) {
    Column(
        modifier = Modifier
            .width(213.dp)
            .height(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Deck.card),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Medium, fontSize = 28.sp, color = Deck.textPrimary)
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Deck.textMuted)
        Spacer(Modifier.weight(1f))
    }
}
