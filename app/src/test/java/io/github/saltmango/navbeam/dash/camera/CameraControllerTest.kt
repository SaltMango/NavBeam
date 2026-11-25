package io.github.saltmango.navbeam.dash.camera

import io.github.saltmango.navbeam.dash.nav.GeoPoint
import io.github.saltmango.navbeam.dash.nav.NavigationLocation
import io.github.saltmango.navbeam.dash.nav.TimeSource
import org.junit.Assert.*
import org.junit.Test

class CameraControllerTest {

    private class FakeTimeSource : TimeSource {
        var wallMs = 1000L
        var monoNanos = 1_000_000_000L

        override fun wallTimeMs(): Long = wallMs
        override fun monotonicNanos(): Long = monoNanos

        fun advance(ms: Long) {
            wallMs += ms
            monoNanos += ms * 1_000_000L
        }
    }

    private fun testProject(lat: Double, lng: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
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

    @Test
    fun testManualPanTimeout() {
        val timeSource = FakeTimeSource()
        val controller = DefaultCameraController(timeSource)

        val loc = NavigationLocation(
            latitude = 37.7749,
            longitude = -122.4194,
            speedMps = 0f,
            bearing = 0f,
            accuracy = 10f,
            timeMs = 1000L,
            elapsedRealtimeNanos = 1_000_000_000L
        )
        val target = CameraTarget(loc, null, null)

        // Initialize camera
        var pos = controller.update(target)
        assertTrue(pos.followMode)

        // Pan manual
        controller.panBy(50f, -50f)
        pos = controller.update(target)
        assertFalse(pos.followMode)
        assertEquals(50f, pos.panX, 0.01f)

        // Advance 4 seconds (less than 8s timeout)
        timeSource.advance(4000)
        pos = controller.update(target)
        assertFalse(pos.followMode)
        assertEquals(50f, pos.panX, 0.01f)

        // Advance another 5 seconds (total 9s, > 8s timeout)
        timeSource.advance(5000)
        pos = controller.update(target)
        assertTrue(pos.followMode)
        assertEquals(0f, pos.panX, 0.01f)
        assertEquals(0f, pos.panY, 0.01f)
    }

    @Test
    fun testMathematicalRegression() {
        val timeSource = FakeTimeSource()
        val controller = DefaultCameraController(timeSource)

        // Old implementation simulation variables
        var oldCamLat = 0.0
        var oldCamLng = 0.0
        var oldCamHdg = 0f
        var oldCamInit = false

        fun oldUpdate(targetLat: Double, targetLng: Double, rawHeading: Float, speed: Float, dt: Double, acc: Float) {
            val heading = if (speed >= 2.0f) rawHeading else if (oldCamInit) oldCamHdg else 0f
            val tau = when {
                acc > 25f -> 0.20 * 2.0
                acc > 12f -> 0.20 * 1.5
                else      -> 0.20
            }
            val a = if (oldCamInit) (1.0 - Math.exp(-dt / tau)) else 1.0
            if (!oldCamInit) {
                oldCamLat = targetLat
                oldCamLng = targetLng
                oldCamHdg = heading
                oldCamInit = true
            } else {
                oldCamLat += (targetLat - oldCamLat) * a
                oldCamLng += (targetLng - oldCamLng) * a
                val dh = (((heading - oldCamHdg) % 360f) + 540f) % 360f - 180f
                oldCamHdg += dh * a.toFloat()
            }
        }

        val baseLat = 37.7749
        val baseLng = -122.4194
        var timeMs = 10000L
        timeSource.wallMs = timeMs
        timeSource.monoNanos = timeMs * 1_000_000L

        for (i in 1..100) {
            val rawLat = baseLat + (i * 0.0001)
            val rawLng = baseLng + (i * 0.0001)
            val heading = (i * 2.5f) % 360f
            val acc = 10f
            val dtMs = 250L // 4 fps
            val speed = if (i > 50) 5f else 0f

            // Predict targets for oldUpdate
            val targetLat: Double
            val targetLng: Double
            if (speed > 0.5f) {
                val elapsed = 0.0
                val conf = ((50f - acc) / 25f).toDouble().coerceIn(0.0, 1.0)
                val dist = speed.toDouble() * (elapsed + 0.4).coerceIn(0.0, 3.0) * conf
                val proj = testProject(rawLat, rawLng, heading.toDouble(), dist)
                targetLat = proj.first
                targetLng = proj.second
            } else {
                targetLat = rawLat
                targetLng = rawLng
            }

            val loc = NavigationLocation(
                latitude = rawLat,
                longitude = rawLng,
                speedMps = speed,
                bearing = heading,
                accuracy = acc,
                timeMs = timeMs,
                elapsedRealtimeNanos = timeMs * 1_000_000L
            )
            val target = CameraTarget(loc, null, null)

            // Update new controller
            val pos = controller.update(target)

            // Update old simulation
            val dt = if (i == 1) 0.042 else dtMs / 1000.0
            oldUpdate(targetLat, targetLng, heading, speed, dt, acc)

            // Compare outputs
            assertEquals("Mismatch in Lat at step $i", oldCamLat, pos.center.lat, 1e-6)
            assertEquals("Mismatch in Lng at step $i", oldCamLng, pos.center.lng, 1e-6)
            assertEquals("Mismatch in Heading at step $i", oldCamHdg, pos.heading, 1e-2f)

            timeMs += dtMs
            timeSource.advance(dtMs)
        }
    }
}
