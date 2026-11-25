# System Architecture: Decoupled Navigation, Camera Control, & Video Streaming

This document describes the decoupled, platform-independent architecture introduced in Phase 1, Phase 2, and Phase 3.

```
                  ┌────────────────────┐
                  │  Location Tracker  │
                  └─────────┬──────────┘
                            │ (Location Update)
                            ▼
                  ┌────────────────────┐
                  │  NavigationEngine  │
                  └─────────┬──────────┘
                            │ (NavigationResult: State & Event)
                            ▼
                  ┌────────────────────┐
                  │   DashViewModel    │
                  └────┬───────────┬───┘
                       │           │
       (CameraTarget)  │           │  (Bitmap / Rendered Frame)
                       ▼           ▼
        ┌──────────────────┐   ┌───────────────────┐
        │ CameraController │   │   FrameStreamer   │
        └────────┬─────────┘   └─────────┬─────────┘
                 │ (CameraPosition)      │ (RTP packet stream)
                 ▼                       ▼
        ┌──────────────────┐   ┌───────────────────┐
        │   MapRenderer    │   │    DashSession    │
        └──────────────────┘   └───────────────────┘
```

---

## 1. Design Philosophy
- **100% Platform Independence**: Core calculations (route progress, ETA smoothing, coordinates snapping, camera dead-reckoning, manual panning, idle timeout) are implemented in pure Kotlin. They do not depend on the Android SDK, `Context`, `Location`, or `CoroutineScope` APIs.
- **Deterministic and Testable**: Time is decoupled via the `TimeSource` interface. This allows reproducing any edge-case transition, GPS timeout, or frame interpolation in local JVM unit tests.
- **Strict Layer Separation**:
  - `NavigationEngine` owns route tracking, snaps, and rerouting triggers.
  - `CameraController` owns predictive extrapolation (dead-reckoning), manual pan states, and camera easing/smoothing.
  - `FrameStreamer` wraps the H.264 video pipeline, MediaCodec hardware encoding, software fallbacks, and RTP packetization.

---

## 2. Phase 1: Navigation Engine (`NavigationEngine`)

### Responsibilities
- Track progress along the compiled OSRM route.
- Interpolate remaining distances and smooth out OSRM/Google route ETA fluctuations using a rolling average.
- Snap user coordinates to the closest matching segment of the route.
- Detect off-route deviation (distance > 150m, or distance > 70m with heading error > 50°) and trigger a reroute event after a 2-second debounce window.
- Monitor GPS signal status (transitions to `WEAK` if accuracy > 30m; transitions to `LOST` if no fixes arrive for 6 seconds).

### Types & Data Flow
All navigation types are located in `io.github.saltmango.navbeam.dash.nav`:
- `NavigationLocation`: Pure Kotlin GPS data.
- `CompiledRoute`: Represents coordinates polyline, instructions, and precomputed cumulative segment distances.
- `NavigationState`: Immutable snapshot of navigation values (`RouteProgress`, `ManeuverState`, `GpsState`, `DestinationState`).
- `NavigationEvent`: One-shot notifications emitted during an update tick (`ManeuverAnnounce`, `Arrived`, `RequestReroute`).
- `NavigationResult`: Encapsulates the updated `NavigationState` and an optional `NavigationEvent`.

### Interface
```kotlin
interface NavigationEngine {
    fun currentState(): NavigationState
    fun currentRoute(): CompiledRoute?
    fun onLocation(location: NavigationLocation?): NavigationResult
    fun setRoute(route: CompiledRoute?)
    fun setDestination(destination: Destination)
    fun clearDestination()
    fun clearRoute()
    fun reset()
}
```

---

## 3. Phase 2: Camera Controller (`CameraController`)

### Responsibilities
- Eases coordinates (`camLat`/`camLng`) and headings (`camHdg`) toward the target position so that the map glides smoothly at 4 FPS instead of jumping at 1 Hz GPS intervals.
- Performs predictive dead-reckoning (dead-reckoning projection) while moving using monotonic elapsed time since the last fix to keep the displayed position aligned with pipeline latency.
- Manages manual pan adjustments (joystick navigation) and transitions the view out of `followMode`.
- Measures manual pan idle duration; automatically clears pan coordinates and restores `followMode` if the rider does not interact for 8 seconds.

### Types & Data Flow
All camera types are located in `io.github.saltmango.navbeam.dash.camera`:
- `CameraTarget`: Wraps the raw `NavigationLocation` and matched snaps (`matchedLat`/`matchedLng`).
- `CameraPosition`: Immutable output coordinates (`center: GeoPoint`), zoom, heading, pan, and follow mode.
- `CameraSettings`: Persistent camera states like target zoom or heading alignment.

### Interface
```kotlin
interface CameraController {
    fun getPosition(): CameraPosition
    fun isHeadingUp(): Boolean
    fun update(target: CameraTarget): CameraPosition
    fun zoomIn()
    fun zoomOut()
    fun panBy(dx: Float, dy: Float)
    fun recenter()
    fun setHeadingUp(headingUp: Boolean)
    fun reset()
}
```

---

## 4. Phase 3: Frame Streamer (`FrameStreamer`)

### Responsibilities
- Encapsulates the H.264 video pipeline, MediaCodec hardware encoding, software fallbacks, and RTP packetization.
- Dynamically configures bitrates based on motion profile and thermal throttling.
- Tracks monotonic presentation timestamps (PTS) internally based on rendering frame intervals.
- Protects thread-safety by encapsulating all encoding/drain operations inside a single-threaded dispatcher (`AppDispatchers.encoder`).
- Implements self-recovery: rebuilds the H.264 encoder on SPS profile mismatch (switching to software fallback mode) and retries up to 3 times on MediaCodec crashes before propagating fatal exceptions.

### Types & Data Flow
All video/encoding types are located in `io.github.saltmango.navbeam.dash.video`:
- `StreamerConfig`: Encapsulates startup configuration (`session`, `initialBitrate`, `initialFrameDurationMs`, `width`, `height`, `forceSoftware`).
- `StreamerMetrics`: Exposes diagnostics (encoder durations, skip counts, and RTP latency).
- `StreamFailureListener`: Reports fatal stream crashes back to the ViewModel to handle application-level responses.

### Interface
```kotlin
interface FrameStreamer {
    fun start(config: StreamerConfig)
    suspend fun submitFrame(bitmap: Bitmap)
    fun setFrameDuration(durationMs: Long)
    fun stop()
    fun updateBitrate(bitrate: Int)
    fun setFailureListener(listener: StreamFailureListener)
    fun getMetrics(): StreamerMetrics
}
```

---

## 5. Time Abstraction (`TimeSource`)
To keep the engine and camera controller deterministic during unit tests, they interact with time through the `TimeSource` interface:
```kotlin
interface TimeSource {
    fun wallTimeMs(): Long         // System time for ETAs/Pan timeouts
    fun monotonicNanos(): Long     // Monotonic clock for dead-reckoning dt
}
```

- **Production**: Wraps `System.currentTimeMillis()` and `SystemClock.elapsedRealtimeNanos()`.
- **Testing**: Managed via `FakeTimeSource` which allows advancing time deterministically.

---

## 6. Verification & Testing

### Running Tests
Execute the Kotlin unit test task using Gradle:
```bash
./gradlew testDebugUnitTest
```

### Test Suites
1. **`NavigationEngineTest`**:
   - Tests snapped route matching and remaining distance updates.
   - Verifies transition to `NavigationGpsStatus.LOST` after elapsed periods.
   - Asserts that off-route coordinates trigger a `NavigationEvent.RequestReroute` event after the 2-second debounce interval.
2. **`CameraControllerTest`**:
   - **Timeout Test**: Asserts that manual pan offsets remain active and then recenter after 8 seconds of simulated idle time.
   - **Regression Test**: Simulates 100 updates of a mock GPX ride side-by-side through the old `DashViewModel` camera math simulation and the new `DefaultCameraController` implementation. Verifies that the outputs match exactly down to $\le 0.1\text{ meters}$ in coordinates and $\le 0.01^{\circ}$ in heading.
3. **`FrameStreamerTest`**:
   - **Basic Streaming & PTS**: Verifies correct startup, encoding callbacks, and shutdown releasing of encoder resources.
   - **Failure Recovery & Listener**: Verifies that encoder failures trigger up to 3 rebuilding retries before reporting fatal crashes to the callback listener.

