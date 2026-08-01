package au.com.threesixty.cabdispatch.hardware.receipt

/**
 * Plain receipt payload shared by all three delivery gateways — print
 * ([au.com.threesixty.cabdispatch.hardware.printing.ReceiptPrinterGateway]),
 * SMS ([SmsReceiptGateway]) and email ([EmailReceiptGateway]) — spec B5 S4:
 * "receipt → print (BT thermal), SMS, email PDF (branded)". Built by the S4
 * screen from a closed trip's [au.com.threesixty.cabdispatch.domain.fare.FareBreakdown]
 * once [au.com.threesixty.cabdispatch.data.local.entity.TripEntity] is
 * updated to CLOSED. Deliberately has no Android/print-SDK/PDF types so it
 * stays trivially unit-testable and gateway-agnostic.
 */
data class Receipt(
    val tripId: String,
    val vehicleId: String,
    val driverId: String,
    val startedAt: String,
    val closedAt: String,
    val fareLines: List<ReceiptLine>,
    val subtotal: String,
    val surcharge: String,
    val total: String,
    val gstComponent: String,
    val paymentMethod: String,
    val receiptRef: String?,
)

data class ReceiptLine(val label: String, val amount: String)
