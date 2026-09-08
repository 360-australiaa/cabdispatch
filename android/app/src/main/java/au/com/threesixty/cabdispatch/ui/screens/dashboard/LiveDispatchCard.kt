package au.com.threesixty.cabdispatch.ui.screens.dashboard

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.domain.location.GeoMath
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.color
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.rememberInfiniteFloat
import au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripCard
import au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelViewModel
import au.com.threesixty.cabdispatch.ui.wheel.content.formatOfferRelativeTime
import java.util.Locale

/**
 * The home screen's Live Dispatch card and its offer rows.
 *
 * Extracted from DeckHomeScreen.kt in Phase 0 (P0.4) — a mechanical move, same package,
 * same declarations, no behaviour change.
 */

// ============================================================================================
// Live dispatch card (Figma right card — real JobsRepository data via AvailableTripsWheelViewModel)
// ============================================================================================

@Composable
internal fun LiveDispatchCard(
    dispatchState: au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsUiState,
    onAccept: (AvailableTripCard) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Glassmorphism pass (2026-09-07, design brief item 3): was a flat gradient-fill + solid border
    // panel; now the shared GlassCard (hudGlass fill + blurred sheen + 1dp purple->cyan gradient
    // border — see Hud.kt's own doc) so this card matches every other reskinned dashboard container
    // instead of carrying its own one-off panel styling.
    GlassCard(modifier = modifier, cornerRadiusDp = 18, glow = CaptainPalette.hudAccent) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
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
                    Text(dispatchState.cards.size.toString(), fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = CaptainPalette.onAccent)
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
}

@Composable
internal fun DispatchOfferRow(card: AvailableTripCard, busy: Boolean, onAccept: () -> Unit) {
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
