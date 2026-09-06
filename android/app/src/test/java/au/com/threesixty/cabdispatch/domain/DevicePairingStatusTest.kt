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
}
