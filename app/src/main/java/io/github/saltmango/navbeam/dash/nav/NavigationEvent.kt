package io.github.saltmango.navbeam.dash.nav

sealed interface NavigationEvent {
    data class ManeuverAnnounce(
        val maneuver: Maneuver?,
        val distanceM: Double,
        val remainingM: Double
    ) : NavigationEvent
    
    data class Arrived(
        val remainingM: Double
    ) : NavigationEvent

    data class RequestReroute(
        val location: NavigationLocation,
        val destination: Destination
    ) : NavigationEvent
}
