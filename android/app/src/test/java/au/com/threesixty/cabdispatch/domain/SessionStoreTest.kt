package au.com.threesixty.cabdispatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Plain-JVM unit tests for [SessionStore], backed by [FakeSharedPreferences] — see that file's own
 * doc for why a fake `SharedPreferences` rather than Robolectric. [now] is a fixed anchor so the
 * fatigue-limit staleness tests below are exact, not "hours ago relative to whenever the test
 * happens to run" — the same reasoning [ShiftDurationLimit]'s own tests already use its `now`
 * parameter for.
 */
class SessionStoreTest {

    private val now: Instant = Instant.parse("2026-09-04T12:00:00Z")

    private fun newStore() = SessionStore(FakeSharedPreferences())

    private val freshSession = DriverSession(
        driverId = "D-100",
        driverName = "Sam Driver",
        vehicleId = "KHI-01",
        vehicleUuid = "veh-uuid-1",
        shiftId = "shift-1",
        shiftStartAt = now.minus(Duration.ofHours(1)).toString(),
    )

    @Test
    fun `restore before any save returns null`() {
        assertNull(newStore().restore(now))
    }

    @Test
    fun `save then restore round-trips every field, well inside the shift limit`() {
        val store = newStore()
        store.save(freshSession)

        assertEquals(freshSession, store.restore(now))
    }

    @Test
    fun `restore tolerates a session with no shift bound`() {
        val store = newStore()
        val noShift = freshSession.copy(shiftId = null, shiftStartAt = null, vehicleUuid = null)
        store.save(noShift)

        assertEquals(noShift, store.restore(now))
    }

    @Test
    fun `a shift older than the fatigue limit is dropped, not silently resumed`() {
        val store = newStore()
        val overdueStart = now.minus(Duration.ofHours(13))
        store.save(freshSession.copy(shiftStartAt = overdueStart.toString()))

        val restored = store.restore(now)

        // Driver identity and vehicle binding still come back...
        assertEquals("D-100", restored?.driverId)
        assertEquals("Sam Driver", restored?.driverName)
        assertEquals("KHI-01", restored?.vehicleId)
        assertEquals("veh-uuid-1", restored?.vehicleUuid)
        // ...but the overdue shift itself is not, so the dashboard doesn't silently carry an
        // already-expired shift forward.
        assertNull(restored?.shiftId)
        assertNull(restored?.shiftStartAt)
    }

    @Test
    fun `a shift just under the fatigue limit is still resumed`() {
        val store = newStore()
        val recentStart = now.minus(Duration.ofHours(11))
        store.save(freshSession.copy(shiftStartAt = recentStart.toString()))

        val restored = store.restore(now)

        assertEquals("shift-1", restored?.shiftId)
        assertEquals(recentStart.toString(), restored?.shiftStartAt)
    }

    @Test
    fun `clear wipes a previously saved session`() {
        val store = newStore()
        store.save(freshSession)
        store.clear()

        assertNull(store.restore(now))
    }

    @Test
    fun `save overwrites a previously saved session rather than merging`() {
        val store = newStore()
        store.save(freshSession)
        val nextDriver = DriverSession(
            driverId = "D-200",
            driverName = "Alex Driver",
            vehicleId = "KHI-02",
            vehicleUuid = null,
            shiftId = null,
            shiftStartAt = null,
        )
        store.save(nextDriver)

        assertEquals(nextDriver, store.restore(now))
    }
}
