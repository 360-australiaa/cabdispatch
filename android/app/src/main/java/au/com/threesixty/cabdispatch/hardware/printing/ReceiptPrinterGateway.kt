package au.com.threesixty.cabdispatch.hardware.printing

import au.com.threesixty.cabdispatch.hardware.HardwareGateway
import au.com.threesixty.cabdispatch.hardware.TEST_RECEIPT_MARKER
import au.com.threesixty.cabdispatch.hardware.receipt.Receipt
import au.com.threesixty.cabdispatch.hardware.simulationForbiddenInRelease
import kotlinx.coroutines.delay

/**
 * Wraps a Bluetooth thermal receipt printer integration (spec B5 S4: "receipt
 * → print (BT thermal)"; S6: "printer pairing screen"). In production this
 * would wrap a vendor SDK (e.g. Sunmi/Epson/Star BT thermal printer ESC/POS
 * library). None is wired into build.gradle.kts here and no BLUETOOTH*
 * permission is declared in the manifest, so no implementation in this
 * codebase can reach a physical printer. A5 makes that machine-readable:
 * [HardwareGateway.isReal] is `false` for both implementations below, and
 * Close & Pay hides the "Print" action unless it is `true`.
 */
interface ReceiptPrinterGateway : HardwareGateway {

    /** Currently paired printer, if any — read by S6's pairing screen to show connection status. */
    val pairedDevice: PrinterDevice?

    /** Scans for nearby paired/unpaired BT thermal printers. */
    suspend fun discover(): Result<List<PrinterDevice>>

    /** Pairs/connects to a specific discovered printer (the exact [PrinterDevice] handed back by
     * [discover], not just its id) for subsequent [printReceipt] calls — see [pairedDevice]'s doc:
     * the paired-status UI reads [PrinterDevice.name] straight off whatever this sets, so a real
     * discovered device's name must survive the pair, not be replaced with a synthesized one. */
    suspend fun pair(device: PrinterDevice): Result<Unit>

    /** Sends [receipt] to the currently paired printer. Fails if no printer is paired. */
    suspend fun printReceipt(receipt: Receipt): Result<Unit>
}

data class PrinterDevice(
    val id: String,
    val name: String,
)

/**
 * *** SIMULATION — DEBUG BUILDS ONLY. NOTHING IS PRINTED. ***
 *
 * Lets the S6 pairing screen and the S4 receipt step be driven on a desk with
 * no Bluetooth printer present. [isReal] is `false`, so Close & Pay does not
 * offer the Print action at all.
 *
 * The two honesty rules it obeys:
 *
 * - Discovered devices are named so nobody can confuse them with hardware in
 *   the room — no plausible "Sunmi BT-Printer" / "Star SM-L200" entries, which
 *   is what the previous mock offered.
 * - [printReceipt] **refuses any receipt not carrying
 *   [au.com.threesixty.cabdispatch.hardware.receipt.Receipt.simulated]**. A
 *   real, genuinely-paid trip must never be "printed" by a gateway that prints
 *   nothing and then reports success — that is precisely how a passenger ends
 *   up holding a receipt for a transaction the operator has no record of.
 *   Callers are additionally required to stamp [TEST_RECEIPT_MARKER] into the
 *   rendered body (see `CloseAndPayViewModel.buildReceipt`).
 *
 * Constructing it outside a debug build throws [NotImplementedError].
 *
 * @param debugBuild pass `BuildConfig.DEBUG` — injected so the gating is
 *   unit-testable without switching build variant.
 */
class SimulatedReceiptPrinterGateway(debugBuild: Boolean) : ReceiptPrinterGateway {

    init {
        if (!debugBuild) simulationForbiddenInRelease("Bluetooth thermal receipt printing")
    }

    override val isReal: Boolean = false

    override var pairedDevice: PrinterDevice? = null
        private set

    override suspend fun discover(): Result<List<PrinterDevice>> {
        delay(400)
        return Result.success(
            listOf(
                PrinterDevice(id = "simulated-printer-1", name = "SIMULATED printer — prints nothing"),
                PrinterDevice(id = "simulated-printer-2", name = "SIMULATED printer 2 — prints nothing"),
            ),
        )
    }

    override suspend fun pair(device: PrinterDevice): Result<Unit> {
        delay(300)
        // Echo the device actually picked — the paired-status tile in S6 reads this name
        // straight back, and a synthesized one there would misreport which entry was chosen.
        pairedDevice = device
        return Result.success(Unit)
    }

    override suspend fun printReceipt(receipt: Receipt): Result<Unit> {
        pairedDevice ?: return Result.failure(IllegalStateException("No printer paired"))
        if (!receipt.simulated) {
            return Result.failure(
                IllegalStateException(
                    "Refusing to simulate printing a real receipt (trip ${receipt.tripId}). " +
                        "No printer hardware is connected and nothing would be printed.",
                ),
            )
        }
        delay(800)
        return Result.success(Unit)
    }
}

/**
 * The release-build wiring: no printer integration exists, so [isReal] is
 * `false` (Close & Pay hides Print) and every call fails with a message a
 * driver can act on. Nothing is ever reported as printed.
 */
class UnavailableReceiptPrinterGateway : ReceiptPrinterGateway {

    override val isReal: Boolean = false

    override val pairedDevice: PrinterDevice? = null

    private fun <T> unavailable(): Result<T> = Result.failure(
        UnsupportedOperationException(
            "Receipt printing is not available on this device — no printer is connected. " +
                "Offer the passenger an emailed or SMS receipt instead.",
        ),
    )

    override suspend fun discover(): Result<List<PrinterDevice>> = unavailable()

    override suspend fun pair(device: PrinterDevice): Result<Unit> = unavailable()

    override suspend fun printReceipt(receipt: Receipt): Result<Unit> = unavailable()
}

