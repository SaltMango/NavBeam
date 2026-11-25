package io.github.saltmango.navbeam.data

/**
 * Represents a destination for routing. 
 * Replaced the old "Share-only" location model with a direct coordinate-first model.
 */
data class SharedLocation(
    val name: String,
    val lat: Double,
    val lng: Double,
)

/** A destination the rider saved to navigate to again. Persisted in SQLite. */
data class SavedLocation(
    val id: Long = 0,
    val name: String,
    val lat: Double,
    val lng: Double,
    val note: String = "",
    val createdMs: Long = System.currentTimeMillis(),
    val sid: String = "",       // stable local UUID
)

/** A complete road route saved locally for 100% offline navigation. Persisted in SQLite. */
data class SavedRoute(
    val id: Long = 0,
    val name: String,
    val fromName: String,
    val toName: String,
    val fromLat: Double,
    val fromLng: Double,
    val toLat: Double,
    val toLng: Double,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val routeJson: String,           // Outbound route geometry & maneuvers JSON
    val returnRouteJson: String = "", // Return route geometry & maneuvers JSON
    val isDownloaded: Boolean = true,
    val createdMs: Long = System.currentTimeMillis(),
    val sid: String = "",
)
