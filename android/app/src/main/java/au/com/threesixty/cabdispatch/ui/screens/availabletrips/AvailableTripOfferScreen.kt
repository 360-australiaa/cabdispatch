package au.com.threesixty.cabdispatch.ui.screens.availabletrips

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TripOrigin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import au.com.threesixty.cabdispatch.ui.theme.CaptainChip
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.CaptainPanel
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.wheel.content.formatOfferRelativeTime
import au.com.threesixty.cabdispatch.ui.wheel.content.rememberOfferCountdown

/**
 * 23 · Job Offer — Captain Taxis purple redesign, moved off the yellow/black `Deck` palette onto
 * [CaptainPalette] to match the rest of this dispatch-journey group (message thread, trip detail,
 * offline sync). All behavior unchanged from the previous version: [AvailableTripOfferViewModel]
 * accept/decline, the accept → HIRED and decline → pop navigations, the pre-accept `openInMaps`
 * deep-link, and the live [rememberOfferCountdown]-driven expiry.
 *
 * Accept is the single most consequential control in this whole four-screen group (accepting
 * commits the driver to a job), so it is the largest, most saturated button on the screen — solid
 * [CaptainPalette.success] fill, 96dp tall, with a real [Icons.Rounded.CheckCircle] glyph rather
 * than plain text — clearly distinct from Decline's smaller outline treatment.
 *
 * Honesty notes carried forward from the previous version of this file: the "TO PICKUP / ETA /
 * TYPE / ZONE" stat tiles have no real data source ([JobDto] carries no distance/ETA/type/zone
 * fields, and live GPS distance remains a TODO — see the identical note in
 * AvailableTripsWheelContent.kt), so only the tiles this app can back with real fields render:
 * fare estimate and requested-time.
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

    Column(modifier = Modifier.fillMaxSize().background(CaptainPalette.bg).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(CaptainPalette.panel)
                    .border(1.dp, CaptainPalette.panelBorder, CircleShape)
                    .clickable { navController.popBackStack() },
                contentAlignment = Alignment.Center,
            ) {
                Text("←", fontSize = 24.sp, color = CaptainPalette.textPrimary)
            }
            Text(
                "New Trip Offer",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = CaptainPalette.textPrimary,
                modifier = Modifier.padding(start = 16.dp),
            )
            Spacer(Modifier.weight(1f))
            // S6 (settings) reachable from anywhere, per spec — kept from the previous version,
            // now a real Material icon rather than an emoji glyph.
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(CaptainPalette.panel)
                    .border(1.dp, CaptainPalette.panelBorder, CircleShape)
                    .clickable { navController.navigate(CabDispatchRoutes.SETTINGS) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = CaptainPalette.textSecondary, modifier = Modifier.size(22.dp))
            }
        }

        CaptainPanel(modifier = Modifier.fillMaxSize()) {
            val pending = state.pending
            if (pending == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No job offer selected.", fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textSecondary)
                }
                return@CaptainPanel
            }

            val secondsLeft by rememberOfferCountdown(pending.offer.expiresAt)
            val expired = (secondsLeft ?: 1L) <= 0L
            val context = LocalContext.current

            Column(modifier = Modifier.fillMaxSize().padding(28.dp)) {
                // Header — countdown circle + real expiry state.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    CountdownCircle(secondsLeft = secondsLeft, expired = expired)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "NEW JOB OFFER",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = CaptainPalette.warning,
                        )
                        Text(
                            "First to accept wins — every available driver sees this.",
                            fontFamily = InterFamily,
                            fontSize = 16.sp,
                            color = CaptainPalette.textSecondary,
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))

                // Address cards: pickup / destination — same TripOrigin/Flag iconography used by
                // TripDetailScreen's timeline, so the two screens in this journey read as one system.
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    AddressCard(
                        icon = Icons.Rounded.TripOrigin,
                        label = "PICKUP",
                        labelColor = CaptainPalette.success,
                        borderColor = CaptainPalette.success.copy(alpha = 0.5f),
                        address = pending.job.originAddress,
                        modifier = Modifier.weight(1f),
                    )
                    AddressCard(
                        icon = Icons.Rounded.Flag,
                        label = "DESTINATION (if provided)",
                        labelColor = CaptainPalette.accent,
                        borderColor = CaptainPalette.accent.copy(alpha = 0.5f),
                        address = pending.job.destAddress,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(28.dp))

                // Stat chips — only the two tiles with real backing fields render, see file doc.
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CaptainChip(label = "EST. FARE", value = "$${pending.job.fareEstimateLow}–${pending.job.fareEstimateHigh}")
                    CaptainChip(label = "REQUESTED", value = formatOfferRelativeTime(pending.offer.offeredAt))
                    Spacer(Modifier.width(8.dp))
                    // Pre-accept maps deep-link — kept from the previous version (the only point in
                    // the flow where a maps deep-link is allowed; accepting jumps straight to Hired).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.clickable {
                            openInMaps(
                                context,
                                NavigationTarget(pending.job.originLat, pending.job.originLng, pending.job.originAddress),
                            )
                        },
                    ) {
                        Icon(Icons.Rounded.Navigation, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(18.dp))
                        Text(
                            "Navigate to pickup",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = CaptainPalette.accent,
                        )
                    }
                }

                val error = state.error
                if (error != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(error, fontFamily = InterFamily, fontSize = 14.sp, color = CaptainPalette.danger)
                }

                Spacer(Modifier.weight(1f))

                // CTAs: outline DECLINE + solid-green ACCEPT — the important-to-hit action, sized
                // and coloured to be unmistakably the primary choice.
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    OutlineOfferButton(
                        text = "Decline",
                        icon = Icons.Rounded.Close,
                        tint = CaptainPalette.danger,
                        enabled = !state.busy && !expired,
                        modifier = Modifier.width(240.dp).height(88.dp),
                        onClick = viewModel::decline,
                    )
                    SolidOfferButton(
                        text = if (state.busy) "Accepting…" else "Accept job",
                        icon = Icons.Rounded.CheckCircle,
                        containerColor = CaptainPalette.success,
                        enabled = !state.busy && !expired,
                        modifier = Modifier.weight(1f).height(96.dp),
                        onClick = viewModel::accept,
                    )
                }
            }
        }
    }
}

/**
 * The amber (turning danger-red on expiry) countdown circle with the seconds value over a
 * "SECONDS" caption. Bound to the real offer expiry; if the expiry timestamp doesn't parse
 * ([rememberOfferCountdown] returns null) the ring renders full with an em-dash rather than a
 * fabricated number.
 */
@Composable
private fun CountdownCircle(secondsLeft: Long?, expired: Boolean) {
    val ringColor = if (expired) CaptainPalette.danger else CaptainPalette.warning
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

/** 180dp panel card, radius 20, 2dp tinted border, icon + label + 28sp address. */
@Composable
private fun AddressCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
            .background(CaptainPalette.panel)
            .border(2.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 26.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = labelColor, modifier = Modifier.size(18.dp))
            Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = labelColor)
        }
        Text(
            address,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            color = CaptainPalette.textPrimary,
        )
    }
}

/**
 * Local button variant mirroring [au.com.threesixty.cabdispatch.ui.theme.CaptainButton]'s
 * press-scale/shape exactly but with an explicit solid fill colour — used for Accept, whose
 * consequence calls for a dedicated success-green rather than the shared button's purple primary.
 */
@Composable
private fun SolidOfferButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.95f else 1f, animationSpec = tween(120), label = "offer-accept-press")
    Row(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(if (pressed && enabled) containerColor.copy(alpha = 0.85f) else containerColor)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // onAccent (fixed white), not textPrimary — this sits on the solid containerColor fill
        // (success at this composable's one call site), see CaptainPalette.onAccent's doc.
        Icon(icon, contentDescription = null, tint = CaptainPalette.onAccent, modifier = Modifier.size(30.dp))
        Text(
            text,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            color = CaptainPalette.onAccent,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** Outline counterpart to [SolidOfferButton] — used for Decline, visually secondary to Accept. */
@Composable
private fun OutlineOfferButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.95f else 1f, animationSpec = tween(120), label = "offer-decline-press")
    Row(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .border(1.5.dp, tint, shape)
            .background(CaptainPalette.bg)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(text, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = tint, modifier = Modifier.padding(start = 10.dp))
    }
}
