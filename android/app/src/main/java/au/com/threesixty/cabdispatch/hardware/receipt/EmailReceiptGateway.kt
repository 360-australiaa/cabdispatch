package au.com.threesixty.cabdispatch.hardware.receipt

import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.ReceiptEmailRequestDto
import au.com.threesixty.cabdispatch.hardware.HardwareGateway

/**
 * Emails a branded PDF receipt to the passenger (spec B5 S4: "email PDF
 * (branded)"). PDF rendering + branding is a backend concern — the receipt
 * template lives with the dashboard's branding assets, not on the meter — so
 * this posts the closed trip to the backend, which renders and sends it.
 *
 * A5: this is no longer a mock. [ApiEmailReceiptGateway] calls the route that
 * already exists, `POST /v1/trips/{trip_id}/receipt/email`
 * (`backend/app/api/v1/trips.py::email_receipt`).
 */
interface EmailReceiptGateway : HardwareGateway {
    suspend fun sendReceipt(receipt: Receipt, emailAddress: String): Result<Unit>
}

/**
 * Real implementation: `POST /v1/trips/{trip_id}/receipt/email`.
 *
 * Two honesty rules are enforced here rather than left to the caller.
 *
 * 1. **The server route is keyed by the server trip id, not the on-device
 *    [Receipt.tripId]** (which is the offline-first `clientUuid`). A trip
 *    closed while offline has no [Receipt.serverTripId] until the sync worker
 *    has run, and posting the client uuid to that path just 404s. So a
 *    not-yet-synced trip fails with a message that says exactly that, instead
 *    of a generic network error the driver cannot act on.
 *
 * 2. **The backend itself answers `mock=true` when no SendGrid key is
 *    configured** (`ReceiptEmailResponse`'s own doc: `would_send_to` is
 *    populated only on the mock path). Reporting that as success would put
 *    this gateway right back in the business of fabricating deliveries, one
 *    layer further down. It is surfaced as a failure with the reason.
 */
class ApiEmailReceiptGateway(
    private val api: () -> ApiService,
) : EmailReceiptGateway {

    override val isReal: Boolean = true

    override suspend fun sendReceipt(receipt: Receipt, emailAddress: String): Result<Unit> {
        val serverId = receipt.serverTripId
            ?: return Result.failure(
                IllegalStateException(
                    "This trip has not synced to the server yet — email receipt is unavailable " +
                        "until it has. Print or SMS once online, or resend from Trip Detail.",
                ),
            )
        return runCatching {
            api().emailReceipt(serverId, ReceiptEmailRequestDto(toEmail = emailAddress))
        }.mapCatching { response ->
            if (response.mock) {
                throw IllegalStateException(
                    "Server has no email provider configured — nothing was sent to $emailAddress.",
                )
            }
            Unit
        }
    }
}
