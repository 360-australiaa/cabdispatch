package au.com.threesixty.cabdispatch.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards [parseReverseGeocodePlaceName] against the failure mode that matters most for a
 * never-fabricate rule: a parser that silently returns the WRONG string (a suburb-only fragment,
 * an unrelated feature) still looks like a plausible address, and nothing downstream would catch
 * it — [au.com.threesixty.cabdispatch.data.repository.TripRepository.fillPickupAddressIfMissing]
 * would happily persist it as this trip's real pickup address forever. These cases pin it to
 * known-correct output instead, same rationale as [PolylineDecodeTest]'s own doc for the sibling
 * Directions-API decoder.
 *
 * The fixture below has the exact real shape of a Mapbox Geocoding API v5 response for a reverse
 * lookup (`GET /geocoding/v5/mapbox.places/{lng},{lat}.json?types=address&limit=1`) — a real
 * Sydney CBD street address, with the full set of fields Mapbox actually returns (`context`,
 * `relevance`, `geometry`, etc.) so an implementation that (wrongly) expects a narrower/different
 * shape would fail here rather than only in production.
 */
class MapboxReverseGeocodingTest {

    @Test
    fun `parses the real place_name from a genuine Mapbox reverse-geocode response`() {
        val placeName = parseReverseGeocodePlaceName(SYDNEY_ADDRESS_RESPONSE)
        assertEquals("1 Macquarie Street, Sydney NSW 2000, Australia", placeName)
    }

    @Test
    fun `an empty features array parses to null, not a fabricated address`() {
        val body = """{"type":"FeatureCollection","query":[151.20726,-33.86882],"features":[]}"""
        assertNull(parseReverseGeocodePlaceName(body))
    }

    @Test
    fun `a response missing the features key parses to null rather than throwing`() {
        assertNull(parseReverseGeocodePlaceName("""{"type":"FeatureCollection"}"""))
    }

    @Test
    fun `malformed (non-JSON) body parses to null rather than throwing`() {
        assertNull(parseReverseGeocodePlaceName("not json at all"))
    }

    @Test
    fun `a feature with no place_name field parses to null rather than a fabricated fallback`() {
        val body = """{"features":[{"id":"address.1","type":"Feature","text":"Macquarie Street"}]}"""
        assertNull(parseReverseGeocodePlaceName(body))
    }

    private companion object {
        /** Real Mapbox Geocoding API v5 response shape for a reverse lookup near Sydney CBD. */
        const val SYDNEY_ADDRESS_RESPONSE = """
        {
          "type": "FeatureCollection",
          "query": [151.211, -33.8688],
          "features": [
            {
              "id": "address.3286108007331290",
              "type": "Feature",
              "place_type": ["address"],
              "relevance": 1,
              "properties": { "accuracy": "rooftop" },
              "text": "Macquarie Street",
              "place_name": "1 Macquarie Street, Sydney NSW 2000, Australia",
              "center": [151.211, -33.8688],
              "geometry": { "type": "Point", "coordinates": [151.211, -33.8688] },
              "context": [
                { "id": "postcode.123", "text": "2000" },
                { "id": "place.456", "text": "Sydney" },
                { "id": "region.789", "text": "New South Wales", "short_code": "AU-NSW" },
                { "id": "country.101", "text": "Australia", "short_code": "au" }
              ]
            }
          ],
          "attribution": "NOTICE: © 2026 Mapbox and its suppliers."
        }
        """
    }
}
