package au.com.threesixty.cabdispatch.hardware.receipt

import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.ReceiptSmsRequestDto
import au.com.threesixty.cabdispatch.hardware.HardwareGateway

/**
 * Sends a receipt summary by SMS to the passenger's phone number. Sending goes
 * through the backend's transactional-SMS provider rather than the device:
 * most fleet tablets are Wi-Fi/data-only with no carrier SIM able to send
 * passenger-facing SMS.
 *
 * A5: no longer a mock — [ApiSmsReceiptGateway] calls the existing route
 * `POST /v1/trips/{trip_id}/receipt/sms`
 * (`backend/app/api/v1/trips.py::sms_receipt`).
 */
interface SmsReceiptGateway : HardwareGateway {
    suspend fun sendReceipt(receipt: Receipt, phoneNumber: String): Result<Unit>
}

/**
 * Real implementation: `POST /v1/trips/{trip_id}/receipt/sms`. Same two
 * honesty rules as [ApiEmailReceiptGateway] — see that class's doc: the route
 * needs [Receipt.serverTripId] (not the offline `clientUuid`), and the
 * backend's own `mock=true` response (no Twilio credentials configured) is
 * reported as a failure rather than dressed up as a delivery.
 */
class ApiSmsReceiptGateway(
    private val api: () -> ApiService,
) : SmsReceiptGateway {

    override val isReal: Boolean = true

    override suspend fun sendReceipt(receipt: Receipt, phoneNumber: String): Result<Unit> {
        val serverId = receipt.serverTripId
            ?: return Result.failure(
                IllegalStateException(
                    "This trip has not synced to the server yet — SMS receipt is unavailable " +
                        "until it has. Resend from Trip Detail once online.",
                ),
            )
        return runCatching {
            api().smsReceipt(serverId, ReceiptSmsRequestDto(toPhone = phoneNumber))
        }.mapCatching { response ->
            if (response.mock) {
                throw IllegalStateException(
                    "Server has no SMS provider configured — nothing was sent to $phoneNumber.",
                )
            }
            Unit
        }
    }
}
