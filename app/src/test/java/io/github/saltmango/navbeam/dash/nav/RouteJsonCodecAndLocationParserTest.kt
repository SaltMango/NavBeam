package io.github.saltmango.navbeam.dash.nav

import io.github.saltmango.navbeam.util.LocationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RouteJsonCodecAndLocationParserTest {

    @Test
    fun routeJsonCodec_roundTripValidRoute() {
        val p1 = GeoPoint(12.9716, 77.5946)
        val p2 = GeoPoint(12.9750, 77.5990)
        val route = Route(
            geometry = listOf(p1, p2),
            maneuvers = listOf(
                Maneuver(ManeuverType.DEPART, "Head north", p1, 0.0),
                Maneuver(ManeuverType.ARRIVE, "Arrive", p2, 600.0),
            ),
            totalMeters = 600.0,
            totalSeconds = 120.0,
            staticSeconds = 100.0,
            cumulative = doubleArrayOf(0.0, GeoPoint.distMeters(p1, p2)),
        )

        val json = RouteJsonCodec.toJson(route)
        val parsed = RouteJsonCodec.fromJson(json)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.geometry.size)
        assertEquals(2, parsed.maneuvers.size)
        assertEquals(600.0, parsed.totalMeters, 0.01)
    }

    @Test
    fun routeJsonCodec_rejectsNonFiniteAndOutOfBoundsCoordinates() {
        val nanGeom = """
            {
              "totalMeters": 100.0,
              "totalSeconds": 10.0,
              "geometry": [
                {"lat": 12.97, "lng": 77.59},
                {"lat": "NaN", "lng": 77.60}
              ],
              "maneuvers": []
            }
        """.trimIndent()
        assertNull(RouteJsonCodec.fromJson(nanGeom))

        val outOfRangeLat = """
            {
              "totalMeters": 100.0,
              "totalSeconds": 10.0,
              "geometry": [
                {"lat": 95.0, "lng": 77.59},
                {"lat": 12.98, "lng": 77.60}
              ],
              "maneuvers": []
            }
        """.trimIndent()
        assertNull(RouteJsonCodec.fromJson(outOfRangeLat))

        val outOfRangeLng = """
            {
              "totalMeters": 100.0,
              "totalSeconds": 10.0,
              "geometry": [
                {"lat": 12.97, "lng": 77.59},
                {"lat": 12.98, "lng": -181.5}
              ],
              "maneuvers": []
            }
        """.trimIndent()
        assertNull(RouteJsonCodec.fromJson(outOfRangeLng))

        val negativeTotal = """
            {
              "totalMeters": -5.0,
              "totalSeconds": 10.0,
              "geometry": [
                {"lat": 12.97, "lng": 77.59},
                {"lat": 12.98, "lng": 77.60}
              ],
              "maneuvers": []
            }
        """.trimIndent()
        assertNull(RouteJsonCodec.fromJson(negativeTotal))
    }

    @Test
    fun locationParser_extractsValidCoordsAndBoundsOversizedInput() {
        assertEquals(
            12.9716 to 77.5946,
            LocationParser.extractCoords("https://www.google.com/maps/place/@12.9716,77.5946,15z"),
        )
        assertEquals(
            -33.8688 to 151.2093,
            LocationParser.extractCoords("geo:-33.8688,151.2093?q=Sydney"),
        )
        assertNull(LocationParser.extractCoords("@91.5000,77.5946"))

        // Coordinates past MAX_INPUT_CHARS should not be scanned
        val pad = "a".repeat(LocationParser.MAX_INPUT_CHARS + 50)
        assertNull(LocationParser.extractCoords("$pad@12.9716,77.5946"))
    }
}
