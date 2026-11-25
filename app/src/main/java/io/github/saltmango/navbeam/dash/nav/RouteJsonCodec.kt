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
        if (jsonStr.isBlank()) return null
        return try {
            val root = JSONObject(jsonStr)
            val totalMeters = root.optDouble("totalMeters", 0.0)
            val totalSeconds = root.optDouble("totalSeconds", 0.0)
            val staticSeconds = root.optDouble("staticSeconds", totalSeconds)

            val geomList = ArrayList<GeoPoint>()
            val geomArray = root.optJSONArray("geometry")
            if (geomArray != null) {
                for (i in 0 until geomArray.length()) {
                    val obj = geomArray.getJSONObject(i)
                    geomList.add(GeoPoint(obj.getDouble("lat"), obj.getDouble("lng")))
                }
            }
            if (geomList.size < 2) return null

            val cum = DoubleArray(geomList.size)
            for (i in 1 until geomList.size) {
                cum[i] = cum[i - 1] + GeoPoint.distMeters(geomList[i - 1], geomList[i])
            }

            val manList = ArrayList<Maneuver>()
            val manArray = root.optJSONArray("maneuvers")
            if (manArray != null) {
                for (i in 0 until manArray.length()) {
                    val obj = manArray.getJSONObject(i)
                    val typeName = obj.optString("type", "CONTINUE")
                    val type = runCatching { ManeuverType.valueOf(typeName) }.getOrDefault(ManeuverType.CONTINUE)
                    val instruction = obj.optString("instruction", "")
                    val lat = obj.optDouble("lat", 0.0)
                    val lng = obj.optDouble("lng", 0.0)
                    val cumulativeMeters = obj.optDouble("cumulativeMeters", 0.0)
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
