package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarHalf
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.data.remote.AnnouncementDto
import au.com.threesixty.cabdispatch.data.remote.IncentiveProgressDto
import au.com.threesixty.cabdispatch.data.remote.RatingDto
import au.com.threesixty.cabdispatch.data.remote.WalletDto
import au.com.threesixty.cabdispatch.data.remote.WalletTransactionDto
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.HudRing
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.PulsingDot
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.screens.dashboard.DriverEngagementFormat as Fmt
import java.time.Instant

/**
 * The dashboard's driver-engagement tiles (mockup #1: WALLET BALANCE, RATING, ANNOUNCEMENTS,
 * INCENTIVE PROGRESS), each a [GlassCard] from the HUD kit, fed by the real `GET /v1/me/{wallet,rating,announcements,incentives}` reads
 * (backend commit 58ccfcf) through [DriverEngagementViewModel].
 *
 * Honesty rules this file enforces, per section:
 * - **Real data only.** No sample balance, no default score, no placeholder announcement or
 *   incentive — every section renders loading / error-with-retry / empty explicitly instead.
 * - **Wallet "Add funds" is an info dialog, not a fake payment flow.** Only owner/admin users can
 *   post wallet lines (`POST /v1/wallet/transactions`, `require_role("owner","admin")`); there is
 *   no driver self-top-up endpoint. [TopUpInfoDialog] says exactly that.
 * - **Rating shows "No ratings yet"** whenever `rating_count == 0` (`average_stars` is null then).
 * - **Announcements** are the backend's already-live list, newest first; **incentives** render
 *   the backend's derived `completed_trips / target_trips` and a "Completed" state when achieved.
 *
 * [DriverEngagementTiles] is the ViewModel-wired entry point the Dashboard pane places next to
 * `MeterCard`/`LiveDispatchCard`; [EngagementTilesContent] is the stateless body the previews
 * render with clearly-marked preview-only data ([EngagementPreviewData]).
 */
@Composable
fun DriverEngagementTiles(
    modifier: Modifier = Modifier,
    viewModel: DriverEngagementViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    // "Refreshed on pane entry": this composable only exists while the Dashboard pane is showing,
    // so its first composition IS pane entry (the ViewModel itself outlives the pane, so a
    // re-entry re-fetches rather than showing whatever it last had without checking).
    LaunchedEffect(Unit) { viewModel.refreshAll() }
    EngagementTilesContent(
        state = state,
        actions = EngagementActions(
            onRefreshAll = viewModel::refreshAll,
            onRetryWallet = viewModel::refreshWallet,
            onRetryRating = viewModel::refreshRating,
            onRetryAnnouncements = viewModel::refreshAnnouncements,
            onRetryIncentives = viewModel::refreshIncentives,
        ),
        modifier = modifier,
    )
}

/** The per-section retry + shared refresh callbacks [EngagementTilesContent] needs. */
data class EngagementActions(
    val onRefreshAll: () -> Unit = {},
    val onRetryWallet: () -> Unit = {},
    val onRetryRating: () -> Unit = {},
    val onRetryAnnouncements: () -> Unit = {},
    val onRetryIncentives: () -> Unit = {},
)

@Composable
fun EngagementTilesContent(
    state: DriverEngagementUiState,
    actions: EngagementActions,
    modifier: Modifier = Modifier,
) {
    var showTopUpInfo by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "MY ACCOUNT",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 2.sp,
                color = CaptainPalette.textMuted,
            )
            Spacer(Modifier.weight(1f))
            RefreshControl(refreshing = state.refreshing, onClick = actions.onRefreshAll)
        }
        WalletTile(section = state.wallet, onRetry = actions.onRetryWallet, onAddFunds = { showTopUpInfo = true })
        RatingTile(section = state.rating, onRetry = actions.onRetryRating)
        AnnouncementsTile(section = state.announcements, onRetry = actions.onRetryAnnouncements)
        IncentiveTile(section = state.incentives, onRetry = actions.onRetryIncentives)
    }
    if (showTopUpInfo) TopUpInfoDialog(onDismiss = { showTopUpInfo = false })
}

// ============================================================================================
// Wallet
// ============================================================================================

@Composable
private fun WalletTile(section: EngagementSection<WalletDto>, onRetry: () -> Unit, onAddFunds: () -> Unit) {
    val balance = section.data?.let { Fmt.parseDecimal(it.balanceAud) }
    val negative = balance != null && balance.signum() < 0
    GlassCard(modifier = Modifier.fillMaxWidth(), glow = if (negative) CaptainPalette.danger else null) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TileHeader(icon = Icons.Rounded.AccountBalanceWallet, label = "WALLET BALANCE")
            SectionBody(section = section, onRetry = onRetry, isEmpty = { false }, emptyText = "") { wallet ->
                au.com.threesixty.cabdispatch.ui.theme.RollingMoneyText(
                    amount = Fmt.formatAud(wallet.balanceAud),
                    fontSize = 36.sp,
                    color = if (negative) CaptainPalette.danger else CaptainPalette.textPrimary,
                )
                val recent = wallet.recent.take(3)
                if (recent.isEmpty()) {
                    Text("No transactions yet", fontFamily = InterFamily, fontSize = 14.sp, color = CaptainPalette.textMuted)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        recent.forEach { LedgerLine(it) }
                    }
                }
            }
            StaleLine(section)
            // Honest affordance (see this file's doc): drivers can't top themselves up, so this
            // opens an explanation, not a payment form.
            CaptainButton(
                text = "ADD FUNDS",
                outline = true,
                heightDp = 52,
                fontSize = 16.sp,
                onClick = onAddFunds,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LedgerLine(line: WalletTransactionDto) {
    val amount = Fmt.parseDecimal(line.amountAud)
    val amountColor = when {
        amount == null -> CaptainPalette.textSecondary
        amount.signum() < 0 -> CaptainPalette.danger
        else -> CaptainPalette.success
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                Fmt.ledgerKindLabel(line.kind),
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = CaptainPalette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val when_ = Fmt.relativeTime(line.createdAt)
            val sub = listOfNotNull(line.reference?.takeIf { it.isNotBlank() }, when_.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, fontFamily = InterFamily, fontSize = 12.sp, color = CaptainPalette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            Fmt.formatSignedAud(line.amountAud),
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = amountColor,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Why this is a dialog and not a payment form: the backend's wallet ledger is operator-posted
 * (`backend/app/api/v1/wallet.py` — every write is `require_role("owner", "admin")`); there is no
 * driver-facing top-up endpoint, card capture or payment gateway for a wallet credit anywhere in
 * this system. Pretending otherwise would be a fake button, so this says what actually happens.
 */
@Composable
private fun TopUpInfoDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CaptainPalette.panel)
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Add funds", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
            Text(
                "Wallet top-ups are posted by your operator, not from this tablet. Ask your base " +
                    "to add funds to your wallet — the new balance shows here on the next refresh.",
                fontFamily = InterFamily,
                fontSize = 16.sp,
                color = CaptainPalette.textSecondary,
            )
            CaptainButton(text = "Got it", heightDp = 56, fontSize = 18.sp, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ============================================================================================
// Rating
// ============================================================================================

@Composable
private fun RatingTile(section: EngagementSection<RatingDto>, onRetry: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TileHeader(icon = Icons.Rounded.Star, label = "RATING")
            SectionBody(section = section, onRetry = onRetry, isEmpty = { false }, emptyText = "") { rating ->
                val rated = rating.ratingCount > 0
                val average = if (rated) Fmt.formatAverage(rating.averageStars) else null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        average ?: "—",
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Bold,
                        fontSize = 40.sp,
                        color = if (average != null) CaptainPalette.textPrimary else CaptainPalette.textMuted,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StarRow(fills = Fmt.starFills(if (rated) Fmt.parseDecimal(rating.averageStars)?.toDouble() else null))
                        Text(
                            if (rated) "(${Fmt.ratingCountLabel(rating.ratingCount)})" else "No ratings yet",
                            fontFamily = InterFamily,
                            fontSize = 14.sp,
                            color = CaptainPalette.textSecondary,
                        )
                    }
                }
            }
            StaleLine(section)
        }
    }
}

@Composable
private fun StarRow(fills: List<StarFill>) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        fills.forEach { fill ->
            val (icon, tint) = when (fill) {
                StarFill.FULL -> Icons.Rounded.Star to CaptainPalette.warning
                StarFill.HALF -> Icons.Rounded.StarHalf to CaptainPalette.warning
                StarFill.EMPTY -> Icons.Rounded.StarOutline to CaptainPalette.textMuted
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}

// ============================================================================================
// Announcements
// ============================================================================================

private const val MAX_ANNOUNCEMENTS_SHOWN = 5

@Composable
private fun AnnouncementsTile(section: EngagementSection<List<AnnouncementDto>>, onRetry: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TileHeader(icon = Icons.Rounded.Campaign, label = "ANNOUNCEMENTS")
            SectionBody(section = section, onRetry = onRetry, isEmpty = { it.isEmpty() }, emptyText = "No announcements") { items ->
                // Backend already orders newest-first; re-sorting here keeps that true even if a
                // future server build changes its default order.
                val shown = items
                    .sortedByDescending { Fmt.parseInstant(it.startsAt) ?: Instant.EPOCH }
                    .take(MAX_ANNOUNCEMENTS_SHOWN)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    shown.forEach { AnnouncementRow(it) }
                }
            }
            StaleLine(section)
        }
    }
}

@Composable
private fun AnnouncementRow(item: AnnouncementDto) {
    val tone = when (item.kind) {
        "maintenance" -> CaptainPalette.warning
        "surge" -> CaptainPalette.hudAccent
        "feature" -> CaptainPalette.success
        else -> CaptainPalette.textSecondary
    }
    Row(verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.padding(top = 6.dp).size(9.dp).clip(CircleShape).background(tone))
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.title,
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = CaptainPalette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.body,
                fontFamily = InterFamily,
                fontSize = 14.sp,
                color = CaptainPalette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val when_ = Fmt.relativeTime(item.startsAt)
            if (when_.isNotEmpty()) {
                Text(when_, fontFamily = InterFamily, fontSize = 12.sp, color = CaptainPalette.textMuted)
            }
        }
    }
}

// ============================================================================================
// Incentive progress
// ============================================================================================

private const val MAX_INCENTIVES_SHOWN = 2

@Composable
private fun IncentiveTile(section: EngagementSection<List<IncentiveProgressDto>>, onRetry: () -> Unit) {
    val anyAchieved = section.data?.any { it.achieved } == true
    GlassCard(modifier = Modifier.fillMaxWidth(), glow = if (anyAchieved) CaptainPalette.success else null) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TileHeader(icon = Icons.Rounded.EmojiEvents, label = "INCENTIVE PROGRESS")
            SectionBody(section = section, onRetry = onRetry, isEmpty = { it.isEmpty() }, emptyText = "No active incentives") { items ->
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items.take(MAX_INCENTIVES_SHOWN).forEach { IncentiveRow(it) }
                }
            }
            StaleLine(section)
        }
    }
}

@Composable
private fun IncentiveRow(item: IncentiveProgressDto) {
    val fraction = Fmt.incentiveFraction(item.completedTrips, item.targetTrips)
    val done = item.achieved || fraction >= 1f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            HudRing(progress = fraction, modifier = Modifier.size(64.dp), strokeWidthDp = 6)
            Text(
                "${(fraction * 100).toInt()}%",
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (done) CaptainPalette.success else CaptainPalette.textPrimary,
            )
        }
        Column(modifier = Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                item.title,
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = CaptainPalette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${Fmt.formatCount(item.completedTrips)} / ${Fmt.formatCount(item.targetTrips)} trips",
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = CaptainPalette.textPrimary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (done) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(CaptainPalette.glowSuccessSoft)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("COMPLETED", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp, color = CaptainPalette.success)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    "Reward ${Fmt.formatAud(item.rewardAud)}",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = if (done) CaptainPalette.success else CaptainPalette.warning,
                )
            }
            val ends = Fmt.endsInLabel(item.endsAt)
            if (ends.isNotEmpty()) {
                Text(ends, fontFamily = InterFamily, fontSize = 12.sp, color = CaptainPalette.textMuted)
            }
        }
    }
}

// ============================================================================================
// Shared scaffolding: header, loading / error / empty body, stale line, refresh control
// ============================================================================================

@Composable
private fun TileHeader(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = CaptainPalette.hudSweepMid, modifier = Modifier.size(18.dp))
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 2.sp,
            color = CaptainPalette.textMuted,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Renders exactly one of: real data (whenever any successful load exists — even while a refresh
 * is in flight or the latest refresh failed, see [EngagementSection]), the empty state, the
 * first-load spinner, or the error + RETRY. Never a placeholder value.
 */
@Composable
private fun <T> SectionBody(
    section: EngagementSection<T>,
    onRetry: () -> Unit,
    isEmpty: (T) -> Boolean,
    emptyText: String,
    content: @Composable (T) -> Unit,
) {
    val data = section.data
    when {
        data != null && isEmpty(data) -> Text(emptyText, fontFamily = InterFamily, fontSize = 15.sp, color = CaptainPalette.textMuted)
        data != null -> content(data)
        section.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            PulsingDot(color = CaptainPalette.hudAccent, animated = true)
            Text("Loading…", fontFamily = InterFamily, fontSize = 15.sp, color = CaptainPalette.textMuted, modifier = Modifier.padding(start = 10.dp))
        }
        section.error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = CaptainPalette.danger, modifier = Modifier.size(16.dp))
                Text(section.error, fontFamily = InterFamily, fontSize = 14.sp, color = CaptainPalette.danger, modifier = Modifier.padding(start = 8.dp))
            }
            CaptainButton(text = "RETRY", outline = true, heightDp = 48, fontSize = 15.sp, onClick = onRetry, modifier = Modifier.fillMaxWidth())
        }
        // Not loaded, not loading, no error: nothing has been asked for yet (only possible before
        // the first refreshAll fires) — say nothing rather than invent something.
        else -> Spacer(Modifier.height(4.dp))
    }
}

/** When real data is on screen but the latest refresh failed, say so under it — the number shown
 * is the last successful read, not a live one. */
@Composable
private fun <T> StaleLine(section: EngagementSection<T>) {
    val error = section.error
    if (section.data != null && error != null) {
        Text(
            "Couldn't refresh — $error",
            fontFamily = InterFamily,
            fontSize = 12.sp,
            color = CaptainPalette.warning,
        )
    }
}

@Composable
private fun RefreshControl(refreshing: Boolean, onClick: () -> Unit) {
    val spin = rememberInfiniteTransition(label = "engagement-refresh")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "engagement-refresh-angle",
    )
    Box(
        modifier = Modifier
            .size(44.dp) // elderly-friendly touch target, same reasoning as the rest of the dashboard
            .clip(CircleShape)
            .gameClick(onClick = onClick, shape = CircleShape, glowColor = CaptainPalette.hudAccent, enabled = !refreshing),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Refresh,
            contentDescription = "Refresh",
            tint = if (refreshing) CaptainPalette.hudAccent else CaptainPalette.textSecondary,
            modifier = Modifier.size(22.dp).rotate(if (refreshing) angle else 0f),
        )
    }
}

// ============================================================================================
// Previews — PREVIEW-ONLY DATA. Nothing below is reachable at runtime; the live tiles are fed
// exclusively by DriverEngagementViewModel over the real /v1/me/{wallet,rating,announcements,incentives} endpoints.
// ============================================================================================

private object EngagementPreviewData {
    private const val T = "PREVIEW-TENANT"
    private const val D = "PREVIEW-DRIVER"
    private val now: Instant = Instant.now()
    private fun ago(minutes: Long) = now.minusSeconds(minutes * 60).toString()

    val wallet = WalletDto(
        driverId = D,
        balanceAud = "1264.35",
        recent = listOf(
            WalletTransactionDto("p1", T, D, "32.40", "trip_earning", reference = "PREVIEW trip", note = null, createdByUserId = null, createdAt = ago(12)),
            WalletTransactionDto("p2", T, D, "-250.00", "payout", reference = "PREVIEW payout", note = null, createdByUserId = null, createdAt = ago(60 * 26)),
            WalletTransactionDto("p3", T, D, "100.00", "top_up", reference = null, note = null, createdByUserId = null, createdAt = ago(60 * 50)),
        ),
    )
    val rating = RatingDto(driverId = D, averageStars = "4.8", ratingCount = 1240)
    val noRating = RatingDto(driverId = D, averageStars = null, ratingCount = 0)
    val announcements = listOf(
        AnnouncementDto("a1", T, "PREVIEW: Airport rank change", "Use the new holding bay entrance from Monday.", "info", ago(90), null, true, ago(90), ago(90)),
        AnnouncementDto("a2", T, "PREVIEW: Surge — CBD tonight", "Concert crowds expected 22:00–01:00.", "surge", ago(60 * 5), null, true, ago(60 * 5), ago(60 * 5)),
    )
    val incentives = listOf(
        IncentiveProgressDto(
            "i1", T, "PREVIEW: Weekend 40", null, targetTrips = 40, rewardAud = "120.00",
            startsAt = ago(60 * 24), endsAt = now.plusSeconds(3 * 86_400).toString(), active = true, createdAt = ago(60 * 24), updatedAt = ago(60 * 24),
            completedTrips = 26, remainingTrips = 14, progressPct = 65, achieved = false,
        ),
    )

    val loaded = DriverEngagementUiState(
        wallet = EngagementSection(data = wallet),
        rating = EngagementSection(data = rating),
        announcements = EngagementSection(data = announcements),
        incentives = EngagementSection(data = incentives),
    )
    val mixed = DriverEngagementUiState(
        wallet = EngagementSection(loading = true),
        rating = EngagementSection(data = noRating),
        announcements = EngagementSection(error = "No connection"),
        incentives = EngagementSection(data = emptyList()),
    )
}

@Preview(widthDp = 340, heightDp = 980, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewEngagementTilesLoaded() {
    EngagementTilesContent(state = EngagementPreviewData.loaded, actions = EngagementActions(), modifier = Modifier.padding(12.dp))
}

@Preview(widthDp = 340, heightDp = 760, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewEngagementTilesLoadingErrorEmpty() {
    EngagementTilesContent(state = EngagementPreviewData.mixed, actions = EngagementActions(), modifier = Modifier.padding(12.dp))
}
