package au.com.threesixty.cabdispatch.ui.screens.offlinesync

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
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sos
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.PaneShell

/**
 * 35 · Offline & Sync — Captain Taxis purple redesign, moved off the yellow/black `Deck` palette
 * onto [CaptainPalette] to match the rest of this dispatch-journey group (message thread, trip
 * detail, incoming trip offer). Visual layer only — [OfflineSyncViewModel] (outbox count,
 * tariff-cache read, force-sync enqueue, network poll) is untouched; see its doc for exactly which
 * rows are real vs. deliberately not fabricated.
 *
 * Layout: shared [PaneShell] back+title header, an amber OFFLINE banner (only while actually
 * offline — same conditional as before) using a real [Icons.Rounded.WarningAmber] glyph, a 2×3
 * grid of status cards (real Material icon · title · status line · state dot), and a solid
 * FORCE SYNC NOW action. The previous separate "← Dashboard" ghost button is dropped in favour of
 * the single header back affordance, matching the other three screens in this group.
 *
 * Honesty notes vs. the previous version (same policy as [OfflineSyncViewModel]'s doc and the
 * Permissions-screen precedent of stating "not on this build" rather than faking a state):
 * - "Driver login cache" and the "Duress path — SMS fallback" cards have no backing feature
 *   anywhere in this codebase; they render as explicit not-on-this-build informational cards
 *   ([CaptainPalette.dialNeutral] dot), not as fabricated healthy states.
 * - The "Network" card shows the real `ConnectivityManager` status this screen's ViewModel
 *   already surfaced — offline-map region state isn't exposed here, and inventing "up to date ·
 *   2.1 GB" would be fiction.
 */
@Composable
fun OfflineSyncScreen(
    navController: NavHostController,
    viewModel: OfflineSyncViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    PaneShell(title = "Offline & Sync", onBack = { navController.popBackStack() }) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.isOffline) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CaptainPalette.warning.copy(alpha = 0.14f))
                        .border(1.dp, CaptainPalette.warning.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = CaptainPalette.warning, modifier = Modifier.size(26.dp))
                    Text(
                        "OFFLINE — the meter is fully operational. Trips queue locally and sync when coverage returns.",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = CaptainPalette.warning,
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            val tariff = state.cachedTariff
            val cards = listOf(
                listOf(
                    SyncCard(
                        icon = Icons.Rounded.CloudUpload,
                        title = "Trips pending sync",
                        status = if (state.pendingOutboxCount == 0) "0 — all synced" else "${state.pendingOutboxCount} queued locally",
                        tone = if (state.pendingOutboxCount == 0) SyncTone.OK else SyncTone.WARN,
                    ),
                    SyncCard(
                        icon = Icons.Rounded.VerifiedUser,
                        title = "Cached tariff",
                        status = if (tariff != null) "${tariff.name} · Ed25519 verified offline ✓" else "No cached tariff",
                        tone = if (tariff != null) SyncTone.OK else SyncTone.WARN,
                    ),
                ),
                listOf(
                    SyncCard(
                        icon = Icons.Rounded.Person,
                        title = "Driver login cache",
                        status = "Not on this build — PIN login is live-only",
                        tone = SyncTone.NEUTRAL,
                    ),
                    SyncCard(
                        icon = Icons.Rounded.Sos,
                        title = "Duress path",
                        status = "Data relay via dispatch — no SMS fallback on this build",
                        tone = SyncTone.NEUTRAL,
                    ),
                ),
                listOf(
                    SyncCard(
                        icon = Icons.Rounded.CreditCard,
                        title = "Card payments",
                        status = "Offline capture enabled — settles on reconnect",
                        tone = SyncTone.OK,
                    ),
                    SyncCard(
                        icon = Icons.Rounded.Wifi,
                        title = "Network",
                        status = if (state.isOffline) "Offline — sync resumes automatically on reconnect" else "Connected",
                        tone = if (state.isOffline) SyncTone.WARN else SyncTone.OK,
                    ),
                ),
            )
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                cards.forEach { rowCards ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        rowCards.forEach { card -> SyncStatusCard(card, modifier = Modifier.weight(1f)) }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
            SyncButton(
                text = if (state.syncTriggeredJustNow) "Sync requested" else "Force sync now",
                icon = Icons.Rounded.Sync,
                onClick = viewModel::forceSyncNow,
                modifier = Modifier.width(300.dp),
            )

            Spacer(Modifier.weight(1f))
        }
    }
}

private enum class SyncTone { OK, WARN, NEUTRAL }

private data class SyncCard(
    val icon: ImageVector,
    val title: String,
    val status: String,
    val tone: SyncTone,
)

@Composable
private fun SyncStatusCard(card: SyncCard, modifier: Modifier = Modifier) {
    val (dotColor, iconTint) = when (card.tone) {
        SyncTone.OK -> CaptainPalette.success to CaptainPalette.success
        SyncTone.WARN -> CaptainPalette.warning to CaptainPalette.warning
        SyncTone.NEUTRAL -> CaptainPalette.dialNeutral to CaptainPalette.textMuted
    }
    val shape = RoundedCornerShape(16.dp)
    val borderModifier = if (card.tone == SyncTone.WARN) {
        Modifier.border(1.5.dp, CaptainPalette.warning.copy(alpha = 0.7f), shape)
    } else {
        Modifier.border(1.dp, CaptainPalette.panelBorder, shape)
    }
    Row(
        modifier = modifier
            .height(104.dp)
            .clip(shape)
            .background(CaptainPalette.panel)
            .then(borderModifier)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(card.icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                card.title,
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = CaptainPalette.textPrimary,
            )
            Text(
                card.status,
                fontFamily = InterFamily,
                fontSize = 14.sp,
                color = when (card.tone) {
                    SyncTone.WARN -> CaptainPalette.warning
                    else -> CaptainPalette.textMuted
                },
            )
        }
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
    }
}

/**
 * Local button variant mirroring [au.com.threesixty.cabdispatch.ui.theme.CaptainButton]'s
 * press-scale/shape but with a leading icon slot — used for Force Sync Now, replacing the
 * previous "⟳" emoji glyph with a real [Icons.Rounded.Sync] icon.
 */
@Composable
private fun SyncButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, animationSpec = tween(120), label = "sync-btn-press")
    Row(
        modifier = modifier
            .height(72.dp)
            .scale(scale)
            .clip(shape)
            .background(if (pressed) CaptainPalette.primary.copy(alpha = 0.85f) else CaptainPalette.primary)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // onAccent (fixed white), not textPrimary — this sits on the solid CaptainPalette.primary
        // fill (see CaptainPalette.onAccent's doc).
        Icon(icon, contentDescription = null, tint = CaptainPalette.onAccent, modifier = Modifier.size(24.dp))
        Text(text, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = CaptainPalette.onAccent)
    }
}
