package au.com.threesixty.cabdispatch.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldSkipDeviceHeartbeatPoll] — the pure "skip this tick's POST" decision behind
 * [DeviceCommandHeartbeat.pollOnce] (W4 task 4, 2026-09-12 optimisation plan). Plain Kotlin, no
 * clock/Android dependency, so every millisecond in these tests is an explicit literal rather
 * than a real sleep.
 */
class DeviceCommandHeartbeatSkipTest {

    @Test
    fun `the very first ever poll (lastPostAtMs 0) is never skipped`() {
        assertFalse(
            shouldSkipDeviceHeartbeatPoll(now = 999_999L, lastPostAtMs = 0L, lastChangedAtMs = 0L, screenOn = false),
        )
    }

    @Test
    fun `screen on is never skipped, regardless of how stable battery-network has been`() {
        assertFalse(
            shouldSkipDeviceHeartbeatPoll(
                now = 1_000_000L,
                lastPostAtMs = 900_000L,
                lastChangedAtMs = 0L, // stable for a very long time
                screenOn = true,
            ),
        )
    }

    @Test
    fun `screen off but not yet 5 minutes stable is not skipped`() {
        val now = 1_000_000L
        assertFalse(
            shouldSkipDeviceHeartbeatPoll(
                now = now,
                lastPostAtMs = now - 60_000L,
                lastChangedAtMs = now - (DEVICE_HEARTBEAT_IDLE_SKIP_THRESHOLD_MS - 1),
                screenOn = false,
            ),
        )
    }

    @Test
    fun `screen off and stable for exactly 5 minutes is skipped`() {
        val now = 1_000_000L
        assertTrue(
            shouldSkipDeviceHeartbeatPoll(
                now = now,
                lastPostAtMs = now - 60_000L,
                lastChangedAtMs = now - DEVICE_HEARTBEAT_IDLE_SKIP_THRESHOLD_MS,
                screenOn = false,
            ),
        )
    }

    @Test
    fun `the 8-minute safety cap forces a real post even if still idle and screen off`() {
        val now = 1_000_000L
        assertTrue(
            "sanity: this WOULD be skipped without the safety cap",
            shouldSkipDeviceHeartbeatPoll(
                now = now,
                lastPostAtMs = now - (DEVICE_HEARTBEAT_MAX_SILENCE_MS - 1),
                lastChangedAtMs = now - DEVICE_HEARTBEAT_IDLE_SKIP_THRESHOLD_MS,
                screenOn = false,
            ),
        )
        assertFalse(
            "the safety cap must force a real POST at/after 8 minutes of total silence",
            shouldSkipDeviceHeartbeatPoll(
                now = now,
                lastPostAtMs = now - DEVICE_HEARTBEAT_MAX_SILENCE_MS,
                lastChangedAtMs = now - DEVICE_HEARTBEAT_IDLE_SKIP_THRESHOLD_MS,
                screenOn = false,
            ),
        )
    }

    @Test
    fun `the safety cap is comfortably under the assumed 10-minute server online threshold`() {
        assertTrue(DEVICE_HEARTBEAT_MAX_SILENCE_MS < 10 * 60_000L)
    }
}
