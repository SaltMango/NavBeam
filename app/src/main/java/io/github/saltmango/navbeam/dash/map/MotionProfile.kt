package io.github.saltmango.navbeam.dash.map

/**
 * Motion profile for the projection session.
 *
 * Only the five values below are defined. The enum also acts as the key
 * into [FusedLocationProvider]'s pre-built [LocationRequest] map so that
 * profile transitions never allocate a new request object at runtime.
 *
 * Hysteresis entry/exit thresholds (enforced in DashViewModel):
 *   HIGHWAY  → enter ≥ 40 km/h   / exit < 35 km/h
 *   CITY     → enter ≥ 10 km/h   / exit < 8 km/h
 *   SLOW     → enter ≥ 2  km/h   / exit < 2 km/h for ≥ 10 s
 *   STOPPED  → enter < 2 km/h for ≥ 10–15 s / exit ≥ 2 km/h
 *   DISCONNECTED → projection link is down (no stream, passive GPS only)
 */
enum class MotionProfile {
    /** > 40 km/h — 4 FPS, 200 kbps, HIGH_ACCURACY, 1 s GPS. */
    HIGHWAY,
    /** 10–40 km/h — 3 FPS, 120 kbps, HIGH_ACCURACY, 2 s GPS. */
    CITY,
    /** 2–10 km/h — 2 FPS, 100 kbps, HIGH_ACCURACY, 4 s GPS. */
    SLOW,
    /** < 2 km/h ≥ 10 s — 1 FPS, 80 kbps, BALANCED_POWER, 10 s GPS. */
    STOPPED,
    /** Projection link down — 0 FPS, passive GPS only. */
    DISCONNECTED,
}
