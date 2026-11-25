package io.github.saltmango.navbeam.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Repository providing access to local SQLite data (saved locations, saved routes, rides).
 * All rider data is stored 100% locally on-device.
 */
class SyncRepository private constructor(private val context: Context) {
    companion object {
        private const val TAG = "SyncRepository"
        @Volatile private var instance: SyncRepository? = null
        fun get(context: Context): SyncRepository =
            instance ?: synchronized(this) { instance ?: SyncRepository(context.applicationContext).also { instance = it } }
    }

    private val db = NavBeamDb.get(context)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bumped on every local data change so ViewModels reload. */
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private fun bump() { _revision.value++ }

    // ── Reads (local SQLite) ─────────────────────────────────────────────
    fun savedLocations() = db.savedLocations()
    fun savedRoutes() = db.savedRoutes()
    fun rides() = db.rides()

    // ── Mutations (local SQLite) ─────────────────────────────────────────
    fun addSavedRoute(r: SavedRoute) {
        val route = if (r.sid.isBlank()) r.copy(sid = NavBeamDb.newSid()) else r
        db.upsertSavedRoute(route); bump()
    }

    fun deleteSavedRoute(s: SavedRoute) {
        db.deleteSavedRouteBySid(s.sid); bump()
    }

    fun addSaved(name: String, lat: Double, lng: Double, note: String) {
        val s = SavedLocation(sid = NavBeamDb.newSid(), name = name, lat = lat, lng = lng, note = note)
        db.upsertSaved(s); bump()
    }

    fun renameSaved(s: SavedLocation, name: String, note: String) {
        val u = s.copy(name = name, note = note); db.upsertSaved(u); bump()
    }

    fun deleteSaved(s: SavedLocation) {
        db.deleteSavedBySid(s.sid); bump()
    }

    /**
     * Persist a finished ride (local SQLite only). Self-scopes on the repo's own IO scope so the
     * write survives even when called from ViewModel teardown.
     */
    fun addRide(r: Ride) {
        io.launch {
            val resolvedRide = if (r.startLocationName.isNullOrBlank()) {
                val resolvedName = runCatching {
                    val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
                    val addresses = geocoder.getFromLocation(r.startLat, r.startLng, 1)
                    addresses?.firstOrNull()?.let { addr ->
                        val street = addr.thoroughfare ?: addr.featureName
                        val city = addr.locality ?: addr.subAdminArea
                        if (street != null && city != null) "$street, $city"
                        else street ?: city
                    }
                }.getOrNull() ?: "Unknown Location"
                r.copy(startLocationName = resolvedName)
            } else {
                r
            }
            db.upsertRide(resolvedRide)
            bump()
        }
    }

    fun deleteRide(r: Ride) {
        db.deleteRideBySid(r.sid); bump()
    }
}
