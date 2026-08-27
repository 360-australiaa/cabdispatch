package au.com.threesixty.cabdispatch.ui.screens.offlinesync

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.ui.deck.DeckButton
import au.com.threesixty.cabdispatch.ui.deck.DeckButtonKind
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * 35 · Offline & Sync — Command Deck v2 port (Figma `h0PSsXQ971dOJvt25tN7BA` node `29:30`).
 * Visual layer only — [OfflineSyncViewModel] (outbox count, tariff-cache read, force-sync
 * enqueue, network poll) is untouched; see its doc for exactly which rows are real vs.
 * deliberately not fabricated.
 *
 * v2 layout, literal from the frame: full-width amber OFFLINE banner (64dp, only while actually
 * offline — same conditional as before), H1, a 2×3 grid of 561×104 status cards (emoji · title ·
 * status line · 12dp state dot), a 300×72 [Deck.info]-blue FORCE SYNC NOW, and a ghost
 * "← Dashboard" pinned to the bottom via Spacer(weight). Zero scroll.
 *
 * Honesty notes vs. the frame (same policy as [OfflineSyncViewModel]'s doc and the
 * Permissions-screen precedent of stating "not on this build" rather than faking a state):
 * - "Driver login cache" and the "Duress path — SMS fallback" cards exist in the frame but have
 *   no backing feature anywhere in this codebase; they render as explicit not-on-this-build
 *   informational cards (neutral dot), not as fabricated healthy states.
 * - The frame's "Offline maps" card slot shows the real Network status instead (the same
 *   `ConnectivityManager` check the old version of this screen surfaced) — offline-map region
 *   state isn't exposed to this screen's ViewModel, and inventing "up to date · 2.1 GB" would be
 *   fiction.
 * - No status strip: the frame composes the shared `c/status-strip`, but on this route there is
 *   no real [au.com.threesixty.cabdispatch.ui.deck.StripStatus] source (that wiring lives on the
 *   dashboard's ViewModel), matching the other ported standalone screens.
 *
 * Navigation contract unchanged: the single back affordance pops (the old screen's two separate
 * "Back"/"DASHBOARD" buttons both did exactly that; the frame draws one ghost button).
 */
@Composable
fun OfflineSyncScreen(
    navController: NavHostController,
    viewModel: OfflineSyncViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Deck.canvas)) {
        if (state.isOffline) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Color(0xFF221407))
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("⚠", fontSize = 24.sp, color = Deck.stopped)
                Text(
                    "OFFLINE — the meter is fully operational. Trips queue locally and sync when coverage returns.",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = Deck.stopped,
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Deck.stopped))
        }

        Column(modifier = Modifier.fillMaxSize().padding(start = 72.dp, end = 72.dp, bottom = 36.dp)) {
            Spacer(Modifier.height(if (state.isOffline) 30.dp else 96.dp))
            Text(
                "Offline mode & sync status",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                color = Deck.textPrimary,
            )
            Spacer(Modifier.height(24.dp))

            val tariff = state.cachedTariff
            val cards = listOf(
                listOf(
                    SyncCard(
                        emoji = "📤",
                        title = "Trips pending sync",
                        status = if (state.pendingOutboxCount == 0) "0 — all synced" else "${state.pendingOutboxCount} queued locally",
                        tone = if (state.pendingOutboxCount == 0) SyncTone.OK else SyncTone.WARN,
                    ),
                    SyncCard(
                        emoji = "🔏",
                        title = "Cached tariff",
                        status = if (tariff != null) "${tariff.name} · Ed25519 verified offline ✓" else "No cached tariff",
                        tone = if (tariff != null) SyncTone.OK else SyncTone.WARN,
                    ),
                ),
                listOf(
                    SyncCard(
                        emoji = "👤",
                        title = "Driver login cache",
                        status = "Not on this build — PIN login is live-only",
                        tone = SyncTone.NEUTRAL,
                    ),
                    SyncCard(
                        emoji = "🆘",
                        title = "Duress path",
                        status = "Data relay via dispatch — no SMS fallback on this build",
                        tone = SyncTone.NEUTRAL,
                    ),
                ),
                listOf(
                    SyncCard(
                        emoji = "💳",
                        title = "Card payments",
                        status = "Offline capture enabled — settles on reconnect",
                        tone = SyncTone.OK,
                    ),
                    SyncCard(
                        emoji = "📶",
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

            Spacer(Modifier.height(44.dp))
            Box(
                modifier = Modifier
                    .width(300.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(Deck.R_MD.dp))
                    .background(Deck.info)
                    .clickable(onClick = viewModel::forceSyncNow),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.syncTriggeredJustNow) "⟳ SYNC REQUESTED" else "⟳ FORCE SYNC NOW",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color(0xFF04121F),
                )
            }

            Spacer(Modifier.weight(1f))
            DeckButton(text = "← Dashboard", kind = DeckButtonKind.Ghost, modifier = Modifier.width(220.dp)) {
                navController.popBackStack()
            }
        }
    }
}

private enum class SyncTone { OK, WARN, NEUTRAL }

private data class SyncCard(
    val emoji: String,
    val title: String,
    val status: String,
    val tone: SyncTone,
)

@Composable
private fun SyncStatusCard(card: SyncCard, modifier: Modifier = Modifier) {
    val dotColor = when (card.tone) {
        SyncTone.OK -> Deck.forHire
        SyncTone.WARN -> Deck.stopped
        SyncTone.NEUTRAL -> Deck.offDuty
    }
    val shape = RoundedCornerShape(Deck.R_LG.dp)
    val borderModifier = if (card.tone == SyncTone.WARN) {
        Modifier.border(1.5.dp, Deck.stopped.copy(alpha = 0.7f), shape)
    } else {
        Modifier.border(1.dp, Deck.strokeSubtle, shape)
    }
    Row(
        modifier = modifier
            .height(104.dp)
            .clip(shape)
            .background(Deck.panel)
            .then(borderModifier)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(card.emoji, fontSize = 26.sp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                card.title,
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = Deck.textPrimary,
            )
            Text(
                card.status,
                fontFamily = InterFamily,
                fontSize = 14.sp,
                color = when (card.tone) {
                    SyncTone.WARN -> Deck.stopped
                    else -> Deck.textMuted
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
