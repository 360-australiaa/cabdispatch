package au.com.threesixty.cabdispatch.hardware.printing

import android.util.Log
import au.com.threesixty.cabdispatch.hardware.receipt.Receipt
import kotlinx.coroutines.delay

/**
 * Wraps a Bluetooth thermal receipt printer integration (spec B5 S4: "receipt
 * → print (BT thermal)"; S6: "printer pairing screen"). In production this
 * would wrap a vendor SDK (e.g. Sunmi/Epson/Star BT thermal printer ESC/POS
 * library) — none is wired into build.gradle.kts here (no BLUETOOTH*
 * permission is declared in the manifest either), see
 * [MockReceiptPrinterGateway].
 */
interface ReceiptPrinterGateway {

    /** Currently paired printer, if any — read by S6's pairing screen to show connection status. */
    val pairedDevice: PrinterDevice?

    /** Scans for nearby paired/unpaired BT thermal printers. */
    suspend fun discover(): Result<List<PrinterDevice>>

    /** Pairs/connects to a specific discovered printer for subsequent [printReceipt] calls. */
    suspend fun pair(deviceId: String): Result<Unit>

    /** Sends [receipt] to the currently paired printer. Fails if no printer is paired. */
    suspend fun printReceipt(receipt: Receipt): Result<Unit>
}

data class PrinterDevice(
    val id: String,
    val name: String,
)

/**
 * *** MOCK / NO-OP — NOT A REAL PRINTER INTEGRATION ***
 *
 * [discover] returns a couple of synthetic devices; [pair] always succeeds;
 * [printReceipt] just logs the receipt content via [Log.i] instead of
 * emitting ESC/POS bytes over Bluetooth. No BT permissions/APIs are actually
 * exercised. Swap for a real vendor SDK wrapper before field-testing S4/S6.
 */
class MockReceiptPrinterGateway : ReceiptPrinterGateway {

    private val tag = "MockReceiptPrinter"

    override var pairedDevice: PrinterDevice? = null
        private set

    override suspend fun discover(): Result<List<PrinterDevice>> {
        delay(400)
        return Result.success(
            listOf(
                PrinterDevice(id = "mock-printer-1", name = "Sunmi BT-Printer (mock)"),
                PrinterDevice(id = "mock-printer-2", name = "Star SM-L200 (mock)"),
            ),
        )
    }

    override suspend fun pair(deviceId: String): Result<Unit> {
        delay(300)
        pairedDevice = PrinterDevice(id = deviceId, name = "Paired printer ($deviceId)")
        return Result.success(Unit)
    }

    override suspend fun printReceipt(receipt: Receipt): Result<Unit> {
        val device = pairedDevice
            ?: return Result.failure(IllegalStateException("No printer paired"))
        Log.i(tag, "Printing receipt (mock) to ${device.name}: $receipt")
        delay(800)
        return Result.success(Unit)
    }
}
