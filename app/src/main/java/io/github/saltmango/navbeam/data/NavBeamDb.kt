package io.github.saltmango.navbeam.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * On-device SQLite — the source of truth for saved locations, saved offline routes, and rides.
 * Plain SQLiteOpenHelper (no Room/KSP).
 *
 * Every row carries a stable UUID [sid] for deterministic upserts and deletes. All calls
 * are synchronous; callers run them off the main thread.
 */
class NavBeamDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "navbeam.db", null, 10) {

    companion object {
        @Volatile private var instance: NavBeamDb? = null
        fun get(context: Context): NavBeamDb =
            instance ?: synchronized(this) {
                instance ?: NavBeamDb(context).also { instance = it }
            }
        fun newSid(): String = UUID.randomUUID().toString()

        private const val CREATE_SAVED_ROUTE =
            """CREATE TABLE IF NOT EXISTS saved_route(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 sid TEXT NOT NULL DEFAULT '',
                 name TEXT NOT NULL,
                 from_name TEXT NOT NULL DEFAULT '',
                 to_name TEXT NOT NULL DEFAULT '',
                 from_lat REAL NOT NULL,
                 from_lng REAL NOT NULL,
                 to_lat REAL NOT NULL,
                 to_lng REAL NOT NULL,
                 distance_m REAL NOT NULL,
                 duration_s REAL NOT NULL,
                 route_json TEXT NOT NULL,
                 return_route_json TEXT NOT NULL DEFAULT '',
                 is_downloaded INTEGER DEFAULT 1,
                 created_ms INTEGER NOT NULL)"""

        private const val CREATE_RIDE =
            """CREATE TABLE IF NOT EXISTS ride(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 sid TEXT NOT NULL DEFAULT '',
                 start_ms INTEGER NOT NULL,
                 end_ms INTEGER NOT NULL,
                 distance_m REAL NOT NULL,
                 duration_s INTEGER NOT NULL,
                 avg_speed REAL NOT NULL,
                 max_speed REAL NOT NULL,
                 track TEXT NOT NULL DEFAULT '',
                 start_lat REAL NOT NULL DEFAULT 0,
                 start_lng REAL NOT NULL DEFAULT 0,
                 end_lat REAL NOT NULL DEFAULT 0,
                 end_lng REAL NOT NULL DEFAULT 0,
                 start_location TEXT,
                 destination_name TEXT,
                 stops TEXT,
                 start_battery INTEGER DEFAULT -1,
                 end_battery INTEGER DEFAULT -1,
                 battery_used INTEGER DEFAULT 0,
                 battery_used_per_min REAL DEFAULT 0.0,
                 start_battery_temp REAL DEFAULT 0.0,
                 end_battery_temp REAL DEFAULT 0.0,
                 thermal_throttled INTEGER DEFAULT 0,
                 reconnect_count INTEGER DEFAULT 0,
                 gps_accuracy_avg REAL DEFAULT 0.0,
                 gps_fix_count INTEGER DEFAULT 0,
                 gps_interval_avg_ms INTEGER DEFAULT 0,
                 gps_drops_count INTEGER DEFAULT 0,
                 recalc_count INTEGER DEFAULT 0,
                 avg_fps REAL DEFAULT 0.0,
                 skipped_frames INTEGER DEFAULT 0,
                 total_bytes_sent INTEGER DEFAULT 0,
                 avg_bitrate_kbps REAL DEFAULT 0.0,
                 avg_encode_time_ms REAL DEFAULT 0.0,
                 avg_network_latency_ms REAL DEFAULT 0.0,
                 encoder_mode TEXT DEFAULT '',
                 nav_mode TEXT DEFAULT '',
                 stream_quality TEXT DEFAULT '',
                 device_model TEXT DEFAULT '',
                 android_version TEXT DEFAULT '')"""


    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE saved_location(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 sid TEXT NOT NULL DEFAULT '',
                 name TEXT NOT NULL,
                 lat REAL NOT NULL,
                 lng REAL NOT NULL,
                 note TEXT NOT NULL DEFAULT '',
                 created_ms INTEGER NOT NULL)"""
        )
        db.execSQL(CREATE_RIDE)
        db.execSQL(CREATE_SAVED_ROUTE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.inTransaction {
            if (oldVersion < 2) {
                execSQL(
                    """CREATE TABLE IF NOT EXISTS saved_location(
                         id INTEGER PRIMARY KEY AUTOINCREMENT,
                         name TEXT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL,
                         note TEXT NOT NULL DEFAULT '', created_ms INTEGER NOT NULL)"""
                )
            }
            if (oldVersion < 3) {
                if (!hasColumn(this, "saved_location", "sid")) {
                    execSQL("ALTER TABLE saved_location ADD COLUMN sid TEXT NOT NULL DEFAULT ''")
                }
                execSQL(
                    "UPDATE saved_location SET sid = lower(hex(randomblob(16))) WHERE sid IS NULL OR sid = ''"
                )
            }
            if (oldVersion < 4) execSQL(CREATE_RIDE)

            if (oldVersion < 9) {
                val cols = listOf(
                    "start_location" to "TEXT",
                    "destination_name" to "TEXT",
                    "stops" to "TEXT",
                    "start_battery" to "INTEGER DEFAULT -1",
                    "end_battery" to "INTEGER DEFAULT -1",
                    "battery_used" to "INTEGER DEFAULT 0",
                    "battery_used_per_min" to "REAL DEFAULT 0.0",
                    "start_battery_temp" to "REAL DEFAULT 0.0",
                    "end_battery_temp" to "REAL DEFAULT 0.0",
                    "thermal_throttled" to "INTEGER DEFAULT 0",
                    "reconnect_count" to "INTEGER DEFAULT 0",
                    "gps_accuracy_avg" to "REAL DEFAULT 0.0",
                    "gps_fix_count" to "INTEGER DEFAULT 0",
                    "gps_interval_avg_ms" to "INTEGER DEFAULT 0",
                    "gps_drops_count" to "INTEGER DEFAULT 0",
                    "recalc_count" to "INTEGER DEFAULT 0",
                    "avg_fps" to "REAL DEFAULT 0.0",
                    "skipped_frames" to "INTEGER DEFAULT 0",
                    "total_bytes_sent" to "INTEGER DEFAULT 0",
                    "avg_bitrate_kbps" to "REAL DEFAULT 0.0",
                    "avg_encode_time_ms" to "REAL DEFAULT 0.0",
                    "avg_network_latency_ms" to "REAL DEFAULT 0.0",
                    "encoder_mode" to "TEXT DEFAULT ''",
                    "nav_mode" to "TEXT DEFAULT ''",
                    "stream_quality" to "TEXT DEFAULT ''",
                    "device_model" to "TEXT DEFAULT ''",
                    "android_version" to "TEXT DEFAULT ''",
                )
                for ((colName, colDef) in cols) {
                    if (!hasColumn(this, "ride", colName)) {
                        execSQL("ALTER TABLE ride ADD COLUMN $colName $colDef")
                    }
                }
            }
            if (oldVersion < 10) execSQL(CREATE_SAVED_ROUTE)
        }
    }

    private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0 && c.getString(nameIdx) == column) return true
            }
        }
        return false
    }



    // ── Saved destinations ─────────────────────────────────────────────────
    fun savedLocations(): List<SavedLocation> {
        val out = ArrayList<SavedLocation>()
        readableDatabase.rawQuery(
            "SELECT id,sid,name,lat,lng,note,created_ms FROM saved_location ORDER BY created_ms DESC", null,
        ).use { c ->
            while (c.moveToNext()) out.add(
                SavedLocation(
                    id = c.getLong(0), sid = c.getString(1), name = c.getString(2), lat = c.getDouble(3),
                    lng = c.getDouble(4), note = c.getString(5) ?: "", createdMs = c.getLong(6),
                )
            )
        }
        return out
    }

    fun upsertSaved(s: SavedLocation) {
        val cv = ContentValues().apply {
            put("sid", s.sid); put("name", s.name); put("lat", s.lat); put("lng", s.lng)
            put("note", s.note); put("created_ms", s.createdMs)
        }
        writableDatabase.inTransaction {
            if (update("saved_location", cv, "sid=?", arrayOf(s.sid)) == 0) {
                insert("saved_location", null, cv)
            }
        }
    }

    fun deleteSavedBySid(sid: String) =
        writableDatabase.delete("saved_location", "sid=?", arrayOf(sid))

    // ── Rides ──────────────────────────────────────────────────────────────
    fun rides(): List<Ride> {
        val out = ArrayList<Ride>()
        readableDatabase.rawQuery(
            "SELECT id,sid,start_ms,end_ms,distance_m,duration_s,avg_speed,max_speed," +
                "track,start_lat,start_lng,end_lat,end_lng," +
                "start_location,destination_name,stops,start_battery,end_battery,battery_used,battery_used_per_min," +
                "start_battery_temp,end_battery_temp,thermal_throttled,reconnect_count,gps_accuracy_avg," +
                "gps_fix_count,gps_interval_avg_ms,gps_drops_count,recalc_count,avg_fps,skipped_frames,total_bytes_sent," +
                "avg_bitrate_kbps,avg_encode_time_ms,avg_network_latency_ms,encoder_mode,nav_mode,stream_quality," +
                "device_model,android_version FROM ride ORDER BY start_ms DESC", null,
        ).use { c ->
            while (c.moveToNext()) out.add(
                Ride(
                    id = c.getLong(0), sid = c.getString(1), startMs = c.getLong(2), endMs = c.getLong(3),
                    distanceMeters = c.getDouble(4), durationSec = c.getLong(5), avgSpeedMps = c.getDouble(6),
                    maxSpeedMps = c.getDouble(7), trackPolyline = c.getString(8) ?: "",
                    startLat = c.getDouble(9), startLng = c.getDouble(10), endLat = c.getDouble(11), endLng = c.getDouble(12),
                    startLocationName = c.getString(13),
                    destinationName = c.getString(14),
                    stops = c.getString(15),
                    startBattery = c.getInt(16),
                    endBattery = c.getInt(17),
                    batteryUsed = c.getInt(18),
                    batteryUsedPerMin = c.getDouble(19),
                    startBatteryTemp = c.getDouble(20),
                    endBatteryTemp = c.getDouble(21),
                    thermalThrottled = c.getInt(22) != 0,
                    reconnectCount = c.getInt(23),
                    gpsAccuracyAvg = c.getDouble(24),
                    gpsFixCount = c.getInt(25),
                    gpsIntervalAvgMs = c.getLong(26),
                    gpsDropsCount = c.getInt(27),
                    recalcCount = c.getInt(28),
                    avgFps = c.getDouble(29),
                    skippedFrames = c.getInt(30),
                    totalBytesSent = c.getLong(31),
                    avgBitrateKbps = c.getDouble(32),
                    avgEncodeTimeMs = c.getDouble(33),
                    avgNetworkLatencyMs = c.getDouble(34),
                    encoderMode = c.getString(35) ?: "",
                    navMode = c.getString(36) ?: "",
                    streamQuality = c.getString(37) ?: "",
                    deviceModel = c.getString(38) ?: "",
                    androidVersion = c.getString(39) ?: "",
                )
            )
        }
        return out
    }

    fun upsertRide(r: Ride) {
        val cv = ContentValues().apply {
            put("sid", r.sid); put("start_ms", r.startMs); put("end_ms", r.endMs)
            put("distance_m", r.distanceMeters); put("duration_s", r.durationSec)
            put("avg_speed", r.avgSpeedMps); put("max_speed", r.maxSpeedMps); put("track", r.trackPolyline)
            put("start_lat", r.startLat); put("start_lng", r.startLng); put("end_lat", r.endLat); put("end_lng", r.endLng)
            put("start_location", r.startLocationName)
            put("destination_name", r.destinationName)
            put("stops", r.stops)
            put("start_battery", r.startBattery)
            put("end_battery", r.endBattery)
            put("battery_used", r.batteryUsed)
            put("battery_used_per_min", r.batteryUsedPerMin)
            put("start_battery_temp", r.startBatteryTemp)
            put("end_battery_temp", r.endBatteryTemp)
            put("thermal_throttled", if (r.thermalThrottled) 1 else 0)
            put("reconnect_count", r.reconnectCount)
            put("gps_accuracy_avg", r.gpsAccuracyAvg)
            put("gps_fix_count", r.gpsFixCount)
            put("gps_interval_avg_ms", r.gpsIntervalAvgMs)
            put("gps_drops_count", r.gpsDropsCount)
            put("recalc_count", r.recalcCount)
            put("avg_fps", r.avgFps)
            put("skipped_frames", r.skippedFrames)
            put("total_bytes_sent", r.totalBytesSent)
            put("avg_bitrate_kbps", r.avgBitrateKbps)
            put("avg_encode_time_ms", r.avgEncodeTimeMs)
            put("avg_network_latency_ms", r.avgNetworkLatencyMs)
            put("encoder_mode", r.encoderMode)
            put("nav_mode", r.navMode)
            put("stream_quality", r.streamQuality)
            put("device_model", r.deviceModel)
            put("android_version", r.androidVersion)
        }
        writableDatabase.inTransaction {
            if (update("ride", cv, "sid=?", arrayOf(r.sid)) == 0) {
                insert("ride", null, cv)
            }
        }
    }

    fun deleteRideBySid(sid: String) =
        writableDatabase.delete("ride", "sid=?", arrayOf(sid))

    // ── Saved offline routes ──────────────────────────────────────────────
    fun savedRoutes(): List<SavedRoute> {
        val out = ArrayList<SavedRoute>()
        readableDatabase.rawQuery(
            "SELECT id,sid,name,from_name,to_name,from_lat,from_lng,to_lat,to_lng,distance_m,duration_s,route_json,return_route_json,is_downloaded,created_ms FROM saved_route ORDER BY created_ms DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    SavedRoute(
                        id = c.getLong(0),
                        sid = c.getString(1),
                        name = c.getString(2),
                        fromName = c.getString(3),
                        toName = c.getString(4),
                        fromLat = c.getDouble(5),
                        fromLng = c.getDouble(6),
                        toLat = c.getDouble(7),
                        toLng = c.getDouble(8),
                        distanceMeters = c.getDouble(9),
                        durationSeconds = c.getDouble(10),
                        routeJson = c.getString(11) ?: "",
                        returnRouteJson = c.getString(12) ?: "",
                        isDownloaded = c.getInt(13) != 0,
                        createdMs = c.getLong(14),
                    )
                )
            }
        }
        return out
    }

    fun upsertSavedRoute(r: SavedRoute) {
        val cv = ContentValues().apply {
            put("sid", r.sid)
            put("name", r.name)
            put("from_name", r.fromName)
            put("to_name", r.toName)
            put("from_lat", r.fromLat)
            put("from_lng", r.fromLng)
            put("to_lat", r.toLat)
            put("to_lng", r.toLng)
            put("distance_m", r.distanceMeters)
            put("duration_s", r.durationSeconds)
            put("route_json", r.routeJson)
            put("return_route_json", r.returnRouteJson)
            put("is_downloaded", if (r.isDownloaded) 1 else 0)
            put("created_ms", r.createdMs)
        }
        writableDatabase.inTransaction {
            if (update("saved_route", cv, "sid=?", arrayOf(r.sid)) == 0) {
                insert("saved_route", null, cv)
            }
        }
    }

    fun deleteSavedRouteBySid(sid: String) =
        writableDatabase.delete("saved_route", "sid=?", arrayOf(sid))
}
