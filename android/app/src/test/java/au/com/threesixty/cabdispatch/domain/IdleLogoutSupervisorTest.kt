package au.com.threesixty.cabdispatch.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM unit tests for [shouldAutoLogOut] — the pure decision table behind
 * [IdleLogoutSupervisor]. Deliberately exercises just the function, not the supervisor itself
 * (which needs a live [SessionHolder]/coroutine scope/[au.com.threesixty.cabdispatch.data.repository.TripRepository]) —
 * see [IdleLogoutSupervisor]'s own class doc for the full write-up of what counts as
 * "unattended" and why a running fare is the one case that never triggers it.
 */
class IdleLogoutSupervisorTest {

    private val timeout = 20 * 60 * 1000L // 20 minutes, matching IDLE_LOGOUT_TIMEOUT_MS's own value

    @Test
    fun `still within the timeout -- stays logged in`() {
        val now = 1_000_000L
        assertFalse(shouldAutoLogOut(now, lastActiveAtMillis = now - (timeout - 1), hasActiveFare = false, timeoutMillis = timeout))
    }

    @Test
    fun `exactly at the timeout -- logs out`() {
        val now = 1_000_000L
        assertTrue(shouldAutoLogOut(now, lastActiveAtMillis = now - timeout, hasActiveFare = false, timeoutMillis = timeout))
    }

    @Test
    fun `well past the timeout -- logs out`() {
        val now = 1_000_000L
        assertTrue(shouldAutoLogOut(now, lastActiveAtMillis = now - timeout * 3, hasActiveFare = false, timeoutMillis = timeout))
    }

    @Test
    fun `a running fare never logs out, no matter how long it has been idle`() {
        // The real case this guards: a driver mid-trip, hands on the wheel rather than the
        // tablet, for well over the ordinary idle timeout -- signing them out mid-fare would be
        // actively dangerous, not a safety feature. See IdleLogoutSupervisor's own doc.
        val now = 1_000_000L
        assertFalse(
            shouldAutoLogOut(now, lastActiveAtMillis = now - timeout * 10, hasActiveFare = true, timeoutMillis = timeout),
        )
    }

    @Test
    fun `zero elapsed time -- never logs out`() {
        val now = 1_000_000L
        assertFalse(shouldAutoLogOut(now, lastActiveAtMillis = now, hasActiveFare = false, timeoutMillis = timeout))
    }

    @Test
    fun `a shorter configured timeout is honoured`() {
        val now = 1_000_000L
        val shortTimeout = 60_000L // 1 minute
        assertFalse(shouldAutoLogOut(now, lastActiveAtMillis = now - 30_000L, hasActiveFare = false, timeoutMillis = shortTimeout))
        assertTrue(shouldAutoLogOut(now, lastActiveAtMillis = now - 61_000L, hasActiveFare = false, timeoutMillis = shortTimeout))
    }

    @Test
    fun `default timeout parameter matches IDLE_LOGOUT_TIMEOUT_MS`() {
        val now = 1_000_000L
        // No explicit timeoutMillis -- exercises the real default a live SessionHolder-driven
        // supervisor would actually use.
        assertFalse(shouldAutoLogOut(now, lastActiveAtMillis = now - (IDLE_LOGOUT_TIMEOUT_MS - 1_000), hasActiveFare = false))
        assertTrue(shouldAutoLogOut(now, lastActiveAtMillis = now - IDLE_LOGOUT_TIMEOUT_MS, hasActiveFare = false))
    }
}
