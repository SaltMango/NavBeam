package io.github.saltmango.navbeam.dash.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import io.github.saltmango.navbeam.util.Dbg
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Platform [LocationManager] fallback for devices without Google Play Services
 * (e.g. GrapheneOS with sandboxed Play Services disabled).
 *
 * Uses GPS_PROVIDER for moving profiles and PASSIVE_PROVIDER for DISCONNECTED,
 * mirroring the behaviour of [FusedLocationProvider] as closely as the platform
 * API allows.  Interval/distance parameters are mapped from [MotionProfile].
 */
class DefaultLocationProvider(context: Context) : LocationProvider {

    companion object {
        private const val TAG = "DefaultLocationProvider"
    }

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile private var currentProfile: MotionProfile? = null
    @Volatile private var activeListener: LocationListener? = null

    override val locations: Flow<Location> = callbackFlow {
        val listener = LocationListener { loc ->
            Dbg.i("Location") { "GPS update: $loc" }
            trySend(loc)
        }
        activeListener = listener
        awaitClose {
            lm.removeUpdates(listener)
            activeListener = null
        }
    }

    override fun start() {
        val listener = activeListener ?: return
        Log.i(TAG, "Starting platform updates (profile=${currentProfile ?: MotionProfile.STOPPED})")
        registerListener(listener, currentProfile ?: MotionProfile.STOPPED)
    }

    override fun stop() {
        activeListener?.let {
            Log.i(TAG, "Stopping platform updates")
            lm.removeUpdates(it)
        }
        currentProfile = null
    }

    override fun setProfile(profile: MotionProfile) {
        if (profile == currentProfile) return
        val listener = activeListener ?: return
        currentProfile = profile
        lm.removeUpdates(listener)
        if (profile != MotionProfile.DISCONNECTED) {
            registerListener(listener, profile)
        }
        Log.d(TAG, "Profile → $profile (platform fallback)")
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun registerListener(listener: LocationListener, profile: MotionProfile) {
        val (provider, intervalMs, minDistM) = profileParams(profile)
        val enabledProviders = lm.allProviders
        val target = if (provider in enabledProviders) provider
                     else LocationManager.GPS_PROVIDER.takeIf { it in enabledProviders }
                        ?: LocationManager.NETWORK_PROVIDER.takeIf { it in enabledProviders }
                        ?: return  // nothing available

        runCatching {
            lm.requestLocationUpdates(target, intervalMs, minDistM, listener, Looper.getMainLooper())
        }.onFailure {
            Log.w(TAG, "requestLocationUpdates failed for $profile / $target: ${it.message}")
        }
    }

    /** Map [MotionProfile] → (provider, minTimeMs, minDistM). */
    private fun profileParams(profile: MotionProfile): Triple<String, Long, Float> = when (profile) {
        MotionProfile.HIGHWAY      -> Triple(LocationManager.GPS_PROVIDER,     500L,   0f)
        MotionProfile.CITY         -> Triple(LocationManager.GPS_PROVIDER,     1_000L,  0f)
        MotionProfile.SLOW         -> Triple(LocationManager.GPS_PROVIDER,     1_000L,  0f)
        MotionProfile.STOPPED      -> Triple(LocationManager.GPS_PROVIDER,     2_000L,  0f)
        MotionProfile.DISCONNECTED -> Triple(LocationManager.PASSIVE_PROVIDER, 0L,      0f)
    }
}
