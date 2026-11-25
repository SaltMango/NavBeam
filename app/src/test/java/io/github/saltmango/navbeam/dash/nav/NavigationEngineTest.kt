package io.github.saltmango.navbeam.dash.nav

import org.junit.Assert.*
import org.junit.Test

class NavigationEngineTest {

    private class FakeTimeSource : TimeSource {
        var timeMs = 1000L
        override fun wallTimeMs() = timeMs
        override fun monotonicNanos() = timeMs * 1_000_000L
    }

    @Test
    fun testBasicProgressTracking() {
        val timeSource = FakeTimeSource()
        val engine = DefaultNavigationEngine(timeSource)

        val geom = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.001),
            GeoPoint(0.0, 0.002),
            GeoPoint(0.0, 0.003)
        )
        val cumulative = doubleArrayOf(0.0, 111.0, 222.0, 333.0)
        
        val maneuvers = listOf(
            Maneuver(ManeuverType.CONTINUE, "Go straight", GeoPoint(0.0, 0.002), 222.0)
        )

        val route = CompiledRoute(
            geometry = geom,
            maneuvers = maneuvers,
            totalMeters = 333.0,
            totalSeconds = 60.0,
            cumulative = cumulative
        )

        engine.setDestination(Destination("Home", 0.0, 0.003))
        engine.setRoute(route)

        var state = engine.currentState()
        assertEquals(333.0, state.progress.remainingDistance ?: 0.0, 0.5)

        val loc1 = NavigationLocation(
            latitude = 0.0,
            longitude = 0.0,
            speedMps = 5f,
            bearing = 90f,
            accuracy = 5f,
            timeMs = 1000L,
            elapsedRealtimeNanos = 1_000_000_000L
        )

        var result = engine.onLocation(loc1)
        assertEquals(333.0, result.state.progress.remainingDistance ?: 0.0, 5.0)
        assertEquals(NavigationGpsStatus.GOOD, result.state.gps.gpsStatus)

        val loc2 = NavigationLocation(
            latitude = 0.0,
            longitude = 0.0005,
            speedMps = 10f,
            bearing = 90f,
            accuracy = 5f,
            timeMs = 6000L,
            elapsedRealtimeNanos = 6_000_000_000L
        )
        timeSource.timeMs = 6000L

        result = engine.onLocation(loc2)
        assertEquals(277.5, result.state.progress.remainingDistance ?: 0.0, 5.0)

        timeSource.timeMs = 15000L
        result = engine.onLocation(null)
        assertEquals(NavigationGpsStatus.LOST, result.state.gps.gpsStatus)
    }

    @Test
    fun testOffRouteAndRerouteEvent() {
        val timeSource = FakeTimeSource()
        val engine = DefaultNavigationEngine(timeSource)

        val geom = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001))
        val cumulative = doubleArrayOf(0.0, 111.0)
        val route = CompiledRoute(
            geometry = geom,
            maneuvers = emptyList(),
            totalMeters = 111.0,
            totalSeconds = 20.0,
            cumulative = cumulative
        )

        val dest = Destination("Office", 0.0, 0.001)
        engine.setDestination(dest)
        engine.setRoute(route)

        val offLoc1 = NavigationLocation(
            latitude = 0.002,
            longitude = 0.0,
            speedMps = 10f,
            bearing = 0f,
            accuracy = 5f,
            timeMs = 10000L,
            elapsedRealtimeNanos = 10_000_000_000L
        )
        timeSource.timeMs = 10000L
        var result = engine.onLocation(offLoc1)
        assertTrue(result.state.progress.offRoute)
        assertNull(result.event)

        val offLoc2 = NavigationLocation(
            latitude = 0.002,
            longitude = 0.0,
            speedMps = 10f,
            bearing = 0f,
            accuracy = 5f,
            timeMs = 13000L,
            elapsedRealtimeNanos = 13_000_000_000L
        )
        timeSource.timeMs = 13000L
        result = engine.onLocation(offLoc2)
        assertTrue(result.state.progress.offRoute)
        
        assertNotNull(result.event)
        assertTrue(result.event is NavigationEvent.RequestReroute)
        val rr = result.event as NavigationEvent.RequestReroute
        assertEquals(dest, rr.destination)
    }
}
