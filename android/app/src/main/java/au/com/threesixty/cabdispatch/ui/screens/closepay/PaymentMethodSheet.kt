package au.com.threesixty.cabdispatch.ui.screens.closepay

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibleForward
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainDialogScrim
import au.com.threesixty.cabdispatch.ui.theme.CaptainKeypad
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import au.com.threesixty.cabdispatch.ui.theme.RollingMoneyText
import java.math.BigDecimal

// W7 file split (2026-09-13): extracted from the former monolithic CloseAndPayScreen.kt (1,629
// lines) — see that file's own doc for the split rationale. This file is every payment-method
// selection/entry screen [ReadyToCloseFlow][CloseAndPayScreen.kt] switches between
// (`MethodPickerScreen` -> Cash/CabCharge/Voucher/Account/Split Fare). `MethodPickerScreen`,
// `CashCalculatorScreen`, `DocketEntryScreen`, `VoucherEntryScreen`, `AccountEntryScreen` and
// `SplitFareEntryScreen` are `internal` (not `private`) because `ReadyToCloseFlow` in
// CloseAndPayScreen.kt calls them across this file boundary — same package
// (`ui.screens.closepay`), so no import needed either way, only the visibility modifier changes
// from the pre-split file. `TotalCol`/`CleaningFeeDialog`/`TipPresetDialog`/`CustomTipDialog` this
// file calls are defined in FareSummaryCard.kt, `PaymentSubScreen` in CloseAndPayScreen.kt.

/**
 * Unified close-button label (Part 4, "unified close-button treatment") — every payment flow's
 * final confirm button previously read a differently-verbed label ("Confirm & Close Trip",
 * "Record & Close Trip", "Redeem & Close Trip", "Charge Account & Close", "Take Payments &
 * Close") despite all using the exact same [CaptainButton] size/weight/gradient already. Rather
 * than lose the flow-specific meaning those verbs carried, this keeps ONE constant verb phrase
 * ("CLOSE TRIP") and appends the method + amount inline instead — every flow still says exactly
 * what will happen and for how much, just with identical wording structure. `state.paymentInFlight`
 * still swaps this out for "Processing…" at every call site, unchanged.
 */
private fun closeButtonLabel(method: PaymentMethodOption, amount: BigDecimal): String =
    "CLOSE TRIP — ${method.label.uppercase()} ${amount.money()}"

/** Small shared suffix for the non-cash/non-card payment-rail sub-screens (CabCharge/Voucher/
 * Account) — those totals settle the fare only (see [CloseAndPayUiState.ReadyToClose.totalDue]'s
 * doc for why), so a tip is called out as collected separately rather than silently vanishing
 * from the driver's view once they leave the method picker. Empty string when there's no tip. */
private fun tipSeparateSuffix(tip: BigDecimal): String =
    if (tip.signum() > 0) " Plus a ${tip.money()} tip, collected separately." else ""

@Suppress("DEPRECATION")
@Composable
internal fun MethodPickerScreen(
    state: CloseAndPayUiState.ReadyToClose,
    vm: CloseAndPayViewModel,
    onSelect: (PaymentMethodOption, PaymentSubScreen) -> Unit,
    onBackToMeter: () -> Unit,
    onDispute: () -> Unit,
) {
    var showCleaningDialog by remember { mutableStateOf(false) }
    var showTipDialog by remember { mutableStateOf(false) }
    var showCustomTipDialog by remember { mutableStateOf(false) }
    // Allow Cash (Settings -> Payment Methods, 2026-09-03 Settings two-pane pass) — real
    // compliance/business toggle: when off, CASH is disabled (greyed out, non-tappable) here
    // rather than removed outright, so a driver mid-shift can see *why* the card looks different
    // instead of it silently vanishing. See au.com.threesixty.cabdispatch.domain
    // .SettingsPreferencesStore's own doc. Defaults true, matching this screen's behaviour before
    // this toggle existed.
    val allowCash by AppContainer.settingsPreferencesStore.allowCash.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp)) {
        Row(modifier = Modifier.fillMaxSize()) {
            // "Back to meter" lives IN the left column's flow, under the scroller, not as a
            // BottomStart overlay on the whole Box (tablet, 2026-09-08). An overlay covers
            // whatever happens to be at the bottom of the viewport at any scroll position -- on
            // the tablet that was the "Add a tip" card, with the button printed across it. A
            // trailing spacer inside the scroller only ever helped once scrolled to the very end.
            // Reserving the button's own row means nothing can ever sit under it.
            Column(modifier = Modifier.width(400.dp).fillMaxHeight()) {
                TotalCol(
                    state = state,
                    onReportSoiling = { showCleaningDialog = true },
                    onAddTip = { showTipDialog = true },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.height(16.dp))
                CaptainButton(text = "← Back to meter", outline = true, widthDp = 240, onClick = onBackToMeter)
            }
            Spacer(Modifier.width(64.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PayCard(
                        Icons.Rounded.Payments,
                        "CASH",
                        CaptainPalette.success,
                        subtitle = if (!allowCash) "Disabled in Settings" else null,
                        enabled = allowCash,
                        selected = state.paymentMethod == PaymentMethodOption.CASH,
                    ) { onSelect(PaymentMethodOption.CASH, PaymentSubScreen.CASH_CALCULATOR) }
                    // A5 · Hardware honesty. Not rendered at all unless a REAL card-payment
                    // gateway is present (AppContainer.cardPaymentGateway.isReal). No Stripe
                    // Terminal SDK is a dependency of this app, so today it never is. This card
                    // previously drove a mock that waited 1.5s and returned a synthetic approval,
                    // and the driver got "Payment received" and a printable receipt for money that
                    // had not moved. Removing the button is the honest failure mode; a disabled
                    // one would still imply the capability exists and is merely switched off.
                    if (state.cardPaymentIsReal) {
                        PayCard(
                            Icons.Rounded.CreditCard,
                            "CARD · TAP",
                            CaptainPalette.accent,
                            selected = state.paymentMethod == PaymentMethodOption.TAP_TO_PAY,
                        ) { onSelect(PaymentMethodOption.TAP_TO_PAY, PaymentSubScreen.CASH_CALCULATOR) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PayCard(
                        Icons.Rounded.LocalTaxi,
                        "CABCHARGE",
                        CaptainPalette.warning,
                        selected = state.paymentMethod == PaymentMethodOption.CABCHARGE,
                    ) { onSelect(PaymentMethodOption.CABCHARGE, PaymentSubScreen.CABCHARGE_ENTRY) }
                    PayCard(
                        Icons.Rounded.AccessibleForward,
                        "TTSS",
                        CaptainPalette.accent,
                        selected = state.paymentMethod == PaymentMethodOption.CABCHARGE,
                    ) { onSelect(PaymentMethodOption.CABCHARGE, PaymentSubScreen.CABCHARGE_ENTRY) }
                }
                Text(
                    "TTSS/CabCharge trips remain fare-regulated and metered even when arranged as a booking.",
                    fontFamily = InterFamily,
                    fontSize = 12.sp,
                    color = CaptainPalette.textMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PayCard(
                        Icons.Rounded.ConfirmationNumber,
                        "VOUCHER",
                        CaptainPalette.warning,
                        // Never fabricate a count while loading/on failure — null renders no
                        // subtitle at all, matching this app's zero-fake-affordance rule.
                        subtitle = state.voucherAvailableCount?.let { "$it Available" },
                        selected = state.paymentMethod == PaymentMethodOption.VOUCHER,
                    ) { onSelect(PaymentMethodOption.VOUCHER, PaymentSubScreen.VOUCHER_ENTRY) }
                    PayCard(
                        Icons.Rounded.Business,
                        "ACCOUNT",
                        CaptainPalette.textSecondary,
                        subtitle = state.corporateAccountActiveCount?.let { "$it Active" },
                        selected = state.paymentMethod == PaymentMethodOption.ACCOUNT,
                    ) { onSelect(PaymentMethodOption.ACCOUNT, PaymentSubScreen.ACCOUNT_ENTRY) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PayCard(
                        Icons.Rounded.CallSplit,
                        "SPLIT FARE",
                        CaptainPalette.textSecondary,
                        selected = state.paymentMethod == PaymentMethodOption.SPLIT_FARE,
                    ) { onSelect(PaymentMethodOption.SPLIT_FARE, PaymentSubScreen.SPLIT_FARE_ENTRY) }
                    PayCard(Icons.Rounded.Flag, "DISPUTE / FLAG", CaptainPalette.danger, onClick = onDispute)
                }
            }
        }
    }

    CaptainDialogScrim(visible = showCleaningDialog, onDismissRequest = { showCleaningDialog = false }) {
        CleaningFeeDialog(
            cap = state.tariff.cleaningFeeCap,
            initial = state.cleaningFee,
            onDismiss = { showCleaningDialog = false },
            onConfirm = { amount ->
                showCleaningDialog = false
                vm.setCleaningFee(amount)
            },
        )
    }

    CaptainDialogScrim(visible = showTipDialog, onDismissRequest = { showTipDialog = false }) {
        TipPresetDialog(
            currentTip = state.tip,
            onDismiss = { showTipDialog = false },
            onSelectPreset = { amount ->
                showTipDialog = false
                vm.setTip(amount)
            },
            onCustom = {
                showTipDialog = false
                showCustomTipDialog = true
            },
        )
    }
    CaptainDialogScrim(visible = showCustomTipDialog, onDismissRequest = { showCustomTipDialog = false }) {
        CustomTipDialog(
            onDismiss = { showCustomTipDialog = false },
            onConfirm = { amount ->
                showCustomTipDialog = false
                vm.setTip(amount)
            },
        )
    }
}

/**
 * HUD kit payment-grid tile: a [GlassCard] (previously a plain raised panel) with the same icon/label/subtitle
 * content, plus a real [selected] state — an accent-coloured `neonGlow` halo when this tile is the
 * trip's current [CloseAndPayUiState.ReadyToClose.paymentMethod] (see
 * [MethodPickerScreen]'s call sites for exactly which [PaymentMethodOption] each tile maps to).
 * Never a decorative glow: it always reflects the real selected method the "CLOSE TRIP" button
 * will actually charge.
 */
@Composable
private fun PayCard(
    icon: ImageVector,
    label: String,
    accent: Color,
    subtitle: String? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.width(357.dp).height(118.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick),
        cornerRadiusDp = 18,
        glow = if (selected) accent else null,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = if (selected) 0.28f else 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(30.dp))
            }
            Column {
                Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 21.sp, color = CaptainPalette.textPrimary)
                // Real backend-derived count only — never shown while loading/failed, see
                // this parameter's call sites in MethodPickerScreen. [enabled]==false's own
                // "Disabled in Settings" subtitle (Allow Cash toggle) overrides that at the call
                // site instead of being invented here.
                subtitle?.let {
                    Text(it, fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = CaptainPalette.textMuted)
                }
            }
        }
    }
}

// --- Cash / Card sub-screen (numeric tender + change) ----------------------------------------

/** Digits typed on [CaptainKeypad] are interpreted as cents (same convention as the Hired
 * screen's custom-toll pad) — the shared keypad has no decimal-point key, so "1284" reads as
 * $12.84. Kept as a local cents string; only the resulting decimal is ever handed to
 * [CloseAndPayViewModel.setCashTendered], which still just stores/parses a plain decimal string. */
@Composable
internal fun CashCalculatorScreen(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, onBack: () -> Unit) {
    var cents by remember { mutableStateOf("") }
    val tendered = if (cents.isEmpty()) BigDecimal.ZERO else BigDecimal(cents).movePointLeft(2)

    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp)) {
        Column(modifier = Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Amount tendered", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = CaptainPalette.textPrimary)
            Text(
                if (state.tip.signum() > 0) {
                    "Total due ${state.totalDue.money()} (incl. ${state.tip.money()} tip)"
                } else {
                    "Total due ${state.totalDue.money()}"
                },
                fontFamily = InterFamily,
                fontSize = 17.sp,
                color = CaptainPalette.textSecondary,
            )
            GlassCard(modifier = Modifier.fillMaxWidth().height(80.dp), cornerRadiusDp = 14, glow = CaptainPalette.hudAccent) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    RollingMoneyText(
                        amount = tendered.money(),
                        fontSize = 34.sp,
                        color = CaptainPalette.success,
                        modifier = Modifier.padding(start = 24.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Exact", "$20", "$50", "$100").forEach { preset ->
                    CaptainButton(
                        text = preset,
                        outline = true,
                        heightDp = 56,
                        fontSize = 16.sp,
                        widthDp = 110,
                    ) {
                        cents = when (preset) {
                            "Exact" -> state.totalDue.movePointRight(2).toBigInteger().toString()
                            else -> preset.drop(1) + "00"
                        }
                        vm.setCashTendered(BigDecimal(cents).movePointLeft(2).toPlainString())
                    }
                }
            }
            state.changeDue?.let {
                Text("Change due ${it.money()}", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = CaptainPalette.success)
            }
            state.paymentError?.let { Text(it, fontFamily = InterFamily, fontSize = 14.sp, color = CaptainPalette.danger) }
        }
        Spacer(Modifier.weight(1f))
        Column {
            CaptainKeypad(
                onDigit = { d -> if (cents.length < 7) { cents += d; vm.setCashTendered(BigDecimal(cents).movePointLeft(2).toPlainString()) } },
                onBackspace = { cents = cents.dropLast(1); vm.setCashTendered(if (cents.isEmpty()) "" else BigDecimal(cents).movePointLeft(2).toPlainString()) },
                onClear = { cents = ""; vm.setCashTendered("") },
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CaptainButton(text = "Back", outline = true, widthDp = 180, onClick = onBack)
                CaptainButton(
                    text = if (state.paymentInFlight) "Processing…" else closeButtonLabel(state.paymentMethod, state.totalDue),
                    heightDp = 72,
                    fontSize = 17.sp,
                    enabled = state.canConfirm && !state.paymentInFlight,
                    widthDp = 320,
                    onClick = vm::confirmPayment,
                )
            }
        }
    }
}

// --- CabCharge / TTSS docket entry -------------------------------------------------------------

@Composable
internal fun DocketEntryScreen(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, onBack: () -> Unit) {
    LabeledEntryScreen(
        title = "CabCharge / TTSS docket",
        totalLine = "Total due ${state.breakdown.grandTotal.money()} — docket number required for reconciliation." +
            tipSeparateSuffix(state.tip),
        value = state.docketNumber,
        onValueChar = { c -> vm.setDocketNumber(state.docketNumber + c) },
        onBackspace = { vm.setDocketNumber(state.docketNumber.dropLast(1)) },
        onClear = { vm.setDocketNumber("") },
        canConfirm = state.canConfirm && !state.paymentInFlight,
        inFlight = state.paymentInFlight,
        error = state.paymentError,
        confirmLabel = closeButtonLabel(state.paymentMethod, state.breakdown.grandTotal),
        onConfirm = vm::confirmPayment,
        onBack = onBack,
    )
}

// --- 20 · Voucher --------------------------------------------------------------------------

@Composable
internal fun VoucherEntryScreen(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 88.dp, vertical = 32.dp)) {
        Column(modifier = Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Voucher payment", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = CaptainPalette.textPrimary)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("VOUCHER CODE", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CaptainPalette.textMuted)
                GlassCard(modifier = Modifier.fillMaxWidth().height(80.dp), cornerRadiusDp = 14, glow = CaptainPalette.hudAccent) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        Text(
                            state.voucherCode,
                            fontFamily = RobotoMonoFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 32.sp,
                            color = CaptainPalette.textPrimary,
                            modifier = Modifier.padding(start = 24.dp),
                        )
                    }
                }
            }
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 14) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Total due ${state.breakdown.grandTotal.money()} — voucher redeemed against the full amount." +
                            tipSeparateSuffix(state.tip),
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = CaptainPalette.textPrimary,
                    )
                    Text(
                        "The fleet backend validates the code at close; invalid codes fall back to cash/card.",
                        fontFamily = InterFamily,
                        fontSize = 14.sp,
                        color = CaptainPalette.textMuted,
                    )
                }
            }
            state.paymentError?.let { Text(it, fontFamily = InterFamily, fontSize = 14.sp, color = CaptainPalette.danger) }
        }
        Spacer(Modifier.weight(1f))
        Column {
            RegoStyleKeyGrid(
                onKey = { c -> vm.setVoucherCode(state.voucherCode + c) },
                onBackspace = { vm.setVoucherCode(state.voucherCode.dropLast(1)) },
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CaptainButton(text = "← Back", outline = true, widthDp = 180, onClick = onBack)
                CaptainButton(
                    text = if (state.paymentInFlight) "Processing…" else closeButtonLabel(state.paymentMethod, state.breakdown.grandTotal),
                    heightDp = 72,
                    fontSize = 17.sp,
                    enabled = state.canConfirm && !state.paymentInFlight,
                    widthDp = 320,
                    onClick = vm::confirmPayment,
                )
            }
        }
    }
}

// --- Account entry (same shape as Voucher) ---------------------------------------------------

@Composable
internal fun AccountEntryScreen(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, onBack: () -> Unit) {
    LabeledEntryScreen(
        title = "Account payment",
        totalLine = "Total due ${state.breakdown.grandTotal.money()} — invoiced to the linked account." +
            tipSeparateSuffix(state.tip),
        value = state.accountReference,
        onValueChar = { c -> vm.setAccountReference(state.accountReference + c) },
        onBackspace = { vm.setAccountReference(state.accountReference.dropLast(1)) },
        onClear = { vm.setAccountReference("") },
        canConfirm = state.canConfirm && !state.paymentInFlight,
        inFlight = state.paymentInFlight,
        error = state.paymentError,
        confirmLabel = closeButtonLabel(state.paymentMethod, state.breakdown.grandTotal),
        onConfirm = vm::confirmPayment,
        onBack = onBack,
        useAlphaGrid = true,
    )
}

/** Shared shape for Docket-number/Account-reference entry — a labeled field + a right-hand key
 * grid (numeric for docket, alpha+numeric for an account code). */
@Composable
private fun LabeledEntryScreen(
    title: String,
    totalLine: String,
    value: String,
    onValueChar: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    canConfirm: Boolean,
    inFlight: Boolean,
    error: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    useAlphaGrid: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 88.dp, vertical = 32.dp)) {
        Column(modifier = Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(title, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = CaptainPalette.textPrimary)
            GlassCard(modifier = Modifier.fillMaxWidth().height(80.dp), cornerRadiusDp = 14, glow = CaptainPalette.hudAccent) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    Text(
                        value,
                        fontFamily = RobotoMonoFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 30.sp,
                        color = CaptainPalette.textPrimary,
                        modifier = Modifier.padding(start = 24.dp),
                    )
                }
            }
            Text(totalLine, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textSecondary)
            error?.let { Text(it, fontFamily = InterFamily, fontSize = 14.sp, color = CaptainPalette.danger) }
        }
        Spacer(Modifier.weight(1f))
        Column {
            if (useAlphaGrid) {
                RegoStyleKeyGrid(onKey = onValueChar, onBackspace = onBackspace)
            } else {
                CaptainKeypad(onDigit = { d -> onValueChar(d.toString()) }, onBackspace = onBackspace, onClear = onClear)
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CaptainButton(text = "← Back", outline = true, widthDp = 180, onClick = onBack)
                CaptainButton(
                    text = if (inFlight) "Processing…" else confirmLabel,
                    heightDp = 72,
                    fontSize = 17.sp,
                    enabled = canConfirm,
                    widthDp = 320,
                    onClick = onConfirm,
                )
            }
        }
    }
}

/** Compact A–Z+digit grid, reused from the Vehicle Bind rego pad's visual language, restyled onto
 * [CaptainPalette] tokens. */
@Suppress("DEPRECATION")
@Composable
private fun RegoStyleKeyGrid(onKey: (String) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf("ABCDEFGHI", "JKLMNOPQR", "STUVWXYZ⌫", "0123456789")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    Box(
                        modifier = Modifier
                            .width(52.dp)
                            .height(50.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(CaptainPalette.raised)
                            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(10.dp))
                            .clickable { if (c == '⌫') onBackspace() else onKey(c.toString()) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (c == '⌫') {
                            Icon(Icons.Rounded.Backspace, contentDescription = "Backspace", tint = CaptainPalette.danger, modifier = Modifier.size(20.dp))
                        } else {
                            Text(
                                c.toString(),
                                fontFamily = ChakraPetch,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp,
                                color = CaptainPalette.textPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- 21 · Split Fare -------------------------------------------------------------------------

@Composable
internal fun SplitFareEntryScreen(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<Char?>('A') } // which leg the keypad edits
    var legACents by remember { mutableStateOf("") }
    var legBCents by remember { mutableStateOf("") }

    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 88.dp, vertical = 24.dp)) {
        Column(modifier = Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Split fare", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = CaptainPalette.textPrimary)
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 14, glow = CaptainPalette.hudAccent) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("TOTAL DUE", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CaptainPalette.textMuted)
                    RollingMoneyText(amount = state.breakdown.grandTotal.money(), fontSize = 44.sp, color = CaptainPalette.success)
                }
            }
            // Split legs must sum exactly to the fare-only grand total (the backend's
            // `SplitPaymentMismatchError` check, app.services.trips.close_trip/sync_trips) — a tip
            // isn't split across legs in this v1, so it's called out separately rather than
            // silently added to (or missing from) the total above.
            if (state.tip.signum() > 0) {
                Text(
                    "Plus a ${state.tip.money()} tip — collected separately (e.g. in cash), not split across legs.",
                    fontFamily = InterFamily,
                    fontSize = 13.sp,
                    color = CaptainPalette.textMuted,
                )
            }
            SplitLegRow(
                icon = Icons.Rounded.Payments,
                label = state.splitLegAMethod.label.uppercase(),
                amountText = state.splitLegAAmount,
                selected = editing == 'A',
                onClick = { editing = 'A' },
            )
            SplitLegRow(
                icon = Icons.Rounded.CreditCard,
                label = state.splitLegBMethod.label.uppercase(),
                amountText = state.splitLegBAmount,
                selected = editing == 'B',
                highlight = true,
                onClick = { editing = 'B' },
            )
            val remaining = state.splitRemaining
            val allocated = remaining != null && remaining.setScale(2, java.math.RoundingMode.HALF_UP).signum() == 0
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background((if (allocated) CaptainPalette.success else CaptainPalette.warning).copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    if (allocated) "✓ Legs sum to the total — exact to the cent, verified before close"
                    else "Remaining to allocate: ${(remaining ?: state.breakdown.grandTotal).money()}",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = if (allocated) CaptainPalette.success else CaptainPalette.warning,
                )
            }
            Text(
                "+ ADD LEG (Cabcharge · TTSS · Voucher · Account)",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = CaptainPalette.accent,
            )
            state.paymentError?.let { Text(it, fontFamily = InterFamily, fontSize = 14.sp, color = CaptainPalette.danger) }
        }
        Spacer(Modifier.weight(1f))
        Column {
            // Digits read as cents (no decimal key on the shared pad) — same convention as Cash.
            CaptainKeypad(
                onDigit = { d ->
                    if (editing == 'A') {
                        legACents = (legACents + d).take(7)
                        vm.setSplitLegAAmount(BigDecimal(legACents).movePointLeft(2).toPlainString())
                    } else {
                        legBCents = (legBCents + d).take(7)
                        vm.setSplitLegBAmount(BigDecimal(legBCents).movePointLeft(2).toPlainString())
                    }
                },
                onBackspace = {
                    if (editing == 'A') {
                        legACents = legACents.dropLast(1)
                        vm.setSplitLegAAmount(if (legACents.isEmpty()) "" else BigDecimal(legACents).movePointLeft(2).toPlainString())
                    } else {
                        legBCents = legBCents.dropLast(1)
                        vm.setSplitLegBAmount(if (legBCents.isEmpty()) "" else BigDecimal(legBCents).movePointLeft(2).toPlainString())
                    }
                },
                onClear = {
                    if (editing == 'A') { legACents = ""; vm.setSplitLegAAmount("") } else { legBCents = ""; vm.setSplitLegBAmount("") }
                },
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CaptainButton(text = "← Back", outline = true, widthDp = 180, onClick = onBack)
                CaptainButton(
                    text = if (state.paymentInFlight) "Processing…" else closeButtonLabel(state.paymentMethod, state.breakdown.grandTotal),
                    heightDp = 72,
                    fontSize = 17.sp,
                    enabled = state.canConfirm && !state.paymentInFlight,
                    widthDp = 320,
                    onClick = vm::confirmPayment,
                )
            }
        }
    }
}

@Composable
private fun SplitLegRow(icon: ImageVector, label: String, amountText: String, selected: Boolean, highlight: Boolean = false, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth().height(86.dp).clickable(onClick = onClick),
        cornerRadiusDp = 16,
        glow = if (selected) CaptainPalette.hudAccent else null,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, tint = CaptainPalette.textSecondary, modifier = Modifier.size(26.dp))
            Text(label, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = CaptainPalette.textPrimary)
            Spacer(Modifier.weight(1f))
            Text(
                "$" + amountText.ifEmpty { "0.00" },
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Medium,
                fontSize = 30.sp,
                color = if (highlight) CaptainPalette.hudAccent else CaptainPalette.textPrimary,
            )
        }
    }
}

// ============================================================================================
// Previews (2026-09-04 day-mode pass) — Close & Pay, in both themes
//
// [CloseAndPayScreen][CloseAndPayScreen.kt] itself needs a live [CloseAndPayViewModel]
// (AndroidViewModel, real repositories via AppContainer) and can't be previewed directly. This
// composes the same building blocks the real screen renders — [PayCard] — as a representative,
// honest stand-in: not the live stateful screen, but real production composables at real sizes,
// not a redrawn mockup.
// ============================================================================================

@Preview(name = "Close & Pay — dark", widthDp = 900, heightDp = 420, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewCloseAndPaySample() {
    CaptainPalette.applyTheme(isLight = false)
    CloseAndPayPreviewSample()
}

@Preview(name = "Close & Pay — light", widthDp = 900, heightDp = 420, backgroundColor = 0xFFF4F3F8, showBackground = true)
@Composable
private fun PreviewCloseAndPaySampleLight() {
    CaptainPalette.applyTheme(isLight = true)
    CloseAndPayPreviewSample()
}

@Composable
private fun CloseAndPayPreviewSample() {
    Row(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        GlassCard(modifier = Modifier.width(320.dp).fillMaxSize(), cornerRadiusDp = 20, glow = CaptainPalette.hudAccent) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                Text("TOTAL DUE", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CaptainPalette.textMuted)
                RollingMoneyText(amount = "\$42.80", fontSize = 64.sp, color = CaptainPalette.success)
                Text("Includes \$5.00 tip", fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = CaptainPalette.textMuted)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PayCard(icon = Icons.Rounded.Payments, label = "Cash", accent = CaptainPalette.success, subtitle = "Exact change or tender + calculate", selected = true, onClick = {})
            PayCard(icon = Icons.Rounded.CreditCard, label = "Card", accent = CaptainPalette.accent, subtitle = "Tap, insert or swipe", onClick = {})
            PayCard(icon = Icons.Rounded.ConfirmationNumber, label = "Voucher", accent = CaptainPalette.warning, subtitle = "CabCharge / TTSS / docket", onClick = {})
        }
    }
}
