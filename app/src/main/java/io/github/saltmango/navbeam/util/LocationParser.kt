package io.github.saltmango.navbeam.util

import io.github.saltmango.navbeam.dash.nav.GeoPoint

/**
 * Simple coordinate extractor for shared text.
 * The complex URL resolution and scraping have been removed in favor of direct
 * Google Maps integration and in-app search.
 */
object LocationParser {
    const val MAX_INPUT_CHARS = 8192

    private val coord3d4d = Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)")
    private val coordAt   = Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
    private val coordQ    = Regex("[?&](?:q|query|destination|daddr)=(-?\\d+\\.\\d+),\\s*(-?\\d+\\.\\d+)")
    private val coordLl   = Regex("[?&]ll=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
    private val coordGeo  = Regex("geo:(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")

    fun extractCoords(text: String): Pair<Double, Double>? {
        val bounded = if (text.length > MAX_INPUT_CHARS) text.take(MAX_INPUT_CHARS) else text
        for (regex in listOf(coord3d4d, coordAt, coordQ, coordLl, coordGeo)) {
            val m = regex.find(bounded) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lng = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0) {
                return lat to lng
            }
        }
        return null
    }
}
