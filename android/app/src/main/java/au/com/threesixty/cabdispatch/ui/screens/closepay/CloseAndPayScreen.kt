package au.com.threesixty.cabdispatch.ui.screens.closepay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.fare.FareBreakdown
import au.com.threesixty.cabdispatch.hardware.receipt.ReceiptLine
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.CabDispatchColors
import au.com.threesixty.cabdispatch.ui.theme.WheelColors
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * S4 — Close & Pay (spec B5, doc TCT-DRIVER-APP-01 §7-8). Visuals match the
 * reference prototype's payment/receipt flow (dark wheel theme, gold primary
 * action) — all data flow/state still lives in [CloseAndPayViewModel]; this
 * file is presentation-only, plus two locally-scoped, UI-only navigation
 * states ([PaymentSubScreen]) for the cash calculator and CabCharge/TTSS
 * entry sub-screens (spec §8 row 22-27, previously "not designed").
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WheelColors.bg),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Close & Pay",
                    color = WheelColors.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                // S6 (settings) reachable from anywhere, per spec B5.
                IconButton(onClick = { navController.navigate(CabDispatchRoutes.SETTINGS) }) {
                    Text("⚙", color = WheelColors.textSecondary, fontSize = 18.sp)
                }
            }
            Spacer(Modifier.height(12.dp))

            when (val s = state) {
                is CloseAndPayUiState.Loading -> CenteredProgress()
                is CloseAndPayUiState.NoActiveTrip -> CenteredMessage("No active trip to close.")
                is CloseAndPayUiState.LoadError -> CenteredMessage(s.message)
                is CloseAndPayUiState.ReadyToClose -> ReadyToCloseContent(s, viewModel)
                is CloseAndPayUiState.ReceiptStep -> ReceiptStepContent(s, viewModel)
                is CloseAndPayUiState.Done -> Unit
            }
        }
    }
}

@Composable
private fun CenteredProgress() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { CircularProgressIndicator(color = WheelColors.gold) }
}

@Composable
private fun CenteredMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { Text(text, color = WheelColors.textSecondary, fontSize = 15.sp) }
}

// ---------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------

private enum class PaymentSubScreen { METHOD_PICKER, CASH_CALCULATOR, CABCHARGE_ENTRY, VOUCHER_ENTRY, ACCOUNT_ENTRY, SPLIT_FARE_ENTRY }

/** Always-visible fare summary — spec §7 step 1 "total fare + breakdown at top". */
@Composable
private fun FareSummaryHeader(breakdown: FareBreakdown, compact: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "TOTAL FARE",
            color = WheelColors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Text(
            breakdown.grandTotal.money(),
            color = WheelColors.textPrimary,
            fontSize = if (compact) 40.sp else 56.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            fareSummaryLine(breakdown),
            color = WheelColors.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp),
        )
    }
}

private fun fareSummaryLine(b: FareBreakdown): String {
    val parts = mutableListOf("Hiring charge ${b.flagFall.money()}")
    if (b.peakCharge.signum() > 0) parts += "Peak ${b.peakCharge.money()}"
    parts += "Distance ${b.distanceCharge.money()}"
    parts += "Waiting ${b.waitingCharge.money()}"
    parts += "Tolls ${b.tolls.money()}"
    if (b.psl.signum() > 0) parts += "PSL ${b.psl.money()}"
    if (b.cleaningFee.signum() > 0) parts += "Cleaning ${b.cleaningFee.money()}"
    if (b.extras.signum() > 0) parts += "Extras ${b.extras.money()}"
    if (b.surcharge.signum() > 0) parts += "Surcharge ${b.surcharge.money()}"
    return parts.joinToString(" · ")
}

@Composable
private fun WheelCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(WheelColors.surfaceRaised, RoundedCornerShape(16.dp))
            .border(1.dp, WheelColors.border, RoundedCornerShape(16.dp))
            .padding(18.dp),
        content = content,
    )
}

@Composable
private fun PrimaryPayButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = WheelColors.gold,
            contentColor = CabDispatchColors.Indigo,
            disabledContainerColor = WheelColors.gold.copy(alpha = 0.4f),
            disabledContentColor = CabDispatchColors.Indigo.copy(alpha = 0.6f),
        ),
    ) {
        Text(text.uppercase(), fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun SecondaryPayButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 58.dp) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(height),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, WheelColors.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = WheelColors.surfaceRaised,
            contentColor = WheelColors.textPrimary,
            disabledContentColor = WheelColors.textMuted,
        ),
    ) {
        Text(text.uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun wheelFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = WheelColors.textPrimary,
    unfocusedTextColor = WheelColors.textPrimary,
    focusedBorderColor = WheelColors.gold,
    unfocusedBorderColor = WheelColors.borderStrong,
    focusedContainerColor = WheelColors.surface,
    unfocusedContainerColor = WheelColors.surface,
    cursorColor = WheelColors.gold,
    focusedLabelColor = WheelColors.gold,
    unfocusedLabelColor = WheelColors.textSecondary,
    focusedPlaceholderColor = WheelColors.textMuted,
    unfocusedPlaceholderColor = WheelColors.textMuted,
)

@Composable
private fun ProcessingOverlay(label: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WheelColors.bg.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = WheelColors.gold, modifier = Modifier.size(44.dp), strokeWidth = 4.dp)
            Spacer(Modifier.height(14.dp))
            Text("Processing $label…", color = WheelColors.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------------------------------------------------------------------------
// ReadyToClose — method picker + the two sub-screens
// ---------------------------------------------------------------------------

@Composable
private fun ReadyToCloseContent(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel) {
    var subScreen by rememberSaveable { mutableStateOf(PaymentSubScreen.METHOD_PICKER) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (subScreen) {
            PaymentSubScreen.METHOD_PICKER -> MethodPickerScreen(
                state = state,
                vm = vm,
                onSelectCash = { vm.selectPaymentMethod(PaymentMethodOption.CASH); subScreen = PaymentSubScreen.CASH_CALCULATOR },
                onSelectCabCharge = { vm.selectPaymentMethod(PaymentMethodOption.CABCHARGE); subScreen = PaymentSubScreen.CABCHARGE_ENTRY },
                onSelectVoucher = { vm.selectPaymentMethod(PaymentMethodOption.VOUCHER); subScreen = PaymentSubScreen.VOUCHER_ENTRY },
                onSelectAccount = { vm.selectPaymentMethod(PaymentMethodOption.ACCOUNT); subScreen = PaymentSubScreen.ACCOUNT_ENTRY },
                onSelectSplitFare = { vm.selectPaymentMethod(PaymentMethodOption.SPLIT_FARE); subScreen = PaymentSubScreen.SPLIT_FARE_ENTRY },
            )
            PaymentSubScreen.CASH_CALCULATOR -> CashCalculatorScreen(
                state = state,
                vm = vm,
                onBack = { subScreen = PaymentSubScreen.METHOD_PICKER },
            )
            PaymentSubScreen.CABCHARGE_ENTRY -> CabChargeEntryScreen(
                state = state,
                vm = vm,
                onBack = { subScreen = PaymentSubScreen.METHOD_PICKER },
            )
            PaymentSubScreen.VOUCHER_ENTRY -> VoucherEntryScreen(
                state = state,
                vm = vm,
                onBack = { subScreen = PaymentSubScreen.METHOD_PICKER },
            )
            PaymentSubScreen.ACCOUNT_ENTRY -> AccountEntryScreen(
                state = state,
                vm = vm,
                onBack = { subScreen = PaymentSubScreen.METHOD_PICKER },
            )
            PaymentSubScreen.SPLIT_FARE_ENTRY -> SplitFareEntryScreen(
                state = state,
                vm = vm,
                onBack = { subScreen = PaymentSubScreen.METHOD_PICKER },
            )
        }

        val method = state.paymentMethod
        if (state.paymentInFlight) {
            ProcessingOverlay(label = method.label)
        }
    }
}

@Composable
private fun MethodPickerScreen(
    state: CloseAndPayUiState.ReadyToClose,
    vm: CloseAndPayViewModel,
    onSelectCash: () -> Unit,
    onSelectCabCharge: () -> Unit,
    onSelectVoucher: () -> Unit,
    onSelectAccount: () -> Unit,
    onSelectSplitFare: () -> Unit,
) {
    val b = state.breakdown
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
        item { Spacer(Modifier.height(8.dp)) }
        item { FareSummaryHeader(b) }

        item {
            WheelCard {
                FareLineRow("Hiring charge", b.flagFall.money())
                if (b.peakCharge.signum() > 0) FareLineRow("Peak time charge", b.peakCharge.money())
                FareLineRow("Distance", b.distanceCharge.money())
                FareLineRow("Waiting", b.waitingCharge.money())
                FareLineRow("Tolls", b.tolls.money())
                if (b.psl.signum() > 0) FareLineRow("Point to Point Transport Levy", b.psl.money())
                if (b.cleaningFee.signum() > 0) FareLineRow("Cleaning fee", b.cleaningFee.money())
                if (b.extras.signum() > 0) FareLineRow("Extras", b.extras.money())
                if (b.surcharge.signum() > 0) FareLineRow("Non-cash surcharge", b.surcharge.money())
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = WheelColors.border)
                FareLineRow("Total", b.grandTotal.money(), emphasize = true)
                Text(
                    "includes GST of ${b.gstComponent.money()}",
                    fontSize = 12.sp,
                    color = WheelColors.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PAYMENT METHOD", color = WheelColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                PrimaryPayButton("Tap to Pay", onClick = { vm.selectPaymentMethod(PaymentMethodOption.TAP_TO_PAY) })
                SecondaryPayButton("Cash", onClick = onSelectCash)
                SecondaryPayButton("Payment Link", onClick = { vm.selectPaymentMethod(PaymentMethodOption.PAYMENT_LINK) })
                SecondaryPayButton("CabCharge · TTSS", onClick = onSelectCabCharge)
                SecondaryPayButton("Voucher", onClick = onSelectVoucher)
                SecondaryPayButton("Account", onClick = onSelectAccount)
                SecondaryPayButton("Split Fare", onClick = onSelectSplitFare)
            }
        }

        if (state.paymentMethod == PaymentMethodOption.TAP_TO_PAY || state.paymentMethod == PaymentMethodOption.PAYMENT_LINK) {
            item {
                WheelCard {
                    Text(
                        "Non-cash surcharge: ${state.surchargePct}% (capped at ${state.tariff.surchargePctCap}%) = ${b.surcharge.money()}",
                        color = WheelColors.textSecondary,
                        fontSize = 13.sp,
                    )
                    Slider(
                        value = state.surchargePct.toFloat(),
                        onValueChange = { vm.setSurchargePct(BigDecimal(it.toString())) },
                        valueRange = 0f..state.tariff.surchargePctCap.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = WheelColors.gold,
                            activeTrackColor = WheelColors.gold,
                            inactiveTrackColor = WheelColors.borderStrong,
                        ),
                    )
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = state.includePsl,
                    onCheckedChange = vm::setIncludePsl,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = WheelColors.gold,
                        checkedTrackColor = WheelColors.surfaceSunken,
                        uncheckedThumbColor = WheelColors.textMuted,
                        uncheckedTrackColor = WheelColors.surface,
                    ),
                )
                Spacer(Modifier.width(10.dp))
                Text("Include Point to Point Transport Levy (PSL)", color = WheelColors.textPrimary, fontSize = 13.sp)
            }
        }

        val paymentLinkUrl = state.paymentLinkUrl
        if (paymentLinkUrl != null) {
            item {
                WheelCard {
                    Text("Payment link / QR payload (mock — no real QR renderer wired in)", color = WheelColors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(paymentLinkUrl, color = WheelColors.textPrimary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                    PrimaryPayButton("Mark as paid", onClick = vm::confirmPaymentLinkPaid)
                }
            }
        }

        val paymentError = state.paymentError
        if (paymentError != null) {
            item { Text(paymentError, color = WheelColors.duress, fontSize = 13.sp) }
        }

        if ((state.paymentMethod == PaymentMethodOption.TAP_TO_PAY || state.paymentMethod == PaymentMethodOption.PAYMENT_LINK) &&
            state.paymentLinkUrl == null
        ) {
            item {
                PrimaryPayButton(
                    text = if (state.paymentMethod == PaymentMethodOption.TAP_TO_PAY) "Collect payment" else "Create payment link",
                    onClick = vm::confirmPayment,
                    enabled = state.canConfirm && !state.paymentInFlight,
                )
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun BackHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        IconButton(onClick = onBack) {
            Text("‹", color = WheelColors.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Text(title, color = WheelColors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** Cash calculator sub-screen — spec §8 row 22-27, "not designed" before this pass. Feeds [CloseAndPayViewModel.setCashTendered] (existing wiring, unchanged). */
@Composable
private fun CashCalculatorScreen(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, onBack: () -> Unit) {
    val total = state.breakdown.grandTotal
    val quickAmounts = remember(total) {
        listOf(
            "Exact fare" to total.setScale(2, RoundingMode.HALF_UP),
            "$20" to BigDecimal(20),
            "$50" to BigDecimal(50),
            "$100" to BigDecimal(100),
        )
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
        item { BackHeader("Cash payment", onBack) }
        item { FareSummaryHeader(state.breakdown, compact = true) }

        item {
            WheelCard {
                Text("AMOUNT TENDERED", color = WheelColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.cashTendered,
                    onValueChange = vm::setCashTendered,
                    placeholder = { Text("0.00") },
                    textStyle = MaterialTheme.typography.headlineMedium.copy(color = WheelColors.textPrimary, fontWeight = FontWeight.Bold),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    colors = wheelFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    quickAmounts.forEach { (label, amount) ->
                        OutlinedButton(
                            onClick = { vm.setCashTendered(amount.toPlainString()) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, WheelColors.border),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = WheelColors.surface,
                                contentColor = WheelColors.textSecondary,
                            ),
                        ) { Text(label, fontSize = 11.sp) }
                    }
                }
            }
        }

        item {
            val change = state.changeDue
            WheelCard {
                Text("CHANGE DUE", color = WheelColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    change?.money() ?: "—",
                    color = if (change != null) WheelColors.available else WheelColors.textMuted,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                if (change == null) {
                    Text(
                        "Enter an amount ≥ total (${total.money()}) to compute change",
                        color = WheelColors.textMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        val paymentError = state.paymentError
        if (paymentError != null) {
            item { Text(paymentError, color = WheelColors.duress, fontSize = 13.sp) }
        }

        item {
            PrimaryPayButton(
                "Confirm & close trip",
                onClick = vm::confirmPayment,
                enabled = state.canConfirm && !state.paymentInFlight,
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** CabCharge / TTSS manual entry sub-screen — spec §8 row 22-27, "not designed" before this pass. Docket number feeds the existing manual-payment path via [CloseAndPayViewModel.setDocketNumber]; notes are new (local-only, surfaced on the receipt — see [CloseAndPayViewModel.setDocketNotes]). */
@Composable
private fun CabChargeEntryScreen(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, onBack: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
        item { BackHeader("CabCharge / TTSS payment", onBack) }
        item { FareSummaryHeader(state.breakdown, compact = true) }

        item {
            WheelCard {
                Text("DOCKET NUMBER", color = WheelColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.docketNumber,
                    onValueChange = vm::setDocketNumber,
                    placeholder = { Text("e.g. TTSS-0001234") },
                    singleLine = true,
                    colors = wheelFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text("NOTES (OPTIONAL)", color = WheelColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.docketNotes,
                    onValueChange = vm::setDocketNotes,
                    placeholder = { Text("e.g. voucher scheme reference, passenger name") },
                    minLines = 3,
                    colors = wheelFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        val paymentError = state.paymentError
        if (paymentError != null) {
            item { Text(paymentError, color = WheelColors.duress, fontSize = 13.sp) }
        }

        item {
            PrimaryPayButton(
                "Confirm & close trip",
                onClick = vm::confirmPayment,
                enabled = state.canConfirm && !state.paymentInFlight,
            )
            if (!state.canConfirm) {
                Text(
                    "Enter a docket number to continue",
                    color = WheelColors.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** Voucher entry sub-screen (see [PaymentMethodOption.VOUCHER]'s doc). Feeds
 * [CloseAndPayViewModel.setVoucherCode]; [CloseAndPayUiState.ReadyToClose.canConfirm] requires a
 * non-blank code before "Confirm & close trip" enables, mirroring the backend's 422 rule. */
@Composable
private fun VoucherEntryScreen(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, onBack: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
        item { BackHeader("Voucher payment", onBack) }
        item { FareSummaryHeader(state.breakdown, compact = true) }

        item {
            WheelCard {
                Text("VOUCHER CODE", color = WheelColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.voucherCode,
                    onValueChange = vm::setVoucherCode,
                    placeholder = { Text("e.g. WELCOME10") },
                    singleLine = true,
                    colors = wheelFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        val paymentError = state.paymentError
        if (paymentError != null) {
            item { Text(paymentError, color = WheelColors.duress, fontSize = 13.sp) }
        }

        item {
            PrimaryPayButton(
                "Confirm & close trip",
                onClick = vm::confirmPayment,
                enabled = state.canConfirm && !state.paymentInFlight,
            )
            if (!state.canConfirm) {
                Text(
                    "Enter a voucher code to continue",
                    color = WheelColors.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** Corporate/linked account entry sub-screen (see [PaymentMethodOption.ACCOUNT]'s doc). Feeds
 * [CloseAndPayViewModel.setAccountReference]; same non-blank-before-confirm rule as
 * [VoucherEntryScreen]. */
@Composable
private fun AccountEntryScreen(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, onBack: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
        item { BackHeader("Account payment", onBack) }
        item { FareSummaryHeader(state.breakdown, compact = true) }

        item {
            WheelCard {
                Text("ACCOUNT REFERENCE", color = WheelColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.accountReference,
                    onValueChange = vm::setAccountReference,
                    placeholder = { Text("e.g. ACC-04821") },
                    singleLine = true,
                    colors = wheelFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        val paymentError = state.paymentError
        if (paymentError != null) {
            item { Text(paymentError, color = WheelColors.duress, fontSize = 13.sp) }
        }

        item {
            PrimaryPayButton(
                "Confirm & close trip",
                onClick = vm::confirmPayment,
                enabled = state.canConfirm && !state.paymentInFlight,
            )
            if (!state.canConfirm) {
                Text(
                    "Enter an account reference to continue",
                    color = WheelColors.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** Small chip-row selector for one [SplitLegMethod] leg — deliberately not a full dropdown menu,
 * only 4 options, matching this screen's existing quick-amount chip-row pattern
 * ([CashCalculatorScreen]'s `quickAmounts` row). */
@Composable
private fun SplitLegMethodSelector(selected: SplitLegMethod, onSelect: (SplitLegMethod) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SplitLegMethod.entries.forEach { option ->
            val isSelected = option == selected
            OutlinedButton(
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, if (isSelected) WheelColors.gold else WheelColors.border),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (isSelected) WheelColors.gold.copy(alpha = 0.15f) else WheelColors.surface,
                    contentColor = if (isSelected) WheelColors.gold else WheelColors.textSecondary,
                ),
            ) { Text(option.label, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * Split-fare sub-screen (see [PaymentMethodOption.SPLIT_FARE]'s doc) — exactly two legs, each a
 * [SplitLegMethodSelector] + amount field. "Confirm & close trip" only enables once both amounts
 * are positive numbers that sum, to the cent, to the total fare
 * ([CloseAndPayUiState.ReadyToClose.canConfirm]) — the "remaining to allocate" card below gives the
 * driver the same kind of live feedback [CashCalculatorScreen]'s "change due" card gives for Cash.
 */
@Composable
private fun SplitFareEntryScreen(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, onBack: () -> Unit) {
    val total = state.breakdown.grandTotal
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
        item { BackHeader("Split fare payment", onBack) }
        item { FareSummaryHeader(state.breakdown, compact = true) }

        item {
            WheelCard {
                Text("PAYMENT 1", color = WheelColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                SplitLegMethodSelector(selected = state.splitLegAMethod, onSelect = vm::setSplitLegAMethod)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.splitLegAAmount,
                    onValueChange = vm::setSplitLegAAmount,
                    placeholder = { Text("0.00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    colors = wheelFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            WheelCard {
                Text("PAYMENT 2", color = WheelColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                SplitLegMethodSelector(selected = state.splitLegBMethod, onSelect = vm::setSplitLegBMethod)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.splitLegBAmount,
                    onValueChange = vm::setSplitLegBAmount,
                    placeholder = { Text("0.00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    colors = wheelFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            val remaining = state.splitRemaining
            WheelCard {
                Text("REMAINING TO ALLOCATE", color = WheelColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    remaining?.money() ?: "—",
                    color = if (remaining != null && remaining.signum() == 0) WheelColors.available else WheelColors.textMuted,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "Total fare ${total.money()} must be split exactly across both payments",
                    color = WheelColors.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        val paymentError = state.paymentError
        if (paymentError != null) {
            item { Text(paymentError, color = WheelColors.duress, fontSize = 13.sp) }
        }

        item {
            PrimaryPayButton(
                "Confirm & close trip",
                onClick = vm::confirmPayment,
                enabled = state.canConfirm && !state.paymentInFlight,
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun FareLineRow(label: String, amount: String, emphasize: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = if (emphasize) WheelColors.textPrimary else WheelColors.textSecondary,
            fontSize = if (emphasize) 16.sp else 14.sp,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            amount,
            color = if (emphasize) WheelColors.gold else WheelColors.textPrimary,
            fontSize = if (emphasize) 16.sp else 14.sp,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ---------------------------------------------------------------------------
// Receipt — spec §7 step 3: checkmark, total, method, breakdown incl. GST,
// EMAIL RECEIPT / SMS RECEIPT actions, DONE.
// ---------------------------------------------------------------------------

@Composable
private fun ReceiptStepContent(state: CloseAndPayUiState.ReceiptStep, vm: CloseAndPayViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        item { Spacer(Modifier.height(8.dp)) }

        item {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(WheelColors.available, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                Text("Payment received", color = WheelColors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Paid via ${state.receipt.paymentMethod}",
                    color = WheelColors.textSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
                )
                Text(state.receipt.total, color = WheelColors.gold, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        item {
            WheelCard {
                state.receipt.fareLines.forEach { line: ReceiptLine -> FareLineRow(line.label, line.amount) }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = WheelColors.border)
                FareLineRow("Total", state.receipt.total, emphasize = true)
                Text(
                    "includes GST of ${state.receipt.gstComponent}",
                    color = WheelColors.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item {
            WheelCard {
                Text("SEND RECEIPT", color = WheelColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(12.dp))

                ReceiptActionRow(
                    label = "Print (BT thermal)",
                    actionState = state.printState,
                    error = state.printError,
                    onClick = vm::printReceipt,
                )

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = state.phoneNumber,
                    onValueChange = vm::setReceiptPhoneNumber,
                    label = { Text("Passenger phone (SMS)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = wheelFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                ReceiptActionRow(
                    label = "SMS Receipt",
                    actionState = state.smsState,
                    error = state.smsError,
                    onClick = vm::sendSmsReceipt,
                    enabled = state.phoneNumber.isNotBlank(),
                )

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = state.emailAddress,
                    onValueChange = vm::setReceiptEmail,
                    label = { Text("Passenger email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = wheelFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                ReceiptActionRow(
                    label = "Email Receipt",
                    actionState = state.emailState,
                    error = state.emailError,
                    onClick = vm::sendEmailReceipt,
                    enabled = state.emailAddress.isNotBlank(),
                )
            }
        }

        item {
            PrimaryPayButton("Done", onClick = vm::finishReceiptStep)
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ReceiptActionRow(
    label: String,
    actionState: ActionState,
    error: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryPayButton(
                text = label,
                onClick = onClick,
                modifier = Modifier.weight(1f),
                enabled = enabled && actionState != ActionState.IN_PROGRESS,
                height = 48.dp,
            )
            when (actionState) {
                ActionState.IN_PROGRESS -> CircularProgressIndicator(color = WheelColors.gold, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                ActionState.SUCCESS -> Text("Sent", color = WheelColors.available, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                ActionState.FAILED -> Text("Failed", color = WheelColors.duress, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                ActionState.IDLE -> Unit
            }
        }
        if (actionState == ActionState.FAILED && error != null) {
            Text(error, color = WheelColors.duress, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
