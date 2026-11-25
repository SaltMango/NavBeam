package io.github.saltmango.navbeam.dash.mode

import io.github.saltmango.navbeam.dash.map.MotionProfile
import io.github.saltmango.navbeam.dash.nav.GeoPoint
import io.github.saltmango.navbeam.dash.profile.DashProfileManager
import io.github.saltmango.navbeam.util.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MapModeController(
    private val navContext: NavigationModeContext,
    private val renderingContext: RenderingContext
) : NavigationModeController {

    private var streamJob: Job? = null

    override fun start(scope: CoroutineScope) {
        if (streamJob != null) return

        navContext.enterNavigation(projection = true)

        val initialDisp = DashProfileManager.activeProfile.value.display
        val activeBps = initialDisp.activeBitrateBps.coerceIn(50_000, 3_000_000)
        val idleBps = initialDisp.idleBitrateBps.coerceIn(50_000, activeBps)
        val quality = renderingContext.getStreamQuality()
        val targetBitrate = when (quality) {
            "Battery Saver" -> idleBps
            "Max"           -> (activeBps * 2).coerceAtMost(3_000_000)
            "Custom"        -> renderingContext.getCustomBitrate("HIGHWAY") * 1000
            else            -> activeBps
        }

        streamJob = scope.launch(AppDispatchers.rendering) {
            try {
                renderingContext.startStreamPipeline(targetBitrate, renderingContext.useSoftwareEncoder())
            } catch (e: Exception) {
                try {
                    android.util.Log.e("MapModeController", "Failed to start streaming pipeline", e)
                } catch (t: Throwable) {
                    System.err.println("[MapModeController] ERROR: Failed to start streaming pipeline")
                }
                navContext.stopNavigation()
                return@launch
            }

            renderingContext.startMediaForwarding()
            
            var lastPrefetch = 0L
            var frameMs = initialDisp.frameDurationActiveMs.coerceIn(50L, 2000L)
            var nextFrameMs = System.currentTimeMillis()

            while (isActive && renderingContext.isStreamingActive()) {
                try {
                    renderingContext.renderAndSubmitFrame()

                    val disp = DashProfileManager.activeProfile.value.display
                    val activeMs = disp.frameDurationActiveMs.coerceIn(50L, 2000L)
                    val idleMs = disp.frameDurationIdleMs.coerceIn(100L, 5000L)
                    val cityMs = ((activeMs * 4L) / 3L).coerceIn(activeMs, idleMs)
                    val stoppedMs = (idleMs * 2L).coerceIn(idleMs, 5000L)
                    val maxFastMs = (activeMs / 2L).coerceIn(100L, activeMs)
                    val maxCityMs = ((maxFastMs * 4L) / 3L).coerceIn(maxFastMs, activeMs)

                    val currentProfileVal = renderingContext.currentMotionProfile()
                    val currentQuality = renderingContext.getStreamQuality()
                    frameMs = if (renderingContext.isThermalThrottled() && currentProfileVal != MotionProfile.DISCONNECTED) {
                        stoppedMs
                    } else {
                        if (renderingContext.isTurnNear()) {
                            when (currentQuality) {
                                "Max" -> maxFastMs
                                "Custom" -> (1000L / renderingContext.getCustomFps("CORNERING").coerceAtLeast(1)).coerceIn(100L, 2000L)
                                else -> activeMs
                            }
                        } else {
                            when (currentQuality) {
                                "Max" -> when (currentProfileVal) {
                                    MotionProfile.HIGHWAY -> maxFastMs
                                    MotionProfile.CITY -> maxCityMs
                                    MotionProfile.SLOW -> activeMs
                                    MotionProfile.STOPPED, MotionProfile.DISCONNECTED -> idleMs
                                }
                                "Battery Saver" -> when (currentProfileVal) {
                                    MotionProfile.HIGHWAY -> idleMs
                                    MotionProfile.CITY -> idleMs
                                    MotionProfile.SLOW -> stoppedMs
                                    MotionProfile.STOPPED, MotionProfile.DISCONNECTED -> stoppedMs
                                }
                                "Custom" -> when (currentProfileVal) {
                                    MotionProfile.HIGHWAY -> (1000L / renderingContext.getCustomFps("HIGHWAY").coerceAtLeast(1)).coerceIn(100L, 2000L)
                                    MotionProfile.CITY -> (1000L / renderingContext.getCustomFps("CITY").coerceAtLeast(1)).coerceIn(100L, 2000L)
                                    MotionProfile.SLOW -> (1000L / renderingContext.getCustomFps("SLOW").coerceAtLeast(1)).coerceIn(100L, 2000L)
                                    MotionProfile.STOPPED, MotionProfile.DISCONNECTED -> (1000L / renderingContext.getCustomFps("STOPPED").coerceAtLeast(1)).coerceIn(100L, 2000L)
                                }
                                else -> when (currentProfileVal) {
                                    MotionProfile.HIGHWAY -> activeMs
                                    MotionProfile.CITY -> cityMs
                                    MotionProfile.SLOW -> idleMs
                                    MotionProfile.STOPPED, MotionProfile.DISCONNECTED -> stoppedMs
                                }
                            }
                        }
                    }
                    renderingContext.setFrameDuration(frameMs)

                    val sendNow = System.currentTimeMillis()
                    if (sendNow - lastPrefetch > 20_000) {
                        lastPrefetch = sendNow
                        val profile = renderingContext.currentMotionProfile()
                        if (profile != MotionProfile.DISCONNECTED) {
                            renderingContext.prefetch(0.0, 0.0)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    try {
                        android.util.Log.e("MapModeController", "Frame loop error", e)
                    } catch (t: Throwable) {
                        System.err.println("[MapModeController] ERROR: Frame loop error")
                    }
                }

                nextFrameMs += frameMs
                val sleepMs = nextFrameMs - System.currentTimeMillis()
                if (sleepMs > 1) {
                    delay(sleepMs)
                } else if (sleepMs < -frameMs) {
                    nextFrameMs = System.currentTimeMillis()
                }
            }
        }
    }

    override fun stop() {
        streamJob?.cancel()
        streamJob = null
        renderingContext.stopStreamPipeline()
        navContext.stopNavigation()
    }

    override fun onLocationUpdated(lat: Double, lng: Double) {
        renderingContext.prefetch(lat, lng)
    }

    override fun onRouteUpdated(geometry: List<GeoPoint>) {
        renderingContext.prefetchRoute(geometry)
    }

    override fun onDestinationUpdated(lat: Double, lng: Double) {
        renderingContext.prefetch(lat, lng)
    }
}
