package au.com.threesixty.cabdispatch.hardware.receipt

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Emails a branded PDF receipt to the passenger (spec B5 S4: "email PDF
 * (branded)"). PDF rendering + branding is inherently a backend concern (the
 * receipt template lives with the dashboard's branding assets, not on the
 * meter), so a real implementation should POST the [Receipt] to a backend
 * endpoint that renders + sends it, not build a PDF on-device. See
 * [MockEmailReceiptGateway].
 */
interface EmailReceiptGateway {
    suspend fun sendReceipt(receipt: Receipt, emailAddress: String): Result<Unit>
}

/**
 * *** MOCK / NO-OP *** — logs instead of calling a backend email endpoint.
 * No `/v1/receipts/email`-style route exists yet in
 * [au.com.threesixty.cabdispatch.data.remote.ApiService]; wire this to a real
 * endpoint (and a PDF template) once the backend/dashboard branding agent
 * adds one.
 */
class MockEmailReceiptGateway : EmailReceiptGateway {
    private val tag = "MockEmailReceiptGateway"

    override suspend fun sendReceipt(receipt: Receipt, emailAddress: String): Result<Unit> {
        Log.i(tag, "Sending email receipt (mock) to $emailAddress for trip ${receipt.tripId}")
        delay(600)
        return Result.success(Unit)
    }
}
