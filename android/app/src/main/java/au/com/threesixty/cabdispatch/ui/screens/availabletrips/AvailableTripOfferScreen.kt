package au.com.threesixty.cabdispatch.ui.screens.availabletrips

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.overlays.NavigationTarget
import au.com.threesixty.cabdispatch.ui.overlays.openInMaps
import au.com.threesixty.cabdispatch.ui.theme.WheelColors
import au.com.threesixty.cabdispatch.ui.wheel.content.formatOfferRelativeTime
import au.com.threesixty.cabdispatch.ui.wheel.content.rememberOfferCountdown

/**
 * Row 12 — job-offer accept/decline detail screen, spec TCT-DRIVER-APP-01.md §8 ("Job queue list,
 * offer accept/decline" — ✅ Figma only, not in the HTML prototype; designed directly off the
 * §10 token system for this pass, same "not yet designed, design inline" precedent
 * [au.com.threesixty.cabdispatch.ui.screens.messages.MessageThreadScreen] set for its row 14).
 *
 * Reachable from a job-offer card's tap target in
 * [au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelContent] — inline
 * Accept/Decline on the card itself is the primary, faster path (see that file's doc on why:
 * offers expire in ~20s), this screen is for reviewing full details before deciding.
 *
 * Verified (reconciliation pass): [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]'s
 * Available Trips wheel-slot content ([au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelContent])
 * navigates here from a job card's tap target, using this route
 * ([CabDispatchRoutes.AVAILABLE_TRIP_OFFER]) exactly as registered.
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
            .background(WheelColors.bg),
    ) {
        val pending = state.pending
        if (pending == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No job offer selected.", color = WheelColors.textSecondary, fontSize = 15.sp)
            }
            return@Box
        }

        val secondsLeft by rememberOfferCountdown(pending.offer.expiresAt)
        val expired = (secondsLeft ?: 1L) <= 0L
        val context = LocalContext.current

        Column(modifier = Modifier.fillMaxSize().padding(28.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "‹",
                    color = WheelColors.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { navController.popBackStack() }
                        .padding(end = 12.dp),
                )
                Text(
                    "JOB OFFER",
                    color = WheelColors.textMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                // S6 (settings) reachable from anywhere, per spec.
                Text(
                    "⚙",
                    color = WheelColors.textSecondary,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clickable { navController.navigate(CabDispatchRoutes.SETTINGS) }
                        .padding(4.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                if (expired) "Offer expired" else "Expires in ${secondsLeft}s",
                color = when {
                    expired -> WheelColors.duress
                    (secondsLeft ?: 99) <= 8 -> WheelColors.waiting
                    else -> WheelColors.goldSoft
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(20.dp))

            RouteBlock("PICKUP", pending.job.originAddress)
            Spacer(Modifier.height(16.dp))
            RouteBlock("DROP-OFF", pending.job.destAddress)

            Spacer(Modifier.height(12.dp))

            // Row 28 — "Navigate" contextual overlay (spec §8: deep-link to the device's default
            // maps app, not custom turn-by-turn — see ui/overlays/NavigateOverlay.kt's doc). This
            // review screen is the natural pre-accept call site: a driver deciding whether to
            // accept can check the pickup's real distance/route before committing (accepting jumps
            // straight to S3/Hired — spec §2's "no navigation while Hired" safety rule — so this is
            // the only point in the flow where a maps deep-link makes sense at all).
            Text(
                "📍 Navigate to pickup",
                color = WheelColors.goldSoft,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable {
                    openInMaps(
                        context,
                        NavigationTarget(pending.job.originLat, pending.job.originLng, pending.job.originAddress),
                    )
                },
            )

            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                DetailStat("Estimated fare", "$${pending.job.fareEstimateLow}–${pending.job.fareEstimateHigh}")
                DetailStat("Requested", formatOfferRelativeTime(pending.offer.offeredAt))
            }

            // TODO(location sibling agent): show real distance-from-driver here once a live
            // location provider replaces domain/FareEngine.kt's StubSpeedSource (see HANDOFF.md
            // "GPS is stubbed, not real") — deliberately omitted rather than faking a
            // plausible-looking number, see AvailableTripsWheelContent.kt's identical note.

            val error = state.error
            if (error != null) {
                Spacer(Modifier.height(16.dp))
                Text(error, color = WheelColors.duress, fontSize = 13.sp)
            }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = viewModel::decline,
                    enabled = !state.busy && !expired,
                    modifier = Modifier.weight(1f).height(60.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, WheelColors.borderStrong),
                    colors = OutlinedButtonDefaults.outlinedButtonColors(contentColor = WheelColors.textPrimary),
                ) { Text("DECLINE", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = viewModel::accept,
                    enabled = !state.busy && !expired,
                    modifier = Modifier.weight(1f).height(60.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WheelColors.gold, contentColor = CabIndigoOnGold),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CabIndigoOnGold)
                    } else {
                        Text("ACCEPT", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteBlock(label: String, address: String) {
    Column {
        Text(label, color = WheelColors.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(3.dp))
        Text(address, color = WheelColors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DetailStat(label: String, value: String) {
    Column {
        Text(value, color = WheelColors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = WheelColors.textSecondary, fontSize = 11.sp)
    }
}

/** #2A1C58 — see [au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelContent]'s
 * identical constant for why this isn't [WheelColors.surfaceRaised] reused semantically. */
private val CabIndigoOnGold = Color(0xFF2A1C58)
