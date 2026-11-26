package io.github.saltmango.navbeam.dash.nav

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON Serializer / Deserializer for [Route] instances.
 * Enables saving complete road routes to SQLite for 100% offline navigation.
 */
object RouteJsonCodec {
    private const val TAG = "RouteJsonCodec"
    const val MAX_JSON_CHARS = 8 * 1024 * 1024
    const val MAX_GEOMETRY_POINTS = 50_000
    const val MAX_MANEUVERS = 5_000

    fun toJson(route: Route): String {
        return try {
            val root = JSONObject()
            root.put("totalMeters", route.totalMeters)
            root.put("totalSeconds", route.totalSeconds)
            root.put("staticSeconds", route.staticSeconds)

            val geomArray = JSONArray()
            for (pt in route.geometry) {
                val ptObj = JSONObject()
                ptObj.put("lat", pt.lat)
                ptObj.put("lng", pt.lng)
                geomArray.put(ptObj)
            }
            root.put("geometry", geomArray)

            val manArray = JSONArray()
            for (m in route.maneuvers) {
                val mObj = JSONObject()
                mObj.put("type", m.type.name)
                mObj.put("instruction", m.instruction)
                mObj.put("lat", m.location.lat)
                mObj.put("lng", m.location.lng)
                mObj.put("cumulativeMeters", m.cumulativeMeters)
                m.rawGoogleManeuver?.let { mObj.put("rawGoogleManeuver", it) }
                manArray.put(mObj)
            }
            root.put("maneuvers", manArray)

            root.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize route to JSON", e)
            ""
        }
    }

    fun fromJson(jsonStr: String): Route? {
        if (jsonStr.isBlank() || jsonStr.length > MAX_JSON_CHARS) return null
        return try {
            val root = JSONObject(jsonStr)
            val totalMeters = root.optDouble("totalMeters", 0.0)
            val totalSeconds = root.optDouble("totalSeconds", 0.0)
            val staticSeconds = root.optDouble("staticSeconds", totalSeconds)
            if (!totalMeters.isFinite() || totalMeters < 0.0) return null
            if (!totalSeconds.isFinite() || totalSeconds < 0.0) return null
            if (!staticSeconds.isFinite() || staticSeconds < 0.0) return null

            val geomArray = root.optJSONArray("geometry") ?: return null
            if (geomArray.length() < 2 || geomArray.length() > MAX_GEOMETRY_POINTS) return null
            val geomList = ArrayList<GeoPoint>(geomArray.length())
            for (i in 0 until geomArray.length()) {
                val obj = geomArray.getJSONObject(i)
                val lat = obj.getDouble("lat")
                val lng = obj.getDouble("lng")
                if (!isValidCoord(lat, lng)) return null
                geomList.add(GeoPoint(lat, lng))
            }

            val cum = DoubleArray(geomList.size)
            for (i in 1 until geomList.size) {
                cum[i] = cum[i - 1] + GeoPoint.distMeters(geomList[i - 1], geomList[i])
            }

            val manList = ArrayList<Maneuver>()
            val manArray = root.optJSONArray("maneuvers")
            if (manArray != null) {
                if (manArray.length() > MAX_MANEUVERS) return null
                for (i in 0 until manArray.length()) {
                    val obj = manArray.getJSONObject(i)
                    val typeName = obj.optString("type", "CONTINUE")
                    val type = runCatching { ManeuverType.valueOf(typeName) }.getOrDefault(ManeuverType.CONTINUE)
                    val instruction = obj.optString("instruction", "")
                    val lat = obj.optDouble("lat", 0.0)
                    val lng = obj.optDouble("lng", 0.0)
                    if (!isValidCoord(lat, lng)) return null
                    val cumulativeMeters = obj.optDouble("cumulativeMeters", 0.0)
                    if (!cumulativeMeters.isFinite() || cumulativeMeters < 0.0) return null
                    val rawGoogle = if (obj.has("rawGoogleManeuver")) obj.getString("rawGoogleManeuver") else null

                    manList.add(
                        Maneuver(
                            type = type,
                            instruction = instruction,
                            location = GeoPoint(lat, lng),
                            cumulativeMeters = cumulativeMeters,
                            rawGoogleManeuver = rawGoogle
                        )
                    )
                }
            }

            Route(
                geometry = geomList,
                maneuvers = manList,
                totalMeters = if (totalMeters > 0) totalMeters else cum.last(),
                totalSeconds = totalSeconds,
                staticSeconds = staticSeconds,
                cumulative = cum
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse saved route JSON", e)
            null
        }
    }

    private fun isValidCoord(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

    /**
     * Fallback for return route if online fetch is unavailable: reverses the polyline
     * and generates return maneuver hints.
     */
    fun createReverseRoute(outbound: Route): Route {
        val revGeom = outbound.geometry.reversed()
        val cum = DoubleArray(revGeom.size)
        for (i in 1 until revGeom.size) {
            cum[i] = cum[i - 1] + GeoPoint.distMeters(revGeom[i - 1], revGeom[i])
        }

        val revManeuvers = ArrayList<Maneuver>()
        if (revGeom.isNotEmpty()) {
            revManeuvers.add(Maneuver(ManeuverType.DEPART, "Start return route", revGeom.first(), 0.0))
            if (revGeom.size > 1) {
                revManeuvers.add(Maneuver(ManeuverType.ARRIVE, "Arrive at destination", revGeom.last(), cum.last()))
            }
        }

        return Route(
            geometry = revGeom,
            maneuvers = revManeuvers,
            totalMeters = outbound.totalMeters,
            totalSeconds = outbound.totalSeconds,
            staticSeconds = outbound.staticSeconds,
            cumulative = cum
        )
    }
}
