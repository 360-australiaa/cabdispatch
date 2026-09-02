package au.com.threesixty.cabdispatch.ui.screens.closepay

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibleForward
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.domain.TripDetailHandoff
import au.com.threesixty.cabdispatch.domain.fare.FareBreakdown
import au.com.threesixty.cabdispatch.ui.deck.rememberDeckClock
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainKeypad
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.CaptainPanel
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import java.math.BigDecimal
import kotlinx.coroutines.launch

/**
 * S4 — Close & Pay, re-skinned onto the [CaptainPalette] purple design system (2026-08-29 pass).
 * All state/logic lives unchanged in [CloseAndPayViewModel] — this file is purely the visual layer
 * over the same [CloseAndPayUiState]/[PaymentSubScreen] machine; every `onClick` still calls the
 * exact same ViewModel method with the same arguments it always did. Cash/CabCharge entry keep
 * their numeric-pad-driven sub-screens (now on [CaptainKeypad]); Voucher/Account/Split/Receipt are
 * restyled in place. Previously ported off the old yellow/black `Deck` tokens this pass replaces.
 */
@Composable
fun CloseAndPayScreen(
    navController: NavHostController,
    onDone: () -> Unit,
    viewModel: CloseAndPayViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state) {
        if (state is CloseAndPayUiState.Done) onDone()
    }

    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.bg)) {
        when (val s = state) {
            CloseAndPayUiState.Loading -> CenterMessage("Loading trip…")
            CloseAndPayUiState.NoActiveTrip -> CenterMessage("No active trip to close.")
            is CloseAndPayUiState.LoadError -> CenterMessage(s.message, isError = true)
            is CloseAndPayUiState.ReadyToClose -> ReadyToCloseFlow(s, viewModel, navController)
            is CloseAndPayUiState.ReceiptStep -> ReceiptScreen(s, viewModel)
            CloseAndPayUiState.Done -> Unit
        }
    }
}

@Composable
private fun CenterMessage(text: String, isError: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontFamily = InterFamily, fontSize = 18.sp, color = if (isError) CaptainPalette.danger else CaptainPalette.textSecondary)
    }
}

private enum class PaymentSubScreen { METHOD_PICKER, CASH_CALCULATOR, CABCHARGE_ENTRY, VOUCHER_ENTRY, ACCOUNT_ENTRY, SPLIT_FARE_ENTRY }

// --- 19 · Close & Pay (method picker) + shared totalCol -------------------------------------

@Composable
private fun ReadyToCloseFlow(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, navController: NavHostController) {
    var subScreen by rememberSaveable { mutableStateOf(PaymentSubScreen.METHOD_PICKER) }

    Column(modifier = Modifier.fillMaxSize()) {
        ClosingStatusStrip()
        Box(modifier = Modifier.weight(1f)) {
            // Premium pass (2026-08-29): sub-screens previously hard-cut. Entering a method's
            // entry flow slides in from the right; returning to the picker slides back from the
            // left — spatial continuity for the "drill in / back out" mental model. Purely
            // presentational: the PaymentSubScreen state machine and every callback are unchanged.
            AnimatedContent(
                targetState = subScreen,
                transitionSpec = {
                    val forward = initialState == PaymentSubScreen.METHOD_PICKER
                    val dir = if (forward) 1 else -1
                    (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { dir * it / 16 })
                        .togetherWith(fadeOut(tween(180)))
                },
                label = "pay-subscreen",
            ) { sub ->
                when (sub) {
                PaymentSubScreen.METHOD_PICKER -> MethodPickerScreen(
                    state = state,
                    onSelect = { method, next -> vm.selectPaymentMethod(method); subScreen = next },
                    onBackToMeter = { navController.popBackStack() },
                    onDispute = {
                        TripDetailHandoff.set(state.trip.clientUuid)
                        navController.navigate(CabDispatchRoutes.TRIP_DETAIL)
                    },
                )
                PaymentSubScreen.CASH_CALCULATOR -> CashCalculatorScreen(state, vm) { subScreen = PaymentSubScreen.METHOD_PICKER }
                PaymentSubScreen.CABCHARGE_ENTRY -> DocketEntryScreen(state, vm) { subScreen = PaymentSubScreen.METHOD_PICKER }
                PaymentSubScreen.VOUCHER_ENTRY -> VoucherEntryScreen(state, vm) { subScreen = PaymentSubScreen.METHOD_PICKER }
                PaymentSubScreen.ACCOUNT_ENTRY -> AccountEntryScreen(state, vm) { subScreen = PaymentSubScreen.METHOD_PICKER }
                PaymentSubScreen.SPLIT_FARE_ENTRY -> SplitFareEntryScreen(state, vm) { subScreen = PaymentSubScreen.METHOD_PICKER }
                }
            }
        }
    }
}

/** The status strip with a "HIRED — CLOSING" pill — this route has no drive-panel/live GPS source
 * of its own, so only the fields this screen genuinely knows (clock) render. */
@Composable
private fun ClosingStatusStrip() {
    Box(
        modifier = Modifier.fillMaxWidth().height(44.dp).background(CaptainPalette.panel).padding(horizontal = 16.dp),
    ) {
        Text(
            rememberDeckClock(),
            fontFamily = RobotoMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = CaptainPalette.textSecondary,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(99.dp))
                .background(CaptainPalette.danger)
                .padding(horizontal = 18.dp, vertical = 6.dp),
        ) {
            Text("HIRED — CLOSING", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp, color = Color.White)
        }
    }
}

@Composable
private fun TotalCol(breakdown: FareBreakdown, modifier: Modifier = Modifier) {
    Column(modifier = modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Close & Pay", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = CaptainPalette.textPrimary)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(CaptainPalette.inset)
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(18.dp))
                .padding(horizontal = 26.dp, vertical = 20.dp),
        ) {
            Text("TOTAL DUE", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CaptainPalette.textMuted)
            // Count-up entrance (premium pass): rolls 0 -> total over ~650ms once on entry, then
            // renders the EXACT BigDecimal `.money()` string from that point on — the animated
            // frames are presentation only; the settled figure is always the engine's own total.
            val target = remember(breakdown.grandTotal) { breakdown.grandTotal.toFloat() }
            var countStarted by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { countStarted = true }
            val counted by animateFloatAsState(
                targetValue = if (countStarted) target else 0f,
                animationSpec = tween(650, easing = FastOutSlowInEasing),
                label = "total-countup",
            )
            Text(
                if (counted >= target) breakdown.grandTotal.money() else "$%.2f".format(counted),
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 84.sp,
                color = CaptainPalette.success,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CaptainPalette.panel)
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BreakdownRow("Flagfall", breakdown.flagFall.money())
            BreakdownRow("Fare (distance + time)", (breakdown.distanceCharge + breakdown.waitingCharge + breakdown.peakCharge).money())
            BreakdownRow("PSL levy", breakdown.psl.money())
            BreakdownRow("Tolls", breakdown.tolls.money())
            BreakdownRow("Extras", (breakdown.extras + breakdown.cleaningFee).money())
            BreakdownRow("GST included", breakdown.gstComponent.money())
        }
        Text(
            "Negotiated (Set Price) trips show the agreed amount here — levies & tolls added on top.",
            fontFamily = InterFamily,
            fontSize = 13.sp,
            color = CaptainPalette.textMuted,
        )
    }
}

@Composable
private fun BreakdownRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textSecondary)
        Text(value, fontFamily = RobotoMonoFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = CaptainPalette.textPrimary)
    }
}

@Composable
private fun MethodPickerScreen(
    state: CloseAndPayUiState.ReadyToClose,
    onSelect: (PaymentMethodOption, PaymentSubScreen) -> Unit,
    onBackToMeter: () -> Unit,
    onDispute: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp)) {
        Row(modifier = Modifier.fillMaxSize()) {
            TotalCol(state.breakdown)
            Spacer(Modifier.width(64.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PayCard(Icons.Rounded.Payments, "CASH", CaptainPalette.success) { onSelect(PaymentMethodOption.CASH, PaymentSubScreen.CASH_CALCULATOR) }
                    PayCard(Icons.Rounded.CreditCard, "CARD · TAP", CaptainPalette.accent) { onSelect(PaymentMethodOption.TAP_TO_PAY, PaymentSubScreen.CASH_CALCULATOR) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PayCard(Icons.Rounded.LocalTaxi, "CABCHARGE", CaptainPalette.warning) { onSelect(PaymentMethodOption.CABCHARGE, PaymentSubScreen.CABCHARGE_ENTRY) }
                    PayCard(Icons.Rounded.AccessibleForward, "TTSS", CaptainPalette.accent) { onSelect(PaymentMethodOption.CABCHARGE, PaymentSubScreen.CABCHARGE_ENTRY) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PayCard(Icons.Rounded.ConfirmationNumber, "VOUCHER", CaptainPalette.warning) { onSelect(PaymentMethodOption.VOUCHER, PaymentSubScreen.VOUCHER_ENTRY) }
                    PayCard(Icons.Rounded.Business, "ACCOUNT", CaptainPalette.textSecondary) { onSelect(PaymentMethodOption.ACCOUNT, PaymentSubScreen.ACCOUNT_ENTRY) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PayCard(Icons.Rounded.CallSplit, "SPLIT FARE", CaptainPalette.textSecondary) { onSelect(PaymentMethodOption.SPLIT_FARE, PaymentSubScreen.SPLIT_FARE_ENTRY) }
                    PayCard(Icons.Rounded.Flag, "DISPUTE / FLAG", CaptainPalette.danger, onClick = onDispute)
                }
            }
        }
        CaptainButton(
            text = "← Back to meter",
            outline = true,
            widthDp = 240,
            modifier = Modifier.align(Alignment.BottomStart),
            onClick = onBackToMeter,
        )
    }
}

@Composable
private fun PayCard(icon: ImageVector, label: String, accent: Color, onClick: () -> Unit) {
    CaptainPanel(
        modifier = Modifier.width(357.dp).height(118.dp).clickable(onClick = onClick),
        raised = true,
        cornerRadiusDp = 18,
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
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(30.dp))
            }
            Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 21.sp, color = CaptainPalette.textPrimary)
        }
    }
}

// --- Cash / Card sub-screen (numeric tender + change) ----------------------------------------

/** Digits typed on [CaptainKeypad] are interpreted as cents (same convention as the Hired
 * screen's custom-toll pad) — the shared keypad has no decimal-point key, so "1284" reads as
 * $12.84. Kept as a local cents string; only the resulting decimal is ever handed to
 * [CloseAndPayViewModel.setCashTendered], which still just stores/parses a plain decimal string. */
@Composable
private fun CashCalculatorScreen(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, onBack: () -> Unit) {
    var cents by remember { mutableStateOf("") }
    val tendered = if (cents.isEmpty()) BigDecimal.ZERO else BigDecimal(cents).movePointLeft(2)

    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp)) {
        Column(modifier = Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Amount tendered", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = CaptainPalette.textPrimary)
            Text("Total due ${state.breakdown.grandTotal.money()}", fontFamily = InterFamily, fontSize = 17.sp, color = CaptainPalette.textSecondary)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CaptainPalette.inset)
                    .border(2.dp, CaptainPalette.accent, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    tendered.money(),
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 34.sp,
                    color = CaptainPalette.success,
                    modifier = Modifier.padding(start = 24.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Exact", "$20", "$50", "$100").forEach { preset ->
                    CaptainButton(
                        text = preset,
                        outline = true,
                        heightDp = 56,
                        fontSize = 16.sp,
                        widthDp = 110,
                    ) {
                        cents = when (preset) {
                            "Exact" -> state.breakdown.grandTotal.movePointRight(2).toBigInteger().toString()
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
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CaptainButton(text = "Back", outline = true, widthDp = 180, onClick = onBack)
                CaptainButton(
                    text = if (state.paymentInFlight) "Processing…" else "Confirm & Close Trip",
                    heightDp = 72,
                    enabled = state.canConfirm && !state.paymentInFlight,
                    widthDp = 268,
                    onClick = vm::confirmPayment,
                )
            }
        }
    }
}

// --- CabCharge / TTSS docket entry -------------------------------------------------------------

@Composable
private fun DocketEntryScreen(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, onBack: () -> Unit) {
    LabeledEntryScreen(
        title = "CabCharge / TTSS docket",
        totalLine = "Total due ${state.breakdown.grandTotal.money()} — docket number required for reconciliation.",
        value = state.docketNumber,
        onValueChar = { c -> vm.setDocketNumber(state.docketNumber + c) },
        onBackspace = { vm.setDocketNumber(state.docketNumber.dropLast(1)) },
        onClear = { vm.setDocketNumber("") },
        canConfirm = state.canConfirm && !state.paymentInFlight,
        inFlight = state.paymentInFlight,
        error = state.paymentError,
        confirmLabel = "Record & Close Trip",
        onConfirm = vm::confirmPayment,
        onBack = onBack,
    )
}

// --- 20 · Voucher --------------------------------------------------------------------------

@Composable
private fun VoucherEntryScreen(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 88.dp, vertical = 32.dp)) {
        Column(modifier = Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Voucher payment", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = CaptainPalette.textPrimary)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("VOUCHER CODE", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CaptainPalette.textMuted)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CaptainPalette.raised)
                        .border(2.dp, CaptainPalette.accent, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.CenterStart,
                ) {
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CaptainPalette.panel)
                    .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Total due ${state.breakdown.grandTotal.money()} — voucher redeemed against the full amount.",
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
            state.paymentError?.let { Text(it, fontFamily = InterFamily, fontSize = 14.sp, color = CaptainPalette.danger) }
        }
        Spacer(Modifier.weight(1f))
        Column {
            RegoStyleKeyGrid(
                onKey = { c -> vm.setVoucherCode(state.voucherCode + c) },
                onBackspace = { vm.setVoucherCode(state.voucherCode.dropLast(1)) },
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CaptainButton(text = "← Back", outline = true, widthDp = 180, onClick = onBack)
                CaptainButton(
                    text = if (state.paymentInFlight) "Processing…" else "Redeem & Close Trip",
                    heightDp = 72,
                    enabled = state.canConfirm && !state.paymentInFlight,
                    widthDp = 268,
                    onClick = vm::confirmPayment,
                )
            }
        }
    }
}

// --- Account entry (same shape as Voucher) ---------------------------------------------------

@Composable
private fun AccountEntryScreen(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, onBack: () -> Unit) {
    LabeledEntryScreen(
        title = "Account payment",
        totalLine = "Total due ${state.breakdown.grandTotal.money()} — invoiced to the linked account.",
        value = state.accountReference,
        onValueChar = { c -> vm.setAccountReference(state.accountReference + c) },
        onBackspace = { vm.setAccountReference(state.accountReference.dropLast(1)) },
        onClear = { vm.setAccountReference("") },
        canConfirm = state.canConfirm && !state.paymentInFlight,
        inFlight = state.paymentInFlight,
        error = state.paymentError,
        confirmLabel = "Charge Account & Close",
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CaptainPalette.raised)
                    .border(2.dp, CaptainPalette.accent, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    value,
                    fontFamily = RobotoMonoFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 30.sp,
                    color = CaptainPalette.textPrimary,
                    modifier = Modifier.padding(start = 24.dp),
                )
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
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CaptainButton(text = "← Back", outline = true, widthDp = 180, onClick = onBack)
                CaptainButton(
                    text = if (inFlight) "Processing…" else confirmLabel,
                    heightDp = 72,
                    enabled = canConfirm,
                    widthDp = 268,
                    onClick = onConfirm,
                )
            }
        }
    }
}

/** Compact A–Z+digit grid, reused from the Vehicle Bind rego pad's visual language, restyled onto
 * [CaptainPalette] tokens. */
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
private fun SplitFareEntryScreen(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<Char?>('A') } // which leg the keypad edits
    var legACents by remember { mutableStateOf("") }
    var legBCents by remember { mutableStateOf("") }

    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 88.dp, vertical = 24.dp)) {
        Column(modifier = Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Split fare", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = CaptainPalette.textPrimary)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CaptainPalette.inset)
                    .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("TOTAL DUE", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CaptainPalette.textMuted)
                Text(state.breakdown.grandTotal.money(), fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, color = CaptainPalette.success)
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
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CaptainButton(text = "← Back", outline = true, widthDp = 180, onClick = onBack)
                CaptainButton(
                    text = if (state.paymentInFlight) "Processing…" else "Take Payments & Close",
                    heightDp = 72,
                    enabled = state.canConfirm && !state.paymentInFlight,
                    widthDp = 268,
                    onClick = vm::confirmPayment,
                )
            }
        }
    }
}

@Composable
private fun SplitLegRow(icon: ImageVector, label: String, amountText: String, selected: Boolean, highlight: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(86.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CaptainPalette.panel)
            .border(if (selected) 2.dp else 1.dp, if (selected) CaptainPalette.accent else CaptainPalette.panelBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
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
            color = if (highlight) CaptainPalette.accent else CaptainPalette.textPrimary,
        )
    }
}

// --- 22 · Receipt ----------------------------------------------------------------------------

@Composable
private fun ReceiptScreen(s: CloseAndPayUiState.ReceiptStep, vm: CloseAndPayViewModel) {
    val scope = rememberCoroutineScope2()
    var apiNote by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).background(CaptainPalette.panel).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(rememberDeckClock(), fontFamily = RobotoMonoFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = CaptainPalette.textSecondary)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(CaptainPalette.success).padding(horizontal = 18.dp, vertical = 6.dp),
            ) {
                Text("TRIP CLOSED", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp, color = CaptainPalette.bg)
            }
            Spacer(Modifier.weight(1f))
        }
        Row(modifier = Modifier.weight(1f).padding(horizontal = 96.dp, vertical = 24.dp)) {
            // Receipt paper — deliberately kept cream/monospace regardless of app theme; it mimics
            // a real thermal-printer slip, not a themed UI surface.
            Column(
                modifier = Modifier
                    .width(430.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF7F5EE))
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val r = s.receipt
                ReceiptMono(r.tripId.let { "LILLY CABS PTY LTD" }, bold = true, size = 20)
                ReceiptMono("ABN 12 345 678 901")
                ReceiptMono("TAX INVOICE / RECEIPT", bold = true, size = 15)
                ReceiptMono("Receipt ${r.receiptRef ?: "—"}")
                ReceiptMono("${r.startedAt} → ${r.closedAt}")
                ReceiptMono("Driver ${r.driverId} · Vehicle ${r.vehicleId}")
                ReceiptMono("------------------------------------", color = Color(0xFF9A968A))
                r.fareLines.forEach { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ReceiptMono(line.label)
                        ReceiptMono(line.amount)
                    }
                }
                ReceiptMono("====================================", color = Color(0xFF9A968A))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ReceiptMono("TOTAL", bold = true, size = 18)
                    ReceiptMono(r.total, bold = true, size = 18)
                }
                ReceiptMono("GST included ${r.gstComponent}")
                ReceiptMono("PAID — ${r.paymentMethod.uppercase()}", bold = true, color = Color(0xFF1C7C3E))
            }
            Spacer(Modifier.width(64.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Passenger copy", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = CaptainPalette.textPrimary)
                Text(
                    "Printer not paired — offer email or SMS. Reprint any time from Trip Detail.",
                    fontFamily = InterFamily,
                    fontSize = 16.sp,
                    color = CaptainPalette.textSecondary,
                )
                ReceiptActionButton(Icons.Rounded.Email, "Email receipt", busy = s.emailState == ActionState.IN_PROGRESS) {
                    scope.launch {
                        vm.sendEmailReceipt()
                        apiNote = runCatching {
                            AppContainer.apiService.emailReceipt(
                                s.receipt.tripId,
                                au.com.threesixty.cabdispatch.data.remote.ReceiptEmailRequestDto(s.emailAddress.ifBlank { "passenger@example.com" }),
                            )
                        }.fold({ if (it.mock) "Email queued (mock — no provider configured)" else "Email sent" }, { "Email failed to send" })
                    }
                }
                ReceiptActionButton(Icons.Rounded.Sms, "SMS receipt", busy = s.smsState == ActionState.IN_PROGRESS) {
                    scope.launch {
                        vm.sendSmsReceipt()
                        apiNote = runCatching {
                            AppContainer.apiService.smsReceipt(
                                s.receipt.tripId,
                                au.com.threesixty.cabdispatch.data.remote.ReceiptSmsRequestDto(s.phoneNumber.ifBlank { "0400000000" }),
                            )
                        }.fold({ if (it.mock) "SMS queued (mock — no provider configured)" else "SMS sent" }, { "SMS failed to send" })
                    }
                }
                ReceiptActionButton(Icons.Rounded.Print, "Print (printer offline)", busy = s.printState == ActionState.IN_PROGRESS, onClick = vm::printReceipt)
                apiNote?.let { Text(it, fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.textMuted) }
                CaptainButton(text = "Done — back to For Hire", heightDp = 72, widthDp = 480) {
                    vm.finishReceiptStep()
                }
            }
        }
        Text(
            "Trip synced ✓ · outbox clear · fare posted to shift totals",
            fontFamily = RobotoMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = CaptainPalette.textMuted,
            modifier = Modifier.padding(start = 96.dp, bottom = 20.dp),
        )
    }
}

@Composable
private fun ReceiptMono(text: String, bold: Boolean = false, size: Int = 13, color: Color = Color(0xFF23252B)) {
    Text(text, fontFamily = RobotoMonoFamily, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontSize = size.sp, color = color)
}

@Composable
private fun ReceiptActionButton(icon: ImageVector, label: String, busy: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .width(480.dp)
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CaptainPalette.raised)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.height(22.dp), color = CaptainPalette.textSecondary)
        } else {
            Icon(icon, contentDescription = null, tint = CaptainPalette.textSecondary, modifier = Modifier.size(22.dp))
            Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = CaptainPalette.textSecondary, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun rememberCoroutineScope2() = androidx.compose.runtime.rememberCoroutineScope()
