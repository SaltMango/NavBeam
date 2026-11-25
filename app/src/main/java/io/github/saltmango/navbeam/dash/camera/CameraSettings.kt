package io.github.saltmango.navbeam.dash.camera

data class CameraSettings(
    val zoom: Int = 16,
    val headingUp: Boolean = true,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val followMode: Boolean = true
)
