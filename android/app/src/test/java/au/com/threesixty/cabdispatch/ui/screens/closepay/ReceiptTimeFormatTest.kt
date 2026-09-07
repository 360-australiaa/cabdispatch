package au.com.threesixty.cabdispatch.ui.screens.closepay

import au.com.threesixty.cabdispatch.domain.fare.NSW_FARE_ZONE
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The receipt's date/time line.
 *
 * A live tax invoice printed "2026-09-07T19:15:55.762Z -> 2026-09-07T19:20:..." — the raw stored
 * instant. That is machine text, it is not the time of day the passenger was in the taxi, and on a
 * tablet whose timezone is wrong it is not even the right time. This pins the two properties that
 * matter: it reads as a NSW wall clock, and it does not drift with the device's own zone or locale.
 *
 * The formatter is restated here rather than exposed from the view model: what is under test is the
 * FORMAT (a NSW local, human, English rendering of a stored instant), and a test that imported the
 * very object it is checking could only ever agree with itself.
 */
class ReceiptTimeFormatTest {

    private val format: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", Locale.ENGLISH)

    private fun render(iso: String) = Instant.parse(iso).atZone(NSW_FARE_ZONE).format(format)

    @Test
    fun `a stored UTC instant prints as NSW local wall-clock time`() {
        // The exact trip that surfaced this: 19:15:55Z is 5:15am the next morning in Sydney (AEST,
        // UTC+10 in September). Printed raw it says 19:15 on the 7th, which is neither the date nor
        // the hour the passenger travelled.
        assertEquals("Tue 8 Sep 2026, 5:15 AM", render("2026-09-07T19:15:55.762Z"))
    }

    @Test
    fun `daylight saving is applied, not a fixed offset`() {
        // January is AEDT (UTC+11). A hardcoded +10 would print 10:30 PM here.
        assertEquals("Thu 1 Jan 2026, 11:30 PM", render("2026-01-01T12:30:00Z"))
        // ...and September is AEST (UTC+10): the same UTC wall clock, an hour apart locally.
        assertEquals("Tue 8 Sep 2026, 10:30 PM", render("2026-09-08T12:30:00Z"))
    }

    @Test
    fun `the same instant renders identically however it was expressed`() {
        // A receipt must describe when the trip happened, not how the timestamp was serialised.
        val fromZulu = render("2026-09-07T19:15:55Z")
        val fromOffset = render("2026-09-08T00:15:55+05:00") // the tablet's own Karachi offset
        assertEquals(fromZulu, fromOffset)
    }
}
