package au.com.threesixty.cabdispatch.ui.screens.closepay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.hardware.receipt.ReceiptLine
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import java.math.BigDecimal

/**
 * S4 — Close & Pay (spec B5). See [CloseAndPayViewModel] for the data flow;
 * this file is presentation-only.
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Close & Pay", style = MaterialTheme.typography.headlineSmall)
            // S6 (settings) reachable from anywhere, per spec B5.
            IconButton(onClick = { navController.navigate(CabDispatchRoutes.SETTINGS) }) {
                Text("⚙")
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

@Composable
private fun CenteredProgress() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { CircularProgressIndicator() }
}

@Composable
private fun CenteredMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun ReadyToCloseContent(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel) {
    val b = state.breakdown
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FareLineRow("Hiring charge", b.flagFall.money())
                    if (b.peakCharge.signum() > 0) FareLineRow("Peak time charge", b.peakCharge.money())
                    FareLineRow("Distance", b.distanceCharge.money())
                    FareLineRow("Waiting", b.waitingCharge.money())
                    if (b.tolls.signum() > 0) FareLineRow("Tolls", b.tolls.money())
                    if (b.psl.signum() > 0) FareLineRow("Point to Point Transport Levy", b.psl.money())
                    if (b.cleaningFee.signum() > 0) FareLineRow("Cleaning fee", b.cleaningFee.money())
                    if (b.extras.signum() > 0) FareLineRow("Extras", b.extras.money())
                    if (b.surcharge.signum() > 0) FareLineRow("Non-cash surcharge", b.surcharge.money())
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    FareLineRow("Total", b.grandTotal.money(), emphasize = true)
                    Text(
                        "includes GST of ${b.gstComponent.money()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Text("Payment method", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PaymentMethodOption.entries.forEach { option ->
                    val selected = state.paymentMethod == option
                    if (selected) {
                        Button(onClick = { vm.selectPaymentMethod(option) }) { Text(option.label) }
                    } else {
                        OutlinedButton(onClick = { vm.selectPaymentMethod(option) }) { Text(option.label) }
                    }
                }
            }
        }

        if (state.paymentMethod == PaymentMethodOption.TAP_TO_PAY || state.paymentMethod == PaymentMethodOption.PAYMENT_LINK) {
            item {
                Column {
                    Text(
                        "Surcharge: ${state.surchargePct}% (capped at ${state.tariff.surchargePctCap}%) = ${b.surcharge.money()}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = state.surchargePct.toFloat(),
                        onValueChange = { vm.setSurchargePct(BigDecimal(it.toString())) },
                        valueRange = 0f..state.tariff.surchargePctCap.toFloat(),
                    )
                }
            }
        }

        if (state.paymentMethod == PaymentMethodOption.CASH) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.cashTendered,
                        onValueChange = vm::setCashTendered,
                        label = { Text("Amount tendered") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val change = state.changeDue
                    Text(
                        if (change != null) "Change due: ${change.money()}" else "Enter an amount ≥ total to compute change",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (state.paymentMethod == PaymentMethodOption.CABCHARGE) {
            item {
                OutlinedTextField(
                    value = state.docketNumber,
                    onValueChange = vm::setDocketNumber,
                    label = { Text("CabCharge / TTSS docket number") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = state.includePsl, onCheckedChange = vm::setIncludePsl)
                Spacer(Modifier.width(8.dp))
                Text("Include Point to Point Transport Levy (PSL)")
            }
        }

        val paymentLinkUrl = state.paymentLinkUrl
        if (paymentLinkUrl != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Payment link / QR payload (mock — no real QR renderer wired in)", style = MaterialTheme.typography.labelMedium)
                        Text(paymentLinkUrl, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = vm::confirmPaymentLinkPaid, modifier = Modifier.fillMaxWidth()) {
                            Text("Mark as paid")
                        }
                    }
                }
            }
        }

        val paymentError = state.paymentError
        if (paymentError != null) {
            item { Text(paymentError, color = MaterialTheme.colorScheme.error) }
        }

        item {
            if (state.paymentInFlight) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            } else if (state.paymentLinkUrl == null) {
                Button(
                    onClick = vm::confirmPayment,
                    enabled = state.canConfirm,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when (state.paymentMethod) {
                            PaymentMethodOption.TAP_TO_PAY -> "Collect payment"
                            PaymentMethodOption.PAYMENT_LINK -> "Create payment link"
                            else -> "Confirm & close trip"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FareLineRow(label: String, amount: String, emphasize: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            amount,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ReceiptStepContent(state: CloseAndPayUiState.ReceiptStep, vm: CloseAndPayViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Receipt ${state.receipt.receiptRef ?: ""}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Paid by ${state.receipt.paymentMethod}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        items(state.receipt.fareLines) { line: ReceiptLine ->
            FareLineRow(line.label, line.amount)
        }

        item {
            Column {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                FareLineRow("Total", state.receipt.total, emphasize = true)
                Text(
                    "includes GST of ${state.receipt.gstComponent}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Send receipt", style = MaterialTheme.typography.titleMedium)

                ReceiptActionRow(
                    label = "Print (BT thermal)",
                    actionState = state.printState,
                    error = state.printError,
                    onClick = vm::printReceipt,
                )

                OutlinedTextField(
                    value = state.phoneNumber,
                    onValueChange = vm::setReceiptPhoneNumber,
                    label = { Text("Passenger phone (SMS)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                ReceiptActionRow(
                    label = "Send SMS",
                    actionState = state.smsState,
                    error = state.smsError,
                    onClick = vm::sendSmsReceipt,
                    enabled = state.phoneNumber.isNotBlank(),
                )

                OutlinedTextField(
                    value = state.emailAddress,
                    onValueChange = vm::setReceiptEmail,
                    label = { Text("Passenger email") },
                    modifier = Modifier.fillMaxWidth(),
                )
                ReceiptActionRow(
                    label = "Send email",
                    actionState = state.emailState,
                    error = state.emailError,
                    onClick = vm::sendEmailReceipt,
                    enabled = state.emailAddress.isNotBlank(),
                )
            }
        }

        item {
            Button(onClick = vm::finishReceiptStep, modifier = Modifier.fillMaxWidth()) {
                Text("Done — back to Idle")
            }
        }
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClick, enabled = enabled && actionState != ActionState.IN_PROGRESS) {
                Text(label)
            }
            when (actionState) {
                ActionState.IN_PROGRESS -> CircularProgressIndicator(modifier = Modifier.height(20.dp))
                ActionState.SUCCESS -> Text("Sent", color = MaterialTheme.colorScheme.primary)
                ActionState.FAILED -> Text("Failed", color = MaterialTheme.colorScheme.error)
                ActionState.IDLE -> Unit
            }
        }
        if (actionState == ActionState.FAILED && error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
