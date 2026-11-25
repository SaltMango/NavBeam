package io.github.saltmango.navbeam.dash.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.github.saltmango.navbeam.util.Dbg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * GPS position wrapper that selects the best available provider at construction
 * time and applies a two-layer jitter filter before publishing fixes:
 *
 *   1. Legacy outlier gate (physics-based teleport / impossible-speed check).
 *   2. **Adaptive accuracy-scaled displacement gate** — skips updates whose
 *      displacement is below `max(profileThreshold, accuracy × 0.5)`, suppressing
 *      GPS noise that would trigger redundant redraws without any visible motion.
 *
 * The active [MotionProfile] is forwarded to the underlying provider via
 * [setProfile], which switches the GPS request parameters without allocating
 * new objects.
 *
 * Provider selection (evaluated once at construction):
 *   • [FusedLocationProvider] if Google Play Services is available and connected.
 *   • [DefaultLocationProvider] (platform LocationManager) otherwise.
 */
class LocationTracker(
    private val context: Context,
    scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "LocationTracker"
        /** Coarse GPS timestamp freshness threshold used by the legacy outlier gate. */
        private const val GPS_STALE_MS = 10_000L
    }

    // ── Provider selection ────────────────────────────────────────────────────
    private val provider: LocationProvider = run {
        val available = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        if (available) {
            Log.i(TAG, "Using FusedLocationProvider")
            FusedLocationProvider(context)
        } else {
            Log.i(TAG, "Play Services unavailable — using DefaultLocationProvider")
            DefaultLocationProvider(context)
        }
    }

    // ── Published state ───────────────────────────────────────────────────────
    private val _location = MutableStateFlow<Location?>(null)
    val location = _location.asStateFlow()

    // ── Outlier gate state ────────────────────────────────────────────────────
    private var rejectStreak = 0

    // ── Jitter filter state ───────────────────────────────────────────────────
    /** Profile-driven base threshold; updated on every [setProfile] call. */
    @Volatile private var profileThresholdM = 5.0f  // STOPPED default

    // ── Collection ────────────────────────────────────────────────────────────
    init {
        scope.launch {
            provider.locations.collect { loc ->
                val cur = _location.value
                if (acceptFix(cur, loc) && passesJitterGate(cur, loc)) {
                    _location.value = loc
                    Dbg.d(TAG) { "fix ${loc.provider} acc=${loc.accuracy} (${loc.latitude},${loc.longitude})" }
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() {
        android.util.Log.i("Location", "Location START")
        seedLastKnown()
        provider.start()
    }

    fun stop() {
        android.util.Log.i("Location", "Location STOP")
        provider.stop()
    }

    /**
     * Forward a profile transition to the underlying provider.
     * Also updates the displacement gate threshold for the new profile.
     * Idempotent — multiple calls with the same profile are free.
     */
    fun setProfile(profile: MotionProfile) {
        profileThresholdM = when (profile) {
            MotionProfile.HIGHWAY,
            MotionProfile.CITY         -> 1.5f
            MotionProfile.SLOW         -> 3.0f
            MotionProfile.STOPPED,
            MotionProfile.DISCONNECTED -> 5.0f
        }
        provider.setProfile(profile)
    }

    /** Best last-known fix without starting updates (for routing before connecting). */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? = try {
        _location.value
            ?: with(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager) {
                getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            }
    } catch (_: Exception) { null }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Adaptive accuracy-scaled displacement gate.
     *
     * Skips updates whose displacement from the last accepted fix is below:
     *   threshold = max(profileThresholdM, location.accuracy × 0.5)
     *
     * This prevents GPS noise from triggering redraws when the bike is
     * stationary, AND protects against reacting to phantom fixes when GPS
     * accuracy suddenly degrades to 15–20 m.
     *
     * Returns `true` (accept) if no previous fix exists (cold start).
     */
    private fun passesJitterGate(cur: Location?, loc: Location): Boolean {
        if (cur == null) return true
        // Allow updates at least once every 4 seconds to prevent the navigation engine/dash
        // from timing out and displaying "GPS lost" while the rider is stationary.
        val timeSinceLastMs = loc.time - cur.time
        if (timeSinceLastMs >= 4000L) {
            Dbg.d(TAG) { "jitter gate: allow update due to time freshness (${timeSinceLastMs}ms)" }
            return true
        }
        val threshold = maxOf(profileThresholdM, loc.accuracy * 0.5f)
        val displacement = cur.distanceTo(loc)
        if (displacement < threshold) {
            Dbg.d(TAG) { "jitter gate: skip displacement=${displacement}m threshold=${threshold}m" }
            return false
        }
        return true
    }

    /**
     * Physics-based outlier gate (unchanged from original LocationTracker).
     *
     * Rejects:
     *   • Stale NETWORK fixes while a fresh GPS fix is in hand.
     *   • Backwards-in-time fixes.
     *   • Impossibly fast jumps (> 85 m/s sustained).
     *   • Statistically implausible jumps given the fix speeds and accuracy radii.
     */
    private fun acceptFix(cur: Location?, loc: Location): Boolean {
        if (cur == null) { rejectStreak = 0; return true }
        val isGps = loc.provider == LocationManager.GPS_PROVIDER
        if (!isGps && cur.provider == LocationManager.GPS_PROVIDER &&
            loc.time - cur.time < GPS_STALE_MS
        ) return false
        if (loc.time < cur.time) return false
        val dt   = (loc.time - cur.time) / 1000.0
        val jump = cur.distanceTo(loc)
        if (dt > 0 && jump > 200f && jump / dt > 85.0) return reject()
        if (dt in 0.0..6.0 && rejectStreak < 3) {
            val plausibleSpeed = maxOf(cur.speed, loc.speed).coerceAtLeast(1f)
            val expected = plausibleSpeed * dt
            val noise    = (loc.accuracy + cur.accuracy)
            val gate     = expected + noise * 1.5 + 12f
            if (jump > gate && jump > 25f) return reject()
        }
        rejectStreak = 0
        return true
    }

    private fun reject(): Boolean { rejectStreak++; return false }

    @SuppressLint("MissingPermission")
    private fun seedLastKnown() {
        if (_location.value != null) return
        _location.value = lastKnown()
    }
}
