package au.com.threesixty.cabdispatch.hardware.receipt

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Sends a receipt summary by SMS to the passenger's phone number. A real
 * implementation should call a backend transactional-SMS endpoint (the spec
 * mentions Twilio for the duress SMS fallback, B7 — the receipt SMS likely
 * reuses the same provider account) rather than sending directly from the
 * device: most fleet tablets are Wi-Fi/data-only with no carrier SIM able to
 * send passenger-facing SMS. See [MockSmsReceiptGateway].
 */
interface SmsReceiptGateway {
    suspend fun sendReceipt(receipt: Receipt, phoneNumber: String): Result<Unit>
}

/**
 * *** MOCK / NO-OP *** — logs instead of calling a backend SMS endpoint.
 * No `/v1/receipts/sms`-style route exists yet in
 * [au.com.threesixty.cabdispatch.data.remote.ApiService]; wire this to a real
 * endpoint once the backend agent adds one.
 */
class MockSmsReceiptGateway : SmsReceiptGateway {
    private val tag = "MockSmsReceiptGateway"

    override suspend fun sendReceipt(receipt: Receipt, phoneNumber: String): Result<Unit> {
        Log.i(tag, "Sending SMS receipt (mock) to $phoneNumber for trip ${receipt.tripId}")
        delay(600)
        return Result.success(Unit)
    }
}
