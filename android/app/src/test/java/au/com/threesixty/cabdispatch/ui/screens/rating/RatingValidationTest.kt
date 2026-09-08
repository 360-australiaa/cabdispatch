package au.com.threesixty.cabdispatch.ui.screens.rating

import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.remote.TripRatingCreateDto
import au.com.threesixty.cabdispatch.data.remote.TripRatingDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic behind the new Rate Passenger screen (2026-09-04 ratings pass):
 * - [RatingValidation]'s bounds (mirrors the backend's `RATING_MIN_STARS`/`RATING_MAX_STARS` = 1/5)
 * - [RatingLookup]'s duplicate-detection lookup over a driver's recent-ratings list
 * - [TripRatingCreateDto] serializes into exactly the `POST /v1/trips/{trip_id}/rating` request
 *   body shape the backend's `TripRatingCreate` schema expects (plain `stars`/`comment`, no
 *   `@SerialName` remapping needed — see that DTO's own doc).
 *
 * No ViewModel test here: [RatePassengerViewModel] resolves `AppContainer.tripDao`/
 * `.driverEngagementRepository` at construction, same as every other ViewModel in this app (see
 * e.g. `TripDetailViewModel`) — none of those are unit-tested directly in this JVM test source
 * set either, for the same reason. The logic that actually needs covering (star bounds, duplicate
 * lookup) is pulled out into the plain objects above specifically so it can be, same rationale
 * `HudRollTest` gives for `HudRoll`.
 */
class RatingValidationTest {

    @Test
    fun `0 stars (nothing picked) is not submittable`() {
        assertFalse(RatingValidation.isSubmittable(0))
    }

    @Test
    fun `1 through 5 stars are submittable`() {
        for (stars in 1..5) assertTrue(RatingValidation.isSubmittable(stars))
    }

    @Test
    fun `6 or more stars is not submittable`() {
        assertFalse(RatingValidation.isSubmittable(6))
        assertFalse(RatingValidation.isSubmittable(Int.MAX_VALUE))
    }

    @Test
    fun `negative stars is not submittable`() {
        assertFalse(RatingValidation.isSubmittable(-1))
    }

    @Test
    fun `clamp keeps 0 (allows clearing back to unset)`() {
        assertEquals(0, RatingValidation.clamp(0))
    }

    @Test
    fun `clamp caps above 5 down to 5`() {
        assertEquals(5, RatingValidation.clamp(9))
    }

    @Test
    fun `clamp floors below 0 up to 0`() {
        assertEquals(0, RatingValidation.clamp(-3))
    }

    @Test
    fun `RatingLookup finds an existing rating for the current trip`() {
        val recent = listOf(
            rating(tripId = "trip-a", stars = 4),
            rating(tripId = "trip-b", stars = 5),
        )
        val found = RatingLookup.findExisting(recent, "trip-b")
        assertEquals("trip-b", found?.tripId)
        assertEquals(5, found?.stars)
    }

    @Test
    fun `RatingLookup returns null when the trip is not in the recent list`() {
        val recent = listOf(rating(tripId = "trip-a", stars = 4))
        assertNull(RatingLookup.findExisting(recent, "trip-not-rated"))
    }

    @Test
    fun `RatingLookup on an empty list never fabricates a match`() {
        assertNull(RatingLookup.findExisting(emptyList(), "trip-a"))
    }

    @Test
    fun `TripRatingCreateDto encodes the exact POST body shape the backend expects`() {
        val body = TripRatingCreateDto(stars = 5, comment = "Great passenger")
        val json = cabDispatchJson.encodeToString(TripRatingCreateDto.serializer(), body)
        assertEquals("""{"stars":5,"comment":"Great passenger"}""", json)
    }

    @Test
    fun `TripRatingCreateDto omits comment as null when not given, not empty string`() {
        val body = TripRatingCreateDto(stars = 3)
        val json = cabDispatchJson.encodeToString(TripRatingCreateDto.serializer(), body)
        assertEquals("""{"stars":3,"comment":null}""", json)
    }

    @Test
    fun `TripRatingDto decodes the real 201 response shape`() {
        val dto = cabDispatchJson.decodeFromString(TripRatingDto.serializer(), RATING_READ_JSON)
        assertEquals("r1", dto.id)
        assertEquals("trip-42", dto.tripId)
        assertEquals("driver-9", dto.driverId)
        assertEquals(5, dto.stars)
        assertEquals("Great trip!", dto.comment)
    }

    @Test
    fun `TripRatingDto decodes a null comment honestly, not as an empty string`() {
        val dto = cabDispatchJson.decodeFromString(TripRatingDto.serializer(), RATING_READ_NO_COMMENT_JSON)
        assertNull(dto.comment)
    }

    private fun rating(tripId: String, stars: Int): TripRatingDto = TripRatingDto(
        id = "id-$tripId",
        tenantId = "tenant-1",
        tripId = tripId,
        driverId = "driver-1",
        stars = stars,
        comment = null,
        createdAt = "2026-09-04T04:53:00",
    )

    private companion object {
        const val RATING_READ_JSON = """{"id":"r1","tenant_id":"tenant-1","trip_id":"trip-42","driver_id":"driver-9","stars":5,"comment":"Great trip!","created_at":"2026-09-04T04:53:00"}"""
        const val RATING_READ_NO_COMMENT_JSON = """{"id":"r2","tenant_id":"tenant-1","trip_id":"trip-43","driver_id":"driver-9","stars":3,"comment":null,"created_at":"2026-09-04T04:53:00"}"""
    }
}
