package io.github.saltmango.navbeam.dash.mode

import io.github.saltmango.navbeam.dash.DashSession
import io.github.saltmango.navbeam.dash.DashState
import io.github.saltmango.navbeam.dash.nav.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Field

class ArrowModeRegressionTest {

    private class FakeTimeSource : TimeSource {
        var timeMs = 1000L
        override fun wallTimeMs() = timeMs
        override fun monotonicNanos() = timeMs * 1_000_000L
    }

    private fun getPrivateField(obj: Any, fieldName: String): Any? {
        val field: Field = obj.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(obj)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun testArrowModeSymmetricRegression() {
        val timeSourceMap = FakeTimeSource()
        val engineMap = DefaultNavigationEngine(timeSourceMap)

        val timeSourceArrow = FakeTimeSource()
        val engineArrow = DefaultNavigationEngine(timeSourceArrow)

        // Setup identical routes
        val geom = listOf(
            GeoPoint(12.9716, 77.5946),
            GeoPoint(12.9726, 77.5956),
            GeoPoint(12.9736, 77.5966),
            GeoPoint(12.9746, 77.5976)
        )
        val cumulative = doubleArrayOf(0.0, 150.0, 300.0, 450.0)
        val maneuvers = listOf(
            Maneuver(ManeuverType.TURN_LEFT, "Turn Left", GeoPoint(12.9726, 77.5956), 150.0)
        )

        val route = CompiledRoute(
            geometry = geom,
            maneuvers = maneuvers,
            totalMeters = 450.0,
            totalSeconds = 90.0,
            cumulative = cumulative
        )

        val dest = Destination("Office", 12.9746, 77.5976)

        // Init Map Engine
        engineMap.setDestination(dest)
        engineMap.setRoute(route)

        // Init Arrow Engine
        engineArrow.setDestination(dest)
        engineArrow.setRoute(route)

        // Set up DashSession for Map and Arrow
        val scope = CoroutineScope(SupervisorJob())
        val sessionMap = DashSession(scope)
        val sessionArrow = DashSession(scope)

        // Transition both sessions to STREAMING via reflection to enable TBT updates
        val stateField = DashSession::class.java.getDeclaredField("_state")
        stateField.isAccessible = true
        (stateField.get(sessionMap) as MutableStateFlow<DashState>).value = DashState.STREAMING
        (stateField.get(sessionArrow) as MutableStateFlow<DashState>).value = DashState.STREAMING

        // Disable video on Arrow Session
        sessionArrow.disableVideoProjection()

        // Feed GPS samples
        val samples = listOf(
            NavigationLocation(12.9716, 77.5946, 5f, 45f, 5f, 1000L, 1_000_000_000L),
            NavigationLocation(12.9720, 77.5950, 8f, 45f, 5f, 3000L, 3_000_000_000L),
            NavigationLocation(12.9725, 77.5955, 10f, 45f, 5f, 5000L, 5_000_000_000L),
            NavigationLocation(12.9730, 77.5960, 5f, 45f, 5f, 8000L, 8_000_000_000L)
        )

        for (i in samples.indices) {
            val sample = samples[i]
            
            // Advance time
            timeSourceMap.timeMs = sample.timeMs
            timeSourceArrow.timeMs = sample.timeMs

            val resultMap = engineMap.onLocation(sample)
            val resultArrow = engineArrow.onLocation(sample)

            // 1. Assert NavigationState A == B for every GPS sample
            assertEquals("Step $i NavigationState mismatch", resultMap.state.progress.remainingDistance, resultArrow.state.progress.remainingDistance)
            assertEquals("Step $i ETA mismatch", resultMap.state.progress.etaSeconds, resultArrow.state.progress.etaSeconds)
            if (resultMap.state.gps.matchedLat != null || resultArrow.state.gps.matchedLat != null) {
                assertEquals("Step $i snapping mismatch", resultMap.state.gps.matchedLat!!, resultArrow.state.gps.matchedLat!!, 1e-6)
            }

            // 2. Assert Voice events generated are identical
            if (resultMap.event != null || resultArrow.event != null) {
                assertNotNull(resultMap.event)
                assertNotNull(resultArrow.event)
                assertEquals(resultMap.event!!.javaClass, resultArrow.event!!.javaClass)
            }

            // Sync with session
            sessionMap.updateNavigation(resultMap.state)
            sessionArrow.updateNavigation(resultArrow.state)

            // 3. Assert NavInfo properties mapped to packets are identical
            assertEquals("Step $i navManeuver mismatch", getPrivateField(sessionMap, "navManeuver"), getPrivateField(sessionArrow, "navManeuver"))
            assertEquals("Step $i navPrimaryDist mismatch", getPrivateField(sessionMap, "navPrimaryDist"), getPrivateField(sessionArrow, "navPrimaryDist"))
            assertEquals("Step $i navPrimaryUnit mismatch", getPrivateField(sessionMap, "navPrimaryUnit"), getPrivateField(sessionArrow, "navPrimaryUnit"))
            assertEquals("Step $i navTotalDist mismatch", getPrivateField(sessionMap, "navTotalDist"), getPrivateField(sessionArrow, "navTotalDist"))
            assertEquals("Step $i navTotalUnit mismatch", getPrivateField(sessionMap, "navTotalUnit"), getPrivateField(sessionArrow, "navTotalUnit"))
            assertEquals("Step $i navEta mismatch", getPrivateField(sessionMap, "navEta"), getPrivateField(sessionArrow, "navEta"))
        }

        // Verify Video Projection settings differ
        assertEquals(true, getPrivateField(sessionMap, "videoProjectionEnabled"))
        assertEquals(false, getPrivateField(sessionArrow, "videoProjectionEnabled"))
    }

    @Test
    fun testControllersArchitectureDecoupling() {
        val navContext = object : NavigationModeContext {
            var enterNavProj: Boolean? = null
            var stopped = false
            override fun enterNavigation(projection: Boolean) { enterNavProj = projection }
            override fun stopNavigation() { stopped = true }
            override fun isNavigationRunning(): Boolean = true
        }

        val renderingContext = object : RenderingContext {
            var startedStream = false
            var stoppedStream = false
            override suspend fun startStreamPipeline(initialBitrate: Int, forceSoftware: Boolean) { startedStream = true }
            override fun stopStreamPipeline() { stoppedStream = true }
            override fun updateBitrate(bitrate: Int) {}
            override fun setFrameDuration(durationMs: Long) {}
            override suspend fun renderAndSubmitFrame() {}
            override fun prefetch(lat: Double, lng: Double) {}
            override fun prefetchRoute(geometry: List<GeoPoint>) {}
            override fun getStreamQuality(): String = "Mid"
            override fun useSoftwareEncoder(): Boolean = false
            override fun isStreamingActive(): Boolean = true
            override fun isThermalThrottled(): Boolean = false
            override fun currentMotionProfile(): io.github.saltmango.navbeam.dash.map.MotionProfile = io.github.saltmango.navbeam.dash.map.MotionProfile.STOPPED
            override fun startMediaForwarding() {}
            override fun getCustomFps(profile: String): Int = 4
            override fun getCustomBitrate(profile: String): Int = 800
            override fun isTurnNear(): Boolean = false
        }

        val arrowController = ArrowModeController(navContext)
        val mapController = MapModeController(navContext, renderingContext)

        // Start Arrow Mode
        arrowController.start(CoroutineScope(SupervisorJob()))
        assertEquals(true, navContext.enterNavProj)

        // Stop Arrow Mode
        arrowController.stop()
        assertTrue(navContext.stopped)

        // Reset
        navContext.enterNavProj = null
        navContext.stopped = false

        // Start Map Mode
        mapController.start(CoroutineScope(SupervisorJob()))
        assertEquals(true, navContext.enterNavProj)

        // Stop Map Mode
        mapController.stop()
        assertTrue(navContext.stopped)
        assertTrue(renderingContext.stoppedStream)
    }
}
