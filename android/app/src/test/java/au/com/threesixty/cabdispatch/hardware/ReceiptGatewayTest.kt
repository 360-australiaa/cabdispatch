package au.com.threesixty.cabdispatch.hardware

import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.ReceiptEmailRequestDto
import au.com.threesixty.cabdispatch.data.remote.ReceiptEmailResponseDto
import au.com.threesixty.cabdispatch.data.remote.ReceiptSmsRequestDto
import au.com.threesixty.cabdispatch.data.remote.ReceiptSmsResponseDto
import au.com.threesixty.cabdispatch.hardware.receipt.ApiEmailReceiptGateway
import au.com.threesixty.cabdispatch.hardware.receipt.ApiSmsReceiptGateway
import au.com.threesixty.cabdispatch.hardware.receipt.Receipt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * A5 · Hardware honesty — the SMS and email receipt gateways, which are the two
 * capabilities this workstream made genuinely real. They now post to the routes
 * the backend already serves (`POST /v1/trips/{id}/receipt/{email,sms}`), so
 * these tests cover the parts that could still quietly lie:
 *
 * - the request must be keyed by the **server** trip id, not the offline
 *   `clientUuid` the app keys trips by locally;
 * - an unsynced trip must fail with a message saying so, not 404 obscurely;
 * - the backend's own `mock=true` answer (no SendGrid/Twilio credentials
 *   configured server-side) must surface as a failure, not as a delivery.
 */
class ReceiptGatewayTest {

    private fun receipt(serverTripId: String?) = Receipt(
        tripId = "client-uuid-1",
        serverTripId = serverTripId,
        vehicleId = "V1",
        driverId = "D1",
        startedAt = "",
        closedAt = "",
        fareLines = emptyList(),
        subtotal = "$20.00",
        surcharge = "$0.00",
        total = "$20.00",
        gstComponent = "$1.82",
        paymentMethod = "Cash",
        receiptRef = "RCPT-1",
    )

    private class FakeApi(
        private val mock: Boolean,
        private val throwing: Throwable? = null,
    ) : ApiService by unsupportedApiService() {
        var emailTripId: String? = null
        var emailBody: ReceiptEmailRequestDto? = null
        var smsTripId: String? = null
        var smsBody: ReceiptSmsRequestDto? = null

        override suspend fun emailReceipt(
            tripId: String,
            body: ReceiptEmailRequestDto,
        ): ReceiptEmailResponseDto {
            throwing?.let { throw it }
            emailTripId = tripId
            emailBody = body
            return ReceiptEmailResponseDto(mock = mock, pdfRelativePath = "receipts/x.pdf")
        }

        override suspend fun smsReceipt(
            tripId: String,
            body: ReceiptSmsRequestDto,
        ): ReceiptSmsResponseDto {
            throwing?.let { throw it }
            smsTripId = tripId
            smsBody = body
            return ReceiptSmsResponseDto(mock = mock, pdfRelativePath = "receipts/x.pdf")
        }
    }

    @Test
    fun `email gateway reports itself as real`() {
        assertTrue(ApiEmailReceiptGateway { unsupportedApiService() }.isReal)
        assertTrue(ApiSmsReceiptGateway { unsupportedApiService() }.isReal)
    }

    @Test
    fun `email posts the server trip id, not the client uuid`() = runTest {
        val api = FakeApi(mock = false)
        val result = ApiEmailReceiptGateway { api }
            .sendReceipt(receipt(serverTripId = "srv-77"), "rider@example.com")

        assertTrue(result.isSuccess)
        assertEquals("srv-77", api.emailTripId)
        assertEquals("rider@example.com", api.emailBody?.toEmail)
    }

    @Test
    fun `sms posts the server trip id, not the client uuid`() = runTest {
        val api = FakeApi(mock = false)
        val result = ApiSmsReceiptGateway { api }
            .sendReceipt(receipt(serverTripId = "srv-77"), "0400111222")

        assertTrue(result.isSuccess)
        assertEquals("srv-77", api.smsTripId)
        assertEquals("0400111222", api.smsBody?.toPhone)
    }

    @Test
    fun `an unsynced trip fails with a message that says so, and never calls the API`() = runTest {
        val api = FakeApi(mock = false)

        val email = ApiEmailReceiptGateway { api }
            .sendReceipt(receipt(serverTripId = null), "rider@example.com")
        val sms = ApiSmsReceiptGateway { api }
            .sendReceipt(receipt(serverTripId = null), "0400111222")

        assertTrue(email.isFailure)
        assertTrue(sms.isFailure)
        assertTrue(email.exceptionOrNull()?.message.orEmpty().contains("has not synced"))
        assertTrue(sms.exceptionOrNull()?.message.orEmpty().contains("has not synced"))
        assertEquals(null, api.emailTripId)
        assertEquals(null, api.smsTripId)
    }

    @Test
    fun `a server-side mock response is a failure, not a delivery`() = runTest {
        val api = FakeApi(mock = true)

        val email = ApiEmailReceiptGateway { api }
            .sendReceipt(receipt("srv-1"), "rider@example.com")
        val sms = ApiSmsReceiptGateway { api }.sendReceipt(receipt("srv-1"), "0400111222")

        assertTrue("mock=true must not read as sent", email.isFailure)
        assertTrue("mock=true must not read as sent", sms.isFailure)
        assertTrue(email.exceptionOrNull()?.message.orEmpty().contains("no email provider"))
        assertTrue(sms.exceptionOrNull()?.message.orEmpty().contains("no SMS provider"))
    }

    @Test
    fun `a transport failure surfaces as a failed Result rather than throwing`() = runTest {
        val api = FakeApi(mock = false, throwing = RuntimeException("timeout"))

        assertTrue(
            ApiEmailReceiptGateway { api }.sendReceipt(receipt("srv-1"), "r@example.com").isFailure,
        )
        assertTrue(
            ApiSmsReceiptGateway { api }.sendReceipt(receipt("srv-1"), "0400111222").isFailure,
        )
    }

    private companion object {
        /** Same pattern as `DriverAuthRepositoryTest` — every un-stubbed route throws. */
        fun unsupportedApiService(): ApiService = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException("ApiService.${method.name} not exercised by this test")
        } as ApiService
    }
}
