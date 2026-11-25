package io.github.saltmango.navbeam.dash.nav

import android.content.Context
import android.util.Log
import io.github.saltmango.navbeam.BuildConfig
import io.github.saltmango.navbeam.data.MapsKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a road route using one of two Google routing backends, selected at build time via
 * [BuildConfig.USE_ROUTES_API]:
 *
 *  - false (debug)   → Directions API  GET  maps.googleapis.com/maps/api/directions/json
 *  - true  (release) → Routes API      POST routes.googleapis.com/directions/v2:computeRoutes
 *
 * Both backends return the same [Route] value type. Only this file is aware of the difference.
 * All other code (RouteViewModel, RouteScreen, dash) stays untouched.
 */
object Router {
    private const val TAG = "Router"

    // ── Public entry point ────────────────────────────────────────────────────

    suspend fun route(context: Context, from: GeoPoint, to: GeoPoint): Route? =
        route(context, from, listOf(to))

    suspend fun route(context: Context, from: GeoPoint, stops: List<GeoPoint>): Route? =
        withContext(Dispatchers.IO) {
            if (stops.isEmpty()) return@withContext null
            val apiKey = MapsKeyManager.getKey(context)
            if (apiKey.isBlank()) {
                Log.e(TAG, "MAPS_API_KEY is missing!")
                return@withContext null
            }

            if (BuildConfig.USE_ROUTES_API) {
                Log.d(TAG, "Using Routes API (release backend)")
                fetchRoutes(apiKey, from, stops)
            } else {
                Log.d(TAG, "Using Directions API (debug backend)")
                fetchDirections(apiKey, from, stops)
            }
        }

    // ── Backend A: Google Directions API (debug) ──────────────────────────────

    private const val DIRECTIONS_BASE = "https://maps.googleapis.com/maps/api/directions/json"

    private fun fetchDirections(apiKey: String, from: GeoPoint, stops: List<GeoPoint>): Route? {
        val originStr = "${from.lat},${from.lng}"
        val destStr = "${stops.last().lat},${stops.last().lng}"
        
        var url = "$DIRECTIONS_BASE" +
            "?origin=$originStr" +
            "&destination=$destStr" +
            "&mode=two_wheeler" +
            "&departure_time=now" +
            "&traffic_model=best_guess" +
            "&key=$apiKey"
            
        if (stops.size > 1) {
            val waypointsStr = stops.subList(0, stops.size - 1).joinToString("|") { "${it.lat},${it.lng}" }
            url += "&waypoints=$waypointsStr"
        }
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            parseDirections(body)
        } catch (e: Exception) {
            Log.w(TAG, "fetchDirections failed: ${e.message}")
            null
        }
    }

    private fun parseDirections(json: String): Route? {
        val root = JSONObject(json)
        if (root.optString("status") != "OK") {
            Log.w(TAG, "Directions API status=${root.optString("status")}")
            return null
        }
        val routes = root.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        val r0 = routes.getJSONObject(0)

        val geometry = ArrayList<GeoPoint>()
        val legsForGeometry = r0.optJSONArray("legs")
        if (legsForGeometry != null) {
            for (li in 0 until legsForGeometry.length()) {
                val leg = legsForGeometry.getJSONObject(li)
                val steps = leg.optJSONArray("steps")
                if (steps != null) {
                    for (si in 0 until steps.length()) {
                        val step = steps.getJSONObject(si)
                        val stepPoly = step.optJSONObject("polyline")?.optString("points")
                        if (stepPoly != null) {
                            val pts = PolylineCodec.decode(stepPoly)
                            for (pt in pts) {
                                if (geometry.isEmpty() || geometry.last() != pt) {
                                    geometry.add(pt)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (geometry.isEmpty()) {
            val overviewPolyline = r0.optJSONObject("overview_polyline")?.optString("points")
            if (overviewPolyline != null) {
                geometry.addAll(PolylineCodec.decode(overviewPolyline))
            }
        }
        if (geometry.size < 2) return null

        val cum = buildCumulative(geometry)

        val legs = r0.optJSONArray("legs")
        var totalStaticSec = 0.0
        var totalTrafficSec = 0.0
        var totalDistMeters = 0.0
        val maneuvers = ArrayList<Maneuver>()

        if (legs != null) {
            for (li in 0 until legs.length()) {
                val leg = legs.getJSONObject(li)
                val staticSec = leg.optJSONObject("duration")?.optDouble("value", 0.0) ?: 0.0
                val trafficSec = leg.optJSONObject("duration_in_traffic")?.optDouble("value", staticSec) ?: staticSec
                val dist = leg.optJSONObject("distance")?.optDouble("value", 0.0) ?: 0.0

                totalStaticSec += staticSec
                totalTrafficSec += trafficSec
                totalDistMeters += dist

                val steps = leg.optJSONArray("steps")
                if (steps != null) {
                    for (si in 0 until steps.length()) {
                        val step = steps.getJSONObject(si)
                        val loc  = step.getJSONObject("start_location")
                        val p    = GeoPoint(loc.getDouble("lat"), loc.getDouble("lng"))
                        val maneuverToken = step.optString("maneuver")
                        val instruction = stripHtml(step.optString("html_instructions"))
                        val type        = ManeuverType.fromGoogle(maneuverToken, instruction)
                        maneuvers.add(Maneuver(
                            type = type,
                            instruction = instruction,
                            location = p,
                            cumulativeMeters = nearestCumulative(p, geometry, cum),
                            rawGoogleManeuver = maneuverToken.takeIf { it.isNotEmpty() }
                        ))
                    }
                }
            }
        }

        val finalMeters = if (totalDistMeters > 0.0) totalDistMeters else cum.last()

        android.util.Log.d("Router", "Parsed Route (Directions): totalMeters=$finalMeters. Maneuvers count=${maneuvers.size}")
        maneuvers.forEachIndexed { index, m ->
            android.util.Log.d("Router", "  Maneuver[$index]: type=${m.type}, rawGoogle=${m.rawGoogleManeuver}, cumMeters=${m.cumulativeMeters}, instr=\"${m.instruction}\" -> dashCode=0x%02X".format(m.dashCode))
        }

        return Route(
            geometry      = geometry,
            maneuvers     = maneuvers,
            totalMeters   = finalMeters,
            totalSeconds  = totalTrafficSec,
            staticSeconds = totalStaticSec,
            cumulative    = cum,
        )
    }

    // ── Backend B: Google Routes API (release) ────────────────────────────────

    private const val ROUTES_ENDPOINT =
        "https://routes.googleapis.com/directions/v2:computeRoutes"

    /**
     * Field mask for Routes API — list only leaf fields.
     * Do NOT mix a parent (e.g. routes.legs.steps) with its children in the same mask;
     * that causes a 400 FieldMask error from the API.
     */
    private const val ROUTES_FIELD_MASK =
        "routes.polyline.encodedPolyline," +
        "routes.legs.distanceMeters," +
        "routes.legs.duration," +
        "routes.legs.staticDuration," +
        "routes.legs.steps.startLocation," +
        "routes.legs.steps.navigationInstruction"

    private fun fetchRoutes(apiKey: String, from: GeoPoint, stops: List<GeoPoint>): Route? {
        val requestBody = buildRoutesBody(from, stops)
        return try {
            val conn = (URL(ROUTES_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", apiKey)
                setRequestProperty("X-Goog-FieldMask", ROUTES_FIELD_MASK)
            }
            conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                val err = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                Log.w(TAG, "Routes API HTTP $code: $err")
                conn.disconnect()
                return null
            }
            conn.disconnect()
            parseRoutes(body)
        } catch (e: Exception) {
            Log.w(TAG, "fetchRoutes failed: ${e.message}")
            null
        }
    }

    private fun buildRoutesBody(from: GeoPoint, stops: List<GeoPoint>): String =
        JSONObject().apply {
            put("origin", JSONObject().apply {
                put("location", JSONObject().apply {
                    put("latLng", JSONObject().apply {
                        put("latitude", from.lat)
                        put("longitude", from.lng)
                    })
                })
            })
            put("destination", JSONObject().apply {
                put("location", JSONObject().apply {
                    put("latLng", JSONObject().apply {
                        put("latitude", stops.last().lat)
                        put("longitude", stops.last().lng)
                    })
                })
            })
            if (stops.size > 1) {
                val intermediates = org.json.JSONArray()
                for (i in 0 until stops.size - 1) {
                    val stop = stops[i]
                    intermediates.put(JSONObject().apply {
                        put("location", JSONObject().apply {
                            put("latLng", JSONObject().apply {
                                put("latitude", stop.lat)
                                put("longitude", stop.lng)
                            })
                        })
                    })
                }
                put("intermediates", intermediates)
            }
            put("travelMode", "TWO_WHEELER")
            put("routingPreference", "TRAFFIC_AWARE")
            put("polylineQuality", "HIGH_QUALITY")
            put("computeAlternativeRoutes", false)
            put("languageCode", "en-US")
        }.toString()

    private fun parseRoutes(json: String): Route? {
        val root = JSONObject(json)

        // Routes API surfaces errors as a JSON object, not a status string
        if (root.has("error")) {
            val err = root.getJSONObject("error")
            Log.w(TAG, "Routes API error: code=${err.optInt("code")} message=${err.optString("message")}")
            return null
        }

        val routes = root.optJSONArray("routes") ?: return null
        if (routes.length() == 0) {
            Log.w(TAG, "Routes API returned 0 routes")
            return null
        }
        val r0 = routes.getJSONObject(0)

        // Geometry — same encoded polyline algorithm, different field name
        val polyline = r0.getJSONObject("polyline").getString("encodedPolyline")
        val geometry = PolylineCodec.decode(polyline)
        if (geometry.size < 2) return null

        val cum = buildCumulative(geometry)

        val legs = r0.optJSONArray("legs")
        var totalStaticSec = 0.0
        var totalTrafficSec = 0.0
        var totalDistMeters = 0.0
        val maneuvers = ArrayList<Maneuver>()

        if (legs != null) {
            for (li in 0 until legs.length()) {
                val leg = legs.getJSONObject(li)
                val trafficSec = parseDurationSeconds(leg.optJSONObject("duration"))
                val staticSec  = parseDurationSeconds(leg.optJSONObject("staticDuration")).takeIf { it > 0.0 } ?: trafficSec
                val dist = leg.optDouble("distanceMeters", 0.0)

                totalStaticSec += staticSec
                totalTrafficSec += trafficSec
                totalDistMeters += dist

                val steps = leg.optJSONArray("steps")
                if (steps != null) {
                    for (si in 0 until steps.length()) {
                        val step   = steps.getJSONObject(si)
                        val latLng = step.optJSONObject("startLocation")?.optJSONObject("latLng") ?: continue
                        val p = GeoPoint(latLng.getDouble("latitude"), latLng.getDouble("longitude"))

                        val navInstr      = step.optJSONObject("navigationInstruction")
                        val maneuverToken = navInstr?.optString("maneuver") ?: ""
                        val instruction   = navInstr?.optString("instructions") ?: ""

                        maneuvers.add(Maneuver(
                            type             = ManeuverType.fromGoogle(maneuverToken, instruction),
                            instruction      = instruction,
                            location         = p,
                            cumulativeMeters = nearestCumulative(p, geometry, cum),
                            rawGoogleManeuver = maneuverToken.takeIf { it.isNotEmpty() }
                        ))
                    }
                }
            }
        }

        val finalMeters = if (totalDistMeters > 0.0) totalDistMeters else cum.last()

        android.util.Log.d("Router", "Parsed Route (Routes API): totalMeters=$finalMeters. Maneuvers count=${maneuvers.size}")
        maneuvers.forEachIndexed { index, m ->
            android.util.Log.d("Router", "  Maneuver[$index]: type=${m.type}, rawGoogle=${m.rawGoogleManeuver}, cumMeters=${m.cumulativeMeters}, instr=\"${m.instruction}\" -> dashCode=0x%02X".format(m.dashCode))
        }

        return Route(
            geometry      = geometry,
            maneuvers     = maneuvers,
            totalMeters   = finalMeters,
            totalSeconds  = totalTrafficSec,
            staticSeconds = totalStaticSec,
            cumulative    = cum,
        )
    }

    // ── Shared utilities ──────────────────────────────────────────────────────

    /** Builds cumulative distance array parallel to [geometry]. */
    private fun buildCumulative(geometry: List<GeoPoint>): DoubleArray {
        val cum = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cum[i] = cum[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])
        }
        return cum
    }

    /** Cumulative distance of the geometry vertex nearest to a maneuver location. */
    private fun nearestCumulative(p: GeoPoint, geom: List<GeoPoint>, cum: DoubleArray): Double {
        var best = 0.0
        var bestD = Double.MAX_VALUE
        for (i in geom.indices) {
            val d = GeoPoint.distMeters(p, geom[i])
            if (d < bestD) { bestD = d; best = cum[i] }
        }
        return best
    }

    /**
     * Parses a Routes API duration object of the form { "seconds": "1800" }.
     * The value is a JSON *string*, not a number — hence the manual parse.
     */
    private fun parseDurationSeconds(obj: JSONObject?): Double {
        obj ?: return 0.0
        return try {
            obj.optString("seconds", "0").toDouble()
        } catch (e: NumberFormatException) {
            Log.w(TAG, "parseDurationSeconds: ${e.message}")
            0.0
        }
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ")
}
