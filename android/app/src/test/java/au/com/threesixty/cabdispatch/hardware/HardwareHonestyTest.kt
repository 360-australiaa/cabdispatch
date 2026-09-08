package au.com.threesixty.cabdispatch.hardware

import au.com.threesixty.cabdispatch.hardware.payments.SimulatedCardPaymentGateway
import au.com.threesixty.cabdispatch.hardware.payments.UnavailableCardPaymentGateway
import au.com.threesixty.cabdispatch.hardware.printing.PrinterDevice
import au.com.threesixty.cabdispatch.hardware.printing.SimulatedReceiptPrinterGateway
import au.com.threesixty.cabdispatch.hardware.printing.UnavailableReceiptPrinterGateway
import au.com.threesixty.cabdispatch.hardware.receipt.Receipt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A5 · Hardware honesty (architecture audit §5). These tests exist to stop the
 * exact regression the audit found: a mock payment/print gateway wired into the
 * only build path, returning a synthetic success a driver could not tell from a
 * real one.
 *
 * Three properties are locked down here:
 *
 * 1. **Availability gating** — no gateway without real hardware behind it may
 *    report `isReal = true`. Close & Pay renders "TAP TO PAY" and "PRINT" only
 *    on that flag, so this is what keeps the un-pressable buttons off screen.
 * 2. **Debug/release branching** — a simulated gateway cannot be constructed
 *    outside a debug build, and the release stand-ins can only fail.
 * 3. **Nothing simulated may look real** — no plausible card brand, PAN
 *    fragment or approval code, and no simulated print of a real receipt.
 */
class HardwareHonestyTest {

    private fun receipt(simulated: Boolean, serverTripId: String? = "srv-1") = Receipt(
        tripId = "client-uuid-1",
        serverTripId = serverTripId,
        simulated = simulated,
        vehicleId = "V1",
        driverId = "D1",
        startedAt = "Mon 7 Sep 2026, 9:15 pm",
        closedAt = "Mon 7 Sep 2026, 9:31 pm",
        fareLines = emptyList(),
        subtotal = "$20.00",
        surcharge = "$0.00",
        total = "$20.00",
        gstComponent = "$1.82",
        paymentMethod = "Cash",
        receiptRef = "RCPT-1",
    )

    // --- 1. Availability gating -------------------------------------------------

    @Test
    fun `no card payment gateway in this build reports itself as real`() {
        assertFalse(SimulatedCardPaymentGateway(debugBuild = true).isReal)
        assertFalse(UnavailableCardPaymentGateway().isReal)
    }

    @Test
    fun `no printer gateway in this build reports itself as real`() {
        assertFalse(SimulatedReceiptPrinterGateway(debugBuild = true).isReal)
        assertFalse(UnavailableReceiptPrinterGateway().isReal)
    }

    // --- 2. Debug / release branching -------------------------------------------

    @Test
    fun `simulated card gateway refuses to exist in a non-debug build`() {
        val error = runCatching { SimulatedCardPaymentGateway(debugBuild = false) }.exceptionOrNull()
        assertTrue(
            "expected NotImplementedError, got $error",
            error is NotImplementedError,
        )
    }

    @Test
    fun `simulated printer gateway refuses to exist in a non-debug build`() {
        val error = runCatching { SimulatedReceiptPrinterGateway(debugBuild = false) }.exceptionOrNull()
        assertTrue(
            "expected NotImplementedError, got $error",
            error is NotImplementedError,
        )
    }

    @Test
    fun `release card gateway fails every call instead of fabricating a payment`() = runTest {
        val gateway = UnavailableCardPaymentGateway()
        assertTrue(gateway.connectReader().isFailure)
        assertTrue(gateway.collectPayment(2000).isFailure)
        assertTrue(gateway.createPaymentLink(2000).isFailure)
    }

    @Test
    fun `release printer gateway never reports a receipt as printed`() = runTest {
        val gateway = UnavailableReceiptPrinterGateway()
        assertNull(gateway.pairedDevice)
        assertTrue(gateway.discover().isFailure)
        assertTrue(gateway.printReceipt(receipt(simulated = false)).isFailure)
    }

    // --- 3. Nothing simulated may look real -------------------------------------

    @Test
    fun `simulated payment result is marked and carries no plausible card details`() = runTest {
        val result = SimulatedCardPaymentGateway(debugBuild = true).collectPayment(4250).getOrThrow()

        assertTrue("simulated flag must be set", result.simulated)
        assertEquals(4250, result.amountCents)
        // The old mock returned visa / 4242 / MOCK00 — indistinguishable from a real
        // approval on a printed docket at arm's length.
        assertNull("must not fabricate PAN digits", result.last4)
        assertEquals(SIMULATED_BANNER, result.cardBrand)
        assertEquals(SIMULATED_BANNER, result.approvalCode)
        assertTrue(result.paymentId.startsWith("SIMULATED-"))
    }

    @Test
    fun `simulated payment link is marked and resolves nowhere`() = runTest {
        val link = SimulatedCardPaymentGateway(debugBuild = true).createPaymentLink(1000).getOrThrow()
        assertTrue(link.simulated)
        assertTrue(link.url.contains("simulated.invalid"))
    }

    @Test
    fun `simulated printer refuses to print a real receipt`() = runTest {
        val gateway = SimulatedReceiptPrinterGateway(debugBuild = true)
        val device = gateway.discover().getOrThrow().first()
        gateway.pair(device).getOrThrow()

        // A genuinely-paid trip must never be "printed" by a gateway that prints nothing
        // and then reports success — that is how a passenger ends up holding a receipt
        // for a transaction with no paper behind it.
        val real = gateway.printReceipt(receipt(simulated = false))
        assertTrue(real.isFailure)

        assertTrue(gateway.printReceipt(receipt(simulated = true)).isSuccess)
    }

    @Test
    fun `simulated printer offers no device name that could pass for hardware`() = runTest {
        val devices = SimulatedReceiptPrinterGateway(debugBuild = true).discover().getOrThrow()
        assertTrue(devices.isNotEmpty())
        devices.forEach { device ->
            assertTrue(
                "device name must announce itself as simulated: ${device.name}",
                device.name.contains("SIMULATED"),
            )
        }
    }

    @Test
    fun `simulated printer will not print without a paired device`() = runTest {
        val gateway = SimulatedReceiptPrinterGateway(debugBuild = true)
        assertTrue(gateway.printReceipt(receipt(simulated = true)).isFailure)
        gateway.pair(PrinterDevice("simulated-printer-1", "SIMULATED printer — prints nothing"))
        assertNotNull(gateway.pairedDevice)
    }

    // --- Receipt defaults --------------------------------------------------------

    @Test
    fun `a receipt is not simulated and has no server id unless explicitly given one`() {
        val r = Receipt(
            tripId = "c1",
            vehicleId = "V1",
            driverId = "D1",
            startedAt = "",
            closedAt = "",
            fareLines = emptyList(),
            subtotal = "$0.00",
            surcharge = "$0.00",
            total = "$0.00",
            gstComponent = "$0.00",
            paymentMethod = "Cash",
            receiptRef = null,
        )
        assertFalse(r.simulated)
        assertNull(r.serverTripId)
    }
}
