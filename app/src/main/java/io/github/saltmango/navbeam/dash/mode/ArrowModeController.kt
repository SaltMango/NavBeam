package io.github.saltmango.navbeam.dash.mode

import io.github.saltmango.navbeam.dash.nav.GeoPoint
import kotlinx.coroutines.CoroutineScope

class ArrowModeController(
    private val navContext: NavigationModeContext
) : NavigationModeController {
    override fun start(scope: CoroutineScope) {
        navContext.enterNavigation(projection = true)
    }

    override fun stop() {
        navContext.stopNavigation()
    }

    override fun onLocationUpdated(lat: Double, lng: Double) {}
    override fun onRouteUpdated(geometry: List<GeoPoint>) {}
    override fun onDestinationUpdated(lat: Double, lng: Double) {}
}
