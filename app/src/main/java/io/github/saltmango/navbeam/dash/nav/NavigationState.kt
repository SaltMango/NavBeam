package io.github.saltmango.navbeam.dash.nav

enum class NavigationGpsStatus {
    GOOD,
    WEAK,
    LOST
}

data class RouteProgress(
    val progress: Double = 0.0,
    val remainingDistance: Double? = null,
    val etaSeconds: Int? = null,
    val etaArrivalMs: Long = 0L,
    val offRoute: Boolean = false,
    val recalculating: Boolean = false
)

data class ManeuverState(
    val nextManeuver: Maneuver? = null,
    val secondaryManeuver: Maneuver? = null
)

data class GpsState(
    val matchedLat: Double? = null,
    val matchedLng: Double? = null,
    val riderBearing: Float = 0f,
    val gpsStatus: NavigationGpsStatus = NavigationGpsStatus.LOST
)

data class Destination(
    val name: String,
    val latitude: Double,
    val longitude: Double
)

data class DestinationState(
    val destination: Destination? = null,
    val arrived: Boolean = false
)

data class NavigationState(
    val progress: RouteProgress = RouteProgress(),
    val maneuver: ManeuverState = ManeuverState(),
    val gps: GpsState = GpsState(),
    val destination: DestinationState = DestinationState()
)
