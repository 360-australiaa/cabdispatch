package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.remember
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
import au.com.threesixty.cabdispatch.ui.theme.Radius
import au.com.threesixty.cabdispatch.ui.theme.Space
import au.com.threesixty.cabdispatch.ui.theme.Type
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
    /** Lays the four tiles out as a 2x2 grid rather than a vertical stack (A3) - see
     * [EngagementTilesContent] for why the stack had to go. */
    twoColumn: Boolean = false,
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
        twoColumn = twoColumn,
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

/**
 * THE LAYOUT CHANGE (A3, 2026-09-08). [twoColumn] lays the four tiles out as a 2x2 grid.
 *
 * The vertical stack this replaces put roughly 700-800dp of tile into a ~461dp scroller with no
 * scrollbar, no peek and no scroll indicator. Wallet was visible; Rating, Announcements and
 * Incentives were below an invisible fold. The audit's finding was blunt: *"The driver never sees
 * them."* That is the concrete content behind the owner's *"make the dashboard screen more
 * accessible with all the features"* - the features existed and were unreachable.
 *
 * THE VISIBILITY CHANGE. Announcements and Incentives now render **nothing at all** when they have
 * loaded successfully and are genuinely empty, per the owner on the dispatch card: *"If it's
 * empty, why we are showing it"*. The rule and its two deliberate exceptions:
 *
 * - **Announcements / Incentives - hidden when empty.** "No announcements" is not information a
 *   driver ever needs. Nothing is being withheld: if one arrives, the tile appears.
 * - **Wallet - ALWAYS shown, including at $0.00.** A zero balance is a real, actionable number
 *   about the driver's own money; hiding it would be hiding a fact, not hiding an absence.
 * - **Rating - ALWAYS shown, including "No ratings yet".** Same reasoning: a new driver's empty
 *   rating is a true statement about their standing, and its absence would read as the app having
 *   lost it.
 *
 * An **error is never hidden**, in any section. `error != null` forces the tile to render even
 * when the data is empty, so a failed load can never be silently disguised as "nothing to show" -
 * exactly the confusion this pass removed from the dispatch card.
 */
@Composable
fun EngagementTilesContent(
    state: DriverEngagementUiState,
    actions: EngagementActions,
    modifier: Modifier = Modifier,
    twoColumn: Boolean = false,
) {
    var showTopUpInfo by rememberSaveable { mutableStateOf(false) }
    var reading by remember { mutableStateOf<AnnouncementDto?>(null) }

    // Empty AND error-free = not worth a tile. See this composable's own doc.
    val showAnnouncements = state.announcements.error != null ||
        state.announcements.data?.isNotEmpty() != false
    val showIncentives = state.incentives.error != null ||
        state.incentives.data?.isNotEmpty() != false

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Space.smd)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("MY ACCOUNT", style = Type.label, letterSpacing = 2.sp, color = CaptainPalette.textMuted)
            Spacer(Modifier.weight(1f))
            RefreshControl(refreshing = state.refreshing, onClick = actions.onRefreshAll)
        }
        // Each entry is a @Composable taking the Modifier its slot in the layout decides on, so
        // the same four tiles render either stacked or gridded without being written twice. The
        // lambdas carry an explicit @Composable annotation because Kotlin will not infer it onto a
        // lambda literal passed to a plain `add`.
        val tiles: List<@Composable (Modifier) -> Unit> = buildList {
            add(@Composable { m: Modifier ->
                WalletTile(section = state.wallet, onRetry = actions.onRetryWallet, onAddFunds = { showTopUpInfo = true }, modifier = m)
            })
            add(@Composable { m: Modifier ->
                RatingTile(section = state.rating, onRetry = actions.onRetryRating, modifier = m)
            })
            if (showAnnouncements) {
                add(@Composable { m: Modifier ->
                    AnnouncementsTile(
                        section = state.announcements,
                        onRetry = actions.onRetryAnnouncements,
                        onOpen = { item -> reading = item },
                        modifier = m,
                    )
                })
            }
            if (showIncentives) {
                add(@Composable { m: Modifier ->
                    IncentiveTile(section = state.incentives, onRetry = actions.onRetryIncentives, modifier = m)
                })
            }
        }
        if (twoColumn) {
            // A plain Column of Rows rather than LazyVerticalGrid: there are at most four tiles,
            // they are all composed anyway, and a lazy grid nested in this pane's own vertical
            // space would need a bounded height it cannot be given here.
            tiles.chunked(2).forEach { rowTiles ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.smd),
                ) {
                    rowTiles.forEach { tile -> tile(Modifier.weight(1f)) }
                    // Keeps a lone final tile at half width instead of letting it stretch across
                    // the whole row and break the grid's rhythm.
                    if (rowTiles.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            tiles.forEach { tile -> tile(Modifier.fillMaxWidth()) }
        }
    }
    if (showTopUpInfo) TopUpInfoDialog(onDismiss = { showTopUpInfo = false })
    reading?.let { AnnouncementDialog(item = it, onDismiss = { reading = null }) }
}

// ============================================================================================
// Wallet
// ============================================================================================

@Composable
private fun WalletTile(
    section: EngagementSection<WalletDto>,
    onRetry: () -> Unit,
    onAddFunds: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val balance = section.data?.let { Fmt.parseDecimal(it.balanceAud) }
    val negative = balance != null && balance.signum() < 0
    GlassCard(modifier = modifier, glow = if (negative) CaptainPalette.danger else null) {
        Column(modifier = Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            TileHeader(icon = Icons.Rounded.AccountBalanceWallet, label = "WALLET BALANCE")
            SectionBody(section = section, onRetry = onRetry, isEmpty = { false }, emptyText = "") { wallet ->
                au.com.threesixty.cabdispatch.ui.theme.RollingMoneyText(
                    amount = Fmt.formatAud(wallet.balanceAud),
                    fontSize = 28.sp,
                    color = if (negative) CaptainPalette.danger else CaptainPalette.textPrimary,
                )
                // 3 -> 2 (A3): the tile is half-width in the 2x2 grid, so it trades one ledger
                // line for the grid that makes all four tiles visible at once.
                val recent = wallet.recent.take(2)
                if (recent.isEmpty()) {
                    Text("No transactions yet", fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.textMuted)
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
                fontSize = 13.sp,
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add funds", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = CaptainPalette.textPrimary)
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
private fun RatingTile(section: EngagementSection<RatingDto>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier) {
        Column(modifier = Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            TileHeader(icon = Icons.Rounded.Star, label = "RATING")
            SectionBody(section = section, onRetry = onRetry, isEmpty = { false }, emptyText = "") { rating ->
                val rated = rating.ratingCount > 0
                val average = if (rated) Fmt.formatAverage(rating.averageStars) else null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        average ?: "—",
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                        color = if (average != null) CaptainPalette.textPrimary else CaptainPalette.textMuted,
                    )
                    Spacer(Modifier.width(Space.smd))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StarRow(fills = Fmt.starFills(if (rated) Fmt.parseDecimal(rating.averageStars)?.toDouble() else null))
                        Text(
                            if (rated) "(${Fmt.ratingCountLabel(rating.ratingCount)})" else "No ratings yet",
                            fontFamily = InterFamily,
                            fontSize = 13.sp,
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
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}

// ============================================================================================
// Announcements
// ============================================================================================

private const val MAX_ANNOUNCEMENTS_SHOWN = 5

@Composable
private fun AnnouncementsTile(
    section: EngagementSection<List<AnnouncementDto>>,
    onRetry: () -> Unit,
    onOpen: (AnnouncementDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Column(modifier = Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            TileHeader(icon = Icons.Rounded.Campaign, label = "ANNOUNCEMENTS")
            SectionBody(section = section, onRetry = onRetry, isEmpty = { it.isEmpty() }, emptyText = "No announcements") { items ->
                // Backend already orders newest-first; re-sorting here keeps that true even if a
                // future server build changes its default order.
                val shown = items
                    .sortedByDescending { Fmt.parseInstant(it.startsAt) ?: Instant.EPOCH }
                    .take(MAX_ANNOUNCEMENTS_SHOWN)
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    shown.forEach { item -> AnnouncementRow(item, onClick = { onOpen(item) }) }
                }
            }
            StaleLine(section)
        }
    }
}

@Composable
private fun AnnouncementRow(item: AnnouncementDto, onClick: () -> Unit) {
    val tone = when (item.kind) {
        "maintenance" -> CaptainPalette.warning
        "surge" -> CaptainPalette.hudAccent
        "feature" -> CaptainPalette.success
        else -> CaptainPalette.textSecondary
    }
    // TAPPABLE (A3). These rows were `maxLines = 1` with an ellipsis and NO way to read the rest
    // - an operator could post "Rank closed from 6pm, use the Elizabeth St stand instead" and the
    // driver would see "Rank closed from 6pm, use the Eliz...". The truncation is fine; having no
    // way past it was not. 48dp minimum target, opens the full text in a dialog.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .padding(vertical = Space.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.padding(top = 6.dp).size(9.dp).clip(CircleShape).background(tone))
        Column(modifier = Modifier.padding(start = Space.sm).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                fontSize = 13.sp,
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
private fun IncentiveTile(
    section: EngagementSection<List<IncentiveProgressDto>>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val anyAchieved = section.data?.any { it.achieved } == true
    GlassCard(modifier = modifier, glow = if (anyAchieved) CaptainPalette.success else null) {
        Column(modifier = Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            TileHeader(icon = Icons.Rounded.EmojiEvents, label = "INCENTIVE PROGRESS")
            SectionBody(section = section, onRetry = onRetry, isEmpty = { it.isEmpty() }, emptyText = "No active incentives") { items ->
                Column(verticalArrangement = Arrangement.spacedBy(Space.smd)) {
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
        Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            HudRing(progress = fraction, modifier = Modifier.size(52.dp), strokeWidthDp = 5)
            Text(
                "${(fraction * 100).toInt()}%",
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (done) CaptainPalette.success else CaptainPalette.textPrimary,
            )
        }
        Column(modifier = Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
                        Text("COMPLETED", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp, color = CaptainPalette.success)
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
            Text("Loading…", fontFamily = InterFamily, fontSize = 15.sp, color = CaptainPalette.textMuted, modifier = Modifier.padding(start = 8.dp))
        }
        section.error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = CaptainPalette.danger, modifier = Modifier.size(16.dp))
                Text(section.error, fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.danger, modifier = Modifier.padding(start = 8.dp))
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
    // STATE-GATED (A3). The transition used to be created and run unconditionally, with the angle
    // merely IGNORED unless `refreshing` - so the app burned frames spinning an invisible number
    // for the entire life of the screen. Now the whole animation only exists while a refresh is
    // genuinely in flight; the rest of the time this composable produces no frames at all.
    //
    // This is a real spinner on a real in-flight request, which is why it is a legitimate loop and
    // not one of the three sanctioned decorative ones: it is bounded by a network call, and it is
    // the standard "work in progress" affordance.
    val angle = if (refreshing) {
        val spin = rememberInfiniteTransition(label = "engagement-refresh")
        spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "engagement-refresh-angle",
        ).value
    } else {
        0f
    }
    Box(
        modifier = Modifier
            .size(48.dp) // 44 -> 48dp (A3): the documented minimum, which this was just under.
            .clip(CircleShape)
            .gameClick(onClick = onClick, shape = CircleShape, glowColor = CaptainPalette.hudAccent, enabled = !refreshing),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Refresh,
            contentDescription = "Refresh",
            tint = if (refreshing) CaptainPalette.hudAccent else CaptainPalette.textSecondary,
            modifier = Modifier.size(22.dp).rotate(angle),
        )
    }
}

// ============================================================================================
// Previews — PREVIEW-ONLY DATA. Nothing below is reachable at runtime; the live tiles are fed
// exclusively by DriverEngagementViewModel over the real /v1/me/{wallet,rating,announcements,incentives} endpoints.
// ============================================================================================

/**
 * The tiles rendered with [EngagementPreviewData] in the 2x2 grid, for the home-screen previews in
 * HomeScreenPreviews.kt - [DriverEngagementTiles] itself needs a ViewModel, which a preview
 * process has no way to supply. Preview-only, same as everything else below this line.
 */
@Composable
internal fun EngagementTilesPreviewBody(modifier: Modifier = Modifier) {
    EngagementTilesContent(
        state = EngagementPreviewData.loaded,
        actions = EngagementActions(),
        twoColumn = true,
        modifier = modifier,
    )
}

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

/**
 * The full text of one announcement (A3) - the destination for [AnnouncementRow]'s new tap.
 *
 * Deliberately plain: title, kind, full untruncated body, and when it started. No actions, because
 * an announcement has none - there is no acknowledge, dismiss or mark-read endpoint anywhere in
 * this app's API surface, and inventing a button that quietly did nothing would be exactly the
 * kind of fake affordance the rest of this file exists to avoid.
 */
@Composable
private fun AnnouncementDialog(item: AnnouncementDto, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(Radius.lg))
                .background(CaptainPalette.panel)
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(Radius.lg))
                .padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.smd),
        ) {
            Text(item.title, style = Type.h1, color = CaptainPalette.textPrimary)
            val when_ = Fmt.relativeTime(item.startsAt)
            if (when_.isNotEmpty()) {
                Text(when_, style = Type.tiny, color = CaptainPalette.textMuted)
            }
            Text(item.body, style = Type.body, color = CaptainPalette.textSecondary)
            CaptainButton(text = "Close", heightDp = 56, fontSize = 18.sp, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}
