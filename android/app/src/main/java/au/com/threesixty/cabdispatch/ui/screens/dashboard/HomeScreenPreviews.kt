package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import au.com.threesixty.cabdispatch.data.remote.JobDto
import au.com.threesixty.cabdispatch.data.remote.JobOfferDto
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.Space
import au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripCard
import au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsUiState
import java.time.Instant

/**
 * Home-screen previews for the A3 redesign (2026-09-08).
 *
 * WHY THESE EXIST, and what they are honestly worth. The owner will judge this work by looking at
 * it on a physical SM-T575, and this agent is forbidden from touching that tablet. These previews
 * are how the layout is checked before it gets there: they render the real composables at the real
 * canvas sizes, so a card that no longer fits, a dial that clips or a column that overflows shows
 * up here rather than in front of the owner.
 *
 * WHAT THEY DELIBERATELY DO NOT DO. They do not host [DeckHomeScreen] itself. That composable
 * reaches straight into `AppContainer` for Room, the duress controller, four REST calls and a
 * location fix in its first few lines, none of which exists in a preview process. So these
 * assemble the same three regions the live screen assembles - header, meter card + right column,
 * stat bar - from the same composables with the same fixture data ([previewState]), against the
 * same measurements. What they verify is **layout and fit**, which is exactly what this workstream
 * changed; they are not a claim that the wired screen runs.
 *
 * THE SIZES ARE THE POINT:
 * - **1280x800** is the real design canvas (`FixedDesignCanvas`, MainActivity) - what the pilot
 *   16:10 tablet actually lays out on.
 * - **1280x720** is what a 16:9 panel used to silently become, because the canvas scaled from
 *   width alone. That is the case that clipped the old hardcoded 414dp dial. The 720 preview is
 *   here specifically so that regression cannot come back unseen: the dial is now sized from its
 *   own constraints and must fit at both heights.
 *
 * All preview-only data is confined to this file and [previewState]; nothing here is reachable at
 * runtime.
 */

private const val CANVAS_DARK = 0xFF080B14

/** The Dashboard pane, assembled the way [DeckHomeScreen] assembles it - see this file's doc for
 * why the real screen composable cannot itself be previewed. */
@Composable
private fun HomeScaffoldPreview(
    state: WheelDashboardUiState,
    dispatchState: AvailableTripsUiState,
    hasActiveTrip: Boolean = false,
) {
    CaptainPalette.applyTheme(isLight = false)
    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            CaptainHeader(
                state = state,
                verified = true,
                hasActiveTrip = hasActiveTrip,
                onShowDriverId = {},
                onOpenProfile = {},
                onToggleAvailability = {},
                onSos = {},
            )
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.panelBorder))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = Space.mlg, start = Space.xl, end = Space.smd, bottom = Space.mlg),
            ) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        MeterCard(
                            state = state,
                            meterPhase = MeterStartPhase.Idle,
                            negotiatedTotal = null,
                            onStartMeter = {},
                            onCancelStart = {},
                            onSetPrice = {},
                            onVouchers = {},
                            modifier = Modifier.width(560.dp).fillMaxHeight(),
                        )
                        Spacer(Modifier.width(Space.md))
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val hasOffers = dispatchState.cards.isNotEmpty()
                            if (hasOffers) {
                                LiveDispatchCard(
                                    dispatchState = dispatchState,
                                    onAccept = {},
                                    onViewAll = {},
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 340.dp),
                                )
                            } else {
                                DispatchIdleStrip(
                                    isAvailable = state.isAvailable,
                                    error = dispatchState.error,
                                    onViewAll = {},
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(Modifier.height(Space.md))
                            // The tiles' own ViewModel is not reachable in a preview process, so
                            // this renders the stateless body with its documented preview data -
                            // the same seam EngagementTiles' own previews already use.
                            EngagementTilesPreviewBody(modifier = Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                    Spacer(Modifier.height(Space.md))
                    ShiftStatsBar(
                        state = state,
                        extras = HomeExtras(verified = true, earningsPctChange = 12.0, tripsActiveThisShift = 1),
                        onTakeBreak = {},
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    )
                }
                Spacer(Modifier.width(Space.smd))
                CaptainNavRail(
                    pane = CaptainPane.DASHBOARD,
                    hasActiveTrip = hasActiveTrip,
                    dispatchOfferCount = dispatchState.cards.size,
                    onSelectPane = {},
                    onOpenVouchers = {},
                    onOpenProfile = {},
                    onOpenSettings = {},
                    onLogOff = {},
                    modifier = Modifier.fillMaxHeight(),
                )
            }
        }
    }
}

// --------------------------------------------------------------------------------------------
// The three required states at the real 1280x800 canvas
// --------------------------------------------------------------------------------------------

/** OFF DUTY: no shift, nothing available. The header pill is neutral and - the point of this
 * state - **nothing on the screen is animating at all**. */
@Preview(name = "Home 1280x800 - off duty", widthDp = 1280, heightDp = 800, backgroundColor = CANVAS_DARK, showBackground = true)
@Composable
private fun PreviewHomeOffDuty() {
    HomeScaffoldPreview(
        state = previewState(available = false).copy(session = null),
        dispatchState = AvailableTripsUiState(),
    )
}

/** AVAILABLE, no offers - **the state a driver is in for most of a shift, and the one the owner
 * was complaining about.** The 300dp empty Live Dispatch block is now a 56dp idle strip, and the
 * space it gave back is why all four MY ACCOUNT tiles fit on screen. */
@Preview(name = "Home 1280x800 - available, no offers", widthDp = 1280, heightDp = 800, backgroundColor = CANVAS_DARK, showBackground = true)
@Composable
private fun PreviewHomeAvailableNoOffers() {
    HomeScaffoldPreview(
        state = previewState(available = true),
        dispatchState = AvailableTripsUiState(),
    )
}

/** AVAILABLE with two live offers - the dispatch card expands back in, bounded at 340dp so a busy
 * queue cannot push MY ACCOUNT off the bottom of the column. */
@Preview(name = "Home 1280x800 - available, 2 offers", widthDp = 1280, heightDp = 800, backgroundColor = CANVAS_DARK, showBackground = true)
@Composable
private fun PreviewHomeTwoOffers() {
    HomeScaffoldPreview(
        state = previewState(available = true),
        dispatchState = AvailableTripsUiState(cards = previewOfferCards()),
    )
}

/**
 * **The clipping regression guard.** 1280x720 is the canvas a 16:9 tablet used to silently get,
 * because `FixedDesignCanvas` scaled from width only (fixed in this same pass, MainActivity). At
 * this height the old hardcoded 414dp meter dial did not fit and was cut off. The dial now sizes
 * itself from its own constraints between 300 and 380dp, so this preview must render complete.
 */
@Preview(name = "Home 1280x720 - 16:9 clip guard", widthDp = 1280, heightDp = 720, backgroundColor = CANVAS_DARK, showBackground = true)
@Composable
private fun PreviewHome16x9() {
    HomeScaffoldPreview(
        state = previewState(available = true),
        dispatchState = AvailableTripsUiState(),
    )
}

/** The dispatch idle strip's error branch, which used to be indistinguishable from "no offers" -
 * same slot, same grey. Now `danger`-toned with a warning icon. */
@Preview(name = "Dispatch idle strip - error", widthDp = 560, heightDp = 120, backgroundColor = CANVAS_DARK, showBackground = true)
@Composable
private fun PreviewDispatchIdleStripError() {
    CaptainPalette.applyTheme(isLight = false)
    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.bg).padding(Space.md)) {
        DispatchIdleStrip(
            isAvailable = true,
            error = "Can't reach dispatch — retrying",
            onViewAll = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// --------------------------------------------------------------------------------------------
// Preview-only fixtures. Nothing below is reachable at runtime.
// --------------------------------------------------------------------------------------------

private const val PREVIEW_TENANT = "PREVIEW-TENANT"

private fun previewOfferCards(): List<AvailableTripCard> = listOf(
    previewOfferCard(
        id = "PREVIEW-1",
        origin = "12 Railway Parade, Lakemba NSW 2195",
        dest = "Sydney Airport T1, Mascot NSW 2020",
        low = "42.00",
        high = "56.00",
        distanceKm = "18.4",
    ),
    previewOfferCard(
        id = "PREVIEW-2",
        origin = "3 Anzac Parade, Kensington NSW 2033",
        dest = "Town Hall Station, Sydney NSW 2000",
        low = "18.00",
        high = "24.00",
        distanceKm = "6.1",
    ),
)

private fun previewOfferCard(
    id: String,
    origin: String,
    dest: String,
    low: String,
    high: String,
    distanceKm: String,
): AvailableTripCard {
    val now = Instant.now()
    return AvailableTripCard(
        job = JobDto(
            id = id,
            tenantId = PREVIEW_TENANT,
            originLat = -33.92,
            originLng = 151.07,
            originAddress = origin,
            destLat = -33.94,
            destLng = 151.17,
            destAddress = dest,
            status = "offered",
            fareEstimateLow = low,
            fareEstimateHigh = high,
            requestedAt = now.toString(),
            createdByUserId = null,
            acceptedByDriverId = null,
            createdAt = now.toString(),
            updatedAt = now.toString(),
            // jobType and etaMin stay null exactly as the live backend leaves them (see JobDto's
            // own doc) - a preview that filled them in would be showing a card shape the driver
            // can never actually receive.
            distanceKm = distanceKm,
        ),
        offer = JobOfferDto(
            id = "offer-$id",
            jobId = id,
            tenantId = PREVIEW_TENANT,
            driverId = "PREVIEW-DRIVER",
            status = "pending",
            offeredAt = now.minusSeconds(6).toString(),
            expiresAt = now.plusSeconds(14).toString(),
            respondedAt = null,
        ),
    )
}
