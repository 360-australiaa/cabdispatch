package au.com.threesixty.cabdispatch.hardware.payments

import kotlinx.coroutines.delay

/**
 * Wraps whatever a real card-present payment SDK integration would expose —
 * in production this is Stripe Terminal Android SDK (Tap to Pay on Android,
 * AU eftpos + PIN, per spec Phase 3 "Stripe Terminal Tap to Pay (AU, eftpos +
 * PIN)"). Kept as a thin interface so S4 (Close & Pay) never touches the
 * Stripe SDK directly — only [MockCardPaymentGateway] (this file) or a future
 * `StripeTerminalCardPaymentGateway` do.
 *
 * *** MOCK ONLY in this codebase *** — see [MockCardPaymentGateway] doc. No
 * real Stripe Terminal dependency is wired into build.gradle.kts; do not
 * treat a successful [collectPayment]/[createPaymentLink] result as evidence
 * of a working payment integration. See android/README.md "Real vs mocked".
 */
interface CardPaymentGateway {

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
     * no connectivity — the mock does not model this, see its doc.
     */
    suspend fun collectPayment(amountCents: Long): Result<PaymentResult>

    /**
     * Creates a deferred payment link / QR payload for [amountCents] (spec
     * B5 S4 "Payment Link/QR"; B7 "deferred payment link" as the offline
     * fallback for card payment). Distinct from [collectPayment]: this does
     * not block on the customer paying — a real implementation returns
     * immediately with a shareable URL, and payment confirmation arrives
     * later out-of-band (a webhook the backend would relay down to the
     * device — not modelled here, see [MockCardPaymentGateway]).
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
)

data class PaymentLinkResult(
    val url: String,
    /** Raw payload a QR renderer would encode — same as [url] for a simple link-based flow. */
    val qrPayload: String,
    val expiresAt: String?,
)

/**
 * *** MOCK / NO-OP — NOT A REAL PAYMENT INTEGRATION ***
 *
 * Simulates a healthy Stripe Terminal session: [connectReader] "connects"
 * quickly, [collectPayment] delays 1.5s (approximating a real tap+PIN
 * interaction) then returns a synthetic success, [createPaymentLink] returns
 * a fake `https://pay.example.invalid/...` URL that resolves nowhere. There
 * is no real card reader, no Stripe API call, no webhook, no money moves.
 * This exists purely so S4's UI and state machine can be built, demoed, and
 * tested end-to-end before a real Stripe Terminal SDK integration lands
 * (Phase 3 per spec Part C).
 *
 * Also does not model offline behaviour (spec B7: Tap to Pay should fail
 * fast when offline) — a real implementation must check connectivity before
 * attempting [collectPayment].
 *
 * DO NOT wire this into a release build path without replacing it — grep for
 * `MockCardPaymentGateway` before shipping.
 */
class MockCardPaymentGateway : CardPaymentGateway {

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
        if (readerState != ReaderState.CONNECTED) {
            connectReader()
        }
        delay(1500)
        if (cancelled) {
            return Result.failure(IllegalStateException("Payment collection cancelled"))
        }
        return Result.success(
            PaymentResult(
                paymentId = "mock_pi_${System.currentTimeMillis()}",
                amountCents = amountCents,
                cardBrand = "visa",
                last4 = "4242",
                approvalCode = "MOCK00",
            ),
        )
    }

    override suspend fun createPaymentLink(amountCents: Long): Result<PaymentLinkResult> {
        delay(500)
        val id = "mock_link_${System.currentTimeMillis()}"
        return Result.success(
            PaymentLinkResult(
                url = "https://pay.example.invalid/$id",
                qrPayload = "https://pay.example.invalid/$id",
                expiresAt = null,
            ),
        )
    }

    override suspend fun cancelCollection() {
        cancelled = true
    }
}
