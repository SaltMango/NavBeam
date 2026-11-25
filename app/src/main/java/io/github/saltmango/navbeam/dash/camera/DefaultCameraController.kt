package io.github.saltmango.navbeam.dash.camera

import io.github.saltmango.navbeam.dash.nav.GeoPoint
import io.github.saltmango.navbeam.dash.nav.NavigationLocation
import io.github.saltmango.navbeam.dash.nav.TimeSource

class DefaultCameraController(
    private val timeSource: TimeSource
) : CameraController {

    companion object {
        private const val SMOOTH_TAU = 0.20
        private const val MANUAL_IDLE_MS = 8000L
        private const val DISPLAY_LEAD_S = 0.4
        private const val MAX_PREDICT_S = 3.0
        private const val BEARING_MIN_SPEED = 2.0f
    }

    private var zoom = 16
    private var baseZoom = 16
    private var headingUp = true
    private var panX = 0f
    private var panY = 0f
    private var followMode = true

    private var camLat = 0.0
    private var camLng = 0.0
    private var camHdg = 0f
    private var camInit = false
    
    private var lastUpdateMs = 0L

    private var fixLat = 0.0
    private var fixLng = 0.0
    private var fixBearing = 0f
    private var fixSpeed = 0f
    private var fixWallNanos = 0L
    private var lastFixTime = 0L
    private var gpsFixIntervalMs = 0.0
    private var heldRider: GeoPoint? = null
    private var lastManualPanAt = 0L

    private var cachedPosition = CameraPosition(GeoPoint(0.0, 0.0), 0f, 16, 0f, 0f, true)

    override fun getPosition(): CameraPosition = cachedPosition

    override fun isHeadingUp(): Boolean = headingUp

    override fun update(target: CameraTarget): CameraPosition {
        val nowMs = timeSource.wallTimeMs()
        val nowMonoNanos = timeSource.monotonicNanos()

        val dt = if (lastUpdateMs == 0L) {
            0.042
        } else {
            ((nowMs - lastUpdateMs) / 1000.0).coerceIn(0.0, 0.5)
        }
        lastUpdateMs = nowMs

        if (!followMode && nowMs - lastManualPanAt > MANUAL_IDLE_MS) {
            panX = 0f
            panY = 0f
            followMode = true
        }

        val loc = target.raw
        val matchedLat = target.matchedLat
        val matchedLng = target.matchedLng

        val heading = when {
            loc != null && loc.speedMps >= BEARING_MIN_SPEED -> loc.bearing
            camInit -> camHdg
            else -> 0f
        }

        if (loc != null && loc.timeMs != lastFixTime) {
            lastFixTime = loc.timeMs
            if (fixWallNanos > 0L) {
                val gap = (nowMonoNanos - fixWallNanos) / 1_000_000.0
                gpsFixIntervalMs = if (gpsFixIntervalMs <= 0.0) gap else gpsFixIntervalMs + (gap - gpsFixIntervalMs) * 0.3
            }
            fixLat = loc.latitude
            fixLng = loc.longitude
            fixBearing = loc.bearing
            fixSpeed = loc.speedMps
            fixWallNanos = nowMonoNanos
        }

        val rider: GeoPoint? = when {
            loc == null -> null
            fixSpeed > 0.5f -> {
                val elapsed = (nowMonoNanos - loc.elapsedRealtimeNanos) / 1_000_000_000.0
                val conf = ((50f - (loc.accuracy)) / 25f).toDouble().coerceIn(0.0, 1.0)
                val dist = predictedDistance(fixSpeed, elapsed + DISPLAY_LEAD_S) * conf
                heldRider = GeoPoint(fixLat, fixLng)
                val proj = project(fixLat, fixLng, fixBearing.toDouble(), dist)
                GeoPoint(proj.first, proj.second)
            }
            else -> {
                val cand = if (matchedLat != null && matchedLng != null) {
                    GeoPoint(matchedLat, matchedLng)
                } else {
                    GeoPoint(loc.latitude, loc.longitude)
                }
                val h = heldRider
                val band = loc.accuracy.coerceIn(8f, 30f).toDouble()
                if (h == null || GeoPoint.distMeters(h, cand) > band) {
                    heldRider = cand
                    cand
                } else {
                    h
                }
            }
        }

        val haveTarget = rider != null
        val targetLat = rider?.lat ?: camLat
        val targetLng = rider?.lng ?: camLng

        val acc = loc?.accuracy ?: 12f
        val tau = when {
            acc > 25f -> SMOOTH_TAU * 2.0
            acc > 12f -> SMOOTH_TAU * 1.5
            else      -> SMOOTH_TAU
        }
        val a = if (camInit) (1.0 - Math.exp(-dt / tau)) else 1.0

        if (haveTarget) {
            if (!camInit) {
                camLat = targetLat
                camLng = targetLng
                camHdg = heading
                camInit = true
            } else {
                camLat += (targetLat - camLat) * a
                camLng += (targetLng - camLng) * a
                val dh = (((heading - camHdg) % 360f) + 540f) % 360f - 180f
                camHdg += dh * a.toFloat()
            }
        }

        val centerLat = if (haveTarget) camLat else 0.0
        val centerLng = if (haveTarget) camLng else 0.0
        val camHeading = if (haveTarget) camHdg else heading

        val dist = target.nextTurnDistanceM
        val targetZoom = if (followMode && dist != null) {
            when {
                dist <= 100.0 -> (baseZoom + 2).coerceIn(11, 20)
                dist <= 300.0 -> (baseZoom + 1).coerceIn(11, 20)
                else -> baseZoom
            }
        } else {
            baseZoom
        }
        zoom = targetZoom

        cachedPosition = CameraPosition(
            center = GeoPoint(centerLat, centerLng),
            heading = camHeading,
            zoom = zoom,
            panX = panX,
            panY = panY,
            followMode = followMode
        )
        return cachedPosition
    }

    override fun zoomIn() {
        val clamped = (baseZoom + 1).coerceIn(11, 20)
        if (clamped != baseZoom) {
            panX *= 2f
            panY *= 2f
            baseZoom = clamped
            zoom = clamped
            lastManualPanAt = timeSource.wallTimeMs()
            updateCachedPosition()
        }
    }

    override fun zoomOut() {
        val clamped = (baseZoom - 1).coerceIn(11, 20)
        if (clamped != baseZoom) {
            panX /= 2f
            panY /= 2f
            baseZoom = clamped
            zoom = clamped
            lastManualPanAt = timeSource.wallTimeMs()
            updateCachedPosition()
        }
    }

    override fun panBy(dx: Float, dy: Float) {
        panX += dx
        panY += dy
        followMode = false
        lastManualPanAt = timeSource.wallTimeMs()
        updateCachedPosition()
    }

    override fun recenter() {
        panX = 0f
        panY = 0f
        followMode = true
        updateCachedPosition()
    }

    private fun updateCachedPosition() {
        cachedPosition = CameraPosition(
            center = GeoPoint(camLat, camLng),
            heading = camHdg,
            zoom = zoom,
            panX = panX,
            panY = panY,
            followMode = followMode
        )
    }

    override fun setHeadingUp(headingUp: Boolean) {
        this.headingUp = headingUp
    }

    override fun reset() {
        zoom = 16
        baseZoom = 16
        headingUp = true
        panX = 0f
        panY = 0f
        followMode = true
        camLat = 0.0
        camLng = 0.0
        camHdg = 0f
        camInit = false
        lastUpdateMs = 0L
        fixLat = 0.0
        fixLng = 0.0
        fixBearing = 0f
        fixSpeed = 0f
        fixWallNanos = 0L
        lastFixTime = 0L
        gpsFixIntervalMs = 0.0
        heldRider = null
        lastManualPanAt = 0L
        cachedPosition = CameraPosition(GeoPoint(0.0, 0.0), 0f, 16, 0f, 0f, true)
    }

    private fun predictedDistance(speedMps: Float, elapsedSec: Double): Double =
        speedMps.toDouble() * elapsedSec.coerceIn(0.0, MAX_PREDICT_S)

    private fun project(lat: Double, lng: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val r = 6371000.0
        val br = Math.toRadians(bearingDeg)
        val dr = distM / r
        val lat1 = Math.toRadians(lat)
        val lng1 = Math.toRadians(lng)
        val lat2 = Math.asin(Math.sin(lat1) * Math.cos(dr) + Math.cos(lat1) * Math.sin(dr) * Math.cos(br))
        val lng2 = lng1 + Math.atan2(
            Math.sin(br) * Math.sin(dr) * Math.cos(lat1),
            Math.cos(dr) - Math.sin(lat1) * Math.sin(lat2),
        )
        return Math.toDegrees(lat2) to Math.toDegrees(lng2)
    }
}
