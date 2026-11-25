package io.github.saltmango.navbeam.dash.camera

interface CameraController {
    fun getPosition(): CameraPosition
    fun isHeadingUp(): Boolean
    
    fun update(
        target: CameraTarget
    ): CameraPosition

    fun zoomIn()
    fun zoomOut()
    fun panBy(dx: Float, dy: Float)
    fun recenter()
    fun setHeadingUp(headingUp: Boolean)
    fun reset()
}
