package au.com.threesixty.cabdispatch.data.remote

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [MapboxDirections.parseRoute]'s real-maneuver-field parsing — `maneuver.type` and
 * `maneuver.modifier` — the two fields the meter screen's turn-icon mapping reads. The fixture
 * body below is shaped exactly like a real Mapbox Directions v5 response (same `routes[0].legs[0]
 * .steps[].maneuver` structure captured for [PolylineDecodeTest]'s Sydney CBD -> Airport route),
 * with the geometry truncated to the single-point canonical polyline from that spec (irrelevant to
 * this test — [PolylineDecodeTest] already pins the decoder itself).
 *
 * Deliberately includes steps with NO `modifier` key at all (a real "depart"/"arrive" shape) so a
 * missing field is pinned to `null`, never a guessed value — exactly what the UI's icon mapping
 * depends on to know "no real data" from "a real direction".
 */
class MapboxDirectionsRouteStepTest {

    private val directions = MapboxDirections(OkHttpClient())

    @Test
    fun `parses real maneuver type and modifier fields per step`() {
        val route = directions.parseRoute(SYDNEY_LIKE_RESPONSE)

        assertEquals(4, route?.steps?.size)
        val steps = requireNotNull(route).steps

        // depart: Mapbox commonly omits `modifier` on this maneuver type — must land as null, not
        // a guessed direction.
        assertEquals("depart", steps[0].maneuverType)
        assertNull(steps[0].modifier)
        assertEquals("Head south on Pitt Street", steps[0].instruction)

        // turn/right: the ordinary case.
        assertEquals("turn", steps[1].maneuverType)
        assertEquals("right", steps[1].modifier)

        // roundabout/left: a maneuver type with real laterality carried in `modifier`, not
        // inferred from `type` alone.
        assertEquals("roundabout", steps[2].maneuverType)
        assertEquals("left", steps[2].modifier)

        // arrive: also commonly modifier-less.
        assertEquals("arrive", steps[3].maneuverType)
        assertNull(steps[3].modifier)
    }

    @Test
    fun `a maneuver with neither type nor modifier keys parses both as null`() {
        val route = directions.parseRoute(NO_MANEUVER_FIELDS_RESPONSE)

        val step = requireNotNull(route).steps.single()
        assertNull(step.maneuverType)
        assertNull(step.modifier)
        // The existing fields this parser already handled are untouched by the new ones.
        assertEquals("Continue straight", step.instruction)
    }

    private companion object {
        /** Single-point canonical polyline from Google's own algorithm spec (decodes to
         * (38.5,-120.2)) — only [PolylineDecodeTest] cares about geometry correctness; this
         * fixture just needs a syntactically valid `geometry` string. */
        const val POINT_GEOMETRY = "_p~iF~ps|U"

        val SYDNEY_LIKE_RESPONSE = """
            {
              "routes": [
                {
                  "distance": 13300.0,
                  "duration": 1320.0,
                  "geometry": "$POINT_GEOMETRY",
                  "legs": [
                    {
                      "steps": [
                        {
                          "distance": 120.5,
                          "maneuver": {
                            "type": "depart",
                            "location": [151.2093, -33.8688],
                            "instruction": "Head south on Pitt Street"
                          }
                        },
                        {
                          "distance": 340.2,
                          "maneuver": {
                            "type": "turn",
                            "modifier": "right",
                            "location": [151.2101, -33.8700],
                            "instruction": "Turn right onto George Street"
                          }
                        },
                        {
                          "distance": 90.0,
                          "maneuver": {
                            "type": "roundabout",
                            "modifier": "left",
                            "location": [151.2110, -33.8720],
                            "instruction": "At the roundabout, take the 2nd exit"
                          }
                        },
                        {
                          "distance": 0.0,
                          "maneuver": {
                            "type": "arrive",
                            "location": [151.2130, -33.8750],
                            "instruction": "You have arrived at your destination"
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val NO_MANEUVER_FIELDS_RESPONSE = """
            {
              "routes": [
                {
                  "distance": 500.0,
                  "duration": 60.0,
                  "geometry": "$POINT_GEOMETRY",
                  "legs": [
                    {
                      "steps": [
                        {
                          "distance": 500.0,
                          "maneuver": {
                            "location": [151.2093, -33.8688],
                            "instruction": "Continue straight"
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
    }
}
