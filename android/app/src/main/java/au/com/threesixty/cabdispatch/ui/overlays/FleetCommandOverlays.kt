package au.com.threesixty.cabdispatch.ui.overlays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * App-level indicators for the two fleet-command flags a driver should be able to *see*, hosted
 * once by [au.com.threesixty.cabdispatch.MainActivity]'s composition root so they follow the
 * driver across every screen rather than living inside whichever screen happens to be open.
 *
 * Both follow [DuressActiveBanner]'s precedent in this package: a full-size [Box] with no
 * `pointerInput`/`clickable` modifier anywhere, so neither is hit-testable and the meter,
 * dashboard and every dialog underneath stay fully usable. Neither is a [androidx.compose.ui.window.Dialog]
 * or `Popup` on purpose — those are hosted in separate windows that keep system density, which
 * would render them at a visibly different scale from the rest of the app (see
 * [au.com.threesixty.cabdispatch.MainActivity]'s `FixedDesignCanvas` doc).
 *
 * ### Placement
 * [DuressActiveBanner]'s stealth lamp owns bottom-END, so kiosk lock — a small, quiet chip — takes
 * bottom-START. Force-update is the wide one and now sits **top-centre, under the header strip**.
 * It was bottom-centre until the 2026-08-29 review pass, which is the one band of a 1280×800 v2
 * screen that is never free: every screen pins its primary CTAs there (`Deck.CTA_H` = 88dp rows —
 * START METER, END SHIFT, the payment actions). Not being hit-testable meant taps still landed, but
 * a driver was tapping money-moving controls they could only half see, on every screen, for as long
 * as the flag stayed set — and it latches with no client-side clear (below). Top-centre clears the
 * 44dp in-shift status strip ([au.com.threesixty.cabdispatch.ui.theme.Deck.STATUS_STRIP_H]). That
 * strip is not universal, though: login, shift-start and settings do not carry one (see
 * `DeckChrome.kt`'s `DeckStatusStrip`, "persists on every in-shift screen"), so on those the banner
 * overlays the top of the content area. Visual only — nothing in this file is clickable or takes a
 * pointerInput — and strictly better than the CTA band it replaced, but not free.
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
