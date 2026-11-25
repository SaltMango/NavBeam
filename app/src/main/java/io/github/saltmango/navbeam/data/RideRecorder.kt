package io.github.saltmango.navbeam.data

import io.github.saltmango.navbeam.dash.nav.GeoPoint
import io.github.saltmango.navbeam.dash.nav.PolylineCodec

/**
 * Accumulates GPS fixes for one ride (a connect→disconnect dash session) and produces a
 * [Ride] at the end. Points are thinned to ~every [MIN_MOVE_M] metres so the stored track
 * stays small; distance/speed are computed from the accepted points.
 *
 * Not thread-safe — driven from a single coroutine in DashViewModel.
 */
class RideRecorder {

    private val points = ArrayList<GeoPoint>()
    private var startMs = 0L
    private var lastMs = 0L
    private var distanceM = 0.0
    private var maxSpeed = 0.0
    private var last: GeoPoint? = null
    private var recording = false

    private var startBattery = -1
    private var startBatteryTemp = 0.0
    private var destinationName: String? = null
    private var stops: String? = null

    val isRecording: Boolean get() = recording
    val startPointTimeMs: Long get() = startMs

    fun start(
        startBattery: Int,
        startBatteryTemp: Double,
        destinationName: String?,
        stopsList: List<io.github.saltmango.navbeam.data.SharedLocation>
    ) {
        points.clear(); distanceM = 0.0; maxSpeed = 0.0; last = null
        startMs = System.currentTimeMillis(); lastMs = startMs
        recording = true
        this.startBattery = startBattery
        this.startBatteryTemp = startBatteryTemp
        this.destinationName = destinationName
        this.stops = if (stopsList.isNotEmpty()) stopsList.joinToString(", ") { it.name } else null
    }

    fun add(lat: Double, lng: Double, speedMps: Float, accuracyM: Float, timeMs: Long) {
        if (!recording) return
        // Drop noisy fixes entirely: a fix accurate only to 30 m wanders ~30 m between samples,
        // and that wander gets counted as travel — the main reason the recorded distance ran
        // ~0.2 km over the dash's odometer. Only trust reasonably precise fixes.
        if (accuracyM > ACC_GATE_M) return
        val p = GeoPoint(lat, lng)
        val prev = last
        if (prev == null) {
            points.add(p); last = p; lastMs = timeMs; return
        }
        val step = GeoPoint.distMeters(prev, p)
        if (step < MIN_MOVE_M) return   // thin out jitter / stationary noise
        // Suppress parked drift: a step that shows up while essentially not moving is GPS wander,
        // not distance ridden. Still advance the point so we don't bank a jump when you set off.
        if (speedMps >= STILL_SPEED) distanceM += step
        // Only count max speed on genuine movement, so parked GPS speed spikes don't inflate it.
        if (speedMps > maxSpeed) maxSpeed = speedMps.toDouble()
        points.add(p); last = p; lastMs = timeMs
    }

    /**
     * Finish the ride. Returns null for a trivial session so we don't litter the history with
     * parking-lot blips. A ride must have COVERED GROUND ([MIN_RIDE_M]); the old rule also kept any
     * session that merely LASTED a while, which saved 0 km "rides" whenever you connected and sat
     * (traffic/parked) for >90 s before disconnecting — distance is the only thing that makes it a ride.
     */
    fun stop(
        endBattery: Int,
        endBatteryTemp: Double,
        thermalThrottled: Boolean,
        reconnectCount: Int,
        gpsAccuracyAvg: Double,
        gpsFixCount: Int,
        gpsIntervalAvgMs: Long,
        gpsDropsCount: Int,
        recalcCount: Int,
        avgFps: Double,
        skippedFrames: Int,
        totalBytesSent: Long,
        avgEncodeTimeMs: Double,
        avgNetworkLatencyMs: Double,
        encoderMode: String,
        navMode: String,
        streamQuality: String,
        startLocationName: String? = null
    ): Ride? {
        recording = false
        val end = System.currentTimeMillis()
        val durationS = ((end - startMs) / 1000L).coerceAtLeast(0)
        if (points.size < 2 || distanceM < MIN_RIDE_M) return null
        val avg = if (durationS > 0) distanceM / durationS else 0.0
        val first = points.first(); val lastPt = points.last()

        val batteryUsed = if (startBattery in 0..100 && endBattery in 0..100) (startBattery - endBattery).coerceAtLeast(0) else 0
        val batteryUsedPerMin = if (durationS > 0) batteryUsed.toDouble() / (durationS / 60.0) else 0.0
        val avgBitrateKbps = if (durationS > 0) (totalBytesSent * 8.0) / (durationS * 1000.0) else 0.0

        val deviceModel = android.os.Build.MODEL ?: ""
        val androidVersion = android.os.Build.VERSION.RELEASE ?: ""

        // Usage: a real ride was recorded — how far/long riders actually go.
        io.github.saltmango.navbeam.util.Telemetry.logEvent(
            "ride_recorded",
            "distance_km" to distanceM / 1000.0,
            "duration_min" to durationS / 60.0,
            "start_battery" to startBattery,
            "end_battery" to endBattery,
            "battery_used" to batteryUsed,
            "battery_used_per_min" to batteryUsedPerMin,
            "start_battery_temp" to startBatteryTemp,
            "end_battery_temp" to endBatteryTemp,
            "thermal_throttled" to thermalThrottled,
            "reconnect_count" to reconnectCount,
            "gps_accuracy_avg" to gpsAccuracyAvg,
            "gps_fix_count" to gpsFixCount,
            "gps_interval_avg_ms" to gpsIntervalAvgMs,
            "gps_drops_count" to gpsDropsCount,
            "recalc_count" to recalcCount,
            "avg_fps" to avgFps,
            "skipped_frames" to skippedFrames,
            "total_bytes_sent" to totalBytesSent,
            "avg_bitrate_kbps" to avgBitrateKbps,
            "avg_encode_time_ms" to avgEncodeTimeMs,
            "avg_network_latency_ms" to avgNetworkLatencyMs,
            "encoder_mode" to encoderMode,
            "nav_mode" to navMode,
            "stream_quality" to streamQuality,
            "device_model" to deviceModel,
            "android_version" to androidVersion
        )
        return Ride(
            sid = NavBeamDb.newSid(),
            startMs = startMs, endMs = end,
            distanceMeters = distanceM, durationSec = durationS,
            avgSpeedMps = avg, maxSpeedMps = maxSpeed,
            trackPolyline = PolylineCodec.encode(points),
            startLat = first.lat, startLng = first.lng, endLat = lastPt.lat, endLng = lastPt.lng,
            startLocationName = startLocationName,
            destinationName = destinationName,
            stops = stops,
            startBattery = startBattery,
            endBattery = endBattery,
            batteryUsed = batteryUsed,
            batteryUsedPerMin = batteryUsedPerMin,
            startBatteryTemp = startBatteryTemp,
            endBatteryTemp = endBatteryTemp,
            thermalThrottled = thermalThrottled,
            reconnectCount = reconnectCount,
            gpsAccuracyAvg = gpsAccuracyAvg,
            gpsFixCount = gpsFixCount,
            gpsIntervalAvgMs = gpsIntervalAvgMs,
            gpsDropsCount = gpsDropsCount,
            recalcCount = recalcCount,
            avgFps = avgFps,
            skippedFrames = skippedFrames,
            totalBytesSent = totalBytesSent,
            avgBitrateKbps = avgBitrateKbps,
            avgEncodeTimeMs = avgEncodeTimeMs,
            avgNetworkLatencyMs = avgNetworkLatencyMs,
            encoderMode = encoderMode,
            navMode = navMode,
            streamQuality = streamQuality,
            deviceModel = deviceModel,
            androidVersion = androidVersion
        )
    }

    companion object {
        private const val MIN_MOVE_M = 8.0     // thin track points to ~every 8 m
        private const val MIN_RIDE_M = 150.0   // discard rides that covered less than this (parking-lot blips)
        private const val ACC_GATE_M = 20f     // ignore fixes worse than this (m); noisy fixes inflate distance
        private const val STILL_SPEED = 0.7f   // m/s (~2.5 km/h); below this, a step is parked GPS drift
    }
}
