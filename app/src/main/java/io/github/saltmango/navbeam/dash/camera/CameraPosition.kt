package io.github.saltmango.navbeam.dash.camera

import io.github.saltmango.navbeam.dash.nav.GeoPoint

data class CameraPosition(
    val center: GeoPoint,
    val heading: Float,
    val zoom: Int,
    val panX: Float,
    val panY: Float,
    val followMode: Boolean
)
