package au.com.threesixty.cabdispatch.data.remote

import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.ui.screens.dashboard.DriverEngagementFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The four `/v1/me/{wallet,rating,announcements,incentives}` DTOs decode the backend's REAL
 * responses. Every JSON literal below was captured verbatim (2026-09-04) from a local backend at
 * commit 58ccfcf running on sqlite, logged in as the seeded demo driver, after the demo tenant's
 * owner posted one top-up + one payout, one announcement and one incentive through the operator
 * routers — see the driver-engagement pass's report. Only the ids/timestamps are the run's own.
 *
 * Decoded through the shared [cabDispatchJson] (the same `ignoreUnknownKeys` config Retrofit
 * uses), so these are the exact bytes-to-DTO path the tiles rely on. Note the naive
 * (offset-less, microsecond) timestamps sqlite emits — [DriverEngagementFormat.parseInstant] must
 * accept them, or every relative time on the tiles would silently vanish on a dev server.
 */
class DriverEngagementDtosTest {

    @Test
    fun `wallet decodes derived balance and signed ledger lines`() {
        val wallet = cabDispatchJson.decodeFromString(WalletDto.serializer(), WALLET_JSON)
        assertEquals("913fa112-e474-4855-b02d-a371e93b41f8", wallet.driverId)
        assertEquals("109.50", wallet.balanceAud)
        assertEquals(2, wallet.recent.size)
        assertEquals("top_up", wallet.recent[0].kind)
        assertEquals("150.00", wallet.recent[0].amountAud)
        assertEquals("verify-top-up", wallet.recent[0].reference)
        assertEquals("payout", wallet.recent[1].kind)
        assertEquals("-40.50", wallet.recent[1].amountAud)
        assertNull(wallet.recent[1].note)
        assertEquals("\$109.50", DriverEngagementFormat.formatAud(wallet.balanceAud))
        assertEquals("-\$40.50", DriverEngagementFormat.formatSignedAud(wallet.recent[1].amountAud))
        assertNotNull(DriverEngagementFormat.parseInstant(wallet.recent[0].createdAt))
    }

    @Test
    fun `rating with no ratings yet has a null average, not a stand-in`() {
        val rating = cabDispatchJson.decodeFromString(RatingDto.serializer(), RATING_JSON)
        assertNull(rating.averageStars)
        assertEquals(0, rating.ratingCount)
        assertTrue(rating.recent.isEmpty())
        assertNull(DriverEngagementFormat.formatAverage(rating.averageStars))
    }

    @Test
    fun `announcements decode with naive microsecond timestamps`() {
        val list = cabDispatchJson.decodeFromString(AnnouncementListDto.serializer(), ANNOUNCEMENTS_JSON)
        assertEquals(1, list.items.size)
        val a = list.items[0]
        assertEquals("Airport rank change", a.title)
        assertEquals("info", a.kind)
        assertNull(a.endsAt)
        assertTrue(a.active)
        assertEquals(Instant.parse("2026-09-04T02:53:00.046356Z"), DriverEngagementFormat.parseInstant(a.startsAt))
    }

    @Test
    fun `incentive progress decodes the derived progress fields`() {
        val list = cabDispatchJson.decodeFromString(IncentiveProgressListDto.serializer(), INCENTIVES_JSON)
        assertEquals(1, list.items.size)
        val i = list.items[0]
        assertEquals("Weekend 40", i.title)
        assertEquals(40, i.targetTrips)
        assertEquals("120.00", i.rewardAud)
        assertEquals(0, i.completedTrips)
        assertEquals(40, i.remainingTrips)
        assertEquals(0, i.progressPct)
        assertFalse(i.achieved)
        assertEquals(0f, DriverEngagementFormat.incentiveFraction(i.completedTrips, i.targetTrips), 1e-6f)
        assertNotNull(DriverEngagementFormat.parseInstant(i.endsAt))
    }

    @Test
    fun `an additive backend field is ignored, not fatal`() {
        val withExtra = RATING_JSON.removeSuffix("}") + ",\"future_field\":123}"
        val rating = cabDispatchJson.decodeFromString(RatingDto.serializer(), withExtra)
        assertEquals(0, rating.ratingCount)
    }

    private companion object {
        const val WALLET_JSON = """{"driver_id":"913fa112-e474-4855-b02d-a371e93b41f8","balance_aud":"109.50","recent":[{"id":"bd00907d-ecdc-4ca2-8130-00313a97adc2","tenant_id":"e350db3e-0341-40db-bcc0-9402d821deda","driver_id":"913fa112-e474-4855-b02d-a371e93b41f8","amount_aud":"150.00","kind":"top_up","reference":"verify-top-up","note":"local verification","created_by_user_id":"f8fa4f56-1ae4-4b63-967e-0f4e481bae81","created_at":"2026-09-04T04:53:00"},{"id":"9d2e47d2-22d0-44ca-9ef4-524f17a9d5bd","tenant_id":"e350db3e-0341-40db-bcc0-9402d821deda","driver_id":"913fa112-e474-4855-b02d-a371e93b41f8","amount_aud":"-40.50","kind":"payout","reference":"verify-payout","note":null,"created_by_user_id":"f8fa4f56-1ae4-4b63-967e-0f4e481bae81","created_at":"2026-09-04T04:53:00"}]}"""
        const val RATING_JSON = """{"driver_id":"913fa112-e474-4855-b02d-a371e93b41f8","average_stars":null,"rating_count":0,"recent":[]}"""
        const val ANNOUNCEMENTS_JSON = """{"items":[{"id":"0e9d8182-d1f7-4969-bcb6-fe3e63a5500e","tenant_id":"e350db3e-0341-40db-bcc0-9402d821deda","title":"Airport rank change","body":"Use the new holding bay entrance from Monday.","kind":"info","starts_at":"2026-09-04T02:53:00.046356","ends_at":null,"active":true,"created_at":"2026-09-04T04:53:00","updated_at":"2026-09-04T04:53:00"}]}"""
        const val INCENTIVES_JSON = """{"items":[{"id":"0e89cea2-86a1-4537-be4f-3ce41ff83c73","tenant_id":"e350db3e-0341-40db-bcc0-9402d821deda","title":"Weekend 40","description":"40 trips this weekend","target_trips":40,"reward_aud":"120.00","starts_at":"2026-09-04T02:53:00.046356","ends_at":"2026-09-07T04:53:00.126427","active":true,"created_at":"2026-09-04T04:53:00","updated_at":"2026-09-04T04:53:00","completed_trips":0,"remaining_trips":40,"progress_pct":0,"achieved":false}]}"""
    }
}
