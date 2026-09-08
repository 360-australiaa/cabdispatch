package au.com.threesixty.cabdispatch.domain.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure display-formatting helpers shared by the Trips/Earnings/Shift wheel
 * content panes and the Trip detail screen — kept as plain-Kotlin top-level
 * functions (no Compose dependency), matching the purity of
 * [au.com.threesixty.cabdispatch.ui.screens.messages.formatMessageRelativeTime]
 * (`MessageTimeFormat.kt`), the sibling Messages feature's equivalent. Lives
 * in `domain/format` rather than under any one feature's `ui/screens` sub-
 * package since 4 different feature packages (trips, earnings, shiftreport,
 * tripdetail) consume it — a neutral home avoids implying any one of them
 * owns it.
 */

/** "$18.40" — never Float/Double, see ApiService.kt header comment. */
fun BigDecimal.asMoney(): String = "$" + this.setScale(2, RoundingMode.HALF_UP).toPlainString()

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a")

/** Formats a [au.com.threesixty.cabdispatch.data.local.entity.TripEntity.startAt]-style ISO-8601 instant string as a local "9:14 AM" clock time. Falls back to the raw string if parsing fails (defensive against a malformed cached row, never crashes the pane). */
fun String.asLocalTime(): String = runCatching {
    Instant.parse(this).atZone(ZoneId.systemDefault()).format(TIME_FORMATTER)
}.getOrDefault(this)

/**
 * Human label for [au.com.threesixty.cabdispatch.data.local.entity.TripEntity.type]
 * (rank_hail | booked | airport_fixed | multi_hire). Trip history's "route"
 * column (spec §4: "trip history rows — route, time, payment method, fare
 * amount") falls back to this rather than a real origin->destination address
 * string — [au.com.threesixty.cabdispatch.data.local.entity.TripEntity] only
 * persists start/end lat/lng, not reverse-geocoded addresses (no geocoding
 * gateway exists in this module yet).
 * TODO(future agent): swap for a real "Origin → Destination" string once a
 * geocoding/reverse-geocoding gateway is wired up.
 */
fun String.asTripTypeLabel(): String = when (this) {
    "rank_hail" -> "Rank / Hail trip"
    "booked" -> "Booked trip"
    "airport_fixed" -> "Airport Fixed Fare"
    "multi_hire" -> "Multiple Hire"
    else -> this.replaceFirstChar { it.uppercase() }
}

/** "cash" | "card" | "voucher" | "account" | "split_fare" persisted value -> display label. */
fun String.asPaymentMethodLabel(): String = when (this) {
    "cash" -> "Cash"
    "card" -> "Card"
    "voucher" -> "Voucher"
    "account" -> "Account"
    "split_fare" -> "Split Fare"
    else -> this.replaceFirstChar { it.uppercase() }
}

fun String.toBigDecimalOrZero(): BigDecimal = runCatching { BigDecimal(this) }.getOrDefault(BigDecimal.ZERO)
