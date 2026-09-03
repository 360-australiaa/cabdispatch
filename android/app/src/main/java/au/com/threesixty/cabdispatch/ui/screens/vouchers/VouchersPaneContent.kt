package au.com.threesixty.cabdispatch.ui.screens.vouchers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.VoucherDto
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.CaptainPanel
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Nav rail `VOUCHERS` pane (plan `squishy-herding-iverson.md`, Phase G) — a real Available/Used/
 * Expired browse screen over the live voucher ledger (`backend/app/api/v1/vouchers.py`, landed by
 * the separate SaaS-platform Phase 3 workstream, commit `1f93840`). Before this pass the rail's
 * VOUCHERS item didn't open a real screen at all: it opened [au.com.threesixty.cabdispatch.ui.screens.dashboard.VoucherInfoDialog],
 * a small honest "there's no voucher wallet in this app yet" placeholder — `RAIL_ITEMS` in
 * `DeckHomeScreen.kt` now points VOUCHERS at this pane instead. The Dashboard's own MeterCard
 * "VOUCHERS · Redeemed at payment" quick-action tile is untouched and still opens that same small
 * dialog, which stays true (redemption still only happens at Close & Pay, never from this pane —
 * see the CHECK A VOUCHER panel's doc below).
 *
 * Fetches `GET /v1/vouchers` unfiltered (`redeemed = null, limit = 200` — same endpoint/DTO
 * [au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayViewModel] already calls for the
 * Close & Pay payment grid's "N Available" count) via a screen-local loader, matching the same
 * convention [au.com.threesixty.cabdispatch.ui.screens.pricing.PricingPaneContent] uses — this pane
 * has no other state to justify a dedicated ViewModel/repository. The backend has no
 * `redeemed`+expiry combined filter and no search-by-code endpoint, so both the three-tab bucketing
 * and the code lookup below are done client-side over this one fetched page (200 is this tenant's
 * whole ledger for any realistic voucher volume; a tenant that outgrows it is a real future
 * pagination gap, not something this pane papers over).
 *
 * Tab buckets, computed from [VoucherDto.redeemedAt]/[VoucherDto.expiresAt] — never a server-side
 * concept, since the backend only exposes a redeemed/not-redeemed split:
 * - **Available**: not redeemed, and `expiresAt` is null or still in the future.
 * - **Used**: `redeemedAt` set (regardless of `expiresAt` — once redeemed, expiry is moot).
 * - **Expired**: not redeemed, but `expiresAt` has passed.
 */
@Composable
fun VouchersPaneContent(modifier: Modifier = Modifier) {
    var vouchers by remember { mutableStateOf<List<VoucherDto>?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(VoucherTab.AVAILABLE) }

    LaunchedEffect(Unit) {
        val result = runCatching { AppContainer.apiService.listVouchers(redeemed = null, limit = 200) }
        vouchers = result.getOrNull()?.items
        loadError = result.isFailure
    }

    Row(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when {
                vouchers == null && !loadError -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CaptainPalette.accent)
                }
                loadError -> Text(
                    "Couldn't load vouchers — check your connection and try again.",
                    fontFamily = InterFamily,
                    fontSize = 16.sp,
                    color = CaptainPalette.danger,
                )
                else -> {
                    val buckets = remember(vouchers) { bucketVouchers(vouchers.orEmpty()) }
                    val shown = when (tab) {
                        VoucherTab.AVAILABLE -> buckets.available
                        VoucherTab.USED -> buckets.used
                        VoucherTab.EXPIRED -> buckets.expired
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VoucherTabPill("Available", buckets.available.size, tab == VoucherTab.AVAILABLE) { tab = VoucherTab.AVAILABLE }
                        VoucherTabPill("Used", buckets.used.size, tab == VoucherTab.USED) { tab = VoucherTab.USED }
                        VoucherTabPill("Expired", buckets.expired.size, tab == VoucherTab.EXPIRED) { tab = VoucherTab.EXPIRED }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (shown.isEmpty()) {
                        Text(
                            when (tab) {
                                VoucherTab.AVAILABLE -> "No available vouchers right now."
                                VoucherTab.USED -> "No vouchers have been redeemed yet."
                                VoucherTab.EXPIRED -> "No expired vouchers."
                            },
                            fontFamily = InterFamily,
                            fontSize = 16.sp,
                            color = CaptainPalette.textSecondary,
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(shown, key = { it.id }) { voucher -> VoucherCard(voucher, tab) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        CheckVoucherPanel(
            lookup = { code -> vouchers.orEmpty().firstOrNull { it.code.equals(code, ignoreCase = true) } },
            listLoaded = vouchers != null,
            modifier = Modifier.width(340.dp).fillMaxHeight(),
        )
    }
}

private enum class VoucherTab { AVAILABLE, USED, EXPIRED }

private data class VoucherBuckets(
    val available: List<VoucherDto>,
    val used: List<VoucherDto>,
    val expired: List<VoucherDto>,
)

private fun parseVoucherInstant(iso: String): Instant? =
    runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .getOrNull()

private fun bucketVouchers(all: List<VoucherDto>, now: Instant = Instant.now()): VoucherBuckets {
    val used = all.filter { it.redeemedAt != null }
    val notRedeemed = all.filter { it.redeemedAt == null }
    val expired = notRedeemed.filter { v -> v.expiresAt?.let { parseVoucherInstant(it) }?.isBefore(now) == true }
    val available = notRedeemed - expired.toSet()
    return VoucherBuckets(available = available, used = used, expired = expired)
}

@Composable
private fun VoucherTabPill(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) CaptainPalette.primary else CaptainPalette.raised
    val textColor = if (selected) CaptainPalette.textPrimary else CaptainPalette.textSecondary
    Box(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, if (selected) CaptainPalette.primary else CaptainPalette.panelBorder, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${label.uppercase()} ($count)",
            color = textColor,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun VoucherCard(voucher: VoucherDto, tab: VoucherTab) {
    CaptainPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(voucher.code, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = CaptainPalette.textPrimary)
                Text(formatAud(voucher.valueAud), fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = CaptainPalette.accent)
            }
            when (tab) {
                VoucherTab.AVAILABLE -> Text(
                    voucher.expiresAt?.let { "Expires ${formatVoucherDate(it)}" } ?: "No expiry",
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = CaptainPalette.textSecondary,
                )
                VoucherTab.USED -> Text(
                    voucher.redeemedAt?.let { "Redeemed ${formatVoucherDate(it)}" } ?: "Redeemed",
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = CaptainPalette.textSecondary,
                )
                VoucherTab.EXPIRED -> Text(
                    voucher.expiresAt?.let { "Expired ${formatVoucherDate(it)}" } ?: "Expired",
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = CaptainPalette.danger,
                )
            }
            // Real field, shown only when present — the backend never fabricates a trip link for a
            // voucher that wasn't actually redeemed against one.
            voucher.redeemedByTripId?.let { tripId ->
                Text(
                    "Trip #${tripId.take(8)}",
                    fontFamily = InterFamily,
                    fontSize = 13.sp,
                    color = CaptainPalette.textMuted,
                )
            }
        }
    }
}

/**
 * "CHECK A VOUCHER" — deliberately not labelled "Apply Voucher" the way the reference mockup draws
 * it. There is no standalone "apply" action this pane could honestly perform: a voucher is only
 * ever redeemed server-side against a specific open trip, at Close & Pay time
 * ([au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayViewModel] posts the code as part
 * of closing that trip — see `backend/app/services/payments.redeem_voucher`), and this pane has no
 * trip context to redeem against. Building a button here that claims "Voucher applied" without
 * calling any real redeem endpoint would be exactly the fabricated success state this codebase's
 * zero-fake-affordance rule forbids. So this panel does the one thing it *can* honestly do: look a
 * code up against the tenant's real voucher ledger and show its real status, explicitly pointing
 * the driver at Close & Pay for the actual redemption step.
 */
@Composable
private fun CheckVoucherPanel(lookup: (String) -> VoucherDto?, listLoaded: Boolean, modifier: Modifier = Modifier) {
    var code by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf<VoucherDto?>(null) }
    var notFound by remember { mutableStateOf(false) }

    CaptainPanel(modifier = modifier) {
        Column(modifier = Modifier.padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("CHECK A VOUCHER", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = CaptainPalette.accent)
            Text(
                "Redemption happens at Close & Pay, against the trip being paid for — this only " +
                    "checks whether a code is real and still valid.",
                fontFamily = InterFamily,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = CaptainPalette.textMuted,
            )
            VoucherCodeField(
                value = code,
                onValueChange = { code = it; checked = null; notFound = false },
                placeholder = "Enter voucher code…",
            )
            CaptainButton(
                text = "Look Up",
                enabled = code.isNotBlank() && listLoaded,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val match = lookup(code.trim())
                checked = match
                notFound = match == null
            }
            when {
                checked != null -> CheckedVoucherResult(checked!!)
                notFound -> Text(
                    "No voucher found with that code.",
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = CaptainPalette.danger,
                )
            }
        }
    }
}

@Composable
private fun CheckedVoucherResult(voucher: VoucherDto) {
    val now = Instant.now()
    val expired = voucher.redeemedAt == null && voucher.expiresAt?.let { parseVoucherInstant(it) }?.isBefore(now) == true
    val (statusText, statusColor) = when {
        voucher.redeemedAt != null -> "Already redeemed" to CaptainPalette.textSecondary
        expired -> "Expired" to CaptainPalette.danger
        else -> "Valid — ready to redeem at Close & Pay" to CaptainPalette.success
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CaptainPalette.raised)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(voucher.code, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = CaptainPalette.textPrimary)
            Text(formatAud(voucher.valueAud), fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = CaptainPalette.accent)
        }
        Text(statusText, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = statusColor)
        voucher.expiresAt?.let {
            Text("Expires ${formatVoucherDate(it)}", fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.textMuted)
        }
    }
}

@Composable
private fun VoucherCodeField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp)),
        placeholder = { Text(placeholder, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textMuted) },
        textStyle = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textPrimary),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = CaptainPalette.inset,
            unfocusedContainerColor = CaptainPalette.inset,
            disabledContainerColor = CaptainPalette.inset,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedTextColor = CaptainPalette.textPrimary,
            unfocusedTextColor = CaptainPalette.textPrimary,
        ),
    )
}

/** `$25.00`, not the wire `"25.0000"` — decimal-as-string per this DTO's own convention. Falls
 * back to the raw string (still prefixed) if it somehow doesn't parse as a number. */
private fun formatAud(raw: String): String = "$" + (raw.toDoubleOrNull()?.let { "%.2f".format(it) } ?: raw)

/** "3 Sep 2026" — same `d MMM`-family convention [au.com.threesixty.cabdispatch.ui.screens.messages.MessageTimeFormat]
 * uses, with the year added since a voucher's expiry/redemption can sit further out than a message
 * thread ever does. Falls back to the raw ISO string if it doesn't parse. */
private fun formatVoucherDate(iso: String): String {
    val instant = parseVoucherInstant(iso) ?: return iso
    return DateTimeFormatter.ofPattern("d MMM yyyy").format(instant.atZone(ZoneId.systemDefault()))
}
