package io.github.saltmango.navbeam.dash.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import io.github.saltmango.navbeam.util.Dbg
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [LocationProvider] implementation backed by Google's FusedLocationProviderClient.
 *
 * Five [LocationRequest] instances are built at construction time — one per
 * [MotionProfile] — and never rebuilt at runtime.  [setProfile] swaps the
 * active request by removing the old callback and re-registering with the
 * pre-built one for the new profile.  If the profile is unchanged the call
 * is a no-op (zero allocation, zero driver interaction).
 *
 * For BALANCED_POWER / PASSIVE profiles, [LocationRequest.Builder.setWaitForAccurateLocation]
 * is set to `false` to prevent the system from stalling updates while it
 * waits for a high-precision GPS fix that isn't needed.
 */
class FusedLocationProvider(context: Context) : LocationProvider {

    companion object {
        private const val TAG = "FusedLocationProvider"
    }

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    // ── Pre-built request map ─────────────────────────────────────────────────
    private val requests: Map<MotionProfile, LocationRequest> = mapOf(
        MotionProfile.HIGHWAY to buildRequest(
            intervalMs      = 500L,
            minIntervalMs   = 250L,
            priority        = Priority.PRIORITY_HIGH_ACCURACY,
            maxDelayMs      = 0L,
            minDistanceM    = 0f,
            waitForAccurate = true,
        ),
        MotionProfile.CITY to buildRequest(
            intervalMs      = 1_000L,
            minIntervalMs   = 500L,
            priority        = Priority.PRIORITY_HIGH_ACCURACY,
            maxDelayMs      = 0L,
            minDistanceM    = 0f,
            waitForAccurate = true,
        ),
        MotionProfile.SLOW to buildRequest(
            intervalMs      = 1_000L,
            minIntervalMs   = 500L,
            priority        = Priority.PRIORITY_HIGH_ACCURACY,
            maxDelayMs      = 0L,
            minDistanceM    = 0f,
            waitForAccurate = false,
        ),
        MotionProfile.STOPPED to buildRequest(
            intervalMs      = 2_000L,
            minIntervalMs   = 1_000L,
            priority        = Priority.PRIORITY_HIGH_ACCURACY,
            maxDelayMs      = 500L,
            minDistanceM    = 0f,
            waitForAccurate = false,
        ),
        MotionProfile.DISCONNECTED to buildRequest(
            intervalMs      = Long.MAX_VALUE,
            minIntervalMs   = Long.MAX_VALUE,
            priority        = Priority.PRIORITY_PASSIVE,
            maxDelayMs      = 0L,
            minDistanceM    = 0f,
            waitForAccurate = false,
        ),
    )

    // ── Mutable state ─────────────────────────────────────────────────────────
    @Volatile private var currentProfile: MotionProfile? = null
    @Volatile private var activeCallback: LocationCallback? = null

    // ── Flow ──────────────────────────────────────────────────────────────────
    override val locations: Flow<Location> = callbackFlow {
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    Dbg.i("Location") { "GPS update: $it" }
                    trySend(it)
                }
            }
        }
        activeCallback = cb
        awaitClose {
            client.removeLocationUpdates(cb)
            activeCallback = null
        }
    }

    override fun start() {
        val cb = activeCallback ?: return
        Log.i(TAG, "Starting updates (profile=${currentProfile ?: MotionProfile.STOPPED})")
        registerCallback(cb, currentProfile ?: MotionProfile.STOPPED)
    }

    override fun stop() {
        activeCallback?.let {
            Log.i(TAG, "Stopping updates")
            client.removeLocationUpdates(it)
        }
        currentProfile = null
    }

    override fun setProfile(profile: MotionProfile) {
        if (profile == currentProfile) return          // idempotent — no driver interaction
        val cb = activeCallback ?: return              // not yet started
        currentProfile = profile
        client.removeLocationUpdates(cb)
        if (profile != MotionProfile.DISCONNECTED) {
            registerCallback(cb, profile)
        }
        Log.d(TAG, "Profile → $profile")
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun registerCallback(cb: LocationCallback, profile: MotionProfile) {
        val request = requests[profile] ?: requests[MotionProfile.STOPPED]!!
        runCatching {
            client.requestLocationUpdates(request, cb, Looper.getMainLooper())
        }.onFailure {
            Log.w(TAG, "requestLocationUpdates failed for $profile: ${it.message}")
        }
    }

    private fun buildRequest(
        intervalMs: Long,
        minIntervalMs: Long,
        priority: Int,
        maxDelayMs: Long,
        minDistanceM: Float,
        waitForAccurate: Boolean,
    ): LocationRequest = LocationRequest.Builder(priority, intervalMs)
        .setMinUpdateIntervalMillis(minIntervalMs)
        .setMaxUpdateDelayMillis(maxDelayMs)
        .setMinUpdateDistanceMeters(minDistanceM)
        .setWaitForAccurateLocation(waitForAccurate)
        .build()
}
