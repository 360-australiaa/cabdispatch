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
    /**
     * The server-assigned trip id, once `/v1/trips/sync` (or an online close) has
     * assigned one — `TripEntity.serverId`. Distinct from [tripId], which is the
     * on-device `clientUuid` this offline-first app keys trips by and which the
     * backend's `/v1/trips/{trip_id}/receipt/...` routes know nothing about.
     * `null` while the trip is still unsynced; the SMS/email gateways refuse (with
     * a message that says so) rather than posting an id the server cannot resolve.
     */
    val serverTripId: String? = null,
    /**
     * True when any part of this transaction ran through a simulated gateway
     * (debug builds only — see
     * [au.com.threesixty.cabdispatch.hardware.HardwareGateway]). When set, the
     * rendered receipt MUST carry
     * [au.com.threesixty.cabdispatch.hardware.TEST_RECEIPT_MARKER] and the
     * confirmation MUST carry
     * [au.com.threesixty.cabdispatch.hardware.SIMULATED_BANNER]. A passenger must
     * never be handed something that reads like a real receipt for a payment that
     * did not occur.
     */
    val simulated: Boolean = false,
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
