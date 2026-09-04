package au.com.threesixty.cabdispatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ordering policy that keeps the fare meter and the navigator from talking over each
 * other ([SpeechQueue], consumed by `SharedTtsEngine` in `SpeechAnnouncer.kt`): a fare
 * announcement always beats any pending nav instruction, nav instructions keep their own order,
 * and nothing is ever interleaved out of enqueue order within a priority.
 */
class SpeechQueueTest {

    private fun SpeechQueue.drain(): List<String> {
        val out = ArrayList<String>()
        while (true) out += (poll() ?: break).text
        return out
    }

    @Test
    fun `fare announcement is spoken before nav instructions queued ahead of it`() {
        val q = SpeechQueue()
        q.enqueue("Turn left onto George Street", SpeechPriority.NAV)
        q.enqueue("Continue straight", SpeechPriority.NAV)
        q.enqueue("Fare now 12 dollars", SpeechPriority.FARE)

        assertEquals(
            listOf("Fare now 12 dollars", "Turn left onto George Street", "Continue straight"),
            q.drain(),
        )
    }

    @Test
    fun `fare and nav never interleave and each keeps FIFO order`() {
        val q = SpeechQueue()
        q.enqueue("nav 1", SpeechPriority.NAV)
        q.enqueue("fare 1", SpeechPriority.FARE)
        q.enqueue("nav 2", SpeechPriority.NAV)
        q.enqueue("fare 2", SpeechPriority.FARE)
        q.enqueue("nav 3", SpeechPriority.NAV)

        assertEquals(listOf("fare 1", "fare 2", "nav 1", "nav 2", "nav 3"), q.drain())
    }

    @Test
    fun `coalesced fare keeps only the latest figure but leaves nav untouched`() {
        val q = SpeechQueue()
        q.enqueue("nav 1", SpeechPriority.NAV)
        q.enqueue("Fare now 11 dollars", SpeechPriority.FARE, coalesce = true)
        q.enqueue("nav 2", SpeechPriority.NAV)
        q.enqueue("Fare now 12 dollars", SpeechPriority.FARE, coalesce = true)

        assertEquals(listOf("Fare now 12 dollars", "nav 1", "nav 2"), q.drain())
    }

    @Test
    fun `a nav instruction enqueued while a fare is pending waits its turn`() {
        val q = SpeechQueue()
        q.enqueue("fare", SpeechPriority.FARE)
        q.enqueue("nav", SpeechPriority.NAV)
        assertEquals("fare", q.poll()?.text)
        assertEquals("nav", q.poll()?.text)
        assertNull(q.poll())
        assertTrue(q.isEmpty())
    }

    @Test
    fun `clearing by owner or priority drops only the matching utterances`() {
        val nav = Any()
        val fare = Any()
        val q = SpeechQueue()
        q.enqueue("fare", SpeechPriority.FARE, owner = fare)
        q.enqueue("nav 1", SpeechPriority.NAV, owner = nav)
        q.enqueue("nav 2", SpeechPriority.NAV, owner = nav)
        assertEquals(3, q.size)

        q.clearOwner(nav)
        assertEquals(listOf("fare"), q.drain())

        q.enqueue("fare", SpeechPriority.FARE, owner = fare)
        q.enqueue("nav", SpeechPriority.NAV, owner = nav)
        q.clear(SpeechPriority.FARE)
        assertEquals(listOf("nav"), q.drain())

        q.enqueue("a", SpeechPriority.NAV)
        q.enqueue("b", SpeechPriority.FARE)
        q.clearAll()
        assertTrue(q.isEmpty())
    }

    @Test
    fun `sequence numbers are unique and monotonic across priorities`() {
        val q = SpeechQueue()
        val a = q.enqueue("a", SpeechPriority.NAV)
        val b = q.enqueue("b", SpeechPriority.FARE)
        val c = q.enqueue("c", SpeechPriority.NAV)
        assertTrue(a.seq < b.seq && b.seq < c.seq)
    }
}
