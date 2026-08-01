package au.com.threesixty.cabdispatch.ui.screens.closepay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.fare.FareBreakdown
import au.com.threesixty.cabdispatch.domain.fare.Tariff
import au.com.threesixty.cabdispatch.domain.fare.reconstructFareState
import au.com.threesixty.cabdispatch.domain.fare.toDomainTariff
import au.com.threesixty.cabdispatch.hardware.receipt.Receipt
import au.com.threesixty.cabdispatch.hardware.receipt.ReceiptLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * S4 — Close & Pay (spec B5). Reads the single active trip straight from
 * [AppContainer.tripRepository] (Room-backed, offline-first — see that
 * class's doc), reconstructs a [FareBreakdown] via
 * [au.com.threesixty.cabdispatch.domain.fare.FareEngine.close] (see
 * [au.com.threesixty.cabdispatch.domain.fare.reconstructFareState] for why
 * that reconstruction from persisted totals is exact), and drives payment
 * collection through the S4-S6 agent's hardware gateway interfaces
 * ([AppContainer.cardPaymentGateway] etc.) — none of which require
 * connectivity to reach [confirmPayment] for cash/CabCharge, matching spec
 * B7.
 *
 * RESOLVED INTEGRATION GAP (integration pass): [TripEntity] used to never be
 * persisted at all — S3 (HIRED) drove its on-screen running fare entirely
 * through its own separate, in-memory `domain.FareEngineImpl`/`domain.FareState`
 * (a different pair of types from this package's, see the note in
 * AppContainer.kt) with no Room write anywhere, so this screen always showed
 * [CloseAndPayUiState.NoActiveTrip], even right after S3's "End trip" button
 * navigated here. Fixed upstream, per the plan this comment used to describe:
 * [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel] now calls
 * `AppContainer.tripRepository.openTrip(...)` when the live engine starts and
 * `.tick(...)` on every live-engine emission (see `FareState.movingSeconds`/
 * `.waitingSeconds`), so this screen's
 * [au.com.threesixty.cabdispatch.domain.fare.reconstructFareState] approach
 * now has real persisted totals to read by the time the driver lands here.
 */
enum class PaymentMethodOption(val label: String, val persistedValue: String) {
    TAP_TO_PAY("Tap to Pay", "card"),
    PAYMENT_LINK("Payment Link / QR", "card"),
    CASH("Cash", "cash"),

    // TODO(backend agent): TripEntity/TripCloseRequestDto.paymentMethod is
    // documented as "cash | card" only — CabCharge/TTSS docket payments are
    // persisted as "card" for now (they typically incur a merchant surcharge
    // in the real world too, so this is the closer fit of the two), but the
    // richer selection + docket number are captured only in the local
    // Receipt, not synced to the server. Extend the backend enum before this
    // needs to be reported distinctly (e.g. a CabCharge reconciliation
    // report).
    CABCHARGE("CabCharge / TTSS", "card"),
}

enum class ActionState { IDLE, IN_PROGRESS, SUCCESS, FAILED }

sealed interface CloseAndPayUiState {
    data object Loading : CloseAndPayUiState
    data object NoActiveTrip : CloseAndPayUiState
    data class LoadError(val message: String) : CloseAndPayUiState

    data class ReadyToClose(
        val trip: TripEntity,
        val tariff: Tariff,
        val paymentMethod: PaymentMethodOption,
        val surchargePct: BigDecimal,
        val cleaningFee: BigDecimal,
        val includePsl: Boolean,
        val cashTendered: String,
        val docketNumber: String,
        // CabCharge/TTSS manual entry — spec §8 row 22-27 sub-screen. Local-only, same as
        // [docketNumber] (see the CABCHARGE enum entry's TODO — backend paymentMethod enum has
        // no CabCharge-specific fields yet), surfaced on the receipt via [buildReceipt].
        val docketNotes: String,
        val breakdown: FareBreakdown,
        val paymentInFlight: Boolean,
        val paymentError: String?,
        val paymentLinkUrl: String?,
    ) : CloseAndPayUiState {
        val changeDue: BigDecimal?
            get() {
                val tendered = cashTendered.toBigDecimalOrNull() ?: return null
                val change = tendered - breakdown.grandTotal
                return if (change >= BigDecimal.ZERO) change else null
            }

        val canConfirm: Boolean
            get() = when (paymentMethod) {
                PaymentMethodOption.CASH ->
                    (cashTendered.toBigDecimalOrNull() ?: BigDecimal.ZERO) >= breakdown.grandTotal
                PaymentMethodOption.CABCHARGE -> docketNumber.isNotBlank()
                PaymentMethodOption.TAP_TO_PAY, PaymentMethodOption.PAYMENT_LINK -> true
            }
    }

    data class ReceiptStep(
        val receipt: Receipt,
        val printState: ActionState = ActionState.IDLE,
        val smsState: ActionState = ActionState.IDLE,
        val emailState: ActionState = ActionState.IDLE,
        val phoneNumber: String = "",
        val emailAddress: String = "",
        val printError: String? = null,
        val smsError: String? = null,
        val emailError: String? = null,
    ) : CloseAndPayUiState

    data object Done : CloseAndPayUiState
}

private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()

/** Formats a fare-engine [BigDecimal] money value for display — never use Float/Double, see ApiService.kt header. */
fun BigDecimal.money(): String = "$" + this.setScale(2, RoundingMode.HALF_UP).toPlainString()

class CloseAndPayViewModel : ViewModel() {

    private val tripRepository = AppContainer.tripRepository
    private val fareEngine = AppContainer.pureFareEngine

    private companion object {
        /** Minimum on-screen duration of the "Processing…" spinner — spec §7 step 2 ("brief"). */
        const val PROCESSING_MIN_MS = 1200L
    }

    private val _uiState = MutableStateFlow<CloseAndPayUiState>(CloseAndPayUiState.Loading)
    val uiState: StateFlow<CloseAndPayUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            tripRepository.observeActiveTrip().collect { trip ->
                if (trip == null) {
                    // Once we've moved past ReadyToClose (closeTrip flips the
                    // trip's status away from OPEN, so observeActiveTrip()
                    // legitimately emits null right after a successful
                    // close) don't stomp the receipt step back to
                    // NoActiveTrip.
                    if (_uiState.value !is CloseAndPayUiState.ReceiptStep && _uiState.value != CloseAndPayUiState.Done) {
                        _uiState.value = CloseAndPayUiState.NoActiveTrip
                    }
                    return@collect
                }
                if (_uiState.value is CloseAndPayUiState.Loading || _uiState.value is CloseAndPayUiState.NoActiveTrip) {
                    loadTariffAndInit(trip)
                }
            }
        }
    }

    private suspend fun loadTariffAndInit(trip: TripEntity) {
        val tariffEntity = AppContainer.tariffDao.getById(trip.tariffId)
        if (tariffEntity == null) {
            _uiState.value = CloseAndPayUiState.LoadError(
                "No cached tariff for this trip (tariffId=${trip.tariffId}) — cannot compute the closing fare.",
            )
            return
        }
        val tariffDto = runCatching {
            cabDispatchJson.decodeFromString<TariffDto>(tariffEntity.rawJson)
        }.getOrNull()
        if (tariffDto == null) {
            _uiState.value = CloseAndPayUiState.LoadError(
                "Cached tariff payload is corrupt (tariffId=${trip.tariffId}).",
            )
            return
        }
        val tariff = tariffDto.toDomainTariff()
        val method = PaymentMethodOption.CASH
        val breakdown = recompute(trip, tariff, method, BigDecimal.ZERO, BigDecimal.ZERO, includePsl = false)
        _uiState.value = CloseAndPayUiState.ReadyToClose(
            trip = trip,
            tariff = tariff,
            paymentMethod = method,
            surchargePct = BigDecimal.ZERO,
            cleaningFee = BigDecimal.ZERO,
            includePsl = false,
            cashTendered = "",
            docketNumber = "",
            docketNotes = "",
            breakdown = breakdown,
            paymentInFlight = false,
            paymentError = null,
            paymentLinkUrl = null,
        )
    }

    private fun recompute(
        trip: TripEntity,
        tariff: Tariff,
        method: PaymentMethodOption,
        surchargePct: BigDecimal,
        cleaningFee: BigDecimal,
        includePsl: Boolean,
    ): FareBreakdown {
        val state = reconstructFareState(trip, tariff)
        return fareEngine.close(
            state = state,
            paymentMethod = method.persistedValue,
            surchargePct = surchargePct,
            cleaningFee = cleaningFee,
            includePsl = includePsl,
        )
    }

    private inline fun updateReady(block: (CloseAndPayUiState.ReadyToClose) -> CloseAndPayUiState.ReadyToClose) {
        val current = _uiState.value
        if (current is CloseAndPayUiState.ReadyToClose) {
            _uiState.value = block(current)
        }
    }

    private fun recomputed(state: CloseAndPayUiState.ReadyToClose): CloseAndPayUiState.ReadyToClose {
        val breakdown = recompute(
            state.trip,
            state.tariff,
            state.paymentMethod,
            state.surchargePct,
            state.cleaningFee,
            state.includePsl,
        )
        return state.copy(breakdown = breakdown)
    }

    /** Live surcharge line — spec B5 S4 "surcharge line auto-computed <=5%". Card-family methods default to a 1.5% pass-through, clamped at the tariff's cap; cash is always 0. */
    fun selectPaymentMethod(method: PaymentMethodOption) = updateReady { state ->
        val surcharge = if (method == PaymentMethodOption.CASH) {
            BigDecimal.ZERO
        } else {
            BigDecimal("1.5").min(state.tariff.surchargePctCap)
        }
        recomputed(
            state.copy(
                paymentMethod = method,
                surchargePct = surcharge,
                paymentError = null,
                paymentLinkUrl = null,
            ),
        )
    }

    fun setSurchargePct(pct: BigDecimal) = updateReady { state ->
        recomputed(state.copy(surchargePct = pct.coerceIn(BigDecimal.ZERO, state.tariff.surchargePctCap)))
    }

    fun setCleaningFee(fee: BigDecimal) = updateReady { state ->
        recomputed(state.copy(cleaningFee = fee.coerceAtLeast(BigDecimal.ZERO)))
    }

    fun setIncludePsl(include: Boolean) = updateReady { state -> recomputed(state.copy(includePsl = include)) }

    fun setCashTendered(value: String) = updateReady { it.copy(cashTendered = value) }

    fun setDocketNumber(value: String) = updateReady { it.copy(docketNumber = value) }
    fun setDocketNotes(value: String) = updateReady { it.copy(docketNotes = value) }

    fun confirmPayment() {
        val state = _uiState.value as? CloseAndPayUiState.ReadyToClose ?: return
        if (!state.canConfirm || state.paymentInFlight) return
        when (state.paymentMethod) {
            PaymentMethodOption.TAP_TO_PAY -> collectCardPayment(state)
            PaymentMethodOption.PAYMENT_LINK -> createLink(state)
            PaymentMethodOption.CASH, PaymentMethodOption.CABCHARGE -> finalizeCloseWithProcessingDelay(state)
        }
    }

    private fun amountCents(breakdown: FareBreakdown): Long =
        breakdown.grandTotal.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()

    /**
     * Ensures [block] appears to take at least [PROCESSING_MIN_MS] — spec §7 step 2: "Selecting a
     * method → brief processing state (spinner + 'Processing [method]…')". The mock card gateway
     * already runs long enough on its own (1.5s collect / 0.5s link — see
     * [au.com.threesixty.cabdispatch.hardware.payments.MockCardPaymentGateway]) that this is a
     * no-op there, but a real/fast implementation (or a fast network) must still show the spinner
     * for a perceptible, consistent beat rather than flashing through instantly.
     */
    private suspend fun <T> withMinimumProcessingDelay(block: suspend () -> T): T {
        val start = System.currentTimeMillis()
        val result = block()
        val elapsed = System.currentTimeMillis() - start
        if (elapsed < PROCESSING_MIN_MS) delay(PROCESSING_MIN_MS - elapsed)
        return result
    }

    private fun collectCardPayment(state: CloseAndPayUiState.ReadyToClose) {
        updateReady { it.copy(paymentInFlight = true, paymentError = null) }
        viewModelScope.launch {
            val result = withMinimumProcessingDelay {
                AppContainer.cardPaymentGateway.collectPayment(amountCents(state.breakdown))
            }
            result.onSuccess {
                finalizeClose(state)
            }.onFailure { error ->
                updateReady { it.copy(paymentInFlight = false, paymentError = error.message ?: "Payment failed") }
            }
        }
    }

    private fun createLink(state: CloseAndPayUiState.ReadyToClose) {
        updateReady { it.copy(paymentInFlight = true, paymentError = null) }
        viewModelScope.launch {
            val result = withMinimumProcessingDelay {
                AppContainer.cardPaymentGateway.createPaymentLink(amountCents(state.breakdown))
            }
            result.onSuccess { link ->
                updateReady { it.copy(paymentInFlight = false, paymentLinkUrl = link.url) }
            }.onFailure { error ->
                updateReady { it.copy(paymentInFlight = false, paymentError = error.message ?: "Could not create payment link") }
            }
        }
    }

    /**
     * Cash and CabCharge/TTSS never round-trip a real gateway (see [PaymentMethodOption.CABCHARGE]
     * doc), so without this they'd close instantly and skip the processing beat every other method
     * gets. Simulates the same driver-perceived pause per spec §7 step 2.
     */
    private fun finalizeCloseWithProcessingDelay(state: CloseAndPayUiState.ReadyToClose) {
        updateReady { it.copy(paymentInFlight = true, paymentError = null) }
        viewModelScope.launch {
            delay(PROCESSING_MIN_MS)
            finalizeClose(state)
        }
    }

    /** Driver taps "Mark as paid" once the passenger has completed a payment-link payment (async/out-of-band — see [au.com.threesixty.cabdispatch.hardware.payments.CardPaymentGateway.createPaymentLink] doc). */
    fun confirmPaymentLinkPaid() {
        val state = _uiState.value as? CloseAndPayUiState.ReadyToClose ?: return
        finalizeClose(state)
    }

    private fun finalizeClose(state: CloseAndPayUiState.ReadyToClose) {
        updateReady { it.copy(paymentInFlight = true, paymentError = null) }
        viewModelScope.launch {
            val receiptRef = "RCPT-${state.trip.clientUuid.take(8).uppercase()}"
            val closed = tripRepository.closeTrip(
                clientUuid = state.trip.clientUuid,
                // TODO: reconcile with S3/GPS sibling — S4 has no live
                // location fix of its own; ideally S3 hands off the trip's
                // last known fix (e.g. via SessionHolder.pendingTrip-style
                // hand-off) instead of this falling back to the start point.
                endLat = state.trip.startLat,
                endLng = state.trip.startLng,
                deviceTotal = state.breakdown.grandTotal.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                paymentMethod = state.paymentMethod.persistedValue,
                surchargePct = state.surchargePct.toPlainString(),
                cleaningFee = state.cleaningFee.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                includePsl = state.includePsl,
                receiptRef = receiptRef,
            )
            _uiState.value = CloseAndPayUiState.ReceiptStep(receipt = buildReceipt(closed, state))
        }
    }

    private fun buildReceipt(trip: TripEntity, state: CloseAndPayUiState.ReadyToClose): Receipt {
        val b = state.breakdown
        val lines = buildList {
            add(ReceiptLine("Hiring charge", b.flagFall.money()))
            if (b.peakCharge.signum() > 0) add(ReceiptLine("Peak time charge", b.peakCharge.money()))
            add(ReceiptLine("Distance", b.distanceCharge.money()))
            add(ReceiptLine("Waiting", b.waitingCharge.money()))
            if (b.tolls.signum() > 0) add(ReceiptLine("Tolls", b.tolls.money()))
            if (b.psl.signum() > 0) add(ReceiptLine("Point to Point Transport Levy", b.psl.money()))
            if (b.cleaningFee.signum() > 0) add(ReceiptLine("Cleaning fee", b.cleaningFee.money()))
            if (b.extras.signum() > 0) add(ReceiptLine("Extras", b.extras.money()))
            if (b.surcharge.signum() > 0) add(ReceiptLine("Non-cash payment surcharge", b.surcharge.money()))
            when (state.paymentMethod) {
                PaymentMethodOption.CASH -> {
                    state.cashTendered.toBigDecimalOrNull()?.let { tendered ->
                        add(ReceiptLine("Amount tendered", tendered.money()))
                        state.changeDue?.let { change -> add(ReceiptLine("Change given", change.money())) }
                    }
                }
                PaymentMethodOption.CABCHARGE -> {
                    add(ReceiptLine("CabCharge / TTSS docket", state.docketNumber))
                    if (state.docketNotes.isNotBlank()) add(ReceiptLine("Notes", state.docketNotes))
                }
                PaymentMethodOption.TAP_TO_PAY, PaymentMethodOption.PAYMENT_LINK -> Unit
            }
        }
        return Receipt(
            tripId = trip.clientUuid,
            vehicleId = trip.vehicleId,
            driverId = trip.driverId,
            startedAt = trip.startAt,
            closedAt = trip.endAt ?: "",
            fareLines = lines,
            subtotal = b.fareTotal.money(),
            surcharge = b.surcharge.money(),
            total = b.grandTotal.money(),
            gstComponent = b.gstComponent.money(),
            paymentMethod = state.paymentMethod.label,
            receiptRef = trip.receiptRef,
        )
    }

    fun setReceiptPhoneNumber(value: String) = updateReceipt { it.copy(phoneNumber = value, smsError = null) }
    fun setReceiptEmail(value: String) = updateReceipt { it.copy(emailAddress = value, emailError = null) }

    private inline fun updateReceipt(block: (CloseAndPayUiState.ReceiptStep) -> CloseAndPayUiState.ReceiptStep) {
        val current = _uiState.value
        if (current is CloseAndPayUiState.ReceiptStep) _uiState.value = block(current)
    }

    fun printReceipt() {
        val state = _uiState.value as? CloseAndPayUiState.ReceiptStep ?: return
        updateReceipt { it.copy(printState = ActionState.IN_PROGRESS, printError = null) }
        viewModelScope.launch {
            val result = AppContainer.receiptPrinterGateway.printReceipt(state.receipt)
            updateReceipt { current ->
                result.fold(
                    onSuccess = { current.copy(printState = ActionState.SUCCESS) },
                    onFailure = { e -> current.copy(printState = ActionState.FAILED, printError = e.message) },
                )
            }
        }
    }

    fun sendSmsReceipt() {
        val state = _uiState.value as? CloseAndPayUiState.ReceiptStep ?: return
        if (state.phoneNumber.isBlank()) return
        updateReceipt { it.copy(smsState = ActionState.IN_PROGRESS, smsError = null) }
        viewModelScope.launch {
            val result = AppContainer.smsReceiptGateway.sendReceipt(state.receipt, state.phoneNumber)
            updateReceipt { current ->
                result.fold(
                    onSuccess = { current.copy(smsState = ActionState.SUCCESS) },
                    onFailure = { e -> current.copy(smsState = ActionState.FAILED, smsError = e.message) },
                )
            }
        }
    }

    fun sendEmailReceipt() {
        val state = _uiState.value as? CloseAndPayUiState.ReceiptStep ?: return
        if (state.emailAddress.isBlank()) return
        updateReceipt { it.copy(emailState = ActionState.IN_PROGRESS, emailError = null) }
        viewModelScope.launch {
            val result = AppContainer.emailReceiptGateway.sendReceipt(state.receipt, state.emailAddress)
            updateReceipt { current ->
                result.fold(
                    onSuccess = { current.copy(emailState = ActionState.SUCCESS) },
                    onFailure = { e -> current.copy(emailState = ActionState.FAILED, emailError = e.message) },
                )
            }
        }
    }

    fun finishReceiptStep() {
        _uiState.value = CloseAndPayUiState.Done
    }
}
