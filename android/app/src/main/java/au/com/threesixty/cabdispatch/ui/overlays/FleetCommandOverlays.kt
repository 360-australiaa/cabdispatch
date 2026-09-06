package au.com.threesixty.cabdispatch.ui.overlays

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.domain.AppUpdateState
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * App-level indicators for the fleet-command flags and connectivity state a driver should be able
 * to *see*, hosted once by [au.com.threesixty.cabdispatch.MainActivity]'s composition root so they
 * follow the driver across every screen rather than living inside whichever screen happens to be
 * open.
 *
 * [KioskLockedBanner] and [OfflineBanner] follow [DuressActiveBanner]'s precedent in this package: a
 * full-size [Box] with no `pointerInput`/`clickable` modifier anywhere, so neither is hit-testable
 * and the meter, dashboard and every dialog underneath stay fully usable. [ForceUpdatePendingBanner]
 * is the one deliberate exception (2026-09-06, real OTA self-update pass) — it now carries an actual
 * "UPDATE NOW"/"INSTALL" action, so its own action row (and only that row, via a `clickable`
 * modifier scoped to it, not the surrounding full-size [Box]) is hit-testable; see that composable's
 * own doc. None of these is a [androidx.compose.ui.window.Dialog] or `Popup` on purpose — those are
 * hosted in separate windows that keep system density, which would render them at a visibly
 * different scale from the rest of the app (see [au.com.threesixty.cabdispatch.MainActivity]'s
 * `FixedDesignCanvas` doc).
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
 *
 * [DeviceUnpairedBanner] (2026-09-06, onboarding-visibility defect pass) reuses [KioskLockedBanner]'s
 * exact bottom-START corner rather than claiming a fourth position, on a deliberate, documented
 * invariant: the two are mutually exclusive. `kioskLocked` can only ever be `true` off a value this
 * device *read back from a successful device-command-heartbeat poll* (see [DeviceCommandState]'s
 * doc), and that poll does not exist at all while unpaired ([DeviceCommandHeartbeat] hard-gates its
 * whole loop on [SessionHolder.deviceId] being non-null) — so a tablet showing this banner has
 * never once been in a position to read a `kioskLocked=true` back. If that invariant is ever broken
 * (e.g. a future change stops resetting [DeviceCommandState] to its defaults on unpairing), these
 * two would need separate corners; until then, sharing one is a deliberate simplicity choice, not
 * an oversight.
 */

/**
 * `Device.force_update_pending` — a fleet admin has flagged this tablet as needing a newer build.
 *
 * ### Real self-update, added 2026-09-06 — read the caveats below before assuming this is silent
 * This app can now genuinely check for, download, verify and launch the install of a newer build
 * on its own (`domain/AppUpdateChecker.kt`, `GET /v1/app-releases/latest`,
 * `POST /v1/platform/app-releases` on the publishing side) — the manifest now declares
 * `REQUEST_INSTALL_PACKAGES` and a `FileProvider`, and this banner drives that flow end to end.
 * This REPLACES the earlier "this tablet cannot update itself" copy, which is no longer true.
 *
 * It is still **not** a silent, zero-tap update, and this banner must never claim otherwise:
 * 1. **Knox Manage blocks it by default.** These tablets are Samsung Knox Manage device-owner
 *    enrolments, and Knox Manage's current documented policy blocks installs from unknown sources
 *    fleet-wide (`docs/KNOX_LOCKDOWN_RUNBOOK.md` §3.2). Until a Knox Manage admin adds a
 *    per-app allowlist exception for this app's package name (see `docs/OTA_UPDATE_ROLLOUT.md`),
 *    the system install step below will be refused by the OS on a real locked-down tablet — this
 *    banner cannot detect or work around that from inside the app, it can only report the
 *    `Failed` state OkHttp/PackageInstaller hands back.
 * 2. **One system confirmation tap is unavoidable.** This app is not Device Owner, so it cannot
 *    call `PackageInstaller`/`DevicePolicyManager` to install silently — [AppUpdateChecker.promptInstall]
 *    always hands off to the standard Android "install this app?" dialog, which a human at the
 *    tablet must accept. "INSTALL" below arms that handoff; it does not complete the install itself.
 *
 * ### Why non-blocking
 * A full-screen block was considered and rejected as actively harmful: the backend's heartbeat
 * clears no `force_update_pending` flag either, and the dashboard's only affordance posts
 * `{"enabled": true}` and then disables its own button — so the flag latches with no un-set path
 * in the UI. A blocking modal would permanently brick a revenue-earning meter for anyone who
 * cannot complete the Knox-exception + install-tap flow above right now. This banner stays
 * visible until an admin clears the flag through the API, which is exactly why it must not get in
 * the driver's way — non-blocking, out of the bottom CTA band (see this file's Placement note),
 * and the only clickable surface is its own compact action row (see this file's header doc).
 */
@Composable
fun ForceUpdatePendingBanner(modifier: Modifier = Modifier) {
    val activity = LocalContext.current as? Activity
    val checker = AppContainer.appUpdateChecker
    val updateState by checker.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Kick off exactly one check per time this banner appears (force_update_pending flips true) —
    // not on every recomposition. A retry after Failed is a deliberate re-tap (see the "Retry"
    // action below), not this effect firing again.
    LaunchedEffect(Unit) {
        if (updateState is AppUpdateState.Idle) {
            checker.checkForUpdate()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
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
                    text = "The depot has flagged this tablet for a newer meter build.",
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = Deck.textSecondary,
                )
            }

            when (val s = updateState) {
                is AppUpdateState.Idle, is AppUpdateState.Checking -> {
                    Text(
                        text = "Checking for an update…",
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        color = Deck.textSecondary,
                    )
                }

                is AppUpdateState.UpToDate -> {
                    // Honest: the admin's flag and the publish record disagree. Never invent a
                    // version to update to.
                    Text(
                        text = "No newer build has been published yet — nothing to update to.",
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        color = Deck.textSecondary,
                    )
                }

                is AppUpdateState.Available -> {
                    UpdateActionChip(
                        label = "UPDATE TO ${s.release.versionName}",
                        onClick = { scope.launch { checker.downloadAndVerify(s.release) } },
                    )
                }

                is AppUpdateState.Downloading -> {
                    Text(
                        text = "Downloading update… ${s.percent}%",
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        color = Deck.textSecondary,
                    )
                }

                is AppUpdateState.Verifying -> {
                    Text(
                        text = "Verifying download…",
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        color = Deck.textSecondary,
                    )
                }

                is AppUpdateState.ReadyToInstall -> {
                    // Tapping this hands off to Android's own system "install this app?"
                    // confirmation — see this composable's class doc, point 2. It is not itself the
                    // install; it only arms the one unavoidable system-confirmed step.
                    UpdateActionChip(
                        label = "INSTALL ${s.release.versionName}",
                        onClick = {
                            activity?.let { checker.promptInstall(it, s.apkFile) }
                        },
                    )
                }

                is AppUpdateState.Failed -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = s.message,
                            fontFamily = InterFamily,
                            fontSize = 13.sp,
                            color = Deck.hired,
                        )
                        UpdateActionChip(
                            label = "RETRY",
                            onClick = { scope.launch { checker.checkForUpdate() } },
                        )
                    }
                }
            }
        }
    }
}

/** Small tappable pill shared by every [ForceUpdatePendingBanner] action state above — the one
 * hit-testable surface this file's [ForceUpdatePendingBanner] introduces (see this file's header
 * doc on why every other banner here stays non-hit-testable). */
@Composable
private fun UpdateActionChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Deck.R_SM.dp))
            .background(Deck.yellow)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Deck.onYellow,
        )
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

/**
 * A quiet, permanent, non-blocking indicator that this tablet has never completed
 * `POST /v1/fleet/devices/register` — see [au.com.threesixty.cabdispatch.domain.DevicePairingStatus]'s
 * class doc for the full defect writeup and why the rego-bind path in "Bind to vehicle" cannot
 * close this gap on its own.
 *
 * Deliberately the same quiet-chip shape as [KioskLockedBanner] (same corner too — see this file's
 * "Placement" doc for the mutual-exclusivity invariant that makes sharing it safe) rather than
 * [ForceUpdatePendingBanner]'s louder top-centre panel: this is a standing operational fact a
 * driver or operator should be able to glance at and confirm, not a fresh event demanding
 * immediate action — the onboarding-time advisory in
 * [au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindScreen] is what actually surfaces
 * this at the one moment a decision could be made about it; this banner's job is only to keep it
 * from ever going quiet again afterwards. Static text, no animation (see this app's standing
 * "no new looping/timer-driven animation" rule) — it appears and disappears solely by composing in
 * or out of [au.com.threesixty.cabdispatch.MainActivity]'s `if`, exactly like [KioskLockedBanner]/
 * [ForceUpdatePendingBanner] above.
 *
 * Not hit-testable (no `clickable`/`pointerInput`), same reasoning as [KioskLockedBanner]: the real
 * fix lives in Settings ▸ About ▸ Device heartbeat (or ▸ Pair Meter), which needs a code only an
 * operator can generate — this banner cannot honestly offer a one-tap fix for something it cannot
 * do itself, so it names where the fix lives in text instead of pretending a tap here would do it.
 */
@Composable
fun DeviceUnpairedBanner(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, bottom = 10.dp)
                .clip(RoundedCornerShape(Deck.R_SM.dp))
                .background(Deck.panel.copy(alpha = 0.9f))
                .border(1.dp, Deck.stopped.copy(alpha = 0.6f), RoundedCornerShape(Deck.R_SM.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Deck.stopped),
            )
            Column {
                Text(
                    text = "TABLET NOT REGISTERED",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = Deck.stopped,
                )
                Text(
                    text = "Remote lock/locate/update unavailable — pair via Settings ▸ About",
                    fontFamily = InterFamily,
                    fontSize = 10.sp,
                    color = Deck.textSecondary,
                )
            }
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
