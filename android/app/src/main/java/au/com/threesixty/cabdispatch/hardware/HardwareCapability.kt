package au.com.threesixty.cabdispatch.hardware

/**
 * A5 · Hardware honesty (architecture audit §5, program plan §1 rule 11).
 *
 * Every hardware gateway in this package declares, at runtime, whether it is
 * backed by something real. This is not documentation — [isReal] is read by
 * the UI: Close & Pay hides "TAP TO PAY" and "PRINT" unless the corresponding
 * gateway reports `true`. An absent button is honest; a button that fabricates
 * a success when pressed is not.
 *
 * The rule this exists to enforce: **no simulated gateway may produce a result
 * that reads like a real one.** A driver must never be able to hand a
 * passenger a receipt for a payment that did not happen. Where a capability
 * cannot honestly be implemented in this codebase (card-present payment needs
 * Stripe Terminal hardware; printing needs a physical Bluetooth thermal
 * printer), the simulated stand-in exists only in a debug build, marks every
 * result it produces with [SIMULATED_BANNER], and stamps [TEST_RECEIPT_MARKER]
 * into the receipt body itself. In a non-debug build it is not constructed at
 * all — see [au.com.threesixty.cabdispatch.data.AppContainer] — and
 * constructing one anyway throws [NotImplementedError] rather than quietly
 * behaving like the real thing.
 */
interface HardwareGateway {
    /**
     * `true` only when this implementation talks to real hardware or a real
     * backend endpoint. Simulated and unavailable stand-ins return `false`,
     * and the UI must gate their entry points on this.
     */
    val isReal: Boolean
}

/** The exact banner text shown on every simulated payment/print result. Asserted by unit tests. */
const val SIMULATED_BANNER: String = "SIMULATED — no money moved"

/** Stamped into the receipt body whenever any part of the transaction was simulated. */
const val TEST_RECEIPT_MARKER: String = "TEST RECEIPT"

/**
 * Thrown by the simulated gateways if one is somehow constructed outside a
 * debug build. Loud by design: a release build that reaches this has wired a
 * fake payment path into a shipping app, which is the exact failure the audit
 * flagged (`CardPaymentGateway.kt`'s own "DO NOT wire this into a release
 * build path" comment, against a mock that was wired into the only build path).
 */
fun simulationForbiddenInRelease(what: String): Nothing =
    throw NotImplementedError(
        "$what has no real implementation in this build. The simulated stand-in is " +
            "debug-only and must never run in a release build — wire a real gateway or " +
            "leave the capability reporting isReal=false.",
    )
