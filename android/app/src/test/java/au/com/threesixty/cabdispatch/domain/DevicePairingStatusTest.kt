package au.com.threesixty.cabdispatch.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for [DevicePairingStatus] — the pure "is this tablet unpaired" derivation behind
 * [au.com.threesixty.cabdispatch.ui.overlays.DeviceUnpairedBanner] and the "Bind to vehicle"
 * onboarding advisory. Same shape as `DriverEngagementFormatTest`: no Android framework classes on
 * the classpath.
 */
class DevicePairingStatusTest {

    @Test
    fun `null device id is unpaired`() {
        assertTrue(DevicePairingStatus.isUnpaired(null))
    }

    @Test
    fun `blank device id is treated as unpaired defensively`() {
        assertTrue(DevicePairingStatus.isUnpaired(""))
        assertTrue(DevicePairingStatus.isUnpaired("   "))
    }

    @Test
    fun `a real server-assigned id is paired`() {
        assertFalse(DevicePairingStatus.isUnpaired("dev_01HXYZ"))
    }

    // --- a tablet the server has disowned ----------------------------------------------------

    @Test
    fun `a device the server rejects is unpaired even though it holds a real id`() {
        // The pilot tablet, 2026-09-07: a genuine server-assigned id, and a heartbeat 404'ing for
        // hours because a fleet wipe had removed the record. The plain check called it paired, so
        // nothing prompted a re-pair and every remote command had nowhere to land.
        assertTrue(DevicePairingStatus.isUnpaired("a3015bd2-3925-46da-b0a8-0ffc4003bd7c", deviceRejected = true))
    }

    @Test
    fun `a real id the server still accepts stays paired`() {
        assertFalse(DevicePairingStatus.isUnpaired("a3015bd2-3925-46da-b0a8-0ffc4003bd7c", deviceRejected = false))
    }

    @Test
    fun `being offline does not make a paired device look unpaired`() {
        // The distinction the whole change rests on: `deviceRejected` comes only from a 404, never
        // from a failed request. An unreachable server must not push a tablet into a re-pair
        // prompt it does not need.
        assertFalse(DevicePairingStatus.isUnpaired("real-id", deviceRejected = false))
    }

    @Test
    fun `no id at all is unpaired regardless of rejection`() {
        assertTrue(DevicePairingStatus.isUnpaired(null, deviceRejected = false))
        assertTrue(DevicePairingStatus.isUnpaired("", deviceRejected = true))
    }
}
