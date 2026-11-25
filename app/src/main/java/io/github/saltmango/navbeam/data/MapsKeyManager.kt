package io.github.saltmango.navbeam.data

import android.content.Context
import android.os.Bundle
import com.google.android.libraries.places.api.Places
import com.google.android.gms.maps.MapsInitializer
import io.github.saltmango.navbeam.BuildConfig

object MapsKeyManager {
    /**
     * Gets the active Google Maps API key: either user-configured or build-time fallback.
     */
    fun getKey(context: Context): String {
        val userKey = SettingsPrefs.getMapsApiKey(context)
        return userKey.ifBlank { BuildConfig.MAPS_API_KEY }
    }

    /**
     * Applies the active key by injecting it into application metadata (for Google Maps SDK)
     * and initializing Places and Maps SDKs dynamically.
     */
    fun applyKey(context: Context) {
        val key = getKey(context)
        if (key.isBlank()) return

        val appContext = context.applicationContext

        // 1. Inject key into ApplicationInfo metadata so Google Maps SDK reads it
        try {
            val info = appContext.applicationInfo
            if (info.metaData == null) {
                info.metaData = Bundle()
            }
            info.metaData.putString("com.google.android.geo.API_KEY", key)
        } catch (e: Exception) {
            android.util.Log.e("MapsKeyManager", "Failed to override com.google.android.geo.API_KEY metadata", e)
        }

        // 2. Initialize Places SDK dynamically
        try {
            if (!Places.isInitialized()) {
                Places.initialize(appContext, key)
            }
        } catch (e: Exception) {
            android.util.Log.e("MapsKeyManager", "Failed to initialize Places SDK", e)
        }

        // 3. Initialize Maps SDK (MapsInitializer)
        try {
            MapsInitializer.initialize(appContext)
        } catch (e: Exception) {
            android.util.Log.e("MapsKeyManager", "Failed to initialize Maps SDK", e)
        }
    }
}
