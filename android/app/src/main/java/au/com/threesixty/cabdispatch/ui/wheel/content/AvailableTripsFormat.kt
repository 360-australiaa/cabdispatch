package au.com.threesixty.cabdispatch.ui.wheel.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Small formatting/countdown helpers for the Available Trips wheel-slot list + detail screen —
 * same "own small helper file per feature" convention
 * [au.com.threesixty.cabdispatch.ui.screens.messages.MessageTimeFormat] uses, kept as its own file
 * here rather than added to [WheelContentFormat] (a sibling agent's file for the Trips/Earnings/
 * Shift slots, landed in parallel with this pass) to avoid a same-file edit race with that agent.
 */
private fun parseOfferInstant(iso: String): Instant? =
    runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .getOrNull()

/** "2 min ago" / "Just now" — matches the reference prototype's Available Trips list strings
 * (docs/driver-dashboard-full-prototype.html lines ~343-346). Falls back to the raw string if
 * [iso] doesn't parse, same defensive shape as [au.com.threesixty.cabdispatch.ui.screens.messages.formatMessageRelativeTime]. */
fun formatOfferRelativeTime(iso: String, now: Instant = Instant.now()): String {
    val instant = parseOfferInstant(iso) ?: return iso
    val seconds = Duration.between(instant, now).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "Just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        else -> "${seconds / 3600} hr ago"
    }
}

/** Whole seconds remaining until [iso] (an offer's `expires_at`); `null` if [iso] doesn't parse —
 * callers should hide the countdown rather than show a bogus value in that case. */
private fun secondsUntil(iso: String, now: Instant = Instant.now()): Long? =
    parseOfferInstant(iso)?.let { Duration.between(now, it).seconds }

/**
 * Ticks once a second for as long as the caller stays composed. Offers expire ~20s after being
 * sent (see [au.com.threesixty.cabdispatch.domain.JobsRepository] doc) — short enough that a live
 * countdown is worth the once-a-second redraw, unlike e.g. [WheelContentFormat]'s static-once
 * clock-time formatting for trip history rows.
 */
@Composable
fun rememberOfferCountdown(expiresAtIso: String): State<Long?> =
    produceState<Long?>(initialValue = secondsUntil(expiresAtIso), expiresAtIso) {
        while (true) {
            value = secondsUntil(expiresAtIso)
            delay(1000)
        }
    }
