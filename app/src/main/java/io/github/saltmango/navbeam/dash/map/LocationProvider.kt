package io.github.saltmango.navbeam.dash.map

import android.location.Location
import kotlinx.coroutines.flow.Flow

/**
 * Common contract for location providers.
 *
 * Two implementations exist:
 *   • [FusedLocationProvider]   — Google Play Services (preferred)
 *   • [DefaultLocationProvider] — platform LocationManager fallback
 *
 * The active provider is selected once in [LocationTracker] based on
 * Play Services availability and never swapped at runtime.
 */
interface LocationProvider {
    /** Hot [Flow] of accepted location fixes. Starts emitting after [start]. */
    val locations: Flow<Location>

    /** Begin requesting location updates. Safe to call multiple times. */
    fun start()

    /** Stop updates and free all resources. */
    fun stop()

    /**
     * Switch to the pre-configured [LocationRequest] for [profile].
     *
     * Implementations MUST be idempotent: if [profile] == currentProfile,
     * return immediately without re-registering the listener.
     * This is the ONLY path that changes the underlying request parameters;
     * no raw interval/accuracy values are ever passed in.
     */
    fun setProfile(profile: MotionProfile)
}
