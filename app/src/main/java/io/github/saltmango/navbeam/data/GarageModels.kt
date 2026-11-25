package io.github.saltmango.navbeam.data


/**
 * One recorded ride = one connect→disconnect session with the dash. Stats are computed
 * from the GPS track as it streams; [trackPolyline] is the encoded path (for the map
 * snapshot on RidesScreen).
 */
data class Ride(
    val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val distanceMeters: Double,
    val durationSec: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val trackPolyline: String = "",   // Google/OSRM-encoded lat/lng path
    val startLat: Double = 0.0,
    val startLng: Double = 0.0,
    val endLat: Double = 0.0,
    val endLng: Double = 0.0,
    val sid: String = "",             // stable local UUID
    
    // New metrics & session parameters
    val startLocationName: String? = null,
    val destinationName: String? = null,
    val stops: String? = null,
    val startBattery: Int = -1,
    val endBattery: Int = -1,
    val batteryUsed: Int = 0,
    val batteryUsedPerMin: Double = 0.0,
    val startBatteryTemp: Double = 0.0,
    val endBatteryTemp: Double = 0.0,
    val thermalThrottled: Boolean = false,
    val reconnectCount: Int = 0,
    val gpsAccuracyAvg: Double = 0.0,
    val gpsFixCount: Int = 0,
    val gpsIntervalAvgMs: Long = 0L,
    val gpsDropsCount: Int = 0,
    val recalcCount: Int = 0,
    val avgFps: Double = 0.0,
    val skippedFrames: Int = 0,
    val totalBytesSent: Long = 0L,
    val avgBitrateKbps: Double = 0.0,
    val avgEncodeTimeMs: Double = 0.0,
    val avgNetworkLatencyMs: Double = 0.0,
    val encoderMode: String = "",
    val navMode: String = "",
    val streamQuality: String = "",
    val deviceModel: String = "",
    val androidVersion: String = "",
) {
    val avgSpeedKmh: Double get() = avgSpeedMps * 3.6
    val maxSpeedKmh: Double get() = maxSpeedMps * 3.6
    val distanceKm: Double get() = distanceMeters / 1000.0
}

