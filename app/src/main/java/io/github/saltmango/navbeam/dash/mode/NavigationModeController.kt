package io.github.saltmango.navbeam.dash.mode

import io.github.saltmango.navbeam.dash.map.MotionProfile
import io.github.saltmango.navbeam.dash.nav.GeoPoint
import kotlinx.coroutines.CoroutineScope

interface NavigationModeController {
    fun start(scope: CoroutineScope)
    fun stop()
    fun onLocationUpdated(lat: Double, lng: Double)
    fun onRouteUpdated(geometry: List<GeoPoint>)
    fun onDestinationUpdated(lat: Double, lng: Double)
}

interface NavigationModeContext {
    fun enterNavigation(projection: Boolean)
    fun stopNavigation()
    fun isNavigationRunning(): Boolean
}

interface RenderingContext {
    suspend fun startStreamPipeline(initialBitrate: Int, forceSoftware: Boolean)
    fun stopStreamPipeline()
    fun updateBitrate(bitrate: Int)
    fun setFrameDuration(durationMs: Long)
    suspend fun renderAndSubmitFrame()
    fun prefetch(lat: Double, lng: Double)
    fun prefetchRoute(geometry: List<GeoPoint>)
    fun getStreamQuality(): String
    fun useSoftwareEncoder(): Boolean
    fun isStreamingActive(): Boolean
    fun isThermalThrottled(): Boolean
    fun currentMotionProfile(): MotionProfile
    fun startMediaForwarding()
    fun getCustomFps(profile: String): Int
    fun getCustomBitrate(profile: String): Int
    fun isTurnNear(): Boolean
}
