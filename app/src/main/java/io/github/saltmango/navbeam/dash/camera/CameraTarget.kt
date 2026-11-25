package io.github.saltmango.navbeam.dash.camera

import io.github.saltmango.navbeam.dash.nav.NavigationLocation

data class CameraTarget(
    val raw: NavigationLocation?,
    val matchedLat: Double?,
    val matchedLng: Double?,
    val nextTurnDistanceM: Double? = null
)
