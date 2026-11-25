package io.github.saltmango.navbeam.dash.nav

data class NavigationLocation(
    val latitude: Double,
    val longitude: Double,
    val speedMps: Float,
    val bearing: Float,
    val accuracy: Float,
    val timeMs: Long,
    val elapsedRealtimeNanos: Long
)
