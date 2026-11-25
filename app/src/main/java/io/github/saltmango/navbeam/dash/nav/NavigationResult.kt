package io.github.saltmango.navbeam.dash.nav

data class NavigationResult(
    val state: NavigationState,
    val event: NavigationEvent? = null
)
