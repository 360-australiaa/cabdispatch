package au.com.threesixty.cabdispatch.ui.screens.dashboard

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

/** How one of the five rating stars is painted — see [DriverEngagementFormat.starFills]. */
enum class StarFill { FULL, HALF, EMPTY }

/**
 * Pure (no Compose/Android) display mapping for the dashboard's driver-engagement tiles
 * ([EngagementTiles]) — kept as a standalone object, same as `HudRoll` next to `RollingMoneyText`,
 * so the star-fill mapping / progress fraction / money formatting are plain-JVM unit-testable
 * (`DriverEngagementFormatTest`).
 *
 * Every function here is total over the wire types it's given: a decimal string that doesn't
 * parse, a null average, a zero target — all degrade to a visibly-empty rendering (`"—"`, empty
 * stars, 0 progress), never to a fabricated value.
 */
object DriverEngagementFormat {

    const val STAR_COUNT = 5

    /**
     * Maps an average (e.g. `4.3`) onto [STAR_COUNT] stars. Per star `i` (1-based), the fraction
     * of that star covered is `avg - (i - 1)`, painted FULL from 0.75, HALF from 0.25, EMPTY below
     * — so 4.3 → ★★★★☆ with the 5th at "0.3 covered" → HALF; 4.8 → the 5th FULL; 4.1 → EMPTY.
     * `null` (no ratings yet) → all EMPTY, deliberately indistinguishable from a 0.0 average so
     * an unrated driver is never painted with a stand-in score.
     */
    fun starFills(averageStars: Double?): List<StarFill> {
        val avg = averageStars?.takeIf { it.isFinite() }?.coerceIn(0.0, STAR_COUNT.toDouble()) ?: 0.0
        return (1..STAR_COUNT).map { i ->
            val covered = avg - (i - 1)
            when {
                covered >= 0.75 -> StarFill.FULL
                covered >= 0.25 -> StarFill.HALF
                else -> StarFill.EMPTY
            }
        }
    }

    /** `completed / target` clamped to `0f..1f`; a non-positive target (impossible from the
     * backend, which validates `target_trips > 0`, but cheap to guard) is 0 progress, not NaN. */
    fun incentiveFraction(completedTrips: Int, targetTrips: Int): Float {
        if (targetTrips <= 0) return 0f
        return (completedTrips.toFloat() / targetTrips.toFloat()).coerceIn(0f, 1f)
    }

    /** Parses a backend decimal-as-string; `null` if it isn't a number. */
    fun parseDecimal(raw: String?): BigDecimal? = raw?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { BigDecimal(it) }.getOrNull()
    }

    /** `"123.4"` → `"$123.40"`, `"-12"` → `"-$12.00"`, unparseable → `"—"`. Grouping per en-AU
     * (`"$1,240.00"`). */
    fun formatAud(raw: String?): String {
        val value = parseDecimal(raw) ?: return "—"
        return formatAud(value)
    }

    fun formatAud(value: BigDecimal): String {
        val scaled = value.setScale(2, RoundingMode.HALF_EVEN)
        val magnitude = String.format(Locale.US, "%,.2f", scaled.abs())
        return if (scaled.signum() < 0) "-$$magnitude" else "$$magnitude"
    }

    /** Ledger-line variant: always carries a sign (`"+$32.40"` / `"-$50.00"`); zero is `"$0.00"`. */
    fun formatSignedAud(raw: String?): String {
        val value = parseDecimal(raw) ?: return "—"
        val base = formatAud(value)
        return if (value.signum() > 0) "+$base" else base
    }

    /** One-decimal average (`"4.8"`), or `null` when there is no average to show. */
    fun formatAverage(raw: String?): String? =
        parseDecimal(raw)?.setScale(1, RoundingMode.HALF_EVEN)?.toPlainString()

    /** `1240` → `"1,240"`. */
    fun formatCount(count: Int): String = String.format(Locale.US, "%,d", count)

    /** `"1,240 ratings"` / `"1 rating"`. */
    fun ratingCountLabel(count: Int): String =
        "${formatCount(count)} ${if (count == 1) "rating" else "ratings"}"

    /** Human label for a `WalletTransactionRead.kind`; unknown kinds fall back to the raw value
     * with underscores spaced out rather than being hidden. */
    fun ledgerKindLabel(kind: String): String = when (kind) {
        "trip_earning" -> "Trip earning"
        "top_up" -> "Top-up"
        "adjustment" -> "Adjustment"
        "payout" -> "Payout"
        else -> kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** ISO-8601 with or without offset (sqlite-backed dev servers emit naive UTC timestamps,
     * postgres emits `+00:00`); `null` if it isn't a timestamp at all. */
    fun parseInstant(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso) }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
            .recoverCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC) }
            .getOrNull()
    }

    /** `"Just now"` / `"5 min ago"` / `"3 hr ago"` / `"Yesterday"` / `"4 days ago"`; a timestamp
     * that doesn't parse renders as `""` (the caller simply omits the time) rather than the raw
     * string. A timestamp slightly in the future (clock skew) is "Just now". */
    fun relativeTime(iso: String?, now: Instant = Instant.now()): String {
        val instant = parseInstant(iso) ?: return ""
        val seconds = Duration.between(instant, now).seconds.coerceAtLeast(0)
        return when {
            seconds < 60 -> "Just now"
            seconds < 3_600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3_600} hr ago"
            seconds < 2 * 86_400 -> "Yesterday"
            else -> "${seconds / 86_400} days ago"
        }
    }

    /** `"Ends in 3 days"` / `"Ends in 5 hr"` / `"Ends in 20 min"` / `"Ends soon"` for an incentive
     * window; `"Ended"` once past; `""` if unparseable. */
    fun endsInLabel(iso: String?, now: Instant = Instant.now()): String {
        val instant = parseInstant(iso) ?: return ""
        val seconds = Duration.between(now, instant).seconds
        return when {
            seconds < 0 -> "Ended"
            seconds < 60 -> "Ends soon"
            seconds < 3_600 -> "Ends in ${seconds / 60} min"
            seconds < 86_400 -> "Ends in ${seconds / 3_600} hr"
            else -> "Ends in ${seconds / 86_400} days"
        }
    }
}
