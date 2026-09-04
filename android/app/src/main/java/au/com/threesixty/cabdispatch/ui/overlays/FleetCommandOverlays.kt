package au.com.threesixty.cabdispatch.ui.overlays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import kotlinx.coroutines.delay

/**
 * App-level indicators for the fleet-command flags and connectivity state a driver should be able
 * to *see*, hosted once by [au.com.threesixty.cabdispatch.MainActivity]'s composition root so they
 * follow the driver across every screen rather than living inside whichever screen happens to be
 * open.
 *
 * All three follow [DuressActiveBanner]'s precedent in this package: a full-size [Box] with no
 * `pointerInput`/`clickable` modifier anywhere, so none is hit-testable and the meter,
 * dashboard and every dialog underneath stay fully usable. None is a [androidx.compose.ui.window.Dialog]
 * or `Popup` on purpose — those are hosted in separate windows that keep system density, which
 * would render them at a visibly different scale from the rest of the app (see
 * [au.com.threesixty.cabdispatch.MainActivity]'s `FixedDesignCanvas` doc).
 *
 * ### Placement
 * [DuressActiveBanner]'s stealth lamp owns bottom-END, so kiosk lock — a small, quiet chip — takes
 * bottom-START. Force-update is the wide one and sits **top-centre, under the header strip**. It
 * was bottom-centre until the 2026-08-29 review pass, which is the one band of a 1280×800 v2
 * screen that is never free: every screen pins its primary CTAs there (`Deck.CTA_H` = 88dp rows —
 * START METER, END SHIFT, the payment actions). Not being hit-testable meant taps still landed, but
 * a driver was tapping money-moving controls they could only half see, on every screen, for as long
 * as the flag stayed set — and it latches with no client-side clear (below). Top-centre clears the
 * 44dp in-shift status strip ([au.com.threesixty.cabdispatch.ui.theme.Deck.STATUS_STRIP_H]). That
 * strip is not universal, though: login, shift-start and settings do not carry one (see
 * `DeckChrome.kt`'s `DeckStatusStrip`, "persists on every in-shift screen"), so on those the banner
 * overlays the top of the content area. Visual only — nothing in this file is clickable or takes a
 * pointerInput — and strictly better than the CTA band it replaced, but not free.
 *
 * [OfflineBanner] (2026-09-05) deliberately takes a THIRD position — a slim full-width strip
 * pinned at the very top edge (y=0), not [ForceUpdatePendingBanner]'s centred pill 54dp further
 * down — so the two never occupy the same pixels when both happen to be true at once (offline +
 * force-update-pending is a perfectly real combination: a fleet admin flagged this tablet while it
 * had no signal to receive the flag over, until the next reconnect). It is thinner than either
 * existing banner specifically so that when it does overlay a screen with no dedicated top margin
 * (the meter's dial/map pills, the dashboard header), only the very top sliver of that content's
 * rounded-corner backdrop is touched, not its readable text/icons — the same "not free, but the
 * right trade-off for an app-wide signal" reasoning [ForceUpdatePendingBanner] above already
 * accepts for the exact same reason.
 */

/**
 * `Device.force_update_pending` — a fleet admin has flagged this tablet as needing a newer build.
 *
 * ### Why this is a notice and not an "Update now" button
 * This app has no self-update channel of any kind, and this banner deliberately does not imply
 * one. The fleet tablets are Samsung Knox Manage kiosks with app install/uninstall blocked from
 * any source *and* unknown-sources installs blocked (`docs/KNOX_LOCKDOWN_RUNBOOK.md` §3.2); the
 * manifest declares no `REQUEST_INSTALL_PACKAGES`, so the app could not install a downloaded APK
 * even if Knox allowed it; Play Store packaging is on `PROJECT_HANDOFF.md`'s "not done anywhere"
 * list, so there is no in-app-update path either; and the API carries no target-version or APK-URL
 * field anywhere, so the app cannot even name the version it is supposed to be on. Updates reach
 * these tablets only via Knox Manage's console app-deployment, performed by a human at the depot.
 * The copy therefore states only what is true and actionable, names no version number, offers no
 * button, and runs no countdown.
 *
 * ### Why non-blocking
 * A full-screen block was considered and rejected as actively harmful: the backend's heartbeat
 * clears no flag, and the dashboard's only affordance posts `{"enabled": true}` and then disables
 * its own button — so `force_update_pending` latches with no un-set path in the UI. A blocking
 * modal would permanently brick a revenue-earning meter with no way out for the admin who pressed
 * the button. This banner will likewise stay visible until an admin clears the flag through the
 * API, which is exactly why it must not get in the driver's way — non-blocking, not hit-testable,
 * and out of the bottom CTA band (see this file's Placement note).
 */
@Composable
fun ForceUpdatePendingBanner(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                // Top-centre, cleared past the 44dp status strip — deliberately NOT bottom-centre,
                // which is every v2 screen's primary-CTA band. See this file's Placement note.
                .align(Alignment.TopCenter)
                .padding(top = (Deck.STATUS_STRIP_H + 10).dp)
                .widthIn(max = 720.dp)
                .clip(RoundedCornerShape(Deck.R_MD.dp))
                .background(Deck.panel)
                .border(1.dp, Deck.stopped.copy(alpha = 0.8f), RoundedCornerShape(Deck.R_MD.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Deck.stopped),
            )
            Text(
                text = "UPDATE PENDING",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Deck.stopped,
            )
            Text(
                text = "The depot needs to install a newer meter build — this tablet cannot update itself.",
                fontFamily = InterFamily,
                fontSize = 14.sp,
                color = Deck.textSecondary,
            )
        }
    }
}

/**
 * `Device.kiosk_locked` — a quiet, permanent indicator that a fleet admin has this tablet locked
 * to the meter app.
 *
 * Exists so the lock is not silent: with the status and navigation bars hidden by the Knox kiosk
 * policy, a driver whose Home/Recents suddenly stop responding has no other way to tell an admin
 * action from a frozen tablet, and would reasonably report it as a fault. It says only that the
 * lock was *requested and applied for*; it deliberately does not claim the OS actually granted the
 * pin, because [android.app.Activity.startLockTask] returns `void` and gives no result — see
 * [au.com.threesixty.cabdispatch.MainActivity]'s doc for the full write-up of what screen pinning
 * does and does not guarantee.
 */
@Composable
fun KioskLockedBanner(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, bottom = 10.dp)
                .clip(RoundedCornerShape(Deck.R_SM.dp))
                .background(Deck.panel.copy(alpha = 0.9f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "🔒",
                fontFamily = InterFamily,
                fontSize = 12.sp,
                color = Deck.info,
            )
            Text(
                text = "FLEET LOCKED",
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = Deck.info,
            )
        }
    }
}

/** How long the post-reconnect "syncing" follow-up state stays up at most — [SyncWorker] itself
 * gives no synchronous "batch done" callback this composable could await (see [OutboxDrainer]'s
 * doc: it runs on a WorkManager coroutine, not this composition), so this is a generous ceiling,
 * not a measured duration. [observeOutboxSize] reaching 0 before this elapses clears the follow-up
 * early — see [OfflineBanner]'s own doc. */
private const val SYNCING_FOLLOWUP_MAX_MS = 12_000L

/**
 * Real, live "you are offline" indicator — see [AppContainer.connectivitySyncTrigger]'s own doc
 * for why this reads [au.com.threesixty.cabdispatch.sync.ConnectivitySyncTrigger.isOnline] rather
 * than standing up a second `ConnectivityManager` detector: it is the exact same
 * `activeNetwork`/[android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET] check
 * [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel]'s Network diagnostic tile
 * already polls for, just made push-driven and process-wide instead of one screen's own poll loop.
 *
 * Two states, never fabricated:
 * 1. **Offline** — shown for as long as [ConnectivitySyncTrigger.isOnline] is false, regardless of
 *    which screen is open (including mid-fare on [au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen]
 *    — nothing about the live meter ever depends on connectivity, see
 *    `domain/FareEngine.kt`/`domain/fare/FareEngine.kt`'s own docs). Disappears the instant
 *    connectivity returns.
 * 2. **Syncing follow-up** — real queued work, not a fabricated spinner: the instant connectivity
 *    returns, if [au.com.threesixty.cabdispatch.data.repository.TripRepository.observeOutboxSize]
 *    (the same pending-count signal [au.com.threesixty.cabdispatch.ui.screens.offlinesync.OfflineSyncScreen]
 *    already surfaces) was non-zero at that moment, this banner stays up in a second, distinctly
 *    worded state ("back online, syncing…") until either the outbox actually drains to zero or
 *    [SYNCING_FOLLOWUP_MAX_MS] elapses (whichever first) — [ConnectivitySyncTrigger]'s own
 *    `onAvailable` is what enqueues that real drain ([SyncWorker.enqueueOneTime]), this composable
 *    only observes the same Room-backed count, never triggers or fakes a sync itself.
 *
 * See this file's class-level "Placement" doc for why this is a slim top-edge strip, not a pill
 * at [ForceUpdatePendingBanner]'s position.
 */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    val isOnline by AppContainer.connectivitySyncTrigger.isOnline.collectAsState()
    val pendingCount by AppContainer.tripRepository.observeOutboxSize().collectAsState(initial = 0)

    var wasOffline by remember { mutableStateOf(!isOnline) }
    var syncingFollowUp by remember { mutableStateOf(false) }

    LaunchedEffect(isOnline) {
        if (isOnline && wasOffline && pendingCount > 0) {
            syncingFollowUp = true
            delay(SYNCING_FOLLOWUP_MAX_MS)
            syncingFollowUp = false
        }
        wasOffline = !isOnline
    }
    // Clears the follow-up the moment the real outbox actually empties, rather than always riding
    // out the full ceiling above — an honest "synced" signal, not a fixed-length animation.
    LaunchedEffect(pendingCount, syncingFollowUp) {
        if (syncingFollowUp && pendingCount == 0) syncingFollowUp = false
    }

    val offline = !isOnline
    if (!offline && !syncingFollowUp) return

    val accent = if (offline) Deck.offDuty else Deck.info
    val headline = if (offline) "OFFLINE" else "BACK ONLINE"
    val detail = if (offline) {
        "Working offline — the meter keeps running and will sync when reconnected."
    } else {
        val n = pendingCount
        if (n > 0) "Syncing $n queued ${if (n == 1) "trip" else "trips"}…" else "Syncing…"
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Deck.panel.copy(alpha = 0.96f))
                    .padding(horizontal = 16.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
                Text(
                    text = headline,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp,
                    color = accent,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Text(
                    text = "  —  $detail",
                    fontFamily = InterFamily,
                    fontSize = 11.sp,
                    color = Deck.textSecondary,
                    maxLines = 1,
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(accent.copy(alpha = 0.7f)))
        }
    }
}
