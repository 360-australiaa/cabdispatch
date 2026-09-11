package au.com.threesixty.cabdispatch.domain

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePairingRepositoryTest {
    @Test
    fun transportFailureDoesNotExposeNetworkTopology() {
        val error = SocketTimeoutException(
            "failed to connect to /72.61.107.107 (port 8001) from /192.168.100.12"
        )

        assertEquals(
            "Cannot reach Cab Dispatch. Check the tablet connection and try again.",
            DevicePairingRepository.errorMessage(error),
        )
    }
}
