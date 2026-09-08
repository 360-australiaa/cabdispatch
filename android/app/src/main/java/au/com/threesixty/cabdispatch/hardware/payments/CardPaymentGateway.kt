package au.com.threesixty.cabdispatch.hardware.payments

import au.com.threesixty.cabdispatch.hardware.HardwareGateway
import au.com.threesixty.cabdispatch.hardware.SIMULATED_BANNER
import au.com.threesixty.cabdispatch.hardware.simulationForbiddenInRelease
import kotlinx.coroutines.delay

/**
 * Wraps whatever a real card-present payment SDK integration would expose —
 * in production this is Stripe Terminal Android SDK (Tap to Pay on Android,
 * AU eftpos + PIN, per spec Phase 3 "Stripe Terminal Tap to Pay (AU, eftpos +
 * PIN)"). Kept as a thin interface so S4 (Close & Pay) never touches the
 * Stripe SDK directly — only the implementations in this file, or a future
 * `StripeTerminalCardPaymentGateway`, do.
 *
 * *** NO REAL IMPLEMENTATION EXISTS IN THIS CODEBASE *** — no Stripe Terminal
 * dependency is wired into build.gradle.kts, so no money can move. A5 makes
 * that fact machine-readable rather than a comment: [HardwareGateway.isReal]
 * is `false` for both implementations here, and Close & Pay hides the "TAP TO
 * PAY" card entirely unless it is `true`. The debug-only
 * [SimulatedCardPaymentGateway] marks every result it returns; the release
 * [UnavailableCardPaymentGateway] returns failures and nothing else.
 */
interface CardPaymentGateway : HardwareGateway {

    /** Current reader connection/session state — read by S6 diagnostics and to gate S4's "Tap to Pay" button. */
    val readerState: ReaderState

    /**
     * Discovers and connects a card reader. In a real Stripe Terminal
     * integration this is `Terminal.getInstance().discoverReaders(...)` then
     * `connectReader(...)`; for a tablet's built-in NFC (Tap to Pay on
     * Android) it instead initializes the on-device reader — no external
     * hardware pairing needed. Idempotent: calling again while already
     * connected is a no-op success.
     */
    suspend fun connectReader(): Result<Unit>

    /**
     * Collects a card-present payment for [amountCents] (AUD, integer cents
     * — never a fractional-dollar Double, consistent with the
     * decimal-as-string money convention used everywhere else in this
     * codebase). Suspends for the full tap/insert/PIN UX; the real SDK
     * drives its own full-screen collection UI (spec B5 S4: "Tap to Pay
     * (Stripe full-screen collection UI)"), so callers should expect this to
     * be long-running and to potentially show system UI on top of the app.
     * Offline: per spec B7 "Card: Tap to Pay unavailable offline", a real
     * implementation must fail fast with a recognizable error when there is
     * no connectivity — neither implementation in this file models this.
     */
    suspend fun collectPayment(amountCents: Long): Result<PaymentResult>

    /**
     * Creates a deferred payment link / QR payload for [amountCents] (spec
     * B5 S4 "Payment Link/QR"; B7 "deferred payment link" as the offline
     * fallback for card payment). Distinct from [collectPayment]: this does
     * not block on the customer paying — a real implementation returns
     * immediately with a shareable URL, and payment confirmation arrives
     * later out-of-band (a webhook the backend would relay down to the
     * device — not modelled by either implementation in this file).
     */
    suspend fun createPaymentLink(amountCents: Long): Result<PaymentLinkResult>

    /** Cancels an in-flight [collectPayment] call, if any (e.g. driver-initiated abort). */
    suspend fun cancelCollection()
}

enum class ReaderState { DISCONNECTED, CONNECTING, CONNECTED }

data class PaymentResult(
    val paymentId: String,
    val amountCents: Long,
    val cardBrand: String?,
    val last4: String?,
    val approvalCode: String?,
    /**
     * True when no real card was ever presented and no money moved. Set only by
     * [SimulatedCardPaymentGateway] (debug builds). Callers MUST propagate it to
     * [au.com.threesixty.cabdispatch.hardware.receipt.Receipt.simulated] so the
     * banner and the "TEST RECEIPT" marker reach the screen and the paper.
     */
    val simulated: Boolean = false,
)

data class PaymentLinkResult(
    val url: String,
    /** Raw payload a QR renderer would encode — same as [url] for a simple link-based flow. */
    val qrPayload: String,
    val expiresAt: String?,
    /** See [PaymentResult.simulated] — a simulated link resolves nowhere and collects nothing. */
    val simulated: Boolean = false,
)

/**
 * *** SIMULATION — DEBUG BUILDS ONLY. NO MONEY MOVES. ***
 *
 * Exists so the Close & Pay state machine can be exercised on a desk without a
 * Stripe Terminal reader. It is not a stand-in for the real thing and is
 * deliberately impossible to mistake for one:
 *
 * - [isReal] is `false`, so Close & Pay does not render the "TAP TO PAY" card
 *   at all. Reaching [collectPayment] requires going around the UI.
 * - Every [PaymentResult] it returns carries `simulated = true`, an approval
 *   code of [SIMULATED_BANNER] rather than a plausible six-character code, and
 *   an obviously non-card brand with no PAN digits. The old mock returned
 *   `cardBrand="visa"`, `last4="4242"`, `approvalCode="MOCK00"` — a receipt
 *   printed from that was indistinguishable from a real one at arm's length,
 *   which is the exact defect the audit called a blocker.
 * - Constructing it with [debugBuild] `false` throws [NotImplementedError]
 *   immediately (see [simulationForbiddenInRelease]), so a release build cannot
 *   quietly acquire a fake payment path.
 *
 * @param debugBuild pass `BuildConfig.DEBUG`. Injected rather than read here so
 *   the gating is unit-testable without switching build variant.
 */
class SimulatedCardPaymentGateway(debugBuild: Boolean) : CardPaymentGateway {

    init {
        if (!debugBuild) simulationForbiddenInRelease("Card-present payment (Stripe Terminal)")
    }

    override val isReal: Boolean = false

    override var readerState: ReaderState = ReaderState.DISCONNECTED
        private set

    private var cancelled = false

    override suspend fun connectReader(): Result<Unit> {
        readerState = ReaderState.CONNECTING
        delay(300)
        readerState = ReaderState.CONNECTED
        return Result.success(Unit)
    }

    override suspend fun collectPayment(amountCents: Long): Result<PaymentResult> {
        cancelled = false
        if (readerState != ReaderState.CONNECTED) connectReader()
        delay(1500)
        if (cancelled) return Result.failure(IllegalStateException("Payment collection cancelled"))
        return Result.success(
            PaymentResult(
                paymentId = "SIMULATED-NO-PAYMENT-${System.currentTimeMillis()}",
                amountCents = amountCents,
                cardBrand = SIMULATED_BANNER,
                last4 = null,
                approvalCode = SIMULATED_BANNER,
                simulated = true,
            ),
        )
    }

    override suspend fun createPaymentLink(amountCents: Long): Result<PaymentLinkResult> {
        delay(500)
        return Result.success(
            PaymentLinkResult(
                url = "https://simulated.invalid/no-payment-link",
                qrPayload = "https://simulated.invalid/no-payment-link",
                expiresAt = null,
                simulated = true,
            ),
        )
    }

    override suspend fun cancelCollection() {
        cancelled = true
    }
}

/**
 * The release-build wiring. There is no card reader integration, so this says
 * so: [isReal] is `false` (Close & Pay hides "TAP TO PAY"), and every call
 * returns a failure carrying a message a driver can read. It never fabricates a
 * success and never throws into the driver's face mid-shift — the loud failure
 * for a *developer* is [SimulatedCardPaymentGateway]'s constructor; the quiet,
 * correct failure for a *driver* is here.
 */
class UnavailableCardPaymentGateway : CardPaymentGateway {

    override val isReal: Boolean = false

    override val readerState: ReaderState = ReaderState.DISCONNECTED

    private fun <T> unavailable(): Result<T> = Result.failure(
        UnsupportedOperationException(
            "Card payment is not available on this device — no card reader is installed. " +
                "Take cash, a CabCharge/TTSS docket, or an account/voucher payment.",
        ),
    )

    override suspend fun connectReader(): Result<Unit> = unavailable()

    override suspend fun collectPayment(amountCents: Long): Result<PaymentResult> = unavailable()

    override suspend fun createPaymentLink(amountCents: Long): Result<PaymentLinkResult> = unavailable()

    override suspend fun cancelCollection() = Unit
}

