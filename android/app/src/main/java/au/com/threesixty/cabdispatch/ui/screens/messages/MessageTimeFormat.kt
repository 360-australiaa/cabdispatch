package au.com.threesixty.cabdispatch.ui.screens.messages

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Relative-time formatting for message timestamps — matches the reference prototype's list
 * style ("2 min ago", "14 min ago", "1 hr ago", docs/driver-dashboard-full-prototype.html's
 * static demo strings), extended with day/date fallbacks for anything the demo data didn't need
 * to cover (a real thread can span more than an hour).
 */
// Bug found in live on-device testing (2026-09-14): missing the third, LocalDateTime-as-UTC
// fallback that au.com.threesixty.cabdispatch.ui.screens.dashboard.DriverEngagementFormat.parseInstant
// already carries (see that function's own doc: "sqlite-backed dev servers emit naive UTC
// timestamps, postgres emits +00:00"). Without it, a naive timestamp -- exactly what a SQLite dev
// backend hands back, since SQLAlchemy's DateTime(timezone=True) is a no-op there -- made both
// formatters below fall through to returning the raw ISO string, which is what actually surfaced
// this: the "Vehicle already on shift" conflict dialog (LoginVehicleBindScreen.kt) reuses
// formatMessageRelativeTime for its "started <when>" line and showed
// "started 2026-09-13T15:28:01.584007" verbatim on screen instead of a relative time.
private fun parseMessageInstant(iso: String): Instant? =
    runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .recoverCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC) }
        .getOrNull()

fun formatMessageRelativeTime(sentAtIso: String, now: Instant = Instant.now()): String {
    val instant = parseMessageInstant(sentAtIso) ?: return sentAtIso
    val seconds = Duration.between(instant, now).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "Just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} hr ago"
        seconds < 86_400 * 7 -> "${seconds / 86_400}d ago"
        else -> DateTimeFormatter.ofPattern("d MMM").format(instant.atZone(ZoneId.systemDefault()))
    }
}

/** Short clock time (e.g. "9:14 AM") for the thread-detail bubble timestamps, where relative
 * "x min ago" reads worse once several messages are on screen at once. */
fun formatMessageClockTime(sentAtIso: String): String {
    val instant = parseMessageInstant(sentAtIso) ?: return sentAtIso
    return DateTimeFormatter.ofPattern("h:mm a").format(instant.atZone(ZoneId.systemDefault()))
}
